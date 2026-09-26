package com.sayit.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComputerPickerIdentityR8Test {
    private val source by lazy {
        File("src/main/java/com/sayit/watch/ui/RecordingScreen.kt").readText()
    }

    @Test
    fun `valid IPv4 uses the last octet as the fallback identity`() {
        assertEquals("电脑 · 142", computerFallbackName("192.168.12.142"))
        assertEquals("电脑 · 153", computerFallbackName("192.168.12.153"))
    }

    @Test
    fun `invalid or non IPv4 input does not leak an arbitrary address into the name`() {
        assertEquals("电脑", computerFallbackName("computer.local"))
        assertEquals("电脑", computerFallbackName("192.168.12.999"))
        assertEquals("电脑", computerFallbackName(""))
    }

    @Test
    fun `picker makes friendly identity primary and endpoint secondary`() {
        val picker = source.substringAfter("private fun ComputerSwitchDialog(")
            .substringBefore("private fun RecordingActiveScreen(")
        assertTrue(picker.contains("computerFallbackName(entry.target.ip)"))
        assertTrue(picker.contains("R.string.switch_status_current"))
        assertTrue(picker.contains("R.string.switch_status_online"))
        assertFalse(picker.contains("Text(\"\${entry.target.ip}:\${entry.target.port}\""))
    }

    @Test
    fun `streamed candidates stay visible but disabled until search completes`() {
        val card = source.substringAfter("private fun SwitchComputerCard(")
            .substringBefore("private fun SwitchComputerGlyph(")
        assertTrue(card.contains("val enabled = !searching"))
        assertTrue(card.contains("enabled = enabled"))
        assertTrue(card.contains("R.string.switch_status_verified_wait"))
    }

    @Test
    fun `close and refresh actions retain watch sized touch targets`() {
        val picker = source.substringAfter("private fun SwitchCloseAction(")
            .substringBefore("private fun RecordingActiveScreen(")
        assertTrue(picker.contains("size(48.dp).clickable"))
        assertTrue(picker.contains("fillMaxWidth().height(48.dp).clickable"))
    }
}
