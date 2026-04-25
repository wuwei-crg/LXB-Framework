package com.example.lxb_ignition.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceEventMapperTest {

    @Test
    fun map_fsmStateEnter_appResolve() {
        val obj = JSONObject()
            .put("event", "fsm_state_enter")
            .put("state", "APP_RESOLVE")
            .put("task_id", "tid-1")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertEquals("fsm_state_enter", mapped?.event)
        assertEquals("tid-1", mapped?.taskId)
        assertTrue(mapped?.messages?.any { it.contains("APP_RESOLVE") } == true)
        assertEquals("APP_RESOLVE", mapped?.runtimeUpdate?.phase)
        assertEquals(false, mapped?.runtimeUpdate?.stopAfter)
    }

    @Test
    fun map_failureEvent_setsStopRuntime() {
        val obj = JSONObject()
            .put("event", "fsm_route_plan_failed")
            .put("reason", "no node")
            .put("task_id", "tid-2")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.joinToString(" ")?.contains("Route planning failed") == true)
        assertEquals("FAILED", mapped?.runtimeUpdate?.phase)
        assertTrue(mapped?.runtimeUpdate?.stopAfter == true)
    }

    @Test
    fun map_emptyEvent_returnsNull() {
        val obj = JSONObject().put("event", "")
        val mapped = TraceEventMapper.map(obj)
        assertNull(mapped)
    }

    @Test
    fun map_unknownEvent_returnsNonNullWithoutRuntime() {
        val obj = JSONObject().put("event", "unknown_event")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertEquals("unknown_event", mapped?.event)
        assertTrue(mapped?.messages?.isEmpty() == true)
        assertNull(mapped?.runtimeUpdate)
    }
}
