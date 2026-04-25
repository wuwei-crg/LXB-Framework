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

    @Test
    public void fromObject_restoresPortableAdaptationFields() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP;
        step.semanticDescriptor.put("instruction", "点击发布帖子入口");
        step.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_FAILED;
        step.adaptationError = "no_match";
        step.materializedFromStepId = "s0001";
        step.materializedAtMs = 8L;
        segment.steps.add(step);
        map.segments.add(segment);

        TaskMap restored = TaskMap.fromObject(map.toMap());

        Assert.assertNotNull(restored);
        Assert.assertEquals(PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP, restored.segments.get(0).steps.get(0).portableKind);
        Assert.assertEquals("点击发布帖子入口", restored.segments.get(0).steps.get(0).semanticDescriptor.get("instruction"));
        Assert.assertEquals("no_match", restored.segments.get(0).steps.get(0).adaptationError);
        Assert.assertEquals(8L, restored.segments.get(0).steps.get(0).materializedAtMs);
    }
}
