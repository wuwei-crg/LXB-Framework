package com.example.lxb_ignition.core

import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.NotificationTriggerRuleSummary
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskMapDetail
import com.example.lxb_ignition.model.TaskMapSegmentSnapshot
import com.example.lxb_ignition.model.TaskMapSnapshot
import com.example.lxb_ignition.model.TaskMapStepSnapshot
import com.example.lxb_ignition.model.TaskRouteActionSnapshot
import com.example.lxb_ignition.model.TaskRouteRecordSnapshot
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TraceEntry
import com.example.lxb_ignition.model.TraceMetaItem
import com.example.lxb_ignition.model.TracePage
import org.json.JSONArray
import org.json.JSONObject

data class TaskSubmitParsed(
    val message: String,
    val taskId: String
)

data class SystemControlParsed(
    val ok: Boolean,
    val detail: String
)

object CoreApiParser {

    fun parseTaskSubmit(payload: ByteArray): TaskSubmitParsed {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null) {
            return TaskSubmitParsed("Invalid response: ${text.take(160)}", "")
        }
        val ok = obj.optBoolean("ok", false)
        val status = obj.optString("status", "")
        val taskId = obj.optString("task_id", "")
        return if (ok && status == "submitted" && taskId.isNotEmpty()) {
            TaskSubmitParsed("Task submitted: $taskId", taskId)
        } else {
            TaskSubmitParsed("Task submission failed: ${text.take(200)}", "")
        }
    }

    fun parseInstalledApps(payload: ByteArray): Pair<String, List<AppPackageOption>> {
        if (payload.isEmpty() || payload.size < 3) {
            return Pair("Installed app snapshot failed: empty/short response.", emptyList())
        }
        val status = payload[0].toInt() and 0xFF
        val jsonLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val available = payload.size - 3
        val safeLen = when {
            jsonLen <= 0 -> available
            jsonLen > available -> available
            else -> jsonLen
        }
        val text = String(payload, 3, safeLen, Charsets.UTF_8)
        if (status != 1) {
            return Pair("Installed app snapshot failed: status=$status.", emptyList())
        }
        val arr = runCatching { JSONArray(text) }.getOrNull()
            ?: return Pair("Installed app snapshot failed: ${text.take(160)}", emptyList())
        val dedup = linkedMapOf<String, AppPackageOption>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val pkg = row.optString("package", "").trim()
            if (pkg.isEmpty()) continue
            val label = row.optString("label", row.optString("name", "")).trim()
            val prev = dedup[pkg]
            if (prev == null || (prev.label.isBlank() && label.isNotBlank())) {
                dedup[pkg] = AppPackageOption(packageName = pkg, label = label)
            }
        }
        val items = dedup.values.sortedWith(
            compareBy<AppPackageOption>({ if (it.label.isBlank()) 1 else 0 }, { it.label.lowercase() }, { it.packageName })
        )
        return Pair("Installed app snapshot refreshed: ${items.size} items.", items)
    }

    fun parseTaskList(payload: ByteArray): Pair<String, List<TaskSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid task list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Task list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val dedup = linkedMapOf<String, TaskSummary>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("task_id", "")
            if (id.isEmpty()) continue
            val summary = TaskSummary(
                taskId = id,
                userTask = t.optString("user_task", ""),
                state = t.optString("state", ""),
                finalState = t.optString("final_state", ""),
                reason = t.optString("reason", ""),
                taskSummary = t.optString("task_summary", ""),
                packageName = t.optString("package_name", ""),
                targetPage = t.optString("target_page", ""),
                source = t.optString("source", ""),
                scheduleId = t.optString("schedule_id", ""),
                routeId = t.optString("route_id", ""),
                taskMapMode = t.optString("task_map_mode", ""),
                hasTaskMap = t.optBoolean("has_task_map", false),
                memoryApplied = t.optBoolean("memory_applied", false),
                recordEnabled = t.optBoolean("record_enabled", false),
                recordFile = t.optString("record_file", ""),
                createdAt = t.optLong("created_at", 0L),
                finishedAt = t.optLong("finished_at", 0L)
            )
            dedup[id] = summary
        }
        val items = dedup.values
            .sortedByDescending { if (it.createdAt > 0L) it.createdAt else it.finishedAt }
        return Pair("Task list refreshed: ${items.size} items.", items)
    }

    fun parseScheduleList(
        payload: ByteArray,
        repeatDaily: String,
        repeatOnce: String
    ): Pair<String, List<ScheduleSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid schedule list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Schedule list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("schedules") ?: JSONArray()
        val dedup = linkedMapOf<String, ScheduleSummary>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("schedule_id", "")
            if (id.isEmpty()) continue
            val summary = ScheduleSummary(
                scheduleId = id,
                name = s.optString("name", ""),
                userTask = s.optString("user_task", ""),
                packageName = s.optString("package", ""),
                startPage = s.optString("start_page", ""),
                recordEnabled = s.optBoolean("record_enabled", false),
                taskMapMode = s.optString("task_map_mode", "off"),
                runAtMs = s.optLong("run_at", 0L),
                repeatMode = s.optString(
                    "repeat_mode",
                    if (s.optBoolean("repeat_daily", false)) repeatDaily else repeatOnce
                ),
                repeatWeekdays = s.optInt("repeat_weekdays", 0),
                nextRunAt = s.optLong("next_run_at", 0L),
                lastTriggeredAt = s.optLong("last_triggered_at", 0L),
                triggerCount = s.optLong("trigger_count", 0L),
                enabled = s.optBoolean("enabled", true),
                createdAt = s.optLong("created_at", 0L),
                userPlaybook = s.optString("user_playbook", "")
            )
            dedup[id] = summary
        }
        val items = dedup.values.sortedByDescending { it.nextRunAt }
        return Pair("Schedule list refreshed: ${items.size} items.", items)
    }

    fun parseScheduleAdd(payload: ByteArray): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return "Add schedule failed: ${text.take(220)}"
        }
        val scheduleObj = obj.optJSONObject("schedule")
        val sid = scheduleObj?.optString("schedule_id", "") ?: ""
        return if (sid.isNotEmpty()) "Schedule added: $sid" else "Schedule added."
    }

    fun parseScheduleUpdate(payload: ByteArray, scheduleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Update schedule failed: ${text.take(220)}"
        } else {
            "Schedule updated: $scheduleId"
        }
    }

    fun parseScheduleRemove(payload: ByteArray, scheduleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Remove schedule failed: ${text.take(220)}"
        } else if (obj.optBoolean("removed", false)) {
            "Schedule removed: $scheduleId"
        } else {
            "Schedule not found: $scheduleId"
        }
    }

    fun parseNotifyRuleList(payload: ByteArray): Pair<String, List<NotificationTriggerRuleSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid notify rule list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Notify rule list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("rules") ?: JSONArray()
        val dedup = linkedMapOf<String, NotificationTriggerRuleSummary>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optString("id", "")
            if (id.isBlank()) continue
            val action = r.optJSONObject("action") ?: JSONObject()
            val summary = NotificationTriggerRuleSummary(
                id = id,
                name = r.optString("name", ""),
                enabled = r.optBoolean("enabled", true),
                priority = r.optInt("priority", 100),
                packageMode = r.optString("package_mode", "any"),
                packageList = jsonArrayToStringList(r.optJSONArray("package_list")),
                textMode = r.optString("text_mode", "contains"),
                titlePattern = r.optString("title_pattern", ""),
                bodyPattern = r.optString("body_pattern", ""),
                llmConditionEnabled = r.optBoolean("llm_condition_enabled", false),
                llmCondition = r.optString("llm_condition", ""),
                llmYesToken = r.optString("llm_yes_token", "yes"),
                llmNoToken = r.optString("llm_no_token", "no"),
                llmTimeoutMs = r.optLong("llm_timeout_ms", 60000L),
                taskRewriteEnabled = r.optBoolean("task_rewrite_enabled", false),
                taskRewriteInstruction = r.optString("task_rewrite_instruction", ""),
                taskRewriteTimeoutMs = r.optLong("task_rewrite_timeout_ms", 60000L),
                taskRewriteFailPolicy = r.optString("task_rewrite_fail_policy", "fallback_raw_task"),
                cooldownMs = r.optLong("cooldown_ms", 60_000L),
                activeTimeStart = r.optString("active_time_start", ""),
                activeTimeEnd = r.optString("active_time_end", ""),
                stopAfterMatched = r.optBoolean("stop_after_matched", true),
                actionType = action.optString("type", "run_task"),
                actionUserTask = action.optString("user_task", ""),
                actionPackage = action.optString("package", ""),
                actionUserPlaybook = action.optString("user_playbook", ""),
                actionRecordEnabled = action.optBoolean("record_enabled", false),
                actionTaskMapMode = action.optString("task_map_mode", "off"),
                actionUseMap = if (action.has("use_map")) action.optBoolean("use_map", true) else null
            )
            dedup[id] = summary
        }
        val items = dedup.values.sortedWith(
            compareByDescending<NotificationTriggerRuleSummary> { it.priority }
                .thenBy { it.id }
        )
        return Pair("Notify rules refreshed: ${items.size} items.", items)
    }

    fun parseNotifyRuleUpsert(payload: ByteArray): Pair<String, String> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Upsert notify rule failed: ${text.take(220)}", "")
        if (!obj.optBoolean("ok", false)) {
            return Pair("Upsert notify rule failed: ${text.take(220)}", "")
        }
        val updated = obj.optBoolean("updated", false)
        val ruleObj = obj.optJSONObject("rule")
        val id = ruleObj?.optString("id", "").orEmpty()
        val msg = if (updated) {
            if (id.isNotBlank()) "Notify rule updated: $id" else "Notify rule updated."
        } else {
            if (id.isNotBlank()) "Notify rule added: $id" else "Notify rule added."
        }
        return Pair(msg, id)
    }

    fun parseNotifyRuleRemove(payload: ByteArray, ruleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Remove notify rule failed: ${text.take(220)}"
        } else if (obj.optBoolean("removed", false)) {
            "Notify rule removed: $ruleId"
        } else {
            "Notify rule not found: $ruleId"
        }
    }

    fun parseTraceLines(payload: ByteArray): Pair<String, TracePage> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj != null && obj.has("ok")) {
            if (!obj.optBoolean("ok", false)) {
                return Pair("Trace pull failed: ${obj.optString("err", text).take(220)}", emptyTracePage())
            }
            return parseTracePageObject(obj)
        }
        val items = text
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, line -> parseTraceEntry(line, (index + 1).toLong()) }
            .toList()
        val oldestSeq = items.firstOrNull()?.seq ?: 0L
        val newestSeq = items.lastOrNull()?.seq ?: 0L
        return Pair(
            "Trace refreshed: ${items.size} lines.",
            TracePage(
                entries = items,
                hasMoreBefore = false,
                hasMoreAfter = false,
                oldestSeq = oldestSeq,
                newestSeq = newestSeq
            )
        )
    }

    fun parseTaskMapDetail(payload: ByteArray): Pair<String, TaskMapDetail?> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid task route detail response: ${text.take(180)}", null)
        if (!obj.optBoolean("ok", false)) {
            return Pair("Task route detail query failed: ${text.take(220)}", null)
        }
        val detail = TaskMapDetail(
            routeId = obj.optString("route_id", ""),
            mode = obj.optString("mode", ""),
            source = obj.optString("source", ""),
            sourceId = obj.optString("source_id", ""),
            userTask = obj.optString("user_task", ""),
            packageName = obj.optString("package_name", ""),
            hasMap = obj.optBoolean("has_map", false),
            hasLatestSuccessRecord = obj.optBoolean("has_latest_success_record", false),
            hasLatestAttemptRecord = obj.optBoolean("has_latest_attempt_record", false),
            taskMap = obj.optJSONObject("task_map")?.let(::parseTaskMapSnapshot),
            latestSuccessRecord = obj.optJSONObject("latest_success_record")?.let(::parseTaskRouteRecordSnapshot),
            latestAttemptRecord = obj.optJSONObject("latest_attempt_record")?.let(::parseTaskRouteRecordSnapshot)
        )
        return Pair("Task route details loaded.", detail)
    }

    private fun parseTracePageObject(obj: JSONObject): Pair<String, TracePage> {
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<TraceEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val seq = row.optLong("seq", 0L)
            val line = row.optString("line", "").trim()
            if (line.isEmpty()) continue
            items.add(parseTraceEntry(line, seq))
        }
        val page = TracePage(
            entries = items,
            hasMoreBefore = obj.optBoolean("has_more_before", false),
            hasMoreAfter = obj.optBoolean("has_more_after", false),
            oldestSeq = obj.optLong("oldest_seq", items.firstOrNull()?.seq ?: 0L),
            newestSeq = obj.optLong("newest_seq", items.lastOrNull()?.seq ?: 0L)
        )
        return Pair("Trace refreshed: ${items.size} lines.", page)
    }

    private fun emptyTracePage(): TracePage {
        return TracePage(
            entries = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            oldestSeq = 0L,
            newestSeq = 0L
        )
    }

    private fun parseTaskMapSnapshot(obj: JSONObject): TaskMapSnapshot {
        val segments = mutableListOf<TaskMapSegmentSnapshot>()
        val arr = obj.optJSONArray("segments") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val segObj = arr.optJSONObject(i) ?: continue
            val steps = mutableListOf<TaskMapStepSnapshot>()
            val stepArr = segObj.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until stepArr.length()) {
                val stepObj = stepArr.optJSONObject(j) ?: continue
                steps += TaskMapStepSnapshot(
                    stepId = stepObj.optString("step_id", ""),
                    sourceActionId = stepObj.optString("source_action_id", ""),
                    op = stepObj.optString("op", ""),
                    args = jsonArrayToStringList(stepObj.optJSONArray("args")),
                    fallbackPoint = normalizeText(stepObj.opt("fallback_point")?.toString().orEmpty(), 240),
                    semanticNote = stepObj.optString("semantic_note", ""),
                    expected = stepObj.optString("expected", ""),
                    locatorFields = jsonObjectToMetaItems(stepObj.optJSONObject("locator")),
                    containerProbeFields = jsonObjectToMetaItems(stepObj.optJSONObject("container_probe")),
                    semanticDescriptorFields = jsonObjectToMetaItems(stepObj.optJSONObject("semantic_descriptor")),
                    tapPoint = normalizeText(stepObj.opt("tap_point")?.toString().orEmpty(), 240),
                    swipeFields = jsonObjectToMetaItems(stepObj.optJSONObject("swipe")),
                    portableKind = stepObj.optString("portable_kind", ""),
                    adaptationStatus = stepObj.optString("adaptation_status", ""),
                    adaptationError = stepObj.optString("adaptation_error", ""),
                    materializedFromStepId = stepObj.optString("materialized_from_step_id", ""),
                    materializedAtMs = stepObj.optLong("materialized_at_ms", 0L)
                )
            }
            segments += TaskMapSegmentSnapshot(
                segmentId = segObj.optString("segment_id", ""),
                subTaskId = segObj.optString("sub_task_id", ""),
                subTaskIndex = segObj.optInt("sub_task_index", 0),
                subTaskDescription = segObj.optString("sub_task_description", ""),
                successCriteria = segObj.optString("success_criteria", ""),
                packageName = segObj.optString("package_name", ""),
                packageLabel = segObj.optString("package_label", ""),
                inputs = jsonArrayToStringList(segObj.optJSONArray("inputs")),
                outputs = jsonArrayToStringList(segObj.optJSONArray("outputs")),
                steps = steps
            )
        }
        return TaskMapSnapshot(
            schema = obj.optString("schema", ""),
            mode = obj.optString("mode", ""),
            packageName = obj.optString("package_name", ""),
            packageLabel = obj.optString("package_label", ""),
            createdFromTaskId = obj.optString("created_from_task_id", ""),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            lastReplayStatus = obj.optString("last_replay_status", ""),
            finishAfterReplay = obj.optBoolean("finish_after_replay", false),
            segments = segments
        )
    }

    private fun parseTaskRouteRecordSnapshot(obj: JSONObject): TaskRouteRecordSnapshot {
        val actions = mutableListOf<TaskRouteActionSnapshot>()
        val arr = obj.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val actionObj = arr.optJSONObject(i) ?: continue
            actions += TaskRouteActionSnapshot(
                actionId = actionObj.optString("action_id", ""),
                subTaskId = actionObj.optString("sub_task_id", ""),
                turn = actionObj.optInt("turn", 0),
                op = actionObj.optString("op", ""),
                args = jsonArrayToStringList(actionObj.optJSONArray("args")),
                rawCommand = actionObj.optString("raw_command", ""),
                execResult = actionObj.optString("exec_result", ""),
                execError = actionObj.optString("exec_error", ""),
                createdPageSemantics = actionObj.optString("created_page_semantics", ""),
                locatorFields = jsonObjectToMetaItems(actionObj.optJSONObject("locator")),
                visionFields = jsonObjectToMetaItems(actionObj.optJSONObject("vision"))
            )
        }
        return TaskRouteRecordSnapshot(
            schema = obj.optString("schema", ""),
            taskId = obj.optString("task_id", ""),
            rootTask = obj.optString("root_task", ""),
            packageName = obj.optString("package_name", ""),
            packageLabel = obj.optString("package_label", ""),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            status = obj.optString("status", ""),
            finalState = obj.optString("final_state", ""),
            reason = obj.optString("reason", ""),
            actions = actions
        )
    }

    private fun jsonObjectToMetaItems(obj: JSONObject?): List<TraceMetaItem> {
        if (obj == null) return emptyList()
        val out = mutableListOf<TraceMetaItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key) ?: continue
            val rendered = when (value) {
                is JSONObject -> normalizeText(value.toString(), 320)
                is JSONArray -> normalizeText(value.toString(), 320)
                else -> normalizeText(value.toString(), 320)
            }
            if (rendered.isNotBlank()) {
                out += TraceMetaItem(label = key, value = rendered)
            }
        }
        return out
    }

    private fun parseTraceEntry(line: String, seq: Long): TraceEntry {
        val obj = runCatching { JSONObject(line) }.getOrNull()
        if (obj == null) {
            return TraceEntry(
                seq = seq,
                rawLine = line,
                timestamp = "",
                event = "raw",
                taskId = "",
                summary = line.take(120),
                detail = "",
                isError = false,
                meta = emptyList(),
                fields = emptyList()
            )
        }
        val event = obj.optString("event", "").trim().ifBlank { "unknown" }
        val ts = obj.optString("ts", "").trim()
        val taskId = obj.optString("task_id", "").trim()
        val summary = buildTraceSummary(obj, event)
        val meta = buildTraceMeta(obj, event)
        val fields = buildTraceFields(obj)
        val detail = buildTraceDetail(fields)
        val isError = event.contains("fail", ignoreCase = true) ||
            event.contains("error", ignoreCase = true) ||
            event.contains("invalid", ignoreCase = true) ||
            obj.has("error")
        return TraceEntry(
            seq = seq,
            rawLine = line,
            timestamp = ts,
            event = event,
            taskId = taskId,
            summary = summary,
            detail = detail,
            isError = isError,
            meta = meta,
            fields = fields
        )
    }

    private fun buildTraceSummary(obj: JSONObject, event: String): String {
        val phase = optText(obj, "phase")
        val state = optText(obj, "state")
        val action = optText(obj, "action")
        val pkg = optText(obj, "package")
        val reason = optText(obj, "reason")
        val error = optText(obj, "error")
        val finalTask = optText(obj, "final_task")
        val title = optText(obj, "title")
        val userTask = optText(obj, "user_task")
        val raw = optText(obj, "raw")
        val result = optText(obj, "result")
        return when {
            event == "fsm_state_enter" -> firstNonBlank(state, userTask, event)
            event == "notify_trigger" -> firstNonBlank(error, optText(obj, "rule_name"), title, pkg, event)
            event.startsWith("exec_tap_") -> buildActionTitle("Tap", obj, event)
            event.startsWith("exec_swipe_") -> buildSwipeTitle(obj, event)
            event.startsWith("exec_input_") -> firstNonBlank(error, "Input", event)
            event.startsWith("exec_wait_") -> firstNonBlank(optText(obj, "ms").let { if (it.isBlank()) "" else "Wait ${it}ms" }, event)
            event.startsWith("exec_back_") -> "Back"
            event.contains("unlock", ignoreCase = true) -> firstNonBlank(error, result, reason, state, event)
            event.startsWith("llm_prompt_") -> firstNonBlank(phase, state, action, userTask, event)
            event.startsWith("llm_response_") -> firstNonBlank(error, finalTask, raw, reason, event)
            event.startsWith("vision_") || event.startsWith("planner_") ->
                firstNonBlank(error, reason, raw, finalTask, event)
            event.startsWith("fsm_routing_") || event.startsWith("route_") ->
                firstNonBlank(error, reason, result, buildRouteSummary(obj), event)
            event.startsWith("fsm_app_resolve") || event.startsWith("resolve_") ->
                firstNonBlank(error, reason, optText(obj, "resolved_package"), optText(obj, "package"), event)
            event.startsWith("cortex_") ->
                firstNonBlank(error, finalTask, reason, userTask, event)
            event == "trace_truncated" ->
                firstNonBlank(reason, "Trace truncated", event)
            else -> firstNonBlank(error, finalTask, reason, phase, state, action, if (pkg.isNotBlank() && title.isNotBlank()) "$pkg | $title" else pkg, raw, event)
        }
            .take(160)
    }

    private fun buildTraceFields(obj: JSONObject): List<TraceMetaItem> {
        val parts = ArrayList<TraceMetaItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "event" || key == "ts") continue
            val text = optText(obj, key, 320)
            if (text.isNotEmpty()) {
                parts.add(TraceMetaItem(label = key, value = text))
            }
        }
        return parts
    }

    private fun buildTraceDetail(fields: List<TraceMetaItem>): String {
        return fields.joinToString("\n") { "${it.label}=${it.value}" }
    }

    private fun buildTraceMeta(obj: JSONObject, event: String): List<TraceMetaItem> {
        val out = ArrayList<TraceMetaItem>(4)
        when {
            event == "fsm_state_enter" -> {
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Task", optText(obj, "user_task"), 80)
                addMeta(out, "Package", optText(obj, "package"))
            }
            event == "notify_trigger" -> {
                addMeta(out, "Rule", optText(obj, "rule_name"))
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Title", optText(obj, "title"), 80)
                addMeta(out, "Text", optText(obj, "text"), 96)
                addMeta(out, "Error", optText(obj, "error"), 96)
            }
            event.startsWith("exec_tap_") -> {
                addMeta(out, "Point", joinNonBlank(", ", optText(obj, "x"), optText(obj, "y")))
            }
            event.startsWith("exec_swipe_") -> {
                addMeta(out, "From", joinNonBlank(", ", optText(obj, "x1"), optText(obj, "y1")))
                addMeta(out, "To", joinNonBlank(", ", optText(obj, "x2"), optText(obj, "y2")))
                addMeta(out, "Duration", optText(obj, "duration").let { if (it.isBlank()) "" else "${it}ms" })
                addMeta(out, "Wait", optText(obj, "wait_ms").let { if (it.isBlank()) "" else "${it}ms" })
            }
            event.startsWith("exec_input_") -> {
                addMeta(out, "Text", optText(obj, "text"), 80)
                addMeta(out, "Method", joinNonBlank(" -> ", optText(obj, "chosen_method"), optText(obj, "actual_method")))
                addMeta(out, "Status", optText(obj, "status"))
            }
            event.startsWith("exec_wait_") -> {
                addMeta(out, "Duration", optText(obj, "ms").let { if (it.isBlank()) "" else "${it}ms" })
            }
            event.startsWith("exec_back_") -> {
                addMeta(out, "Action", "Back")
            }
            event.contains("unlock", ignoreCase = true) -> {
                addMeta(out, "Result", optText(obj, "result"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Package", firstNonBlank(optText(obj, "final_package"), optText(obj, "package")))
                addMeta(out, "Screen", joinNonBlank(" / ", optText(obj, "screen_state_final"), optText(obj, "lock_hint_final")))
                addMeta(out, "Error", optText(obj, "err"), 96)
            }
            event.startsWith("fsm_routing_") || event.startsWith("route_") -> {
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Path", buildRoutePath(obj))
                addMeta(out, "Step", firstNonBlank(optText(obj, "step"), optText(obj, "step_index")))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event.startsWith("fsm_app_resolve") || event.startsWith("resolve_") -> {
                addMeta(out, "Package", firstNonBlank(optText(obj, "resolved_package"), optText(obj, "package")))
                addMeta(out, "Stage", firstNonBlank(optText(obj, "stage"), optText(obj, "result")))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event.startsWith("llm_prompt_") || event.startsWith("llm_response_") -> {
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
                addMeta(out, "Raw", optText(obj, "raw"), 96)
                addMeta(out, "Error", optText(obj, "error"), 96)
            }
            event.startsWith("vision_") || event.startsWith("planner_") -> {
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Error", firstNonBlank(optText(obj, "error"), optText(obj, "reason")), 96)
                addMeta(out, "Raw", optText(obj, "raw"), 96)
            }
            event.startsWith("cortex_") -> {
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event == "trace_truncated" -> {
                addMeta(out, "Dropped", optText(obj, "drop_head_lines"))
                addMeta(out, "Kept", optText(obj, "kept_tail_lines"))
                addMeta(out, "Reason", optText(obj, "reason"))
            }
            else -> {
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
            }
        }
        if (out.none { it.label == "Task" } && obj.optString("task_id", "").isNotBlank()) {
            addMeta(out, "TaskId", obj.optString("task_id", "").trim(), 24)
        }
        return out.take(4)
    }

    private fun buildActionTitle(label: String, obj: JSONObject, event: String): String {
        val x = optText(obj, "x")
        val y = optText(obj, "y")
        val stage = when {
            event.endsWith("_start") -> "start"
            event.endsWith("_done") -> "done"
            else -> ""
        }
        val point = joinNonBlank(", ", x, y)
        return joinNonBlank(" ", label, if (point.isBlank()) "" else "($point)", stage).ifBlank { label }
    }

    private fun buildSwipeTitle(obj: JSONObject, event: String): String {
        val from = joinNonBlank(", ", optText(obj, "x1"), optText(obj, "y1"))
        val to = joinNonBlank(", ", optText(obj, "x2"), optText(obj, "y2"))
        val stage = when {
            event.endsWith("_start") -> "start"
            event.endsWith("_done") -> "done"
            event.endsWith("_post_wait") -> "wait"
            else -> ""
        }
        return joinNonBlank(" ", "Swipe", if (from.isNotBlank() || to.isNotBlank()) "($from -> $to)" else "", stage)
            .ifBlank { "Swipe" }
    }

    private fun buildRouteSummary(obj: JSONObject): String {
        val pkg = optText(obj, "package")
        val path = buildRoutePath(obj)
        return when {
            pkg.isNotBlank() && path.isNotBlank() -> "$pkg | $path"
            path.isNotBlank() -> path
            else -> pkg
        }
    }

    private fun buildRoutePath(obj: JSONObject): String {
        return joinNonBlank(
            " -> ",
            firstNonBlank(optText(obj, "from_page"), optText(obj, "from")),
            firstNonBlank(optText(obj, "to_page"), optText(obj, "to"), optText(obj, "target_page"))
        )
    }

    private fun buildAttemptText(obj: JSONObject): String {
        val attempt = firstNonBlank(optText(obj, "attempt"), optText(obj, "route_attempt"), optText(obj, "swipe_attempt"))
        val total = firstNonBlank(optText(obj, "max_attempts"), optText(obj, "swipe_total"))
        return when {
            attempt.isNotBlank() && total.isNotBlank() -> "$attempt/$total"
            else -> attempt
        }
    }

    private fun addMeta(
        out: MutableList<TraceMetaItem>,
        label: String,
        value: String,
        maxLen: Int = 72
    ) {
        val clean = normalizeText(value, maxLen)
        if (clean.isNotBlank() && out.none { it.label == label && it.value == clean }) {
            out.add(TraceMetaItem(label = label, value = clean))
        }
    }

    private fun optText(obj: JSONObject, key: String, maxLen: Int = 180): String {
        if (!obj.has(key)) return ""
        val value = obj.opt(key) ?: return ""
        return normalizeText(value.toString(), maxLen)
    }

    private fun normalizeText(value: String, maxLen: Int = 180): String {
        val normalized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) {
            return normalized
        }
        return normalized.take(maxLen - 3).trimEnd() + "..."
    }

    private fun firstNonBlank(vararg values: String): String {
        for (value in values) {
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun joinNonBlank(separator: String, vararg values: String): String {
        return values.filter { it.isNotBlank() }.joinToString(separator)
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) {
                out.add(s)
            }
        }
        return out
    }

    fun parseSystemControl(payload: ByteArray): SystemControlParsed {
        if (payload.isEmpty()) {
            return SystemControlParsed(false, "empty_response")
        }
        if (payload.size < 3) {
            return SystemControlParsed(false, "short_response(${payload.size})")
        }
        val status = payload[0].toInt() and 0xFF
        val jsonLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val available = payload.size - 3
        val safeLen = when {
            jsonLen <= 0 -> available
            jsonLen > available -> available
            else -> jsonLen
        }
        val text = String(payload, 3, safeLen, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null) {
            return SystemControlParsed(status == 1, "invalid_json:${text.take(180)}")
        }
        val ok = (status == 1) && obj.optBoolean("ok", false)
        val detail = buildString {
            val stdout = obj.optString("stdout", "").trim()
            val stderr = obj.optString("stderr", "").trim()
            val err = obj.optString("error", "").trim()
            if (stdout.isNotEmpty()) append("stdout=").append(stdout.take(200))
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("stderr=").append(stderr.take(200))
            }
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("error=").append(err.take(200))
            }
            if (isEmpty()) {
                append("ok=").append(ok)
            }
        }
        return SystemControlParsed(ok, detail)
    }
}
