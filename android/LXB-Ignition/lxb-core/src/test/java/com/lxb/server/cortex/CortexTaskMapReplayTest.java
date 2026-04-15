package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;
import com.lxb.server.perception.PerceptionEngine;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CortexTaskMapReplayTest {

    @Test
    public void containerProbeReplay_requiresCurrentTapPointHitToMatchRecordedAttrs() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(40, 300, 1040, 520, "android.widget.LinearLayout", "", "feed_list", ""),
                actionNode(80, 340, 1000, 500, "android.widget.LinearLayout", "新闻", "feed_item", "条目")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay").toFile())
        );

        TaskMap.Step step = new TaskMap.Step();
        step.tapPoint.add(200);
        step.tapPoint.add(420);
        step.containerProbe.put("resource_id", "feed_item");
        step.containerProbe.put("class", "LinearLayout");
        step.containerProbe.put("parent_rid", "feed_list");

        Object resolved = invokeResolveTaskMapContainerProbePoint(engine, step);

        Assert.assertEquals("container_probe_hit", readField(resolved, "pickedStage"));
        Assert.assertEquals(200, ((Number) readField(resolved, "x")).intValue());
        Assert.assertEquals(420, ((Number) readField(resolved, "y")).intValue());
    }

    @Test
    public void containerProbeReplay_doesNotSearchGloballyForSimilarNode() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(60, 320, 420, 540, "android.widget.LinearLayout", "", "other_list", ""),
                actionNode(100, 360, 380, 500, "android.widget.LinearLayout", "其他", "other_item", "条目"),
                actionNode(520, 320, 1040, 540, "android.widget.LinearLayout", "", "feed_list", ""),
                actionNode(560, 360, 1000, 500, "android.widget.LinearLayout", "新闻", "feed_item", "条目")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay").toFile())
        );

        TaskMap.Step step = new TaskMap.Step();
        step.tapPoint.add(200);
        step.tapPoint.add(420);
        step.containerProbe.put("resource_id", "feed_item");
        step.containerProbe.put("class", "LinearLayout");
        step.containerProbe.put("parent_rid", "feed_list");

        try {
            invokeResolveTaskMapContainerProbePoint(engine, step);
            Assert.fail("expected attr mismatch");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IllegalStateException);
            Assert.assertTrue(String.valueOf(cause.getMessage()).contains("task_map_container_probe_attr_mismatch"));
        }
    }

    private static Object invokeResolveTaskMapContainerProbePoint(CortexFsmEngine engine, TaskMap.Step step) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("resolveTaskMapContainerProbePoint", TaskMap.Step.class);
        method.setAccessible(true);
        return method.invoke(engine, step);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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
        return new DumpActionsParser.ActionNode(
                (byte) 1,
                new Bounds(left, top, right, bottom),
                className,
                text,
                resourceId,
                contentDesc
        );
    }

    private static byte[] buildDumpActionsPayload(DumpActionsParser.ActionNode... nodes) throws Exception {
        List<String> shortPool = new ArrayList<String>();
        List<String> longPool = new ArrayList<String>();
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
