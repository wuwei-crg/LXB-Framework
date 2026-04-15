package com.lxb.server.cortex.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NotificationTriggerRule {

    public static class Action {
        public final String type;
        public final String userTask;
        public final String packageName;
        public final String userPlaybook;
        public final boolean recordEnabled;
        public final Boolean useMapOverride;
        public final String taskMapMode;

        public Action(
                String type,
                String userTask,
                String packageName,
                String userPlaybook,
                boolean recordEnabled,
                Boolean useMapOverride,
                String taskMapMode
        ) {
            this.type = type != null ? type : "run_task";
            this.userTask = userTask != null ? userTask : "";
            this.packageName = packageName != null ? packageName : "";
            this.userPlaybook = userPlaybook != null ? userPlaybook : "";
            this.recordEnabled = recordEnabled;
            this.useMapOverride = useMapOverride;
            this.taskMapMode = normalizeTaskMapMode(taskMapMode);
        }
    }

    public final String id;
    public final String name;
    public final boolean enabled;
    public final int priority;
    public final String packageMode;
    public final List<String> packageList;
    public final String textMode;
    public final String titlePattern;
    public final String bodyPattern;
    public final boolean llmConditionEnabled;
    public final String llmCondition;
    public final String llmYesToken;
    public final String llmNoToken;
    public final long llmTimeoutMs;
    public final boolean taskRewriteEnabled;
    public final String taskRewriteInstruction;
    public final long taskRewriteTimeoutMs;
    public final String taskRewriteFailPolicy;
    public final long cooldownMs;
    public final String activeTimeStart;
    public final String activeTimeEnd;
    public final boolean stopAfterMatched;
    public final Action action;

    public NotificationTriggerRule(
            String id,
            String name,
            boolean enabled,
            int priority,
            String packageMode,
            List<String> packageList,
            String textMode,
            String titlePattern,
            String bodyPattern,
            boolean llmConditionEnabled,
            String llmCondition,
            String llmYesToken,
            String llmNoToken,
            long llmTimeoutMs,
            boolean taskRewriteEnabled,
            String taskRewriteInstruction,
            long taskRewriteTimeoutMs,
            String taskRewriteFailPolicy,
            long cooldownMs,
            String activeTimeStart,
            String activeTimeEnd,
            boolean stopAfterMatched,
            Action action
    ) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.enabled = enabled;
        this.priority = priority;
        this.packageMode = normalizePackageMode(packageMode);
        this.packageList = packageList != null
                ? Collections.unmodifiableList(new ArrayList<String>(packageList))
                : Collections.<String>emptyList();
        this.textMode = normalizeTextMode(textMode);
        this.titlePattern = titlePattern != null ? titlePattern : "";
        this.bodyPattern = bodyPattern != null ? bodyPattern : "";
        this.llmConditionEnabled = llmConditionEnabled;
        this.llmCondition = llmCondition != null ? llmCondition : "";
        this.llmYesToken = normalizeToken(llmYesToken, "yes");
        this.llmNoToken = normalizeToken(llmNoToken, "no");
        this.llmTimeoutMs = clampTimeout(llmTimeoutMs, 60000L);
        this.taskRewriteEnabled = taskRewriteEnabled;
        this.taskRewriteInstruction = taskRewriteInstruction != null ? taskRewriteInstruction : "";
        this.taskRewriteTimeoutMs = clampTimeout(taskRewriteTimeoutMs, 60000L);
        this.taskRewriteFailPolicy = normalizeFailPolicy(taskRewriteFailPolicy);
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.activeTimeStart = normalizeTimeOfDay(activeTimeStart);
        this.activeTimeEnd = normalizeTimeOfDay(activeTimeEnd);
        this.stopAfterMatched = stopAfterMatched;
        this.action = action != null ? action : new Action("run_task", "", "", "", false, null, "off");
    }

    @SuppressWarnings("unchecked")
    public static NotificationTriggerRule fromMap(Map<String, Object> map, int fallbackIndex) {
        if (map == null) return null;
        String id = stringOrEmpty(map.get("id"));
        if (id.isEmpty()) {
            id = "notify_rule_" + fallbackIndex;
        }
        String name = stringOrEmpty(map.get("name"));
        boolean enabled = toBool(map.get("enabled"), true);
        int priority = toInt(map.get("priority"), 100);

        String packageMode = stringOrEmpty(map.get("package_mode"));
        List<String> packageList = toStringList(map.get("package_list"));

        String textMode = stringOrEmpty(map.get("text_mode"));
        String titlePattern = stringOrEmpty(map.get("title_pattern"));
        String bodyPattern = stringOrEmpty(map.get("body_pattern"));

        boolean llmConditionEnabled = toBool(map.get("llm_condition_enabled"), false);
        String llmCondition = stringOrEmpty(map.get("llm_condition"));
        String llmYesToken = stringOrEmpty(map.get("llm_yes_token"));
        String llmNoToken = stringOrEmpty(map.get("llm_no_token"));
        long llmTimeoutMs = toLong(map.get("llm_timeout_ms"), 60000L);

        boolean taskRewriteEnabled = toBool(map.get("task_rewrite_enabled"), false);
        String taskRewriteInstruction = stringOrEmpty(map.get("task_rewrite_instruction"));
        long taskRewriteTimeoutMs = toLong(map.get("task_rewrite_timeout_ms"), 60000L);
        String taskRewriteFailPolicy = stringOrEmpty(map.get("task_rewrite_fail_policy"));

        long cooldownMs = toLong(map.get("cooldown_ms"), 60000L);
        String activeTimeStart = stringOrEmpty(map.get("active_time_start"));
        String activeTimeEnd = stringOrEmpty(map.get("active_time_end"));
        boolean stopAfterMatched = toBool(map.get("stop_after_matched"), true);

        Object actionObj = map.get("action");
        Action action = actionObj instanceof Map
                ? parseAction((Map<String, Object>) actionObj)
                : new Action("run_task", "", "", "", false, null, "off");
        return new NotificationTriggerRule(
                id,
                name,
                enabled,
                priority,
                packageMode,
                packageList,
                textMode,
                titlePattern,
                bodyPattern,
                llmConditionEnabled,
                llmCondition,
                llmYesToken,
                llmNoToken,
                llmTimeoutMs,
                taskRewriteEnabled,
                taskRewriteInstruction,
                taskRewriteTimeoutMs,
                taskRewriteFailPolicy,
                cooldownMs,
                activeTimeStart,
                activeTimeEnd,
                stopAfterMatched,
                action
        );
    }

    @SuppressWarnings("unchecked")
    private static Action parseAction(Map<String, Object> m) {
        if (m == null) {
            return new Action("run_task", "", "", "", false, null, "off");
        }
        String type = stringOrEmpty(m.get("type"));
        if (type.isEmpty()) type = "run_task";
        String userTask = stringOrEmpty(m.get("user_task"));
        String packageName = stringOrEmpty(m.get("package"));
        String userPlaybook = stringOrEmpty(m.get("user_playbook"));
        boolean recordEnabled = toBool(m.get("record_enabled"), false);
        Boolean useMapOverride = null;
        if (m.containsKey("use_map")) {
            useMapOverride = Boolean.valueOf(toBool(m.get("use_map"), true));
        }
        String taskMapMode = stringOrEmpty(m.get("task_map_mode"));
        return new Action(type, userTask, packageName, userPlaybook, recordEnabled, useMapOverride, taskMapMode);
    }

    private static String normalizePackageMode(String s) {
        String v = normalizeLower(s);
        if ("allowlist".equals(v) || "blocklist".equals(v)) {
            return v;
        }
        return "any";
    }

    private static String normalizeTextMode(String s) {
        String v = normalizeLower(s);
        if ("regex".equals(v)) return "regex";
        return "contains";
    }

    private static String normalizeFailPolicy(String s) {
        String v = normalizeLower(s);
        if ("skip".equals(v)) return "skip";
        return "fallback_raw_task";
    }

    private static String normalizeTaskMapMode(String s) {
        String v = normalizeLower(s);
        if ("ai".equals(v) || "manual".equals(v) || "off".equals(v)) return v;
        return "off";
    }

    private static String normalizeToken(String s, String defVal) {
        String v = normalizeLower(s);
        return v.isEmpty() ? defVal : v;
    }

    private static String normalizeTimeOfDay(String raw) {
        String s = raw != null ? raw.trim() : "";
        if (s.isEmpty()) return "";
        int colon = s.indexOf(':');
        if (colon <= 0 || colon >= s.length() - 1) return "";
        String h = s.substring(0, colon).trim();
        String m = s.substring(colon + 1).trim();
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(h);
            minute = Integer.parseInt(m);
        } catch (Exception ignored) {
            return "";
        }
        if (hour < 0 || hour > 23) return "";
        if (minute < 0 || minute > 59) return "";
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private static long clampTimeout(long timeoutMs, long defVal) {
        long t = timeoutMs > 0 ? timeoutMs : defVal;
        if (t < 500L) return 500L;
        if (t > 120000L) return 120000L;
        return t;
    }

    private static String normalizeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o, int defVal) {
        if (o == null) return defVal;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
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

    private static boolean toBool(Object o, boolean defVal) {
        if (o == null) return defVal;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return defVal;
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) return false;
        return defVal;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object o) {
        if (!(o instanceof List)) return Collections.emptyList();
        List<Object> src = (List<Object>) o;
        List<String> out = new ArrayList<String>();
        for (Object v : src) {
            String s = stringOrEmpty(v);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
