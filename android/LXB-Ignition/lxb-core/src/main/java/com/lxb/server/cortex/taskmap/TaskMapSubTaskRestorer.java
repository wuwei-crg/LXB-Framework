package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.CortexFsmEngine;

import java.util.ArrayList;
import java.util.List;

public final class TaskMapSubTaskRestorer {

    private TaskMapSubTaskRestorer() {}

    public static List<CortexFsmEngine.SubTask> restore(TaskMap map) {
        List<CortexFsmEngine.SubTask> out = new ArrayList<CortexFsmEngine.SubTask>();
        if (map == null) {
            return out;
        }
        for (TaskMap.Segment segment : map.segments) {
            CortexFsmEngine.SubTask st = new CortexFsmEngine.SubTask();
            st.id = segment.subTaskId != null && !segment.subTaskId.isEmpty()
                    ? segment.subTaskId
                    : segment.segmentId;
            st.description = segment.subTaskDescription != null ? segment.subTaskDescription : "";
            st.mode = "single";
            st.appHint = segment.packageName != null ? segment.packageName : "";
            st.successCriteria = segment.successCriteria != null ? segment.successCriteria : "";
            if (segment.inputs != null) {
                st.inputs.addAll(segment.inputs);
            }
            if (segment.outputs != null) {
                st.outputs.addAll(segment.outputs);
            }
            out.add(st);
        }
        return out;
    }
}
