package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        // External semantic history (maintained by host, not by model memory).
        // Each row contains: instruction, expected, actual, judgement.
        public final List<Map<String, Object>> visionHistory = new ArrayList<>();
        public String pendingHistoryInstruction = "";
        public String pendingHistoryExpected = "";

        // Optional guidance injected by TaskManager.
        public String userPlaybook = "";
        public final Map<String, Object> taskMemoryHint = new LinkedHashMap<>();

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
        if (taskMemoryHint != null && !taskMemoryHint.isEmpty()) {
            ctx.taskMemoryHint.putAll(taskMemoryHint);
        }

        String initialPackageName = packageName != null ? packageName : "";
        ctx.selectedPackage = initialPackageName;

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
                ctx.currentSubTask = st;
                ctx.currentSubTaskIndex = idx;
                ctx.visionHistory.clear();
                ctx.pendingHistoryInstruction = "";
                ctx.pendingHistoryExpected = "";

                // Select package: caller-provided package has highest priority, then sub_task appHint.
                if (!initialPackageName.isEmpty()) {
                    ctx.selectedPackage = initialPackageName;
                } else {
                    String hint = st.appHint != null ? st.appHint.trim() : "";
                    ctx.selectedPackage = hint;
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
                trace.event("fsm_sub_task_begin", subBegin);

                boolean memoryFastPathUsed = false;
                boolean memoryFastPathFallbackUsed = false;
                String fastPathPkg = resolveMemoryFastPathPackage(ctx);
                if (!fastPathPkg.isEmpty()) {
                    ctx.selectedPackage = fastPathPkg;
                    boolean launchOk = launchAppForRouting(fastPathPkg);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("task_id", ctx.taskId);
                    m.put("index", idx);
                    m.put("sub_task_id", st.id);
                    m.put("package", fastPathPkg);
                    m.put("launch_ok", launchOk);
                    m.put("memory_summary", stringOrEmpty(ctx.taskMemoryHint.get("summary_text")));
                    trace.event("fsm_memory_fast_path", m);
                    if (launchOk) {
                        memoryFastPathUsed = true;
                        anyMemoryFastPathUsed = true;
                        // Align with routing launch settle to reduce early vision mismatch.
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException ignored) {
                        }
                        state = State.VISION_ACT;
                    } else {
                        state = State.APP_RESOLVE;
                    }
                } else {
                    state = State.APP_RESOLVE;
                }
                for (int step = 0; step < 30; step++) {
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
                    if (state == State.FAIL && memoryFastPathUsed && !memoryFastPathFallbackUsed) {
                        // Fallback once to full pipeline if fast path fails.
                        memoryFastPathFallbackUsed = true;
                        anyMemoryFastPathFallback = true;
                        memoryFastPathUsed = false;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("task_id", ctx.taskId);
                        m.put("index", idx);
                        m.put("sub_task_id", st.id);
                        m.put("reason", ctx.error);
                        trace.event("fsm_memory_fast_path_fallback", m);
                        ctx.error = "";
                        ctx.routeResult.clear();
                        ctx.targetPage = "";
                        ctx.visionTurns = 0;
                        ctx.lastCommand = "";
                        ctx.sameCommandStreak = 0;
                        ctx.sameActivityStreak = 0;
                        ctx.visionHistory.clear();
                        ctx.pendingHistoryInstruction = "";
                        ctx.pendingHistoryExpected = "";
                        state = State.APP_RESOLVE;
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

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", finalState == State.FINISH ? "success" : "failed");
            out.put("task_id", ctx.taskId);
            out.put("state", finalState.name());
            out.put("package_name", ctx.selectedPackage);
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
     * This is a lightweight text-only variant: we ask the LLM for a synthetic 4-corner
     * coordinate system and treat it as the model's native range. If VLM is added later,
     * this can be upgraded to use a real image probe.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runCoordProbe(Context ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        // For now always enabled; can be made configurable if needed.

        // We do a simple logical probe: assume the model uses a 0~999 square.
        // This mirrors the Python behavior enough for _map_point_by_probe semantics.
        try {
            String prompt = "Coordinate Calibration Task.\n"
                    + "You are calibrating a logical 2D coordinate space for a mobile screen.\n"
                    + "Return ONLY JSON with this exact schema:\n"
                    + "{\"tl\":[x,y],\"tr\":[x,y],\"bl\":[x,y],\"br\":[x,y]}\n"
                    + "Rules:\n"
                    + "1) Use a square coordinate range where top-left is near (0,0).\n"
                    + "2) top-right has max X and min Y; bottom-left has min X and max Y; bottom-right has max X and max Y.\n"
                    + "3) Use integers, no decimals.\n"
                    + "4) Do NOT add markdown or comments.\n";

            LlmConfig cfg = LlmConfig.loadDefault();
            String raw = llmClient.chatOnce(cfg, null, prompt);

            Map<String, Object> parsed = extractJsonObjectFromText(raw);
            if (parsed.isEmpty()) {
                return result;
            }
            Map<String, List<Number>> pts = new LinkedHashMap<>();
            for (String key : new String[]{"tl", "tr", "bl", "br"}) {
                Object v = parsed.get(key);
                if (!(v instanceof List)) {
                    return result;
                }
                List<?> arr = (List<?>) v;
                if (arr.size() < 2) {
                    return result;
                }
                Number x = toNumber(arr.get(0));
                Number y = toNumber(arr.get(1));
                if (x == null || y == null) {
                    return result;
                }
                List<Number> pair = new ArrayList<>(2);
                pair.add(x);
                pair.add(y);
                pts.put(key, pair);
            }

            double xMin = (pts.get("tl").get(0).doubleValue() + pts.get("bl").get(0).doubleValue()) / 2.0;
            double xMax = (pts.get("tr").get(0).doubleValue() + pts.get("br").get(0).doubleValue()) / 2.0;
            double yMin = (pts.get("tl").get(1).doubleValue() + pts.get("tr").get(1).doubleValue()) / 2.0;
            double yMax = (pts.get("bl").get(1).doubleValue() + pts.get("br").get(1).doubleValue()) / 2.0;
            double spanX = Math.max(1e-6, xMax - xMin);
            double spanY = Math.max(1e-6, yMax - yMin);

            double maxX = Math.max(
                    Math.max(pts.get("tl").get(0).doubleValue(), pts.get("tr").get(0).doubleValue()),
                    Math.max(pts.get("bl").get(0).doubleValue(), pts.get("br").get(0).doubleValue())
            );
            double maxY = Math.max(
                    Math.max(pts.get("tl").get(1).doubleValue(), pts.get("tr").get(1).doubleValue()),
                    Math.max(pts.get("bl").get(1).doubleValue(), pts.get("br").get(1).doubleValue())
            );

            Map<String, Object> points = new LinkedHashMap<>();
            for (Map.Entry<String, List<Number>> e : pts.entrySet()) {
                List<Number> p = e.getValue();
                List<Double> coord = new ArrayList<>(2);
                coord.add(p.get(0).doubleValue());
                coord.add(p.get(1).doubleValue());
                points.put(e.getKey(), coord);
            }

            result.put("max_x", round4(maxX));
            result.put("max_y", round4(maxY));
            result.put("x_min", round4(xMin));
            result.put("x_max", round4(xMax));
            result.put("y_min", round4(yMin));
            result.put("y_max", round4(yMax));
            result.put("span_x", round4(spanX));
            result.put("span_y", round4(spanY));
            result.put("points", points);
            Map<String, Object> probeSize = new LinkedHashMap<>();
            probeSize.put("width", 1000);
            probeSize.put("height", 1000);
            result.put("probe_size", probeSize);

            Map<String, Object> ev = new LinkedHashMap<>(result);
            ev.put("task_id", ctx.taskId);
            trace.event("coord_probe_done", ev);
            return result;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("reason", String.valueOf(e));
            trace.event("coord_probe_failed", ev);
            return result;
        }
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
                    String name = stringOrEmpty(m.get("name"));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("package", pkg);
                    row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                    out.add(row);
                    continue;
                }
                String pkg = stringOrEmpty(item);
                if (pkg.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        } else if (raw instanceof Map) {
            // Fallback: a single map, try to interpret as one app entry.
            Map<String, Object> m = (Map<String, Object>) raw;
            String pkg = stringOrEmpty(m.get("package"));
            if (!pkg.isEmpty()) {
                String name = stringOrEmpty(m.get("name"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
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

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("sub_task_key", key);
        ev.put("summary", s);
        trace.event("fsm_sub_task_summary", ev);
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
            String name = stringOrEmpty(c.get("name"));
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("name", name);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that decomposes a high-level Android user task into a small number of high-level sub_tasks.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"name\"}):\n");
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
        sb.append("Each sub_task MUST have fields: id, description, mode, app_hint, inputs, outputs, success_criteria.\n");
        sb.append("Additional rules:\n");
        sb.append("1) mode is either \"single\" or \"loop\".\n");
        sb.append("2) For loop sub_tasks, add loop_metadata with loop_unit, loop_target_condition, loop_termination_criteria, max_iterations.\n");
        sb.append("3) Use app_hint to prefer an installed app when obvious (e.g. Bilibili, Taobao, WeChat).\n");
        sb.append("4) If the task is simple and fits in one app, return a single sub_task.\n");
        sb.append("5) For multi-app tasks, break into multiple sub_tasks and wire outputs/inputs.\n");
        sb.append("6) sub_tasks count should usually be between 1 and 5, never dozens.\n");
        sb.append("7) Do NOT output markdown, code fences, or comments.\n");
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
            String name = stringOrEmpty(c.get("name"));
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("name", name);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that selects the best Android app to handle a task.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"name\"}):\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"package_name\":\"one_package_from_apps\"}\n");
        sb.append("Rules:\n");
        sb.append("1) package_name MUST be exactly one of the \"package\" values above.\n");
        sb.append("2) If the task clearly refers to a specific brand (e.g., Bilibili, Taobao), map it to that app.\n");
        sb.append("3) If ambiguous, choose the app that typical users would most likely use.\n");
        sb.append("4) Do NOT explain, do NOT add markdown, do NOT add comments.\n");
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
        String pattern = "<" + tag + ">\\s*([\\s\\S]*?)\\s*</" + tag + ">";
        Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return "";
        }
        return m.group(1).trim();
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
        if (state == State.VISION_ACT) {
            String action = stringOrEmpty(obj.get("action")).toUpperCase();
            if ("DONE".equals(action)) {
                return "DONE";
            }
            if ("BACK".equals(action)) {
                return "BACK";
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
            String name = stringOrEmpty(c.get("name")).toLowerCase();
            if (!task.isEmpty() && !name.isEmpty() && task.contains(name)) {
                return pkg;
            }
        }
        // Fallback: first candidate.
        Map<String, Object> first = ctx.appCandidates.get(0);
        return stringOrEmpty(first.get("package"));
    }

    private State runAppResolveState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.APP_RESOLVE.name());
        ev.put("selected_package", ctx.selectedPackage);
        trace.event("fsm_state_enter", ev);
        // 1) Caller-specified package: accept directly and skip LLM.
        if (ctx.selectedPackage != null && !ctx.selectedPackage.trim().isEmpty()) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
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
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", ctx.selectedPackage);
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

        // 2) Launch app once for both map and no-map modes.
        boolean launchOk = launchAppForRouting(pkg);
        Map<String, Object> launchEv = new LinkedHashMap<>();
        launchEv.put("task_id", ctx.taskId);
        launchEv.put("package", pkg);
        launchEv.put("clear_task", true);
        launchEv.put("result", launchOk ? "ok" : "fail");
        trace.event("fsm_routing_launch_app", launchEv);

        // No-map mode: nothing to tap, route_result just records launch.
        if (!hasMap || path == null || path.isEmpty()) {
            ctx.routeTrace.clear();
            ctx.routeResult.clear();
            ctx.routeResult.put("ok", launchOk);
            ctx.routeResult.put("mode", "no_map");
            ctx.routeResult.put("package", pkg);
            ctx.routeResult.put("steps", new ArrayList<Map<String, Object>>());
            if (!launchOk) {
                ctx.error = "routing_launch_failed";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_failed", fail);
                return State.FAIL;
            }
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
        int index = 0;

        for (RouteMap.Transition t : path) {
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
                    allOk = false;
                } else {
                    ResolvedNode node = resolver.resolve(locator);
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
            } catch (Exception e) {
                allOk = false;
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
            stepSummaries.add(step);

            if (!"ok".equals(result)) {
                break;
            }
            index++;

            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
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
            ctx.routeResult.put("reason", "step_failed");
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", pkg);
        done.put("from_page", fromPage);
        done.put("to_page", toPage);
        done.put("ok", allOk);
        done.put("steps", stepSummaries.size());
        trace.event("fsm_routing_done", done);

        if (!allOk) {
            ctx.error = "routing_step_failed";
            return State.FAIL;
        }
        return State.VISION_ACT;
    }

    /**
     * Launch app for routing, Java port of Python RouteThenActCortex._execute_route launch step.
     * Always uses CLEAR_TASK flag so each route starts from a clean task stack.
     */
    private boolean launchAppForRouting(String packageName) {
        try {
            // Best-effort stop before launch to avoid start failure when app is in a bad background state.
            stopAppBestEffortForRouting(packageName);
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
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_launch_err", ev);
            return false;
        }
    }

    private void stopAppBestEffortForRouting(String packageName) {
        try {
            byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            byte[] resp = execution != null ? execution.handleStopApp(buf.array()) : null;
            boolean ok = resp != null && resp.length > 0 && resp[0] == 0x01;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("result", ok ? "ok" : "fail");
            trace.event("fsm_routing_stop_app", ev);
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_stop_err", ev);
        }
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

        // Turn limit (mirror Python FSMConfig.max_vision_turns, default 20)
        if (ctx.visionTurns >= 20) {
            ctx.error = "vision_turn_limit";
            return State.FAIL;
        }
        ctx.visionTurns += 1;

        // Optional one-time settle on first vision turn.
        if (ctx.visionTurns == 1) {
            try {
                Thread.sleep(600); // screenshot_settle_sec ~0.6s
            } catch (InterruptedException ignored) {
            }
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
        final int maxVisionParseAttempts = 3;
        String raw = "";
        String normalized = "";
        String observing = "";
        String observeResult = "";
        String judging = "";
        String judgeResult = "";
        String thinking = "";
        String actionText = "";
        String expectedText = "";
        String commandText = "";
        String lastParseError = "";
        List<Instruction> commands = null;

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

        for (int attempt = 1; attempt <= maxVisionParseAttempts; attempt++) {
            String attemptPrompt = prompt;
            if (attempt > 1) {
                StringBuilder retryPrompt = new StringBuilder(prompt);
                retryPrompt.append("\n\n[FORMAT_RETRY]\n");
                retryPrompt.append("Previous response could not be parsed: ").append(lastParseError).append("\n");
                retryPrompt.append("Return exactly the required 8 tags in order, and provide one valid DSL command in <command>.");
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
                ctx.error = "planner_call_failed:VISION_ACT:" + e;
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
                judging = extractTagText(raw, "Judging");
                judgeResult = extractTagText(raw, "Judge_result");
                thinking = extractTagText(raw, "Thinking");
                actionText = extractTagText(raw, "action");
                expectedText = extractTagText(raw, "expected");
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
                break;
            } catch (InstructionError e) {
                lastParseError = e.getMessage();
                Map<String, Object> retryEv = new LinkedHashMap<>();
                retryEv.put("task_id", ctx.taskId);
                retryEv.put("state", State.VISION_ACT.name());
                retryEv.put("attempt", attempt);
                retryEv.put("max_attempts", maxVisionParseAttempts);
                retryEv.put("error", lastParseError);
                String retryRaw = (normalized != null && !normalized.isEmpty()) ? normalized : raw;
                if (retryRaw != null && retryRaw.length() > 1000) {
                    retryRaw = retryRaw.substring(0, 1000) + "...";
                }
                retryEv.put("raw", retryRaw != null ? retryRaw : "");
                trace.event("vision_parse_retry", retryEv);
            }
        }

        if (commands == null) {
            ctx.error = "vision_instruction_invalid_after_retries:" + lastParseError;
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("state", State.VISION_ACT.name());
            fail.put("error", lastParseError);
            trace.event("vision_instruction_invalid", fail);
            throw new IllegalStateException(ctx.error);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("Observing", observing);
        structured.put("Ovserve_result", observeResult);
        structured.put("Judging", judging);
        structured.put("Judge_result", judgeResult);
        structured.put("Thinking", thinking);
        structured.put("action", actionText);
        structured.put("expected", expectedText);
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
        if (!ctx.pendingHistoryInstruction.isEmpty() || !ctx.pendingHistoryExpected.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instruction", ctx.pendingHistoryInstruction);
            row.put("expected", ctx.pendingHistoryExpected);
            String actual = !observeResult.isEmpty() ? observeResult : observing;
            row.put("actual", actual);
            row.put("judgement", !judgeResult.isEmpty() ? judgeResult : "unknown");
            ctx.visionHistory.add(row);
            while (ctx.visionHistory.size() > 8) {
                ctx.visionHistory.remove(0);
            }

            Map<String, Object> hEv = new LinkedHashMap<>();
            hEv.put("task_id", ctx.taskId);
            hEv.put("row", row);
            hEv.put("history_size", ctx.visionHistory.size());
            trace.event("vision_history_append", hEv);
        }

        // Stash next pair for history matching in next turn.
        ctx.pendingHistoryInstruction = actionText != null ? actionText.trim() : "";
        ctx.pendingHistoryExpected = expectedText != null ? expectedText.trim() : "";

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

        for (Instruction cmd : commands) {
            if ("DONE".equals(cmd.op)) {
                String doneSummary = cmd.args.isEmpty() ? "" : String.join(" ", cmd.args).trim();
                if (doneSummary.isEmpty()) {
                    doneSummary = !observeResult.isEmpty() ? observeResult : "";
                }
                storeSubTaskSummary(ctx, doneSummary);
                return State.FINISH;
            }
            if ("FAIL".equals(cmd.op)) {
                String reason = cmd.args.isEmpty() ? "" : String.join(" ", cmd.args);
                ctx.error = "vision_fail:" + reason;
                return State.FAIL;
            }
            if (!execActionCommand(ctx, cmd)) {
                return State.FAIL;
            }
        }

        return State.VISION_ACT;
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
                return true;
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
                return true;
            }
            if ("INPUT".equals(cmd.op)) {
                String text = cmd.args.get(0);
                // Simple method: method=1, flags=0, targetX/targetY=0, delay=0.
                byte method = 0x01;
                byte flags = 0x00;
                short targetX = 0;
                short targetY = 0;
                short delayMs = 0;
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                short len = (short) textBytes.length;

                ByteBuffer buf = ByteBuffer.allocate(10 + textBytes.length).order(ByteOrder.BIG_ENDIAN);
                buf.put(method);
                buf.put(flags);
                buf.putShort(targetX);
                buf.putShort(targetY);
                buf.putShort(delayMs);
                buf.putShort(len);
                buf.put(textBytes);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("text", text);
                trace.event("exec_input_start", ev);
                byte[] resp = execution.handleInputText(buf.array());
                int status = (resp != null && resp.length >= 1) ? (resp[0] & 0xFF) : 0;
                ev.put("status", status);
                trace.event("exec_input_result", ev);
                return status != 0;
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
                return true;
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

        sb.append("[HISTORY_BLOCK]\n");
        if (ctx.visionHistory.isEmpty()) {
            sb.append("Recent turns: none\n");
        } else {
            sb.append("Recent turns (oldest -> newest):\n");
            for (int i = 0; i < ctx.visionHistory.size(); i++) {
                Map<String, Object> row = ctx.visionHistory.get(i);
                sb.append(i + 1).append(") action: ").append(stringOrEmpty(row.get("instruction"))).append("\n");
                sb.append("   expected: ").append(stringOrEmpty(row.get("expected"))).append("\n");
                sb.append("   actual: ").append(stringOrEmpty(row.get("actual"))).append("\n");
                sb.append("   judgement: ").append(stringOrEmpty(row.get("judgement"))).append("\n");
            }
        }
        sb.append("History guidance:\n");
        sb.append("- Do not repeat actions that already failed with no_effect/wrong_target.\n");
        sb.append("- If repeated no progress, change action strategy.\n\n");

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
        sb.append("Constraints:\n");
        sb.append("- One turn chooses one primary action.\n");
        sb.append("- If repeated no progress, change action type/intent.\n");
        sb.append("- Prefer safe, incremental exploration before failure.\n\n");

        sb.append("[PASSED_CONTEXT_BLOCK]\n");
        if (ctx.blackboard.isEmpty()) {
            sb.append("Information from previous sub-tasks:\n- none\n");
        } else {
            sb.append("Information from previous sub-tasks:\n");
            int n = 0;
            for (Map.Entry<String, Object> e : ctx.blackboard.entrySet()) {
                if (n >= 8) break;
                String val = stringOrEmpty(e.getValue());
                if (val.isEmpty()) continue;
                sb.append("- ").append(val).append("\n");
                n++;
            }
            if (n == 0) {
                sb.append("- none\n");
            }
        }
        sb.append("Usage guidance:\n");
        sb.append("- Use relevant items when useful for this objective.\n");
        sb.append("- Ignore irrelevant items safely.\n\n");

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
        sb.append("<Judging>...</Judging>\n");
        sb.append("<Judge_result>...</Judge_result>\n");
        sb.append("<Thinking>...</Thinking>\n");
        sb.append("<action>...</action>\n");
        sb.append("<expected>...</expected>\n");
        sb.append("<command>...</command>\n");
        sb.append("Field meaning:\n");
        sb.append("- <Observing>: describe what is currently visible and relevant to the current objective.\n");
        sb.append("- <Ovserve_result>: one-sentence summary of current page/result; used as actual outcome.\n");
        sb.append("- <Judging>: evaluate previous action vs previous expected result; explain mismatch cause if any.\n");
        sb.append("- <Judge_result>: one-sentence judgement for previous action.\n");
        sb.append("- <Thinking>: analyze current situation and decide next strategy.\n");
        sb.append("- <action>: one short natural-language next action intent.\n");
        sb.append("- <expected>: expected result after next action.\n");
        sb.append("- <command>: executable command string.\n");
        sb.append("Special first-turn rule:\n");
        sb.append("- If there is no previous action/history, output:\n");
        sb.append("  <Judging>none</Judging>\n");
        sb.append("  <Judge_result>none</Judge_result>\n");
        sb.append("Do not output markdown, code fences, JSON, or any extra text outside these tags.\n");

        return sb.toString();
    }

    /**
     * Map logical (VLM) coordinates to screen pixels using coord_probe.
     * Java port of Python _map_point_by_probe.
     */
    private int[] mapPointByProbe(Context ctx, double xf, double yf) {
        int w = (int) (ctx.deviceInfo.getOrDefault("width", 0));
        int h = (int) (ctx.deviceInfo.getOrDefault("height", 0));
        Map<String, Object> probe = ctx.coordProbe;
        double maxX = toDouble(probe.get("max_x"));
        double maxY = toDouble(probe.get("max_y"));

        if (w <= 1 || h <= 1 || maxX <= 0.0 || maxY <= 0.0) {
            int rx = (int) Math.round(xf);
            int ry = (int) Math.round(yf);
            return new int[]{rx, ry};
        }

        // If looks like absolute screen coordinates that exceed model range, bypass scaling.
        if (xf >= 0.0 && xf <= (double) (w - 1) && yf >= 0.0 && yf <= (double) (h - 1)) {
            if (xf > maxX * 1.2 || yf > maxY * 1.2) {
                int rx = (int) Math.round(xf);
                int ry = (int) Math.round(yf);
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

