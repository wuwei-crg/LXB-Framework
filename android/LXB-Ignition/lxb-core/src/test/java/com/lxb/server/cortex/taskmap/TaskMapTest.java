package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

public class TaskMapTest {

    @Test
    public void fromObject_restoresFinishAfterReplayFlag() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        map.finishAfterReplay = true;

        TaskMap restored = TaskMap.fromObject(map.toMap());

        Assert.assertNotNull(restored);
        Assert.assertTrue(restored.finishAfterReplay);
    }
}
