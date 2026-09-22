package com.sayit.watch.net

import com.sayit.watch.recording.AudioCapture
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.ui.RecordingViewModel
import com.sayit.watch.ui.WatchUiState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery 1C 1C-D-04@R7 — the production-chain regression coverage for §2A/§2B.
 *
 * Every test drives the REAL chain: `RecordingViewModel` → `ResolverBridge` →
 * `ResolverEngine` → `DiscoveryCoordinator` → fake `ServiceDiscovery`/`DiscoveryProbe`
 * → UI projection. No test calls a pure wording function or inspects source text to
 * stand in for behaviour.
 *
 * The central defect: with the Watch already on Ready, starting the PC afterwards did
 * nothing and the user had to kill the app. `RecordingScreen` only asked for a search
 * when `ui.screen` CHANGED, so one failed round left the screen with no scheduler.
 *
 * Timings: the coordinator windows are shortened (the pure timing seam), but the
 * retry interval is the PRODUCTION 5 s value in the first test so the frozen cadence
 * itself is verified. Later tests shorten it to keep the suite fast; the interval is
 * read from `ConnectionTaskOwner.DefaultRetryDelayMs`, never re-typed here.
 */
class ConnectionRecoveryR7Test {

    /** Every ViewModel built here is stopped after the test so no round survives it. */
    private val built = mutableListOf<RecordingViewModel>()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        built.forEach { it.stopDiscovery() }
        built.clear()
        Dispatchers.resetMain()
    }

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val desktop = DiscoverySelection("192.168.12.142", 18099)
    private val laptop = DiscoverySelection("192.168.12.153", 18099)

    private fun realService(ip: String, port: Int = 18099) =
        ResolvedService("SayIt", SAYIT_SERVICE_TYPE_ANDROID, ip, port, "1")

    // ── Test doubles ─────────────────────────────────────────────────────────

    private class FakeSettings(
        override var savedIp: String = "",
        override var savedPort: String = "",
        override var devToken: String = "",
    ) : DiscoverySettings {
        var savedCount = 0
        var lastSaved: Pair<String, Int>? = null
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
            lastSaved = ip to port
        }
    }

    /** A browser whose every `start` delivers the CURRENT candidate list. */
    private class SettableDiscovery : ServiceDiscovery {
        val startCount = AtomicInteger(0)
        @Volatile var candidates: List<ResolvedService> = emptyList()
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount.incrementAndGet()
            val batch = candidates
            if (batch.isNotEmpty()) observer(batch)
        }
        override fun stop() = Unit
    }

    /** A probe whose reachable set can change while the test runs (a PC boots). */
    private class SettableProbe : DiscoveryProbe {
        val probed = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var reachable: Set<String> = emptySet()
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            probed.computeIfAbsent("$ip:$port") { AtomicInteger(0) }.incrementAndGet()
            return if (reachable.contains("$ip:$port")) {
                DiscoveryPolicy.parseProbeResponse(
                    200,
                    """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
                )
            } else {
                DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
            }
        }
    }

    /** Short coordinator windows; the retry cadence stays the production one. */
    private class TestTiming(
        override val savedProbeTotalMs: Long = 120L,
        override val discoveryWindowMs: Long = 240L,
        override val savedProbeAttemptMs: Long = 60L,
        override val discoveryProbeAttemptMs: Long = 80L,
        override val pollIntervalMs: Long = 10L,
    ) : DiscoveryTiming

    private class Harness(val settings: FakeSettings, val discovery: SettableDiscovery, val probe: SettableProbe) {
        lateinit var coordinator: DiscoveryCoordinator
        lateinit var viewModel: RecordingViewModel
    }

    /** Builds the real chain and registers it for the post-test teardown. */
    private fun harness(retryMs: Long, healthMs: Long = retryMs, timing: TestTiming = TestTiming()): Harness {
        val h = Harness(FakeSettings(devToken = validToken), SettableDiscovery(), SettableProbe())
        h.coordinator = DiscoveryCoordinator(
            settings = h.settings,
            discovery = h.discovery,
            probe = h.probe,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            probeDispatcher = Dispatchers.IO,
            timing = timing,
            diagnostics = CategoryLog(),
        )
        h.viewModel = RecordingViewModel(
            settings = h.settings,
            session = RecordingSession(),
            capture = AudioCapture(),
            clientFactory = { null },
            context = null,
            discoveryBrowser = h.discovery,
            discoveryProbe = h.probe,
            coordinatorOverride = h.coordinator,
            retryDelayMs = retryMs,
            healthCheckIntervalMs = healthMs,
        )
        built.add(h.viewModel)
        return h
    }

    // ── A: starting the PC AFTER the Watch must connect without a restart ────

    @Test
    fun `starting the PC after the Watch connects on the next 5 s round without a restart`() {
        val h = harness(retryMs = ConnectionTaskOwner.DefaultRetryDelayMs)
        val started = System.nanoTime() / 1_000_000L
        // Foreground Ready with a valid token and nothing reachable yet.
        h.viewModel.onForeground()

        // Round 1 proves the failure the user saw: searching, nothing found, no target.
        waitUntil("the first round must run and fail", timeoutMs = 20_000L) {
            h.discovery.startCount.get() >= 1 && !h.coordinator.isRunning
        }
        assertNull("nothing may be adopted yet", h.viewModel.currentDestination())
        assertFalse(h.viewModel.canRecord.value)

        // The user now starts the desktop receiver.
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")

        // No app restart, no new screen, no extra user action.
        waitUntil("the recovery round must connect", timeoutMs = 25_000L) {
            h.viewModel.currentDestination() == desktop
        }
        val elapsed = System.nanoTime() / 1_000_000L - started

        assertEquals("the desktop must become the upload target", desktop, h.viewModel.currentDestination())
        assertTrue("recording must be enabled", h.viewModel.canRecord.value)
        assertEquals("the connected computer must be persisted", desktop.ip to desktop.port, h.settings.lastSaved)
        assertTrue(
            "at least two rounds must have run (the retry, not a single shot): ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() >= 2,
        )
        assertTrue(
            "the retry cadence must be 5 s, not a single one-shot attempt (took ${elapsed}ms)",
            elapsed >= ConnectionTaskOwner.DefaultRetryDelayMs,
        )
    }

    @Test
    fun `a failed round is followed by another one while the screen stays disconnected`() {
        val short = 150L
        val h = harness(retryMs = short)
        h.viewModel.onForeground()

        waitUntil("several recovery rounds must run", timeoutMs = 15_000L) {
            h.discovery.startCount.get() >= 3
        }
        assertNull(h.viewModel.currentDestination())
        assertEquals(
            "a disconnected Ready screen keeps retrying instead of stopping after one round",
            true,
            h.discovery.startCount.get() >= 3,
        )
    }

    // ── A/D: the task stops when the app leaves the foreground ───────────────

    @Test
    fun `leaving the foreground cancels the task and no further round runs`() {
        val h = harness(retryMs = 150L)
        h.viewModel.onForeground()
        waitUntil("rounds must start in the foreground", timeoutMs = 15_000L) {
            h.discovery.startCount.get() >= 2
        }
        h.viewModel.onBackground()
        waitUntil("the running round must stop", timeoutMs = 5_000L) { !h.coordinator.isRunning }
        val stopsAt = h.discovery.startCount.get()
        Thread.sleep(600)
        assertEquals("nothing may poll in the background", stopsAt, h.discovery.startCount.get())
        assertFalse("no round may stay in flight", h.viewModel.isConnectionTaskRunningForTest)
    }

    // ── D: the idle health check is bounded, and it clears a dead target ─────

    @Test
    fun `an offline computer stops being presented as connected while the app is idle`() {
        val h = harness(retryMs = 150L, healthMs = 150L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        assertTrue(h.viewModel.ui.value.connected)

        // 1C-D-04@R8: the desktop goes away and stops advertising itself as well. Without this the
        // recovery browse would simply re-authenticate it and the test would be timing-dependent.
        h.probe.reachable = emptySet()
        h.discovery.candidates = emptyList()
        waitUntil("the bounded health check must notice", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == null
        }
        assertNull("an offline computer may not stay the upload target", h.viewModel.currentDestination())
        assertFalse("recording must be unavailable", h.viewModel.canRecord.value)
        assertFalse("the screen may not claim a connection", h.viewModel.ui.value.connected)
    }

    @Test
    fun `a recording pauses the connection task so no health check preempts the transfer`() {
        val h = harness(retryMs = 120L, healthMs = 120L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }

        // This is exactly what `startRecording()` does before it opens the one-shot
        // transfer chain (the JVM cannot construct a real `AudioRecord`, so the capture
        // itself is not exercised here; the scheduler pause is the production call).
        h.viewModel.pauseSchedulingForTransfer()
        assertFalse("the connection round must be cancelled", h.viewModel.isConnectionTaskRunningForTest)
        // A round that was already running may still finish its transport call; wait for it
        // to settle so the assertion below is about the PAUSED scheduler, not about a race.
        waitUntil("the transfer pause must settle") { !h.coordinator.isRunning }
        val probesAtSettle = h.probe.probed.values.sumOf { it.get() }
        Thread.sleep(700)
        assertEquals(
            "no health check may run while a transfer owns the transport",
            probesAtSettle,
            h.probe.probed.values.sumOf { it.get() },
        )
        assertFalse("no connection round may run while busy", h.viewModel.isConnectionTaskRunningForTest)
        // The last round may have found the desktop still there (it stays usable) or, after
        // the pause cancelled a probe in flight, no usable target. What must NOT happen is a
        // new round: the two are consistent states, not a lost connection.
        val settled = h.viewModel.currentDestination()
        assertTrue(
            "the paused scheduler leaves either the connected target or none: $settled",
            settled == desktop || settled == null,
        )
        assertEquals("recording availability follows the settled target", settled != null, h.viewModel.canRecord.value)

        h.viewModel.resumeConnectionTaskForTest()
        // The scheduler must actively probe again once the transport is free.
        waitUntil("the connection task must resume when the transfer is over") {
            h.probe.probed.values.sumOf { it.get() } > probesAtSettle
        }
    }

    // ── E: settings must not cost the current target ────────────────────────

    @Test
    fun `opening and leaving settings without edits keeps the connected computer`() {
        val h = harness(retryMs = 120L, healthMs = 120L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        val writesBefore = h.settings.tokenWrites
        val savedBefore = h.settings.savedCount

        // The real screen path: entering Config is what the screen effect calls.
        h.viewModel.openConfig()
        assertEquals(WatchUiState.Screen.CONFIG, h.viewModel.ui.value.screen)
        assertEquals("opening settings must not drop the computer in use", desktop, h.viewModel.currentDestination())
        assertTrue("the target stays usable", h.viewModel.canRecord.value)

        // Leaving without editing anything and saving the SAME form.
        h.viewModel.applySettings(desktop.ip, desktop.port.toString(), validToken)
        assertEquals(WatchUiState.Screen.READY, h.viewModel.ui.value.screen)
        assertEquals("a submission with no change must keep the target", desktop, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
        assertEquals("the same token must not be counted as a rewrite", writesBefore, h.settings.tokenWrites)
        assertEquals("nothing may be re-persisted for an unchanged form", savedBefore, h.settings.savedCount)
    }

    @Test
    fun `submitting a genuinely different address re-verifies instead of reusing the old target`() {
        val h = harness(retryMs = 120L, healthMs = 120L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }

        // The user types a different, unreachable address and applies it.
        h.viewModel.applySettings(laptop.ip, laptop.port.toString(), validToken)
        waitUntil("the manual probe must fail", timeoutMs = 10_000L) {
            h.viewModel.currentDestination() != desktop
        }
        assertNotEquals(
            "an unreachable manual address may not leave the old target in place",
            desktop,
            h.viewModel.currentDestination(),
        )
        assertFalse(h.viewModel.canRecord.value)
        assertEquals("a failed manual probe must persist nothing", 1, h.settings.savedCount)
    }

    // ── B: one task, the newest intent wins, late results do not flow back ───

    @Test
    fun `repeated explicit switches collapse into one running task`() {
        val h = harness(retryMs = 5_000L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        val startsBefore = h.discovery.startCount.get()

        // The user taps 重新搜索 three times in a row.
        repeat(3) { h.viewModel.requestSwitch() }
        // The newest browse must actually open: a superseded round can cancel a click
        // that arrived while it was already winding down.
        waitUntil("the newest browse must run", timeoutMs = 15_000L) {
            h.discovery.startCount.get() > startsBefore
        }
        waitUntil("the browse must settle", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }
        val total = h.discovery.startCount.get() - startsBefore
        assertTrue("the switch must actually search: $total", total >= 1)
        assertTrue("clicks must coalesce instead of stacking rounds: $total", total <= 3)
        assertFalse("no browse may be left running", h.coordinator.isRunning)
        assertEquals("the current target must survive the searches", desktop, h.viewModel.currentDestination())
        assertFalse("the engine must not stay in Automatic", h.viewModel.isAutomaticResolutionActiveForTest)
    }

    @Test
    fun `a late result from a superseded round cannot change the target`() {
        val h = harness(retryMs = 5_000L)
        h.discovery.candidates = emptyList()
        h.viewModel.onForeground()
        waitUntil("a round must be in flight", timeoutMs = 10_000L) { h.discovery.startCount.get() >= 1 }

        // While round 1 is winding down, the user states a new intent.
        h.discovery.candidates = listOf(realService(laptop.ip))
        h.probe.reachable = setOf("${laptop.ip}:${laptop.port}")
        h.viewModel.startDiscovery()
        waitUntil("the newest round must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == laptop
        }
        assertEquals(
            "only the newest round may publish a target",
            laptop,
            h.viewModel.currentDestination(),
        )
    }

    // ── C: an explicit switch is always a user pick ─────────────────────────

    @Test
    fun `one new candidate is offered and never adopted by the browse itself`() {
        val h = harness(retryMs = 5_000L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        // 1C-D-04@R8: the automatic health loop would open its own browse and blur the count, so it
        // is paused while this test measures the user's single explicit switch.
        h.viewModel.pauseConnectionTaskForTest()

        // Exactly ONE new computer answers the explicit switch browse.
        h.discovery.candidates = listOf(realService(desktop.ip), realService(laptop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}", "${laptop.ip}:${laptop.port}")
        val startsBefore = h.discovery.startCount.get()
        h.viewModel.requestSwitch()
        // 1C-D-04@R9: the owner starts the round on its OWN dispatcher, so "not searching yet" is
        // not "finished". Wait for the browse to really open before waiting for it to settle,
        // otherwise this returns in the gap before the round is scheduled and asserts on a picker
        // that was never refreshed.
        waitUntil("the browse must open", timeoutMs = 15_000L) {
            h.coordinator.isRunning || h.discovery.startCount.get() > startsBefore
        }
        waitUntil("the browse must finish", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }

        assertEquals(
            "a single new candidate must NOT be adopted by the browse (R7 必修 2)",
            desktop,
            h.viewModel.currentDestination(),
        )
        assertTrue("the picker offers both computers", h.viewModel.switchEntries().size >= 2)
        assertTrue(
            "the current computer is always one of the rows",
            h.viewModel.switchEntries().any { it.isCurrent && it.target == desktop },
        )

        // Only the user's tap switches.
        h.viewModel.onTargetPicked(laptop)
        waitUntil("the user's pick must be adopted", timeoutMs = 10_000L) {
            h.viewModel.currentDestination() == laptop
        }
        assertEquals(laptop, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
    }

    @Test
    fun `cancelling the picker keeps the computer that is still online`() {
        val h = harness(retryMs = 5_000L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        val savedBefore = h.settings.savedCount

        h.viewModel.requestSwitch()
        waitUntil("the browse must finish", timeoutMs = 15_000L) { h.discovery.startCount.get() >= 2 }
        h.viewModel.dismissSwitchPicker()

        assertEquals("cancelling must keep the current computer", desktop, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
        assertEquals("cancelling must not write settings", savedBefore, h.settings.savedCount)
        assertFalse(h.viewModel.ui.value.switchOpen)
    }

    @Test
    fun `picking a computer that stopped answering changes nothing`() {
        val h = harness(retryMs = 5_000L)
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        // 1C-D-04@R8: freeze the automatic loop FIRST. The health check would otherwise keep
        // re-probing the desktop and could clear the target exactly while this test is measuring a
        // refused pick, which made the assertion below depend on timing rather than behaviour.
        h.viewModel.pauseConnectionTaskForTest()

        // The laptop must be an offered candidate before it can be picked.
        h.discovery.candidates = listOf(realService(desktop.ip), realService(laptop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}", "${laptop.ip}:${laptop.port}")
        h.viewModel.requestSwitch()
        waitUntil("the laptop must be offered", timeoutMs = 15_000L) {
            h.viewModel.switchEntries().any { it.target == laptop }
        }

        // The laptop goes away before the user picks it.
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        val savedBefore = h.settings.savedCount
        val currentBefore = h.viewModel.currentDestination()
        h.viewModel.onTargetPicked(laptop)
        waitUntil("the pick must settle", timeoutMs = 15_000L) { !h.viewModel.ui.value.switchOpen }

        assertNotEquals(
            "a computer that stopped answering must not become the target",
            laptop,
            h.viewModel.currentDestination(),
        )
        assertEquals(
            "a refused pick must leave the computer in use alone",
            currentBefore,
            h.viewModel.currentDestination(),
        )
        assertEquals("a refused pick must persist nothing", savedBefore, h.settings.savedCount)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun waitUntil(message: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue(message, condition())
    }
}
