package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.taskmap.PortableTaskRouteCodec;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapLocalTapBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SemanticStepMaterializer {

    public static final class Result {
        public boolean ok;
        public TaskMap.Step materializedStep;
        public String error = "";
        public String rawResponse = "";
    }

    private SemanticStepMaterializer() {}

    public static Result materialize(
            LlmClient llmClient,
            LlmConfig cfg,
            TraceLogger trace,
            String taskId,
            String taskKeyHash,
            String segmentId,
            String pkg,
            TaskMap.Step pendingStep,
            byte[] screenshotPng,
            List<DumpActionsParser.ActionNode> nodes,
            TaskMapLocalTapBuilder.LocatorUniquenessGate uniquenessGate
    ) {
        Result result = new Result();
        if (pendingStep == null) {
            result.error = "semantic_step_missing";
            return result;
        }
        if (screenshotPng == null || screenshotPng.length == 0) {
            result.error = "semantic_adaptation_screenshot_missing";
            return result;
        }
        if (nodes == null || nodes.isEmpty()) {
            result.error = "semantic_adaptation_nodes_missing";
            return result;
        }
        try {
            String prompt = SemanticAdaptationPromptBuilder.buildPrompt(pkg, pendingStep);
            Map<String, Object> promptEv = new LinkedHashMap<String, Object>();
            promptEv.put("task_id", taskId);
            promptEv.put("route_id", taskKeyHash);
            promptEv.put("segment_id", segmentId);
            promptEv.put("step_id", pendingStep.stepId);
            promptEv.put("prompt", prompt);
            trace.event("llm_prompt_semantic_adaptation", promptEv);

            String raw = llmClient.chatOnce(cfg, null, prompt, screenshotPng);
            result.rawResponse = raw;
            Map<String, Object> respEv = new LinkedHashMap<String, Object>();
            respEv.put("task_id", taskId);
            respEv.put("route_id", taskKeyHash);
            respEv.put("segment_id", segmentId);
            respEv.put("step_id", pendingStep.stepId);
            respEv.put("response", raw != null && raw.length() > 2000 ? raw.substring(0, 2000) + "..." : raw);
            trace.event("llm_response_semantic_adaptation", respEv);

            Map<String, Object> parsed = Json.parseObject(raw);
            String status = stringOrEmpty(parsed.get("result"));
            if ("point".equals(status)) {
                int x = toInt(parsed.get("x"), Integer.MIN_VALUE);
                int y = toInt(parsed.get("y"), Integer.MIN_VALUE);
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                    result.error = "semantic_adaptation_point_invalid";
                    return result;
                }
                TaskMapLocalTapBuilder.LocalTapPayload payload = TaskMapLocalTapBuilder.materializeTap(
                        x,
                        y,
                        nodes,
                        uniquenessGate
                );
                TaskMap.Step materialized = new TaskMap.Step();
                materialized.stepId = pendingStep.stepId;
                materialized.sourceActionId = pendingStep.sourceActionId;
                materialized.op = pendingStep.op;
                materialized.args.addAll(pendingStep.args);
                materialized.locator.putAll(payload.locator);
                materialized.containerProbe.putAll(payload.containerProbe);
                materialized.tapPoint.addAll(payload.tapPoint);
                materialized.fallbackPoint = payload.fallbackPoint;
                materialized.semanticNote = pendingStep.semanticNote;
                materialized.expected = pendingStep.expected;
                materialized.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_MATERIALIZED;
                materialized.semanticDescriptor.putAll(pendingStep.semanticDescriptor);
                materialized.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_ADAPTED;
                materialized.adaptationError = "";
                materialized.adaptationAttemptedAtMs = System.currentTimeMillis();
                materialized.materializedFromStepId = pendingStep.stepId;
                materialized.materializedAtMs = materialized.adaptationAttemptedAtMs;
                result.ok = true;
                result.materializedStep = materialized;
                return result;
            }
            if ("no_match".equals(status) || "ambiguous".equals(status)) {
                result.error = "semantic_adaptation_" + status + ":" + stringOrEmpty(parsed.get("reason"));
                return result;
            }
            result.error = "semantic_adaptation_result_invalid";
            return result;
        } catch (Exception e) {
            result.error = "semantic_adaptation_error:" + e;
            return result;
        }
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o, int defVal) {
        if (o == null) {
            return defVal;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }
}
