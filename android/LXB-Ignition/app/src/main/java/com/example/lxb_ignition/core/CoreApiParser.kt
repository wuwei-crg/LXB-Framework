package com.example.lxb_ignition.core

import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskSummary
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
