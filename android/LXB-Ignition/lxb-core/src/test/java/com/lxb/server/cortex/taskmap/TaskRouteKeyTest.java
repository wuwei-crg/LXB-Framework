package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

public class TaskRouteKeyTest {
    @Test
    public void routeKeyCarriesExplicitRouteIdOnly() {
        TaskRouteKey key = TaskRouteKey.route("schedule", "sid1", "com.demo", "open app", "pb", "manual", "schedule:sid1");
        Assert.assertEquals("schedule:sid1", key.taskKeyHash);
        Assert.assertEquals("schedule:sid1", key.asMap().get("route_id"));
    }
}
