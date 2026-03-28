package com.example.lxb_ignition.schedule

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleUseCaseTest {

    @Test
    fun buildDraft_rejectsEmptyTask() {
        val input = ScheduleFormInput(
            name = "n",
            task = "   ",
            packageName = "pkg",
            playbook = "",
            recordEnabled = false,
            runAtRaw = "1730000000000",
            repeatModeRaw = "once",
            repeatWeekdays = 0
        )
        val result = ScheduleUseCase.buildDraft(
            input = input,
            nowMs = 1720000000000,
            repeatOnce = "once",
            repeatWeekly = "weekly"
        )
        assertTrue(result.isFailure)
        assertEquals("Schedule task cannot be empty.", result.exceptionOrNull()?.message)
    }

    @Test
    fun buildDraft_rejectsWeeklyWithoutWeekdays() {
        val input = ScheduleFormInput(
            name = "n",
            task = "task",
            packageName = "pkg",
            playbook = "",
            recordEnabled = false,
            runAtRaw = "1730000000000",
            repeatModeRaw = "weekly",
            repeatWeekdays = 0
        )
        val result = ScheduleUseCase.buildDraft(
            input = input,
            nowMs = 1720000000000,
            repeatOnce = "once",
            repeatWeekly = "weekly"
        )
        assertTrue(result.isFailure)
        assertEquals(
            "Please select at least one weekday for weekly repeat.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun buildDraft_acceptsValidInput_andBuildPayload() {
        val input = ScheduleFormInput(
            name = "  demo  ",
            task = "  do things ",
            packageName = " com.demo.app ",
            playbook = " step1 ",
            recordEnabled = true,
            runAtRaw = "1730000000000",
            repeatModeRaw = "daily",
            repeatWeekdays = 0b1111111
        )
        val draft = ScheduleUseCase.buildDraft(
            input = input,
            nowMs = 1720000000000,
            repeatOnce = "once",
            repeatWeekly = "weekly"
        ).getOrThrow()

        assertEquals("demo", draft.name)
        assertEquals("do things", draft.task)
        assertEquals("com.demo.app", draft.packageName)
        assertEquals("step1", draft.playbook)
        assertTrue(draft.recordEnabled)
        assertEquals(1730000000000, draft.runAt)

        val payload = ScheduleUseCase.buildUpsertPayload(
            draft = draft,
            traceUdpPort = 23456,
            repeatDaily = "daily",
            scheduleId = "sid-1"
        )
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        assertEquals("sid-1", json.optString("schedule_id"))
        assertEquals("daily", json.optString("repeat_mode"))
        assertEquals(true, json.optBoolean("repeat_daily"))
        assertEquals(23456, json.optInt("trace_udp_port"))
        assertFalse(json.optString("user_task").isBlank())
    }
}
