package com.lxb.server.cortex;

import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.notify.NotificationTriggerModule;
import com.lxb.server.cortex.route.RouteExecutionService;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapAssembler;
import com.lxb.server.cortex.taskmap.TaskRouteKey;
import com.lxb.server.cortex.taskmap.TaskRouteRecord;
import com.lxb.server.cortex.taskmap.TaskMapStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Cortex bootstrap facade (Milestone 1.x):
 * - burn map (gzip json) to device local filesystem
 * - resolve locator using staged matching (self -> parent_rid -> bounds_hint)
 * - tap resolved node
 * - trace pull (jsonl ring buffer)
 * - route-only execution: given target_page, infer home + BFS + tap transitions
 *
 * Full end-side Cortex planner/LLM will be added in later milestones.
 */
public class CortexFacade {

    private static final String TAG = "[LXB][Cortex]";
    private final ExecutionEngine executionEngine;
    private final PerceptionEngine perceptionEngine;

    private final MapManager mapManager;
    private final TraceLogger trace;
    private final LocatorResolver locatorResolver;
    private final LlmClient llmClient;
    private final CortexFsmEngine fsmEngine;
    private final CortexTaskManager taskManager;
    private final RouteExecutionService routeExecutionService;
    private final NotificationTriggerModule notificationTriggerModule;
    private final TaskMapStore taskMapStore;

    public CortexFacade(PerceptionEngine perceptionEngine, ExecutionEngine executionEngine) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.mapManager = new MapManager();
        this.trace = new TraceLogger(2000);
        this.locatorResolver = new LocatorResolver(perceptionEngine, trace);
        this.llmClient = new LlmClient();
        this.taskMapStore = new TaskMapStore();
        this.fsmEngine = new CortexFsmEngine(perceptionEngine, executionEngine, mapManager, trace, taskMapStore);
        this.taskManager = new CortexTaskManager(fsmEngine, taskMapStore);
        this.routeExecutionService = new RouteExecutionService(
                executionEngine,
                perceptionEngine,
                locatorResolver,
                trace
        );
        this.notificationTriggerModule = new NotificationTriggerModule(taskManager, trace);
    }

    public byte[] handleMapSetGz(byte[] payload) {
        // payload: package_len[2B] + package[UTF-8] + gzipped_map_json[...]
        try {
            if (payload.length < 2) {
                return err("payload too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            int pkgLen = buf.getShort() & 0xFFFF;
            if (pkgLen <= 0 || pkgLen > 256 || payload.length < 2 + pkgLen + 8) {
                return err("invalid package_len=" + pkgLen);
            }
            byte[] pkgBytes = new byte[pkgLen];
            buf.get(pkgBytes);
            String pkg = new String(pkgBytes, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) {
                return err("empty package");
            }

            byte[] gz = new byte[buf.remaining()];
            buf.get(gz);
            String json = gunzipToString(gz);
            mapManager.setLaneMap("burn", pkg, json);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", pkg);
            ev.put("bytes_gz", gz.length);
            ev.put("bytes_json", json.getBytes(StandardCharsets.UTF_8).length);
            ev.put("source", "burn");
            ev.put("lane_path", mapManager.getLaneMapFile("burn", pkg).getAbsolutePath());
            trace.event("map_set", ev);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("package", pkg);
            out.put("path", mapManager.getLaneMapFile("burn", pkg).getAbsolutePath());
            out.put("lane_path", mapManager.getLaneMapFile("burn", pkg).getAbsolutePath());
            out.put("source", "burn");
            out.put("size", json.length());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("map_set_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleMapGetInfo(byte[] payload) {
        // payload: package[UTF-8]
        try {
            String pkg = new String(payload, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) return err("empty package");
            String mapSource = getConfiguredMapSourceOrDefault();
            File f = mapManager.getMapFileForSource(pkg, mapSource);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", f.exists());
            out.put("package", pkg);
            out.put("source", mapSource);
            out.put("path", f.getAbsolutePath());
            out.put("exists", f.exists());
            out.put("bytes", f.exists() ? f.length() : 0);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            return err(String.valueOf(e));
        }
    }

    public byte[] handleResolveLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("bounds", node.bounds.toList());
            out.put("candidates", node.candidateCount);
            out.put("picked_stage", node.pickedStage);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("resolve_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTapLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            int cx = (node.bounds.left + node.bounds.right) / 2;
            int cy = (node.bounds.top + node.bounds.bottom) / 2;

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("x", cx);
            ev.put("y", cy);
            ev.put("stage", node.pickedStage);
            ev.put("candidates", node.candidateCount);
            trace.event("tap", ev);

            // Reuse existing tap handler (binary protocol expects >HH).
            ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            tapPayload.putShort((short) cx);
            tapPayload.putShort((short) cy);
            byte[] resp = executionEngine.handleTap(tapPayload.array());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("tap_resp_len", resp != null ? resp.length : 0);
            out.put("bounds", node.bounds.toList());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("tap_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTracePull(byte[] payload) {
        try {
            int limit = 80;
            String mode = "tail";
            long beforeSeq = 0L;
            long afterSeq = 0L;

            if (payload != null && payload.length > 0) {
                String text = new String(payload, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty() && text.charAt(0) == '{') {
                    Map<String, Object> req = Json.parseObject(text);
                    mode = stringOrEmpty(req.get("mode"));
                    if (mode.isEmpty()) mode = "tail";
                    limit = Math.max(1, Math.min(200, toInt(req.get("limit"), 80)));
                    beforeSeq = toLong(req.get("before_seq"), 0L);
                    afterSeq = toLong(req.get("after_seq"), 0L);
                } else if (payload.length >= 2) {
                    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                    limit = Math.max(1, Math.min(1000, buf.getShort() & 0xFFFF));
                }
            }

            TraceLogger.PullPage page;
            if ("before".equalsIgnoreCase(mode)) {
                page = trace.pullBefore(beforeSeq, limit);
            } else if ("after".equalsIgnoreCase(mode)) {
                page = trace.pullAfter(afterSeq, limit);
            } else {
                page = trace.pullTail(limit);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("mode", mode);
            out.put("limit", limit);
            out.put("has_more_before", page.hasMoreBefore);
            out.put("has_more_after", page.hasMoreAfter);
            out.put("oldest_seq", page.oldestSeq);
            out.put("newest_seq", page.newestSeq);
            java.util.List<Object> items = new java.util.ArrayList<>();
            for (TraceLogger.PullItem item : page.items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", item.seq);
                row.put("line", item.line);
                items.add(row);
            }
            out.put("items", items);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            return err(String.valueOf(e));
        }
    }

    /**
     * High-level Cortex entry: use end-side LLM to plan target_page from RouteMap,
     * then execute routing to that page. This is the first step of migrating the
     * Python Route-Then-Act / Cortex FSM to Java.
     *
     * Payload JSON UTF-8:
     * {
     *   "package": "tv.danmaku.bili",
     *   "user_task": "open search page",
     *   "start_page": "bilibili_home",   // optional override of inferred home
     *   "max_steps": 16                  // optional route upper bound
     * }
     *
     * Response JSON:
     * {
     *   "ok": true/false,
     *   "package": "...",
     *   "target_page": "...",
     *   "route": { ... same as handleRouteRun(...) result ... },
     *   "llm_used_fallback": true/false,
     *   "reason": "error message if any"
     * }
     */
    public byte[] handleCortexMapPlanRoute(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String pkg = stringOrEmpty(req.get("package"));
            String userTask = stringOrEmpty(req.get("user_task"));
            String startPageOverride = stringOrEmpty(req.get("start_page"));
            int maxSteps = toInt(req.get("max_steps"), 0);

            if (pkg.isEmpty()) return err("package is required");
            if (userTask.isEmpty()) return err("user_task is required");

            File mapFile = getConfiguredMapFile(pkg);
            if (!mapFile.exists()) {
                return err("map file not found for package=" + pkg + ", path=" + mapFile.getAbsolutePath());
            }
            RouteMap routeMap = RouteMap.loadFromFile(mapFile);
            if (routeMap.pages.isEmpty()) {
                return err("route map has no pages");
            }
            if (routeMap.transitions.isEmpty()) {
                return err("route map has no transitions");
            }
            if (maxSteps <= 0) {
                maxSteps = 64;
            }

            // Trace: task begin
            Map<String, Object> beginEv = new LinkedHashMap<>();
            beginEv.put("package", pkg);
            beginEv.put("user_task", userTask);
            trace.event("cortex_begin", beginEv);

            // Load LLM config and run MapPromptPlanner
            LlmConfig llmConfig = LlmConfig.loadDefault();
            MapPromptPlanner planner = new MapPromptPlanner(llmClient);
            MapPromptPlanner.PlanResult planResult = planner.plan(llmConfig, userTask, routeMap);

            String targetPage = planResult.targetPage != null ? planResult.targetPage.trim() : "";
            if (targetPage.isEmpty()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("package", pkg);
                ev.put("reason", "empty_target_page_after_planning");
                trace.event("cortex_plan_fail", ev);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("package", pkg);
                out.put("target_page", "");
                out.put("reason", "empty_target_page_after_planning");
                return ok(Json.stringify(out));
            }

            Map<String, Object> planEv = new LinkedHashMap<>();
            planEv.put("package", pkg);
            planEv.put("target_page", targetPage);
            planEv.put("used_fallback", planResult.usedFallback);
            trace.event("cortex_plan_done", planEv);

            // Compute route path from map (same semantics as handleRouteRun target_page branch).
            String effectiveFrom;
            java.util.List<RouteMap.Transition> path;
            if (!startPageOverride.isEmpty()) {
                effectiveFrom = startPageOverride;
                path = routeMap.findPath(effectiveFrom, targetPage, maxSteps);
            } else {
                effectiveFrom = routeMap.inferHomePage();
                if (effectiveFrom == null || effectiveFrom.isEmpty()) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", false);
                    out.put("package", pkg);
                    out.put("target_page", targetPage);
                    out.put("reason", "cannot_infer_home_page");
                    return ok(Json.stringify(out));
                }
                path = routeMap.findPathFromHome(targetPage, maxSteps);
            }

            if (path == null) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("package", pkg);
                ev.put("from_page", effectiveFrom);
                ev.put("to_page", targetPage);
                trace.event("cortex_route_no_path", ev);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("package", pkg);
                out.put("target_page", targetPage);
                out.put("reason", "no_path");
                return ok(Json.stringify(out));
            }

            Map<String, Object> routeResult = routeExecutionService.executeRoute(pkg, effectiveFrom, targetPage, path);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", routeResult.get("ok"));
            out.put("package", pkg);
            out.put("target_page", targetPage);
            out.put("route", routeResult);
            out.put("llm_used_fallback", planResult.usedFallback);
            if (Boolean.FALSE.equals(routeResult.get("ok"))) {
                Object reason = routeResult.get("reason");
                if (reason != null) {
                    out.put("reason", String.valueOf(reason));
                }
            }

            trace.event("cortex_end", out);

            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * High-level Cortex FSM entry point.
     *
     * Payload JSON UTF-8:
     * {
     *   "user_task": "open xxx",
     *   "package": "tv.danmaku.bili",    // optional, if omitted INIT+APP_RESOLVE will be extended later
     *   "map_path": "/data/local/tmp/nav_map.json", // optional, reserved for future
     *   "start_page": "bilibili_home",   // optional
     *   "trace_mode": "push",            // optional: "push" to enable UDP trace streaming
     *   "trace_udp_port": 23456,         // optional: Android app local UDP port for trace push
     *   "record_enabled": false          // optional: task-level recording switch
     * }
     *
     * For now this wires only INIT -> APP_RESOLVE, and requires package to be provided.
     */
    public byte[] handleCortexFsmRun(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String userTask = stringOrEmpty(req.get("user_task"));
            String pkg = stringOrEmpty(req.get("package"));
            String mapPath = stringOrEmpty(req.get("map_path"));
            String startPage = stringOrEmpty(req.get("start_page"));
            String traceMode = stringOrEmpty(req.get("trace_mode"));
            int traceUdpPort = toInt(req.get("trace_udp_port"), 0);
            String userPlaybook = stringOrEmpty(req.get("user_playbook"));
            boolean recordEnabled = toBool(req.get("record_enabled"), false);
            Object useMapObj = req.get("use_map");
            Boolean useMapOverride = useMapObj != null ? Boolean.valueOf(toBool(useMapObj, true)) : null;

            if (userTask.isEmpty()) {
                return err("user_task is required");
            }

            String taskId = taskManager.submitTask(
                    userTask,
                    pkg,
                    mapPath.isEmpty() ? null : mapPath,
                    startPage.isEmpty() ? null : startPage,
                    traceMode.isEmpty() ? null : traceMode,
                    traceUdpPort > 0 ? traceUdpPort : null,
                    userPlaybook.isEmpty() ? null : userPlaybook,
                    Boolean.valueOf(recordEnabled),
                    useMapOverride
            );
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("task_id", taskId);
            out.put("status", "submitted");
            out.put("user_task", userTask);
            if (!pkg.isEmpty()) {
                out.put("package", pkg);
            }
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_fsm_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Task status query entry point.
     *
     * Payload JSON UTF-8:
     * {
     *   "task_id": "<uuid>"
     * }
     *
     * Response JSON:
     * {
     *   "found": true/false,
     *   "task_id": "...",
     *   "user_task": "...",
     *   "state": "PENDING|RUNNING|COMPLETED|FAILED",
     *   "created_at": 0,
     *   "started_at": 0,
     *   "finished_at": 0,
     *   "final_state": "...",
     *   "reason": "..."
     * }
     */
    public byte[] handleCortexTaskStatus(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String taskId = stringOrEmpty(req.get("task_id"));

            Map<String, Object> status = taskManager.getTaskStatus(taskId);
            return ok(Json.stringify(status));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_task_status_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Request cancellation of the currently running Cortex FSM task.
     *
     * Payload JSON UTF-8 (optional):
     * {
     *   "reason": "user_cancel"
     * }
     *
     * For now this applies to the single worker thread only and does not
     * require a task_id. It is safe to call even if no task is running.
     */
    public byte[] handleCortexFsmCancel(byte[] payload) {
        try {
            taskManager.requestCancel();
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("source", "remote");
            ev.put("reason", "user_cancel");
            trace.event("fsm_cancel_requested", ev);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("reason", "cancel_requested");
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("fsm_cancel_request_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * List recent Cortex FSM tasks.
     *
     * Payload JSON UTF-8 (optional):
     * {
     *   "limit": 50
     * }
     *
     * Response JSON:
     * {
     *   "ok": true,
     *   "tasks": [
     *     {
     *       "task_id": "...",
     *       "user_task": "...",
     *       "state": "PENDING|RUNNING|COMPLETED|FAILED|CANCELLED",
     *       "created_at": 0,
     *       "started_at": 0,
     *       "finished_at": 0,
     *       "final_state": "...",
     *       "reason": "...",
     *       "package_name": "...",
     *       "target_page": "..."
     *     },
     *     ...
     *   ]
     * }
     */
    public byte[] handleCortexTaskList(byte[] payload) {
        try {
            int limit = 50;
            if (payload != null && payload.length > 0) {
                String s = new String(payload, StandardCharsets.UTF_8);
                Map<String, Object> req = Json.parseObject(s);
                limit = toInt(req.get("limit"), 50);
            }
            java.util.List<Map<String, Object>> tasks = taskManager.listRecentTasks(limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("tasks", tasks);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_task_list_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Add a scheduled FSM task.
     *
     * Payload JSON:
     * {
     *   "name": "optional",
     *   "user_task": "...",              // required
     *   "package": "optional",
     *   "map_path": "optional",
     *   "start_page": "optional",
     *   "trace_mode": "push|",
     *   "trace_udp_port": 23456,
     *   "run_at": 1711111111111,         // required epoch ms, phone system time
     *   "repeat_mode": "once|daily|weekly", // optional, default once
     *   "repeat_weekdays": 31,           // optional, weekly mask (Mon bit0 ... Sun bit6)
     *   "user_playbook": "optional",
     *   "record_enabled": false          // optional
     * }
     */
    public byte[] handleCortexScheduleAdd(byte[] payload) {
        try {
            String s = payload != null ? new String(payload, StandardCharsets.UTF_8) : "{}";
            Map<String, Object> req = Json.parseObject(s);

            String name = stringOrEmpty(req.get("name"));
            String userTask = stringOrEmpty(req.get("user_task"));
            String pkg = stringOrEmpty(req.get("package"));
            String mapPath = stringOrEmpty(req.get("map_path"));
            String startPage = stringOrEmpty(req.get("start_page"));
            String traceMode = stringOrEmpty(req.get("trace_mode"));
            int traceUdpPort = toInt(req.get("trace_udp_port"), 0);
            long runAt = toLong(req.get("run_at"), 0L);
            if (runAt <= 0L) {
                // Backward compatibility with old field name.
                runAt = toLong(req.get("start_at"), 0L);
            }
            String repeatMode = stringOrEmpty(req.get("repeat_mode"));
            int repeatWeekdays = toInt(req.get("repeat_weekdays"), 0);
            // Backward compatibility for old payloads.
            if (repeatMode.isEmpty()) {
                boolean repeatDaily = false;
                Object repeatObj = req.get("repeat_daily");
                if (repeatObj instanceof Boolean) {
                    repeatDaily = ((Boolean) repeatObj).booleanValue();
                } else if (repeatObj != null) {
                    repeatDaily = "true".equalsIgnoreCase(String.valueOf(repeatObj));
                }
                repeatMode = repeatDaily ? "daily" : "once";
            }
            String userPlaybook = stringOrEmpty(req.get("user_playbook"));
            boolean recordEnabled = toBool(req.get("record_enabled"), false);
            String taskMapMode = stringOrEmpty(req.get("task_map_mode"));

            Map<String, Object> schedule = taskManager.addScheduledTask(
                    name,
                    userTask,
                    pkg,
                    mapPath.isEmpty() ? null : mapPath,
                    startPage.isEmpty() ? null : startPage,
                    traceMode.isEmpty() ? null : traceMode,
                    traceUdpPort > 0 ? traceUdpPort : null,
                    runAt,
                    repeatMode,
                    repeatWeekdays,
                    userPlaybook,
                    recordEnabled,
                    taskMapMode
            );

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("schedule", schedule);
            trace.event("cortex_schedule_add", schedule);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_schedule_add_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * List schedules.
     *
     * Payload JSON (optional):
     * {
     *   "limit": 50
     * }
     */
    public byte[] handleCortexScheduleList(byte[] payload) {
        try {
            int limit = 50;
            if (payload != null && payload.length > 0) {
                String s = new String(payload, StandardCharsets.UTF_8);
                Map<String, Object> req = Json.parseObject(s);
                limit = toInt(req.get("limit"), 50);
            }
            java.util.List<Map<String, Object>> schedules = taskManager.listScheduledTasks(limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("schedules", schedules);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_schedule_list_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Remove schedule by id.
     *
     * Payload JSON:
     * {
     *   "schedule_id": "..."
     * }
     */
    public byte[] handleCortexScheduleRemove(byte[] payload) {
        try {
            String s = payload != null ? new String(payload, StandardCharsets.UTF_8) : "{}";
            Map<String, Object> req = Json.parseObject(s);
            String scheduleId = stringOrEmpty(req.get("schedule_id"));
            if (scheduleId.isEmpty()) {
                return err("schedule_id is required");
            }
            boolean removed = taskManager.removeScheduledTask(scheduleId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("removed", removed);
            out.put("schedule_id", scheduleId);
            if (removed) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("schedule_id", scheduleId);
                trace.event("cortex_schedule_remove", ev);
            }
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_schedule_remove_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Update schedule by id.
     *
     * Payload JSON:
     * {
     *   "schedule_id": "...",            // required
     *   "name": "optional",
     *   "user_task": "...",              // required
     *   "package": "optional",
     *   "map_path": "optional",
     *   "start_page": "optional",
     *   "trace_mode": "push|",
     *   "trace_udp_port": 23456,
     *   "run_at": 1711111111111,         // required epoch ms
     *   "repeat_mode": "once|daily|weekly",
     *   "repeat_weekdays": 31,           // Mon bit0 ... Sun bit6
     *   "user_playbook": "optional",
     *   "enabled": true,                 // optional, default keep current / true
     *   "record_enabled": false          // optional
     * }
     */
    public byte[] handleCortexScheduleUpdate(byte[] payload) {
        try {
            String s = payload != null ? new String(payload, StandardCharsets.UTF_8) : "{}";
            Map<String, Object> req = Json.parseObject(s);

            String scheduleId = stringOrEmpty(req.get("schedule_id"));
            if (scheduleId.isEmpty()) {
                return err("schedule_id is required");
            }
            String name = stringOrEmpty(req.get("name"));
            String userTask = stringOrEmpty(req.get("user_task"));
            String pkg = stringOrEmpty(req.get("package"));
            String mapPath = stringOrEmpty(req.get("map_path"));
            String startPage = stringOrEmpty(req.get("start_page"));
            String traceMode = stringOrEmpty(req.get("trace_mode"));
            int traceUdpPort = toInt(req.get("trace_udp_port"), 0);
            long runAt = toLong(req.get("run_at"), 0L);
            if (runAt <= 0L) {
                runAt = toLong(req.get("start_at"), 0L);
            }
            String repeatMode = stringOrEmpty(req.get("repeat_mode"));
            int repeatWeekdays = toInt(req.get("repeat_weekdays"), 0);
            if (repeatMode.isEmpty()) {
                boolean repeatDaily = false;
                Object repeatObj = req.get("repeat_daily");
                if (repeatObj instanceof Boolean) {
                    repeatDaily = ((Boolean) repeatObj).booleanValue();
                } else if (repeatObj != null) {
                    repeatDaily = "true".equalsIgnoreCase(String.valueOf(repeatObj));
                }
                repeatMode = repeatDaily ? "daily" : "once";
            }
            String userPlaybook = stringOrEmpty(req.get("user_playbook"));
            Boolean enabled = req.containsKey("enabled") ? Boolean.valueOf(toBool(req.get("enabled"), true)) : null;
            boolean recordEnabled = toBool(req.get("record_enabled"), false);
            String taskMapMode = stringOrEmpty(req.get("task_map_mode"));

            Map<String, Object> schedule = taskManager.updateScheduledTask(
                    scheduleId,
                    name,
                    userTask,
                    pkg,
                    mapPath.isEmpty() ? null : mapPath,
                    startPage.isEmpty() ? null : startPage,
                    traceMode.isEmpty() ? null : traceMode,
                    traceUdpPort > 0 ? traceUdpPort : null,
                    runAt,
                    repeatMode,
                    repeatWeekdays,
                    userPlaybook,
                    enabled,
                    recordEnabled,
                    taskMapMode
            );
            if (schedule == null) {
                return err("schedule not found: " + scheduleId);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("updated", true);
            out.put("schedule", schedule);
            trace.event("cortex_schedule_update", schedule);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_schedule_update_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Notification trigger module API.
     *
     * Payload JSON:
     * {
     *   "action": "status|list_rules|upsert_rule|remove_rule|list_logs|control",
     *   "rule": { ... },         // required for upsert_rule
     *   "rule_id": "id",         // required for remove_rule
     *   "limit": 100,            // optional for list_logs
     *   "mode": "start|stop|reload|status" // for control
     * }
     */
    public byte[] handleCortexNotify(byte[] payload) {
        try {
            String s = payload != null && payload.length > 0
                    ? new String(payload, StandardCharsets.UTF_8)
                    : "{}";
            Map<String, Object> req = Json.parseObject(s);
            String action = stringOrEmpty(req.get("action")).toLowerCase();
            if (action.isEmpty()) {
                action = "status";
            }

            Map<String, Object> out = new LinkedHashMap<>();
            if ("status".equals(action)) {
                out.putAll(notificationTriggerModule.getStatus());
                out.put("ok", true);
            } else if ("list_rules".equals(action)) {
                out.put("ok", true);
                out.put("rules", notificationTriggerModule.listRules());
            } else if ("upsert_rule".equals(action)) {
                Object ruleObj = req.get("rule");
                if (!(ruleObj instanceof Map)) {
                    return err("rule is required");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> rule = (Map<String, Object>) ruleObj;
                out.putAll(notificationTriggerModule.upsertRule(rule));
            } else if ("remove_rule".equals(action)) {
                String ruleId = stringOrEmpty(req.get("rule_id"));
                out.putAll(notificationTriggerModule.removeRule(ruleId));
            } else if ("list_logs".equals(action)) {
                int limit = toInt(req.get("limit"), 100);
                out.put("ok", true);
                out.put("logs", notificationTriggerModule.listLogs(limit));
            } else if ("control".equals(action)) {
                String mode = stringOrEmpty(req.get("mode"));
                out.putAll(notificationTriggerModule.control(mode));
            } else {
                return err("unsupported notify action: " + action);
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("action", action);
            ev.put("ok", toBool(out.get("ok"), false));
            trace.event("cortex_notify_api", ev);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_notify_api_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleCortexTaskMap(byte[] payload) {
        try {
            String s = payload != null && payload.length > 0
                    ? new String(payload, StandardCharsets.UTF_8)
                    : "{}";
            Map<String, Object> req = Json.parseObject(s);
            String action = stringOrEmpty(req.get("action")).toLowerCase();
            if (action.isEmpty()) {
                action = "get";
            }
            String taskKeyHash = stringOrEmpty(req.get("route_id"));
            String taskId = stringOrEmpty(req.get("task_id"));
            String source = stringOrEmpty(req.get("source"));
            String sourceId = stringOrEmpty(req.get("source_id"));
            String packageName = stringOrEmpty(req.get("package_name"));
            String userTask = stringOrEmpty(req.get("user_task"));
            String userPlaybook = stringOrEmpty(req.get("user_playbook"));
            String mode = stringOrEmpty(req.get("mode"));
            boolean includeDetails = toBool(req.get("include_details"), false);

            Map<String, Object> out;
            if ("get".equals(action)) {
                out = taskManager.getTaskMapStatus(taskKeyHash, taskId, source, sourceId, packageName, userTask, userPlaybook, mode, includeDetails);
            } else if ("delete".equals(action)) {
                out = taskManager.deleteTaskMap(taskKeyHash, taskId, source, sourceId, packageName, userTask, userPlaybook, mode);
                if (toBool(out.get("ok"), false)) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("route_id", out.get("route_id"));
            ev.put("route_id", out.get("route_id"));
                    trace.event("task_map_deleted", ev);
                }
            } else if ("save_manual".equals(action)) {
                java.util.List<String> deleteActionIds = new java.util.ArrayList<String>();
                Object listObj = req.get("delete_action_ids");
                boolean finishAfterReplay = toBool(req.get("finish_after_replay"), false);
                if (listObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> src = (java.util.List<Object>) listObj;
                    for (Object item : src) {
                        String id = stringOrEmpty(item);
                        if (!id.isEmpty()) {
                            deleteActionIds.add(id);
                        }
                    }
                }
                out = taskManager.saveManualTaskMap(taskKeyHash, taskId, deleteActionIds, finishAfterReplay);
            } else if ("export_portable".equals(action)) {
                out = taskManager.exportPortableTaskMap(
                        taskKeyHash,
                        taskId,
                        source,
                        sourceId,
                        packageName,
                        userTask,
                        userPlaybook,
                        mode
                );
            } else if ("import_portable".equals(action)) {
                String targetTaskKeyHash = stringOrEmpty(req.get("target_route_id"));
                String targetPackageName = stringOrEmpty(req.get("target_package_name"));
                String bundleJson = stringOrEmpty(req.get("bundle_json"));
                out = taskManager.importPortableTaskMap(targetTaskKeyHash, targetPackageName, bundleJson);
            } else if ("set_mode".equals(action)) {
                out = taskManager.setTaskMapMode(source, sourceId, mode, taskId);
            } else {
                return err("unsupported task_map action: " + action);
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("action", action);
            ev.put("ok", toBool(out.get("ok"), false));
            ev.put("route_id", out.get("route_id"));
            ev.put("route_id", out.get("route_id"));
            trace.event("cortex_task_map_api", ev);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_task_map_api_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Route-only 闂佸湱鐟抽崱鈺傛杸闂佹寧绋掗懝楣冩偄閳ь剙霉?map transitions闂佹寧绋戞總鏃傚垝?home闂佹寧绋戦悧濠囧垂閵娾晛鍙婇柟顖嗗懐顢?start_page闂佹寧绋戦ˇ鎶芥儗妤ｅ啯鍋ㄩ柛妤冨仜閻?target_page闂?     *
     * payload: JSON UTF-8:
     * {
     *   "package": "tv.danmaku.bili",
     *   "target_page": "search_page__n_xxx",   // 闂佺儵鏅╅崰妤呮偉閿濆棎浜滅痪鏉款槺缁€鍕叓閸パ勫櫣闁挎繄鍋ら弫?     *   "start_page": "bilibili_home",         // 闂佸憡鐟崹鍫曞焵椤掆偓椤р偓缂佽鲸绻堥幃鎯р枎閹存梹鏅為梺鍝勫暙婢у酣顢旈浣虹懝鐟滃海鈧?map 闂佽浜介崝宥夊蓟?home
     *   "max_steps": 16                        // 闂佸憡鐟崹鍫曞焵椤掆偓椤р偓缂? 闂佺懓鐡ㄩ悧婊冾啅闁秵鍎戝ù锝咁潟閳ь剙鍟扮划鍫熸姜閺夊簱鏋忛梺娲绘娇閸旀垹鍒掗婊勫闁靛牆瀚悷鎰版⒒?     * }
     *
     * 闂佺绻掗崢褔顢欓幇鏉跨睄鐟滃繑鏅跺Δ鍐╁闁告劦鍘惧Σ鎼佹煥濞戞瑨澹樻い鈹洤鍑犳繝濠傚瀵捇鏌熺紒妯哄缂?target_page 婵炶揪绲藉Λ娆忥耿?from_page/to_page闂佹寧绋戦懟顖炲垂椤栨粍灏庨柣妤€鐗婇埢鏃傗偓?from->to 闁荤姳璀﹂崹鎶藉极闁秴违?     *
     * 闁哄鏅滈弻銊ッ?JSON:
     * {
     *   "ok": true/false,
     *   "package": "...",
     *   "from_page": "...",
     *   "to_page": "...",
     *   "steps": [ { index, from, to, description, picked_stage, picked_bounds, result, reason }, ... ],
     *   "reason": "step_failed|no_path" // 婵炲濮撮幊搴ㄥΦ閹寸姵瀚婚柕澶涢檮椤ρ囨倵濞戞顏勶耿?     * }
     */
        public byte[] handleRouteRun(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String pkg = stringOrEmpty(req.get("package"));
            String targetPage = stringOrEmpty(req.get("target_page"));
            String startPageOverride = stringOrEmpty(req.get("start_page"));
            // Legacy compatibility: allow from_page/to_page when target_page is empty
            String fromPageLegacy = stringOrEmpty(req.get("from_page"));
            String toPageLegacy = stringOrEmpty(req.get("to_page"));
            int maxSteps = toInt(req.get("max_steps"), 0);

            if (pkg.isEmpty()) return err("package is required");
            boolean useLegacyFromTo = targetPage.isEmpty() && !fromPageLegacy.isEmpty() && !toPageLegacy.isEmpty();
            if (!useLegacyFromTo && targetPage.isEmpty()) {
                return err("target_page is required");
            }

            File mapFile = getConfiguredMapFile(pkg);
            if (!mapFile.exists()) {
                return err("map file not found for package=" + pkg + ", path=" + mapFile.getAbsolutePath());
            }

            RouteMap routeMap = RouteMap.loadFromFile(mapFile);
            if (routeMap.transitions.isEmpty()) {
                return err("route map has no transitions");
            }

            if (maxSteps <= 0) {
                maxSteps = 64; // default upper bound to avoid pathological long paths
            }

            String effectiveFrom;
            String effectiveTo;
            java.util.List<RouteMap.Transition> path;

            if (useLegacyFromTo) {
                effectiveFrom = fromPageLegacy;
                effectiveTo = toPageLegacy;
                trace.event("route_begin", RouteExecutionService.buildRouteEvent(pkg, effectiveFrom, effectiveTo, "begin"));
                path = routeMap.findPath(effectiveFrom, effectiveTo, maxSteps);
            } else {
                // New semantics: only target_page is required; start_page is optional override of inferred home.
                String start = !startPageOverride.isEmpty() ? startPageOverride : routeMap.inferHomePage();
                if (start == null || start.isEmpty()) {
                    return err("cannot infer home page from map");
                }
                effectiveFrom = start;
                effectiveTo = targetPage;
                trace.event("route_begin", RouteExecutionService.buildRouteEvent(pkg, effectiveFrom, effectiveTo, "begin"));
                path = routeMap.findPathFromHome(effectiveTo, maxSteps);
            }

            if (path == null) {
                Map<String, Object> ev = RouteExecutionService.buildRouteEvent(pkg, effectiveFrom, effectiveTo, "no_path");
                trace.event("route_no_path", ev);
                Map<String, Object> outFail = new LinkedHashMap<>();
                outFail.put("ok", false);
                outFail.put("package", pkg);
                outFail.put("from_page", effectiveFrom);
                outFail.put("to_page", effectiveTo);
                outFail.put("steps", new java.util.ArrayList<>());
                outFail.put("reason", "no_path");
                return ok(Json.stringify(outFail));
            }

            Map<String, Object> out = routeExecutionService.executeRoute(pkg, effectiveFrom, effectiveTo, path);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("route_err", ev);
            return err(String.valueOf(e));
        }
    }

    private static String gunzipToString(byte[] gz) throws Exception {
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = gis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        gis.close();
        return baos.toString("UTF-8");
    }

    private static byte[] ok(String s) {
        // response is plain UTF-8 (JSON or JSONL)
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] err(String msg) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", false);
        o.put("err", msg);
        return Json.stringify(o).getBytes(StandardCharsets.UTF_8);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String firstNonEmpty(String a, String b) {
        String av = stringOrEmpty(a);
        return !av.isEmpty() ? av : stringOrEmpty(b);
    }

    private static int toInt(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static long toLong(Object o, long defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean toBool(Object o, boolean defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return defaultValue;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }


    private String getConfiguredMapSourceOrDefault() {
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            String source = cfg.mapSource != null ? cfg.mapSource.trim() : "";
            if ("candidate".equalsIgnoreCase(source) || "burn".equalsIgnoreCase(source)) {
                return source.toLowerCase();
            }
            return "stable";
        } catch (Exception ignored) {
            return "stable";
        }
    }

    private File getConfiguredMapFile(String pkg) {
        return mapManager.getMapFileForSource(pkg, getConfiguredMapSourceOrDefault());
    }

}
