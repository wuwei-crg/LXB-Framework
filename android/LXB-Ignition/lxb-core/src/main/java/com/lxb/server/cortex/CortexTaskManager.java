package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
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
    private final String schedulesPath;
    private final String taskRunsPath;

    // Dedicated worker/scheduler threads.
    private final Thread workerThread;
    private final Thread schedulerThread;

    // Simple global cancellation flag for the single-worker FSM. When true,
    // the current FSM run will notice and exit at the next state boundary.
    private volatile boolean cancelRequested = false;

    public CortexTaskManager(CortexFsmEngine fsmEngine) {
        this.fsmEngine = fsmEngine;
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
        return submitTask(userTask, packageName, mapPath, startPage, traceMode, traceUdpPort, null);
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
        return submitTaskInternal(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                "manual",
                null,
                userPlaybook
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
            String userPlaybook
    ) {
        long now = System.currentTimeMillis();
        TaskInstance instance = new TaskInstance();
        instance.taskId = UUID.randomUUID().toString();
        instance.userTask = userTask != null ? userTask : "";
        instance.source = source != null ? source : "manual";
        instance.scheduleId = scheduleId;
        instance.userPlaybook = userPlaybook != null ? userPlaybook.trim() : "";
        String taskKey = buildTaskMemoryKey(instance.userTask);
        instance.taskMemoryKey = taskKey;
        Map<String, Object> memoryHint = selectTaskMemoryHint(taskKey, scheduleId);
        instance.memoryApplied = memoryHint != null && !memoryHint.isEmpty();
        instance.state = TaskState.PENDING;
        instance.createdAt = now;

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
            String userPlaybook
    ) {
        if (userTask == null || userTask.trim().isEmpty()) {
            throw new IllegalArgumentException("user_task is required");
        }
        if (runAtMs <= 0) {
            throw new IllegalArgumentException("run_at is required and must be > 0");
        }
        String repeatMode = normalizeRepeatMode(repeatModeRaw);
        long now = System.currentTimeMillis();
        int normalizedMask = (repeatWeekdays & 0x7F);
        if ("weekly".equals(repeatMode) && normalizedMask == 0) {
            throw new IllegalArgumentException("repeat_weekdays is required for weekly schedule");
        }
        long firstRunAt = computeFirstRunAt(runAtMs, repeatMode, normalizedMask, now);

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
        def.runAtMs = runAtMs;
        def.repeatMode = repeatMode;
        def.repeatWeekdays = normalizedMask;
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
            String userPlaybook
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

        String repeatMode = normalizeRepeatMode(repeatModeRaw);
        int normalizedMask = (repeatWeekdays & 0x7F);
        if ("weekly".equals(repeatMode) && normalizedMask == 0) {
            throw new IllegalArgumentException("repeat_weekdays is required for weekly schedule");
        }

        synchronized (def) {
            long now = System.currentTimeMillis();
            long firstRunAt = computeFirstRunAt(runAtMs, repeatMode, normalizedMask, now);
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

            def.runAtMs = runAtMs;
            def.repeatMode = repeatMode;
            def.repeatWeekdays = normalizedMask;
            def.hourOfDay = c.get(Calendar.HOUR_OF_DAY);
            def.minuteOfHour = c.get(Calendar.MINUTE);
            def.nextRunAt = firstRunAt;
            def.enabled = true;
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
                            def.nextRunAt = computeNextDailyRun(def.hourOfDay, def.minuteOfHour, now2 + 1000L);
                        } else if ("weekly".equals(def.repeatMode)) {
                            def.nextRunAt = computeNextWeeklyRun(
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
                                    def.userPlaybook
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

                try {
                    Map<String, Object> out = fsmEngine.run(
                            req.userTask,
                            req.packageName,
                            req.mapPath,
                            req.startPage,
                            req.traceMode,
                            req.traceUdpPort,
                            req.userPlaybook,
                            req.taskMemoryHint,
                            instance.taskId,
                            checker
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
                    Object target = out.get("target_page");
                    if (target != null) {
                        instance.targetPage = String.valueOf(target);
                    }
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
                }
            } catch (InterruptedException e) {
                // Allow graceful shutdown if ever needed.
                Thread.currentThread().interrupt();
                break;
            }
        }
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
            this.instance = instance;
        }
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
        String targetPage;
        String source;           // manual | schedule
        String scheduleId;       // nullable
        String userPlaybook;     // optional guidance text
        String taskMemoryKey;    // normalized key
        boolean memoryApplied;   // whether this run consumed memory hint
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
        out.put("target_page", inst.targetPage);
        out.put("source", inst.source);
        out.put("schedule_id", inst.scheduleId);
        out.put("task_memory_key", inst.taskMemoryKey);
        out.put("memory_applied", inst.memoryApplied);
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
            row.put("target_page", inst.targetPage);
            row.put("source", inst.source);
            row.put("schedule_id", inst.scheduleId);
            row.put("task_memory_key", inst.taskMemoryKey);
            row.put("memory_applied", inst.memoryApplied);
            result.add(row);
        }
        return result;
    }

    private static String buildTaskMemoryKey(String userTask) {
        String s = userTask != null ? userTask.trim().toLowerCase() : "";
        if (s.isEmpty()) {
            return "task:empty";
        }
        return "task:" + s;
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
        String page = stringOrEmpty(snapshot.get("target_page"));
        if (!pkg.isEmpty() && !page.isEmpty()) {
            return "Last successful route reached " + page + " in " + pkg + ".";
        }
        if (!pkg.isEmpty()) {
            return "Last successful task ran in package " + pkg + ".";
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
        try {
            File f = new File(taskMemoryPath);
            if (!f.exists() || !f.isFile()) {
                return;
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object byTaskObj = root.get("memory_by_task_key");
            if (byTaskObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) byTaskObj;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!(e.getValue() instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) e.getValue();
                    memoryByTaskKey.put(e.getKey(), new LinkedHashMap<String, Object>(row));
                }
            }
            Object byScheduleObj = root.get("memory_by_schedule_id");
            if (byScheduleObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) byScheduleObj;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!(e.getValue() instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) e.getValue();
                    memoryByScheduleId.put(e.getKey(), new LinkedHashMap<String, Object>(row));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveTaskMemoryToDisk() {
        try {
            File f = new File(taskMemoryPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("memory_by_task_key", new LinkedHashMap<String, Object>(memoryByTaskKey));
            root.put("memory_by_schedule_id", new LinkedHashMap<String, Object>(memoryByScheduleId));
            String json = Json.stringify(root);
            Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void loadSchedulesFromDisk() {
        try {
            Map<String, Object> root = loadJsonRootWithBackup(schedulesPath);
            if (root == null) {
                return;
            }
            Object rowsObj = root.get("schedules");
            if (!(rowsObj instanceof List)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) rowsObj;
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
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("schema_version", "schedules.v1");
            root.put("updated_at", System.currentTimeMillis());
            root.put("schedules", rows);
            writeJsonAtomically(schedulesPath, Json.stringify(root));
        } catch (Exception ignored) {
        }
    }

    private void loadTaskRunsFromDisk() {
        try {
            Map<String, Object> root = loadJsonRootWithBackup(taskRunsPath);
            if (root == null) {
                return;
            }
            Object rowsObj = root.get("tasks");
            if (!(rowsObj instanceof List)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) rowsObj;

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
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("schema_version", "task_runs.v1");
            root.put("updated_at", System.currentTimeMillis());
            root.put("tasks", rows);
            writeJsonAtomically(taskRunsPath, Json.stringify(root));
        } catch (Exception ignored) {
        }
    }

    private static Map<String, Object> snapshotTaskRun(TaskInstance inst) {
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
        row.put("target_page", inst.targetPage);
        row.put("source", inst.source);
        row.put("schedule_id", inst.scheduleId);
        row.put("user_playbook", inst.userPlaybook);
        row.put("task_memory_key", inst.taskMemoryKey);
        row.put("memory_applied", inst.memoryApplied);
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
            inst.targetPage = stringOrEmpty(row.get("target_page"));
            inst.source = stringOrEmpty(row.get("source"));
            inst.scheduleId = stringOrEmpty(row.get("schedule_id"));
            inst.userPlaybook = stringOrEmpty(row.get("user_playbook"));
            inst.taskMemoryKey = stringOrEmpty(row.get("task_memory_key"));
            inst.memoryApplied = toBool(row.get("memory_applied"), false);
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
            def.runAtMs = toLong(row.get("run_at"), 0L);
            def.repeatMode = normalizeRepeatMode(stringOrEmpty(row.get("repeat_mode")));
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
                        def.nextRunAt = computeNextDailyRun(def.hourOfDay, def.minuteOfHour, now);
                    }
                } else if ("weekly".equals(def.repeatMode)) {
                    if (def.nextRunAt <= 0L) {
                        def.nextRunAt = computeNextWeeklyRun(
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

    private static Map<String, Object> loadJsonRootWithBackup(String path) {
        Object primary = parseJsonFile(path);
        if (primary instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) primary;
            return root;
        }
        Object backup = parseJsonFile(path + ".bak");
        if (backup instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) backup;
            return root;
        }
        return null;
    }

    private static Object parseJsonFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return Json.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeJsonAtomically(String path, String json) throws Exception {
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(path + ".tmp");
        File bak = new File(path + ".bak");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            //noinspection ResultOfMethodCallIgnored
            target.renameTo(bak);
        }
        if (!tmp.renameTo(target)) {
            Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
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

    private static long computeNextDailyRun(int hour, int minute, long baseMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(baseMs);

        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(baseMs);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        if (next.getTimeInMillis() <= now.getTimeInMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        return next.getTimeInMillis();
    }

    private static long computeNextWeeklyRun(int hour, int minute, int weekdaysMask, long baseMs) {
        if ((weekdaysMask & 0x7F) == 0) {
            return 0L;
        }
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(baseMs);
        long best = Long.MAX_VALUE;
        for (int offset = 0; offset <= 7; offset++) {
            Calendar cand = (Calendar) base.clone();
            cand.add(Calendar.DAY_OF_MONTH, offset);
            cand.set(Calendar.SECOND, 0);
            cand.set(Calendar.MILLISECOND, 0);
            cand.set(Calendar.HOUR_OF_DAY, hour);
            cand.set(Calendar.MINUTE, minute);
            int dayIndex = toMonFirstDayIndex(cand.get(Calendar.DAY_OF_WEEK));
            if (((weekdaysMask >> dayIndex) & 1) == 0) {
                continue;
            }
            long t = cand.getTimeInMillis();
            if (t > baseMs && t < best) {
                best = t;
            }
        }
        if (best != Long.MAX_VALUE) {
            return best;
        }
        // Fallback: next selected day in the following week.
        for (int offset = 1; offset <= 14; offset++) {
            Calendar cand = (Calendar) base.clone();
            cand.add(Calendar.DAY_OF_MONTH, offset);
            cand.set(Calendar.SECOND, 0);
            cand.set(Calendar.MILLISECOND, 0);
            cand.set(Calendar.HOUR_OF_DAY, hour);
            cand.set(Calendar.MINUTE, minute);
            int dayIndex = toMonFirstDayIndex(cand.get(Calendar.DAY_OF_WEEK));
            if (((weekdaysMask >> dayIndex) & 1) == 1) {
                return cand.getTimeInMillis();
            }
        }
        return 0L;
    }

    // Convert Calendar day to Mon=0 ... Sun=6.
    private static int toMonFirstDayIndex(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:
                return 0;
            case Calendar.TUESDAY:
                return 1;
            case Calendar.WEDNESDAY:
                return 2;
            case Calendar.THURSDAY:
                return 3;
            case Calendar.FRIDAY:
                return 4;
            case Calendar.SATURDAY:
                return 5;
            case Calendar.SUNDAY:
            default:
                return 6;
        }
    }

    private static boolean isWeekdaySelected(long whenMs, int weekdaysMask) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(whenMs);
        int dayIndex = toMonFirstDayIndex(c.get(Calendar.DAY_OF_WEEK));
        return ((weekdaysMask >> dayIndex) & 1) == 1;
    }

    private static String normalizeRepeatMode(String repeatModeRaw) {
        String s = repeatModeRaw != null ? repeatModeRaw.trim().toLowerCase() : "";
        if ("daily".equals(s) || "weekly".equals(s) || "once".equals(s)) {
            return s;
        }
        return "once";
    }

    private static long computeFirstRunAt(long runAtMs, String repeatMode, int repeatWeekdays, long now) {
        if ("daily".equals(repeatMode)) {
            if (runAtMs > now) {
                return runAtMs;
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(runAtMs);
            return computeNextDailyRun(
                    c.get(Calendar.HOUR_OF_DAY),
                    c.get(Calendar.MINUTE),
                    now
            );
        }
        if ("weekly".equals(repeatMode)) {
            if (runAtMs > now && isWeekdaySelected(runAtMs, repeatWeekdays)) {
                return runAtMs;
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(runAtMs);
            return computeNextWeeklyRun(
                    c.get(Calendar.HOUR_OF_DAY),
                    c.get(Calendar.MINUTE),
                    repeatWeekdays,
                    now
            );
        }
        if (runAtMs <= now) {
            throw new IllegalArgumentException("run_at must be in the future for one-shot schedule");
        }
        return runAtMs;
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
