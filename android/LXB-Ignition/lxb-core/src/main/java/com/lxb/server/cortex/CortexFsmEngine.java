package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Java port of the Python CortexFSMEngine skeleton.
 *
 * Goal for now:
 * - Mirror the high-level FSM structure and state transitions:
 *   INIT -> APP_RESOLVE -> ROUTE_PLAN -> ROUTING -> VISION_ACT -> FINISH/FAIL
 * - Provide a Context object and run() signature compatible with Python:
 *   status/state/package_name/target_page/route_result/command_log/llm_history/lessons/reason
 * - Gradually fill internal behavior with end-side engines (LLM planner, routing, VLM actions).
 */
public class CortexFsmEngine {

    public enum State {
        INIT,
        TASK_DECOMPOSE,
        APP_RESOLVE,
        ROUTE_PLAN,
        ROUTING,
        VISION_ACT,
        FINISH,
        FAIL
    }

    /**
     * Simple cancellation checker used by the FSM run loop. The implementation
     * is provided by CortexTaskManager so that a separate command can request
     * cancellation of the currently running task.
     */
    public interface CancellationChecker {
        boolean isCancelled();
    }

    /**
     * Execution context for a single Cortex automation task.
     * Mirrors the Python CortexContext dataclass in a minimal form.
     */
    public static class Context {
        public final String taskId;
        public String userTask = "";
        public String rootUserTask = "";
        public String mapPath = null;
        public String startPage = null;

        public String selectedPackage = "";
        public String selectedPackageLabel = "";
        public String targetPage = "";

        public final List<String> routeTrace = new ArrayList<>();
        public final List<Map<String, Object>> commandLog = new ArrayList<>();
        public final Map<String, Object> routeResult = new LinkedHashMap<>();

        public String error = "";
        public final Map<String, Object> output = new LinkedHashMap<>();

        public int visionTurns = 0;
        public final List<Map<String, Object>> llmHistory = new ArrayList<>();
        public final List<String> lessons = new ArrayList<>();

        // Vision loop tracking (simplified port from Python)
        public String lastCommand = "";
        public int sameCommandStreak = 0;
        public String lastActivitySig = "";
        public int sameActivityStreak = 0;

        // INIT-related fields
        public final Map<String, Object> deviceInfo = new LinkedHashMap<>();
        public final Map<String, Object> currentActivity = new LinkedHashMap<>();
        public final List<Map<String, Object>> appCandidates = new ArrayList<>();
        public final List<Map<String, Object>> pageCandidates = new ArrayList<>();
        public final Map<String, Object> coordProbe = new LinkedHashMap<>();

        // Task decomposition (v2) fields
        public final List<SubTask> subTasks = new ArrayList<>();
        public String taskType = "";
        public final Map<String, Object> blackboard = new LinkedHashMap<>();

        // Current sub_task runtime context
        public SubTask currentSubTask = null;
        public int currentSubTaskIndex = -1;
        public boolean currentSubTaskIsLast = false;

        // External semantic history (maintained by host, not by model memory).
        // Each row contains:
        // instruction, expected, actual, judgement_prev, judgement_global, carry_context.
        public final List<Map<String, Object>> visionHistory = new ArrayList<>();
        public String pendingHistoryInstruction = "";
        public String pendingHistoryExpected = "";
        public String pendingHistoryCarryContext = "";
        // Durable memory written by model across turns for long-context tasks.
        public final List<String> workingMemory = new ArrayList<>();

        // Optional guidance injected by TaskManager.
        public String userPlaybook = "";
        public final Map<String, Object> taskMemoryHint = new LinkedHashMap<>();

        // Device unlock/lock policy (loaded from config).
        public boolean autoUnlockBeforeRoute = true;
        public boolean autoLockAfterTask = true;
        public String unlockPin = "";
        public boolean unlockedByFsm = false;

        public Context(String taskId) {
            this.taskId = taskId;
        }
    }

    private final PerceptionEngine perception;
    private final ExecutionEngine execution;
    private final TraceLogger trace;
    private final LlmClient llmClient;
    private final MapManager mapManager;
    private final MapPromptPlanner mapPlanner;
    private static final int LAUNCH_RETRY_MAX = 3;
    private static final long LAUNCH_WAIT_TIMEOUT_MS = 8000L;
    private static final long LAUNCH_WAIT_SAMPLE_MS = 350L;
    private static final int LAUNCH_READY_REQUIRED_HITS = 1;
    private static final long LAUNCH_POST_START_WAIT_MS = 1200L;
    private static final int INPUT_METHOD_ADB = 0;
    private static final int INPUT_METHOD_CLIPBOARD = 1;
    private static final long UI_SETTLE_TIMEOUT_MS = 2500L;
    private static final long UI_SETTLE_SAMPLE_MS = 500L;
    private static final long UI_SETTLE_FALLBACK_MS = 600L;
    private static final long UI_SETTLE_PRE_WAIT_MS = 1000L;
    private static final long SWIPE_POST_WAIT_MS = 1500L;
    private static final double UI_SETTLE_SIM_THRESHOLD = 0.90d;
    private static final int UI_SETTLE_REQUIRED_HITS = 2;
    private static final int FAST_SKIP_MAX_TURNS = 2;
    private static final long FAST_SKIP_POST_TAP_SLEEP_MS = 220L;
    private static final int VISION_MAX_TURNS_SINGLE = 100;
    private static final int VISION_MAX_TURNS_LOOP = 100;
    private static final long UNLOCK_POST_CMD_SLEEP_MS = 3000L;
    private static final long UNLOCK_POST_PIN_SLEEP_MS = 1200L;
    private static final long UNLOCK_POST_HOME_SLEEP_MS = 700L;
    private static final long UNLOCK_STABLE_TIMEOUT_MS = 3000L;
    private static final long UNLOCK_STABLE_SAMPLE_MS = 300L;
    private static final int UNLOCK_STABLE_REQUIRED_HITS = 2;
    private static final long ROUTING_FIRST_STEP_WINDOW_MS = 10000L;
    private static final long ROUTING_FIRST_STEP_SAMPLE_MS = 350L;
    private static final long ROUTING_FIRST_STEP_AFTER_SKIP_SLEEP_MS = 300L;
    private static final int ROUTING_FIRST_STEP_SKIP_MIN_SCORE = 120;
    private static final int ROUTING_STEP_RESOLVE_RETRY_MAX = 3;
    private static final long ROUTING_STEP_RESOLVE_RETRY_SLEEP_MS = 300L;
    private static final int ROUTING_RECOVERY_MAX = 2;
    private static final long ROUTING_RECOVERY_POST_ACTION_SLEEP_MS = 300L;
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_POWER = 26;

    // Allowed ops per state, mirroring Python _ALLOWED_OPS
    private static final java.util.Set<String> VISION_ALLOWED_OPS = new java.util.HashSet<>();

    static {
        VISION_ALLOWED_OPS.add("TAP");
        VISION_ALLOWED_OPS.add("SWIPE");
        VISION_ALLOWED_OPS.add("INPUT");
        VISION_ALLOWED_OPS.add("WAIT");
        VISION_ALLOWED_OPS.add("BACK");
        VISION_ALLOWED_OPS.add("DONE");
        VISION_ALLOWED_OPS.add("FAIL");
    }

    public CortexFsmEngine(PerceptionEngine perception,
                           ExecutionEngine execution,
                           MapManager mapManager,
                           TraceLogger trace) {
        this.perception = perception;
        this.execution = execution;
        this.trace = trace;
        this.llmClient = new LlmClient();
        this.mapManager = mapManager;
        this.mapPlanner = new MapPromptPlanner(llmClient);
    }

    /**
     * Expose a thin system_control wrapper so TaskManager can run task-level
     * lifecycle hooks (e.g., DND/recording) without changing FSM state flow.
     */
    public Map<String, Object> runSystemControl(String taskId, String action, Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("action", action != null ? action.trim() : "");
        if (args != null && !args.isEmpty()) {
            req.putAll(args);
        }

        Map<String, Object> out = parseSystemControlResponse(
                execution != null
                        ? execution.handleSystemControl(Json.stringify(req).getBytes(StandardCharsets.UTF_8))
                        : null,
                action
        );

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId != null ? taskId : "");
        ev.put("action", action != null ? action : "");
        ev.put("ok", toBool(out.get("ok"), false));
        ev.put("result", out);
        trace.event("fsm_task_system_control", ev);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSystemControlResponse(byte[] resp, String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("action", action != null ? action : "");
        if (resp == null || resp.length < 3) {
            out.put("error", "empty_response");
            return out;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
            int status = buf.get() & 0xFF;
            int jsonLen = buf.getShort() & 0xFFFF;
            out.put("transport_ok", status != 0);
            if (jsonLen > 0 && buf.remaining() >= jsonLen) {
                byte[] jsonBytes = new byte[jsonLen];
                buf.get(jsonBytes);
                String jsonText = new String(jsonBytes, StandardCharsets.UTF_8);
                Object parsed = Json.parse(jsonText);
                if (parsed instanceof Map) {
                    out.clear();
                    out.putAll((Map<String, Object>) parsed);
                    if (!out.containsKey("action")) {
                        out.put("action", action != null ? action : "");
                    }
                } else {
                    out.put("raw", jsonText);
                    out.put("ok", status != 0);
                }
            } else {
                out.put("ok", status != 0);
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e));
        }
        return out;
    }

    /**
     * Sub-task contract for TASK_DECOMPOSE (v2).
     */
    public static class SubTask {
        public String id = "";
        public String description = "";
        public String mode = "";          // "single" | "loop"
        public String appHint = "";
        public final List<String> inputs = new ArrayList<>();
        public final List<String> outputs = new ArrayList<>();
        public String successCriteria = "";
        public LoopMetadata loopMetadata = null;
    }

    /**
     * Loop-specific metadata for loop sub-tasks.
     */
    public static class LoopMetadata {
        public String loopUnit = "";
        public String loopTargetCondition = "";
        public String loopTerminationCriteria = "";
        public int maxIterations = 0;
    }

    /**
     * Run a Cortex FSM task.
     *
     * v2 structure:
     * - INIT: device/app discovery, coord probe.
     * - TASK_DECOMPOSE: call LLM to produce sub_tasks contracts (optional, best-effort).
     * - For each sub_task (currently using existing APP_RESOLVE/ROUTE_PLAN/ROUTING/VISION_ACT):
     *   - APP_RESOLVE
     *   - ROUTE_PLAN
     *   - ROUTING
     *   - VISION_ACT
     * - Overall FINISH/FAIL is aggregated over all sub_tasks.
     */
    public Map<String, Object> run(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String userPlaybook,
            Map<String, Object> taskMemoryHint,
            String taskIdOverride,
            CancellationChecker cancellationChecker
    ) {
        String effectiveTaskId = (taskIdOverride != null && !taskIdOverride.isEmpty())
                ? taskIdOverride
                : UUID.randomUUID().toString();
        Context ctx = new Context(effectiveTaskId);
        ctx.userTask = userTask != null ? userTask : "";
        ctx.rootUserTask = ctx.userTask;
        ctx.mapPath = mapPath;
        ctx.startPage = startPage;
        ctx.userPlaybook = userPlaybook != null ? userPlaybook.trim() : "";
        if (ctx.userPlaybook.isEmpty() && taskMemoryHint != null && !taskMemoryHint.isEmpty()) {
            ctx.taskMemoryHint.putAll(taskMemoryHint);
        }

        String initialPackageName = packageName != null ? packageName : "";
        ctx.selectedPackage = initialPackageName;
        loadUnlockPolicyFromConfig(ctx);

        boolean enablePush = "push".equalsIgnoreCase(traceMode)
                && traceUdpPort != null
                && traceUdpPort.intValue() > 0;
        if (enablePush) {
            // Push to localhost; the Android front-end listens on this port.
            trace.setPushTarget("127.0.0.1", traceUdpPort.intValue(), ctx.taskId);
        }

        try {
            // 1) INIT + TASK_DECOMPOSE (single pass)
            State state = State.INIT;
            for (int i = 0; i < 30; i++) {
                if (cancellationChecker != null && cancellationChecker.isCancelled()) {
                    ctx.error = "cancelled_by_user";
                    Map<String, Object> cancelEv = new LinkedHashMap<>();
                    cancelEv.put("task_id", ctx.taskId);
                    trace.event("fsm_task_cancelled", cancelEv);
                    state = State.FAIL;
                    break;
                }
                if (state == State.INIT) {
                    state = runInitState(ctx);
                    continue;
                }
                if (state == State.TASK_DECOMPOSE) {
                    state = runTaskDecomposeState(ctx);
                    break; // after decomposition, move on to per-sub_task execution
                }
                // Any other state here is unexpected; bail out.
                break;
            }

            // 2) Determine effective sub_tasks: if decomposition failed/empty, fallback to a single synthetic sub_task.
            List<SubTask> effectiveSubTasks = new ArrayList<>();
            if (ctx.subTasks.isEmpty()) {
                SubTask st = new SubTask();
                st.id = "default";
                st.description = ctx.userTask != null ? ctx.userTask : "";
                st.mode = "single";
                st.appHint = "";
                effectiveSubTasks.add(st);
            } else {
                effectiveSubTasks.addAll(ctx.subTasks);
            }

            boolean overallSuccess = true;
            State lastState = State.FINISH;
            boolean anyMemoryFastPathUsed = false;
            boolean anyMemoryFastPathFallback = false;

            // 3) Execute each sub_task using the existing APP_RESOLVE/ROUTE_PLAN/ROUTING/VISION_ACT pipeline.
            for (int idx = 0; idx < effectiveSubTasks.size(); idx++) {
                SubTask st = effectiveSubTasks.get(idx);

                // For now, loop sub_tasks are still executed with the same single-shot pipeline.
                // Later we will add dedicated loop semantics in VISION_ACT.

                // Reset per-sub_task fields.
                ctx.targetPage = "";
                ctx.routeResult.clear();
                ctx.visionTurns = 0;
                ctx.lastCommand = "";
                ctx.sameCommandStreak = 0;
                ctx.sameActivityStreak = 0;
                ctx.selectedPackageLabel = "";
                ctx.currentSubTask = st;
                ctx.currentSubTaskIndex = idx;
                ctx.currentSubTaskIsLast = (idx == effectiveSubTasks.size() - 1);
                ctx.visionHistory.clear();
                ctx.pendingHistoryInstruction = "";
                ctx.pendingHistoryExpected = "";
                ctx.pendingHistoryCarryContext = "";
                ctx.workingMemory.clear();

                // Select package:
                // - caller-provided package has highest priority.
                // - sub_task appHint is intentionally NOT used for execution routing.
                //   APP_RESOLVE remains the single source of truth for package selection.
                if (!initialPackageName.isEmpty()) {
                    ctx.selectedPackage = initialPackageName;
                } else {
                    ctx.selectedPackage = "";
                }

                // Use sub_task description as current userTask for prompts.
                String globalTask = ctx.userTask != null ? ctx.userTask : "";
                String subDesc = (st.description != null && !st.description.isEmpty()) ? st.description : globalTask;
                ctx.userTask = subDesc;

                Map<String, Object> subBegin = new LinkedHashMap<>();
                subBegin.put("task_id", ctx.taskId);
                subBegin.put("index", idx);
                subBegin.put("sub_task_id", st.id);
                subBegin.put("mode", st.mode);
                subBegin.put("app_hint", st.appHint);
                subBegin.put("app_hint_used", false);
                trace.event("fsm_sub_task_begin", subBegin);

                // Memory fast path is intentionally disabled for now.
                // Always use the full pipeline to keep execution deterministic.
                state = State.APP_RESOLVE;
                int subTaskStepLimit = Math.max(40, resolveVisionMaxTurns(ctx) + 20);
                for (int step = 0; step < subTaskStepLimit; step++) {
                    if (cancellationChecker != null && cancellationChecker.isCancelled()) {
                        ctx.error = "cancelled_by_user";
                        Map<String, Object> cancelEv = new LinkedHashMap<>();
                        cancelEv.put("task_id", ctx.taskId);
                        trace.event("fsm_task_cancelled", cancelEv);
                        state = State.FAIL;
                        break;
                    }
                    if (state == State.APP_RESOLVE) {
                        state = runAppResolveState(ctx);
                        continue;
                    }
                    if (state == State.ROUTE_PLAN) {
                        state = runRoutePlanState(ctx);
                        continue;
                    }
                    if (state == State.ROUTING) {
                        state = runRoutingState(ctx);
                        continue;
                    }
                    if (state == State.VISION_ACT) {
                        state = runVisionActState(ctx);
                        continue;
                    }
                    if (state == State.FINISH || state == State.FAIL) {
                        break;
                    }
                    // Safety: unknown state inside sub_task.
                    ctx.error = "unknown_state:" + state.name();
                    state = State.FAIL;
                    break;
                }
                if (state != State.FINISH && state != State.FAIL) {
                    ctx.error = "sub_task_step_limit_exceeded";
                    Map<String, Object> limitEv = new LinkedHashMap<>();
                    limitEv.put("task_id", ctx.taskId);
                    limitEv.put("sub_task_id", st.id);
                    limitEv.put("mode", st.mode);
                    limitEv.put("step_limit", subTaskStepLimit);
                    limitEv.put("vision_turns", ctx.visionTurns);
                    limitEv.put("state", state.name());
                    trace.event("fsm_sub_task_step_limit", limitEv);
                    state = State.FAIL;
                }

                lastState = state;

                Map<String, Object> subEnd = new LinkedHashMap<>();
                subEnd.put("task_id", ctx.taskId);
                subEnd.put("index", idx);
                subEnd.put("sub_task_id", st.id);
                subEnd.put("mode", st.mode);
                subEnd.put("status", state == State.FINISH ? "success" : "failed");
                trace.event("fsm_sub_task_end", subEnd);

                // After a successful sub_task, best-effort reset: go HOME and stop the app.
                if (state == State.FINISH) {
                    safeResetToHomeAndStopApp(ctx);
                }

                // Restore global task text for logging / next sub_task.
                ctx.userTask = globalTask;

                if (state != State.FINISH) {
                    overallSuccess = false;
                    break;
                }
            }

            State finalState = overallSuccess ? State.FINISH : State.FAIL;
            ctx.currentSubTask = null;
            ctx.currentSubTaskIndex = -1;
            ctx.currentSubTaskIsLast = false;
            tryAutoLockAfterTask(ctx, finalState);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", finalState == State.FINISH ? "success" : "failed");
            out.put("task_id", ctx.taskId);
            out.put("state", finalState.name());
            out.put("package_name", ctx.selectedPackage);
            out.put("package_label", ctx.selectedPackageLabel != null ? ctx.selectedPackageLabel : "");
            out.put("target_page", ctx.targetPage != null ? ctx.targetPage : "");
            out.put("route_trace", new ArrayList<>(ctx.routeTrace));
            out.put("route_result", new LinkedHashMap<>(ctx.routeResult));
            out.put("command_log", new ArrayList<>(ctx.commandLog));
            out.put("llm_history", new ArrayList<>(ctx.llmHistory));
            out.put("lessons", new ArrayList<>(ctx.lessons));
            out.put("guidance_playbook_used", !ctx.userPlaybook.isEmpty());
            out.put("guidance_memory_used", !ctx.taskMemoryHint.isEmpty());
            out.put("memory_fast_path_used", anyMemoryFastPathUsed);
            out.put("memory_fast_path_fallback", anyMemoryFastPathFallback);
            out.put("auto_unlock_before_route", ctx.autoUnlockBeforeRoute);
            out.put("auto_lock_after_task", ctx.autoLockAfterTask);
            out.put("unlocked_by_fsm", ctx.unlockedByFsm);
            if (ctx.error != null && !ctx.error.isEmpty()) {
                out.put("reason", ctx.error);
            }
            if (!ctx.output.isEmpty()) {
                out.put("output", new LinkedHashMap<>(ctx.output));
            }
            return out;
        } finally {
            if (enablePush) {
                trace.clearPushTarget(ctx.taskId);
            }
        }
    }

    private State runInitState(Context ctx) {
        Map<String, Object> enterEv = new LinkedHashMap<>();
        enterEv.put("task_id", ctx.taskId);
        enterEv.put("state", State.INIT.name());
        enterEv.put("user_task", ctx.userTask);
        trace.event("fsm_state_enter", enterEv);

        // 1) Screen size (width/height/density), via PerceptionEngine.GET_SCREEN_SIZE
        int width = 0, height = 0, density = 0;
        try {
            byte[] resp = perception != null ? perception.handleGetScreenSize() : null;
            if (resp != null && resp.length >= 7) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                if (status != 0) {
                    width = buf.getShort() & 0xFFFF;
                    height = buf.getShort() & 0xFFFF;
                    density = buf.getShort() & 0xFFFF;
                }
            }
        } catch (Exception ignored) {
        }
        ctx.deviceInfo.put("width", width);
        ctx.deviceInfo.put("height", height);
        ctx.deviceInfo.put("density", density);

        // 2) Current activity, via PerceptionEngine.GET_ACTIVITY
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            boolean ok = false;
            String pkg = "";
            String act = "";
            if (resp != null && resp.length >= 5) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                ok = status != 0;
                if (resp.length >= 5) {
                    int pkgLen = buf.getShort() & 0xFFFF;
                    if (pkgLen >= 0 && buf.remaining() >= pkgLen + 2) {
                        byte[] pkgBytes = new byte[pkgLen];
                        buf.get(pkgBytes);
                        pkg = new String(pkgBytes, StandardCharsets.UTF_8);
                        int actLen = buf.getShort() & 0xFFFF;
                        if (actLen >= 0 && buf.remaining() >= actLen) {
                            byte[] actBytes = new byte[actLen];
                            buf.get(actBytes);
                            act = new String(actBytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", ok);
            ctx.currentActivity.put("package", pkg != null ? pkg : "");
            ctx.currentActivity.put("activity", act != null ? act : "");
        } catch (Exception e) {
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", false);
            ctx.currentActivity.put("package", "");
            ctx.currentActivity.put("activity", "");
        }

        // 3) App list (user apps) as candidates, via ExecutionEngine.LIST_APPS(filter=1)
        if (ctx.appCandidates.isEmpty()) {
            try {
                byte[] payload = new byte[]{0x01}; // filter=1 (user apps)
                byte[] resp = execution != null ? execution.handleListApps(payload) : null;
                if (resp != null && resp.length >= 3) {
                    ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                    byte status = buf.get();
                    int jsonLen = buf.getShort() & 0xFFFF;
                    if (status != 0 && jsonLen > 0 && buf.remaining() >= jsonLen) {
                        byte[] jsonBytes = new byte[jsonLen];
                        buf.get(jsonBytes);
                        String json = new String(jsonBytes, StandardCharsets.UTF_8);
                        Object parsed = Json.parse(json);
                        List<Map<String, Object>> candidates = normalizeAppCandidates(parsed);
                        int limit = Math.min(200, candidates.size());
                        ctx.appCandidates.addAll(candidates.subList(0, limit));
                    }
                }
            } catch (Exception ignored) {
                // Keep appCandidates empty on error.
            }
        }

        // 4) Coordinate probe: send a synthetic calibration image to the VLM-style endpoint
        //    and let it report corner coordinates, mirroring Python _probe_coordinate_space.
        Map<String, Object> probe = runCoordProbe(ctx);
        if (!probe.isEmpty()) {
            ctx.coordProbe.clear();
            ctx.coordProbe.putAll(probe);
            ctx.output.put("coord_probe", new LinkedHashMap<>(probe));
        }

        Map<String, Object> readyEv = new LinkedHashMap<>();
        readyEv.put("task_id", ctx.taskId);
        readyEv.put("device_info", new LinkedHashMap<>(ctx.deviceInfo));
        readyEv.put("current_activity", new LinkedHashMap<>(ctx.currentActivity));
        readyEv.put("app_candidates", ctx.appCandidates.size());
        readyEv.put("page_candidates", ctx.pageCandidates.size());
        readyEv.put("coord_probe", ctx.coordProbe.isEmpty() ? null : new LinkedHashMap<>(ctx.coordProbe));
        trace.event("fsm_init_ready", readyEv);

        // Next: task decomposition (v2). Even if decomposition fails, the FSM will
        // fall back to the old single-task pipeline.
        return State.TASK_DECOMPOSE;
    }

    /**
     * Decide whether memory fast path can be used for current sub_task.
     * Rules:
     * - Requires non-empty taskMemoryHint.
     * - Uses selected package if already decided.
     * - If selected package is empty, falls back to memory package_name.
     * - If both exist but conflict, disable fast path for safety.
     */
    private String resolveMemoryFastPathPackage(Context ctx) {
        if (ctx.taskMemoryHint.isEmpty()) {
            return "";
        }
        String selected = ctx.selectedPackage != null ? ctx.selectedPackage.trim() : "";
        String memoryPkg = stringOrEmpty(ctx.taskMemoryHint.get("package_name"));
        if (!selected.isEmpty()) {
            if (!memoryPkg.isEmpty() && !selected.equals(memoryPkg)) {
                return "";
            }
            return selected;
        }
        return memoryPkg;
    }

    /**
     * Coordinate-space probe, Java port of Python _probe_coordinate_space.
     *
     * Normalized-coordinate mode:
     * - force a stable logical space: top-left (0,0), bottom-right (1000,1000)
     * - avoid extra LLM variability during INIT.
     */
    private Map<String, Object> runCoordProbe(Context ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max_x", 1000.0d);
        result.put("max_y", 1000.0d);
        result.put("x_min", 0.0d);
        result.put("x_max", 1000.0d);
        result.put("y_min", 0.0d);
        result.put("y_max", 1000.0d);
        result.put("span_x", 1000.0d);
        result.put("span_y", 1000.0d);
        Map<String, Object> points = new LinkedHashMap<>();
        points.put("tl", Arrays.asList(0.0d, 0.0d));
        points.put("tr", Arrays.asList(1000.0d, 0.0d));
        points.put("bl", Arrays.asList(0.0d, 1000.0d));
        points.put("br", Arrays.asList(1000.0d, 1000.0d));
        result.put("points", points);
        Map<String, Object> probeSize = new LinkedHashMap<>();
        probeSize.put("width", 1000);
        probeSize.put("height", 1000);
        result.put("probe_size", probeSize);

        Map<String, Object> ev = new LinkedHashMap<>(result);
        ev.put("task_id", ctx.taskId);
        ev.put("mode", "fixed_normalized_1000");
        trace.event("coord_probe_done", ev);
        return result;
    }

    private static Number toNumber(Object o) {
        if (o instanceof Number) {
            return (Number) o;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeAppCandidates(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            List<?> arr = (List<?>) raw;
            for (Object item : arr) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String pkg = stringOrEmpty(m.get("package"));
                    if (pkg.isEmpty()) continue;
                    Object rawLabel = m.containsKey("label") ? m.get("label") : m.get("name");
                    String label = stringOrEmpty(rawLabel);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("package", pkg);
                    // Keep null/empty labels as-is so APP_RESOLVE prompt can explicitly
                    // expose unknown labels and let the model infer from package id.
                    row.put("label", label.isEmpty() ? null : label);
                    // Keep "name" for backward compatibility with old callers/logics.
                    row.put("name", label.isEmpty() ? null : label);
                    out.add(row);
                    continue;
                }
                String pkg = stringOrEmpty(item);
                if (pkg.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("label", null);
                row.put("name", null);
                out.add(row);
            }
        } else if (raw instanceof Map) {
            // Fallback: a single map, try to interpret as one app entry.
            Map<String, Object> m = (Map<String, Object>) raw;
            String pkg = stringOrEmpty(m.get("package"));
            if (!pkg.isEmpty()) {
                Object rawLabel = m.containsKey("label") ? m.get("label") : m.get("name");
                String label = stringOrEmpty(rawLabel);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("label", label.isEmpty() ? null : label);
                row.put("name", label.isEmpty() ? null : label);
                out.add(row);
            }
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean toBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String normalizeCarryContext(String s) {
        if (s == null) {
            return "";
        }
        String out = s.trim().replaceAll("[\\t\\r\\n]+", " ").replaceAll(" +", " ");
        if (out.length() > 1200) {
            out = out.substring(0, 1200).trim();
        }
        return out;
    }

    private static String normalizeMemoryWrite(String s) {
        if (s == null) {
            return "";
        }
        String out = s.trim().replaceAll("[\\t\\r\\n]+", " ").replaceAll(" +", " ");
        if (out.isEmpty()) {
            return "";
        }
        String lower = out.toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || "n/a".equals(lower) || "na".equals(lower) || "null".equals(lower)) {
            return "";
        }
        if (out.length() > 300) {
            out = out.substring(0, 300).trim();
        }
        return out;
    }

    private static void appendWorkingMemory(Context ctx, String memoryWrite) {
        String s = normalizeMemoryWrite(memoryWrite);
        if (s.isEmpty()) {
            return;
        }
        if (ctx.workingMemory.contains(s)) {
            return;
        }
        ctx.workingMemory.add(s);
        while (ctx.workingMemory.size() > 20) {
            ctx.workingMemory.remove(0);
        }
    }

    @SuppressWarnings("unchecked")
    private void storeSubTaskSummary(Context ctx, String summary) {
        String s = summary != null ? summary.trim() : "";
        if (s.isEmpty()) {
            return;
        }
        String key = "sub_task_" + ctx.currentSubTaskIndex;
        if (ctx.currentSubTask != null && ctx.currentSubTask.id != null && !ctx.currentSubTask.id.trim().isEmpty()) {
            key = ctx.currentSubTask.id.trim();
        }

        ctx.blackboard.put(key, s);

        Object obj = ctx.output.get("sub_task_summaries");
        Map<String, Object> summaries;
        if (obj instanceof Map) {
            summaries = (Map<String, Object>) obj;
        } else {
            summaries = new LinkedHashMap<>();
            ctx.output.put("sub_task_summaries", summaries);
        }
        summaries.put(key, s);

        // Wire current sub_task outputs to blackboard so later sub_tasks can
        // request them explicitly via inputs.
        List<String> outputKeys = new ArrayList<>();
        if (ctx.currentSubTask != null && ctx.currentSubTask.outputs != null) {
            for (String raw : ctx.currentSubTask.outputs) {
                String outKey = raw != null ? raw.trim() : "";
                if (outKey.isEmpty()) {
                    continue;
                }
                ctx.blackboard.put(outKey, s);
                outputKeys.add(outKey);
            }
        }
        if (!outputKeys.isEmpty()) {
            Object outObj = ctx.output.get("sub_task_outputs");
            Map<String, Object> outputMap;
            if (outObj instanceof Map) {
                outputMap = (Map<String, Object>) outObj;
            } else {
                outputMap = new LinkedHashMap<>();
                ctx.output.put("sub_task_outputs", outputMap);
            }
            for (String outKey : outputKeys) {
                outputMap.put(outKey, s);
            }
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("sub_task_key", key);
        ev.put("summary", s);
        if (!outputKeys.isEmpty()) {
            ev.put("output_keys", new ArrayList<>(outputKeys));
        }
        trace.event("fsm_sub_task_summary", ev);
    }

    private Map<String, String> collectPassedContextForCurrentSubTask(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ctx == null || ctx.currentSubTask == null || ctx.currentSubTask.inputs == null) {
            return out;
        }
        if (ctx.currentSubTask.inputs.isEmpty() || ctx.blackboard.isEmpty()) {
            return out;
        }
        for (String raw : ctx.currentSubTask.inputs) {
            String key = raw != null ? raw.trim() : "";
            if (key.isEmpty()) {
                continue;
            }
            String val = stringOrEmpty(ctx.blackboard.get(key));
            if (val.isEmpty()) {
                for (Map.Entry<String, Object> e : ctx.blackboard.entrySet()) {
                    if (key.equalsIgnoreCase(stringOrEmpty(e.getKey()))) {
                        val = stringOrEmpty(e.getValue());
                        break;
                    }
                }
            }
            if (!val.isEmpty()) {
                out.put(key, val);
            }
        }
        return out;
    }

    /**
     * Best-effort reset between sub_tasks: go HOME and stop the current app.
     * This avoids repeated LAUNCH_APP calls on an already-running task stack,
     * which can cause rendering or state issues in some apps.
     */
    private void safeResetToHomeAndStopApp(Context ctx) {
        // 1) Go HOME via KEY_EVENT (keycode=3, CLICK).
        try {
            ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) 3);   // KEYCODE_HOME
            buf.put((byte) 2);   // CLICK
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            trace.event("fsm_sub_task_go_home", ev);
            execution.handleKeyEvent(buf.array());
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_sub_task_go_home_error", ev);
        }

        // 2) Stop the current app process if we know its package.
        String pkg = ctx.selectedPackage != null ? ctx.selectedPackage.trim() : "";
        if (pkg.isEmpty()) {
            return;
        }
        try {
            byte[] pkgBytes = pkg.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("package", pkg);
            trace.event("fsm_sub_task_stop_app", ev);
            execution.handleStopApp(buf.array());
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("package", pkg);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_sub_task_stop_app_error", ev);
        }
    }

    /**
     * TASK_DECOMPOSE (v2): call LLM to decompose the high-level task into sub_tasks.
     * In step 1 we only log and store the result; execution behavior is unchanged.
     */
    private State runTaskDecomposeState(Context ctx) {
        Map<String, Object> enterEv = new LinkedHashMap<>();
        enterEv.put("task_id", ctx.taskId);
        enterEv.put("state", State.TASK_DECOMPOSE.name());
        trace.event("fsm_state_enter", enterEv);

        String prompt = buildTaskDecomposePrompt(ctx);
        Map<String, Object> promptEv = new LinkedHashMap<>();
        promptEv.put("task_id", ctx.taskId);
        promptEv.put("state", State.TASK_DECOMPOSE.name());
        promptEv.put("prompt", prompt);
        trace.event("llm_prompt_task_decompose", promptEv);

        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            String raw = llmClient.chatOnce(cfg, buildTaskDecomposeSystemPrompt(), prompt);

            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("state", State.TASK_DECOMPOSE.name());
            String snippet = raw != null && raw.length() > 800 ? raw.substring(0, 800) + "..." : raw;
            respEv.put("response", snippet != null ? snippet : "");
            trace.event("llm_response_task_decompose", respEv);

            Map<String, Object> obj = extractJsonObjectFromText(raw);
            if (!obj.isEmpty()) {
                List<SubTask> subs = parseSubTasksFromObject(obj);
                if (!subs.isEmpty()) {
                    ctx.subTasks.clear();
                    ctx.subTasks.addAll(subs);
                }
                ctx.taskType = stringOrEmpty(obj.get("task_type"));

                Map<String, Object> doneEv = new LinkedHashMap<>();
                doneEv.put("task_id", ctx.taskId);
                doneEv.put("sub_task_count", ctx.subTasks.size());
                doneEv.put("task_type", ctx.taskType);
                trace.event("fsm_task_decompose_done", doneEv);
            } else {
                Map<String, Object> failEv = new LinkedHashMap<>();
                failEv.put("task_id", ctx.taskId);
                failEv.put("reason", "empty_or_invalid_json");
                trace.event("fsm_task_decompose_parse_failed", failEv);
            }
        } catch (Exception e) {
            Map<String, Object> errEv = new LinkedHashMap<>();
            errEv.put("task_id", ctx.taskId);
            errEv.put("state", State.TASK_DECOMPOSE.name());
            errEv.put("err", String.valueOf(e));
            trace.event("llm_error_task_decompose", errEv);
        }

        // For now, always proceed to APP_RESOLVE. Even if decomposition fails,
        // the legacy single-task pipeline remains functional.
        return State.APP_RESOLVE;
    }

    /**
     * Build prompt for TASK_DECOMPOSE.
     */
    private String buildTaskDecomposePrompt(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            String pkg = stringOrEmpty(c.get("package"));
            String label = stringOrEmpty(c.get("label"));
            if (label.isEmpty()) {
                label = stringOrEmpty(c.get("name"));
            }
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("label", label.isEmpty() ? null : label);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that decomposes a high-level Android user task into a small number of high-level sub_tasks.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"label\"}):\n");
        sb.append("(label may be null; when null, infer app intent from package id)\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"sub_tasks\":[{...}],\"task_type\":\"single|loop|mixed\"}\n");
        sb.append("Definition of sub_task (very important):\n");
        sb.append("- A sub_task is a self-contained sub-goal that the agent can execute as a mini-workflow.\n");
        sb.append("- It is NOT a single UI click or a tiny step inside one screen.\n");
        sb.append("- It usually corresponds to one user-intent like \"view my followers\" or \"send a message with a link\".\n");
        sb.append("- All low-level UI steps (open app, tap tabs, tap buttons) to achieve that intent belong INSIDE one sub_task, not as separate sub_tasks.\n");
        sb.append("\n");
        sb.append("When to create multiple sub_tasks:\n");
        sb.append("- If the user describes multiple distinct goals or phases, even in the same app, use multiple sub_tasks.\n");
        sb.append("  Example: \"open Xiaohongshu, check my followers, then check my following\" ->\n");
        sb.append("    sub_task_1: open Xiaohongshu and view my followers.\n");
        sb.append("    sub_task_2: open Xiaohongshu and view my following.\n");
        sb.append("- If the user task involves multiple apps (e.g., copy link in app A, send link in app B), use multiple sub_tasks (one per high-level goal).\n");
        sb.append("- If the task is a single simple goal in one app, use exactly one sub_task.\n");
        sb.append("\n");
        sb.append("What NOT to do (wrong decomposition):\n");
        sb.append("- Do NOT break a single high-level goal into micro-steps per click.\n");
        sb.append("  Wrong: \"open app\", \"tap Me\", \"tap Followers\" as three sub_tasks.\n");
        sb.append("  Correct: one sub_task \"open the app and view my followers\".\n");
        sb.append("- Do NOT create one sub_task per UI element on a single page.\n");
        sb.append("\n");
        sb.append("Meaning of modes:\n");
        sb.append("- mode=\"single\": the sub_task is executed once and has a single completion condition.\n");
        sb.append("  Examples: post one dynamic, send one message, view my followers once.\n");
        sb.append("- mode=\"loop\": the sub_task needs to repeat an operation over a set of items whose size is not known from the text.\n");
        sb.append("  Examples: sign in to all forums, like all unread posts, clear all unread notifications.\n");
        sb.append("- For loop sub_tasks, do NOT create one sub_task per item; instead create a single loop sub_task that covers all items.\n");
        sb.append("\n");
        sb.append("Each sub_task MUST have fields: id, description, mode, inputs, outputs, success_criteria.\n");
        sb.append("Optional fields are allowed (for example app_hint), but they are not required.\n");
        sb.append("Additional rules:\n");
        sb.append("1) mode is either \"single\" or \"loop\".\n");
        sb.append("2) For loop sub_tasks, add loop_metadata with loop_unit, loop_target_condition, loop_termination_criteria, max_iterations.\n");
        sb.append("3) If the task is simple and fits in one app, return a single sub_task.\n");
        sb.append("4) For multi-app tasks, break into multiple sub_tasks and wire outputs/inputs.\n");
        sb.append("5) sub_tasks count should usually be between 1 and 5, never dozens.\n");
        sb.append("6) Do NOT output markdown, code fences, or comments.\n");
        return sb.toString();
    }

    /**
     * System prompt for TASK_DECOMPOSE to enforce JSON-only output.
     */
    private String buildTaskDecomposeSystemPrompt() {
        return "You are an assistant that decomposes a high-level Android user task into a small number of high-level sub_tasks for an automation agent.\n"
                + "sub_tasks are independent sub-goals (like \"view my followers\" or \"send a link\"), NOT individual button clicks.\n"
                + "You MUST output strict JSON with fields: sub_tasks (array) and task_type.\n"
                + "Do not output markdown, code fences, or any commentary outside the JSON.";
    }

    @SuppressWarnings("unchecked")
    private List<SubTask> parseSubTasksFromObject(Map<String, Object> obj) {
        List<SubTask> subs = new ArrayList<>();
        Object subTasksObj = obj.get("sub_tasks");
        if (!(subTasksObj instanceof List)) {
            return subs;
        }
        List<?> arr = (List<?>) subTasksObj;
        for (Object item : arr) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            SubTask st = new SubTask();
            st.id = stringOrEmpty(m.get("id"));
            st.description = stringOrEmpty(m.get("description"));
            st.mode = stringOrEmpty(m.get("mode")).toLowerCase(Locale.ROOT);
            st.appHint = stringOrEmpty(m.get("app_hint"));
            st.successCriteria = stringOrEmpty(m.get("success_criteria"));

            Object inputsObj = m.get("inputs");
            if (inputsObj instanceof List) {
                for (Object v : (List<?>) inputsObj) {
                    String s = stringOrEmpty(v);
                    if (!s.isEmpty()) {
                        st.inputs.add(s);
                    }
                }
            }
            Object outputsObj = m.get("outputs");
            if (outputsObj instanceof List) {
                for (Object v : (List<?>) outputsObj) {
                    String s = stringOrEmpty(v);
                    if (!s.isEmpty()) {
                        st.outputs.add(s);
                    }
                }
            }

            Object loopObj = m.get("loop_metadata");
            if (loopObj instanceof Map) {
                Map<String, Object> lm = (Map<String, Object>) loopObj;
                LoopMetadata md = new LoopMetadata();
                md.loopUnit = stringOrEmpty(lm.get("loop_unit"));
                md.loopTargetCondition = stringOrEmpty(lm.get("loop_target_condition"));
                md.loopTerminationCriteria = stringOrEmpty(lm.get("loop_termination_criteria"));
                String maxItStr = stringOrEmpty(lm.get("max_iterations"));
                try {
                    if (!maxItStr.isEmpty()) {
                        md.maxIterations = Integer.parseInt(maxItStr);
                    }
                } catch (NumberFormatException ignored) {
                    md.maxIterations = 0;
                }
                st.loopMetadata = md;
            }

            subs.add(st);
        }
        return subs;
    }

    /**
     * Build LLM prompt for APP_RESOLVE, similar in spirit to Python PromptBuilder(APP_RESOLVE).
     */
    private String buildAppResolvePrompt(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            String pkg = stringOrEmpty(c.get("package"));
            String label = stringOrEmpty(c.get("label"));
            if (label.isEmpty()) {
                label = stringOrEmpty(c.get("name"));
            }
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("label", label.isEmpty() ? null : label);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that selects the best Android app to handle a task.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"label\"}):\n");
        sb.append("(label may be null; when null, infer app intent from package id)\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"package_name\":\"one_package_from_apps\"}\n");
        sb.append("Rules:\n");
        sb.append("1) package_name MUST be exactly one of the \"package\" values above.\n");
        sb.append("2) package_name MUST be a package id string (e.g., com.tencent.mm), NOT app label/name (e.g., 微信).\n");
        sb.append("3) If the task clearly refers to a specific brand (e.g., Bilibili, Taobao), map it to that app.\n");
        sb.append("4) If ambiguous, choose the app that typical users would most likely use.\n");
        sb.append("5) Do NOT explain, do NOT add markdown, do NOT add comments.\n");
        return sb.toString();
    }

    /**
     * System prompt for APP_RESOLVE to mirror Python-side LLM usage:
     * - clearly separates system role from user prompt
     * - enforces JSON-only output with package_name field.
     */
    private String buildAppResolveSystemPrompt() {
        return "You are an assistant that selects the best Android app to handle a task.\n"
                + "You MUST output strict JSON only with a single field: package_name.\n"
                + "package_name must be an installed package id (contains dots), never a human-readable app label.\n"
                + "Do not output markdown or any extra commentary.";
    }

    /**
     * Extract package_name from LLM JSON response.
     */
    @SuppressWarnings("unchecked")
    private String extractPackageFromResponse(String raw) {
        Map<String, Object> obj = extractJsonObjectFromText(raw);
        if (obj.isEmpty()) {
            return "";
        }
        String pkg = stringOrEmpty(obj.get("package_name"));
        if (pkg.isEmpty()) {
            pkg = stringOrEmpty(obj.get("package"));
        }
        return pkg;
    }

    /**
     * Best-effort extraction of a JSON object from arbitrary text.
     *
     * Mirrors the Python-side _extract_json_object helper and the Java MapPromptPlanner
     * implementation so that we can recover even when the model wraps JSON in prose
     * or markdown/code fences.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJsonObjectFromText(String text) {
        String s = text != null ? text.trim() : "";
        if (s.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        // 1) Direct parse as an object.
        try {
            Map<String, Object> obj = Json.parseObject(s);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }

        // 2) Fallback: slice between first '{' and last '}' and parse that.
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            return new LinkedHashMap<String, Object>();
        }
        String slice = s.substring(start, end + 1);
        try {
            Map<String, Object> obj = Json.parseObject(slice);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    // ===== Instruction parsing (Java port of fsm_instruction.py) =====
    private static class Instruction {
        final String op;
        final List<String> args;
        final String raw;

        Instruction(String op, List<String> args, String raw) {
            this.op = op;
            this.args = args;
            this.raw = raw;
        }
    }

    private static class InstructionError extends Exception {
        InstructionError(String msg) {
            super(msg);
        }
    }

    private static final Map<String, int[]> INSTRUCTION_ARITY = new LinkedHashMap<>();

    static {
        INSTRUCTION_ARITY.put("SET_APP", new int[]{1, 1});
        INSTRUCTION_ARITY.put("ROUTE", new int[]{2, 2});
        INSTRUCTION_ARITY.put("TAP", new int[]{2, 2});
        INSTRUCTION_ARITY.put("SWIPE", new int[]{5, 5});
        INSTRUCTION_ARITY.put("INPUT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("WAIT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("BACK", new int[]{0, 0});
        // DONE can optionally carry a natural-language summary tail.
        INSTRUCTION_ARITY.put("DONE", new int[]{0, 9999});
        INSTRUCTION_ARITY.put("FAIL", new int[]{1, 9999});
    }

    private static List<Instruction> parseInstructions(String text, int maxCommands) throws InstructionError {
        String[] lines = (text != null ? text : "").split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        if (nonEmpty.isEmpty()) {
            throw new InstructionError("empty instruction output");
        }
        if (nonEmpty.size() > maxCommands) {
            throw new InstructionError("too many instructions: " + nonEmpty.size() + " > " + maxCommands);
        }

        List<Instruction> out = new ArrayList<>();
        for (String line : nonEmpty) {
            List<String> parts = shellSplit(line);
            if (parts.isEmpty()) continue;
            String op = parts.get(0).trim().toUpperCase();
            List<String> args = parts.subList(1, parts.size());
            validateArity(op, args);
            out.add(new Instruction(op, new ArrayList<>(args), line));
        }
        if (out.isEmpty()) {
            throw new InstructionError("no valid instructions parsed");
        }
        return out;
    }

    private static void validateAllowed(List<Instruction> instructions, java.util.Set<String> allowedOps) throws InstructionError {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (String op : allowedOps) {
            allowed.add(op.toUpperCase());
        }
        for (Instruction ins : instructions) {
            if (!allowed.contains(ins.op)) {
                throw new InstructionError("op not allowed in this state: " + ins.op);
            }
        }
    }

    private static void validateArity(String op, List<String> args) throws InstructionError {
        int[] range = INSTRUCTION_ARITY.get(op);
        if (range == null) {
            throw new InstructionError("unknown instruction op: " + op);
        }
        int n = args.size();
        int min = range[0];
        int max = range[1];
        if (n < min || n > max) {
            String expected = (min == max) ? String.valueOf(min) : (min + ".." + max);
            throw new InstructionError(op + " expects " + expected + " args, got " + n);
        }
    }

    // Very small shell-like splitter that understands quotes for INPUT "text".
    private static List<String> shellSplit(String line) throws InstructionError {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'' || c == '\"') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuotes) {
            throw new InstructionError("invalid instruction quoting: " + line);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Extract content between XML-like tags, Java port of _extract_tag_text.
     */
    private static String extractTagText(String text, String tag) {
        if (text == null || text.isEmpty() || tag == null || tag.isEmpty()) {
            return "";
        }
        String safeTag = Pattern.quote(tag);
        // Preferred: strict paired tags.
        String strict = "(?is)<\\s*" + safeTag + "\\s*>\\s*([\\s\\S]*?)\\s*</\\s*" + safeTag + "\\s*>";
        Matcher m = Pattern.compile(strict, Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Fallback: opening tag exists but closing tag is missing.
        // Capture until next sibling opening tag or end of text.
        String openOnly = "(?is)<\\s*" + safeTag + "\\s*>\\s*([\\s\\S]*?)(?=<\\s*[A-Za-z_][A-Za-z0-9_]*\\s*>|$)";
        Matcher m2 = Pattern.compile(openOnly, Pattern.CASE_INSENSITIVE).matcher(text);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return "";
    }

    /**
     * Normalize model output to DSL commands, Java port of Python _normalize_model_output.
     */
    private String normalizeModelOutput(String raw, State state, Context ctx) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty() || !text.startsWith("{")) {
            return text;
        }
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(text);
        } catch (Exception e) {
            return text;
        }
        if (state == State.APP_RESOLVE) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = stringOrEmpty(obj.get("package"));
            }
            if (!pkg.isEmpty()) {
                return "SET_APP " + pkg;
            }
        }
        if (state == State.ROUTE_PLAN) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = ctx.selectedPackage != null ? ctx.selectedPackage : "";
            }
            String target = stringOrEmpty(obj.get("target_page"));
            if (!pkg.isEmpty() && !target.isEmpty()) {
                return "ROUTE " + pkg + " " + target;
            }
        }
        return text;
    }

    /**
     * Fallback when LLM is unavailable or returns invalid package.
     * Simple heuristic: match task text against app names, else pick first candidate.
     */
    private String heuristicPickPackage(Context ctx) {
        String task = ctx.userTask != null ? ctx.userTask.toLowerCase() : "";
        if (ctx.appCandidates.isEmpty()) return "";

        // Prefer name/label substring matches.
        for (Map<String, Object> c : ctx.appCandidates) {
            String pkg = stringOrEmpty(c.get("package"));
            String label = stringOrEmpty(c.get("label"));
            if (label.isEmpty()) {
                label = stringOrEmpty(c.get("name"));
            }
            String lower = label.toLowerCase();
            if (!task.isEmpty() && !lower.isEmpty() && task.contains(lower)) {
                return pkg;
            }
        }
        // Fallback: first candidate.
        Map<String, Object> first = ctx.appCandidates.get(0);
        return stringOrEmpty(first.get("package"));
    }

    private boolean isKnownCandidatePackage(Context ctx, String pkg) {
        String p = pkg != null ? pkg.trim() : "";
        if (p.isEmpty()) {
            return false;
        }
        for (Map<String, Object> c : ctx.appCandidates) {
            if (p.equals(stringOrEmpty(c.get("package")))) {
                return true;
            }
        }
        return false;
    }

    private String resolvePackageLabel(Context ctx, String pkg) {
        String p = pkg != null ? pkg.trim() : "";
        if (p.isEmpty()) {
            return "";
        }
        for (Map<String, Object> c : ctx.appCandidates) {
            if (p.equals(stringOrEmpty(c.get("package")))) {
                String label = stringOrEmpty(c.get("label"));
                if (label.isEmpty()) {
                    label = stringOrEmpty(c.get("name"));
                }
                if (!label.isEmpty()) {
                    return label;
                }
                break;
            }
        }
        return "";
    }

    private State runAppResolveState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.APP_RESOLVE.name());
        ev.put("selected_package", ctx.selectedPackage);
        trace.event("fsm_state_enter", ev);
        // 1) Caller-specified package: accept directly and skip LLM.
        if (ctx.selectedPackage != null && !ctx.selectedPackage.trim().isEmpty()) {
            ctx.selectedPackage = ctx.selectedPackage.trim();
            ctx.selectedPackageLabel = resolvePackageLabel(ctx, ctx.selectedPackage);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("app_name", ctx.selectedPackageLabel);
            done.put("source", "caller");
            trace.event("fsm_app_resolve_fixed_package", done);
            return State.ROUTE_PLAN;
        }

        // 2) Need app candidates collected in INIT.
        if (ctx.appCandidates.isEmpty()) {
            ctx.error = "app_resolve_no_candidates";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_no_candidates", fail);
            return State.FAIL;
        }

        // 3) Build prompt and call end-side LLM to choose package.
        String prompt = buildAppResolvePrompt(ctx);
        Map<String, Object> promptEv = new LinkedHashMap<>();
        promptEv.put("task_id", ctx.taskId);
        promptEv.put("state", State.APP_RESOLVE.name());
        promptEv.put("prompt", prompt);
        trace.event("llm_prompt_app_resolve", promptEv);

        String raw = null;
        String chosenPackage = "";
        boolean usedFallback = false;

        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            raw = llmClient.chatOnce(cfg, buildAppResolveSystemPrompt(), prompt);

            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("state", State.APP_RESOLVE.name());
            String snippet = raw != null && raw.length() > 800 ? raw.substring(0, 800) + "..." : raw;
            respEv.put("response", snippet != null ? snippet : "");
            trace.event("llm_response_app_resolve", respEv);

            chosenPackage = extractPackageFromResponse(raw);
            if (!chosenPackage.isEmpty() && !isKnownCandidatePackage(ctx, chosenPackage)) {
                usedFallback = true;
                Map<String, Object> invalidEv = new LinkedHashMap<>();
                invalidEv.put("task_id", ctx.taskId);
                invalidEv.put("state", State.APP_RESOLVE.name());
                invalidEv.put("llm_package", chosenPackage);
                invalidEv.put("reason", "not_in_candidates_or_label");
                trace.event("llm_response_app_resolve_invalid_package", invalidEv);
                chosenPackage = "";
            }
        } catch (Exception e) {
            usedFallback = true;
            Map<String, Object> errEv = new LinkedHashMap<>();
            errEv.put("task_id", ctx.taskId);
            errEv.put("state", State.APP_RESOLVE.name());
            errEv.put("err", String.valueOf(e));
            trace.event("llm_error_app_resolve", errEv);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            usedFallback = true;
            chosenPackage = heuristicPickPackage(ctx);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            ctx.error = "app_resolve_failed:no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_failed", fail);
            return State.FAIL;
        }

        ctx.selectedPackage = chosenPackage.trim();
        ctx.selectedPackageLabel = resolvePackageLabel(ctx, ctx.selectedPackage);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", ctx.selectedPackage);
        done.put("app_name", ctx.selectedPackageLabel);
        done.put("source", usedFallback ? "fallback" : "llm");
        trace.event("fsm_app_resolve_done", done);

        return State.ROUTE_PLAN;
    }

    private State runRoutePlanState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTE_PLAN.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        // 1) Require selected package from APP_RESOLVE.
        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "route_plan_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        File mapFile = mapManager.getCurrentMapFile(pkg);

        // 2) No map for this package: keep pipeline, but mark as no-map mode.
        if (!mapFile.exists() || !mapFile.isFile() || mapFile.length() == 0) {
            ctx.mapPath = null;
            Map<String, Object> noMapEv = new LinkedHashMap<>();
            noMapEv.put("task_id", ctx.taskId);
            noMapEv.put("package", pkg);
            noMapEv.put("map_path", mapFile.getAbsolutePath());
            trace.event("fsm_route_plan_no_map", noMapEv);
            // No map available: continue to ROUTING, then fall through to VISION_ACT.
            return State.ROUTING;
        }

        // 3) Map exists: load RouteMap and ask LLM to choose target_page.
        RouteMap routeMap = null;
        try {
            routeMap = RouteMap.loadFromFile(mapFile);
        } catch (Exception e) {
            ctx.mapPath = null;
            ctx.error = "route_plan_map_load_failed:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_map_load_failed", fail);
            // Keep pipeline consistent: ROUTING handles no-map mode.
            return State.ROUTING;
        }

        ctx.mapPath = mapFile.getAbsolutePath();

        String targetPage = null;
        boolean usedFallback = false;
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            MapPromptPlanner.PlanResult plan = mapPlanner.plan(cfg, ctx.userTask, routeMap);
            if (plan.packageName != null && !plan.packageName.trim().isEmpty()) {
                ctx.selectedPackage = plan.packageName.trim();
            }
            targetPage = plan.targetPage != null ? plan.targetPage.trim() : "";
            usedFallback = plan.usedFallback;

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("target_page", targetPage);
            done.put("used_fallback", usedFallback);
            trace.event("fsm_route_plan_done", done);
        } catch (Exception e) {
            ctx.error = "route_plan_llm_error:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_llm_error", fail);
            return State.FAIL;
        }

        if (targetPage == null || targetPage.isEmpty()) {
            ctx.error = "route_plan_failed:no_target_page";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", ctx.selectedPackage);
            fail.put("map_path", ctx.mapPath);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        ctx.targetPage = targetPage;
        return State.ROUTING;
    }

    private State runRoutingState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTING.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("target_page", ctx.targetPage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "routing_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_routing_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        boolean hasMap = ctx.mapPath != null && !ctx.mapPath.trim().isEmpty();
        RouteMap routeMap = null;
        List<RouteMap.Transition> path = null;
        String fromPage = "";
        String toPage = ctx.targetPage != null ? ctx.targetPage.trim() : "";

        // 1) If mapPath is available, try to load RouteMap and find BFS path.
        if (hasMap) {
            File mapFile = new File(ctx.mapPath);
            try {
                routeMap = RouteMap.loadFromFile(mapFile);
            } catch (Exception e) {
                hasMap = false;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("task_id", ctx.taskId);
                m.put("package", pkg);
                m.put("map_path", mapFile.getAbsolutePath());
                m.put("reason", "routing_map_load_failed:" + e.getMessage());
                trace.event("fsm_routing_map_load_failed", m);
            }
        }

        if (hasMap && routeMap != null && toPage != null && !toPage.isEmpty()) {
            int maxSteps = 64;

            if (ctx.startPage != null && !ctx.startPage.trim().isEmpty()) {
                fromPage = ctx.startPage.trim();
                path = routeMap.findPath(fromPage, toPage, maxSteps);
            } else {
                fromPage = routeMap.inferHomePage();
                if (fromPage == null || fromPage.isEmpty()) {
                    ctx.error = "routing_no_home_page";
                    Map<String, Object> fail = new LinkedHashMap<>();
                    fail.put("task_id", ctx.taskId);
                    fail.put("package", pkg);
                    fail.put("reason", ctx.error);
                    trace.event("fsm_routing_failed", fail);
                    return State.FAIL;
                }
                path = routeMap.findPathFromHome(toPage, maxSteps);
            }

            if (path == null) {
                ctx.error = "routing_no_path";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("from_page", fromPage);
                fail.put("to_page", toPage);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_no_path", fail);
                return State.FAIL;
            }
        } else {
            hasMap = false;
        }

        Map<String, Object> planEv = new LinkedHashMap<>();
        planEv.put("task_id", ctx.taskId);
        planEv.put("package", pkg);
        planEv.put("has_map", hasMap);
        planEv.put("map_path", ctx.mapPath != null ? ctx.mapPath : "");
        planEv.put("from_page", fromPage);
        planEv.put("to_page", toPage);
        planEv.put("path_steps", path != null ? path.size() : 0);
        planEv.put("start_page_override", ctx.startPage != null && !ctx.startPage.trim().isEmpty());
        trace.event("fsm_routing_plan", planEv);

        // 2) Launch app once for both map and no-map modes.
        boolean launchOk = launchAppForRouting(ctx, pkg);
        Map<String, Object> launchEv = new LinkedHashMap<>();
        launchEv.put("task_id", ctx.taskId);
        launchEv.put("package", pkg);
        launchEv.put("clear_task", true);
        launchEv.put("result", launchOk ? "ok" : "fail");
        trace.event("fsm_routing_launch_app", launchEv);
        if (!launchOk) {
            ctx.error = "routing_launch_failed";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("reason", ctx.error);
            trace.event("fsm_routing_failed", fail);
            return State.FAIL;
        }

        Map<String, Object> uiEv = new LinkedHashMap<>();
        uiEv.put("task_id", ctx.taskId);
        uiEv.put("package", pkg);
        uiEv.put("mode", hasMap ? "map" : "no_map");
        uiEv.put("ui", captureRoutingUiFingerprint());
        trace.event("fsm_routing_post_launch_ui", uiEv);

        // No-map mode: nothing to tap, route_result just records launch.
        if (!hasMap || path == null || path.isEmpty()) {
            ctx.routeTrace.clear();
            ctx.routeResult.clear();
            ctx.routeResult.put("ok", launchOk);
            ctx.routeResult.put("mode", "no_map");
            ctx.routeResult.put("package", pkg);
            ctx.routeResult.put("steps", new ArrayList<Map<String, Object>>());
            trace.event("fsm_routing_done", new LinkedHashMap<String, Object>() {{
                put("task_id", ctx.taskId);
                put("package", pkg);
                put("mode", "no_map");
                put("steps", 0);
            }});
            return State.VISION_ACT;
        }

        // Small settle delay before first tap.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        // 3) Execute route steps with locator resolution and tap.
        LocatorResolver resolver = new LocatorResolver(perception, trace);
        List<Map<String, Object>> stepSummaries = new ArrayList<>();
        boolean allOk = true;
        boolean degradedToVision = false;
        int index = 0;
        int recoveryAttempts = 0;

        while (index < path.size()) {
            RouteMap.Transition t = path.get(index);
            RouteStepExec stepExec = (index == 0)
                    ? executeFirstRoutingStepWithWindow(ctx, pkg, t, resolver, index)
                    : executeRegularRoutingStep(ctx, pkg, t, resolver, index);
            stepSummaries.add(stepExec.step);

            if (stepExec.ok) {
                index += 1;
                sleepQuiet(400);
                continue;
            }

            if (recoveryAttempts < ROUTING_RECOVERY_MAX) {
                String failReason = stringOrEmpty(stepExec.step.get("reason"));
                boolean recovered = tryRoutePopupRecoveryByVision(ctx, pkg, index, t, failReason);
                Map<String, Object> recEv = new LinkedHashMap<>();
                recEv.put("task_id", ctx.taskId);
                recEv.put("package", pkg);
                recEv.put("index", index);
                recEv.put("attempt", recoveryAttempts + 1);
                recEv.put("max_attempts", ROUTING_RECOVERY_MAX);
                recEv.put("recovered", recovered);
                recEv.put("reason", failReason);
                trace.event("fsm_routing_recovery_result", recEv);
                if (recovered) {
                    recoveryAttempts += 1;
                    sleepQuiet(ROUTING_RECOVERY_POST_ACTION_SLEEP_MS);
                    continue;
                }
            }

            allOk = false;
            degradedToVision = true;
            break;
        }

        // 4) Populate context route_result and route_trace.
        ctx.routeTrace.clear();
        if (fromPage != null && !fromPage.isEmpty()) {
            ctx.routeTrace.add(fromPage);
        }
        for (RouteMap.Transition t : path) {
            if (t.toPage != null && !t.toPage.isEmpty()) {
                ctx.routeTrace.add(t.toPage);
            }
        }

        ctx.routeResult.clear();
        ctx.routeResult.put("ok", allOk);
        ctx.routeResult.put("package", pkg);
        ctx.routeResult.put("from_page", fromPage);
        ctx.routeResult.put("to_page", toPage);
        ctx.routeResult.put("steps", stepSummaries);
        if (!allOk) {
            ctx.routeResult.put("reason", degradedToVision ? "step_failed_degraded_to_vision" : "step_failed");
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", pkg);
        done.put("from_page", fromPage);
        done.put("to_page", toPage);
        done.put("ok", allOk);
        done.put("steps", stepSummaries.size());
        done.put("degraded_to_vision", degradedToVision);
        trace.event("fsm_routing_done", done);

        if (!allOk) {
            if (degradedToVision) {
                Map<String, Object> deg = new LinkedHashMap<>();
                deg.put("task_id", ctx.taskId);
                deg.put("package", pkg);
                deg.put("failed_index", index);
                deg.put("reason", "routing_step_failed_degraded_to_vision");
                trace.event("fsm_routing_degraded_to_vision", deg);
                return State.VISION_ACT;
            }
            ctx.error = "routing_step_failed";
            return State.FAIL;
        }
        return State.VISION_ACT;
    }

    /**
     * Launch app for routing, Java port of Python RouteThenActCortex._execute_route launch step.
     * Always uses CLEAR_TASK flag so each route starts from a clean task stack.
     */
    private boolean launchAppForRouting(Context ctx, String packageName) {
        try {
            for (int attempt = 1; attempt <= LAUNCH_RETRY_MAX; attempt++) {
                ActivityInfo pre = getCurrentActivityInfoForRouting();
                Map<String, Object> begin = new LinkedHashMap<>();
                begin.put("task_id", ctx.taskId);
                begin.put("package", packageName);
                begin.put("attempt", attempt);
                begin.put("pre_package", pre.packageName);
                begin.put("pre_activity", pre.activityName);
                begin.put("screen_state", getScreenStateCode());
                begin.put("lock_hint", isLikelyLockscreenShown());
                trace.event("fsm_routing_launch_attempt_begin", begin);

                if (!ensureUnlockedBeforeRoute(ctx, packageName, attempt)) {
                    Map<String, Object> skip = new LinkedHashMap<>();
                    skip.put("task_id", ctx.taskId);
                    skip.put("package", packageName);
                    skip.put("attempt", attempt);
                    skip.put("reason", "unlock_not_ready");
                    trace.event("fsm_routing_launch_attempt_skip", skip);
                    continue;
                }
                boolean launchOk = launchAppClearTaskForRouting(packageName);
                if (launchOk) {
                    Map<String, Object> postWaitEv = new LinkedHashMap<>();
                    postWaitEv.put("task_id", ctx.taskId);
                    postWaitEv.put("package", packageName);
                    postWaitEv.put("attempt", attempt);
                    postWaitEv.put("wait_ms", LAUNCH_POST_START_WAIT_MS);
                    trace.event("fsm_routing_launch_post_wait", postWaitEv);
                    sleepQuiet(LAUNCH_POST_START_WAIT_MS);
                }
                boolean packageReady = launchOk && waitForForegroundPackageForRouting(
                        packageName, LAUNCH_WAIT_TIMEOUT_MS, LAUNCH_WAIT_SAMPLE_MS, LAUNCH_READY_REQUIRED_HITS
                );
                ActivityInfo post = getCurrentActivityInfoForRouting();
                boolean postCheckReady = false;
                if (!packageReady && launchOk && packageName.equals(post.packageName)) {
                    packageReady = true;
                    postCheckReady = true;
                }
                Map<String, Object> uiFingerprint = null;
                boolean launcherLike = false;
                if (launchOk && packageReady) {
                    uiFingerprint = captureRoutingUiFingerprint();
                    launcherLike = isLikelyLauncherUi(uiFingerprint);
                    // Treat launcher-like UI as failure only when foreground package is not target.
                    if (launcherLike && !packageName.equals(post.packageName)) {
                        packageReady = false;
                    }
                }

                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("package", packageName);
                ev.put("attempt", attempt);
                ev.put("stop_before_start_in_launch", true);
                ev.put("launch_ok", launchOk);
                ev.put("package_ready", packageReady);
                ev.put("post_check_ready", postCheckReady);
                ev.put("post_package", post.packageName);
                ev.put("post_activity", post.activityName);
                ev.put("ready_required_hits", LAUNCH_READY_REQUIRED_HITS);
                ev.put("launcher_like", launcherLike);
                if (uiFingerprint != null) {
                    ev.put("ui", uiFingerprint);
                }
                trace.event("fsm_routing_launch_attempt", ev);

                if (launchOk && packageReady) {
                    return true;
                }
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("attempts", LAUNCH_RETRY_MAX);
            ev.put("reason", "package_not_ready");
            trace.event("fsm_routing_launch_failed", ev);
            return false;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_launch_err", ev);
            return false;
        }
    }

    private RouteStepExec executeFirstRoutingStepWithWindow(
            Context ctx,
            String pkg,
            RouteMap.Transition t,
            LocatorResolver resolver,
            int index
    ) {
        Map<String, Object> stepEv = new LinkedHashMap<>();
        stepEv.put("task_id", ctx.taskId);
        stepEv.put("package", pkg);
        stepEv.put("from_page", t.fromPage);
        stepEv.put("to_page", t.toPage);
        stepEv.put("index", index);
        stepEv.put("description", t.description);
        stepEv.put("window_ms", ROUTING_FIRST_STEP_WINDOW_MS);
        trace.event("fsm_routing_step_start", stepEv);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", index);
        step.put("from", t.fromPage);
        step.put("to", t.toPage);
        step.put("description", t.description);
        step.put("first_step_window_ms", ROUTING_FIRST_STEP_WINDOW_MS);

        Locator locator = t.action != null ? t.action.locator : null;
        if (locator == null) {
            step.put("result", "resolve_fail");
            step.put("reason", "missing_locator");
            trace.event("fsm_routing_step_end", step);
            return new RouteStepExec(step, false);
        }

        long deadline = System.currentTimeMillis() + ROUTING_FIRST_STEP_WINDOW_MS;
        int probes = 0;
        String lastResolveErr = "";

        while (System.currentTimeMillis() <= deadline) {
            probes += 1;

            try {
                ResolvedNode node = resolver.resolve(locator);
                int cx = (node.bounds.left + node.bounds.right) / 2;
                int cy = (node.bounds.top + node.bounds.bottom) / 2;

                ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                tapPayload.putShort((short) cx);
                tapPayload.putShort((short) cy);
                byte[] resp = execution.handleTap(tapPayload.array());

                step.put("picked_stage", node.pickedStage);
                step.put("picked_bounds", node.bounds.toList());
                step.put("tap_resp_len", resp != null ? resp.length : 0);
                step.put("window_probe_count", probes);
                step.put("result", "ok");
                step.put("reason", "first_step_window_route_hit");

                Map<String, Object> hitEv = new LinkedHashMap<>();
                hitEv.put("task_id", ctx.taskId);
                hitEv.put("package", pkg);
                hitEv.put("index", index);
                hitEv.put("probe", probes);
                hitEv.put("x", cx);
                hitEv.put("y", cy);
                hitEv.put("picked_stage", node.pickedStage);
                trace.event("fsm_routing_first_step_route_hit", hitEv);

                trace.event("fsm_routing_step_end", step);
                return new RouteStepExec(step, true);
            } catch (Exception e) {
                lastResolveErr = String.valueOf(e);
            }

            boolean skipTapped = tryTapRoutingSkipCandidate(ctx, pkg, index, probes);
            if (skipTapped) {
                sleepQuiet(ROUTING_FIRST_STEP_AFTER_SKIP_SLEEP_MS);
            }
            sleepQuiet(ROUTING_FIRST_STEP_SAMPLE_MS);
        }

        step.put("window_probe_count", probes);
        step.put("result", "resolve_fail");
        step.put("reason", lastResolveErr.isEmpty()
                ? "first_step_window_timeout"
                : ("first_step_window_timeout:" + lastResolveErr));
        trace.event("fsm_routing_step_end", step);
        return new RouteStepExec(step, false);
    }

    private RouteStepExec executeRegularRoutingStep(
            Context ctx,
            String pkg,
            RouteMap.Transition t,
            LocatorResolver resolver,
            int index
    ) {
        Map<String, Object> stepEv = new LinkedHashMap<>();
        stepEv.put("task_id", ctx.taskId);
        stepEv.put("package", pkg);
        stepEv.put("from_page", t.fromPage);
        stepEv.put("to_page", t.toPage);
        stepEv.put("index", index);
        stepEv.put("description", t.description);
        trace.event("fsm_routing_step_start", stepEv);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", index);
        step.put("from", t.fromPage);
        step.put("to", t.toPage);
        step.put("description", t.description);

        String result = "ok";
        String reason = "";
        String pickedStage = "";
        List<Object> pickedBounds = null;

        try {
            Locator locator = t.action != null ? t.action.locator : null;
            if (locator == null) {
                result = "resolve_fail";
                reason = "missing_locator";
            } else {
                ResolvedNode node = null;
                String lastResolveErr = "";
                int resolveAttempts = 0;
                for (int resolveAttempt = 1; resolveAttempt <= ROUTING_STEP_RESOLVE_RETRY_MAX; resolveAttempt++) {
                    resolveAttempts = resolveAttempt;
                    try {
                        node = resolver.resolve(locator);
                        if (resolveAttempt > 1) {
                            Map<String, Object> okRetryEv = new LinkedHashMap<>();
                            okRetryEv.put("task_id", ctx.taskId);
                            okRetryEv.put("package", pkg);
                            okRetryEv.put("index", index);
                            okRetryEv.put("attempt", resolveAttempt);
                            okRetryEv.put("result", "recovered");
                            trace.event("fsm_routing_step_resolve_retry", okRetryEv);
                        }
                        break;
                    } catch (Exception re) {
                        lastResolveErr = String.valueOf(re);
                        Map<String, Object> retryEv = new LinkedHashMap<>();
                        retryEv.put("task_id", ctx.taskId);
                        retryEv.put("package", pkg);
                        retryEv.put("index", index);
                        retryEv.put("attempt", resolveAttempt);
                        retryEv.put("max_attempts", ROUTING_STEP_RESOLVE_RETRY_MAX);
                        retryEv.put("reason", lastResolveErr);
                        retryEv.put("retrying", resolveAttempt < ROUTING_STEP_RESOLVE_RETRY_MAX);
                        trace.event("fsm_routing_step_resolve_retry", retryEv);
                        if (resolveAttempt < ROUTING_STEP_RESOLVE_RETRY_MAX) {
                            sleepQuiet(ROUTING_STEP_RESOLVE_RETRY_SLEEP_MS);
                        }
                    }
                }

                step.put("resolve_attempts", resolveAttempts);
                if (node == null) {
                    result = "resolve_fail";
                    reason = lastResolveErr.isEmpty() ? "resolve_failed_after_retries" : lastResolveErr;
                } else {
                    pickedStage = node.pickedStage;
                    pickedBounds = node.bounds.toList();

                    int cx = (node.bounds.left + node.bounds.right) / 2;
                    int cy = (node.bounds.top + node.bounds.bottom) / 2;

                    ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                    tapPayload.putShort((short) cx);
                    tapPayload.putShort((short) cy);
                    byte[] resp = execution.handleTap(tapPayload.array());

                    step.put("tap_resp_len", resp != null ? resp.length : 0);
                }
            }
        } catch (Exception e) {
            String msg = String.valueOf(e);
            if (result.startsWith("resolve")) {
                result = "resolve_fail";
            } else {
                result = "tap_fail";
            }
            reason = msg;
        }

        step.put("picked_stage", pickedStage);
        if (pickedBounds != null) {
            step.put("picked_bounds", pickedBounds);
        }
        step.put("result", result);
        step.put("reason", reason);

        trace.event("fsm_routing_step_end", step);
        return new RouteStepExec(step, "ok".equals(result));
    }

    private boolean tryRoutePopupRecoveryByVision(
            Context ctx,
            String pkg,
            int failedIndex,
            RouteMap.Transition failedTransition,
            String failReason
    ) {
        try {
            Map<String, Object> begin = new LinkedHashMap<>();
            begin.put("task_id", ctx.taskId);
            begin.put("package", pkg);
            begin.put("failed_index", failedIndex);
            begin.put("from_page", failedTransition != null ? failedTransition.fromPage : "");
            begin.put("to_page", failedTransition != null ? failedTransition.toPage : "");
            begin.put("reason", failReason);
            trace.event("fsm_routing_recovery_begin", begin);

            byte[] shotResp = perception != null ? perception.handleScreenshot() : null;
            byte[] screenshotPng = null;
            if (shotResp != null && shotResp.length > 1 && shotResp[0] != 0x00) {
                screenshotPng = Arrays.copyOfRange(shotResp, 1, shotResp.length);
            }
            if (screenshotPng == null || screenshotPng.length == 0) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("reason", "screenshot_unavailable");
                trace.event("fsm_routing_recovery_skip", ev);
                return false;
            }

            LlmConfig cfg = LlmConfig.loadDefault();
            String prompt = buildRoutingRecoveryPrompt(ctx, pkg, failedIndex, failedTransition, failReason);
            Map<String, Object> promptEv = new LinkedHashMap<>();
            promptEv.put("task_id", ctx.taskId);
            promptEv.put("failed_index", failedIndex);
            promptEv.put("prompt", prompt);
            trace.event("llm_prompt_routing_recovery", promptEv);

            String raw = llmClient.chatOnce(cfg, null, prompt, screenshotPng);
            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("failed_index", failedIndex);
            respEv.put("response", raw != null && raw.length() > 2000 ? raw.substring(0, 2000) + "..." : raw);
            trace.event("llm_response_routing_recovery", respEv);

            String normalized = normalizeModelOutput(raw, State.VISION_ACT, ctx);
            List<Instruction> cmds = parseInstructions(normalized, 1);
            if (cmds == null || cmds.isEmpty()) {
                return false;
            }

            Instruction cmd = cmds.get(0);
            String argErr = validateVisionCommandArgs(cmd);
            if (!argErr.isEmpty()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("failed_index", failedIndex);
                ev.put("command", cmd.raw);
                ev.put("reason", argErr);
                trace.event("fsm_routing_recovery_invalid_command", ev);
                return false;
            }

            String op = cmd.op != null ? cmd.op : "";
            if ("FAIL".equals(op) || "DONE".equals(op)) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("failed_index", failedIndex);
                ev.put("command", cmd.raw);
                ev.put("result", "not_popup");
                trace.event("fsm_routing_recovery_decision", ev);
                return false;
            }
            if (!"TAP".equals(op) && !"BACK".equals(op) && !"WAIT".equals(op)) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("failed_index", failedIndex);
                ev.put("command", cmd.raw);
                ev.put("result", "unsupported_op");
                trace.event("fsm_routing_recovery_decision", ev);
                return false;
            }

            boolean ok = execActionCommand(ctx, cmd);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("failed_index", failedIndex);
            done.put("command", cmd.raw);
            done.put("op", op);
            done.put("action_ok", ok);
            trace.event("fsm_routing_recovery_exec", done);
            if (ok) {
                ctx.error = "";
            }
            return ok;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx != null ? ctx.taskId : "");
            ev.put("package", pkg);
            ev.put("failed_index", failedIndex);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_recovery_err", ev);
            return false;
        }
    }

    private String buildRoutingRecoveryPrompt(
            Context ctx,
            String pkg,
            int failedIndex,
            RouteMap.Transition failedTransition,
            String failReason
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are assisting ROUTING recovery on Android UI.\n");
        sb.append("Task: determine whether a FULL-SCREEN or LARGE blocking popup/overlay is preventing route locator matching.\n");
        sb.append("Only handle severe blockers that occupy most of the screen and block normal interaction.\n");
        sb.append("If such blocker exists, output ONE dismiss action command.\n");
        sb.append("If not, output FAIL not_popup.\n");
        sb.append("\n");
        sb.append("Current context:\n");
        sb.append("- package: ").append(pkg).append("\n");
        sb.append("- user_task: ").append(ctx != null ? stringOrEmpty(ctx.userTask) : "").append("\n");
        sb.append("- failed_route_step_index: ").append(failedIndex).append("\n");
        sb.append("- failed_from_page: ").append(failedTransition != null ? stringOrEmpty(failedTransition.fromPage) : "").append("\n");
        sb.append("- failed_to_page: ").append(failedTransition != null ? stringOrEmpty(failedTransition.toPage) : "").append("\n");
        sb.append("- failed_reason: ").append(failReason != null ? failReason : "").append("\n");
        sb.append("\n");
        sb.append("Allowed outputs (one line only):\n");
        sb.append("- TAP x y      (x,y normalized in [0,1000])\n");
        sb.append("- BACK\n");
        sb.append("- WAIT ms\n");
        sb.append("- FAIL not_popup\n");
        sb.append("\n");
        sb.append("Rules:\n");
        sb.append("1) Output exactly one command line, no extra text.\n");
        sb.append("2) Treat as popup only when it is full-screen or large center modal/overlay (roughly >= 40% screen area), and it blocks routing interaction.\n");
        sb.append("3) Ignore small ads/cards/inline banners/floating widgets that do not block the whole page; for these output FAIL not_popup.\n");
        sb.append("4) Only close the blocker; do not perform business actions.\n");
        sb.append("5) If uncertain, output FAIL not_popup.\n");
        return sb.toString();
    }

    private boolean tryTapRoutingSkipCandidate(Context ctx, String pkg, int stepIndex, int probeIndex) {
        try {
            byte[] payload = perception != null ? perception.handleDumpActions(new byte[0]) : null;
            List<DumpActionsParser.ActionNode> nodes = DumpActionsParser.parse(payload);
            if (nodes == null || nodes.isEmpty()) {
                return false;
            }

            int screenW = parseIntLike(ctx.deviceInfo.get("width"), 0);
            int screenH = parseIntLike(ctx.deviceInfo.get("height"), 0);
            if (screenW <= 0 || screenH <= 0) {
                for (DumpActionsParser.ActionNode n : nodes) {
                    if (n == null || n.bounds == null) {
                        continue;
                    }
                    screenW = Math.max(screenW, n.bounds.right);
                    screenH = Math.max(screenH, n.bounds.bottom);
                }
            }
            if (screenW <= 0) {
                screenW = 1080;
            }
            if (screenH <= 0) {
                screenH = 2400;
            }

            SkipCandidate best = null;
            int matched = 0;
            for (DumpActionsParser.ActionNode n : nodes) {
                SkipCandidate c = scoreSplashSkipCandidate(n, screenW, screenH);
                if (c == null) {
                    continue;
                }
                matched += 1;
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }

            if (best != null) {
                Map<String, Object> probeEv = new LinkedHashMap<>();
                probeEv.put("task_id", ctx.taskId);
                probeEv.put("package", pkg);
                probeEv.put("index", stepIndex);
                probeEv.put("probe", probeIndex);
                probeEv.put("matched", matched);
                probeEv.put("best_score", best.score);
                probeEv.put("best_text", best.label);
                probeEv.put("best_x", best.x);
                probeEv.put("best_y", best.y);
                trace.event("fsm_routing_first_step_skip_probe", probeEv);
            }

            if (best == null || best.score < ROUTING_FIRST_STEP_SKIP_MIN_SCORE) {
                return false;
            }

            boolean tapOk = sendTap(best.x, best.y);
            Map<String, Object> tapEv = new LinkedHashMap<>();
            tapEv.put("task_id", ctx.taskId);
            tapEv.put("package", pkg);
            tapEv.put("index", stepIndex);
            tapEv.put("probe", probeIndex);
            tapEv.put("x", best.x);
            tapEv.put("y", best.y);
            tapEv.put("score", best.score);
            tapEv.put("text", best.label);
            tapEv.put("tap_ok", tapOk);
            trace.event("fsm_routing_first_step_skip_tap", tapEv);
            return tapOk;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx != null ? ctx.taskId : "");
            ev.put("package", pkg);
            ev.put("index", stepIndex);
            ev.put("probe", probeIndex);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_first_step_skip_err", ev);
            return false;
        }
    }

    private boolean launchAppClearTaskForRouting(String packageName) {
        byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
        int flags = 0x01; // CLEAR_TASK
        buf.put((byte) flags);
        buf.putShort((short) pkgBytes.length);
        buf.put(pkgBytes);
        byte[] resp = execution != null ? execution.handleLaunchApp(buf.array()) : null;
        boolean ok = resp != null && resp.length > 0 && resp[0] == 0x01;
        if (!ok) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("status", resp != null && resp.length > 0 ? (int) resp[0] : 0);
            trace.event("fsm_routing_launch_status", ev);
        }
        return ok;
    }

    private boolean waitForForegroundPackageForRouting(
            String expectedPackage, long timeoutMs, long sampleMs, int requiredHits
    ) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        int hits = 0;
        int unknownSamples = 0;
        while (true) {
            String currentPkg = getCurrentPackageForRouting();
            if (expectedPackage.equals(currentPkg)) {
                hits += 1;
                unknownSamples = 0;
                if (hits >= Math.max(1, requiredHits)) {
                    return true;
                }
            } else if (currentPkg == null || currentPkg.trim().isEmpty()) {
                // Transient GET_ACTIVITY miss: do not immediately clear progress.
                unknownSamples += 1;
                if (unknownSamples >= 3) {
                    hits = 0;
                }
            } else {
                hits = 0;
                unknownSamples = 0;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1L, sampleMs));
            } catch (InterruptedException ignored) {
                return false;
            }
        }
    }

    private static class ActivityInfo {
        final String packageName;
        final String activityName;

        ActivityInfo(String packageName, String activityName) {
            this.packageName = packageName != null ? packageName : "";
            this.activityName = activityName != null ? activityName : "";
        }
    }

    private ActivityInfo getCurrentActivityInfoForRouting() {
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            if (resp == null || resp.length < 5) {
                return new ActivityInfo("", "");
            }
            ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
            byte status = buf.get();
            if (status == 0) {
                return new ActivityInfo("", "");
            }
            int pkgLen = buf.getShort() & 0xFFFF;
            if (pkgLen <= 0 || buf.remaining() < pkgLen) {
                return new ActivityInfo("", "");
            }
            byte[] pkgBytes = new byte[pkgLen];
            buf.get(pkgBytes);
            String pkg = new String(pkgBytes, StandardCharsets.UTF_8).trim();

            String act = "";
            if (buf.remaining() >= 2) {
                int actLen = buf.getShort() & 0xFFFF;
                if (actLen > 0 && buf.remaining() >= actLen) {
                    byte[] actBytes = new byte[actLen];
                    buf.get(actBytes);
                    act = new String(actBytes, StandardCharsets.UTF_8).trim();
                }
            }
            return new ActivityInfo(pkg, act);
        } catch (Exception ignored) {
            return new ActivityInfo("", "");
        }
    }

    private String getCurrentPackageForRouting() {
        return getCurrentActivityInfoForRouting().packageName;
    }

    private boolean isLikelyLockscreenShown() {
        ActivityInfo a = getCurrentActivityInfoForRouting();
        String pkg = a.packageName.toLowerCase(Locale.ROOT);
        String act = a.activityName.toLowerCase(Locale.ROOT);
        return pkg.contains("systemui")
                || act.contains("keyguard")
                || act.contains("lockscreen");
    }

    private Map<String, Object> captureRoutingUiFingerprint() {
        Map<String, Object> out = new LinkedHashMap<>();
        ActivityInfo a = getCurrentActivityInfoForRouting();
        out.put("package", a.packageName);
        out.put("activity", a.activityName);

        try {
            byte[] payload = perception != null ? perception.handleDumpActions(new byte[0]) : null;
            List<DumpActionsParser.ActionNode> nodes = DumpActionsParser.parse(payload);
            out.put("node_count", nodes != null ? nodes.size() : 0);

            List<String> samples = new ArrayList<>();
            if (nodes != null) {
                for (DumpActionsParser.ActionNode n : nodes) {
                    String s = "";
                    if (n != null) {
                        if (n.text != null && !n.text.trim().isEmpty()) {
                            s = "text:" + n.text.trim();
                        } else if (n.contentDesc != null && !n.contentDesc.trim().isEmpty()) {
                            s = "desc:" + n.contentDesc.trim();
                        } else if (n.resourceId != null && !n.resourceId.trim().isEmpty()) {
                            s = "rid:" + n.resourceId.trim();
                        }
                    }
                    if (!s.isEmpty() && !samples.contains(s)) {
                        samples.add(s);
                        if (samples.size() >= 8) {
                            break;
                        }
                    }
                }
            }
            out.put("samples", samples);
        } catch (Exception e) {
            out.put("dump_actions_error", String.valueOf(e));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean isLikelyLauncherUi(Map<String, Object> ui) {
        if (ui == null || ui.isEmpty()) {
            return false;
        }
        Object samplesObj = ui.get("samples");
        if (!(samplesObj instanceof List)) {
            return false;
        }
        List<Object> arr = (List<Object>) samplesObj;
        if (arr.isEmpty()) {
            return false;
        }

        int hit = 0;
        for (Object o : arr) {
            String s = stringOrEmpty(o);
            if (s.isEmpty()) {
                continue;
            }
            if (s.contains("页共") || s.contains("第1屏") || s.contains("第 1 屏")) {
                hit += 2;
                continue;
            }
            if (s.contains("Play 商店") || s.contains("YouTube") || s.contains("Discord")
                    || s.contains("王者荣耀") || s.contains("抖音") || s.contains("微博")
                    || s.contains("智能助理")) {
                hit += 1;
            }
        }
        return hit >= 2;
    }

    private void loadUnlockPolicyFromConfig(Context ctx) {
        ctx.autoUnlockBeforeRoute = true;
        ctx.autoLockAfterTask = true;
        ctx.unlockPin = "";
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            ctx.autoUnlockBeforeRoute = cfg.autoUnlockBeforeRoute;
            ctx.autoLockAfterTask = cfg.autoLockAfterTask;
            ctx.unlockPin = cfg.unlockPin != null ? cfg.unlockPin.trim() : "";

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("auto_unlock_before_route", ctx.autoUnlockBeforeRoute);
            ev.put("auto_lock_after_task", ctx.autoLockAfterTask);
            ev.put("has_unlock_pin", !ctx.unlockPin.isEmpty());
            trace.event("fsm_unlock_policy_loaded", ev);
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("reason", "config_unavailable");
            ev.put("err", String.valueOf(e));
            ev.put("auto_unlock_before_route", ctx.autoUnlockBeforeRoute);
            ev.put("auto_lock_after_task", ctx.autoLockAfterTask);
            trace.event("fsm_unlock_policy_default", ev);
        }
    }

    private boolean ensureUnlockedBeforeRoute(Context ctx, String packageName, int routeAttempt) {
        if (!ctx.autoUnlockBeforeRoute) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("package", packageName);
            ev.put("route_attempt", routeAttempt);
            ev.put("result", "skip_policy_disabled");
            trace.event("fsm_route_unlock", ev);
            return true;
        }

        int state = getScreenStateCode();
        boolean lockHint = isLikelyLockscreenShown();
        if (state == 1 && !lockHint) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("package", packageName);
            ev.put("route_attempt", routeAttempt);
            ev.put("screen_state", state);
            ev.put("lock_hint", false);
            ev.put("result", "already_unlocked");
            trace.event("fsm_route_unlock", ev);
            return true;
        }

        // Locked/off or lockscreen-like foreground: perform unlock flow with stable checks.
        for (int unlockAttempt = 1; unlockAttempt <= 3; unlockAttempt++) {
            byte[] unlockResp = execution != null ? execution.handleUnlock(new byte[0]) : null;
            boolean unlockCmdOk = unlockResp != null && unlockResp.length > 0 && unlockResp[0] == 0x01;
            sleepQuiet(UNLOCK_POST_CMD_SLEEP_MS);

            int stateAfterUnlockCmd = getScreenStateCode();
            boolean lockHintAfterUnlockCmd = isLikelyLockscreenShown();
            boolean pinTried = false;
            boolean pinInputOk = false;
            boolean homeSent = false;
            boolean unlockedStable = false;
            boolean homeStable = false;

            // After wake+swipe, if lockscreen is still shown, try PIN/password.
            if ((stateAfterUnlockCmd != 1 || lockHintAfterUnlockCmd) && !ctx.unlockPin.isEmpty()) {
                pinTried = true;
                pinInputOk = tryInputUnlockPin(ctx, ctx.unlockPin);
                sleepQuiet(UNLOCK_POST_PIN_SLEEP_MS);
            }

            unlockedStable = waitForUnlockedStableForRouting(
                    UNLOCK_STABLE_TIMEOUT_MS,
                    UNLOCK_STABLE_SAMPLE_MS,
                    UNLOCK_STABLE_REQUIRED_HITS
            );

            // Unlock considered successful only when lockscreen signal disappears stably.
            if (unlockedStable) {
                homeSent = sendKeyClick(KEYCODE_HOME);
                sleepQuiet(UNLOCK_POST_HOME_SLEEP_MS);
                homeStable = waitForUnlockedStableForRouting(
                        UNLOCK_STABLE_TIMEOUT_MS,
                        UNLOCK_STABLE_SAMPLE_MS,
                        UNLOCK_STABLE_REQUIRED_HITS
                );
                if (homeStable) {
                    ctx.unlockedByFsm = true;
                }
            }

            int finalState = getScreenStateCode();
            boolean finalLockHint = isLikelyLockscreenShown();

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("package", packageName);
            ev.put("route_attempt", routeAttempt);
            ev.put("unlock_attempt", unlockAttempt);
            ev.put("unlock_cmd_ok", unlockCmdOk);
            ev.put("pin_tried", pinTried);
            ev.put("pin_input_ok", pinInputOk);
            ev.put("screen_state_after_unlock_cmd", stateAfterUnlockCmd);
            ev.put("lock_hint_after_unlock_cmd", lockHintAfterUnlockCmd);
            ev.put("unlocked_stable", unlockedStable);
            ev.put("home_sent", homeSent);
            ev.put("home_stable", homeStable);
            ev.put("screen_state_final", finalState);
            ev.put("lock_hint_final", finalLockHint);
            trace.event("fsm_route_unlock_attempt", ev);

            if (unlockedStable && homeStable && finalState == 1 && !finalLockHint) {
                Map<String, Object> okEv = new LinkedHashMap<>();
                okEv.put("task_id", ctx.taskId);
                okEv.put("package", packageName);
                okEv.put("route_attempt", routeAttempt);
                okEv.put("unlock_attempt", unlockAttempt);
                okEv.put("home_sent", homeSent);
                okEv.put("result", "ok");
                trace.event("fsm_route_unlock", okEv);
                return true;
            }
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("package", packageName);
        ev.put("route_attempt", routeAttempt);
        ev.put("result", "failed");
        ev.put("screen_state", getScreenStateCode());
        ev.put("lock_hint", isLikelyLockscreenShown());
        trace.event("fsm_route_unlock", ev);
        return false;
    }

    private boolean waitForUnlockedStableForRouting(long timeoutMs, long sampleMs, int requiredHits) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        int hits = 0;
        while (true) {
            int state = getScreenStateCode();
            boolean lockHint = isLikelyLockscreenShown();
            if (state == 1 && !lockHint) {
                hits += 1;
                if (hits >= Math.max(1, requiredHits)) {
                    return true;
                }
            } else {
                hits = 0;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            sleepQuiet(Math.max(1L, sampleMs));
        }
    }

    private boolean tryInputUnlockPin(Context ctx, String pin) {
        if (pin == null || pin.trim().isEmpty()) {
            return false;
        }
        String text = pin.trim();
        if (isDigitsOnly(text)) {
            boolean tapModeOk = tryInputUnlockPinByTap(ctx, text);
            if (tapModeOk) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx != null ? ctx.taskId : "");
                ev.put("mode", "tap");
                ev.put("digits", text.length());
                ev.put("result", "ok");
                trace.event("fsm_unlock_pin_input", ev);
                return true;
            }

            Map<String, Object> fallbackEv = new LinkedHashMap<>();
            fallbackEv.put("task_id", ctx != null ? ctx.taskId : "");
            fallbackEv.put("mode", "tap");
            fallbackEv.put("digits", text.length());
            fallbackEv.put("result", "fallback_to_keyevent");
            trace.event("fsm_unlock_pin_input", fallbackEv);

            boolean allOk = true;
            for (int i = 0; i < text.length(); i++) {
                int d = text.charAt(i) - '0';
                int keycode = 7 + d; // KEYCODE_0..KEYCODE_9
                allOk = sendKeyClick(keycode) && allOk;
                sleepQuiet(80);
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx != null ? ctx.taskId : "");
            ev.put("mode", "key_event");
            ev.put("digits", text.length());
            ev.put("result", allOk ? "ok" : "failed");
            trace.event("fsm_unlock_pin_input", ev);
            return allOk;
        }

        int[] methods = containsNonAscii(text)
                ? new int[]{INPUT_METHOD_CLIPBOARD, INPUT_METHOD_ADB}
                : new int[]{INPUT_METHOD_ADB, INPUT_METHOD_CLIPBOARD};
        boolean inputOk = false;
        for (int method : methods) {
            byte[] resp = sendInputText(method, (byte) 0x00, (short) 0, (short) 0, (short) 0, text);
            int status = (resp != null && resp.length >= 1) ? (resp[0] & 0xFF) : 0;
            if (status == 1) {
                inputOk = true;
                break;
            }
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx != null ? ctx.taskId : "");
        ev.put("mode", "input_text");
        ev.put("chars", text.length());
        ev.put("result", inputOk ? "ok" : "failed");
        trace.event("fsm_unlock_pin_input", ev);
        return inputOk;
    }

    private boolean tryInputUnlockPinByTap(Context ctx, String digits) {
        if (digits == null || digits.isEmpty()) {
            return false;
        }
        try {
            byte[] payload = perception != null ? perception.handleDumpActions(new byte[0]) : null;
            List<DumpActionsParser.ActionNode> nodes = DumpActionsParser.parse(payload);
            if (nodes == null || nodes.isEmpty()) {
                return false;
            }

            int bottomMax = 0;
            for (DumpActionsParser.ActionNode n : nodes) {
                if (n != null && n.bounds != null) {
                    bottomMax = Math.max(bottomMax, n.bounds.bottom);
                }
            }
            if (bottomMax <= 0) {
                bottomMax = 2400;
            }

            for (int i = 0; i < digits.length(); i++) {
                char d = digits.charAt(i);
                DigitTapTarget target = pickDigitTapTarget(nodes, d, bottomMax);
                if (target == null) {
                    Map<String, Object> miss = new LinkedHashMap<>();
                    miss.put("task_id", ctx != null ? ctx.taskId : "");
                    miss.put("index", i);
                    miss.put("digit", String.valueOf(d));
                    miss.put("reason", "digit_not_found_in_dump_actions");
                    trace.event("fsm_unlock_pin_tap_digit", miss);
                    return false;
                }
                boolean tapOk = sendTap(target.x, target.y);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx != null ? ctx.taskId : "");
                ev.put("index", i);
                ev.put("digit", String.valueOf(d));
                ev.put("x", target.x);
                ev.put("y", target.y);
                ev.put("score", target.score);
                ev.put("source", target.source);
                ev.put("tap_ok", tapOk);
                trace.event("fsm_unlock_pin_tap_digit", ev);
                if (!tapOk) {
                    return false;
                }
                sleepQuiet(100);
            }
            return true;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx != null ? ctx.taskId : "");
            ev.put("reason", "tap_mode_exception");
            ev.put("err", String.valueOf(e));
            trace.event("fsm_unlock_pin_tap_failed", ev);
            return false;
        }
    }

    private DigitTapTarget pickDigitTapTarget(List<DumpActionsParser.ActionNode> nodes, char digit, int screenBottom) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        DigitTapTarget best = null;
        for (DumpActionsParser.ActionNode n : nodes) {
            if (n == null || n.bounds == null) {
                continue;
            }
            int score = scoreDigitField(n.text, digit, "text");
            String source = "text";
            int descScore = scoreDigitField(n.contentDesc, digit, "desc");
            if (descScore > score) {
                score = descScore;
                source = "desc";
            }
            int ridScore = scoreDigitField(n.resourceId, digit, "rid");
            if (ridScore > score) {
                score = ridScore;
                source = "rid";
            }
            if (score <= 0) {
                continue;
            }

            // Prefer clickable key-like nodes near lower screen area.
            if ((n.type & 0x01) != 0) {
                score += 25;
            } else if ((n.type & 0x08) != 0) {
                score -= 25;
            }
            int centerY = n.bounds.centerY();
            if (centerY >= (screenBottom * 55) / 100) {
                score += 18;
            } else {
                score -= 16;
            }

            int w = Math.max(0, n.bounds.right - n.bounds.left);
            int h = Math.max(0, n.bounds.bottom - n.bounds.top);
            if (w <= 0 || h <= 0) {
                continue;
            }
            if (w > 1000 || h > 800) {
                score -= 30;
            }

            if (best == null || score > best.score) {
                best = new DigitTapTarget(n.bounds.centerX(), centerY, score, source);
            }
        }
        return best;
    }

    private int scoreDigitField(String value, char digit, String source) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return 0;
        }
        String d = String.valueOf(digit);

        // Strong exact match ("1").
        if (s.equals(d)) {
            return 200;
        }
        // Typical keypad label like "1 ABC".
        if (s.startsWith(d) && s.length() > 1) {
            char c = s.charAt(1);
            if (!Character.isDigit(c)) {
                return 170;
            }
        }
        // Resource-id style match: "key_1", "digit1", "btn_1".
        if ("rid".equals(source)) {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.contains("key_" + d) || lower.contains("digit" + d) || lower.endsWith("_" + d)) {
                return 160;
            }
        }
        // Loose token match.
        if (containsTokenDigit(s, digit)) {
            return 130;
        }
        return 0;
    }

    private boolean containsTokenDigit(String s, char digit) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String d = String.valueOf(digit);
        String normalized = s.replaceAll("[^0-9A-Za-z]+", " ").trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String[] tokens = normalized.split("\\s+");
        for (String t : tokens) {
            if (d.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private boolean sendTap(int x, int y) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) x);
            buf.putShort((short) y);
            byte[] resp = execution != null ? execution.handleTap(buf.array()) : null;
            return resp != null && resp.length > 0 && resp[0] == 0x01;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static class DigitTapTarget {
        final int x;
        final int y;
        final int score;
        final String source;

        DigitTapTarget(int x, int y, int score, String source) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.source = source != null ? source : "";
        }
    }

    private boolean sendKeyClick(int keycode) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) (keycode & 0xFF));
            buf.put((byte) 2); // CLICK
            buf.putInt(0);
            byte[] resp = execution != null ? execution.handleKeyEvent(buf.array()) : null;
            return resp != null && resp.length > 0 && resp[0] == 0x01;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isDigitsOnly(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private int getScreenStateCode() {
        try {
            byte[] resp = perception != null ? perception.handleGetScreenState() : null;
            if (resp == null || resp.length < 2) {
                return -1;
            }
            int ok = resp[0] & 0xFF;
            if (ok == 0) {
                return -1;
            }
            return resp[1] & 0xFF;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void tryAutoLockAfterTask(Context ctx, State finalState) {
        if (!ctx.autoLockAfterTask || !ctx.unlockedByFsm) {
            return;
        }
        int before = getScreenStateCode();
        if (before != 1) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("final_state", finalState.name());
            ev.put("before_state", before);
            ev.put("result", "skip_not_unlocked_state");
            trace.event("fsm_auto_lock", ev);
            return;
        }

        boolean locked = false;
        int after = before;
        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean powerOk = sendKeyClick(KEYCODE_POWER);
            sleepQuiet(250);
            after = getScreenStateCode();
            locked = (after == 0 || after == 2);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("final_state", finalState.name());
            ev.put("attempt", attempt);
            ev.put("before_state", before);
            ev.put("after_state", after);
            ev.put("power_ok", powerOk);
            ev.put("locked", locked);
            trace.event("fsm_auto_lock_attempt", ev);

            if (locked) {
                break;
            }
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("final_state", finalState.name());
        ev.put("before_state", before);
        ev.put("after_state", after);
        ev.put("locked", locked);
        trace.event("fsm_auto_lock", ev);
    }

    /**
     * Refresh current activity info, a minimal port of Python _refresh_activity.
     */
    private void refreshActivity(Context ctx) {
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            boolean ok = false;
            String pkg = "";
            String act = "";
            if (resp != null && resp.length >= 5) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                ok = status != 0;
                int pkgLen = buf.getShort() & 0xFFFF;
                if (pkgLen >= 0 && buf.remaining() >= pkgLen + 2) {
                    byte[] pkgBytes = new byte[pkgLen];
                    buf.get(pkgBytes);
                    pkg = new String(pkgBytes, StandardCharsets.UTF_8);
                    int actLen = buf.getShort() & 0xFFFF;
                    if (actLen >= 0 && buf.remaining() >= actLen) {
                        byte[] actBytes = new byte[actLen];
                        buf.get(actBytes);
                        act = new String(actBytes, StandardCharsets.UTF_8);
                    }
                }
            }
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", ok);
            ctx.currentActivity.put("package", pkg != null ? pkg : "");
            ctx.currentActivity.put("activity", act != null ? act : "");

            String sig = stringOrEmpty(ctx.currentActivity.get("package")) + "/" + stringOrEmpty(ctx.currentActivity.get("activity"));
            if (sig.equals(ctx.lastActivitySig)) {
                ctx.sameActivityStreak += 1;
            } else {
                ctx.sameActivityStreak = 1;
                ctx.lastActivitySig = sig;
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("current_activity", new LinkedHashMap<>(ctx.currentActivity));
            ev.put("same_activity_streak", ctx.sameActivityStreak);
            trace.event("activity_refreshed", ev);
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("err", String.valueOf(e));
            trace.event("activity_refresh_failed", ev);
        }
    }

    /**
     * Result of extracting <command> and structured vision_analysis tags from model output.
     */
    private static class ExtractResult {
        final String commandText;
        final Map<String, Object> structured;

        ExtractResult(String commandText, Map<String, Object> structured) {
            this.commandText = commandText != null ? commandText : "";
            this.structured = structured != null ? structured : new LinkedHashMap<String, Object>();
        }
    }

    /**
     * Extract <command> and structured fields from a VISION_ACT response.
     * Java port of Python _extract_structured_command for CortexState.VISION_ACT.
     */
    private ExtractResult extractStructuredCommandForVision(String raw) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty()) {
            return new ExtractResult("", new LinkedHashMap<String, Object>());
        }

        String cmd = extractTagText(text, "command");
        if (cmd.isEmpty()) {
            // No command tag 鈥?keep raw text as command and no structured fields.
            return new ExtractResult(text, new LinkedHashMap<String, Object>());
        }

        String rootContent = extractTagText(text, "vision_analysis");
        if (rootContent.isEmpty()) {
            // Soft fallback: command only if top-level root is missing.
            return new ExtractResult(cmd.trim(), new LinkedHashMap<String, Object>());
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("root", "vision_analysis");
        String[] names = new String[]{
                "page_state",
                "step_review",
                "reflection",
                "next_step_reasoning",
                "completion_gate",
                "done_confirm",
                "lesson"
        };
        for (String name : names) {
            String fv = extractTagText(rootContent, name);
            if (!fv.isEmpty()) {
                fields.put(name, fv.trim());
            }
        }
        return new ExtractResult(cmd.trim(), fields);
    }

    /**
     * Collect lesson from structured vision output, Java port of Python _collect_lesson.
     */
    private void collectLesson(Context ctx, Map<String, Object> structured) {
        if (structured == null) {
            return;
        }
        Object val = structured.get("lesson");
        String raw = val == null ? "" : String.valueOf(val).trim();
        if (raw.isEmpty()) {
            return;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("n/a") || lower.equals("na")
                || lower.equals("null") || lower.equals("no lesson")) {
            return;
        }
        // Keep lesson concise to avoid prompt bloat.
        String lesson;
        if (raw.length() <= 180) {
            lesson = raw;
        } else {
            lesson = raw.substring(0, 180).replaceAll("\\s+$", "") + "...";
        }
        if (ctx.lessons.contains(lesson)) {
            return;
        }
        ctx.lessons.add(lesson);
    }

    /**
     * Append commands to context.commandLog and emit trace, Java port of _append_commands.
     */
    private void appendCommands(Context ctx, State state, List<Instruction> commands) {
        for (Instruction cmd : commands) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", state.name());
            row.put("op", cmd.op);
            row.put("args", new ArrayList<>(cmd.args));
            row.put("raw", cmd.raw);
            ctx.commandLog.add(row);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("state", state.name());
            ev.put("op", cmd.op);
            ev.put("args", new ArrayList<>(cmd.args));
            trace.event("fsm_command", ev);
        }
    }

    private State runVisionActState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.VISION_ACT.name());
        trace.event("fsm_state_enter", ev);

        // Turn limit by sub_task mode:
        // - single: 30
        // - loop: 60
        int maxTurns = resolveVisionMaxTurns(ctx);
        if (ctx.visionTurns >= maxTurns) {
            ctx.error = "vision_turn_limit";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("mode", resolveCurrentSubTaskMode(ctx));
            fail.put("vision_turns", ctx.visionTurns);
            fail.put("max_turns", maxTurns);
            trace.event("vision_turn_limit", fail);
            return State.FAIL;
        }
        ctx.visionTurns += 1;

        // First turn uses the same dump-actions based settle logic as action-post settle.
        if (ctx.visionTurns == 1) {
            waitForUiStableByDumpActions(ctx, "FIRST_TURN_PRE_SCREENSHOT");
        }

        // Fast local splash-ad skip:
        // avoid waiting for VLM when "skip" only exists for a short window.
        if (tryFastSkipSplashAd(ctx)) {
            return State.VISION_ACT;
        }

        // Screenshot: we still take it for parity with Python, but current LLM is text-only.
        byte[] shotResp = null;
        byte[] screenshotPng = null;
        int imageSize = 0;
        try {
            shotResp = perception != null ? perception.handleScreenshot() : null;
            if (shotResp != null && shotResp.length > 1 && shotResp[0] != 0x00) {
                // Protocol: first byte is status, remaining bytes are PNG data.
                screenshotPng = Arrays.copyOfRange(shotResp, 1, shotResp.length);
                imageSize = screenshotPng.length;
                Map<String, Object> readyEv = new LinkedHashMap<>();
                readyEv.put("task_id", ctx.taskId);
                readyEv.put("size", imageSize);
                readyEv.put("attached", true);
                trace.event("vision_screenshot_ready", readyEv);
            } else {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                trace.event("vision_screenshot_failed", fail);
            }
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("err", String.valueOf(e));
            trace.event("vision_screenshot_error", fail);
        }

        // Refresh activity + streak tracking
        refreshActivity(ctx);

        // Build full VISION_ACT prompt, extended with current sub_task contract when available.
        String prompt = buildVisionPrompt(ctx);
        // Unified retry budget per VISION_ACT turn:
        // parse failures + command-arg failures + planner call timeout/errors share the same attempts.
        final int maxVisionAttempts = 3;

        LlmConfig cfg;
        try {
            cfg = LlmConfig.loadDefault();
        } catch (Exception e) {
            ctx.error = "planner_call_failed:VISION_ACT:" + e;
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("state", State.VISION_ACT.name());
            fail.put("err", String.valueOf(e));
            trace.event("planner_call_failed", fail);
            return State.FAIL;
        }

        String retryReason = "";
        for (int attempt = 1; attempt <= maxVisionAttempts; attempt++) {
            String raw = "";
            String normalized = "";
            String observing = "";
            String observeResult = "";
            String judgingPrev = "";
            String judgePrevResult = "";
            String judgingGlobal = "";
            String judgeGlobalResult = "";
            String thinking = "";
            String actionText = "";
            String expectedText = "";
            String carryContextText = "";
            String memoryWriteText = "";
            String commandText = "";
            List<Instruction> commands;

            String attemptPrompt = prompt;
            if (attempt > 1) {
                StringBuilder retryPrompt = new StringBuilder(prompt);
                retryPrompt.append("\n\n[RETRY_CONTEXT]\n");
                retryPrompt.append("Previous attempt failed. reason: ").append(retryReason).append("\n");
                retryPrompt.append("Fix the output and return exactly the required 12 tags in order.\n");
                retryPrompt.append("In <command>, output one valid command line with strict argument format.\n");
                attemptPrompt = retryPrompt.toString();
            }

            Map<String, Object> promptEv = new LinkedHashMap<>();
            promptEv.put("task_id", ctx.taskId);
            promptEv.put("state", State.VISION_ACT.name());
            promptEv.put("attempt", attempt);
            promptEv.put("prompt", attemptPrompt);
            trace.event("llm_prompt_vision_act", promptEv);

            try {
                // Send multimodal request (prompt + screenshot) via chat/completions.
                raw = llmClient.chatOnce(cfg, null, attemptPrompt, screenshotPng);
            } catch (Exception e) {
                retryReason = "planner_call_failed:VISION_ACT:" + e;
                Map<String, Object> retryEv = new LinkedHashMap<>();
                retryEv.put("task_id", ctx.taskId);
                retryEv.put("state", State.VISION_ACT.name());
                retryEv.put("phase", "planner_call");
                retryEv.put("attempt", attempt);
                retryEv.put("max_attempts", maxVisionAttempts);
                retryEv.put("reason", retryReason);
                retryEv.put("retrying", attempt < maxVisionAttempts);
                trace.event("vision_retry", retryEv);
                if (attempt < maxVisionAttempts) {
                    continue;
                }
                ctx.error = retryReason;
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("state", State.VISION_ACT.name());
                fail.put("attempt", attempt);
                fail.put("err", String.valueOf(e));
                trace.event("planner_call_failed", fail);
                return State.FAIL;
            }

            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("state", State.VISION_ACT.name());
            respEv.put("attempt", attempt);
            String snippet = raw != null && raw.length() > 4000 ? raw.substring(0, 4000) + "..." : raw;
            respEv.put("response", snippet != null ? snippet : "");
            trace.event("llm_response_vision_act", respEv);

            try {
                // Parse the agreed regex-friendly output tags.
                observing = extractTagText(raw, "Observing");
                observeResult = extractTagText(raw, "Ovserve_result");
                judgingPrev = extractTagText(raw, "Judging_prev");
                if (judgingPrev.isEmpty()) {
                    judgingPrev = extractTagText(raw, "Judging");
                }
                judgePrevResult = extractTagText(raw, "Judge_prev_result");
                if (judgePrevResult.isEmpty()) {
                    judgePrevResult = extractTagText(raw, "Judge_result");
                }
                judgingGlobal = extractTagText(raw, "Judging_global");
                if (judgingGlobal.isEmpty()) {
                    judgingGlobal = judgingPrev;
                }
                judgeGlobalResult = extractTagText(raw, "Judge_global_result");
                if (judgeGlobalResult.isEmpty()) {
                    judgeGlobalResult = judgePrevResult;
                }
                thinking = extractTagText(raw, "Thinking");
                actionText = extractTagText(raw, "action");
                expectedText = extractTagText(raw, "expected");
                carryContextText = normalizeCarryContext(extractTagText(raw, "carry_context"));
                memoryWriteText = normalizeMemoryWrite(extractTagText(raw, "memory_write"));
                commandText = extractTagText(raw, "command");

                // Backward compatibility: if <command> is missing, try old extractor.
                if (commandText == null || commandText.trim().isEmpty()) {
                    ExtractResult er = extractStructuredCommandForVision(raw);
                    commandText = er.commandText;
                }
                if (commandText == null || commandText.trim().isEmpty()) {
                    throw new InstructionError("missing <command> tag");
                }

                // Normalize JSON outputs to DSL if needed.
                normalized = normalizeModelOutput(commandText, State.VISION_ACT, ctx);
                commands = parseInstructions(normalized, 1);
                validateAllowed(commands, VISION_ALLOWED_OPS);
            } catch (Exception e) {
                String parseError = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                retryReason = "vision_parse_error:" + parseError;
                Map<String, Object> retryEv = new LinkedHashMap<>();
                retryEv.put("task_id", ctx.taskId);
                retryEv.put("state", State.VISION_ACT.name());
                retryEv.put("phase", "parse");
                retryEv.put("attempt", attempt);
                retryEv.put("max_attempts", maxVisionAttempts);
                retryEv.put("error", parseError);
                retryEv.put("retrying", attempt < maxVisionAttempts);
                String retryRaw = (normalized != null && !normalized.isEmpty()) ? normalized : raw;
                if (retryRaw != null && retryRaw.length() > 1000) {
                    retryRaw = retryRaw.substring(0, 1000) + "...";
                }
                retryEv.put("raw", retryRaw != null ? retryRaw : "");
                trace.event("vision_parse_retry", retryEv);
                if (attempt < maxVisionAttempts) {
                    continue;
                }
                ctx.error = "vision_instruction_invalid_after_retries:" + parseError;
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("state", State.VISION_ACT.name());
                fail.put("error", parseError);
                trace.event("vision_instruction_invalid", fail);
                return State.FAIL;
            }

            // Command-arg strict validation before execution.
            String commandArgError = validateVisionCommandArgs(commands.get(0));
            if (!commandArgError.isEmpty()) {
                retryReason = "vision_command_arg_error:" + commandArgError;
                Map<String, Object> retryEv = new LinkedHashMap<>();
                retryEv.put("task_id", ctx.taskId);
                retryEv.put("state", State.VISION_ACT.name());
                retryEv.put("phase", "command_args");
                retryEv.put("attempt", attempt);
                retryEv.put("max_attempts", maxVisionAttempts);
                retryEv.put("reason", retryReason);
                retryEv.put("command", commands.get(0).raw);
                retryEv.put("retrying", attempt < maxVisionAttempts);
                trace.event("vision_retry", retryEv);
                if (attempt < maxVisionAttempts) {
                    continue;
                }
                ctx.error = "vision_command_invalid_after_retries:" + commandArgError;
                return State.FAIL;
            }

            // Snapshot mutable context so retry does not pollute history/logs.
            int llmHistorySizeBefore = ctx.llmHistory.size();
            int visionHistorySizeBefore = ctx.visionHistory.size();
            int commandLogSizeBefore = ctx.commandLog.size();
            String pendingInstructionBefore = ctx.pendingHistoryInstruction;
            String pendingExpectedBefore = ctx.pendingHistoryExpected;
            String pendingCarryBefore = ctx.pendingHistoryCarryContext;
            int workingMemorySizeBefore = ctx.workingMemory.size();
            String lastCommandBefore = ctx.lastCommand;
            int sameCommandStreakBefore = ctx.sameCommandStreak;

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("Observing", observing);
            structured.put("Ovserve_result", observeResult);
            structured.put("Judging_prev", judgingPrev);
            structured.put("Judge_prev_result", judgePrevResult);
            structured.put("Judging_global", judgingGlobal);
            structured.put("Judge_global_result", judgeGlobalResult);
            structured.put("Thinking", thinking);
            structured.put("action", actionText);
            structured.put("expected", expectedText);
            structured.put("carry_context", carryContextText);
            structured.put("memory_write", memoryWriteText);
            structured.put("command", commandText);

            Map<String, Object> hist = new LinkedHashMap<>();
            hist.put("state", State.VISION_ACT.name());
            hist.put("structured", structured);
            hist.put("command", commandText);
            ctx.llmHistory.add(hist);

            Map<String, Object> stEv = new LinkedHashMap<>();
            stEv.put("task_id", ctx.taskId);
            stEv.put("state", State.VISION_ACT.name());
            stEv.put("data", structured);
            stEv.put("command", commandText);
            trace.event("llm_structured_vision_act", stEv);

            // External history maintenance:
            // previous instruction/expected are matched with current actual/judgement.
            if (!ctx.pendingHistoryInstruction.isEmpty()
                    || !ctx.pendingHistoryExpected.isEmpty()
                    || !ctx.pendingHistoryCarryContext.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instruction", ctx.pendingHistoryInstruction);
                row.put("expected", ctx.pendingHistoryExpected);
                String actual = !observeResult.isEmpty() ? observeResult : observing;
                row.put("actual", actual);
                String jp = !judgePrevResult.isEmpty() ? judgePrevResult : "unknown";
                String jg = !judgeGlobalResult.isEmpty() ? judgeGlobalResult : jp;
                row.put("judgement_prev", jp);
                row.put("judgement_global", jg);
                // Keep legacy key for compatibility with old readers.
                row.put("judgement", jp);
                row.put("carry_context", ctx.pendingHistoryCarryContext);
                ctx.visionHistory.add(row);
                while (ctx.visionHistory.size() > 10) {
                    ctx.visionHistory.remove(0);
                }

                Map<String, Object> hEv = new LinkedHashMap<>();
                hEv.put("task_id", ctx.taskId);
                hEv.put("row", row);
                hEv.put("history_size", ctx.visionHistory.size());
                trace.event("vision_history_append", hEv);
            }

            appendWorkingMemory(ctx, memoryWriteText);

            // Stash next pair for history matching in next turn.
            ctx.pendingHistoryInstruction = actionText != null ? actionText.trim() : "";
            ctx.pendingHistoryExpected = expectedText != null ? expectedText.trim() : "";
            ctx.pendingHistoryCarryContext = carryContextText;

            Instruction cmd0 = commands.get(0);
            String currentSig = cmd0.raw.trim();
            if (!currentSig.isEmpty() && currentSig.equals(ctx.lastCommand)) {
                ctx.sameCommandStreak += 1;
            } else {
                ctx.sameCommandStreak = 1;
            }
            ctx.lastCommand = currentSig;

            if (ctx.sameCommandStreak >= 3 && ctx.sameActivityStreak >= 3) {
                ctx.error = "vision_action_loop_detected:repeated_same_command";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("command", currentSig);
                fail.put("same_command_streak", ctx.sameCommandStreak);
                fail.put("same_activity_streak", ctx.sameActivityStreak);
                trace.event("vision_action_loop_detected", fail);
                return State.FAIL;
            }

            appendCommands(ctx, State.VISION_ACT, commands);

            boolean retryAction = false;
            for (Instruction cmd : commands) {
                if ("DONE".equals(cmd.op)) {
                    String doneSummary = cmd.args.isEmpty() ? "" : String.join(" ", cmd.args).trim();
                    if (doneSummary.isEmpty()) {
                        doneSummary = !observeResult.isEmpty() ? observeResult : "";
                    }
                    if (doneSummary.isEmpty()) {
                        String objective = "";
                        if (ctx.currentSubTask != null) {
                            objective = stringOrEmpty(ctx.currentSubTask.description);
                        }
                        if (objective.isEmpty()) {
                            objective = stringOrEmpty(ctx.userTask);
                        }
                        doneSummary = objective.isEmpty()
                                ? "Sub-task completed."
                                : ("Sub-task completed: " + objective);
                    }
                    storeSubTaskSummary(ctx, doneSummary);
                    if (ctx.currentSubTaskIsLast) {
                        ctx.output.put("task_summary", doneSummary);
                    }
                    return State.FINISH;
                }
                if ("FAIL".equals(cmd.op)) {
                    String reason = cmd.args.isEmpty() ? "" : String.join(" ", cmd.args);
                    ctx.error = "vision_fail:" + reason;
                    return State.FAIL;
                }
                if (!execActionCommand(ctx, cmd)) {
                    if (ctx.error == null || ctx.error.isEmpty()) {
                        ctx.error = "vision_action_exec_failed:" + cmd.op;
                    }
                    retryReason = ctx.error;
                    if (attempt < maxVisionAttempts) {
                        retryAction = true;
                        Map<String, Object> retryEv = new LinkedHashMap<>();
                        retryEv.put("task_id", ctx.taskId);
                        retryEv.put("state", State.VISION_ACT.name());
                        retryEv.put("phase", "action_exec");
                        retryEv.put("attempt", attempt);
                        retryEv.put("max_attempts", maxVisionAttempts);
                        retryEv.put("reason", retryReason);
                        retryEv.put("command", cmd.raw);
                        retryEv.put("retrying", true);
                        trace.event("vision_retry", retryEv);
                        break;
                    }
                    return State.FAIL;
                }
            }

            if (retryAction) {
                while (ctx.llmHistory.size() > llmHistorySizeBefore) {
                    ctx.llmHistory.remove(ctx.llmHistory.size() - 1);
                }
                while (ctx.visionHistory.size() > visionHistorySizeBefore) {
                    ctx.visionHistory.remove(ctx.visionHistory.size() - 1);
                }
                while (ctx.commandLog.size() > commandLogSizeBefore) {
                    ctx.commandLog.remove(ctx.commandLog.size() - 1);
                }
                while (ctx.workingMemory.size() > workingMemorySizeBefore) {
                    ctx.workingMemory.remove(ctx.workingMemory.size() - 1);
                }
                ctx.pendingHistoryInstruction = pendingInstructionBefore;
                ctx.pendingHistoryExpected = pendingExpectedBefore;
                ctx.pendingHistoryCarryContext = pendingCarryBefore;
                ctx.lastCommand = lastCommandBefore;
                ctx.sameCommandStreak = sameCommandStreakBefore;
                ctx.error = "";
                continue;
            }

            return State.VISION_ACT;
        }

        ctx.error = "vision_retry_exhausted";
        return State.FAIL;
    }

    private boolean tryFastSkipSplashAd(Context ctx) {
        if (ctx == null || ctx.visionTurns > FAST_SKIP_MAX_TURNS) {
            return false;
        }
        try {
            byte[] payload = perception != null ? perception.handleDumpActions(new byte[0]) : null;
            List<DumpActionsParser.ActionNode> nodes = DumpActionsParser.parse(payload);
            if (nodes == null || nodes.isEmpty()) {
                return false;
            }

            int screenW = parseIntLike(ctx.deviceInfo.get("width"), 0);
            int screenH = parseIntLike(ctx.deviceInfo.get("height"), 0);
            if (screenW <= 0 || screenH <= 0) {
                for (DumpActionsParser.ActionNode n : nodes) {
                    if (n == null || n.bounds == null) {
                        continue;
                    }
                    screenW = Math.max(screenW, n.bounds.right);
                    screenH = Math.max(screenH, n.bounds.bottom);
                }
            }
            if (screenW <= 0) {
                screenW = 1080;
            }
            if (screenH <= 0) {
                screenH = 2400;
            }

            SkipCandidate best = null;
            int matched = 0;
            for (DumpActionsParser.ActionNode n : nodes) {
                SkipCandidate c = scoreSplashSkipCandidate(n, screenW, screenH);
                if (c == null) {
                    continue;
                }
                matched += 1;
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }

            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("task_id", ctx.taskId);
            probe.put("turn", ctx.visionTurns);
            probe.put("matched_nodes", matched);
            probe.put("screen_w", screenW);
            probe.put("screen_h", screenH);
            if (best != null) {
                probe.put("best_score", best.score);
                probe.put("best_text", best.label);
                probe.put("best_x", best.x);
                probe.put("best_y", best.y);
            }
            trace.event("vision_fast_skip_probe", probe);

            if (best == null || best.score < 120) {
                return false;
            }

            boolean tapOk = sendTap(best.x, best.y);
            Map<String, Object> tapEv = new LinkedHashMap<>();
            tapEv.put("task_id", ctx.taskId);
            tapEv.put("turn", ctx.visionTurns);
            tapEv.put("x", best.x);
            tapEv.put("y", best.y);
            tapEv.put("score", best.score);
            tapEv.put("text", best.label);
            tapEv.put("tap_ok", tapOk);
            trace.event("vision_fast_skip_tap", tapEv);
            if (!tapOk) {
                return false;
            }

            sleepQuiet(FAST_SKIP_POST_TAP_SLEEP_MS);
            waitForUiStableByDumpActions(ctx, "FAST_SKIP");
            return true;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("task_id", ctx != null ? ctx.taskId : "");
            err.put("turn", ctx != null ? ctx.visionTurns : 0);
            err.put("err", String.valueOf(e));
            trace.event("vision_fast_skip_error", err);
            return false;
        }
    }

    private SkipCandidate scoreSplashSkipCandidate(DumpActionsParser.ActionNode n, int screenW, int screenH) {
        if (n == null || n.bounds == null) {
            return null;
        }
        String text = stringOrEmpty(n.text).trim();
        String desc = stringOrEmpty(n.contentDesc).trim();
        String label = !text.isEmpty() ? text : desc;
        if (label.isEmpty()) {
            return null;
        }

        String lower = label.toLowerCase(Locale.ROOT);
        boolean keywordHit = lower.contains("跳过")
                || lower.equals("skip")
                || lower.contains("skip ad")
                || lower.contains("skipads")
                || lower.contains("略过");
        if (!keywordHit) {
            return null;
        }

        int x = n.bounds.centerX();
        int y = n.bounds.centerY();
        int w = Math.max(0, n.bounds.right - n.bounds.left);
        int h = Math.max(0, n.bounds.bottom - n.bounds.top);
        long area = (long) w * (long) h;
        long screenArea = (long) Math.max(1, screenW) * (long) Math.max(1, screenH);

        int score = 100;
        if ((n.type & 0x01) != 0) {
            score += 22;
        } else if ((n.type & 0x08) != 0) {
            score -= 18;
        }

        if (x >= (screenW * 72) / 100) {
            score += 20;
        } else if (x >= (screenW * 55) / 100) {
            score += 10;
        } else {
            score -= 16;
        }

        if (y <= (screenH * 18) / 100) {
            score += 20;
        } else if (y <= (screenH * 35) / 100) {
            score += 10;
        } else {
            score -= 18;
        }

        if (area > 0 && area <= (screenArea / 8)) {
            score += 8;
        } else if (area > (screenArea / 5)) {
            score -= 18;
        }

        if (label.length() > 12) {
            score -= 8;
        }
        if (lower.contains("广告") && lower.contains("跳过")) {
            score += 8;
        }

        return new SkipCandidate(x, y, score, label);
    }

    private static int parseIntLike(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static class SkipCandidate {
        final int x;
        final int y;
        final int score;
        final String label;

        SkipCandidate(int x, int y, int score, String label) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.label = label != null ? label : "";
        }
    }

    private static class RouteStepExec {
        final Map<String, Object> step;
        final boolean ok;

        RouteStepExec(Map<String, Object> step, boolean ok) {
            this.step = step != null ? step : new LinkedHashMap<String, Object>();
            this.ok = ok;
        }
    }

    /**
     * Execute a single action command (TAP, SWIPE, INPUT, WAIT, BACK) using Java engines.
     * Java port of Python _exec_action_command.
     */
    private boolean execActionCommand(Context ctx, Instruction cmd) {
        try {
            if ("TAP".equals(cmd.op)) {
                double xf = Double.parseDouble(cmd.args.get(0));
                double yf = Double.parseDouble(cmd.args.get(1));
                int[] mapped = mapPointByProbe(ctx, xf, yf);
                int x = mapped[0];
                int y = mapped[1];

                int tx = x;
                int ty = y;

                // Jitter disabled for now (can be enabled later).
                ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                buf.putShort((short) tx);
                buf.putShort((short) ty);
                trace.event("exec_tap_start", new LinkedHashMap<String, Object>() {{
                    put("task_id", ctx.taskId);
                    put("x", tx);
                    put("y", ty);
                }});
                execution.handleTap(buf.array());
                trace.event("exec_tap_done", new LinkedHashMap<String, Object>() {{
                    put("task_id", ctx.taskId);
                    put("x", tx);
                    put("y", ty);
                }});
                return waitForUiStableByDumpActions(ctx, "TAP");
            }
            if ("SWIPE".equals(cmd.op)) {
                double x1f = Double.parseDouble(cmd.args.get(0));
                double y1f = Double.parseDouble(cmd.args.get(1));
                double x2f = Double.parseDouble(cmd.args.get(2));
                double y2f = Double.parseDouble(cmd.args.get(3));
                int dur = Integer.parseInt(cmd.args.get(4));

                int[] p1 = mapPointByProbe(ctx, x1f, y1f);
                int[] p2 = mapPointByProbe(ctx, x2f, y2f);
                int x1 = p1[0];
                int y1 = p1[1];
                int x2 = p2[0];
                int y2 = p2[1];

                ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                buf.putShort((short) x1);
                buf.putShort((short) y1);
                buf.putShort((short) x2);
                buf.putShort((short) y2);
                buf.putShort((short) dur);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("x1", x1);
                ev.put("y1", y1);
                ev.put("x2", x2);
                ev.put("y2", y2);
                ev.put("duration", dur);
                trace.event("exec_swipe_start", ev);
                execution.handleSwipe(buf.array());
                trace.event("exec_swipe_done", ev);
                Map<String, Object> waitEv = new LinkedHashMap<>();
                waitEv.put("task_id", ctx.taskId);
                waitEv.put("op", "SWIPE");
                waitEv.put("wait_ms", SWIPE_POST_WAIT_MS);
                trace.event("exec_swipe_post_wait", waitEv);
                sleepQuiet(SWIPE_POST_WAIT_MS);
                return waitForUiStableByDumpActions(ctx, "SWIPE");
            }
            if ("INPUT".equals(cmd.op)) {
                String text = cmd.args.get(0);
                // Keep parity with client.input_text(..., method=AUTO):
                // - ASCII text: ADB -> Clipboard
                // - Non-ASCII text: Clipboard -> ADB
                byte flags = 0x00;
                short targetX = 0;
                short targetY = 0;
                short delayMs = 0;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("text", text);
                trace.event("exec_input_start", ev);

                int[] methods = containsNonAscii(text)
                        ? new int[]{INPUT_METHOD_CLIPBOARD, INPUT_METHOD_ADB}
                        : new int[]{INPUT_METHOD_ADB, INPUT_METHOD_CLIPBOARD};
                int lastStatus = 0;
                int lastActualMethod = methods[0];
                int chosenMethod = methods[0];

                for (int i = 0; i < methods.length; i++) {
                    int method = methods[i];
                    byte[] resp = sendInputText(method, flags, targetX, targetY, delayMs, text);
                    int status = (resp != null && resp.length >= 1) ? (resp[0] & 0xFF) : 0;
                    int actualMethod = (resp != null && resp.length >= 2) ? (resp[1] & 0xFF) : method;
                    lastStatus = status;
                    lastActualMethod = actualMethod;

                    Map<String, Object> tryEv = new LinkedHashMap<>();
                    tryEv.put("task_id", ctx.taskId);
                    tryEv.put("try", i + 1);
                    tryEv.put("method", method);
                    tryEv.put("status", status);
                    tryEv.put("actual_method", actualMethod);
                    trace.event("exec_input_try", tryEv);

                    if (status == 1) {
                        chosenMethod = method;
                        break;
                    }
                }

                ev.put("method_auto", true);
                ev.put("chosen_method", chosenMethod);
                ev.put("actual_method", lastActualMethod);
                ev.put("status", lastStatus);
                trace.event("exec_input_result", ev);
                if (lastStatus != 1) {
                    return false;
                }
                return waitForUiStableByDumpActions(ctx, "INPUT");
            }
            if ("WAIT".equals(cmd.op)) {
                int ms = Integer.parseInt(cmd.args.get(0));
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("ms", ms);
                trace.event("exec_wait_start", ev);
                try {
                    Thread.sleep(Math.max(0, ms));
                } catch (InterruptedException ignored) {
                }
                trace.event("exec_wait_done", ev);
                return true;
            }
            if ("BACK".equals(cmd.op)) {
                // KEY_EVENT: keycode=4 (BACK), action=2 (click), meta=0.
                ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) 4);
                buf.put((byte) 2);
                buf.putInt(0);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                trace.event("exec_back_start", ev);
                execution.handleKeyEvent(buf.array());
                trace.event("exec_back_done", ev);
                return waitForUiStableByDumpActions(ctx, "BACK");
            }

            ctx.error = "unsupported_action_op:" + cmd.op;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("op", cmd.op);
            trace.event("exec_action_unsupported", ev);
            return false;
        } catch (Exception e) {
            ctx.error = "action_exec_error:" + cmd.op + ":" + e;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("op", cmd.op);
            ev.put("err", String.valueOf(e));
            trace.event("exec_action_error", ev);
            return false;
        }
    }

    private int resolveVisionMaxTurns(Context ctx) {
        String mode = resolveCurrentSubTaskMode(ctx);
        if ("loop".equals(mode)) {
            return VISION_MAX_TURNS_LOOP;
        }
        return VISION_MAX_TURNS_SINGLE;
    }

    private String validateVisionCommandArgs(Instruction cmd) {
        if (cmd == null) {
            return "empty command";
        }
        if ("TAP".equals(cmd.op)) {
            if (cmd.args.size() != 2) {
                return "TAP expects 2 args";
            }
            Integer x = parseStrictIntToken(cmd.args.get(0));
            Integer y = parseStrictIntToken(cmd.args.get(1));
            if (x == null || y == null) {
                return "TAP args must be digits-only integers";
            }
            if (x < 0 || x > 1000 || y < 0 || y > 1000) {
                return "TAP args out of range [0,1000]";
            }
            return "";
        }
        if ("SWIPE".equals(cmd.op)) {
            if (cmd.args.size() != 5) {
                return "SWIPE expects 5 args";
            }
            Integer x1 = parseStrictIntToken(cmd.args.get(0));
            Integer y1 = parseStrictIntToken(cmd.args.get(1));
            Integer x2 = parseStrictIntToken(cmd.args.get(2));
            Integer y2 = parseStrictIntToken(cmd.args.get(3));
            Integer dur = parseStrictIntToken(cmd.args.get(4));
            if (x1 == null || y1 == null || x2 == null || y2 == null || dur == null) {
                return "SWIPE args must be digits-only integers";
            }
            if (x1 < 0 || x1 > 1000 || y1 < 0 || y1 > 1000
                    || x2 < 0 || x2 > 1000 || y2 < 0 || y2 > 1000) {
                return "SWIPE coordinates out of range [0,1000]";
            }
            if (dur <= 0 || dur > 10000) {
                return "SWIPE duration_ms out of range (1..10000)";
            }
            return "";
        }
        if ("WAIT".equals(cmd.op)) {
            if (cmd.args.size() != 1) {
                return "WAIT expects 1 arg";
            }
            Integer ms = parseStrictIntToken(cmd.args.get(0));
            if (ms == null) {
                return "WAIT arg must be digits-only integer";
            }
            if (ms < 0 || ms > 120000) {
                return "WAIT ms out of range (0..120000)";
            }
            return "";
        }
        // INPUT/BACK/DONE/FAIL do not require strict numeric checks here.
        return "";
    }

    private Integer parseStrictIntToken(String token) {
        if (token == null) {
            return null;
        }
        String s = token.trim();
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveCurrentSubTaskMode(Context ctx) {
        String mode = "";
        if (ctx != null && ctx.currentSubTask != null && ctx.currentSubTask.mode != null) {
            mode = ctx.currentSubTask.mode.trim().toLowerCase(Locale.ROOT);
        }
        if (mode.isEmpty()) {
            mode = "single";
        }
        return mode;
    }

    private boolean waitForUiStableByDumpActions(Context ctx, String op) {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("task_id", ctx.taskId);
        begin.put("op", op);
        begin.put("pre_wait_ms", UI_SETTLE_PRE_WAIT_MS);
        begin.put("timeout_ms", UI_SETTLE_TIMEOUT_MS);
        begin.put("sample_ms", UI_SETTLE_SAMPLE_MS);
        begin.put("sim_threshold", UI_SETTLE_SIM_THRESHOLD);
        trace.event("vision_settle_begin", begin);

        try {
            // Always wait a bit before starting stability sampling.
            // This improves reliability on pages with inertial motion / delayed rendering.
            sleepQuiet(UI_SETTLE_PRE_WAIT_MS);
            DumpSnapshot prev = captureDumpActionsSnapshot();
            if (!prev.valid) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("op", op);
                ev.put("reason", "dump_unavailable");
                ev.put("fallback_wait_ms", UI_SETTLE_FALLBACK_MS);
                trace.event("vision_settle_fallback", ev);
                sleepQuiet(UI_SETTLE_FALLBACK_MS);
                return true;
            }

            int stableHits = 0;
            long deadline = System.currentTimeMillis() + UI_SETTLE_TIMEOUT_MS;
            int samples = 0;

            while (System.currentTimeMillis() < deadline) {
                sleepQuiet(UI_SETTLE_SAMPLE_MS);
                DumpSnapshot cur = captureDumpActionsSnapshot();
                if (!cur.valid) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("task_id", ctx.taskId);
                    ev.put("op", op);
                    ev.put("reason", "dump_unavailable_midway");
                    ev.put("fallback_wait_ms", UI_SETTLE_FALLBACK_MS);
                    trace.event("vision_settle_fallback", ev);
                    sleepQuiet(UI_SETTLE_FALLBACK_MS);
                    return true;
                }

                samples += 1;
                double sim = jaccard(prev.keys, cur.keys);
                if (sim >= UI_SETTLE_SIM_THRESHOLD) {
                    stableHits += 1;
                } else {
                    stableHits = 0;
                }

                Map<String, Object> sampleEv = new LinkedHashMap<>();
                sampleEv.put("task_id", ctx.taskId);
                sampleEv.put("op", op);
                sampleEv.put("sample", samples);
                sampleEv.put("prev_size", prev.keys.size());
                sampleEv.put("cur_size", cur.keys.size());
                sampleEv.put("similarity", sim);
                sampleEv.put("stable_hits", stableHits);
                trace.event("vision_settle_sample", sampleEv);

                if (stableHits >= UI_SETTLE_REQUIRED_HITS) {
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("task_id", ctx.taskId);
                    ok.put("op", op);
                    ok.put("samples", samples);
                    ok.put("similarity", sim);
                    trace.event("vision_settle_ready", ok);
                    return true;
                }
                prev = cur;
            }

            Map<String, Object> timeoutEv = new LinkedHashMap<>();
            timeoutEv.put("task_id", ctx.taskId);
            timeoutEv.put("op", op);
            timeoutEv.put("status", "timeout");
            trace.event("vision_settle_timeout", timeoutEv);
            return true;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("task_id", ctx.taskId);
            err.put("op", op);
            err.put("err", String.valueOf(e));
            trace.event("vision_settle_error", err);
            sleepQuiet(UI_SETTLE_FALLBACK_MS);
            return true;
        }
    }

    private DumpSnapshot captureDumpActionsSnapshot() {
        try {
            byte[] payload = perception != null ? perception.handleDumpActions(new byte[0]) : null;
            List<DumpActionsParser.ActionNode> nodes = DumpActionsParser.parse(payload);
            if (nodes == null || nodes.isEmpty()) {
                return DumpSnapshot.invalid();
            }
            Set<String> keys = new HashSet<>(nodes.size() * 2);
            for (DumpActionsParser.ActionNode n : nodes) {
                if (n == null || n.bounds == null) {
                    continue;
                }
                String cls = n.className != null ? n.className : "";
                String rid = n.resourceId != null ? n.resourceId : "";
                String key = (n.type & 0xFF) + "|" + cls + "|" + rid + "|"
                        + n.bounds.left + "," + n.bounds.top + "," + n.bounds.right + "," + n.bounds.bottom;
                keys.add(key);
            }
            if (keys.isEmpty()) {
                return DumpSnapshot.invalid();
            }
            return DumpSnapshot.valid(keys);
        } catch (Exception ignored) {
            return DumpSnapshot.invalid();
        }
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int inter = 0;
        for (String k : small) {
            if (large.contains(k)) {
                inter += 1;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union <= 0) {
            return 0.0d;
        }
        return ((double) inter) / ((double) union);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(Math.max(0L, ms));
        } catch (InterruptedException ignored) {
        }
    }

    private static class DumpSnapshot {
        final boolean valid;
        final Set<String> keys;

        DumpSnapshot(boolean valid, Set<String> keys) {
            this.valid = valid;
            this.keys = keys != null ? keys : new HashSet<String>();
        }

        static DumpSnapshot valid(Set<String> keys) {
            return new DumpSnapshot(true, keys);
        }

        static DumpSnapshot invalid() {
            return new DumpSnapshot(false, new HashSet<String>());
        }
    }

    private byte[] sendInputText(int method, byte flags, short targetX, short targetY, short delayMs, String text) {
        byte[] textBytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        short len = (short) textBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(10 + textBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) method);
        buf.put(flags);
        buf.putShort(targetX);
        buf.putShort(targetY);
        buf.putShort(delayMs);
        buf.putShort(len);
        buf.put(textBytes);
        return execution.handleInputText(buf.array());
    }

    private boolean containsNonAscii(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build full VISION_ACT prompt, mirroring Python PromptBuilder.build(..., VISION_ACT).
     */
    private String buildVisionPrompt(Context ctx) {
        SubTask st = ctx.currentSubTask;
        String globalRequest = ctx.rootUserTask != null ? ctx.rootUserTask : "";
        String objective = (st != null && st.description != null && !st.description.trim().isEmpty())
                ? st.description.trim()
                : (ctx.userTask != null ? ctx.userTask : "");
        String mode = (st != null && st.mode != null && !st.mode.trim().isEmpty())
                ? st.mode.trim().toLowerCase(Locale.ROOT)
                : "single";
        String completion = (st != null && st.successCriteria != null && !st.successCriteria.trim().isEmpty())
                ? st.successCriteria.trim()
                : "Complete the current objective with clear visible evidence.";

        StringBuilder sb = new StringBuilder();
        sb.append("You are a mobile UI agent. Focus on completing the current objective safely.\n\n");

        sb.append("[TASK_BLOCK]\n");
        sb.append("Global request: ").append(globalRequest).append("\n");
        sb.append("Current objective: ").append(objective).append("\n");
        sb.append("Mode: ").append(mode).append("\n");
        sb.append("Completion condition: ").append(completion).append("\n");
        sb.append("Guidance:\n");
        sb.append("- single: finish once evidence of completion is present.\n");
        sb.append("- loop: continue until no pending target remains.\n\n");

        sb.append("[RECENT_HISTORY_BLOCK]\n");
        String pendingInstruction = stringOrEmpty(ctx.pendingHistoryInstruction);
        String pendingExpected = stringOrEmpty(ctx.pendingHistoryExpected);
        String pendingCarryContext = stringOrEmpty(ctx.pendingHistoryCarryContext);
        boolean hasPending = !pendingInstruction.isEmpty() || !pendingExpected.isEmpty() || !pendingCarryContext.isEmpty();

        if (ctx.visionHistory.isEmpty() && !hasPending) {
            sb.append("Recent turns: none\n");
        } else {
            sb.append("Recent turns (oldest -> newest):\n");
            int rowIndex = 1;
            for (int i = 0; i < ctx.visionHistory.size(); i++) {
                Map<String, Object> row = ctx.visionHistory.get(i);
                sb.append(rowIndex++).append(") action: ").append(stringOrEmpty(row.get("instruction"))).append("\n");
                sb.append("   expected: ").append(stringOrEmpty(row.get("expected"))).append("\n");
                sb.append("   actual: ").append(stringOrEmpty(row.get("actual"))).append("\n");
                sb.append("   judge_prev: ").append(stringOrEmpty(row.get("judgement_prev"))).append("\n");
                sb.append("   judge_global: ").append(stringOrEmpty(row.get("judgement_global"))).append("\n");
                sb.append("   carry_context: ").append(stringOrEmpty(row.get("carry_context"))).append("\n");
            }
            if (hasPending) {
                sb.append(rowIndex).append(") action: ").append(pendingInstruction).append("\n");
                sb.append("   expected: ").append(pendingExpected).append("\n");
                sb.append("   actual: pending - observe actual result in this turn\n");
                sb.append("   judge_prev: pending - evaluate previous action outcome in this turn\n");
                sb.append("   judge_global: pending - evaluate global progress in this turn\n");
                sb.append("   carry_context: ").append(!pendingCarryContext.isEmpty() ? pendingCarryContext : "none").append("\n");
            }
        }
        sb.append("Recent-history guidance:\n");
        sb.append("- judge_prev checks only previous action vs previous expected result.\n");
        sb.append("- judge_global checks whether the overall objective is converging or drifting.\n");
        sb.append("- Do not repeat actions that already failed with no_effect/wrong_target.\n");
        sb.append("- If repeated no progress, change action strategy.\n\n");

        sb.append("[WORKING_MEMORY_BLOCK]\n");
        if (ctx.workingMemory.isEmpty()) {
            sb.append("Working memory: none\n");
        } else {
            sb.append("Durable facts from earlier turns (oldest -> newest):\n");
            for (int i = 0; i < ctx.workingMemory.size(); i++) {
                sb.append("- ").append(ctx.workingMemory.get(i)).append("\n");
            }
        }
        sb.append("Working-memory guidance:\n");
        sb.append("- Use this for stable facts needed across many turns (for example long text fragments, options, constraints).\n");
        sb.append("- Do not copy volatile UI details into working memory.\n");
        sb.append("- Write concise, reusable facts only.\n\n");

        sb.append("[ACTION_BLOCK]\n");
        sb.append("Available actions:\n");
        sb.append("- TAP x y\n");
        sb.append("- SWIPE x1 y1 x2 y2 duration_ms\n");
        sb.append("- INPUT \"text\"\n");
        sb.append("- WAIT ms\n");
        sb.append("- BACK\n");
        sb.append("- DONE summary_text\n");
        sb.append("Action semantics and parameters:\n");
        sb.append("1) TAP x y: tap one target point. x/y are coordinates.\n");
        sb.append("2) SWIPE x1 y1 x2 y2 duration_ms: drag to scroll/reveal more content.\n");
        sb.append("3) INPUT \"text\": input text into focused input field.\n");
        sb.append("4) WAIT ms: wait for UI/network transition.\n");
        sb.append("5) BACK: press Android back once.\n");
        sb.append("6) DONE summary_text: terminate current objective with concise summary.\n");
        sb.append("Coordinate convention (required for TAP/SWIPE):\n");
        sb.append("- Use normalized coordinates in a 1000x1000 logical plane.\n");
        sb.append("- Top-left is (0,0); bottom-right is (1000,1000).\n");
        sb.append("- All TAP/SWIPE coordinates should be integers in [0,1000].\n");
        sb.append("- Do NOT output device pixel coordinates.\n");
        sb.append("- Numeric tokens must be pure digits only (0-9), with no comma, no period, no unit, no parentheses.\n");
        sb.append("- Use ASCII half-width spaces to separate tokens.\n");
        sb.append("- Valid examples: TAP 373 947 ; SWIPE 500 820 500 220 450\n");
        sb.append("- Invalid examples: TAP 373, 947 ; TAP (373,947) ; SWIPE 500 820 500 220 450ms\n");
        sb.append("Constraints:\n");
        sb.append("- One turn chooses one primary action.\n");
        sb.append("- Do not BACK immediately just because one expected control is not visible.\n");
        sb.append("- If UI seems to be the expected page but target control is missing, first use SWIPE to explore more of the same page.\n");
        sb.append("- Before BACK in such cases, try 1-3 SWIPEs (typically upward to reveal lower content; if already at bottom, try downward).\n");
        sb.append("- Only BACK after swipe-based exploration still cannot find expected control.\n");
        sb.append("- If repeated no progress, change action type/intent.\n");
        sb.append("- Prefer safe, incremental exploration before failure.\n\n");

        sb.append("SWIPE timing examples:\n");
        sb.append("- Example A: expected \"My Courses\" after tapping \"More\", but not visible yet -> SWIPE up to scan page; do not BACK first.\n");
        sb.append("- Example B: entered settings-like page and expected section not on screen -> SWIPE to continue searching, then decide BACK only if still absent.\n\n");

        sb.append("[PASSED_CONTEXT_BLOCK]\n");
        List<String> requiredInputs = new ArrayList<>();
        if (ctx.currentSubTask != null && ctx.currentSubTask.inputs != null) {
            for (String raw : ctx.currentSubTask.inputs) {
                String key = raw != null ? raw.trim() : "";
                if (!key.isEmpty()) {
                    requiredInputs.add(key);
                }
            }
        }
        Map<String, String> passedContext = collectPassedContextForCurrentSubTask(ctx);
        if (requiredInputs.isEmpty()) {
            sb.append("Information from previous sub-tasks:\n- none (no input dependencies for this sub-task)\n");
        } else if (passedContext.isEmpty()) {
            sb.append("Information from previous sub-tasks:\n- none\n");
            sb.append("Requested input keys (currently unavailable):\n");
            for (String key : requiredInputs) {
                sb.append("- ").append(key).append("\n");
            }
        } else {
            sb.append("Information from previous sub-tasks:\n");
            for (Map.Entry<String, String> e : passedContext.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        sb.append("Usage guidance:\n");
        sb.append("- Use only items listed above when they are relevant to current objective.\n");
        sb.append("- Do not invent missing input values.\n\n");

        sb.append("[GUIDANCE_BLOCK]\n");
        if (!ctx.userPlaybook.isEmpty()) {
            sb.append("User-provided playbook (highest priority):\n");
            sb.append(ctx.userPlaybook).append("\n");
        } else {
            sb.append("User-provided playbook: none\n");
        }
        if (!ctx.taskMemoryHint.isEmpty()) {
            sb.append("Last successful task memory (reference only):\n");
            String memPkg = stringOrEmpty(ctx.taskMemoryHint.get("package_name"));
            String memPage = stringOrEmpty(ctx.taskMemoryHint.get("target_page"));
            String memSummary = stringOrEmpty(ctx.taskMemoryHint.get("summary_text"));
            if (!memPkg.isEmpty()) {
                sb.append("- package: ").append(memPkg).append("\n");
            }
            if (!memPage.isEmpty()) {
                sb.append("- target_page: ").append(memPage).append("\n");
            }
            if (!memSummary.isEmpty()) {
                sb.append("- summary: ").append(memSummary).append("\n");
            }
            Object routeObj = ctx.taskMemoryHint.get("route_trace");
            if (routeObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> route = (List<Object>) routeObj;
                int take = Math.min(6, route.size());
                for (int i = Math.max(0, route.size() - take); i < route.size(); i++) {
                    sb.append("- route_step: ").append(String.valueOf(route.get(i))).append("\n");
                }
            }
            Object cmdObj = ctx.taskMemoryHint.get("command_log");
            if (cmdObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> cmdList = (List<Object>) cmdObj;
                int take = Math.min(6, cmdList.size());
                for (int i = Math.max(0, cmdList.size() - take); i < cmdList.size(); i++) {
                    Object item = cmdList.get(i);
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) item;
                        String raw = stringOrEmpty(m.get("raw"));
                        if (!raw.isEmpty()) {
                            sb.append("- previous_command: ").append(raw).append("\n");
                        }
                    } else {
                        sb.append("- previous_command: ").append(String.valueOf(item)).append("\n");
                    }
                }
            }
        } else {
            sb.append("Last successful task memory: none\n");
        }
        sb.append("Guidance policy:\n");
        sb.append("- Prioritize user playbook over memory when conflict exists.\n");
        sb.append("- Use memory as heuristic reference, not hard constraint.\n");
        sb.append("- If current UI contradicts guidance, trust current UI and adapt.\n\n");

        sb.append("[SCREENSHOT_BLOCK]\n");
        sb.append("Screenshot: attached\n\n");

        sb.append("[OUTPUT_BLOCK]\n");
        sb.append("You MUST output exactly the following tags in this exact order:\n");
        sb.append("<Observing>...</Observing>\n");
        sb.append("<Ovserve_result>...</Ovserve_result>\n");
        sb.append("<Judging_prev>...</Judging_prev>\n");
        sb.append("<Judge_prev_result>...</Judge_prev_result>\n");
        sb.append("<Judging_global>...</Judging_global>\n");
        sb.append("<Judge_global_result>...</Judge_global_result>\n");
        sb.append("<Thinking>...</Thinking>\n");
        sb.append("<action>...</action>\n");
        sb.append("<expected>...</expected>\n");
        sb.append("<carry_context>...</carry_context>\n");
        sb.append("<memory_write>...</memory_write>\n");
        sb.append("<command>...</command>\n");
        sb.append("Field meaning:\n");
        sb.append("- <Observing>: describe what is currently visible and relevant to the current objective.\n");
        sb.append("- <Ovserve_result>: one-sentence summary of current page/result; used as actual outcome.\n");
        sb.append("- <Judging_prev>: evaluate previous action vs previous expected result; explain mismatch cause if any.\n");
        sb.append("- <Judge_prev_result>: one-sentence verdict for previous action (match/mismatch and why).\n");
        sb.append("- <Judging_global>: evaluate global progress toward current objective; detect drift/repetition/unfinished key requirements.\n");
        sb.append("- <Judge_global_result>: one-sentence verdict for global progress (on_track/stuck/drifting and why).\n");
        sb.append("- <Thinking>: analyze current situation and decide next strategy.\n");
        sb.append("- <action>: one short natural-language next action intent.\n");
        sb.append("- <expected>: expected result after next action.\n");
        sb.append("- <carry_context>: short-term notes for immediate next turns; use 'none' if not needed.\n");
        sb.append("- <memory_write>: one durable fact to store for later turns (long-context tasks); use 'none' if no new durable fact.\n");
        sb.append("- <command>: executable command string.\n");
        sb.append("Command format strictness:\n");
        sb.append("- <command> must contain exactly one command line and nothing else.\n");
        sb.append("- Allowed command signatures only:\n");
        sb.append("  TAP x y\n");
        sb.append("  SWIPE x1 y1 x2 y2 duration_ms\n");
        sb.append("  INPUT \"text\"\n");
        sb.append("  WAIT ms\n");
        sb.append("  BACK\n");
        sb.append("  DONE summary_text\n");
        sb.append("  FAIL reason_text\n");
        sb.append("- Before output, self-check that numeric args are digits-only tokens with no punctuation.\n");
        sb.append("Special first-turn rule:\n");
        sb.append("- If there is no previous action/history, output:\n");
        sb.append("  <Judging_prev>none</Judging_prev>\n");
        sb.append("  <Judge_prev_result>none</Judge_prev_result>\n");
        sb.append("- If global progress cannot be judged yet, output:\n");
        sb.append("  <Judging_global>none</Judging_global>\n");
        sb.append("  <Judge_global_result>none</Judge_global_result>\n");
        sb.append("Do not output markdown, code fences, JSON, or any extra text outside these tags.\n");

        return sb.toString();
    }

    /**
     * Map logical (VLM) coordinates to screen pixels using coord_probe.
     * Java port of Python _map_point_by_probe.
     */
    private int[] mapPointByProbe(Context ctx, double xf, double yf) {
        int w = parseIntLike(ctx.deviceInfo.get("width"), 0);
        int h = parseIntLike(ctx.deviceInfo.get("height"), 0);
        Map<String, Object> probe = ctx.coordProbe;
        if (w <= 1 || h <= 1) {
            int rx = (int) Math.round(xf);
            int ry = (int) Math.round(yf);
            return new int[]{rx, ry};
        }

        // Preferred mode: normalized coordinate space [0, 1000].
        if (xf >= 0.0d && xf <= 1000.0d && yf >= 0.0d && yf <= 1000.0d) {
            int rx = (int) Math.round((xf / 1000.0d) * (double) (w - 1));
            int ry = (int) Math.round((yf / 1000.0d) * (double) (h - 1));
            rx = Math.max(0, Math.min(w - 1, rx));
            ry = Math.max(0, Math.min(h - 1, ry));
            return new int[]{rx, ry};
        }

        // Compatibility fallback for legacy outputs:
        // keep old probe-based mapping if values are outside [0,1000].
        double maxX = toDouble(probe.get("max_x"));
        double maxY = toDouble(probe.get("max_y"));
        if (maxX <= 0.0d || maxY <= 0.0d) {
            int rx = (int) Math.round(xf);
            int ry = (int) Math.round(yf);
            rx = Math.max(0, Math.min(w - 1, rx));
            ry = Math.max(0, Math.min(h - 1, ry));
            return new int[]{rx, ry};
        }

        // If looks like absolute screen coordinates that exceed probe range, bypass scaling.
        if (xf >= 0.0 && xf <= (double) (w - 1) && yf >= 0.0 && yf <= (double) (h - 1)) {
            if (xf > maxX * 1.2 || yf > maxY * 1.2) {
                int rx = (int) Math.round(xf);
                int ry = (int) Math.round(yf);
                rx = Math.max(0, Math.min(w - 1, rx));
                ry = Math.max(0, Math.min(h - 1, ry));
                return new int[]{rx, ry};
            }
        }

        double xMin = toDouble(probe.get("x_min"));
        double xMax = toDouble(probe.get("x_max"));
        double yMin = toDouble(probe.get("y_min"));
        double yMax = toDouble(probe.get("y_max"));

        int rx;
        int ry;
        if (xMax > xMin && yMax > yMin) {
            double nx = (xf - xMin) / (xMax - xMin);
            double ny = (yf - yMin) / (yMax - yMin);
            rx = (int) Math.round(nx * (double) (w - 1));
            ry = (int) Math.round(ny * (double) (h - 1));
        } else {
            rx = (int) Math.round((xf / maxX) * (double) (w - 1));
            ry = (int) Math.round((yf / maxY) * (double) (h - 1));
        }

        rx = Math.max(0, Math.min(w - 1, rx));
        ry = Math.max(0, Math.min(h - 1, ry));
        return new int[]{rx, ry};
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}

