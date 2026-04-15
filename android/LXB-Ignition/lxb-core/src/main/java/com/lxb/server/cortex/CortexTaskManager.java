package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapAssembler;
import com.lxb.server.cortex.taskmap.TaskMapStore;
import com.lxb.server.cortex.taskmap.TaskRouteKey;
import com.lxb.server.cortex.taskmap.TaskRouteRecord;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Task manager shell for Cortex FSM tasks.
 *
 * Current design:
 * - Async submission via submitTask(...), returning task_id immediately.
 * - Single worker thread executes FSM tasks in queue order.
 * - Minimal in-memory TaskInstance registry with bounded retention.
 *
 * Later phases:
 * - Maintain richer task registry and async submission/status APIs.
 * - Support scheduled tasks and trace push.
 */
public class CortexTaskManager {

    private final CortexFsmEngine fsmEngine;
    private final TaskMapStore taskMapStore;

    // Simple ready queue for FSM tasks.
    private final BlockingQueue<FsmTaskRequest> readyQueue =
            new LinkedBlockingQueue<FsmTaskRequest>();

    // Minimal in-memory registry of tasks, keyed by taskId.
    private final ConcurrentHashMap<String, TaskInstance> taskRegistry =
            new ConcurrentHashMap<String, TaskInstance>();

    // Insertion order of taskIds for simple LRU-style eviction.
    private final Deque<String> taskOrder = new ArrayDeque<String>();
    private static final int MAX_TASKS = 200;

    // Schedule registry (in-memory v1).
    private final ConcurrentHashMap<String, ScheduledTaskDef> scheduleRegistry =
            new ConcurrentHashMap<String, ScheduledTaskDef>();
    private final Deque<String> scheduleOrder = new ArrayDeque<String>();
    private static final int MAX_SCHEDULES = 200;

    // Task memory (v1): keyed by normalized task text and schedule id.
    private final ConcurrentHashMap<String, Map<String, Object>> memoryByTaskKey =
            new ConcurrentHashMap<String, Map<String, Object>>();
    private final ConcurrentHashMap<String, Map<String, Object>> memoryByScheduleId =
            new ConcurrentHashMap<String, Map<String, Object>>();
    private final Object memoryLock = new Object();
    private static final String DEFAULT_TASK_MEMORY_PATH = "/data/local/tmp/lxb/task_memory.json";
    private final String taskMemoryPath;
    private static final String DEFAULT_SCHEDULES_PATH = "/data/local/tmp/lxb/schedules.v1.json";
    private static final String DEFAULT_TASK_RUNS_PATH = "/data/local/tmp/lxb/task_runs.v1.json";
    private static final String DEFAULT_RECORD_ROOT = "/sdcard/Movies/lxb";
    private static final int RECORD_SEGMENT_TIME_LIMIT_SEC = 170;
    private static final long RECORD_SEGMENT_ROTATE_CHECK_MS = 1000L;
    private final String schedulesPath;
    private final String taskRunsPath;
    private final CortexTaskPersistence persistence = new CortexTaskPersistence();
    // Dedicated worker/scheduler threads.
    private final Thread workerThread;
    private final Thread schedulerThread;

    // Simple global cancellation flag for the single-worker FSM. When true,
    // the current FSM run will notice and exit at the next state boundary.
    private volatile boolean cancelRequested = false;

    public CortexTaskManager(CortexFsmEngine fsmEngine) {
        this(fsmEngine, new TaskMapStore());
    }

    public CortexTaskManager(CortexFsmEngine fsmEngine, TaskMapStore taskMapStore) {
        this.fsmEngine = fsmEngine;
        this.taskMapStore = taskMapStore != null ? taskMapStore : new TaskMapStore();
        this.taskMemoryPath = resolveTaskMemoryPath();
        this.schedulesPath = resolveSchedulesPath(this.taskMemoryPath);
        this.taskRunsPath = resolveTaskRunsPath(this.taskMemoryPath);
        loadTaskMemoryFromDisk();
        loadSchedulesFromDisk();
        loadTaskRunsFromDisk();
        this.workerThread = new Thread(this::workerLoop, "CortexFsmWorker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
        this.schedulerThread = new Thread(this::schedulerLoop, "CortexFsmScheduler");
        this.schedulerThread.setDaemon(true);
        this.schedulerThread.start();
    }

    /**
     * Request cancellation of the currently running FSM task, if any. Since
     * there is only a single worker thread, this flag applies to "the current"
     * task and is cleared when that task finishes.
     */
    public void requestCancel() {
        cancelRequested = true;
    }

    /**
     * Submit a Cortex FSM task for asynchronous execution.
     *
     * Returns the generated task_id immediately; the actual FSM run will be
     * performed by the worker thread. Callers should use CMD_CORTEX_TASK_STATUS
     * or trace push to observe progress and final result.
     */
    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort
    ) {
        return submitTask(userTask, packageName, mapPath, startPage, traceMode, traceUdpPort, null, null);
    }

    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String userPlaybook
    ) {
        return submitTask(userTask, packageName, mapPath, startPage, traceMode, traceUdpPort, userPlaybook, null);
    }

    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String userPlaybook,
            Boolean recordEnabled
    ) {
        return submitTask(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                userPlaybook,
                recordEnabled,
                null
        );
    }

    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String userPlaybook,
            Boolean recordEnabled,
            Boolean useMapOverride
    ) {
        return submitTask(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                userPlaybook,
                recordEnabled,
                useMapOverride,
                "off"
        );
    }

    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String userPlaybook,
            Boolean recordEnabled,
            Boolean useMapOverride,
            String taskMapMode
    ) {
        boolean record = recordEnabled != null && recordEnabled.booleanValue();
        return submitTaskInternal(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                "manual",
                null,
                userPlaybook,
                recordEnabled,
                useMapOverride,
                taskMapMode,
                "",
                buildManualConfigHash(userTask, packageName, mapPath, startPage, traceMode, traceUdpPort, userPlaybook, record, useMapOverride, taskMapMode)
        );
    }

    /**
     * Submit a notification-triggered task.
     *
     * This path is intentionally lightweight:
     * - source is marked as "notify_trigger"
     * - schedule fields are disabled by default
     */
    public String submitNotificationTriggeredTask(
            String userTask,
            String packageName,
            String userPlaybook,
            Boolean recordEnabled,
            Boolean useMapOverride
    ) {
        return submitNotificationTriggeredTask(userTask, packageName, userPlaybook, recordEnabled, useMapOverride, "off", "", "");
    }

    public String submitNotificationTriggeredTask(
            String userTask,
            String packageName,
            String userPlaybook,
            Boolean recordEnabled,
            Boolean useMapOverride,
            String taskMapMode,
            String sourceId,
            String sourceConfigHash
    ) {
        return submitTaskInternal(
                userTask,
                packageName,
                null,
                null,
                null,
                null,
                "notify_trigger",
                null,
                userPlaybook,
                recordEnabled,
                useMapOverride,
                taskMapMode,
                sourceId,
                sourceConfigHash
        );
    }

    private String submitTaskInternal(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String source,
            String scheduleId,
            String userPlaybook,
            Boolean recordEnabled,
            Boolean useMapOverride,
            String taskMapMode,
            String sourceId,
            String sourceConfigHash
    ) {
        long now = System.currentTimeMillis();
        TaskInstance instance = new TaskInstance();
        instance.taskId = UUID.randomUUID().toString();
        instance.userTask = userTask != null ? userTask : "";
        instance.source = source != null ? source : "manual";
        instance.scheduleId = scheduleId;
        instance.userPlaybook = userPlaybook != null ? userPlaybook.trim() : "";
        instance.packageName = packageName != null ? packageName.trim() : "";
        instance.taskMapMode = taskMapMode != null ? taskMapMode.trim() : "off";
        instance.sourceId = sourceId != null ? sourceId.trim() : "";
        instance.sourceConfigHash = sourceConfigHash != null ? sourceConfigHash.trim() : "";
        String taskKey = buildTaskMemoryKey(instance.userTask);
        instance.taskMemoryKey = taskKey;
        Map<String, Object> memoryHint = instance.userPlaybook.isEmpty()
                ? selectTaskMemoryHint(taskKey, scheduleId)
                : null;
        instance.memoryApplied = memoryHint != null && !memoryHint.isEmpty();
        instance.state = TaskState.PENDING;
        instance.createdAt = now;
        instance.recordEnabled = recordEnabled != null && recordEnabled.booleanValue();

        FsmTaskRequest req = new FsmTaskRequest(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                instance.source,
                instance.scheduleId,
                instance.userPlaybook,
                memoryHint,
                instance.recordEnabled,
                useMapOverride,
                instance.taskMapMode,
                instance.sourceId,
                instance.sourceConfigHash,
                instance
        );
        try {
            readyQueue.put(req);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while enqueuing FSM task", e);
        }

        registerTaskInstance(instance);

        return instance.taskId;
    }

    /**
     * Add a periodic scheduled task (v1).
     *
     * intervalSec: period in seconds, must be > 0.
     * startAtMs: first run time (epoch ms); if <= 0, defaults to now + 1s.
     */
    public Map<String, Object> addScheduledTask(
            String name,
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            long runAtMs,
            String repeatModeRaw,
            int repeatWeekdays,
            String userPlaybook,
            boolean recordEnabled,
            String taskMapMode
    ) {
        if (userTask == null || userTask.trim().isEmpty()) {
            throw new IllegalArgumentException("user_task is required");
        }
        if (runAtMs <= 0) {
            throw new IllegalArgumentException("run_at is required and must be > 0");
        }
        String repeatMode = CortexScheduleTime.normalizeRepeatMode(repeatModeRaw);
        long now = System.currentTimeMillis();
        int normalizedMask = (repeatWeekdays & 0x7F);
        if ("weekly".equals(repeatMode) && normalizedMask == 0) {
            throw new IllegalArgumentException("repeat_weekdays is required for weekly schedule");
        }
        long firstRunAt = CortexScheduleTime.computeFirstRunAt(runAtMs, repeatMode, normalizedMask, now);

        ScheduledTaskDef def = new ScheduledTaskDef();
        def.scheduleId = UUID.randomUUID().toString();
        def.name = name != null ? name.trim() : "";
        def.userTask = userTask.trim();
        def.packageName = packageName != null ? packageName.trim() : "";
        def.mapPath = mapPath;
        def.startPage = startPage;
        def.traceMode = traceMode;
        def.traceUdpPort = traceUdpPort;
        def.userPlaybook = userPlaybook != null ? userPlaybook.trim() : "";
        def.taskMapMode = normalizeTaskMapMode(taskMapMode);
        def.runAtMs = runAtMs;
        def.repeatMode = repeatMode;
        def.repeatWeekdays = normalizedMask;
        def.recordEnabled = recordEnabled;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(runAtMs);
        def.hourOfDay = c.get(Calendar.HOUR_OF_DAY);
        def.minuteOfHour = c.get(Calendar.MINUTE);
        def.nextRunAt = firstRunAt;
        def.enabled = true;
        def.createdAt = now;

        scheduleRegistry.put(def.scheduleId, def);
        synchronized (scheduleOrder) {
            scheduleOrder.addLast(def.scheduleId);
            while (scheduleOrder.size() > MAX_SCHEDULES) {
                String evictId = scheduleOrder.pollFirst();
                if (evictId != null) {
                    scheduleRegistry.remove(evictId);
                }
            }
        }
        saveSchedulesToDisk();

        return snapshotSchedule(def);
    }

    /**
     * Update an existing schedule in place (schedule_id remains unchanged).
     */
    public Map<String, Object> updateScheduledTask(
            String scheduleId,
            String name,
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            long runAtMs,
            String repeatModeRaw,
            int repeatWeekdays,
            String userPlaybook,
            Boolean enabled,
            boolean recordEnabled,
            String taskMapMode
    ) {
        if (scheduleId == null || scheduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("schedule_id is required");
        }
        if (userTask == null || userTask.trim().isEmpty()) {
            throw new IllegalArgumentException("user_task is required");
        }
        if (runAtMs <= 0) {
            throw new IllegalArgumentException("run_at is required and must be > 0");
        }

        ScheduledTaskDef def = scheduleRegistry.get(scheduleId);
        if (def == null) {
            return null;
        }

        String repeatMode = CortexScheduleTime.normalizeRepeatMode(repeatModeRaw);
        int normalizedMask = (repeatWeekdays & 0x7F);
        if ("weekly".equals(repeatMode) && normalizedMask == 0) {
            throw new IllegalArgumentException("repeat_weekdays is required for weekly schedule");
        }

        synchronized (def) {
            long now = System.currentTimeMillis();
            long firstRunAt = CortexScheduleTime.computeFirstRunAt(runAtMs, repeatMode, normalizedMask, now);
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(runAtMs);

            def.name = name != null ? name.trim() : "";
            def.userTask = userTask.trim();
            def.packageName = packageName != null ? packageName.trim() : "";
            def.mapPath = mapPath;
            def.startPage = startPage;
            def.traceMode = traceMode;
            def.traceUdpPort = traceUdpPort;
            def.userPlaybook = userPlaybook != null ? userPlaybook.trim() : "";
            if (def.taskMapMode == null || def.taskMapMode.isEmpty()) {
                def.taskMapMode = "off";
            }

            def.runAtMs = runAtMs;
            def.repeatMode = repeatMode;
            def.repeatWeekdays = normalizedMask;
            def.recordEnabled = recordEnabled;
            def.taskMapMode = normalizeTaskMapMode(taskMapMode);
            def.hourOfDay = c.get(Calendar.HOUR_OF_DAY);
            def.minuteOfHour = c.get(Calendar.MINUTE);
            def.nextRunAt = firstRunAt;
            def.enabled = enabled != null ? enabled.booleanValue() : def.enabled;
            if (!def.enabled) {
                if ("once".equals(def.repeatMode)) {
                    def.nextRunAt = 0L;
                }
            }
        }
        saveSchedulesToDisk();
        return snapshotSchedule(def);
    }

    /**
     * Remove a schedule by id.
     */
    public boolean removeScheduledTask(String scheduleId) {
        if (scheduleId == null || scheduleId.isEmpty()) {
            return false;
        }
        ScheduledTaskDef removed = scheduleRegistry.remove(scheduleId);
        synchronized (scheduleOrder) {
            scheduleOrder.remove(scheduleId);
        }
        synchronized (memoryLock) {
            memoryByScheduleId.remove(scheduleId);
            saveTaskMemoryToDisk();
        }
        saveSchedulesToDisk();
        return removed != null;
    }

    /**
     * List schedules (newest first).
     */
    public List<Map<String, Object>> listScheduledTasks(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_SCHEDULES));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<String> ids;
        synchronized (scheduleOrder) {
            ids = new ArrayList<String>(scheduleOrder);
        }
        for (int i = ids.size() - 1; i >= 0 && result.size() < effectiveLimit; i--) {
            String id = ids.get(i);
            ScheduledTaskDef def = scheduleRegistry.get(id);
            if (def == null) {
                continue;
            }
            result.add(snapshotSchedule(def));
        }
        return result;
    }

    private void schedulerLoop() {
        for (; ; ) {
            try {
                long now = System.currentTimeMillis();
                for (ScheduledTaskDef def : scheduleRegistry.values()) {
                    if (def == null || !def.enabled) {
                        continue;
                    }
                    if (now < def.nextRunAt) {
                        continue;
                    }
                    synchronized (def) {
                        long now2 = System.currentTimeMillis();
                        if (!def.enabled || now2 < def.nextRunAt) {
                            continue;
                        }
                        long triggeredAt = now2;
                        def.lastTriggeredAt = triggeredAt;
                        if ("daily".equals(def.repeatMode)) {
                            def.nextRunAt = CortexScheduleTime.computeNextDailyRun(def.hourOfDay, def.minuteOfHour, now2 + 1000L);
                        } else if ("weekly".equals(def.repeatMode)) {
                            def.nextRunAt = CortexScheduleTime.computeNextWeeklyRun(
                                    def.hourOfDay,
                                    def.minuteOfHour,
                                    def.repeatWeekdays,
                                    now2 + 1000L
                            );
                        } else {
                            def.enabled = false;
                            def.nextRunAt = 0L;
                        }
                        def.triggerCount += 1;
                        saveSchedulesToDisk();

                        try {
                            submitTaskInternal(
                                    def.userTask,
                                    def.packageName,
                                    def.mapPath,
                                    def.startPage,
                                    def.traceMode,
                                    def.traceUdpPort,
                                    "schedule",
                                    def.scheduleId,
                                    def.userPlaybook,
                                    Boolean.valueOf(def.recordEnabled),
                                    null,
                                    def.taskMapMode,
                                    def.scheduleId,
                                    buildScheduleConfigHash(def)
                            );
                        } catch (Exception ignored) {
                            // Keep scheduler resilient; failures are reflected by missing task rows.
                        }
                    }
                }
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Keep loop alive even if one bad schedule entry appears.
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e2) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void workerLoop() {
        for (; ; ) {
            try {
                FsmTaskRequest req = readyQueue.take();
                TaskInstance instance = req.instance;
                instance.state = TaskState.RUNNING;
                instance.startedAt = System.currentTimeMillis();
                cancelRequested = false;
                saveTaskRunsToDisk();

                CortexFsmEngine.CancellationChecker checker =
                        new CortexFsmEngine.CancellationChecker() {
                            @Override
                            public boolean isCancelled() {
                                return cancelRequested;
                            }
                        };

                TaskSessionHooks hooks = applyTaskSessionStart(instance, req);
                try {
                    CortexFsmEngine.UnlockReadyCallback unlockReadyCallback = null;
                    if (hooks.recordEnabled && hooks.recordPath != null && !hooks.recordPath.isEmpty()) {
                        unlockReadyCallback = new CortexFsmEngine.UnlockReadyCallback() {
                            @Override
                            public void onUnlockReady(String taskId, String source) {
                                if (hooks.recordStarted) {
                                    return;
                                }
                                try {
                                    hooks.recordStarted = startRecordSegment(instance, hooks, hooks.recordPath);
                                    instance.recordStarted = hooks.recordStarted;
                                    if (hooks.recordStarted) {
                                        startRecordRotateThread(instance, hooks);
                                    }
                                } catch (Exception ignored) {
                                    hooks.recordStarted = false;
                                    instance.recordStarted = false;
                                }
                            }
                        };
                    }

                    Map<String, Object> out = fsmEngine.run(
                            req.userTask,
                            req.packageName,
                            req.mapPath,
                            req.startPage,
                            req.traceMode,
                            req.traceUdpPort,
                            req.userPlaybook,
                            req.taskMemoryHint,
                            req.useMapOverride,
                            instance.taskId,
                            checker,
                            unlockReadyCallback,
                            req.source,
                            req.sourceId,
                            req.sourceConfigHash,
                            req.taskMapMode
                    );
                    instance.finishedAt = System.currentTimeMillis();
                    Object finalState = out.get("state");
                    if (finalState != null) {
                        instance.finalState = String.valueOf(finalState);
                    }
                    Object reason = out.get("reason");
                    if (reason != null) {
                        instance.reason = String.valueOf(reason);
                    }
                    String status = String.valueOf(out.get("status"));
                    if ("success".equalsIgnoreCase(status)) {
                        instance.state = TaskState.COMPLETED;
                    } else if (instance.reason != null && instance.reason.contains("cancelled_by_user")) {
                        instance.state = TaskState.CANCELLED;
                    } else {
                        instance.state = TaskState.FAILED;
                    }
                    Object pkg = out.get("package_name");
                    if (pkg != null) {
                        instance.packageName = String.valueOf(pkg);
                    }
                    Object pkgLabel = out.get("package_label");
                    if (pkgLabel != null) {
                        instance.packageLabel = String.valueOf(pkgLabel);
                    }
                    Object target = out.get("target_page");
                    if (target != null) {
                        instance.targetPage = String.valueOf(target);
                    }
                    instance.taskSummary = extractTaskSummary(out);
                    instance.resultSummary = out;
                    if (instance.state == TaskState.COMPLETED) {
                        saveTaskMemoryFromSuccess(req, instance, out);
                    }
                    saveTaskRunsToDisk();

                } catch (Exception e) {
                    instance.finishedAt = System.currentTimeMillis();
                    instance.state = TaskState.FAILED;
                    instance.reason = String.valueOf(e);
                    saveTaskRunsToDisk();
                } finally {
                    applyTaskSessionEnd(instance, hooks);
                    saveTaskRunsToDisk();
                }
            } catch (InterruptedException e) {
                // Allow graceful shutdown if ever needed.
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private TaskSessionHooks applyTaskSessionStart(TaskInstance instance, FsmTaskRequest req) {
        TaskSessionHooks hooks = new TaskSessionHooks();
        hooks.recordEnabled = req.recordEnabled;
        hooks.dndMode = resolveTaskDndMode();

        // Apply task-level DND mode based on runtime config (best-effort).
        if (!"skip".equals(hooks.dndMode)) {
            try {
                Map<String, Object> dndArgs = new LinkedHashMap<String, Object>();
                dndArgs.put("mode", hooks.dndMode);
                hooks.dndApplied = toBool(
                        fsmEngine.runSystemControl(instance.taskId, "dnd_set", dndArgs).get("ok"),
                        false
                );
            } catch (Exception ignored) {
                hooks.dndApplied = false;
            }
        }

        // Recording is schedule-controlled; manual tasks default to off.
        if (req.recordEnabled) {
            hooks.recordSegmentIndex = 1;
            hooks.recordPath = buildRecordFilePath(instance.taskId, instance.startedAt, hooks.recordSegmentIndex);
            instance.recordFilePath = hooks.recordPath;
            hooks.recordStarted = false;
            instance.recordStarted = false;
        }
        return hooks;
    }

    private void applyTaskSessionEnd(TaskInstance instance, TaskSessionHooks hooks) {
        if (hooks != null) {
            hooks.recordStopRequested = true;
            if (hooks.recordRotateThread != null) {
                hooks.recordRotateThread.interrupt();
                try {
                    hooks.recordRotateThread.join(1500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // Stop recording first, then restore DND.
        if (hooks != null && hooks.recordStarted) {
            try {
                synchronized (hooks.recordLock) {
                    fsmEngine.runSystemControl(instance.taskId, "screen_record_stop", new LinkedHashMap<String, Object>());
                }
            } catch (Exception ignored) {
            }
        }
        if (hooks != null && !hooks.recordSegments.isEmpty()) {
            instance.recordFilePath = joinRecordSegments(hooks.recordSegments);
        }
        if (hooks != null && !"skip".equals(hooks.dndMode)) {
            try {
                Map<String, Object> dndOff = new LinkedHashMap<String, Object>();
                dndOff.put("mode", "off");
                fsmEngine.runSystemControl(instance.taskId, "dnd_set", dndOff);
            } catch (Exception ignored) {
            }
        }
    }

    private String resolveTaskDndMode() {
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            return normalizeTaskDndMode(cfg != null ? cfg.taskDndMode : null);
        } catch (Exception ignored) {
            return "none";
        }
    }

    private String normalizeTaskDndMode(String raw) {
        String mode = raw != null ? raw.trim().toLowerCase() : "";
        if ("skip".equals(mode) || "off".equals(mode) || "none".equals(mode)) {
            return mode;
        }
        return "none";
    }

    private static String buildRecordFilePath(String taskId, long startedAtMs, int segmentIndex) {
        String safeTaskId = taskId != null && !taskId.trim().isEmpty()
                ? taskId.trim().replaceAll("[^A-Za-z0-9._-]", "_")
                : "unknown_task";
        long ts = startedAtMs > 0L ? startedAtMs : System.currentTimeMillis();
        int safeSegmentIndex = Math.max(1, segmentIndex);
        String fileName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ts))
                + "_part_" + String.format(Locale.US, "%03d", safeSegmentIndex) + ".mp4";
        return DEFAULT_RECORD_ROOT + "/" + safeTaskId + "/" + fileName;
    }

    private boolean startRecordSegment(TaskInstance instance, TaskSessionHooks hooks, String path) {
        synchronized (hooks.recordLock) {
            Map<String, Object> recArgs = new LinkedHashMap<String, Object>();
            recArgs.put("path", path);
            recArgs.put("time_limit_sec", RECORD_SEGMENT_TIME_LIMIT_SEC);
            Map<String, Object> rec = fsmEngine.runSystemControl(
                    instance.taskId,
                    "screen_record_start",
                    recArgs
            );
            boolean ok = toBool(rec.get("ok"), false);
            hooks.recordStarted = ok;
            if (ok) {
                hooks.recordPath = path;
                hooks.recordSegmentStartedAt = System.currentTimeMillis();
                if (!hooks.recordSegments.contains(path)) {
                    hooks.recordSegments.add(path);
                }
                instance.recordFilePath = joinRecordSegments(hooks.recordSegments);
            }
            return ok;
        }
    }

    private void rotateRecordSegment(TaskInstance instance, TaskSessionHooks hooks) {
        synchronized (hooks.recordLock) {
            if (hooks.recordStopRequested || !hooks.recordStarted) {
                return;
            }
            try {
                fsmEngine.runSystemControl(instance.taskId, "screen_record_stop", new LinkedHashMap<String, Object>());
            } catch (Exception ignored) {
            }
            hooks.recordStarted = false;
            instance.recordStarted = false;
            hooks.recordSegmentIndex += 1;
            String nextPath = buildRecordFilePath(instance.taskId, instance.startedAt, hooks.recordSegmentIndex);
            boolean ok = startRecordSegment(instance, hooks, nextPath);
            instance.recordStarted = ok;
        }
    }

    private void startRecordRotateThread(TaskInstance instance, TaskSessionHooks hooks) {
        if (hooks.recordRotateThread != null) {
            return;
        }
        Thread t = new Thread(() -> {
            while (!hooks.recordStopRequested) {
                try {
                    Thread.sleep(RECORD_SEGMENT_ROTATE_CHECK_MS);
                } catch (InterruptedException e) {
                    if (hooks.recordStopRequested) {
                        return;
                    }
                }
                if (hooks.recordStopRequested || !hooks.recordStarted) {
                    continue;
                }
                long elapsedMs = System.currentTimeMillis() - hooks.recordSegmentStartedAt;
                if (elapsedMs < (RECORD_SEGMENT_TIME_LIMIT_SEC - 2L) * 1000L) {
                    continue;
                }
                rotateRecordSegment(instance, hooks);
            }
        }, "LxbRecordRotate-" + instance.taskId);
        t.setDaemon(true);
        hooks.recordRotateThread = t;
        t.start();
    }

    private static String joinRecordSegments(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(segments.get(i));
        }
        return sb.toString();
    }

    private void registerTaskInstance(TaskInstance instance) {
        if (instance.taskId == null || instance.taskId.isEmpty()) {
            return;
        }
        taskRegistry.put(instance.taskId, instance);
        synchronized (taskOrder) {
            taskOrder.addLast(instance.taskId);
            while (taskOrder.size() > MAX_TASKS) {
                String evictId = taskOrder.pollFirst();
                if (evictId != null) {
                    taskRegistry.remove(evictId);
                }
            }
        }
        saveTaskRunsToDisk();
    }

    /**
     * Minimal per-request container for FSM tasks executed by the worker
     * thread. This is intentionally small; TaskInstance carries lifecycle
     * information for later inspection.
     */
    private static class FsmTaskRequest {
        final String userTask;
        final String packageName;
        final String mapPath;
        final String startPage;
        final String traceMode;
        final Integer traceUdpPort;
        final String source;
        final String scheduleId;
        final String userPlaybook;
        final Map<String, Object> taskMemoryHint;
        final boolean recordEnabled;
        final Boolean useMapOverride;
        final String taskMapMode;
        final String sourceId;
        final String sourceConfigHash;
        final TaskInstance instance;

        FsmTaskRequest(String userTask,
                       String packageName,
                       String mapPath,
                       String startPage,
                       String traceMode,
                       Integer traceUdpPort,
                       String source,
                       String scheduleId,
                       String userPlaybook,
                       Map<String, Object> taskMemoryHint,
                       boolean recordEnabled,
                       Boolean useMapOverride,
                       String taskMapMode,
                       String sourceId,
                       String sourceConfigHash,
                       TaskInstance instance) {
            this.userTask = userTask;
            this.packageName = packageName;
            this.mapPath = mapPath;
            this.startPage = startPage;
            this.traceMode = traceMode;
            this.traceUdpPort = traceUdpPort;
            this.source = source;
            this.scheduleId = scheduleId;
            this.userPlaybook = userPlaybook;
            this.taskMemoryHint = taskMemoryHint;
            this.recordEnabled = recordEnabled;
            this.useMapOverride = useMapOverride;
            this.taskMapMode = taskMapMode;
            this.sourceId = sourceId;
            this.sourceConfigHash = sourceConfigHash;
            this.instance = instance;
        }
    }

    private static class TaskSessionHooks {
        String dndMode;
        boolean dndApplied;
        boolean recordEnabled;
        boolean recordStarted;
        String recordPath;
        final List<String> recordSegments = new ArrayList<String>();
        final Object recordLock = new Object();
        volatile boolean recordStopRequested;
        volatile long recordSegmentStartedAt;
        volatile int recordSegmentIndex;
        Thread recordRotateThread;
    }

    /**
     * Internal task lifecycle state for the minimal registry.
     */
    private enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /**
     * Minimal TaskInstance snapshot for one FSM run.
     *
     * This captures only lifecycle timestamps and a few summary fields.
     * Future phases can extend this with TaskDefinition links, schedule_at,
     * and richer outcome summaries.
     */
    private static class TaskInstance {
        String taskId;           // Prefer FSM's task_id when available
        String userTask;

        TaskState state;
        long createdAt;
        long startedAt;
        long finishedAt;

        String finalState;       // FSM final state name, e.g. FINISH/FAIL
        String reason;           // Error or explanation, if any
        String packageName;
        String packageLabel;
        String targetPage;
        String source;           // manual | schedule
        String scheduleId;       // nullable
        String userPlaybook;     // optional guidance text
        String sourceId;
        String sourceConfigHash;
        String taskMapMode;
        String taskMemoryKey;    // normalized key
        boolean memoryApplied;   // whether this run consumed memory hint
        boolean recordEnabled;   // schedule-controlled recording switch
        boolean recordStarted;   // recording process started successfully
        String recordFilePath;   // output path (if enabled)
        String taskSummary;      // final DONE summary for user-facing result
        Map<String, Object> resultSummary;
    }

    /**
     * Minimal status snapshot for a given task_id. This is an internal helper
     * for future CMD_TASK_STATUS wiring; it is not exposed on the wire yet.
     *
     * Returns a map with:
     *   - found: boolean
     *   - task_id, user_task
     *   - state: PENDING/RUNNING/COMPLETED/FAILED
     *   - created_at, started_at, finished_at (epoch millis)
     *   - final_state, reason (may be null)
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (taskId == null || taskId.isEmpty()) {
            out.put("found", false);
            return out;
        }
        TaskInstance inst = taskRegistry.get(taskId);
        if (inst == null) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.put("task_id", inst.taskId);
        out.put("user_task", inst.userTask);
        out.put("state", inst.state != null ? inst.state.name() : null);
        out.put("created_at", inst.createdAt);
        out.put("started_at", inst.startedAt);
        out.put("finished_at", inst.finishedAt);
        out.put("final_state", inst.finalState);
        out.put("reason", inst.reason);
        out.put("package_name", inst.packageName);
        out.put("package_label", inst.packageLabel);
        out.put("target_page", inst.targetPage);
        out.put("source", inst.source);
        out.put("schedule_id", inst.scheduleId);
        out.put("source_id", inst.sourceId);
        out.put("source_config_hash", inst.sourceConfigHash);
        out.put("task_map_mode", inst.taskMapMode);
        out.put("task_memory_key", inst.taskMemoryKey);
        out.put("memory_applied", inst.memoryApplied);
        out.put("record_enabled", inst.recordEnabled);
        out.put("record_started", inst.recordStarted);
        out.put("record_file", inst.recordFilePath);
        TaskRouteKey routeKey = buildTaskRouteKey(inst);
        out.put("task_key", routeKey.canonicalJson);
        out.put("task_key_hash", routeKey.taskKeyHash);
        out.put("has_task_map", taskMapStore.hasMap(routeKey.taskKeyHash));
        out.put("task_summary", inst.taskSummary);
        if (inst.resultSummary != null) {
            out.put("summary", inst.resultSummary);
        }
        return out;
    }

    /**
     * List up to 'limit' most recent tasks in reverse chronological order.
     * Each entry is a shallow snapshot similar to getTaskStatus(), but
     * without the 'found' flag.
     */
    public java.util.List<Map<String, Object>> listRecentTasks(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_TASKS));
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        java.util.List<String> ids;
        synchronized (taskOrder) {
            ids = new java.util.ArrayList<String>(taskOrder);
        }
        // Iterate from newest to oldest
        for (int i = ids.size() - 1; i >= 0 && result.size() < effectiveLimit; i--) {
            String id = ids.get(i);
            TaskInstance inst = taskRegistry.get(id);
            if (inst == null) {
                continue;
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("task_id", inst.taskId);
            row.put("user_task", inst.userTask);
            row.put("state", inst.state != null ? inst.state.name() : null);
            row.put("created_at", inst.createdAt);
            row.put("started_at", inst.startedAt);
            row.put("finished_at", inst.finishedAt);
            row.put("final_state", inst.finalState);
            row.put("reason", inst.reason);
            row.put("package_name", inst.packageName);
            row.put("package_label", inst.packageLabel);
            row.put("target_page", inst.targetPage);
            row.put("source", inst.source);
            row.put("schedule_id", inst.scheduleId);
            row.put("source_id", inst.sourceId);
            row.put("source_config_hash", inst.sourceConfigHash);
            row.put("task_map_mode", inst.taskMapMode);
            row.put("task_memory_key", inst.taskMemoryKey);
            row.put("memory_applied", inst.memoryApplied);
            row.put("record_enabled", inst.recordEnabled);
            row.put("record_started", inst.recordStarted);
            row.put("record_file", inst.recordFilePath);
            TaskRouteKey routeKey = buildTaskRouteKey(inst);
            String persistedKeyHash = resolvePersistedTaskKeyHash(
                    routeKey.taskKeyHash,
                    inst.taskId,
                    inst.source,
                    inst.sourceId,
                    inst.packageName,
                    inst.userTask,
                    inst.userPlaybook,
                    inst.taskMapMode
            );
            row.put("task_key", routeKey.canonicalJson);
            row.put("task_key_hash", persistedKeyHash.isEmpty() ? routeKey.taskKeyHash : persistedKeyHash);
            row.put("has_task_map", taskMapStore.hasMap(persistedKeyHash.isEmpty() ? routeKey.taskKeyHash : persistedKeyHash));
            row.put("task_summary", inst.taskSummary);
            result.add(row);
        }
        return result;
    }

    public Map<String, Object> getTaskMapStatus(
            String taskKeyHash,
            String taskId,
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode,
            boolean includeDetails
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String resolved = resolvePersistedTaskKeyHash(taskKeyHash, taskId, source, sourceId, packageName, userTask, userPlaybook, taskMapMode);
        if (resolved.isEmpty()) {
            out.put("ok", false);
            out.put("err", "task_key_unresolved");
            return out;
        }
        out.put("ok", true);
        out.putAll(taskMapStore.getStatus(resolved));
        TaskInstance inst = taskId != null && !taskId.trim().isEmpty() ? taskRegistry.get(taskId.trim()) : null;
        out.put("mode", inst != null && inst.taskMapMode != null && !inst.taskMapMode.isEmpty() ? inst.taskMapMode : normalizeTaskMapMode(taskMapMode));
        if (inst != null) {
            out.put("source", stringOrEmpty(inst.source));
            out.put("source_id", stringOrEmpty(inst.sourceId));
            out.put("user_task", stringOrEmpty(inst.userTask));
            out.put("package_name", stringOrEmpty(inst.packageName));
        }
        if (includeDetails) {
            TaskMap map = taskMapStore.loadMap(resolved);
            TaskRouteRecord success = taskMapStore.loadLatestSuccessRecord(resolved);
            TaskRouteRecord attempt = taskMapStore.loadLatestAttemptRecord(resolved);
            if (map != null) {
                out.put("task_map", map.toMap());
            }
            if (success != null) {
                out.put("latest_success_record", success.toMap());
            }
            if (attempt != null) {
                out.put("latest_attempt_record", attempt.toMap());
            }
        }
        return out;
    }

    public Map<String, Object> deleteTaskMap(
            String taskKeyHash,
            String taskId,
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String resolved = resolvePersistedTaskKeyHash(taskKeyHash, taskId, source, sourceId, packageName, userTask, userPlaybook, taskMapMode);
        if (resolved.isEmpty()) {
            out.put("ok", false);
            out.put("err", "task_key_unresolved");
            return out;
        }
        boolean deleted = taskMapStore.deleteMap(resolved);
        out.put("ok", true);
        out.put("deleted", deleted);
        out.put("task_key_hash", resolved);
        return out;
    }

    public Map<String, Object> saveManualTaskMap(
            String taskKeyHash,
            String taskId,
            List<String> deleteActionIds,
            boolean finishAfterReplay
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String resolved = resolvePersistedTaskKeyHash(taskKeyHash, taskId, "", "", "", "", "", "manual");
        if (resolved.isEmpty()) {
            out.put("ok", false);
            out.put("err", "task_key_unresolved");
            return out;
        }
        TaskRouteRecord record = taskMapStore.loadLatestAttemptRecord(resolved);
        String recordKind = "latest_attempt_record";
        if (record == null || record.actions.isEmpty()) {
            record = taskMapStore.loadLatestSuccessRecord(resolved);
            recordKind = "latest_success_record";
        }
        if (record == null || record.actions.isEmpty()) {
            out.put("ok", false);
            out.put("err", "latest_record_missing");
            return out;
        }
        LinkedHashSet<String> deleteSet = new LinkedHashSet<String>();
        if (deleteActionIds != null) {
            for (String id : deleteActionIds) {
                String v = stringOrEmpty(id);
                if (!v.isEmpty()) {
                    deleteSet.add(v);
                }
            }
        }
        TaskMap map = TaskMapAssembler.assemble(record, deleteSet, "manual");
        if (map == null || map.stepCount() <= 0) {
            out.put("ok", false);
            out.put("err", "assemble_empty");
            return out;
        }
        map.finishAfterReplay = finishAfterReplay;
        boolean saved = taskMapStore.saveMap(map);
        out.put("ok", saved);
        out.put("saved", saved);
        out.put("task_key_hash", resolved);
        out.put("segment_count", map.segments.size());
        out.put("step_count", map.stepCount());
        out.put("finish_after_replay", map.finishAfterReplay);
        out.put("record_kind", recordKind);
        return out;
    }

    public Map<String, Object> setTaskMapMode(String source, String sourceId, String mode, String taskId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String normalizedMode = normalizeTaskMapMode(mode);
        boolean updated = false;
        if ("schedule".equals(source) && sourceId != null && !sourceId.trim().isEmpty()) {
            ScheduledTaskDef def = scheduleRegistry.get(sourceId.trim());
            if (def != null) {
                def.taskMapMode = normalizedMode;
                saveSchedulesToDisk();
                updated = true;
            }
        }
        if (!updated && taskId != null && !taskId.trim().isEmpty()) {
            TaskInstance inst = taskRegistry.get(taskId.trim());
            if (inst != null) {
                inst.taskMapMode = normalizedMode;
                saveTaskRunsToDisk();
                updated = true;
            }
        }
        out.put("ok", updated);
        out.put("updated", updated);
        out.put("mode", normalizedMode);
        if (!updated) {
            out.put("err", "task_map_mode_target_not_found");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String extractTaskSummary(Map<String, Object> out) {
        if (out == null || out.isEmpty()) {
            return "";
        }
        Object outputObj = out.get("output");
        if (outputObj instanceof Map) {
            Map<String, Object> output = (Map<String, Object>) outputObj;
            String taskSummary = stringOrEmpty(output.get("task_summary"));
            if (!taskSummary.isEmpty()) {
                return taskSummary;
            }
            Object subTaskObj = output.get("sub_task_summaries");
            if (subTaskObj instanceof Map) {
                Map<String, Object> subs = (Map<String, Object>) subTaskObj;
                String last = "";
                for (Object v : subs.values()) {
                    String s = stringOrEmpty(v);
                    if (!s.isEmpty()) {
                        last = s;
                    }
                }
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return "";
    }

    private static String buildTaskMemoryKey(String userTask) {
        String s = userTask != null ? userTask.trim().toLowerCase() : "";
        if (s.isEmpty()) {
            return "task:empty";
        }
        return "task:" + s;
    }

    private String resolveTaskKeyHash(
            String taskKeyHash,
            String taskId,
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode
    ) {
        String direct = stringOrEmpty(taskKeyHash);
        if (!direct.isEmpty()) {
            return direct;
        }
        String tid = stringOrEmpty(taskId);
        if (!tid.isEmpty()) {
            TaskInstance inst = taskRegistry.get(tid);
            if (inst != null) {
                return buildTaskRouteKey(inst).taskKeyHash;
            }
        }
        String src = stringOrEmpty(source);
        String sid = stringOrEmpty(sourceId);
        if ("schedule".equals(src) && !sid.isEmpty()) {
            ScheduledTaskDef def = scheduleRegistry.get(sid);
            if (def != null) {
                return TaskRouteKey.build(
                        "schedule",
                        sid,
                        buildScheduleConfigHash(def),
                        def.packageName,
                        def.userTask,
                        def.userPlaybook,
                        stringOrEmpty(def.taskMapMode)
                ).taskKeyHash;
            }
        }
        return TaskRouteKey.build(
                src,
                sid,
                "",
                stringOrEmpty(packageName),
                stringOrEmpty(userTask),
                stringOrEmpty(userPlaybook),
                normalizeTaskMapMode(taskMapMode)
        ).taskKeyHash;
    }

    private String resolvePersistedTaskKeyHash(
            String taskKeyHash,
            String taskId,
            String source,
            String sourceId,
            String packageName,
            String userTask,
            String userPlaybook,
            String taskMapMode
    ) {
        String canonical = resolveTaskKeyHash(taskKeyHash, taskId, source, sourceId, packageName, userTask, userPlaybook, taskMapMode);
        if (canonical.isEmpty()) {
            return "";
        }
        if (taskMapStore.hasAnyArtifact(canonical)) {
            return canonical;
        }
        TaskInstance inst = null;
        String tid = stringOrEmpty(taskId);
        if (!tid.isEmpty()) {
            inst = taskRegistry.get(tid);
        }
        String matchSource = inst != null ? stringOrEmpty(inst.source) : stringOrEmpty(source);
        String matchSourceId = inst != null ? stringOrEmpty(inst.sourceId) : stringOrEmpty(sourceId);
        String matchConfigHash = inst != null
                ? stringOrEmpty(inst.sourceConfigHash)
                : stringOrEmpty(sourceConfigHashFromInputs(source, sourceId, taskId));
        String matchPackage = inst != null ? stringOrEmpty(inst.packageName) : stringOrEmpty(packageName);
        String matchRootTask = inst != null ? stringOrEmpty(inst.userTask) : stringOrEmpty(userTask);
        String compatible = taskMapStore.findCompatibleKeyHash(
                canonical,
                matchSource,
                matchSourceId,
                matchConfigHash,
                matchPackage,
                matchRootTask
        );
        return compatible.isEmpty() ? canonical : compatible;
    }

    private String sourceConfigHashFromInputs(String source, String sourceId, String taskId) {
        String src = stringOrEmpty(source);
        String sid = stringOrEmpty(sourceId);
        if ("schedule".equals(src) && !sid.isEmpty()) {
            ScheduledTaskDef def = scheduleRegistry.get(sid);
            if (def != null) {
                return buildScheduleConfigHash(def);
            }
        }
        String tid = stringOrEmpty(taskId);
        if (!tid.isEmpty()) {
            TaskInstance inst = taskRegistry.get(tid);
            if (inst != null) {
                return stringOrEmpty(inst.sourceConfigHash);
            }
        }
        return "";
    }

    private static String buildScheduleConfigHash(ScheduledTaskDef def) {
        StringBuilder sb = new StringBuilder();
        sb.append("user_task=").append(stringOrEmpty(def != null ? def.userTask : null)).append('\n');
        sb.append("package=").append(stringOrEmpty(def != null ? def.packageName : null)).append('\n');
        sb.append("map_path=").append(stringOrEmpty(def != null ? def.mapPath : null)).append('\n');
        sb.append("start_page=").append(stringOrEmpty(def != null ? def.startPage : null)).append('\n');
        sb.append("trace_mode=").append(stringOrEmpty(def != null ? def.traceMode : null)).append('\n');
        sb.append("trace_udp_port=").append(def != null && def.traceUdpPort != null ? def.traceUdpPort : 0).append('\n');
        sb.append("user_playbook=").append(stringOrEmpty(def != null ? def.userPlaybook : null)).append('\n');
        sb.append("record_enabled=").append(def != null && def.recordEnabled).append('\n');
        sb.append("repeat_mode=").append(stringOrEmpty(def != null ? def.repeatMode : null)).append('\n');
        sb.append("repeat_weekdays=").append(def != null ? def.repeatWeekdays : 0).append('\n');
        sb.append("task_map_mode=").append(stringOrEmpty(def != null ? def.taskMapMode : null)).append('\n');
        return sha256Hex(sb.toString());
    }

    private static String buildManualConfigHash(String userTask, String packageName, String mapPath, String startPage, String traceMode, Integer traceUdpPort, String userPlaybook, boolean recordEnabled, Boolean useMapOverride, String taskMapMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("user_task=").append(stringOrEmpty(userTask)).append('\n');
        sb.append("package=").append(stringOrEmpty(packageName)).append('\n');
        sb.append("map_path=").append(stringOrEmpty(mapPath)).append('\n');
        sb.append("start_page=").append(stringOrEmpty(startPage)).append('\n');
        sb.append("trace_mode=").append(stringOrEmpty(traceMode)).append('\n');
        sb.append("trace_udp_port=").append(traceUdpPort != null ? traceUdpPort.intValue() : 0).append('\n');
        sb.append("user_playbook=").append(stringOrEmpty(userPlaybook)).append('\n');
        sb.append("record_enabled=").append(recordEnabled).append('\n');
        sb.append("use_map_override=").append(useMapOverride != null ? useMapOverride.booleanValue() : true).append('\n');
        sb.append("task_map_mode=").append(stringOrEmpty(taskMapMode)).append('\n');
        return sha256Hex(sb.toString());
    }

    private static String buildNotifyConfigHash(Map<String, Object> rule) {
        if (rule == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("package_mode=").append(stringOrEmpty(rule.get("package_mode"))).append('\n');
        sb.append("package_list=").append(stringOrEmpty(rule.get("package_list"))).append('\n');
        sb.append("text_mode=").append(stringOrEmpty(rule.get("text_mode"))).append('\n');
        sb.append("title_pattern=").append(stringOrEmpty(rule.get("title_pattern"))).append('\n');
        sb.append("body_pattern=").append(stringOrEmpty(rule.get("body_pattern"))).append('\n');
        sb.append("llm_condition_enabled=").append(toBool(rule.get("llm_condition_enabled"), false)).append('\n');
        sb.append("llm_condition=").append(stringOrEmpty(rule.get("llm_condition"))).append('\n');
        sb.append("action=").append(stringOrEmpty(rule.get("action"))).append('\n');
        return sha256Hex(sb.toString());
    }

    private TaskRouteKey buildTaskRouteKey(TaskInstance inst) {
        if (inst == null) {
            return TaskRouteKey.build("", "", "", "", "", "", "off");
        }
        String source = inst.source != null ? inst.source : "";
        String sourceId = inst.sourceId != null ? inst.sourceId : "";
        String sourceConfigHash = inst.sourceConfigHash != null ? inst.sourceConfigHash : "";
        String packageName = inst.packageName != null ? inst.packageName : "";
        String userTask = inst.userTask != null ? inst.userTask : "";
        String userPlaybook = inst.userPlaybook != null ? inst.userPlaybook : "";
        String mode = inst.taskMapMode != null ? inst.taskMapMode : "off";
        return TaskRouteKey.build(source, sourceId, sourceConfigHash, packageName, userTask, userPlaybook, mode);
    }

    private static String normalizeTaskMapMode(String mode) {
        String v = stringOrEmpty(mode).toLowerCase(Locale.ROOT);
        if ("ai".equals(v) || "manual".equals(v) || "off".equals(v)) {
            return v;
        }
        return "off";
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> selectTaskMemoryHint(String taskKey, String scheduleId) {
        if (scheduleId != null && !scheduleId.isEmpty()) {
            Map<String, Object> bySchedule = memoryByScheduleId.get(scheduleId);
            if (bySchedule != null && !bySchedule.isEmpty()) {
                return new LinkedHashMap<String, Object>(bySchedule);
            }
        }
        if (taskKey != null && !taskKey.isEmpty()) {
            Map<String, Object> byTask = memoryByTaskKey.get(taskKey);
            if (byTask != null && !byTask.isEmpty()) {
                return new LinkedHashMap<String, Object>(byTask);
            }
        }
        return null;
    }

    private void saveTaskMemoryFromSuccess(FsmTaskRequest req, TaskInstance instance, Map<String, Object> out) {
        try {
            if (out == null || out.isEmpty()) {
                return;
            }
            String status = String.valueOf(out.get("status"));
            if (!"success".equalsIgnoreCase(status)) {
                return;
            }
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("updated_at", System.currentTimeMillis());
            snapshot.put("task_id", instance.taskId);
            snapshot.put("user_task", req.userTask != null ? req.userTask : "");
            snapshot.put("package_name", stringOrEmpty(out.get("package_name")));
            snapshot.put("package_label", stringOrEmpty(out.get("package_label")));
            snapshot.put("target_page", stringOrEmpty(out.get("target_page")));
            snapshot.put("route_trace", copyList(out.get("route_trace"), 32));
            snapshot.put("command_log", copyList(out.get("command_log"), 64));
            snapshot.put("llm_history", copyList(out.get("llm_history"), 24));
            Object outputObj = out.get("output");
            if (outputObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> outputMap = (Map<String, Object>) outputObj;
                snapshot.put("output", new LinkedHashMap<String, Object>(outputMap));
            }
            String summaryText = deriveSummaryText(snapshot);
            snapshot.put("summary_text", summaryText);

            synchronized (memoryLock) {
                if (instance.taskMemoryKey != null && !instance.taskMemoryKey.isEmpty()) {
                    memoryByTaskKey.put(instance.taskMemoryKey, snapshot);
                }
                if (instance.scheduleId != null && !instance.scheduleId.isEmpty()) {
                    memoryByScheduleId.put(instance.scheduleId, snapshot);
                }
                saveTaskMemoryToDisk();
            }
        } catch (Exception ignored) {
        }
    }

    private static String deriveSummaryText(Map<String, Object> snapshot) {
        String pkg = stringOrEmpty(snapshot.get("package_name"));
        String label = stringOrEmpty(snapshot.get("package_label"));
        String app = !label.isEmpty() ? label : pkg;
        String page = stringOrEmpty(snapshot.get("target_page"));
        if (!app.isEmpty() && !page.isEmpty()) {
            return "Last successful route reached " + page + " in " + app + ".";
        }
        if (!app.isEmpty()) {
            return "Last successful task ran in " + app + ".";
        }
        return "Last successful task memory available.";
    }

    private static List<Object> copyList(Object obj, int maxItems) {
        List<Object> out = new ArrayList<Object>();
        if (!(obj instanceof List)) {
            return out;
        }
        List<?> in = (List<?>) obj;
        int n = Math.min(maxItems, in.size());
        for (int i = Math.max(0, in.size() - n); i < in.size(); i++) {
            out.add(in.get(i));
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private void loadTaskMemoryFromDisk() {
        persistence.loadTaskMemory(taskMemoryPath, memoryByTaskKey, memoryByScheduleId);
    }

    private void saveTaskMemoryToDisk() {
        persistence.saveTaskMemory(taskMemoryPath, memoryByTaskKey, memoryByScheduleId);
    }

    private void loadSchedulesFromDisk() {
        try {
            List<Object> rows = persistence.loadRows(schedulesPath, "schedules");
            if (rows == null) {
                return;
            }
            long now = System.currentTimeMillis();

            scheduleRegistry.clear();
            synchronized (scheduleOrder) {
                scheduleOrder.clear();
            }

            for (Object o : rows) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) o;
                ScheduledTaskDef def = parseScheduleRow(row, now);
                if (def == null || def.scheduleId == null || def.scheduleId.isEmpty()) {
                    continue;
                }
                scheduleRegistry.put(def.scheduleId, def);
                synchronized (scheduleOrder) {
                    scheduleOrder.addLast(def.scheduleId);
                }
            }
            synchronized (scheduleOrder) {
                while (scheduleOrder.size() > MAX_SCHEDULES) {
                    String evictId = scheduleOrder.pollFirst();
                    if (evictId != null) {
                        scheduleRegistry.remove(evictId);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSchedulesToDisk() {
        try {
            List<Object> rows = new ArrayList<Object>();
            List<String> ids;
            synchronized (scheduleOrder) {
                ids = new ArrayList<String>(scheduleOrder);
            }
            for (String id : ids) {
                ScheduledTaskDef def = scheduleRegistry.get(id);
                if (def == null) continue;
                rows.add(snapshotSchedule(def));
            }
            persistence.saveRows(schedulesPath, "schedules.v1", "schedules", rows);
        } catch (Exception ignored) {
        }
    }

    private void loadTaskRunsFromDisk() {
        try {
            List<Object> rows = persistence.loadRows(taskRunsPath, "tasks");
            if (rows == null) {
                return;
            }

            taskRegistry.clear();
            synchronized (taskOrder) {
                taskOrder.clear();
            }

            boolean normalized = false;
            for (Object o : rows) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) o;
                TaskInstance inst = parseTaskRunRow(row);
                if (inst == null || inst.taskId == null || inst.taskId.isEmpty()) {
                    continue;
                }
                if (inst.state == TaskState.RUNNING || inst.state == TaskState.PENDING) {
                    inst.state = TaskState.FAILED;
                    String priorReason = inst.reason != null ? inst.reason : "";
                    inst.reason = priorReason.isEmpty()
                            ? "recovered_after_restart"
                            : (priorReason + "; recovered_after_restart");
                    if (inst.finishedAt <= 0L) {
                        inst.finishedAt = System.currentTimeMillis();
                    }
                    normalized = true;
                }
                taskRegistry.put(inst.taskId, inst);
                synchronized (taskOrder) {
                    taskOrder.addLast(inst.taskId);
                }
            }
            synchronized (taskOrder) {
                while (taskOrder.size() > MAX_TASKS) {
                    String evictId = taskOrder.pollFirst();
                    if (evictId != null) {
                        taskRegistry.remove(evictId);
                    }
                }
            }
            if (normalized) {
                saveTaskRunsToDisk();
            }
        } catch (Exception ignored) {
        }
    }

    private void saveTaskRunsToDisk() {
        try {
            List<Object> rows = new ArrayList<Object>();
            List<String> ids;
            synchronized (taskOrder) {
                ids = new ArrayList<String>(taskOrder);
            }
            for (String id : ids) {
                TaskInstance inst = taskRegistry.get(id);
                if (inst == null) continue;
                rows.add(snapshotTaskRun(inst));
            }
            persistence.saveRows(taskRunsPath, "task_runs.v1", "tasks", rows);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> snapshotTaskRun(TaskInstance inst) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("task_id", inst.taskId);
        row.put("user_task", inst.userTask);
        row.put("state", inst.state != null ? inst.state.name() : "");
        row.put("created_at", inst.createdAt);
        row.put("started_at", inst.startedAt);
        row.put("finished_at", inst.finishedAt);
        row.put("final_state", inst.finalState);
        row.put("reason", inst.reason);
        row.put("package_name", inst.packageName);
        row.put("package_label", inst.packageLabel);
        row.put("target_page", inst.targetPage);
        row.put("source", inst.source);
        row.put("schedule_id", inst.scheduleId);
        row.put("user_playbook", inst.userPlaybook);
        row.put("source_id", inst.sourceId);
        row.put("source_config_hash", inst.sourceConfigHash);
        row.put("task_map_mode", inst.taskMapMode);
        row.put("task_memory_key", inst.taskMemoryKey);
        row.put("memory_applied", inst.memoryApplied);
        row.put("record_enabled", inst.recordEnabled);
        row.put("record_started", inst.recordStarted);
        row.put("record_file", inst.recordFilePath);
        TaskRouteKey routeKey = buildTaskRouteKey(inst);
        String persistedKeyHash = resolvePersistedTaskKeyHash(
                routeKey.taskKeyHash,
                inst.taskId,
                inst.source,
                inst.sourceId,
                inst.packageName,
                inst.userTask,
                inst.userPlaybook,
                inst.taskMapMode
        );
        row.put("task_key", routeKey.canonicalJson);
        row.put("task_key_hash", persistedKeyHash.isEmpty() ? routeKey.taskKeyHash : persistedKeyHash);
        row.put("has_task_map", taskMapStore.hasMap(persistedKeyHash.isEmpty() ? routeKey.taskKeyHash : persistedKeyHash));
        row.put("task_summary", inst.taskSummary);
        return row;
    }

    private TaskInstance parseTaskRunRow(Map<String, Object> row) {
        try {
            TaskInstance inst = new TaskInstance();
            inst.taskId = stringOrEmpty(row.get("task_id"));
            inst.userTask = stringOrEmpty(row.get("user_task"));
            inst.state = parseTaskState(stringOrEmpty(row.get("state")));
            inst.createdAt = toLong(row.get("created_at"), 0L);
            inst.startedAt = toLong(row.get("started_at"), 0L);
            inst.finishedAt = toLong(row.get("finished_at"), 0L);
            inst.finalState = stringOrEmpty(row.get("final_state"));
            inst.reason = stringOrEmpty(row.get("reason"));
            inst.packageName = stringOrEmpty(row.get("package_name"));
            inst.packageLabel = stringOrEmpty(row.get("package_label"));
            inst.targetPage = stringOrEmpty(row.get("target_page"));
            inst.source = stringOrEmpty(row.get("source"));
            inst.scheduleId = stringOrEmpty(row.get("schedule_id"));
            inst.userPlaybook = stringOrEmpty(row.get("user_playbook"));
            inst.sourceId = stringOrEmpty(row.get("source_id"));
            inst.sourceConfigHash = stringOrEmpty(row.get("source_config_hash"));
            inst.taskMapMode = stringOrEmpty(row.get("task_map_mode"));
            inst.taskMemoryKey = stringOrEmpty(row.get("task_memory_key"));
            inst.memoryApplied = toBool(row.get("memory_applied"), false);
            inst.recordEnabled = toBool(row.get("record_enabled"), false);
            inst.recordStarted = toBool(row.get("record_started"), false);
            inst.recordFilePath = stringOrEmpty(row.get("record_file"));
            inst.taskSummary = stringOrEmpty(row.get("task_summary"));
            return inst;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TaskState parseTaskState(String s) {
        if (s == null) return TaskState.FAILED;
        try {
            return TaskState.valueOf(s);
        } catch (Exception ignored) {
            return TaskState.FAILED;
        }
    }

    private ScheduledTaskDef parseScheduleRow(Map<String, Object> row, long now) {
        try {
            ScheduledTaskDef def = new ScheduledTaskDef();
            def.scheduleId = stringOrEmpty(row.get("schedule_id"));
            def.name = stringOrEmpty(row.get("name"));
            def.userTask = stringOrEmpty(row.get("user_task"));
            def.packageName = stringOrEmpty(row.get("package"));
            def.mapPath = stringOrEmpty(row.get("map_path"));
            if (def.mapPath.isEmpty()) def.mapPath = null;
            def.startPage = stringOrEmpty(row.get("start_page"));
            if (def.startPage.isEmpty()) def.startPage = null;
            def.traceMode = stringOrEmpty(row.get("trace_mode"));
            if (def.traceMode.isEmpty()) def.traceMode = null;
            int port = toInt(row.get("trace_udp_port"), 0);
            def.traceUdpPort = port > 0 ? Integer.valueOf(port) : null;
            def.userPlaybook = stringOrEmpty(row.get("user_playbook"));
            def.taskMapMode = stringOrEmpty(row.get("task_map_mode"));
            if (def.taskMapMode.isEmpty()) {
                def.taskMapMode = "off";
            }
            def.recordEnabled = toBool(row.get("record_enabled"), false);
            def.runAtMs = toLong(row.get("run_at"), 0L);
            def.repeatMode = CortexScheduleTime.normalizeRepeatMode(stringOrEmpty(row.get("repeat_mode")));
            def.repeatWeekdays = toInt(row.get("repeat_weekdays"), 0) & 0x7F;
            if ("weekly".equals(def.repeatMode) && def.repeatWeekdays == 0) {
                def.repeatWeekdays = 0b0011111;
            }
            def.nextRunAt = toLong(row.get("next_run_at"), 0L);
            def.lastTriggeredAt = toLong(row.get("last_triggered_at"), 0L);
            def.triggerCount = toLong(row.get("trigger_count"), 0L);
            def.enabled = toBool(row.get("enabled"), true);
            def.createdAt = toLong(row.get("created_at"), now);
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(def.runAtMs > 0 ? def.runAtMs : now);
            def.hourOfDay = c.get(Calendar.HOUR_OF_DAY);
            def.minuteOfHour = c.get(Calendar.MINUTE);

            if (def.enabled) {
                if ("once".equals(def.repeatMode)) {
                    if (def.nextRunAt <= 0L) {
                        def.nextRunAt = def.runAtMs;
                    }
                    if (def.nextRunAt <= 0L) {
                        def.enabled = false;
                    }
                } else if ("daily".equals(def.repeatMode)) {
                    if (def.nextRunAt <= 0L) {
                        def.nextRunAt = CortexScheduleTime.computeNextDailyRun(def.hourOfDay, def.minuteOfHour, now);
                    }
                } else if ("weekly".equals(def.repeatMode)) {
                    if (def.nextRunAt <= 0L) {
                        def.nextRunAt = CortexScheduleTime.computeNextWeeklyRun(
                                def.hourOfDay,
                                def.minuteOfHour,
                                def.repeatWeekdays,
                                now
                        );
                    }
                }
            }
            return def;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static long toLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static boolean toBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }


    private static class ScheduledTaskDef {
        String scheduleId;
        String name;
        String userTask;
        String packageName;
        String mapPath;
        String startPage;
        String traceMode;
        Integer traceUdpPort;
        String userPlaybook;
        String taskMapMode;
        boolean recordEnabled;

        long runAtMs;
        String repeatMode;
        int repeatWeekdays;
        int hourOfDay;
        int minuteOfHour;
        long nextRunAt;
        long lastTriggeredAt;
        long triggerCount;
        long createdAt;
        boolean enabled;
    }

    private static Map<String, Object> snapshotSchedule(ScheduledTaskDef def) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("schedule_id", def.scheduleId);
        out.put("name", def.name);
        out.put("user_task", def.userTask);
        out.put("package", def.packageName);
        out.put("map_path", def.mapPath);
        out.put("start_page", def.startPage);
        out.put("trace_mode", def.traceMode);
        out.put("trace_udp_port", def.traceUdpPort);
        out.put("user_playbook", def.userPlaybook);
        out.put("task_map_mode", def.taskMapMode);
        out.put("record_enabled", def.recordEnabled);
        out.put("run_at", def.runAtMs);
        out.put("repeat_mode", def.repeatMode);
        out.put("repeat_weekdays", def.repeatWeekdays);
        out.put("repeat_daily", "daily".equals(def.repeatMode));
        out.put("next_run_at", def.nextRunAt);
        out.put("last_triggered_at", def.lastTriggeredAt);
        out.put("trigger_count", def.triggerCount);
        out.put("enabled", def.enabled);
        out.put("created_at", def.createdAt);
        return out;
    }


    private static String resolveTaskMemoryPath() {
        String override = System.getProperty("lxb.task.memory.path");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return DEFAULT_TASK_MEMORY_PATH;
    }

    private static String resolveSchedulesPath(String taskMemoryPath) {
        String override = System.getProperty("lxb.schedules.path");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        File p = new File(taskMemoryPath).getParentFile();
        if (p != null) {
            return new File(p, "schedules.v1.json").getAbsolutePath();
        }
        return DEFAULT_SCHEDULES_PATH;
    }

    private static String resolveTaskRunsPath(String taskMemoryPath) {
        String override = System.getProperty("lxb.task.runs.path");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        File p = new File(taskMemoryPath).getParentFile();
        if (p != null) {
            return new File(p, "task_runs.v1.json").getAbsolutePath();
        }
        return DEFAULT_TASK_RUNS_PATH;
    }

    // Future public methods (not implemented yet):
    // - submitTask(...) -> task_id (async)
    // - listTasks()
}

