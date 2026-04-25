package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

import java.util.Map;

public final class SemanticAdaptationPromptBuilder {

    private SemanticAdaptationPromptBuilder() {}

    public static String buildPrompt(String pkg, TaskMap.Step step) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are adapting an imported mobile task-route TAP step to the current device.\n");
        sb.append("Use the screenshot to find the target UI element described below.\n");
        sb.append("Return JSON only.\n");
        sb.append("Allowed outputs:\n");
        sb.append("{\"result\":\"point\",\"x\":123,\"y\":456,\"reason\":\"...\"}\n");
        sb.append("{\"result\":\"no_match\",\"reason\":\"...\"}\n");
        sb.append("{\"result\":\"ambiguous\",\"reason\":\"...\"}\n");
        sb.append("Do not output any command text. Do not plan multiple actions. Only locate one tap target.\n\n");
        sb.append("Package: ").append(stringOrEmpty(pkg)).append("\n");
        sb.append("Step ID: ").append(stringOrEmpty(step != null ? step.stepId : "")).append("\n");
        sb.append("Expected after tap: ").append(stringOrEmpty(step != null ? step.expected : "")).append("\n");
        Map<String, Object> descriptor = step != null ? step.semanticDescriptor : null;
        if (descriptor != null && !descriptor.isEmpty()) {
            sb.append("Instruction: ").append(stringOrEmpty(descriptor.get("instruction"))).append("\n");
            sb.append("Target name: ").append(stringOrEmpty(descriptor.get("target_name"))).append("\n");
            sb.append("Target role: ").append(stringOrEmpty(descriptor.get("target_role"))).append("\n");
            sb.append("Visual hint: ").append(stringOrEmpty(descriptor.get("visual_hint"))).append("\n");
            sb.append("Page context: ").append(stringOrEmpty(descriptor.get("page_context"))).append("\n");
            sb.append("Source observation: ").append(stringOrEmpty(descriptor.get("source_observation"))).append("\n");
            sb.append("Source command: ").append(stringOrEmpty(descriptor.get("source_command"))).append("\n");
            sb.append("Descriptor quality: ").append(stringOrEmpty(descriptor.get("descriptor_quality"))).append("\n");
        } else {
            sb.append("Fallback semantic note: ").append(stringOrEmpty(step != null ? step.semanticNote : "")).append("\n");
        }
        sb.append("\nIf the target is not visible in the screenshot, return no_match.\n");
        sb.append("If multiple similar targets remain and you cannot choose one confidently, return ambiguous.\n");
        return sb.toString();
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
