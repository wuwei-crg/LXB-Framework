package com.lxb.server.cortex.taskmap;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class TaskRouteRecorder {

    private final TaskRouteRecord record = new TaskRouteRecord();
    private int actionIndex = 0;

    public TaskRouteRecord begin(
            TaskRouteKey key,
            String taskId,
            String rootTask,
            String packageName,
            String packageLabel,
            String mode
    ) {
        record.taskKeyHash = key != null ? key.taskKeyHash : "";
        record.source = key != null ? key.source : "";
        record.sourceId = key != null ? key.sourceId : "";
        record.sourceConfigHash = key != null ? key.sourceConfigHash : "";
        record.taskMapMode = normalize(mode);
        record.taskId = taskId != null ? taskId : "";
        record.rootTask = rootTask != null ? rootTask : "";
        record.packageName = packageName != null ? packageName : "";
        record.packageLabel = packageLabel != null ? packageLabel : "";
        record.createdAtMs = System.currentTimeMillis();
        record.status = "failed";
        record.finalState = "";
        record.reason = "";
        record.actions.clear();
        actionIndex = 0;
        return record;
    }

    public String nextActionId() {
        actionIndex += 1;
        return String.format(Locale.ROOT, "a%04d", actionIndex);
    }

    public TaskRouteRecord.Action newAction(
            String actionId,
            String subTaskId,
            int turn,
            String op,
            String rawCommand
    ) {
        TaskRouteRecord.Action a = new TaskRouteRecord.Action();
        a.actionId = actionId != null ? actionId : nextActionId();
        a.subTaskId = subTaskId != null ? subTaskId : "";
        a.turn = turn;
        a.op = op != null ? op : "";
        a.rawCommand = rawCommand != null ? rawCommand : "";
        return a;
    }

    public void recordVisionSnapshot(TaskRouteRecord.Action action, Map<String, Object> vision) {
        if (action == null) {
            return;
        }
        action.vision.clear();
        if (vision != null) {
            action.vision.putAll(new LinkedHashMap<String, Object>(vision));
        }
    }

    public TaskRouteRecord.Action appendAction(TaskRouteRecord.Action action) {
        if (action != null) {
            record.actions.add(action);
        }
        return action;
    }

    public TaskRouteRecord finish(String status, String finalState, String reason) {
        record.status = normalizeStatus(status);
        record.finalState = finalState != null ? finalState : "";
        record.reason = reason != null ? reason : "";
        return record;
    }

    public TaskRouteRecord snapshot() {
        TaskRouteRecord copy = TaskRouteRecord.fromObject(record.toMap());
        return copy != null ? copy : record;
    }

    private static String normalize(String mode) {
        String v = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("ai".equals(v) || "manual".equals(v) || "off".equals(v)) {
            return v;
        }
        return "off";
    }

    private static String normalizeStatus(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if ("success".equals(v)) {
            return "success";
        }
        return "failed";
    }
}
