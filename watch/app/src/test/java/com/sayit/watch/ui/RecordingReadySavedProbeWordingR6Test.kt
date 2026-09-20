package com.sayit.watch.ui

import com.sayit.watch.R
import com.sayit.watch.net.CategoryLog
import com.sayit.watch.net.DiscoveryCoordinator
import com.sayit.watch.net.DiscoveryDiagnostic
import com.sayit.watch.net.DiscoveryProbe
import com.sayit.watch.net.DiscoveryProbeResult
import com.sayit.watch.net.DiscoverySelection
import com.sayit.watch.net.DiscoverySettings
import com.sayit.watch.net.DiscoveryState
import com.sayit.watch.net.DiscoveryTiming
import com.sayit.watch.net.ResolvedService
import com.sayit.watch.net.SAYIT_DISCOVERY_PROTOCOL
import com.sayit.watch.net.SAYIT_DISCOVERY_SERVICE_ID
import com.sayit.watch.net.SAYIT_SERVICE_TYPE_ANDROID
import com.sayit.watch.net.ServiceDiscovery
import com.sayit.watch.recording.AudioCapture
import com.sayit.watch.recording.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Delivery 1C 1C-D-04@R6 — "已自动发现电脑" must not appear for a saved-address re-probe.
 *
 * R5 fixed the *pure* wording function but tested it with `ExistingAddress`, a state
 * the production chain never reaches for a saved-address success: the bridge
 * normalises every verified run to `Discovered`, and the ViewModel publishes
 * `Discovered` for the saved verdict too. The real Galaxy Watch therefore showed
 * "已自动发现电脑" on a cold start whose only log lines were
 * `saved-probe:accepted` / `verdict:one` (no `browse:*`).
 *
 * These tests therefore drive the REAL `RecordingViewModel.onReadyEntered()` through
 * the REAL `DiscoveryCoordinator` and assert BOTH:
 *   (a) the ViewModel currently publishes `Discovered` (documenting why the pure
 *       function test was insufficient), and
 *   (b) the Ready status mapping still says "已连接电脑" (fail-safe).
 *
 * Compose rendering itself is not executable in this JVM environment; the layout
 * remains PM-device-verified.
 */
class RecordingReadySavedProbeWordingR6Test {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val savedPc = DiscoverySelection("192.168.12.100", 18099)

    private val screenSource: String by lazy {
        File("src/main/java/com/sayit/watch/ui/RecordingScreen.kt").readText()
    }

    // ── test doubles ─────────────────────────────────────────────────────────

    private class FakeSettings(
        override var savedIp: String = "",
        override var savedPort: String = "",
        override var devToken: String = "",
    ) : DiscoverySettings {
        var savedCount = 0
        var tokenWrites = 0
        override fun hasValidToken(): Boolean =
            com.sayit.watch.settings.DevTokenValidator.isValid(devToken)
        override fun saveDevToken(token: String) {
            devToken = token
            tokenWrites++
        }
        override fun saveDestination(ip: String, port: Int) {
            savedIp = ip
            savedPort = port.toString()
            savedCount++
        }
    }

    /** A browser that never delivers anything: the saved path must not browse. */
    private class SilentDiscovery : ServiceDiscovery {
        var startCount = 0
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount++
        }
        override fun stop() {}
    }

    private class FixedProbe(private val authenticated: Set<Pair<String, Int>>) : DiscoveryProbe {
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult =
            if (authenticated.contains(ip to port)) {
                DiscoveryPolicyBody
            } else {
                DiscoveryPolicyUnauthorized
            }

        private companion object {
            val DiscoveryPolicyBody: DiscoveryProbeResult = com.sayit.watch.net.DiscoveryPolicy
                .parseProbeResponse(
                    200,
                    """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
                )
            val DiscoveryPolicyUnauthorized: DiscoveryProbeResult = com.sayit.watch.net.DiscoveryPolicy
                .parseProbeResponse(401, """{"error":"unauthorized"}""")
        }
    }

    private class FastTiming : DiscoveryTiming {
        override val savedProbeTotalMs = 200L
        override val discoveryWindowMs = 300L
        override val savedProbeAttemptMs = 60L
        override val discoveryProbeAttemptMs = 60L
        override val pollIntervalMs = 10L
    }

    private class Harness(
        val settings: FakeSettings,
        val browser: SilentDiscovery,
        val probe: FixedProbe,
    ) {
        lateinit var coordinator: DiscoveryCoordinator
        lateinit var viewModel: RecordingViewModel

        fun build() {
            val logged = CategoryLog()
            coordinator = DiscoveryCoordinator(
                settings = settings,
                discovery = browser,
                probe = probe,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                probeDispatcher = Dispatchers.IO,
                timing = FastTiming(),
                diagnostics = logged,
            )
            viewModel = RecordingViewModel(
                settings = settings,
                session = RecordingSession(),
                capture = AudioCapture(),
                clientFactory = { null },
                context = null,
                discoveryBrowser = browser,
                discoveryProbe = probe,
                coordinatorOverride = coordinator,
            )
            this.log = logged
        }

        lateinit var log: CategoryLog
    }

    private fun harness(
        savedIp: String = savedPc.ip,
        authenticated: Set<Pair<String, Int>> = setOf(savedPc.ip to savedPc.port),
    ): Harness = Harness(
        FakeSettings(savedIp, if (savedIp.isEmpty()) "" else "18099", validToken),
        SilentDiscovery(),
        FixedProbe(authenticated),
    ).also { it.build() }

    // ── 必修 2: the real production chain, saved address ──────────────────────

    @Test
    fun `the saved-probe production path reaches the UI state but is never worded as a discovery`() {
        val h = harness()
        // The exact real-device input: a saved address and a live receiver.
        h.viewModel.onReadyEntered()
        waitForTarget(h, savedPc)

        val stages = h.log.snapshot()
        assertTrue(
            "the saved probe must have authenticated: $stages",
            stages.contains(DiscoveryDiagnostic.PROBE_AUTHENTICATED),
        )
        assertTrue(
            "this must be the saved-address path: $stages",
            stages.contains(DiscoveryDiagnostic.SAVED_PROBE_ACCEPTED),
        )
        assertFalse(
            "no browse may have happened on this path: $stages",
            stages.contains(DiscoveryDiagnostic.BROWSE_STARTED),
        )
        assertEquals("the saved path must not open a browse", 0, h.browser.startCount)

        // (a) Documenting WHY R5's pure-function test was not enough: the production
        // chain normalises the saved-address verdict to Discovered.
        assertEquals(
            "the ViewModel currently publishes Discovered for the saved verdict",
            DiscoveryState.Discovered,
            h.viewModel.discovery.value,
        )
        assertTrue(h.viewModel.canRecord.value)

        // (b) ...and the Ready wording must STILL be neutral.
        val status = readyStatusTextRes(h.viewModel.discovery.value, h.viewModel.canRecord.value)
        assertEquals(R.string.discovery_saved_neutral, status)
        assertNotEquals(
            "'已自动发现电脑' must not be reachable for a saved-address re-probe",
            R.string.discovery_found,
            status,
        )
        // The rendered label is a resource lookup, so this is the on-screen text.
        assertNotEquals(
            "the neutral wording must not be the discovery claim",
            R.string.discovery_found,
            status,
        )
    }

    @Test
    fun `a manual target is not worded as an automatic discovery either`() {
        val h = harness(savedIp = "", authenticated = setOf(savedPc.ip to savedPc.port))
        // Drive the REAL manual path: Save & Apply with a manual address, whose probe
        // authenticates. This is the other success path that reaches the UI.
        h.viewModel.applySettings(savedPc.ip, savedPc.port.toString(), validToken)
        waitForTarget(h, savedPc)

        assertEquals(
            "the manual path also normalises to Discovered",
            DiscoveryState.Discovered,
            h.viewModel.discovery.value,
        )
        assertEquals(
            "a manual success must not claim an automatic discovery",
            R.string.discovery_saved_neutral,
            readyStatusTextRes(h.viewModel.discovery.value, h.viewModel.canRecord.value),
        )
        // And a failed/no-target fallback keeps naming the action.
        assertEquals(R.string.discovery_manual, readyStatusTextRes(DiscoveryState.ManualFallback, false))
    }

    @Test
    fun `the ViewModel never publishes ExistingAddress so a pure-function test alone is insufficient`() {
        // This is the R5 test gap, pinned as a fact rather than a comment: the state the
        // R5 test used is not the state the production chain produces.
        val h = harness()
        h.viewModel.onReadyEntered()
        waitForTarget(h, savedPc)
        assertNotEquals(
            "the saved verdict arrives as Discovered, not ExistingAddress",
            DiscoveryState.ExistingAddress,
            h.viewModel.discovery.value,
        )
        // Both states must nevertheless map to neutral wording.
        assertEquals(
            R.string.discovery_saved_neutral,
            readyStatusTextRes(DiscoveryState.ExistingAddress, true),
        )
        assertEquals(
            R.string.discovery_saved_neutral,
            readyStatusTextRes(DiscoveryState.Discovered, true),
        )
    }

    @Test
    fun `non-ready statuses keep their accurate wording`() {
        // Searching is an action, not a result.
        assertEquals(R.string.discovery_searching, readyStatusTextRes(DiscoveryState.Searching, false))
        assertEquals(R.string.discovery_searching, readyStatusTextRes(DiscoveryState.Searching, true))
        // No target yet.
        assertEquals(R.string.discovery_none_yet, readyStatusTextRes(DiscoveryState.Idle, false))
        assertEquals(R.string.discovery_awaiting, readyStatusTextRes(DiscoveryState.Discovered, false))
        assertEquals(R.string.discovery_awaiting, readyStatusTextRes(DiscoveryState.ExistingAddress, false))
        // Failure must name the next action, not claim success.
        assertEquals(R.string.discovery_manual, readyStatusTextRes(DiscoveryState.ManualFallback, false))
        assertEquals(R.string.discovery_manual, readyStatusTextRes(DiscoveryState.ManualFallback, true))

        // No reachable state may claim a completed automatic discovery while the UI
        // cannot prove one happened.
        for (state in listOf(
            DiscoveryState.Idle,
            DiscoveryState.Searching,
            DiscoveryState.ExistingAddress,
            DiscoveryState.Discovered,
            DiscoveryState.ManualFallback,
        )) {
            for (verified in listOf(false, true)) {
                assertNotEquals(
                    "state $state (verified=$verified) must not claim a discovery",
                    R.string.discovery_found,
                    readyStatusTextRes(state, verified),
                )
            }
        }
    }

    @Test
    fun `the Ready screen cannot render the discovery claim because no state maps to it`() {
        val ready = screenSource
            .substringAfter("private fun ReadyScreen(")
            .substringBefore("internal fun searchEntryAvailable")
        assertTrue(
            "the status line must go through the resource decision",
            ready.contains("readyStatusTextRes("),
        )
        assertFalse(
            "the Ready screen must not hardcode a discovery claim",
            ready.contains("discovery_found"),
        )
        // The mapping itself must not reach the claim. The KDoc above the function
        // explains the removed behaviour and mentions the resource by name, so only
        // the executable lines are inspected.
        val mapping = screenSource
            .substringAfter("internal fun readyStatusTextRes(")
            .substringBefore("internal fun discoveryLabel(")
            .lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "readyStatusTextRes must not map any branch to discovery_found",
            mapping.contains("discovery_found"),
        )
    }

    @Test
    fun `the neutral wording resource exists and avoids the discovery claim`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(
            "the neutral wording must exist",
            strings.contains("name=\"discovery_saved_neutral\""),
        )
        val neutral = strings.substringAfter("name=\"discovery_saved_neutral\">").substringBefore("<")
        assertTrue("neutral wording must not be empty", neutral.isNotBlank())
        assertFalse("neutral wording must not claim a discovery: $neutral", neutral.contains("自动发现"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun waitForTarget(h: Harness, expected: DiscoverySelection) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (h.viewModel.currentDestination() == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, h.viewModel.currentDestination())
    }
}
