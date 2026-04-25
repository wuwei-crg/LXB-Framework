package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class TaskMapStoreTest {

    @Test
    public void store_roundTripRecordAndMap() throws Exception {
        File dir = Files.createTempDirectory("taskmap-store").toFile();
        TaskMapStore store = new TaskMapStore(dir);

        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash1";
        record.taskId = "task1";
        record.packageName = "com.demo";
        record.rootTask = "open demo";
        record.status = "success";
        record.createdAtMs = 1L;
        TaskRouteRecord.Action action = new TaskRouteRecord.Action();
        action.actionId = "a0001";
        action.subTaskId = "default";
        action.op = "TAP";
        action.args.add("100");
        action.args.add("200");
        action.locator.put("resource_id", "search_box");
        action.locator.put("class", "TextView");
        action.locator.put("fallback_point", java.util.Arrays.asList(100, 200));
        record.actions.add(action);

        Assert.assertTrue(store.saveLatestSuccessRecord(record));
        Assert.assertNotNull(store.loadLatestSuccessRecord("hash1"));

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "manual");
        Assert.assertNotNull(map);
        Assert.assertTrue(store.saveMap(map));
        Assert.assertTrue(store.hasMap("hash1"));
        Assert.assertNotNull(store.loadMap("hash1"));
    }

    @Test
    public void strictTaskIdsDoNotCrossReadCompatibleRecords() throws Exception {
        File dir = Files.createTempDirectory("taskmap-store-strict").toFile();
        TaskMapStore store = new TaskMapStore(dir);

        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "task-a";
        record.source = "schedule";
        record.sourceId = "schedule-1";
        record.packageName = "com.demo";
        record.rootTask = "open demo";
        record.status = "success";
        TaskRouteRecord.Action action = new TaskRouteRecord.Action();
        action.actionId = "a0001";
        action.subTaskId = "default";
        action.op = "TAP";
        action.args.addAll(Arrays.asList("1", "2"));
        action.locator.put("resource_id", "button_a");
        record.actions.add(action);
        Assert.assertTrue(store.saveLatestSuccessRecord(record));
        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "manual");
        Assert.assertTrue(store.saveMap(map));

        Assert.assertNull(store.loadMap("task-b"));
        Assert.assertFalse(store.hasMap("task-b"));
    }

    @Test
    public void rootFallsBackToMapDirSiblingTaskMapsInsteadOfTmp() throws Exception {
        String oldTaskMapsRoot = System.getProperty("lxb.task.maps.root");
        String oldMapDir = System.getProperty("lxb.map.dir");
        File state = Files.createTempDirectory("lxb-state").toFile();
        try {
            System.clearProperty("lxb.task.maps.root");
            System.setProperty("lxb.map.dir", new File(state, "maps").getAbsolutePath());
            TaskMapStore store = new TaskMapStore();
            Assert.assertEquals(new File(state, "task_maps").getAbsolutePath(), store.getRootDir().getAbsolutePath());
            Assert.assertFalse(store.getRootDir().getAbsolutePath().contains("/data/local/tmp"));
        } finally {
            restoreProperty("lxb.task.maps.root", oldTaskMapsRoot);
            restoreProperty("lxb.map.dir", oldMapDir);
        }
    }

    @Test
    public void saveMaterializedStep_replacesOnlyTargetStep() throws Exception {
        File dir = Files.createTempDirectory("taskmap-store-materialized").toFile();
        TaskMapStore store = new TaskMapStore(dir);

        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash2";
        map.packageName = "com.demo";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP;
        step.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_PENDING;
        segment.steps.add(step);
        map.segments.add(segment);
        Assert.assertTrue(store.saveMap(map));

        TaskMap.Step materialized = new TaskMap.Step();
        materialized.stepId = "s0001";
        materialized.op = "TAP";
        materialized.locator.put("resource_id", "publish_button");
        materialized.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_MATERIALIZED;
        materialized.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_ADAPTED;
        materialized.materializedAtMs = 12L;

        Assert.assertTrue(store.saveMaterializedStep("hash2", "seg0001", "s0001", materialized));
        TaskMap restored = store.loadMap("hash2");
        Assert.assertNotNull(restored);
        TaskMap.Step restoredStep = restored.segments.get(0).steps.get(0);
        Assert.assertEquals("publish_button", restoredStep.locator.get("resource_id"));
        Assert.assertEquals(PortableTaskRouteCodec.PORTABLE_KIND_MATERIALIZED, restoredStep.portableKind);
        Assert.assertEquals(PortableTaskRouteCodec.ADAPTATION_STATUS_ADAPTED, restoredStep.adaptationStatus);
    }

    @Test
    public void failedAdaptation_persistsAcrossReload() throws Exception {
        File dir = Files.createTempDirectory("taskmap-store-failed").toFile();
        TaskMapStore store = new TaskMapStore(dir);

        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash3";
        map.packageName = "com.demo";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP;
        step.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_PENDING;
        segment.steps.add(step);
        map.segments.add(segment);
        Assert.assertTrue(store.saveMap(map));

        Assert.assertTrue(store.markSemanticStepAdaptationFailed("hash3", "seg0001", "s0001", "no_match", 77L));

        TaskMap restored = store.loadMap("hash3");
        Assert.assertNotNull(restored);
        TaskMap.Step restoredStep = restored.segments.get(0).steps.get(0);
        Assert.assertEquals(PortableTaskRouteCodec.ADAPTATION_STATUS_FAILED, restoredStep.adaptationStatus);
        Assert.assertEquals("no_match", restoredStep.adaptationError);
        Assert.assertEquals(77L, restoredStep.adaptationAttemptedAtMs);
        Assert.assertTrue(restoredStep.tapPoint.isEmpty());
    }


    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
