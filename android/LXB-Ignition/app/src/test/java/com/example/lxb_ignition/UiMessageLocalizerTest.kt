package com.example.lxb_ignition

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMessageLocalizerTest {

    @Test
    fun localize_returnsOriginalForNonZh() {
        val text = "Task finished successfully."
        assertEquals(text, UiMessageLocalizer.localize("en", text))
    }

    @Test
    fun localize_mapsKnownMessageForZh() {
        val text = "Task finished successfully."
        assertEquals("任务执行成功。", UiMessageLocalizer.localize("zh", text))
    }

    @Test
    fun localize_keepsUnknownMessage() {
        val text = "some-random-message"
        assertEquals(text, UiMessageLocalizer.localize("zh", text))
    }
}
