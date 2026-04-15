package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskRouteRecord {

    public String schema = "task_route_record.v1";
    public String taskKeyHash = "";
    public String source = "";
    public String sourceId = "";
    public String sourceConfigHash = "";
    public String taskMapMode = "off";
    public String taskId = "";
    public String rootTask = "";
    public String packageName = "";
    public String packageLabel = "";
    public long createdAtMs;
    public String status = "failed";
    public String finalState = "";
    public String reason = "";
    public final List<Action> actions = new ArrayList<Action>();

    public static final class Action {
        public String actionId = "";
        public String subTaskId = "";
        public int turn;
        public String op = "";
        public final List<String> args = new ArrayList<String>();
        public String rawCommand = "";
        public Map<String, Object> locator = new LinkedHashMap<String, Object>();
        public Map<String, Object> containerProbe = new LinkedHashMap<String, Object>();
        public final List<Object> tapPoint = new ArrayList<Object>();
        public Map<String, Object> swipe = new LinkedHashMap<String, Object>();
        public Map<String, Object> vision = new LinkedHashMap<String, Object>();
        public String execResult = "";
        public String execError = "";
        public String createdPageSemantics = "";

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("action_id", actionId);
            out.put("sub_task_id", subTaskId);
            out.put("turn", turn);
            out.put("op", op);
            out.put("args", new ArrayList<String>(args));
            out.put("raw_command", rawCommand);
            out.put("locator", new LinkedHashMap<String, Object>(locator));
            out.put("container_probe", new LinkedHashMap<String, Object>(containerProbe));
            out.put("tap_point", new ArrayList<Object>(tapPoint));
            out.put("swipe", new LinkedHashMap<String, Object>(swipe));
            out.put("vision", new LinkedHashMap<String, Object>(vision));
            out.put("exec_result", execResult);
            out.put("exec_error", execError);
            out.put("created_page_semantics", createdPageSemantics);
            return out;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("schema", schema);
        out.put("task_key_hash", taskKeyHash);
        out.put("source", source);
        out.put("source_id", sourceId);
        out.put("source_config_hash", sourceConfigHash);
        out.put("task_map_mode", taskMapMode);
        out.put("task_id", taskId);
        out.put("root_task", rootTask);
        out.put("package_name", packageName);
        out.put("package_label", packageLabel);
        out.put("created_at_ms", createdAtMs);
        out.put("status", status);
        out.put("final_state", finalState);
        out.put("reason", reason);
        List<Object> rows = new ArrayList<Object>();
        for (Action a : actions) {
            rows.add(a.toMap());
        }
        out.put("actions", rows);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static TaskRouteRecord fromObject(Object obj) {
        if (!(obj instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) obj;
        TaskRouteRecord record = new TaskRouteRecord();
        record.schema = stringOrEmpty(map.get("schema"));
        if (record.schema.isEmpty()) {
            record.schema = "task_route_record.v1";
        }
        record.taskKeyHash = stringOrEmpty(map.get("task_key_hash"));
        record.source = stringOrEmpty(map.get("source"));
        record.sourceId = stringOrEmpty(map.get("source_id"));
        record.sourceConfigHash = stringOrEmpty(map.get("source_config_hash"));
        record.taskMapMode = stringOrEmpty(map.get("task_map_mode"));
        if (record.taskMapMode.isEmpty()) {
            record.taskMapMode = "off";
        }
        record.taskId = stringOrEmpty(map.get("task_id"));
        record.rootTask = stringOrEmpty(map.get("root_task"));
        record.packageName = stringOrEmpty(map.get("package_name"));
        record.packageLabel = stringOrEmpty(map.get("package_label"));
        record.createdAtMs = toLong(map.get("created_at_ms"), 0L);
        record.status = stringOrEmpty(map.get("status"));
        if (record.status.isEmpty()) {
            record.status = "failed";
        }
        record.finalState = stringOrEmpty(map.get("final_state"));
        record.reason = stringOrEmpty(map.get("reason"));
        Object actionsObj = map.get("actions");
        if (actionsObj instanceof List) {
            for (Object item : (List<Object>) actionsObj) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) item;
                Action a = new Action();
                a.actionId = stringOrEmpty(row.get("action_id"));
                a.subTaskId = stringOrEmpty(row.get("sub_task_id"));
                a.turn = (int) toLong(row.get("turn"), 0L);
                a.op = stringOrEmpty(row.get("op"));
                Object argsObj = row.get("args");
                if (argsObj instanceof List) {
                    for (Object v : (List<Object>) argsObj) {
                        a.args.add(stringOrEmpty(v));
                    }
                }
                a.rawCommand = stringOrEmpty(row.get("raw_command"));
                Object locatorObj = row.get("locator");
                if (locatorObj instanceof Map) {
                    a.locator.putAll((Map<String, Object>) locatorObj);
                }
                Object containerProbeObj = row.get("container_probe");
                if (containerProbeObj instanceof Map) {
                    a.containerProbe.putAll((Map<String, Object>) containerProbeObj);
                }
                Object tapPointObj = row.get("tap_point");
                if (tapPointObj instanceof List) {
                    a.tapPoint.addAll((List<Object>) tapPointObj);
                }
                Object swipeObj = row.get("swipe");
                if (swipeObj instanceof Map) {
                    a.swipe.putAll((Map<String, Object>) swipeObj);
                }
                Object visionObj = row.get("vision");
                if (visionObj instanceof Map) {
                    a.vision.putAll((Map<String, Object>) visionObj);
                }
                a.execResult = stringOrEmpty(row.get("exec_result"));
                a.execError = stringOrEmpty(row.get("exec_error"));
                a.createdPageSemantics = stringOrEmpty(row.get("created_page_semantics"));
                record.actions.add(a);
            }
        }
        return record;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long toLong(Object o, long defVal) {
        if (o == null) return defVal;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }
}
