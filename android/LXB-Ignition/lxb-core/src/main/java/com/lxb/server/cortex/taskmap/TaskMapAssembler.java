package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.fsm.VisionCommandParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TaskMapAssembler {

    private static final Set<String> REPLAYABLE_OPS = new HashSet<String>();

    static {
        REPLAYABLE_OPS.add("TAP");
        REPLAYABLE_OPS.add("SWIPE");
        REPLAYABLE_OPS.add("INPUT");
        REPLAYABLE_OPS.add("BACK");
        REPLAYABLE_OPS.add("WAIT");
    }

    private TaskMapAssembler() {}

    public static TaskMap assemble(TaskRouteRecord record, Set<String> deleteActionIds, String mode) {
        if (record == null || record.actions.isEmpty()) {
            return null;
        }
        Set<String> deleteSet = deleteActionIds != null ? deleteActionIds : new HashSet<String>();
        Map<String, TaskMap.Segment> segments = new LinkedHashMap<String, TaskMap.Segment>();
        for (TaskRouteRecord.Action action : record.actions) {
            if (action == null || deleteSet.contains(action.actionId)) {
                continue;
            }
            String op = normalize(action.op);
            if (!REPLAYABLE_OPS.contains(op)) {
                continue;
            }
            if ("TAP".equals(op)
                    && (action.locator == null || action.locator.isEmpty())
                    && (action.containerProbe == null || action.containerProbe.isEmpty())
                    && (action.tapPoint == null || action.tapPoint.size() < 2)) {
                continue;
            }
            if ("SWIPE".equals(op)
                    && (action.swipe == null || action.swipe.isEmpty())
                    && (action.args == null || action.args.size() < 5)) {
                continue;
            }
            String segmentKey = segmentKey(action);
            TaskMap.Segment segment = segments.get(segmentKey);
            if (segment == null) {
                segment = new TaskMap.Segment();
                segment.segmentId = "seg" + String.format(Locale.ROOT, "%04d", segments.size() + 1);
                segment.subTaskId = action.subTaskId != null && !action.subTaskId.isEmpty() ? action.subTaskId : "default";
                segment.subTaskIndex = segments.size();
                segment.subTaskDescription = record.rootTask != null ? record.rootTask : "";
                segment.subTaskDescriptionHash = TaskRouteKey.sha256Hex(segment.subTaskDescription);
                segment.successCriteria = "";
                segment.packageName = record.packageName != null ? record.packageName : "";
                segment.packageLabel = record.packageLabel != null ? record.packageLabel : "";
                segments.put(segmentKey, segment);
            }
            TaskMap.Step step = new TaskMap.Step();
            step.stepId = "s" + String.format(Locale.ROOT, "%04d", segment.steps.size() + 1);
            step.sourceActionId = action.actionId;
            step.op = op;
            step.args.addAll(resolveStepArgs(action, op));
            if (action.locator != null) {
                step.locator.putAll(action.locator);
            }
            if (action.containerProbe != null) {
                step.containerProbe.putAll(action.containerProbe);
            }
            if (action.tapPoint != null) {
                step.tapPoint.addAll(action.tapPoint);
            }
            if (action.swipe != null) {
                step.swipe.putAll(action.swipe);
            }
            Object fallbackPoint = action.locator != null ? action.locator.get("fallback_point") : null;
            if (fallbackPoint == null && action.tapPoint != null && action.tapPoint.size() >= 2) {
                fallbackPoint = action.tapPoint;
            }
            step.fallbackPoint = fallbackPoint != null ? String.valueOf(fallbackPoint) : "";
            step.semanticNote = action.createdPageSemantics != null ? action.createdPageSemantics : "";
            step.expected = action.vision != null ? stringOrEmpty(action.vision.get("expected")) : "";
            segment.steps.add(step);
        }
        if (segments.isEmpty()) {
            return null;
        }
        TaskMap out = new TaskMap();
        out.taskKeyHash = record.taskKeyHash;
        out.source = record.source;
        out.sourceId = record.sourceId;
        out.sourceConfigHash = record.sourceConfigHash;
        out.packageName = record.packageName;
        out.packageLabel = record.packageLabel;
        out.createdFromTaskId = record.taskId;
        out.createdAtMs = System.currentTimeMillis();
        out.mode = normalizeMode(mode);
        out.lastReplayStatus = "unused";
        out.segments.addAll(segments.values());
        return out.stepCount() > 0 ? out : null;
    }

    public static boolean isReplayableOp(String op) {
        return REPLAYABLE_OPS.contains(normalize(op));
    }

    public static String normalizeMode(String mode) {
        String v = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("manual".equals(v) || "ai".equals(v) || "off".equals(v)) {
            return v;
        }
        return "ai";
    }

    private static String segmentKey(TaskRouteRecord.Action action) {
        return action.subTaskId != null && !action.subTaskId.isEmpty() ? action.subTaskId : "default";
    }

    private static List<String> resolveStepArgs(TaskRouteRecord.Action action, String op) {
        if (action == null) {
            return new ArrayList<String>();
        }
        if (!"INPUT".equals(op)) {
            return copyArgs(action.args);
        }
        List<String> rawArgs = parseRawInstructionArgs(action.rawCommand);
        if (!rawArgs.isEmpty()) {
            return rawArgs;
        }
        return copyArgs(action.args);
    }

    private static List<String> parseRawInstructionArgs(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        try {
            List<VisionCommandParser.Instruction> instructions = VisionCommandParser.parseInstructions(rawCommand, 1);
            if (!instructions.isEmpty()) {
                return copyArgs(instructions.get(0).args);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<String>();
    }

    private static List<String> copyArgs(List<String> args) {
        List<String> out = new ArrayList<String>();
        if (args != null) {
            for (String s : args) {
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
