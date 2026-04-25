package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PortableTaskRouteCodec {

    public static final String PORTABLE_SCHEMA = "task_route_asset.v1";
    public static final String LEGACY_PORTABLE_SCHEMA = "task_route_portable.v1";
    public static final String PORTABLE_KIND_LOCAL_LOCATOR = "local_locator";
    public static final String PORTABLE_KIND_SEMANTIC_TAP = "semantic_tap";
    public static final String PORTABLE_KIND_MATERIALIZED = "materialized";
    public static final String ADAPTATION_STATUS_NONE = "none";
    public static final String ADAPTATION_STATUS_PENDING = "pending";
    public static final String ADAPTATION_STATUS_ADAPTED = "adapted";
    public static final String ADAPTATION_STATUS_FAILED = "failed";

    public static final class ExportResult {
        public Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        public int locatorStepCount;
        public int semanticStepCount;
    }

    public static final class ImportResult {
        public TaskMap map;
        public Map<String, Object> taskInfo = new LinkedHashMap<String, Object>();
        public int pendingAdaptationCount;
        public int executableImportCount;
    }

    private PortableTaskRouteCodec() {}

    public static ExportResult exportPortable(TaskMap map, TaskRouteRecord record) {
        if (map == null) {
            throw new IllegalArgumentException("task_map_missing");
        }
        ExportResult out = new ExportResult();
        Map<String, TaskRouteRecord.Action> actions = indexActions(record);
        Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        bundle.put("schema", PORTABLE_SCHEMA);

        Map<String, Object> taskInfo = buildTaskInfo(map, record);
        // The portable asset contract is task_info + segments only. It must not
        // carry schedule metadata, run timestamps, or duplicated legacy source_task
        // data; imported routes become new local task assets.
        bundle.put("task_info", taskInfo);

        List<Object> segmentRows = new ArrayList<Object>();
        for (TaskMap.Segment segment : map.segments) {
            Map<String, Object> segRow = new LinkedHashMap<String, Object>();
            segRow.put("segment_id", segment.segmentId);
            segRow.put("sub_task_id", segment.subTaskId);
            segRow.put("sub_task_index", segment.subTaskIndex);
            segRow.put("sub_task_description", segment.subTaskDescription);
            segRow.put("success_criteria", segment.successCriteria);
            segRow.put("package_name", segment.packageName);
            segRow.put("package_label", segment.packageLabel);
            segRow.put("inputs", new ArrayList<String>(segment.inputs));
            segRow.put("outputs", new ArrayList<String>(segment.outputs));

            List<Object> stepRows = new ArrayList<Object>();
            for (TaskMap.Step step : segment.steps) {
                stepRows.add(exportStep(step, actions.get(step.sourceActionId), out));
            }
            segRow.put("steps", stepRows);
            segmentRows.add(segRow);
        }
        bundle.put("segments", segmentRows);
        out.bundle = bundle;
        return out;
    }

    public static ImportResult importPortable(String targetTaskKeyHash, String targetPackageName, String bundleJson) {
        return importPortable(targetTaskKeyHash, targetPackageName, Json.parseObject(bundleJson));
    }

    @SuppressWarnings("unchecked")
    public static ImportResult importPortable(String targetTaskKeyHash, String targetPackageName, Map<String, Object> bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("portable_bundle_missing");
        }
        String schema = stringOrEmpty(bundle.get("schema"));
        if (!PORTABLE_SCHEMA.equals(schema) && !LEGACY_PORTABLE_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("invalid_portable_schema:" + schema);
        }
        Map<String, Object> sourceTask = bundle.get("task_info") instanceof Map
                ? (Map<String, Object>) bundle.get("task_info")
                : (bundle.get("source_task") instanceof Map
                        ? (Map<String, Object>) bundle.get("source_task")
                        : new LinkedHashMap<String, Object>());
        String sourcePackageName = stringOrEmpty(sourceTask.get("package_name"));
        String requestedPackage = stringOrEmpty(targetPackageName);
        if (!requestedPackage.isEmpty() && !sourcePackageName.isEmpty() && !requestedPackage.equals(sourcePackageName)) {
            throw new IllegalArgumentException("portable_package_mismatch:" + sourcePackageName + "!=" + requestedPackage);
        }
        TaskMap map = new TaskMap();
        map.taskKeyHash = stringOrEmpty(targetTaskKeyHash);
        map.source = "portable_import";
        map.sourceId = firstNonBlank(
                stringOrEmpty(sourceTask.get("route_id")),
                stringOrEmpty(sourceTask.get("task_id"))
        );
        map.packageName = !requestedPackage.isEmpty() ? requestedPackage : sourcePackageName;
        map.packageLabel = stringOrEmpty(sourceTask.get("package_label"));
        map.createdFromTaskId = stringOrEmpty(sourceTask.get("task_id"));
        map.createdAtMs = System.currentTimeMillis();
        map.mode = "manual";
        map.lastReplayStatus = "unused";

        ImportResult result = new ImportResult();
        result.map = map;
        result.taskInfo.putAll(sourceTask);

        Object segObj = bundle.get("segments");
        if (segObj instanceof List) {
            for (Object segItem : (List<Object>) segObj) {
                if (!(segItem instanceof Map)) {
                    continue;
                }
                Map<String, Object> segRow = (Map<String, Object>) segItem;
                TaskMap.Segment segment = new TaskMap.Segment();
                segment.segmentId = stringOrEmpty(segRow.get("segment_id"));
                segment.subTaskId = stringOrEmpty(segRow.get("sub_task_id"));
                segment.subTaskIndex = (int) toLong(segRow.get("sub_task_index"), 0L);
                segment.subTaskDescription = stringOrEmpty(segRow.get("sub_task_description"));
                segment.subTaskDescriptionHash = "";
                segment.successCriteria = stringOrEmpty(segRow.get("success_criteria"));
                segment.packageName = !map.packageName.isEmpty() ? map.packageName : stringOrEmpty(segRow.get("package_name"));
                segment.packageLabel = !map.packageLabel.isEmpty() ? map.packageLabel : stringOrEmpty(segRow.get("package_label"));
                copyStringList(segRow.get("inputs"), segment.inputs);
                copyStringList(segRow.get("outputs"), segment.outputs);

                Object stepsObj = segRow.get("steps");
                if (stepsObj instanceof List) {
                    for (Object stepItem : (List<Object>) stepsObj) {
                        if (!(stepItem instanceof Map)) {
                            continue;
                        }
                        TaskMap.Step step = importStep((Map<String, Object>) stepItem);
                        if (PORTABLE_KIND_SEMANTIC_TAP.equals(step.portableKind)) {
                            result.pendingAdaptationCount += 1;
                        } else {
                            result.executableImportCount += 1;
                        }
                        segment.steps.add(step);
                    }
                }
                if (!segment.packageName.isEmpty() && !segment.steps.isEmpty()) {
                    map.segments.add(segment);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> exportStep(TaskMap.Step step, TaskRouteRecord.Action action, ExportResult counters) {
        String op = normalizeOp(step != null ? step.op : "");
        if ("SWIPE".equals(op)) {
            throw new IllegalArgumentException("unsupported_portable_op:SWIPE");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("step_id", step != null ? step.stepId : "");
        out.put("source_action_id", step != null ? step.sourceActionId : "");
        out.put("op", op);
        out.put("args", new ArrayList<String>(step != null ? step.args : new ArrayList<String>()));
        out.put("expected", step != null ? step.expected : "");

        if (!"TAP".equals(op)) {
            return out;
        }

        Map<String, Object> descriptor = buildSemanticDescriptor(step, action);
        if (step != null && step.locator != null && !step.locator.isEmpty()) {
            out.put("portable_kind", PORTABLE_KIND_LOCAL_LOCATOR);
            out.put("locator", new LinkedHashMap<String, Object>(step.locator));
            out.put("semantic_descriptor", descriptor);
            counters.locatorStepCount += 1;
            return out;
        }

        if (step != null && PORTABLE_KIND_SEMANTIC_TAP.equals(step.portableKind)
                && step.semanticDescriptor != null && !step.semanticDescriptor.isEmpty()) {
            descriptor = new LinkedHashMap<String, Object>(step.semanticDescriptor);
        }
        if (step != null && hasCoordinateBackedTap(step)) {
            out.put("portable_kind", PORTABLE_KIND_SEMANTIC_TAP);
            out.put("source_local_kind", sourceLocalKind(step));
            out.put("semantic_descriptor", descriptor);
            counters.semanticStepCount += 1;
            return out;
        }
        throw new IllegalArgumentException("unsupported_step:no_tap_target");
    }

    private static TaskMap.Step importStep(Map<String, Object> row) {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = stringOrEmpty(row.get("step_id"));
        step.sourceActionId = stringOrEmpty(row.get("source_action_id"));
        step.op = normalizeOp(stringOrEmpty(row.get("op")));
        copyStringList(row.get("args"), step.args);
        step.expected = stringOrEmpty(row.get("expected"));

        if (!"TAP".equals(step.op)) {
            step.portableKind = "";
            step.adaptationStatus = ADAPTATION_STATUS_NONE;
            return step;
        }

        String portableKind = stringOrEmpty(row.get("portable_kind"));
        if (PORTABLE_KIND_LOCAL_LOCATOR.equals(portableKind)) {
            Object locatorObj = row.get("locator");
            if (locatorObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> locator = (Map<String, Object>) locatorObj;
                step.locator.putAll(locator);
            }
            Object descriptorObj = row.get("semantic_descriptor");
            if (descriptorObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> descriptor = (Map<String, Object>) descriptorObj;
                step.semanticDescriptor.putAll(descriptor);
                step.semanticNote = stringOrEmpty(descriptor.get("page_context"));
            }
            step.portableKind = PORTABLE_KIND_LOCAL_LOCATOR;
            step.adaptationStatus = ADAPTATION_STATUS_NONE;
            return step;
        }

        if (PORTABLE_KIND_SEMANTIC_TAP.equals(portableKind)) {
            Object descriptorObj = row.get("semantic_descriptor");
            if (descriptorObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> descriptor = (Map<String, Object>) descriptorObj;
                step.semanticDescriptor.putAll(descriptor);
                step.semanticNote = firstNonBlank(
                        stringOrEmpty(descriptor.get("instruction")),
                        stringOrEmpty(descriptor.get("page_context")),
                        stringOrEmpty(descriptor.get("source_observation"))
                );
                if (step.expected.isEmpty()) {
                    step.expected = stringOrEmpty(descriptor.get("expected_after_tap"));
                }
            }
            step.portableKind = PORTABLE_KIND_SEMANTIC_TAP;
            step.adaptationStatus = ADAPTATION_STATUS_PENDING;
            step.locator.clear();
            step.containerProbe.clear();
            step.tapPoint.clear();
            step.fallbackPoint = "";
            return step;
        }
        throw new IllegalArgumentException("unsupported_portable_kind:" + portableKind);
    }


    private static Map<String, Object> buildTaskInfo(TaskMap map, TaskRouteRecord record) {
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("task_id", stringOrEmpty(record != null ? record.taskId : map != null ? map.createdFromTaskId : ""));
        task.put("user_task", firstNonBlank(
                stringOrEmpty(record != null ? record.rootTask : ""),
                firstSegmentDescription(map)
        ));
        task.put("package_name", firstNonBlank(
                stringOrEmpty(record != null ? record.packageName : ""),
                stringOrEmpty(map != null ? map.packageName : "")
        ));
        task.put("package_label", firstNonBlank(
                stringOrEmpty(record != null ? record.packageLabel : ""),
                stringOrEmpty(map != null ? map.packageLabel : "")
        ));
        task.put("task_map_mode", firstNonBlank(
                stringOrEmpty(record != null ? record.taskMapMode : ""),
                stringOrEmpty(map != null ? map.mode : "")
        ));
        task.put("route_id", stringOrEmpty(map != null ? map.taskKeyHash : ""));
        return task;
    }

    private static Map<String, TaskRouteRecord.Action> indexActions(TaskRouteRecord record) {
        Map<String, TaskRouteRecord.Action> out = new LinkedHashMap<String, TaskRouteRecord.Action>();
        if (record == null || record.actions == null) {
            return out;
        }
        for (TaskRouteRecord.Action action : record.actions) {
            if (action == null || stringOrEmpty(action.actionId).isEmpty()) {
                continue;
            }
            out.put(action.actionId, action);
        }
        return out;
    }

    private static Map<String, Object> buildSemanticDescriptor(TaskMap.Step step, TaskRouteRecord.Action action) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("version", 1);
        String instruction = firstNonBlank(
                visionText(action, "action"),
                stringOrEmpty(step != null ? step.semanticNote : ""),
                stringOrEmpty(action != null ? action.createdPageSemantics : ""),
                humanizeRawCommand(action != null ? action.rawCommand : ""),
                "点击目标控件"
        );
        String expected = firstNonBlank(
                visionText(action, "expected"),
                stringOrEmpty(step != null ? step.expected : ""),
                ""
        );
        String pageContext = firstNonBlank(
                stringOrEmpty(step != null ? step.semanticNote : ""),
                stringOrEmpty(action != null ? action.createdPageSemantics : "")
        );
        String targetName = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("target_name") : null),
                guessTargetName(instruction, expected)
        );
        String targetRole = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("target_role") : null),
                guessTargetRole(instruction)
        );
        String visualHint = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("visual_hint") : null),
                targetName
        );
        String sourceObservation = firstNonBlank(
                stringOrEmpty(action != null ? action.createdPageSemantics : ""),
                stringOrEmpty(step != null ? step.semanticNote : "")
        );
        descriptor.put("instruction", instruction);
        descriptor.put("target_name", targetName);
        descriptor.put("target_role", targetRole);
        descriptor.put("visual_hint", visualHint);
        descriptor.put("page_context", pageContext);
        descriptor.put("expected_after_tap", expected);
        descriptor.put("source_observation", sourceObservation);
        descriptor.put("source_command", stringOrEmpty(action != null ? action.rawCommand : ""));
        descriptor.put("descriptor_quality", hasStrongDescriptor(instruction, targetName, pageContext, sourceObservation) ? "strong" : "weak");
        return descriptor;
    }

    private static boolean hasCoordinateBackedTap(TaskMap.Step step) {
        return step != null && (
                (step.containerProbe != null && !step.containerProbe.isEmpty())
                        || (step.tapPoint != null && step.tapPoint.size() >= 2)
                        || (step.fallbackPoint != null && !step.fallbackPoint.isEmpty())
        );
    }

    private static String sourceLocalKind(TaskMap.Step step) {
        if (step == null) {
            return "tap_point";
        }
        if (step.containerProbe != null && !step.containerProbe.isEmpty()) {
            return "container_probe";
        }
        if (step.tapPoint != null && step.tapPoint.size() >= 2) {
            return "tap_point";
        }
        return "fallback_point";
    }

    private static String firstSegmentDescription(TaskMap map) {
        if (map == null || map.segments.isEmpty()) {
            return "";
        }
        return stringOrEmpty(map.segments.get(0).subTaskDescription);
    }

    private static String visionText(TaskRouteRecord.Action action, String key) {
        if (action == null || action.vision == null || action.vision.isEmpty()) {
            return "";
        }
        Object value = action.vision.get(key);
        if (value == null && "action".equals(key)) {
            value = action.vision.get("Action");
        }
        if (value == null && "expected".equals(key)) {
            value = action.vision.get("expected");
        }
        return stringOrEmpty(value);
    }

    private static String humanizeRawCommand(String rawCommand) {
        String raw = stringOrEmpty(rawCommand);
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.toUpperCase(Locale.ROOT).startsWith("TAP")) {
            return "";
        }
        return raw;
    }

    private static String guessTargetName(String instruction, String expected) {
        String merged = firstNonBlank(instruction, expected);
        if (merged.contains("发布")) return "发布";
        if (merged.contains("朋友圈")) return "朋友圈";
        if (merged.contains("发现")) return "发现";
        if (merged.contains("加号") || merged.contains("+")) return "加号按钮";
        return "";
    }

    private static String guessTargetRole(String instruction) {
        String lower = stringOrEmpty(instruction).toLowerCase(Locale.ROOT);
        if (lower.contains("tab")) return "tab";
        if (lower.contains("button") || lower.contains("按钮") || lower.contains("入口")) return "button";
        return "unknown";
    }

    private static boolean hasStrongDescriptor(String instruction, String targetName, String pageContext, String sourceObservation) {
        int nonBlankCount = 0;
        if (!stringOrEmpty(instruction).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(targetName).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(pageContext).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(sourceObservation).isEmpty()) nonBlankCount += 1;
        return nonBlankCount >= 2 && !"点击目标控件".equals(stringOrEmpty(instruction));
    }

    private static void copyStringList(Object src, List<String> out) {
        if (!(src instanceof List) || out == null) {
            return;
        }
        for (Object item : (List<Object>) src) {
            String value = stringOrEmpty(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = stringOrEmpty(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizeOp(String op) {
        return stringOrEmpty(op).toUpperCase(Locale.ROOT);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long toLong(Object o, long defVal) {
        if (o == null) {
            return defVal;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }
}
