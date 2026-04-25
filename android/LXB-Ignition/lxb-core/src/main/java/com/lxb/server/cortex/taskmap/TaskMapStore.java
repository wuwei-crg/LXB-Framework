package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TaskMapStore {

    private static final String DEFAULT_ROOT = "/sdcard/Android/data/com.example.lxb_ignition/files/lxb_state/task_maps";

    private final File rootDir;
    private final File recordsDir;
    private final File mapsDir;
    private final File indexFile;

    public TaskMapStore() {
        this(resolveRootDir());
    }

    public TaskMapStore(File rootDir) {
        this.rootDir = rootDir != null ? rootDir : resolveRootDir();
        this.recordsDir = new File(this.rootDir, "records");
        this.mapsDir = new File(this.rootDir, "maps");
        this.indexFile = new File(this.rootDir, "index.v1.json");
        ensureDirs();
    }

    public File getRootDir() {
        return rootDir;
    }

    public boolean hasMap(String taskKeyHash) {
        return loadMap(taskKeyHash) != null;
    }

    public boolean hasMap(TaskRouteKey key) {
        return key != null && hasMap(key.taskKeyHash);
    }

    public boolean hasAnyArtifact(String taskKeyHash) {
        String key = normalize(taskKeyHash);
        if (key.isEmpty()) {
            return false;
        }
        return mapFile(key).isFile() || successRecordFile(key).isFile() || attemptRecordFile(key).isFile();
    }

    public TaskMap loadMap(String taskKeyHash) {
        Object obj = loadJson(mapFile(taskKeyHash));
        TaskMap map = TaskMap.fromObject(obj);
        if (map != null && map.taskKeyHash.isEmpty()) {
            map.taskKeyHash = normalize(taskKeyHash);
        }
        return map;
    }

    public TaskRouteRecord loadLatestSuccessRecord(String taskKeyHash) {
        return TaskRouteRecord.fromObject(loadJson(successRecordFile(taskKeyHash)));
    }

    public TaskRouteRecord loadLatestAttemptRecord(String taskKeyHash) {
        return TaskRouteRecord.fromObject(loadJson(attemptRecordFile(taskKeyHash)));
    }

    public boolean saveLatestSuccessRecord(TaskRouteRecord record) {
        return record != null && saveJson(successRecordFile(record.taskKeyHash), record.toMap());
    }

    public boolean saveLatestAttemptRecord(TaskRouteRecord record) {
        return record != null && saveJson(attemptRecordFile(record.taskKeyHash), record.toMap());
    }

    public boolean saveMap(TaskMap map) {
        if (map == null) {
            return false;
        }
        boolean ok = saveJson(mapFile(map.taskKeyHash), map.toMap());
        if (ok) {
            updateIndex(map);
        }
        return ok;
    }

    public boolean saveMaterializedStep(
            String taskKeyHash,
            String segmentId,
            String stepId,
            TaskMap.Step materializedStep
    ) {
        if (materializedStep == null) {
            return false;
        }
        TaskMap map = loadMap(taskKeyHash);
        if (map == null) {
            return false;
        }
        TaskMap.Step target = findStep(map, segmentId, stepId);
        if (target == null) {
            return false;
        }
        copyStep(target, materializedStep);
        return saveMap(map);
    }

    public boolean markSemanticStepAdaptationFailed(
            String taskKeyHash,
            String segmentId,
            String stepId,
            String error,
            long attemptedAtMs
    ) {
        TaskMap map = loadMap(taskKeyHash);
        if (map == null) {
            return false;
        }
        TaskMap.Step target = findStep(map, segmentId, stepId);
        if (target == null) {
            return false;
        }
        target.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP;
        target.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_FAILED;
        target.adaptationError = normalize(error);
        target.adaptationAttemptedAtMs = attemptedAtMs;
        target.locator.clear();
        target.containerProbe.clear();
        target.tapPoint.clear();
        target.fallbackPoint = "";
        return saveMap(map);
    }

    public boolean deleteMap(String taskKeyHash) {
        boolean deleted = mapFile(taskKeyHash).delete();
        updateIndexEntry(taskKeyHash, null, null, null, null);
        return deleted;
    }

    public Map<String, Object> getStatus(String taskKeyHash) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        TaskMap map = loadMap(taskKeyHash);
        TaskRouteRecord success = loadLatestSuccessRecord(taskKeyHash);
        TaskRouteRecord attempt = loadLatestAttemptRecord(taskKeyHash);
        out.put("route_id", normalize(taskKeyHash));
        out.put("has_map", map != null);
        out.put("has_latest_success_record", success != null);
        out.put("has_latest_attempt_record", attempt != null);
        if (map != null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("created_at_ms", map.createdAtMs);
            summary.put("created_from_task_id", map.createdFromTaskId);
            summary.put("segment_count", map.segments.size());
            summary.put("step_count", map.stepCount());
            summary.put("last_replay_status", map.lastReplayStatus);
            out.put("map_summary", summary);
        }
        if (success != null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("task_id", success.taskId);
            summary.put("created_at_ms", success.createdAtMs);
            summary.put("action_count", success.actions.size());
            summary.put("status", success.status);
            out.put("latest_success_record_summary", summary);
        }
        if (attempt != null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("task_id", attempt.taskId);
            summary.put("created_at_ms", attempt.createdAtMs);
            summary.put("action_count", attempt.actions.size());
            summary.put("status", attempt.status);
            out.put("latest_attempt_record_summary", summary);
        }
        return out;
    }

    public Map<String, Object> getTaskKeySummary(String taskKeyHash) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("route_id", normalize(taskKeyHash));
        out.put("has_map", hasMap(taskKeyHash));
        TaskRouteRecord success = loadLatestSuccessRecord(taskKeyHash);
        TaskRouteRecord attempt = loadLatestAttemptRecord(taskKeyHash);
        out.put("has_latest_success_record", success != null);
        out.put("has_latest_attempt_record", attempt != null);
        return out;
    }

    private void updateIndex(TaskMap map) {
        updateIndexEntry(map.taskKeyHash, map.source, map.sourceId, map.packageName, map);
    }

    @SuppressWarnings("unchecked")
    private void updateIndexEntry(String taskKeyHash, String source, String sourceId, String packageName, TaskMap map) {
        try {
            Map<String, Object> root = loadIndex();
            Map<String, Object> index = (Map<String, Object>) root.get("entries");
            if (index == null) {
                index = new LinkedHashMap<String, Object>();
                root.put("entries", index);
            }
            if (taskKeyHash == null || taskKeyHash.trim().isEmpty()) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("route_id", taskKeyHash);
            row.put("source", source != null ? source : "");
            row.put("source_id", sourceId != null ? sourceId : "");
            row.put("package_name", packageName != null ? packageName : "");
            row.put("has_map", map != null);
            row.put("map_created_at_ms", map != null ? map.createdAtMs : 0L);
            row.put("map_created_from_task_id", map != null ? map.createdFromTaskId : "");
            row.put("segment_count", map != null ? map.segments.size() : 0);
            row.put("step_count", map != null ? map.stepCount() : 0);
            index.put(taskKeyHash, row);
            saveJson(indexFile, root);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadIndex() {
        Object parsed = loadJson(indexFile);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema_version", "task_maps.v1");
        root.put("entries", new LinkedHashMap<String, Object>());
        return root;
    }

    private boolean saveJson(File file, Object obj) {
        try {
            ensureDirs();
            writeJsonAtomically(file, Json.stringify(obj));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object loadJson(File file) {
        try {
            if (file != null && file.exists() && file.isFile()) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                return Json.parse(text);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void ensureDirs() {
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        if (!recordsDir.exists()) {
            recordsDir.mkdirs();
        }
        if (!mapsDir.exists()) {
            mapsDir.mkdirs();
        }
    }

    private File mapFile(String taskKeyHash) {
        return new File(mapsDir, safeStorageName(taskKeyHash) + ".task_map.json");
    }

    private File successRecordFile(String taskKeyHash) {
        return new File(recordsDir, safeStorageName(taskKeyHash) + ".latest_success_record.json");
    }

    private File attemptRecordFile(String taskKeyHash) {
        return new File(recordsDir, safeStorageName(taskKeyHash) + ".latest_attempt_record.json");
    }

    private static File resolveRootDir() {
        String override = System.getProperty("lxb.task.maps.root");
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        String mapDir = System.getProperty("lxb.map.dir");
        if (mapDir != null && !mapDir.trim().isEmpty()) {
            File base = new File(mapDir.trim());
            File parent = base.getName().equals("maps") ? base.getParentFile() : base;
            if (parent != null) {
                return new File(parent, "task_maps");
            }
        }
        return new File(DEFAULT_ROOT);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static String safeStorageName(String id) {
        String normalized = normalize(id);
        if (normalized.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean changed = false;
        for (int i = 0; i < normalized.length(); i += 1) {
            char c = normalized.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (ok) {
                sb.append(c);
            } else {
                sb.append('_');
                changed = true;
            }
        }
        String out = sb.toString();
        if (out.length() > 120) {
            out = out.substring(0, 80) + "_" + Integer.toHexString(normalized.hashCode());
            changed = true;
        }
        return changed ? out + "_" + Integer.toHexString(normalized.hashCode()) : out;
    }

    private static TaskMap.Step findStep(TaskMap map, String segmentId, String stepId) {
        if (map == null || map.segments == null) {
            return null;
        }
        String normalizedSegmentId = normalize(segmentId);
        String normalizedStepId = normalize(stepId);
        for (TaskMap.Segment segment : map.segments) {
            if (segment == null || !normalizedSegmentId.equals(normalize(segment.segmentId))) {
                continue;
            }
            for (TaskMap.Step step : segment.steps) {
                if (step != null && normalizedStepId.equals(normalize(step.stepId))) {
                    return step;
                }
            }
        }
        return null;
    }

    private static void copyStep(TaskMap.Step target, TaskMap.Step src) {
        target.stepId = src.stepId;
        target.sourceActionId = src.sourceActionId;
        target.op = src.op;
        target.args.clear();
        target.args.addAll(src.args);
        target.locator.clear();
        target.locator.putAll(src.locator);
        target.containerProbe.clear();
        target.containerProbe.putAll(src.containerProbe);
        target.tapPoint.clear();
        target.tapPoint.addAll(src.tapPoint);
        target.swipe.clear();
        target.swipe.putAll(src.swipe);
        target.fallbackPoint = src.fallbackPoint;
        target.semanticNote = src.semanticNote;
        target.expected = src.expected;
        target.portableKind = src.portableKind;
        target.semanticDescriptor.clear();
        target.semanticDescriptor.putAll(src.semanticDescriptor);
        target.adaptationStatus = src.adaptationStatus;
        target.adaptationError = src.adaptationError;
        target.adaptationAttemptedAtMs = src.adaptationAttemptedAtMs;
        target.materializedFromStepId = src.materializedFromStepId;
        target.materializedAtMs = src.materializedAtMs;
    }

    private void writeJsonAtomically(File file, String json) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        File bak = new File(file.getAbsolutePath() + ".bak");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        if (file.exists()) {
            bak.delete();
            file.renameTo(bak);
        }
        if (!tmp.renameTo(file)) {
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }
}
