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

    private static final String DEFAULT_ROOT = "/data/local/tmp/lxb/task_maps";

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
        out.put("task_key_hash", normalize(taskKeyHash));
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
        out.put("task_key_hash", normalize(taskKeyHash));
        out.put("has_map", hasMap(taskKeyHash));
        TaskRouteRecord success = loadLatestSuccessRecord(taskKeyHash);
        TaskRouteRecord attempt = loadLatestAttemptRecord(taskKeyHash);
        out.put("has_latest_success_record", success != null);
        out.put("has_latest_attempt_record", attempt != null);
        return out;
    }

    public String resolveExistingKeyHash(TaskRouteKey key, String rootTask) {
        if (key == null) {
            return "";
        }
        String direct = normalize(key.taskKeyHash);
        if (!direct.isEmpty() && hasAnyArtifact(direct)) {
            return direct;
        }
        return findCompatibleKeyHash(
                direct,
                key.source,
                key.sourceId,
                key.sourceConfigHash,
                key.packageName,
                rootTask
        );
    }

    public String findCompatibleKeyHash(
            String requestedKeyHash,
            String source,
            String sourceId,
            String sourceConfigHash,
            String packageName,
            String rootTask
    ) {
        String direct = normalize(requestedKeyHash);
        if (!direct.isEmpty() && hasAnyArtifact(direct)) {
            return direct;
        }
        String normalizedSource = normalize(source);
        String normalizedSourceId = normalize(sourceId);
        String normalizedConfigHash = normalize(sourceConfigHash);
        String normalizedPackage = normalize(packageName);
        String normalizedRootTask = normalize(rootTask);

        String hit = findCompatibleMapKey(
                normalizedSource,
                normalizedSourceId,
                normalizedConfigHash,
                normalizedPackage
        );
        if (!hit.isEmpty()) {
            return hit;
        }
        hit = findCompatibleRecordKey(
                ".latest_success_record.json",
                normalizedSource,
                normalizedSourceId,
                normalizedConfigHash,
                normalizedPackage,
                normalizedRootTask
        );
        if (!hit.isEmpty()) {
            return hit;
        }
        return findCompatibleRecordKey(
                ".latest_attempt_record.json",
                normalizedSource,
                normalizedSourceId,
                normalizedConfigHash,
                normalizedPackage,
                normalizedRootTask
        );
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
            row.put("task_key_hash", taskKeyHash);
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
        return new File(mapsDir, normalize(taskKeyHash) + ".task_map.json");
    }

    private File successRecordFile(String taskKeyHash) {
        return new File(recordsDir, normalize(taskKeyHash) + ".latest_success_record.json");
    }

    private File attemptRecordFile(String taskKeyHash) {
        return new File(recordsDir, normalize(taskKeyHash) + ".latest_attempt_record.json");
    }

    private String findCompatibleMapKey(
            String source,
            String sourceId,
            String sourceConfigHash,
            String packageName
    ) {
        File[] files = mapsDir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(".task_map.json")) {
                continue;
            }
            TaskMap map = TaskMap.fromObject(loadJson(file));
            if (map == null) {
                continue;
            }
            if (!matchesMeta(source, map.source)) {
                continue;
            }
            if (!matchesMeta(sourceId, map.sourceId)) {
                continue;
            }
            if (!matchesMeta(sourceConfigHash, map.sourceConfigHash)) {
                continue;
            }
            if (!matchesMeta(packageName, map.packageName)) {
                continue;
            }
            String key = normalize(map.taskKeyHash);
            if (!key.isEmpty()) {
                return key;
            }
            return stripKnownSuffix(file.getName(), ".task_map.json");
        }
        return "";
    }

    private String findCompatibleRecordKey(
            String suffix,
            String source,
            String sourceId,
            String sourceConfigHash,
            String packageName,
            String rootTask
    ) {
        File[] files = recordsDir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        Set<String> seenKeys = new LinkedHashSet<String>();
        for (File file : files) {
            if (file == null || !file.isFile() || !file.getName().endsWith(suffix)) {
                continue;
            }
            TaskRouteRecord record = TaskRouteRecord.fromObject(loadJson(file));
            if (record == null) {
                continue;
            }
            String key = normalize(record.taskKeyHash);
            if (key.isEmpty()) {
                key = stripKnownSuffix(file.getName(), suffix);
            }
            if (key.isEmpty() || !seenKeys.add(key)) {
                continue;
            }
            if (!matchesMeta(source, record.source)) {
                continue;
            }
            if (!matchesMeta(sourceId, record.sourceId)) {
                continue;
            }
            if (!matchesMeta(sourceConfigHash, record.sourceConfigHash)) {
                continue;
            }
            if (!matchesMeta(packageName, record.packageName)) {
                continue;
            }
            if (!matchesMeta(rootTask, record.rootTask)) {
                continue;
            }
            return key;
        }
        return "";
    }

    private static boolean matchesMeta(String expected, String actual) {
        String normalizedExpected = normalize(expected);
        if (normalizedExpected.isEmpty()) {
            return true;
        }
        return normalizedExpected.equals(normalize(actual));
    }

    private static String stripKnownSuffix(String fileName, String suffix) {
        if (fileName == null || suffix == null || !fileName.endsWith(suffix)) {
            return "";
        }
        return normalize(fileName.substring(0, fileName.length() - suffix.length()));
    }

    private static File resolveRootDir() {
        String override = System.getProperty("lxb.task.maps.root");
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        return new File(DEFAULT_ROOT);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
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
