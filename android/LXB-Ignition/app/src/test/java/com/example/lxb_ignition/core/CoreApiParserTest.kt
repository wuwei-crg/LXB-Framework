package com.example.lxb_ignition.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreApiParserTest {

    @Test
    fun parseTaskSubmit_success() {
        val payload = JSONObject()
            .put("ok", true)
            .put("status", "submitted")
            .put("task_id", "tid-1")
            .toString()
            .toByteArray(Charsets.UTF_8)

        val parsed = CoreApiParser.parseTaskSubmit(payload)
        assertEquals("tid-1", parsed.taskId)
        assertTrue(parsed.message.startsWith("Task submitted: "))
    }

    @Test
    fun parseTaskSubmit_invalidJson() {
        val parsed = CoreApiParser.parseTaskSubmit("not-json".toByteArray(Charsets.UTF_8))
        assertEquals("", parsed.taskId)
        assertTrue(parsed.message.startsWith("Invalid response: "))
    }

    @Test
    fun parseSystemControl_emptyPayload() {
        val parsed = CoreApiParser.parseSystemControl(ByteArray(0))
        assertEquals(false, parsed.ok)
        assertEquals("empty_response", parsed.detail)
    }

    @Test
    fun parseSystemControl_okPayload() {
        val body = JSONObject().put("ok", true).put("stdout", "done").toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(3 + bodyBytes.size)
        payload[0] = 1 // status
        payload[1] = ((bodyBytes.size shr 8) and 0xFF).toByte()
        payload[2] = (bodyBytes.size and 0xFF).toByte()
        System.arraycopy(bodyBytes, 0, payload, 3, bodyBytes.size)

        val parsed = CoreApiParser.parseSystemControl(payload)
        assertEquals(true, parsed.ok)
        assertTrue(parsed.detail.contains("stdout=done"))
    }
}
