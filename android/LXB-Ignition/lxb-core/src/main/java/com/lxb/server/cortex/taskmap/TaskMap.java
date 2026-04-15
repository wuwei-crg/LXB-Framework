package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskMap {

    public String schema = "task_map.v1";
    public String taskKeyHash = "";
    public String source = "";
    public String sourceId = "";
    public String sourceConfigHash = "";
    public String packageName = "";
    public String packageLabel = "";
    public String createdFromTaskId = "";
    public long createdAtMs;
    public String mode = "ai";
    public String lastReplayStatus = "unused";
    public boolean finishAfterReplay = false;
    public final List<Segment> segments = new ArrayList<Segment>();

    public static final class Segment {
        public String segmentId = "";
        public String subTaskId = "";
        public int subTaskIndex;
        public String subTaskDescription = "";
        public String subTaskDescriptionHash = "";
        public String successCriteria = "";
        public String packageName = "";
        public String packageLabel = "";
        public final List<String> inputs = new ArrayList<String>();
        public final List<String> outputs = new ArrayList<String>();
        public final List<Step> steps = new ArrayList<Step>();

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("segment_id", segmentId);
            out.put("sub_task_id", subTaskId);
            out.put("sub_task_index", subTaskIndex);
            out.put("sub_task_description", subTaskDescription);
            out.put("sub_task_description_hash", subTaskDescriptionHash);
            out.put("success_criteria", successCriteria);
            out.put("package_name", packageName);
            out.put("package_label", packageLabel);
            out.put("inputs", new ArrayList<String>(inputs));
            out.put("outputs", new ArrayList<String>(outputs));
            List<Object> rows = new ArrayList<Object>();
            for (Step step : steps) {
                rows.add(step.toMap());
            }
            out.put("steps", rows);
            return out;
        }
    }

    public static final class Step {
        public String stepId = "";
        public String sourceActionId = "";
        public String op = "";
        public final List<String> args = new ArrayList<String>();
        public Map<String, Object> locator = new LinkedHashMap<String, Object>();
        public Map<String, Object> containerProbe = new LinkedHashMap<String, Object>();
        public final List<Object> tapPoint = new ArrayList<Object>();
        public Map<String, Object> swipe = new LinkedHashMap<String, Object>();
        public String fallbackPoint = "";
        public String semanticNote = "";
        public String expected = "";

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("step_id", stepId);
            out.put("source_action_id", sourceActionId);
            out.put("op", op);
            out.put("args", new ArrayList<String>(args));
            out.put("locator", new LinkedHashMap<String, Object>(locator));
            out.put("container_probe", new LinkedHashMap<String, Object>(containerProbe));
            out.put("tap_point", new ArrayList<Object>(tapPoint));
            out.put("swipe", new LinkedHashMap<String, Object>(swipe));
            out.put("fallback_point", fallbackPoint);
            out.put("semantic_note", semanticNote);
            out.put("expected", expected);
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
        out.put("package_name", packageName);
        out.put("package_label", packageLabel);
        out.put("created_from_task_id", createdFromTaskId);
        out.put("created_at_ms", createdAtMs);
        out.put("mode", mode);
        out.put("last_replay_status", lastReplayStatus);
        out.put("finish_after_replay", finishAfterReplay);
        List<Object> rows = new ArrayList<Object>();
        for (Segment segment : segments) {
            rows.add(segment.toMap());
        }
        out.put("segments", rows);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static TaskMap fromObject(Object obj) {
        if (!(obj instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) obj;
        TaskMap out = new TaskMap();
        out.schema = stringOrEmpty(map.get("schema"));
        if (out.schema.isEmpty()) {
            out.schema = "task_map.v1";
        }
        out.taskKeyHash = stringOrEmpty(map.get("task_key_hash"));
        out.source = stringOrEmpty(map.get("source"));
        out.sourceId = stringOrEmpty(map.get("source_id"));
        out.sourceConfigHash = stringOrEmpty(map.get("source_config_hash"));
        out.packageName = stringOrEmpty(map.get("package_name"));
        out.packageLabel = stringOrEmpty(map.get("package_label"));
        out.createdFromTaskId = stringOrEmpty(map.get("created_from_task_id"));
        out.createdAtMs = toLong(map.get("created_at_ms"), 0L);
        out.mode = stringOrEmpty(map.get("mode"));
        if (out.mode.isEmpty()) {
            out.mode = "ai";
        }
        out.lastReplayStatus = stringOrEmpty(map.get("last_replay_status"));
        if (out.lastReplayStatus.isEmpty()) {
            out.lastReplayStatus = "unused";
        }
        out.finishAfterReplay = toBoolean(map.get("finish_after_replay"), false);
        Object segObj = map.get("segments");
        if (segObj instanceof List) {
            for (Object item : (List<Object>) segObj) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) item;
                Segment seg = new Segment();
                seg.segmentId = stringOrEmpty(row.get("segment_id"));
                seg.subTaskId = stringOrEmpty(row.get("sub_task_id"));
                seg.subTaskIndex = (int) toLong(row.get("sub_task_index"), 0L);
                seg.subTaskDescription = stringOrEmpty(row.get("sub_task_description"));
                seg.subTaskDescriptionHash = stringOrEmpty(row.get("sub_task_description_hash"));
                seg.successCriteria = stringOrEmpty(row.get("success_criteria"));
                seg.packageName = stringOrEmpty(row.get("package_name"));
                seg.packageLabel = stringOrEmpty(row.get("package_label"));
                Object inputsObj = row.get("inputs");
                if (inputsObj instanceof List) {
                    for (Object v : (List<Object>) inputsObj) {
                        String s = stringOrEmpty(v);
                        if (!s.isEmpty()) seg.inputs.add(s);
                    }
                }
                Object outputsObj = row.get("outputs");
                if (outputsObj instanceof List) {
                    for (Object v : (List<Object>) outputsObj) {
                        String s = stringOrEmpty(v);
                        if (!s.isEmpty()) seg.outputs.add(s);
                    }
                }
                Object stepsObj = row.get("steps");
                if (stepsObj instanceof List) {
                    for (Object sObj : (List<Object>) stepsObj) {
                        if (!(sObj instanceof Map)) continue;
                        Map<String, Object> sRow = (Map<String, Object>) sObj;
                        Step step = new Step();
                        step.stepId = stringOrEmpty(sRow.get("step_id"));
                        step.sourceActionId = stringOrEmpty(sRow.get("source_action_id"));
                        step.op = stringOrEmpty(sRow.get("op"));
                        Object argsObj = sRow.get("args");
                        if (argsObj instanceof List) {
                            for (Object v : (List<Object>) argsObj) {
                                step.args.add(stringOrEmpty(v));
                            }
                        }
                        Object locatorObj = sRow.get("locator");
                        if (locatorObj instanceof Map) {
                            step.locator.putAll((Map<String, Object>) locatorObj);
                        }
                        Object containerProbeObj = sRow.get("container_probe");
                        if (containerProbeObj instanceof Map) {
                            step.containerProbe.putAll((Map<String, Object>) containerProbeObj);
                        }
                        Object tapPointObj = sRow.get("tap_point");
                        if (tapPointObj instanceof List) {
                            step.tapPoint.addAll((List<Object>) tapPointObj);
                        }
                        Object swipeObj = sRow.get("swipe");
                        if (swipeObj instanceof Map) {
                            step.swipe.putAll((Map<String, Object>) swipeObj);
                        }
                        step.fallbackPoint = stringOrEmpty(sRow.get("fallback_point"));
                        step.semanticNote = stringOrEmpty(sRow.get("semantic_note"));
                        step.expected = stringOrEmpty(sRow.get("expected"));
                        seg.steps.add(step);
                    }
                }
                out.segments.add(seg);
            }
        }
        return out;
    }

    public int stepCount() {
        int count = 0;
        for (Segment segment : segments) {
            count += segment.steps.size();
        }
        return count;
    }

    public boolean isUsable() {
        if (segments.isEmpty()) {
            return false;
        }
        for (Segment segment : segments) {
            if (segment.packageName == null || segment.packageName.trim().isEmpty()) {
                return false;
            }
            if (segment.steps.isEmpty()) {
                return false;
            }
        }
        return true;
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

    private static boolean toBoolean(Object o, boolean defVal) {
        if (o == null) return defVal;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return defVal;
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return defVal;
    }
}
