package com.lxb.server.cortex.taskmap;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskRouteKey {

    public final String source;
    public final String sourceId;
    public final String sourceConfigHash;
    public final String packageName;
    public final String userTask;
    public final String userPlaybook;
    public final String taskMapMode;
    public final String taskKey;
    public final String canonicalJson;
    public final String taskKeyHash;

    private TaskRouteKey(
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode,
            String routeId
    ) {
        this.source = normalize(source);
        this.sourceId = normalize(sourceId);
        this.sourceConfigHash = "";
        this.packageName = normalize(packageName);
        this.userTask = normalize(userTask);
        this.userPlaybook = normalize(userPlaybook);
        this.taskMapMode = normalize(taskMapMode);
        this.taskKey = normalize(routeId);
        this.canonicalJson = normalize(routeId);
        this.taskKeyHash = normalize(routeId);
    }

    public static TaskRouteKey route(
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode,
            String routeId
    ) {
        return new TaskRouteKey(source, sourceId, packageName, userTask, userPlaybook, taskMapMode, routeId);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("source", source);
        out.put("source_id", sourceId);
        out.put("package_name", packageName);
        out.put("user_task", userTask);
        out.put("user_playbook", userPlaybook);
        out.put("task_map_mode", taskMapMode);
        out.put("route_id", taskKeyHash);
        return out;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
