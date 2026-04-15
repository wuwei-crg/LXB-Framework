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
    public void resolveExistingKeyHash_fallsBackToCompatibleLegacyRecord() throws Exception {
        File dir = Files.createTempDirectory("taskmap-store-compat").toFile();
        TaskMapStore store = new TaskMapStore(dir);

        TaskRouteRecord legacy = new TaskRouteRecord();
        legacy.taskKeyHash = "legacy-hash";
        legacy.source = "schedule";
        legacy.sourceId = "schedule-1";
        legacy.sourceConfigHash = "cfg-1";
        legacy.packageName = "com.demo";
        legacy.rootTask = "open demo";
        legacy.status = "success";
        legacy.createdAtMs = 2L;
        TaskRouteRecord.Action action = new TaskRouteRecord.Action();
        action.actionId = "a0001";
        action.subTaskId = "default";
        action.op = "TAP";
        action.args.addAll(Arrays.asList("1", "2"));
        action.locator.put("resource_id", "legacy_button");
        legacy.actions.add(action);
        Assert.assertTrue(store.saveLatestSuccessRecord(legacy));

        TaskRouteKey canonical = TaskRouteKey.build(
                "schedule",
                "schedule-1",
                "cfg-1",
                "com.demo",
                "open demo",
                "new playbook text",
                "manual"
        );
        Assert.assertNotEquals("legacy-hash", canonical.taskKeyHash);
        Assert.assertEquals("legacy-hash", store.resolveExistingKeyHash(canonical, "open demo"));
    }
}
