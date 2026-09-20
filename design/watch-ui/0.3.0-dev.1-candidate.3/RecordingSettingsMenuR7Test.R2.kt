package com.sayit.watch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level UI wiring guards; final Compose layout is checked on the Watch. */
class RecordingSettingsMenuR7Test {
    private val source by lazy { File("src/main/java/com/sayit/watch/ui/RecordingScreen.kt").readText() }

    private val ready by lazy {
        source.substringAfter("private fun ReadyScreen(")
            .substringBefore("private fun ReadySettingsMenu(")
    }

    private val menu by lazy {
        source.substringAfter("private fun ReadySettingsMenu(")
            .substringBefore("internal fun searchEntryAvailable")
    }

    @Test
    fun `Ready keeps only a low key Settings entry and no large switch chip`() {
        assertTrue(ready.contains("stringResource(R.string.ready_open_config)"))
        assertTrue(ready.contains("settingsMenuOpen = true"))
        assertTrue(ready.contains("ReadySettingsMenu("))
        assertFalse(ready.contains("switchEntryLabelRes("))
        assertFalse(ready.contains("R.string.ready_switch_computer"))
    }

    @Test
    fun `opening Settings does not invalidate the verified computer`() {
        val openMenu = ready.substringAfter("stringResource(R.string.ready_open_config)")
            .substringBefore("if (settingsMenuOpen)")
        assertFalse(openMenu.contains("viewModel.openConfig()"))

        val switchAction = ready.substringAfter("onSwitch = {")
            .substringBefore("onConnectionSettings = {")
        assertTrue(switchAction.contains("viewModel.requestSwitch()"))
        assertFalse(switchAction.contains("viewModel.openConfig()"))

        val connectionAction = ready.substringAfter("onConnectionSettings = {")
            .substringBefore("if (ui.switchOpen)")
        assertTrue(connectionAction.contains("viewModel.openConfig()"))
    }

    @Test
    fun `the Settings menu exposes switch and manual configuration with watch sized actions`() {
        assertTrue(menu.contains("Dialog(onDismissRequest = onDismiss)"))
        assertTrue(menu.contains("switchEntryLabelRes(ui.switchSearching, ui.targets.isNotEmpty())"))
        assertTrue(menu.contains("onSwitch,"))
        assertTrue(menu.contains("R.string.screen_config_title"))
        assertTrue(menu.contains("onConnectionSettings"))
        assertTrue(menu.contains("PillAction("))
        assertTrue(menu.contains("WatchUiMetrics.RowMinHeightDp"))

        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("name=\"ready_open_config\">设置<"))
        assertFalse(strings.contains("点上方按钮重新搜索"))
    }
}
