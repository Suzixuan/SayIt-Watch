package com.sayit.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery 1C Repair 1 必修 2 regression at the state-machine level: in each of the
 * four "no verified destination yet" states the Ready screen must not offer
 * recording, and exactly one authenticated verdict must enable it.
 *
 * The upload-target invariant itself (`currentDestination()` returns only the
 * probe-authenticated address, and `startRecording()` is gated on it) is pinned
 * at source level in [RecordingEntryGuardTest], because constructing the
 * ViewModel needs a Context-bound SettingsStore this JVM environment lacks.
 */
class WatchUiStateMachineTest {

    private fun readyMachine(): WatchUiStateMachine = WatchUiStateMachine().also { it.settingsApplied() }

    /** Ready with an authenticated destination (what discovery success produces). */
    private fun verifiedMachine(): WatchUiStateMachine =
        WatchUiStateMachine().also {
            it.settingsApplied()
            it.destinationResolved()
        }

    // ── existing dev.3 flow ─────────────────────────────────────────────────

    @Test
    fun `save and apply moves from config to ready`() {
        val machine = WatchUiStateMachine()
        assertEquals(WatchUiState.Screen.CONFIG, machine.state.screen)
        machine.settingsApplied()
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertFalse("a freshly applied screen has no verified target", machine.state.canRecord)
    }

    @Test
    fun `valid saved config starts directly on ready`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = true)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertFalse(machine.state.isUploading)
    }

    @Test
    fun `invalid or missing config starts on config and stays there`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = false)
        assertEquals(WatchUiState.Screen.CONFIG, machine.state.screen)
        machine.settingsApplied()
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `startup decision is idempotent and does not move a recording screen`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = true)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        machine.startupWith(configValid = false)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `stop starts silent upload and immediately returns the visible screen to ready`() {
        val machine = verifiedMachine()
        machine.recordingStarted()
        machine.uploadStarted()

        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertTrue(machine.state.isUploading)
        assertTrue(machine.state.keepScreenOn)
        assertFalse("no recording while an upload is in flight", machine.state.canRecord)
    }

    @Test
    fun `success and failure share the same silent completion state`() {
        repeat(2) {
            val machine = verifiedMachine()
            machine.recordingStarted()
            machine.uploadStarted()
            machine.uploadFinished()

            assertEquals(WatchUiState.Screen.READY, machine.state.screen)
            assertFalse(machine.state.isUploading)
            assertFalse(machine.state.keepScreenOn)
            assertTrue(machine.canStartRecording())
            assertTrue("the verified target survives an upload", machine.state.canRecord)
        }
    }

    @Test
    fun `record is a no op during silent upload and works when upload finishes`() {
        val machine = verifiedMachine()
        machine.recordingStarted()
        machine.uploadStarted()
        machine.recordingStarted()

        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertTrue(machine.state.isUploading)

        machine.uploadFinished()
        machine.recordingStarted()
        assertEquals(WatchUiState.Screen.RECORDING, machine.state.screen)
    }

    @Test
    fun `only recording start and stop retain haptics`() {
        org.junit.Assert.assertArrayEquals(
            longArrayOf(0, 60),
            recordingHapticPattern(com.sayit.watch.recording.RecordingSession.State.RECORDING),
        )
        org.junit.Assert.assertArrayEquals(
            longArrayOf(0, 40, 60, 40),
            recordingHapticPattern(com.sayit.watch.recording.RecordingSession.State.RECORDED),
        )
        org.junit.Assert.assertNull(
            recordingHapticPattern(com.sayit.watch.recording.RecordingSession.State.TRANSPORT_SUCCESS),
        )
        org.junit.Assert.assertNull(
            recordingHapticPattern(com.sayit.watch.recording.RecordingSession.State.FAILURE),
        )
    }

    // ── Repair 1 必修 2: no upload target until exactly one endpoint authenticates ──

    @Test
    fun `state one - a valid token alone starts on ready without a target`() {
        val machine = WatchUiStateMachine()
        machine.startupWith(configValid = true)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
        assertFalse("no probe has run yet", machine.state.canRecord)
        assertFalse(machine.state.manualOpen)
        assertFalse(machine.state.discoveryExhausted)
    }

    @Test
    fun `state two - re-probing or browsing disables recording`() {
        val machine = verifiedMachine()
        assertTrue(machine.state.canRecord)
        machine.searchingStarted()
        assertFalse("a run in flight has no verified target", machine.state.canRecord)
        assertFalse(machine.canStartRecording() && machine.state.canRecord)
    }

    @Test
    fun `state three - manual probe in flight disables recording`() {
        val machine = readyMachine()
        machine.searchingStarted()
        assertFalse(machine.state.canRecord)
        // Saving config moves to Ready but still yields no target.
        machine.settingsApplied()
        assertFalse(machine.state.canRecord)
    }

    @Test
    fun `state four - manual probe failure and discovery failure both disable recording`() {
        for (machine in listOf(readyMachine(), verifiedMachine())) {
            machine.discoveryFailed()
            assertEquals(WatchUiState.Screen.READY, machine.state.screen)
            assertTrue(machine.state.discoveryExhausted)
            assertTrue("a failure must offer the manual fields", machine.state.manualOpen)
            assertFalse("a failed probe leaves no target", machine.state.canRecord)
            // The UI stays usable, but recording itself is unavailable.
            assertTrue(machine.canStartRecording())
        }
    }

    @Test
    fun `authentication success yields exactly one recordable target`() {
        val machine = readyMachine()
        machine.searchingStarted()
        assertFalse(machine.state.canRecord)
        machine.destinationResolved()
        assertTrue("exactly one authenticated verdict enables recording", machine.state.canRecord)
        assertFalse(machine.state.discoveryExhausted)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `discovery results never disturb a running recording`() {
        val machine = verifiedMachine()
        machine.recordingStarted()
        machine.discoveryFailed()
        assertEquals(WatchUiState.Screen.RECORDING, machine.state.screen)
        machine.destinationResolved()
        assertEquals(WatchUiState.Screen.RECORDING, machine.state.screen)
        assertFalse(machine.state.manualOpen)
    }

    @Test
    fun `manual settings section toggles and is independent of the screen`() {
        val machine = readyMachine()
        assertFalse(machine.state.manualOpen)
        machine.manualSettingsToggled()
        assertTrue(machine.state.manualOpen)
        machine.manualSettingsToggled()
        assertFalse(machine.state.manualOpen)
        assertEquals(WatchUiState.Screen.READY, machine.state.screen)
    }

    @Test
    fun `discovery status line is transport-only wording`() {
        assertTrue(discoveryLabel(com.sayit.watch.net.DiscoveryState.Idle, false).isNotEmpty())
        // 1C-D-04@R5 必修 3: searching is an action, and the saved-address verdict is
        // never worded as a new automatic discovery.
        assertTrue(
            discoveryLabel(com.sayit.watch.net.DiscoveryState.Searching, false).contains("搜索"),
        )
        val saved = discoveryLabel(com.sayit.watch.net.DiscoveryState.ExistingAddress, true)
        assertTrue(saved.isNotEmpty())
        assertFalse(
            "a saved-address re-probe must not be worded as an automatic discovery: $saved",
            saved.contains("自动发现"),
        )
        assertTrue(discoveryLabel(com.sayit.watch.net.DiscoveryState.Discovered, true).isNotEmpty())
        assertTrue(discoveryLabel(com.sayit.watch.net.DiscoveryState.ManualFallback, false).contains("手动"))
        // "Discovered" without a verified target must never claim success.
        assertTrue(
            discoveryLabel(com.sayit.watch.net.DiscoveryState.Discovered, false) !=
                discoveryLabel(com.sayit.watch.net.DiscoveryState.Discovered, true),
        )
        for (state in listOf(
            com.sayit.watch.net.DiscoveryState.Idle,
            com.sayit.watch.net.DiscoveryState.Searching,
            com.sayit.watch.net.DiscoveryState.ExistingAddress,
            com.sayit.watch.net.DiscoveryState.Discovered,
            com.sayit.watch.net.DiscoveryState.ManualFallback,
        )) {
            for (verified in listOf(false, true)) {
                val label = discoveryLabel(state, verified)
                assertFalse("status text must not claim transcription: $label", label.contains("转写"))
                assertFalse("status text must not claim ASR readiness: $label", label.contains("识别就绪"))
            }
        }
    }
}
