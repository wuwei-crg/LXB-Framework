package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
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
            String sourceConfigHash,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode,
            String taskKey,
            String canonicalJson,
            String taskKeyHash
    ) {
        this.source = source;
        this.sourceId = sourceId;
        this.sourceConfigHash = sourceConfigHash;
        this.packageName = packageName;
        this.userTask = userTask;
        this.userPlaybook = userPlaybook;
        this.taskMapMode = taskMapMode;
        this.taskKey = taskKey;
        this.canonicalJson = canonicalJson;
        this.taskKeyHash = taskKeyHash;
    }

    public static TaskRouteKey build(
            String source,
            String sourceId,
            String sourceConfigHash,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode
    ) {
        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("source", normalize(source));
        canonical.put("source_id", normalize(sourceId));
        canonical.put("source_config_hash", normalize(sourceConfigHash));
        canonical.put("package_name", normalize(packageName));
        canonical.put("user_task", normalize(userTask));
        canonical.put("user_playbook", normalize(userPlaybook));
        String json = Json.stringify(canonical);
        String hash = sha256Hex(json);
        return new TaskRouteKey(
                normalize(source),
                normalize(sourceId),
                normalize(sourceConfigHash),
                normalize(packageName),
                normalize(userTask),
                normalize(userPlaybook),
                normalize(taskMapMode),
                json,
                json,
                hash
        );
    }

    public static TaskRouteKey alias(TaskRouteKey base, String resolvedTaskKeyHash) {
        if (base == null) {
            return build("", "", "", "", "", "", "off");
        }
        String resolved = normalize(resolvedTaskKeyHash);
        return new TaskRouteKey(
                base.source,
                base.sourceId,
                base.sourceConfigHash,
                base.packageName,
                base.userTask,
                base.userPlaybook,
                base.taskMapMode,
                base.taskKey,
                base.canonicalJson,
                resolved.isEmpty() ? base.taskKeyHash : resolved
        );
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("source", source);
        out.put("source_id", sourceId);
        out.put("source_config_hash", sourceConfigHash);
        out.put("package_name", packageName);
        out.put("user_task", userTask);
        out.put("user_playbook", userPlaybook);
        out.put("task_map_mode", taskMapMode);
        out.put("task_key", taskKey);
        out.put("task_key_hash", taskKeyHash);
        out.put("canonical", canonicalJson);
        return out;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }
}
