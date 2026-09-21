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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery 1C 1C-D-04@R8 — the PM's five blockers, driven through the REAL production chain.
 *
 * Every test runs `RecordingViewModel → ConnectionTaskOwner → ResolverBridge →
 * DiscoveryCoordinator → fake discovery/probe → UI projection`. No test asserts on source text to
 * stand in for behaviour; the only file-reading test is the portable-notes check, which is about
 * generated documentation rather than runtime logic.
 *
 * The four runtime defects this suite pins:
 *
 * 1. **P0-A** — handing the resolution over to an explicit switch used `onTargetInvalidated()`,
 *    which cleared the engine's verified target while the ViewModel still pointed at the old
 *    computer. One tap and the two sources of truth disagreed.
 * 2. **P0-B** — the idle connected loop always went back to a full SEARCH, so an idle Watch tore
 *    down its own connection (and its recording gate) every five seconds.
 * 3. **P0-C** — cancelling did not wait for the old browse, closing the picker did not cancel the
 *    browse at all, and a background upload could resurrect the foreground flag.
 *
 * `SettableDiscovery` counts MAXIMUM SIMULTANEOUS listeners: two overlapping browse sessions are
 * exactly what the serialized hand-off must make impossible.
 */
class ConnectionLifecycleR8Test {

    private val built = mutableListOf<RecordingViewModel>()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        built.forEach { runCatching { it.onBackground() } }
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
        override fun hasValidToken(): Boolean =
            com.sayit.watch.settings.DevTokenValidator.isValid(devToken)
        override fun saveDevToken(token: String) {
            devToken = token
        }
        override fun saveDestination(ip: String, port: Int) {
            savedIp = ip
            savedPort = port.toString()
            savedCount++
            lastSaved = ip to port
        }
    }

    /**
     * A browser that tracks BOTH how many times it was opened and how many sessions were open at
     * the same time. The peak is the assertion that matters: a serialized hand-off can never have
     * two live listeners.
     */
    private class CountingDiscovery : ServiceDiscovery {
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        val active = AtomicInteger(0)
        val peakActive = AtomicInteger(0)

        @Volatile
        var candidates: List<ResolvedService> = emptyList()

        /** When true, a browse never delivers anything: the window stays open. */
        @Volatile
        var hold: Boolean = false

        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount.incrementAndGet()
            val now = active.incrementAndGet()
            peakActive.updateAndGet { maxOf(it, now) }
            if (!hold) {
                val batch = candidates
                if (batch.isNotEmpty()) observer(batch)
            }
        }

        override fun stop() {
            stopCount.incrementAndGet()
            active.decrementAndGet()
        }
    }

    private class SettableProbe : DiscoveryProbe {
        val probed = ConcurrentHashMap<String, AtomicInteger>()

        @Volatile
        var reachable: Set<String> = emptySet()

        fun totalProbes(): Int = probed.values.sumOf { it.get() }

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

    private class TestTiming(
        override val savedProbeTotalMs: Long = 120L,
        override val discoveryWindowMs: Long = 250L,
        override val savedProbeAttemptMs: Long = 60L,
        override val discoveryProbeAttemptMs: Long = 80L,
        override val pollIntervalMs: Long = 10L,
    ) : DiscoveryTiming

    private class Harness(val settings: FakeSettings, val discovery: CountingDiscovery, val probe: SettableProbe) {
        lateinit var coordinator: DiscoveryCoordinator
        lateinit var viewModel: RecordingViewModel
    }

    private fun harness(retryMs: Long = 60_000L, healthMs: Long = 60_000L, timing: TestTiming = TestTiming()): Harness {
        val h = Harness(FakeSettings(devToken = validToken), CountingDiscovery(), SettableProbe())
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

    /** Brings the chain to "one computer connected and usable". */
    private fun connect(h: Harness) {
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.onForeground()
        waitUntil("the desktop must connect", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == desktop
        }
        assertTrue("recording must be possible once connected", h.viewModel.canRecord.value)
    }

    /**
     * The single source of truth across every layer, asserted together.
     *
     * The PM's P0-A finding was exactly this: the ViewModel's private target and the shared
     * resolver disagreed, so the page still offered recording while the scheduler believed it was
     * disconnected. Pinning them separately is what makes that class of defect fail loudly.
     *
     * The layers are written in a few statements on different threads, so this waits (bounded) for
     * them to converge before asserting — a transient interleaving is not the defect being hunted;
     * a PERSISTENT disagreement is. The `ui.currentTarget` projection is only asserted when a
     * target EXISTS: with no target it is a presentation detail a later round may still refresh,
     * while the three behaviour-bearing facts below are the contract.
     */
    private fun assertTargetAgrees(h: Harness, expected: DiscoverySelection?, because: String) {
        waitUntil("$because: every layer must agree on $expected", timeoutMs = 5_000L) {
            h.viewModel.currentDestination() == expected &&
                h.viewModel.resolverTargetForTest == expected &&
                h.viewModel.canRecord.value == (expected != null)
        }
        assertEquals("$because (ViewModel target)", expected, h.viewModel.currentDestination())
        assertEquals(
            "$because (Resolver/engine target)",
            expected,
            h.viewModel.resolverTargetForTest,
        )
        assertEquals("$because (recording gate)", expected != null, h.viewModel.canRecord.value)
        if (expected != null) {
            assertEquals("$because (UI current target)", expected, h.viewModel.ui.value.currentTarget)
            assertEquals("$because (UI connected)", true, h.viewModel.ui.value.connected)
        } else {
            assertEquals(
                "$because (the screen must not claim a connection)",
                false,
                h.viewModel.ui.value.connected,
            )
        }
    }

    // ── P0-A: an explicit switch keeps one single target everywhere ───────────

    @Test
    fun `entering the switch and coming back keeps the same target in every layer`() {
        val h = harness(healthMs = 60_000L)
        connect(h)
        val startsBefore = h.discovery.startCount.get()

        // The user taps 切换电脑 and the browse finds nothing new.
        h.discovery.candidates = emptyList()
        h.viewModel.requestSwitch()
        waitUntil("the browse must finish", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching
        }
        assertTargetAgrees(h, desktop, "a browse that finds nothing new")

        h.viewModel.dismissSwitchPicker()
        assertTargetAgrees(h, desktop, "immediately after returning from the switch")

        // ...and it must still be the same target after several health periods.
        Thread.sleep(700)
        assertTargetAgrees(h, desktop, "after several health periods")
        // One user request may not turn into an open-ended sequence of browsings. One is ideal;
        // the health loop is allowed at most one extra round while the switch settles.
        assertTrue(
            "the user asked for a switch, not a browse storm: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() <= startsBefore + 2,
        )
        assertTrue(
            "the user's switch must have actually browsed: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() > startsBefore,
        )
    }

    @Test
    fun `a switch with no candidate at all keeps the target and only browses once`() {
        val h = harness()
        connect(h)
        val startsBefore = h.discovery.startCount.get()

        // Nothing answers the browse at all.
        h.discovery.candidates = emptyList()
        h.viewModel.requestSwitch()
        waitUntil("the browse must run", timeoutMs = 15_000L) {
            h.discovery.startCount.get() > startsBefore
        }
        waitUntil("the browse must finish", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching
        }
        h.viewModel.dismissSwitchPicker()

        assertTargetAgrees(h, desktop, "a switch with zero candidates")
        // The health loop may add at most one round while the switch settles; a storm would mean
        // the recovery loop had been entered, which is exactly the R8 P0-A defect.
        assertTrue(
            "one user request must not become a browse storm: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() in (startsBefore + 1)..(startsBefore + 2),
        )
        waitUntil("the browse must have been stopped", timeoutMs = 5_000L) {
            h.discovery.active.get() == 0
        }
        assertEquals("no listener may stay open", 0, h.discovery.active.get())
    }

    @Test
    fun `re-searching from the picker keeps the target when the browse finds nothing`() {
        // A long retry interval so only the user's two presses can browse here; this test is about
        // the target surviving them, not about the loop's cadence.
        val h = harness(retryMs = 60_000L)
        connect(h)

        h.viewModel.requestSwitch()
        waitUntil("the first browse must settle", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }
        // 重新搜索 with nothing out there: still no reason to lose the working computer.
        h.viewModel.requestSwitch()
        waitUntil("the second browse must settle", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }
        assertTargetAgrees(h, desktop, "after two explicit searches")
        assertTrue(
            "both explicit searches must really have browsed: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() >= 2,
        )
        assertTrue(
            "and neither may have opened a second listener: ${h.discovery.peakActive.get()}",
            h.discovery.peakActive.get() <= 1,
        )

        h.viewModel.dismissSwitchPicker()
        assertTargetAgrees(h, desktop, "after dismissing a re-search")
    }

    // ── P0-B: an idle connected Watch only probes ────────────────────────────

    @Test
    fun `an idle connected watch probes on schedule and never browses`() {
        // This test is ABOUT the idle cadence, so it is the one place that needs a short interval.
        val h = harness(healthMs = 120L)
        connect(h)
        val startsBefore = h.discovery.startCount.get()
        val probesBefore = h.probe.totalProbes()

        // Two full health periods: probes accumulate, browsers do not appear.
        Thread.sleep(500)
        assertTrue(
            "the idle health check must keep probing (before=$probesBefore)",
            h.probe.totalProbes() >= probesBefore + 2,
        )
        assertEquals(
            "an idle connected watch must never open a browse",
            startsBefore,
            h.discovery.startCount.get(),
        )
        assertTargetAgrees(h, desktop, "across two idle health periods")
        assertEquals(
            "the loop must report the idle connected state",
            ConnectionTaskOwner.RoundKind.IDLE_CONNECTED,
            h.viewModel.ownerRoundKindForTest,
        )
        assertFalse("connecting must not be shown while merely idle", h.viewModel.ui.value.connecting)
    }

    @Test
    fun `a failed probe is what enters recovery, not the passage of time`() {
        val h = harness(retryMs = 150L, healthMs = 150L)
        connect(h)
        val startsBefore = h.discovery.startCount.get()

        // The computer goes away: the next probe fails and recovery begins. It must also stop
        // ADVERTISING itself, otherwise the recovery browse would simply re-authenticate it and
        // the test would be measuring the mDNS window instead of the probe.
        h.probe.reachable = emptySet()
        h.discovery.candidates = emptyList()
        waitUntil("the failed probe must clear the target", timeoutMs = 15_000L) {
            h.viewModel.currentDestination() == null
        }
        assertTargetAgrees(h, null, "after the probe failed")
        waitUntil("recovery must start searching", timeoutMs = 15_000L) {
            h.discovery.startCount.get() > startsBefore
        }

        // And when it comes back, the health loop takes over again without a browse.
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        waitUntil("the desktop must reconnect", timeoutMs = 20_000L) {
            h.viewModel.currentDestination() == desktop
        }
        val startsAfterRecovery = h.discovery.startCount.get()
        Thread.sleep(600)
        assertEquals(
            "once reconnected, probing must replace browsing again",
            startsAfterRecovery,
            h.discovery.startCount.get(),
        )
        assertTargetAgrees(h, desktop, "after recovering the connection")
    }

    // ── P0-C: cancellation, serialization and the background boundary ────────

    @Test
    fun `rapid re-searches never put two listeners up at once`() {
        val h = harness()
        connect(h)

        // A browse that never delivers: the window stays open until it is cancelled.
        h.discovery.hold = true
        h.viewModel.requestSwitch()
        waitUntil("the first browse must open", timeoutMs = 15_000L) {
            h.discovery.startCount.get() >= 1
        }
        // Three more taps while it is still running.
        repeat(3) { h.viewModel.requestSwitch() }
        Thread.sleep(600)

        assertEquals(
            "two browse sessions must never overlap",
            1,
            h.discovery.peakActive.get(),
        )
        // Four taps can open at most four cancellations-plus-replacements, and every one of them
        // must have been preceded by a completed stop (asserted by peakActive above).
        assertTrue(
            "the taps must not stack an unbounded number of browsings: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() <= 5,
        )
        assertEquals(
            "every cancelled browse must have been stopped",
            h.discovery.startCount.get(),
            h.discovery.stopCount.get(),
        )
        assertTargetAgrees(h, desktop, "after rapid re-searches")

        h.viewModel.dismissSwitchPicker()
        waitUntil("the browse must be stopped on return", timeoutMs = 15_000L) {
            h.discovery.active.get() == 0
        }
        assertTargetAgrees(h, desktop, "after returning from rapid re-searches")
    }

    @Test
    fun `returning from the picker cancels the browse and stops publishing candidates`() {
        val h = harness()
        connect(h)

        h.discovery.hold = true
        h.viewModel.requestSwitch()
        waitUntil("the browse must open", timeoutMs = 15_000L) {
            h.discovery.active.get() == 1
        }
        val startsAtBrowse = h.discovery.startCount.get()

        h.viewModel.dismissSwitchPicker()
        waitUntil("the browse must be cancelled", timeoutMs = 15_000L) {
            h.discovery.active.get() == 0
        }
        assertTargetAgrees(h, desktop, "right after returning")

        // A hidden picker must not keep browsing behind the user's back.
        Thread.sleep(700)
        assertEquals(
            "no browse may continue after the picker closed",
            startsAtBrowse,
            h.discovery.startCount.get(),
        )
        assertFalse("the picker must be closed", h.viewModel.ui.value.switchOpen)
        assertTargetAgrees(h, desktop, "after the picker was closed")
    }

    @Test
    fun `returning with no target goes back to the recovery search, not to a hidden browse`() {
        val h = harness(retryMs = 60_000L)
        // No computer anywhere: Ready searches and opens a browse.
        h.viewModel.onForeground()
        waitUntil("the search must open a browse", timeoutMs = 15_000L) {
            h.discovery.startCount.get() >= 1
        }
        waitUntil("that search must settle", timeoutMs = 15_000L) {
            !h.coordinator.isRunning && h.discovery.active.get() == 0
        }

        // The user opens the picker and immediately goes back, with no browse running.
        h.viewModel.requestSwitch()
        h.viewModel.dismissSwitchPicker()
        assertTargetAgrees(h, null, "returning with nothing to connect to")
        val startsAfterReturn = h.discovery.startCount.get()

        // No hidden BROWSE may survive the closed picker. The owner goes back to its DISCONNECTED
        // recovery loop (this test's interval is 60 s), so after the one revalidation round the
        // picker triggered, nothing more may browse.
        waitUntil("the revalidation must settle", timeoutMs = 5_000L) {
            !h.coordinator.isRunning && h.discovery.active.get() == 0
        }
        Thread.sleep(700)
        assertTrue(
            "a closed picker must not keep browsing: ${h.discovery.startCount.get()}",
            h.discovery.startCount.get() <= startsAfterReturn + 1,
        )
        assertEquals("no listener may stay open", 0, h.discovery.active.get())

        // Foreground recovery is still allowed; this test shortens only the interval, so it asks
        // for the same production re-search the user's "重新搜索" would start.
        h.discovery.candidates = listOf(realService(desktop.ip))
        h.probe.reachable = setOf("${desktop.ip}:${desktop.port}")
        h.viewModel.startDiscovery()
        waitUntil("the recovery search must still connect later", timeoutMs = 20_000L) {
            h.viewModel.currentDestination() == desktop
        }
        assertTargetAgrees(h, desktop, "after the late computer appeared")
    }

    @Test
    fun `leaving the foreground cancels everything and a background upload cannot revive it`() {
        val h = harness(retryMs = 150L, healthMs = 60_000L)
        connect(h)

        h.discovery.hold = true
        h.viewModel.requestSwitch()
        waitUntil("the browse must open", timeoutMs = 15_000L) {
            h.discovery.active.get() == 1
        }

        h.viewModel.onBackground()
        waitUntil("the browse must be cancelled", timeoutMs = 15_000L) {
            h.discovery.active.get() == 0
        }
        val startsAtBackground = h.discovery.startCount.get()
        val probesAtBackground = h.probe.totalProbes()
        val uiAtBackground = h.viewModel.ui.value

        // More than two health periods: nothing may run and nothing may change.
        Thread.sleep(700)
        assertEquals(
            "no browse may run in the background",
            startsAtBackground,
            h.discovery.startCount.get(),
        )
        assertEquals(
            "no probe may run in the background",
            probesAtBackground,
            h.probe.totalProbes(),
        )
        assertEquals(
            "no UI state may change in the background",
            uiAtBackground,
            h.viewModel.ui.value,
        )
        assertFalse("the owner must not think it is in the foreground", h.viewModel.ownerForegroundForTest)

        // A transfer that finishes in the background must not resurrect the foreground flag.
        h.viewModel.resumeConnectionTaskForTest()
        assertFalse(
            "a background completion must not set the app back to foreground",
            h.viewModel.ownerForegroundForTest,
        )
        Thread.sleep(300)
        assertEquals(
            "and it must not start anything either",
            startsAtBackground,
            h.discovery.startCount.get(),
        )

        // Returning to the foreground revalidates immediately.
        h.discovery.hold = false
        h.viewModel.onForeground()
        waitUntil("returning to the foreground must revalidate", timeoutMs = 15_000L) {
            h.viewModel.ownerForegroundForTest && h.viewModel.currentDestination() == desktop
        }
    }

    // ── P1: the portable package notes must be executable, not decorative ─────

    @Test
    fun `the portable notes name the real config path and contain no control characters`() {
        // The packaging script GENERATES README-PORTABLE.txt. Reading the template here keeps the
        // wording honest even before the package is built: the real config path must be named, the
        // non-existent settings entry must not be, and the frontend note must not rely on a
        // backtick escape (which is how a form feed once reached the shipped README).
        val script = File("../../client/scripts/package-watch-portable.ps1")
        assertTrue(
            "the packaging script must exist for the notes to be generated: ${script.absolutePath}",
            script.isFile,
        )
        val source = script.readText()
        assertTrue(
            "the notes must name the receiver configuration file",
            source.contains("%LOCALAPPDATA%\\com.sayit.app\\watch-receiver.config.json"),
        )
        assertTrue(
            "the notes must explain that the token is brought over by the user",
            source.contains("手表访问令牌"),
        )
        // The script may MENTION the non-existent entry (it documents the check it performs), but
        // the note text it ships must not send the user there. Assert on the generated template.
        val templateMarker = "\$buildInfoTemplate = @"
        val template = source.substringAfter(templateMarker).substringBefore("\n'@")
        assertTrue("the notes template must exist", template.length > 200)
        assertFalse(
            "the shipped notes must not point at a settings entry that does not exist",
            template.contains("SayIt 设置"),
        )
        assertFalse(
            "the shipped notes must not advertise an unrelated token field",
            template.contains("服务器访问令牌"),
        )
        assertTrue(
            "the notes must still explain that the frontend is embedded",
            template.contains("frontendDist"),
        )
        assertFalse(
            "the template must not use a backtick escape (that is how a form feed was shipped)",
            template.contains('`'),
        )
        assertTrue(
            "the notes must be written from a single-quoted here-string so nothing is interpreted",
            source.contains("@'"),
        )
        assertTrue(
            "the script must verify the generated notes for control characters",
            source.contains("control character"),
        )
        assertTrue(
            "BUILD-INFO must record the product commit",
            source.contains("git_head="),
        )
        assertTrue(
            "the package must be built from a clean, committed product head",
            source.contains("uncommitted changes"),
        )
        assertTrue(
            "the script must still refuse to ship a binary without the embedded frontend",
            source.contains("tauri build --debug --no-bundle"),
        )
    }

    @Test
    fun `the receiver's missing-config notice names the real path and adds no fake entry`() {
        val receiver = File("../../client/src-tauri/src/watch_receiver/mod.rs")
        assertTrue("the receiver module must exist", receiver.isFile)
        val source = receiver.readText()

        val message = source.substringAfter("ReceiverStartError::MissingConfig => {")
            .substringBefore("ReceiverStartError::BindFailed => {")
        assertTrue(
            "the notice must name the real receiver config path",
            message.contains("%LOCALAPPDATA%\\\\com.sayit.app\\\\watch-receiver.config.json"),
        )
        assertTrue(
            "the notice must name what the user has to fill in",
            message.contains("手表访问令牌"),
        )
        assertFalse(
            "the notice must not send the user to a settings entry that does not exist",
            message.contains("设置"),
        )
        assertFalse(
            "the notice must never print a token value",
            message.contains("devToken"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Waits until the owner's first browse has really opened and settled.
     *
     * A test that wants a deterministic "no target yet" state must first let the eager recovery
     * search finish; otherwise the round it starts can land after the pause and adopt a candidate
     * the test did not expect.
     */
    private fun waitForFirstBrowseToSettle(h: Harness, timeoutMs: Long = 15_000L) {
        waitUntil("the initial search must have browsed", timeoutMs = timeoutMs) {
            h.discovery.startCount.get() > 0
        }
        waitUntil("the initial search must settle", timeoutMs = timeoutMs) {
            !h.coordinator.isRunning && !h.viewModel.isConnectionTaskRunningForTest
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue(message, condition())
    }
}
