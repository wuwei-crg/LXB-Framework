package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.cortex.taskmap.RuntimeLocatorBuilder;
import com.lxb.server.perception.PerceptionEngine;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LocatorSemanticsTest {

    @Test
    public void runtimeBuilder_assignsLocatorIndexForDuplicateButtons() throws Exception {
        List<DumpActionsParser.ActionNode> nodes = parse(buildDumpActionsPayload(
                actionNode(0, 0, 300, 80, "android.widget.LinearLayout", "", "root", ""),
                actionNode(0, 100, 120, 180, "android.widget.Button", "", "cta_button", "确定"),
                actionNode(140, 100, 260, 180, "android.widget.Button", "", "cta_button", "确定")
        ));

        Map<String, Object> locator = RuntimeLocatorBuilder.buildLocator(200, 140, nodes);

        Assert.assertEquals("cta_button", locator.get("resource_id"));
        Assert.assertEquals(1, ((Number) locator.get("locator_index")).intValue());
        Assert.assertEquals(2, ((Number) locator.get("locator_count")).intValue());
        Assert.assertFalse(locator.containsKey("parent_rid"));
    }

    @Test
    public void locatorResolver_usesLocatorIndexBeforeBoundsHint() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 300, 80, "android.widget.LinearLayout", "", "root", ""),
                actionNode(0, 100, 120, 180, "android.widget.Button", "", "cta_button", "确定"),
                actionNode(140, 100, 260, 180, "android.widget.Button", "", "cta_button", "确定")
        );
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload), new TraceLogger(64));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resource_id", "cta_button");
        map.put("class", "Button");
        map.put("locator_index", 1);
        map.put("locator_count", 2);
        map.put("bounds_hint", Arrays.asList(10, 100, 130, 180));

        ResolvedNode resolved = resolver.resolve(Locator.fromMap(map));

        Assert.assertEquals("locator_index", resolved.pickedStage);
        Assert.assertEquals(140, resolved.bounds.left);
        Assert.assertEquals(260, resolved.bounds.right);
    }

    @Test
    public void informativeResourceId_filtersGenericContainerIds() {
        Assert.assertFalse(Util.isInformativeResourceId("container"));
        Assert.assertFalse(Util.isInformativeResourceId("123456"));
        Assert.assertTrue(Util.isInformativeResourceId("login_confirm_button"));
    }

    @Test
    public void runtimeBuilder_buildsContainerProbeFromNearestContainer() throws Exception {
        List<DumpActionsParser.ActionNode> nodes = parse(buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode((byte) 8, 60, 320, 1020, 520, "android.widget.TextView", "???", "feed_item_title", ""),
                actionNode(40, 300, 1040, 520, "android.widget.LinearLayout", "", "feed_list", ""),
                actionNode(80, 340, 1000, 500, "android.widget.LinearLayout", "新闻", "feed_item", "条目")
        ));

        Map<String, Object> probe = RuntimeLocatorBuilder.buildContainerProbe(200, 420, nodes);

        Assert.assertEquals("feed_item", probe.get("resource_id"));
        Assert.assertEquals("新闻", probe.get("text"));
        Assert.assertEquals("条目", probe.get("content_desc"));
        Assert.assertEquals("LinearLayout", probe.get("class"));
        Assert.assertEquals("feed_list", probe.get("parent_rid"));
        Assert.assertTrue(probe.containsKey("bounds_hint"));
        Assert.assertTrue(probe.containsKey("center_hint"));
    }

    private static DumpActionsParser.ActionNode actionNode(
            byte type,
            int left,
            int top,
            int right,
            int bottom,
            String className,
            String text,
            String resourceId,
            String contentDesc
    ) {
        return new DumpActionsParser.ActionNode(
                type,
                new Bounds(left, top, right, bottom),
                className,
                text,
                resourceId,
                contentDesc
        );
    }

    private static DumpActionsParser.ActionNode actionNode(
            int left,
            int top,
            int right,
            int bottom,
            String className,
            String text,
            String resourceId,
            String contentDesc
    ) {
        return actionNode((byte) 1, left, top, right, bottom, className, text, resourceId, contentDesc);
    }

    private static List<DumpActionsParser.ActionNode> parse(byte[] payload) throws Exception {
        return DumpActionsParser.parse(payload);
    }

    private static byte[] buildDumpActionsPayload(DumpActionsParser.ActionNode... nodes) throws Exception {
        List<String> shortPool = new ArrayList<>();
        List<String> longPool = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        out.write((nodes.length >> 8) & 0xFF);
        out.write(nodes.length & 0xFF);

        for (DumpActionsParser.ActionNode node : nodes) {
            ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
            buf.put(node.type);
            buf.putShort((short) node.bounds.left);
            buf.putShort((short) node.bounds.top);
            buf.putShort((short) node.bounds.right);
            buf.putShort((short) node.bounds.bottom);
            buf.put((byte) shortPoolIndex(shortPool, node.className));
            buf.putShort((short) longPoolIndex(longPool, node.text));
            buf.put((byte) shortPoolIndex(shortPool, node.resourceId));
            buf.put((byte) shortPoolIndex(shortPool, node.contentDesc));
            buf.put(new byte[6]);
            out.write(buf.array());
        }

        out.write(shortPool.size());
        for (String value : shortPool) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            out.write(data.length);
            out.write(data);
        }

        out.write((longPool.size() >> 8) & 0xFF);
        out.write(longPool.size() & 0xFF);
        for (String value : longPool) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
            out.write(data);
        }
        return out.toByteArray();
    }

    private static int shortPoolIndex(List<String> pool, String value) {
        if (value == null || value.isEmpty()) {
            return 0xFF;
        }
        int idx = pool.indexOf(value);
        if (idx >= 0) {
            return idx;
        }
        pool.add(value);
        return pool.size() - 1;
    }

    private static int longPoolIndex(List<String> pool, String value) {
        if (value == null || value.isEmpty()) {
            return 0xFFFF;
        }
        int idx = pool.indexOf(value);
        if (idx >= 0) {
            return idx;
        }
        pool.add(value);
        return pool.size() - 1;
    }

    private static final class FakePerceptionEngine extends PerceptionEngine {
        private final byte[] payload;

        private FakePerceptionEngine(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public byte[] handleDumpActions(byte[] payload) {
            return this.payload;
        }
    }
}
