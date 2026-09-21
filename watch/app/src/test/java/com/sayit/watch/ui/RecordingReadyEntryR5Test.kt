package com.sayit.watch.ui

import com.sayit.watch.R
import com.sayit.watch.net.DiscoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Delivery 1C 1C-D-04@R5 — the "搜索/切换电脑" entry must be reachable from Ready.
 *
 * The real Galaxy Watch run showed the user's most common path was unreachable:
 * `RecordingScreen` rendered the switch entry only when `ui.targets.size > 1`, but a
 * successful saved-address re-probe yields exactly one known computer, so the entry
 * was hidden in the normal "one PC online" case. R4's tests only drove the ViewModel
 * entry point directly and never checked that the UI offers it.
 *
 * This file pins the decision functions (real, production code — not mirrors) plus
 * the source-level wiring. Compose rendering itself is NOT executable in this JVM
 * environment, so the rendered layout stays with the PM device check; that limit is
 * stated explicitly rather than papered over.
 */
class RecordingReadyEntryR5Test {

    private val screenSource: String by lazy {
        File("src/main/java/com/sayit/watch/ui/RecordingScreen.kt").readText()
    }

    private fun ready(
        targets: List<com.sayit.watch.net.DiscoverySelection> = emptyList(),
        searching: Boolean = false,
    ) = WatchUiState(
        screen = WatchUiState.Screen.READY,
        transportAvailable = targets.isNotEmpty(),
        isUploading = false,
        targets = targets,
        currentTarget = targets.firstOrNull(),
        switchSearching = searching,
    )

    private val oneTarget = listOf(com.sayit.watch.net.DiscoverySelection("192.168.12.100", 18099))
    private val twoTargets = oneTarget + com.sayit.watch.net.DiscoverySelection("192.168.12.142", 18099)

    // ── 必修 1: the entry exists for 0 / 1 / ≥2 targets ────────────────────────

    @Test
    fun `the search entry is available with zero one and two authenticated computers`() {
        assertTrue("0 targets must still offer the entry", searchEntryAvailable(ready()))
        assertTrue(
            "1 target (the reported real-device case) must offer the entry",
            searchEntryAvailable(ready(targets = oneTarget)),
        )
        assertTrue("2 targets must offer the entry", searchEntryAvailable(ready(targets = twoTargets)))
        // And the decision does not depend on the target count at all.
        assertFalse(
            "the entry must not appear on a non-Ready screen",
            searchEntryAvailable(
                ready(targets = oneTarget).copy(screen = WatchUiState.Screen.CONFIG),
            ),
        )
    }

    @Test
    fun `the entry label is neutral while searching and names the action otherwise`() {
        assertEquals(R.string.ready_entry_searching, switchEntryLabelRes(searching = true, hasKnownTargets = true))
        assertEquals(R.string.ready_entry_searching, switchEntryLabelRes(searching = true, hasKnownTargets = false))
        assertEquals(R.string.ready_search_computer, switchEntryLabelRes(searching = false, hasKnownTargets = false))
        assertEquals(R.string.ready_switch_computer, switchEntryLabelRes(searching = false, hasKnownTargets = true))
    }

    // ── 必修 3: wording must not turn a saved re-probe into a discovery ────────

    @Test
    fun `a saved address verdict is never worded as a new automatic discovery`() {
        // `saved-probe:accepted` / `verdict:one` reaches the UI as ExistingAddress.
        val saved = readyStatusTextRes(DiscoveryState.ExistingAddress, connected = true, connecting = false)
        assertEquals(R.string.discovery_saved_neutral, saved)
        // The same holds for the string-level label policy.
        val label = discoveryLabel(DiscoveryState.ExistingAddress, true)
        assertFalse("'$label' must not claim an automatic discovery", label.contains("自动发现"))
        assertFalse("'$label' must not claim mDNS success", label.contains("mDNS"))
        // 1C-D-04@R6: the production chain normalises every verified run to Discovered,
        // so the UI cannot tell a fresh browse from a saved re-probe and must not claim
        // one. 1C-D-04@R7 removed the "已自动发现电脑" resource entirely (it had no
        // production call site), so the claim is not expressible any more.
        assertEquals(
            R.string.discovery_saved_neutral,
            readyStatusTextRes(DiscoveryState.Discovered, connected = true, connecting = false),
        )
        // 1C-D-04@R7 §2A: a round that is still checking must not reuse the previous
        // authentication as if it were a live connection.
        assertEquals(
            R.string.discovery_searching,
            readyStatusTextRes(DiscoveryState.Discovered, connected = true, connecting = true),
        )
        assertEquals(
            "an unverified Discovered state must not claim success",
            R.string.discovery_awaiting,
            readyStatusTextRes(DiscoveryState.Discovered, connected = false, connecting = false),
        )
    }

    @Test
    fun `every status state maps to a distinct, resource-backed label`() {
        for (state in listOf(
            DiscoveryState.Idle,
            DiscoveryState.Searching,
            DiscoveryState.ExistingAddress,
            DiscoveryState.Discovered,
            DiscoveryState.ManualFallback,
        )) {
            for (connected in listOf(false, true)) {
                for (connecting in listOf(false, true)) {
                    val res = readyStatusTextRes(state, connected, connecting)
                    assertNotEquals("state $state must have a label", 0, res)
                }
            }
        }
        // Searching must not claim a result, and the fallback must name the action.
        assertEquals(
            R.string.discovery_searching,
            readyStatusTextRes(DiscoveryState.Searching, connected = false, connecting = false),
        )
        assertEquals(
            R.string.discovery_manual,
            readyStatusTextRes(DiscoveryState.ManualFallback, connected = false, connecting = false),
        )
        assertFalse(
            "the fallback wording must not imply mDNS success",
            discoveryLabel(DiscoveryState.ManualFallback, false).contains("自动发现成功"),
        )
    }

    // ── source-level wiring guards (rendering is PM-verified) ─────────────────

    /**
     * Strips KDoc/line comments so a source guard cannot be defeated — or tripped — by
     * the explanatory comment that documents the removed behaviour.
     */
    private fun codeOnly(source: String): String = source
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `the Ready screen keeps the search entry reachable through Settings`() {
        val ready = codeOnly(
            screenSource.substringAfter("private fun ReadyScreen(")
                .substringBefore("private fun ReadySettingsMenu("),
        )
        val menu = codeOnly(
            screenSource.substringAfter("private fun ReadySettingsMenu(")
                .substringBefore("internal fun searchEntryAvailable"),
        )
        assertNotNull("ReadyScreen must exist", ready)

        assertTrue(
            "the Settings menu must call the production requestSwitch(): $ready",
            ready.contains("viewModel.requestSwitch()"),
        )
        assertFalse(
            "the entry must NOT be gated on more than one known computer",
            ready.contains("targets.size > 1") || ready.contains("targets.size >= 2"),
        )
        assertTrue(
            "the menu must use the shared decision function",
            menu.contains("switchEntryLabelRes("),
        )
        assertTrue(
            "the status line must go through the resource decision",
            ready.contains("readyStatusTextRes("),
        )
        // The manual/IP screen keeps its own separate entry and never opens itself.
        // The manual fallback must NOT be the only path to settings: the settings entry
        // has to be its own unconditional statement, not nested under a fallback check.
        assertTrue("the settings entry must stay", ready.contains("settingsMenuOpen = true"))
        assertTrue("the menu must keep connection settings accessible", ready.contains("viewModel.openConfig()"))
        assertFalse(
            "the failure state must not be the only way to reach settings",
            Regex(
                """(?m)^\s*if\s*\(\s*discovery\s+is\s+DiscoveryState\.ManualFallback\s*\)""",
            ).containsMatchIn(ready),
        )
    }

    @Test
    fun `the entry meets the existing 48 dp touch rule and avoids the old overlaps`() {
        val ready = codeOnly(
            screenSource.substringAfter("private fun ReadyScreen(")
                .substringBefore("private fun ReadySettingsMenu("),
        )
        val menu = codeOnly(
            screenSource.substringAfter("private fun ReadySettingsMenu(")
                .substringBefore("internal fun searchEntryAvailable"),
        )
        // The R3 Settings action is a 52 dp-tall row, not the former pill chip.
        assertTrue("the switch entry must be a touch-sized row", menu.contains("modifier.height(52.dp)"))
        assertTrue(
            "the Settings menu must carry an explicit switch action",
            menu.contains("onClick = onSwitch"),
        )
        assertTrue(
            "the settings entry must respect the 48 dp row minimum",
            ready.contains("WatchUiMetrics.RowMinHeightDp"),
        )
        // The original overlapping offsets must not return. The dial now uses
        // responsive placement to match the frozen dev.3 microphone position.
        assertFalse(
            "the Ready screen must not hand-place overlapping offsets any more",
            ready.contains("offset(y = 106.dp)") || ready.contains("offset(y = 88.dp)"),
        )
        assertTrue(
            "the Ready screen must center the microphone on the dial",
            ready.contains("BoxWithConstraints(Modifier.fillMaxSize())") &&
                ready.contains("Modifier.align(Alignment.Center).size(96.dp)"),
        )
        assertTrue(
            "only the neutral connected label receives the optical center adjustment",
            ready.contains("if (statusLabelRes == R.string.discovery_saved_neutral) (-2).dp else 0.dp"),
        )
    }

    @Test
    fun `the UI strings the entry needs are all declared`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (name in listOf(
            "ready_search_computer",
            "ready_switch_computer",
            "ready_entry_searching",
            "ready_open_config",
            "discovery_searching",
            "discovery_saved_neutral",
            "discovery_awaiting",
            "discovery_none_yet",
            "discovery_manual",
            // 1C-D-04@R7 §2B: the switch page's current-computer line and short labels.
            "switch_current_section",
            "switch_current_computer",
            "switch_other_computer",
            "switch_dialog_hint",
            "switch_search_again",
            "switch_searching",
            "discovery_current_missing",
            "action_back",
        )) {
            assertTrue("missing string resource: $name", strings.contains("name=\"$name\""))
        }
        // 1C-D-04@R7: the unreachable discovery claim was removed, so the Ready screen
        // cannot render "已自动发现电脑" at all any more.
        assertFalse(
            "the removed discovery claim must stay removed",
            strings.contains("name=\"discovery_found\""),
        )
        // The long fixed "all of these computers have been verified" paragraph is gone.
        assertFalse(
            "the fixed long verification paragraph must be removed",
            strings.contains("以下电脑都已验证通过"),
        )
        // The neutral saved-address wording must not contain the discovery claim.
        val neutral = strings.substringAfter("name=\"discovery_saved_neutral\">").substringBefore("<")
        assertFalse("neutral wording must not claim discovery: $neutral", neutral.contains("自动发现"))
    }

    @Test
    fun `the state machine can carry a searching entry without breaking recording`() {
        val machine = WatchUiStateMachine().also { it.settingsApplied() }
        machine.destinationResolved()
        assertTrue("recording stays enabled during a search", machine.state.canRecord)

        machine.switchSearching(true)
        assertTrue("the search must not disturb the recording gate", machine.state.canRecord)
        assertTrue(machine.state.switchSearching)

        machine.switchSearching(false)
        assertFalse(machine.state.switchSearching)
        assertTrue(machine.state.canRecord)
    }
}
