package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class TaskMapAssemblerTest {

    @Test
    public void assemble_dropsDeletedAndNonReplayable() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open something";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.locator.put("resource_id", "search_box");
        tap.locator.put("class", "TextView");
        tap.locator.put("fallback_point", java.util.Arrays.asList(100, 200));
        record.actions.add(tap);

        TaskRouteRecord.Action input = new TaskRouteRecord.Action();
        input.actionId = "a0002";
        input.subTaskId = "default";
        input.op = "INPUT";
        input.args.add("secret");
        record.actions.add(input);

        TaskRouteRecord.Action wait = new TaskRouteRecord.Action();
        wait.actionId = "a0003";
        wait.subTaskId = "default";
        wait.op = "WAIT";
        wait.args.add("500");
        record.actions.add(wait);

        Set<String> deleteIds = new HashSet<String>();
        deleteIds.add("a0003");
        TaskMap map = TaskMapAssembler.assemble(record, deleteIds, "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.segments.size());
        Assert.assertEquals(2, map.stepCount());
        Assert.assertEquals("TAP", map.segments.get(0).steps.get(0).op);
        Assert.assertEquals("search_box", map.segments.get(0).steps.get(0).locator.get("resource_id"));
        Assert.assertEquals("INPUT", map.segments.get(0).steps.get(1).op);
        Assert.assertEquals("secret", map.segments.get(0).steps.get(1).args.get(0));
    }

    @Test
    public void assemble_skipsTouchStepWithoutLocator() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open something";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.tapPoint.add(100);
        tap.tapPoint.add(200);
        record.actions.add(tap);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        Assert.assertEquals("TAP", map.segments.get(0).steps.get(0).op);
        Assert.assertEquals(2, map.segments.get(0).steps.get(0).tapPoint.size());
    }

    @Test
    public void assemble_restoresInputArgsFromRawCommandWhenRecordedArgsAreRedacted() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "search keyword";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.locator.put("resource_id", "search_box");
        tap.locator.put("class", "EditText");
        tap.locator.put("fallback_point", java.util.Arrays.asList(100, 200));
        record.actions.add(tap);

        TaskRouteRecord.Action input = new TaskRouteRecord.Action();
        input.actionId = "a0002";
        input.subTaskId = "default";
        input.op = "INPUT";
        input.rawCommand = "INPUT \"hello world\"";
        input.args.add("[redacted]");
        record.actions.add(input);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(2, map.stepCount());
        Assert.assertEquals("INPUT", map.segments.get(0).steps.get(1).op);
        Assert.assertEquals(Collections.singletonList("hello world"), map.segments.get(0).steps.get(1).args);
    }

    @Test
    public void assemble_keepsTapWithContainerProbeWhenLocatorMissing() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open feed";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.tapPoint.add(320);
        tap.tapPoint.add(640);
        tap.containerProbe.put("resource_id", "feed_item");
        tap.containerProbe.put("class", "LinearLayout");
        tap.containerProbe.put("parent_rid", "feed_list");
        record.actions.add(tap);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "manual");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        Assert.assertTrue(map.segments.get(0).steps.get(0).locator.isEmpty());
        Assert.assertEquals("feed_item", map.segments.get(0).steps.get(0).containerProbe.get("resource_id"));
        Assert.assertEquals(2, map.segments.get(0).steps.get(0).tapPoint.size());
    }

    @Test
    public void assemble_keepsSwipeReplayPayload() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "scroll once";
        record.taskId = "tid";

        TaskRouteRecord.Action swipe = new TaskRouteRecord.Action();
        swipe.actionId = "a0001";
        swipe.subTaskId = "default";
        swipe.op = "SWIPE";
        swipe.swipe.put("start", java.util.Arrays.asList(500, 1600));
        swipe.swipe.put("end", java.util.Arrays.asList(500, 400));
        swipe.swipe.put("duration_ms", 1500);
        record.actions.add(swipe);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        Assert.assertEquals("SWIPE", map.segments.get(0).steps.get(0).op);
        Assert.assertEquals(1500, ((Number) map.segments.get(0).steps.get(0).swipe.get("duration_ms")).intValue());
        Assert.assertEquals(2, ((java.util.List<?>) map.segments.get(0).steps.get(0).swipe.get("start")).size());
    }
}
