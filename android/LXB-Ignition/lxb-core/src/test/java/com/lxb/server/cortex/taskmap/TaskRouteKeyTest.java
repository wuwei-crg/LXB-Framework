package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

public class TaskRouteKeyTest {

    @Test
    public void build_sameInputs_sameHash() {
        TaskRouteKey a = TaskRouteKey.build("manual", "", "cfg", "com.demo", "open app", "pb", "ai");
        TaskRouteKey b = TaskRouteKey.build("manual", "", "cfg", "com.demo", "open app", "pb", "ai");
        Assert.assertEquals(a.taskKeyHash, b.taskKeyHash);
        Assert.assertEquals(a.taskKey, b.taskKey);
    }

    @Test
    public void build_changedConfig_differentHash() {
        TaskRouteKey a = TaskRouteKey.build("schedule", "sid1", "cfg-a", "com.demo", "open app", "", "ai");
        TaskRouteKey b = TaskRouteKey.build("schedule", "sid1", "cfg-b", "com.demo", "open app", "", "ai");
        Assert.assertNotEquals(a.taskKeyHash, b.taskKeyHash);
    }
}
