package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.CortexFsmEngine;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TaskMapSubTaskRestorerTest {

    @Test
    public void restore_preservesInputsOutputsAndMetadata() {
        TaskMap map = new TaskMap();
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.subTaskId = "sub_1";
        segment.subTaskDescription = "open chat";
        segment.successCriteria = "chat page visible";
        segment.packageName = "com.demo.app";
        segment.inputs.add("contact_name");
        segment.outputs.add("chat_opened");
        map.segments.add(segment);

        List<CortexFsmEngine.SubTask> subTasks = TaskMapSubTaskRestorer.restore(map);

        Assert.assertEquals(1, subTasks.size());
        CortexFsmEngine.SubTask subTask = subTasks.get(0);
        Assert.assertEquals("sub_1", subTask.id);
        Assert.assertEquals("open chat", subTask.description);
        Assert.assertEquals("single", subTask.mode);
        Assert.assertEquals("com.demo.app", subTask.appHint);
        Assert.assertEquals("chat page visible", subTask.successCriteria);
        Assert.assertEquals(1, subTask.inputs.size());
        Assert.assertEquals("contact_name", subTask.inputs.get(0));
        Assert.assertEquals(1, subTask.outputs.size());
        Assert.assertEquals("chat_opened", subTask.outputs.get(0));
    }
}
