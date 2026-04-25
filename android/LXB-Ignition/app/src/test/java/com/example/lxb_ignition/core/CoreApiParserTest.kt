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

    @Test
    fun parseTaskMapDetail_readsPortableAdaptationFields() {
        val step = JSONObject()
            .put("step_id", "s0001")
            .put("source_action_id", "a0001")
            .put("op", "TAP")
            .put("args", org.json.JSONArray())
            .put("fallback_point", "[100,200]")
            .put("semantic_note", "当前页面是首页")
            .put("expected", "进入发布页")
            .put("locator", JSONObject().put("resource_id", "publish_button"))
            .put("container_probe", JSONObject().put("class", "LinearLayout"))
            .put("semantic_descriptor", JSONObject().put("instruction", "点击发布帖子入口"))
            .put("tap_point", org.json.JSONArray().put(100).put(200))
            .put("swipe", JSONObject())
            .put("portable_kind", "materialized")
            .put("adaptation_status", "adapted")
            .put("adaptation_error", "")
            .put("materialized_from_step_id", "s0001")
            .put("materialized_at_ms", 99L)
        val segment = JSONObject()
            .put("segment_id", "seg0001")
            .put("sub_task_id", "default")
            .put("sub_task_index", 0)
            .put("sub_task_description", "发帖")
            .put("success_criteria", "")
            .put("package_name", "com.demo")
            .put("package_label", "Demo")
            .put("inputs", org.json.JSONArray())
            .put("outputs", org.json.JSONArray())
            .put("steps", org.json.JSONArray().put(step))
        val taskMap = JSONObject()
            .put("schema", "task_map.v1")
            .put("mode", "manual")
            .put("package_name", "com.demo")
            .put("package_label", "Demo")
            .put("created_from_task_id", "tid")
            .put("created_at_ms", 1L)
            .put("last_replay_status", "unused")
            .put("finish_after_replay", false)
            .put("segments", org.json.JSONArray().put(segment))
        val payload = JSONObject()
            .put("ok", true)
            .put("route_id", "hash")
            .put("has_map", true)
            .put("task_map", taskMap)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val parsed = CoreApiParser.parseTaskMapDetail(payload).second!!
        val parsedStep = parsed.taskMap!!.segments.first().steps.first()

        assertEquals("materialized", parsedStep.portableKind)
        assertEquals("adapted", parsedStep.adaptationStatus)
        assertEquals("[100,200]", parsedStep.tapPoint)
        assertEquals("点击发布帖子入口", parsedStep.semanticDescriptorFields.first().value)
        assertEquals("LinearLayout", parsedStep.containerProbeFields.first().value)
        assertEquals(99L, parsedStep.materializedAtMs)
    }
}
