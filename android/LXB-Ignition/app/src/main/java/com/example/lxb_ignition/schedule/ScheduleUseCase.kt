package com.example.lxb_ignition.schedule

data class ScheduleFormInput(
    val name: String,
    val task: String,
    val packageName: String,
    val playbook: String,
    val recordEnabled: Boolean,
    val runAtRaw: String,
    val repeatModeRaw: String,
    val repeatWeekdays: Int
)

data class ScheduleDraft(
    val name: String,
    val task: String,
    val packageName: String,
    val playbook: String,
    val recordEnabled: Boolean,
    val runAt: Long,
    val repeatMode: String,
    val repeatWeekdays: Int
)

object ScheduleUseCase {

    fun buildDraft(
        input: ScheduleFormInput,
        nowMs: Long,
        repeatOnce: String,
        repeatWeekly: String
    ): Result<ScheduleDraft> = runCatching {
        val task = input.task.trim()
        if (task.isEmpty()) {
            throw IllegalArgumentException("Schedule task cannot be empty.")
        }
        val runAt = input.runAtRaw.trim().toLongOrNull()
        if (runAt == null || runAt <= 0L) {
            throw IllegalArgumentException("Please pick a valid date and time.")
        }

        val repeatMode = input.repeatModeRaw.trim().lowercase()
        val repeatWeekdays = input.repeatWeekdays and 0x7F
        if (repeatMode == repeatOnce && runAt <= nowMs) {
            throw IllegalArgumentException("run_at must be in the future.")
        }
        if (repeatMode == repeatWeekly && repeatWeekdays == 0) {
            throw IllegalArgumentException("Please select at least one weekday for weekly repeat.")
        }

        ScheduleDraft(
            name = input.name.trim(),
            task = task,
            packageName = input.packageName.trim(),
            playbook = input.playbook.trim(),
            recordEnabled = input.recordEnabled,
            runAt = runAt,
            repeatMode = repeatMode,
            repeatWeekdays = repeatWeekdays
        )
    }

    fun buildUpsertPayload(
        draft: ScheduleDraft,
        traceUdpPort: Int,
        repeatDaily: String,
        scheduleId: String? = null
    ): ByteArray {
        val payload = org.json.JSONObject()
            .put("name", draft.name)
            .put("user_task", draft.task)
            .put("package", draft.packageName)
            .put("trace_mode", "push")
            .put("trace_udp_port", traceUdpPort)
            .put("run_at", draft.runAt)
            .put("repeat_mode", draft.repeatMode)
            .put("repeat_weekdays", draft.repeatWeekdays)
            .put("repeat_daily", draft.repeatMode == repeatDaily) // backward compatibility
            .put("user_playbook", draft.playbook)
            .put("record_enabled", draft.recordEnabled)
        if (!scheduleId.isNullOrBlank()) {
            payload.put("schedule_id", scheduleId)
        }
        return payload.toString().toByteArray(Charsets.UTF_8)
    }
}
