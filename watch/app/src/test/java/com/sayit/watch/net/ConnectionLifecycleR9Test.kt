package com.sayit.watch.net

import com.sayit.watch.recording.AudioCapture
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.ui.RecordingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery 1C 1C-D-04@R9 — the PM's blockers, driven through the REAL production chain.
 *
 * Every test runs `RecordingViewModel → ConnectionTaskOwner → ResolverBridge →
 * DiscoveryCoordinator → fake discovery/probe → UI projection`, and every one of them is about a
 * defect R8 shipped:
 *
 * 1. **P0-A** — the health probe still ran under `withContext(NonCancellable)`, so a probe that was
 *    already in flight kept running after `onBackground()` and could clear the target and refresh
 *    the UI afterwards. R9 makes the probe cancellable and gates every consequence on the round
 *    still being the current, foreground one.
 * 2. **P0-B** — `joinBounded()` ran `runBlocking` on the UI thread and, after its 1.5 s timeout,
 *    started the replacement round anyway, so two listeners could exist at once and a tap could
 *    freeze the UI. R9 replaces it with one command loop that cancels and JOINS the old round —
 *    with no timeout — before the next one starts, and that never blocks the caller.
 * 3. **P0-C** — the "several health periods" case was asserted with a 60 s interval and a 700 ms
 *    sleep, so no health period was ever crossed. R9 crosses at least two REAL short periods.
 * 4. **P1** — the notes test asserted a Chinese phrase in the script's source text while the
 *    shipped notes are English, so the delivery head was red. R9 runs the packaging script and
 *    asserts on the README it really generates.
 *
 * `ControllableProbe` blocks inside `probe()` the way a real socket call does: it cannot be
 * interrupted, so it is exactly the case "a blocking platform call returns late" that the
 * generation gate has to survive.
 */
class ConnectionLifecycleR9Test {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

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
    private val healthMs = 120L

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
     * A browser that tracks how many times it was opened and how many sessions were live at the
     * same time, and whose `stop()` can be made SLOW.
     *
     * The peak is the assertion that matters: a serialized, cancel-and-join hand-off can never have
     * two live listeners, and a stop that takes longer than any fixed budget must still finish
     * before the replacement opens one.
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

        /** Simulates a platform teardown that returns late (R8 assumed it returned in 1.5 s). */
        @Volatile
        var stopDelayMs: Long = 0L

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
            // The listener is closed FIRST — that is what "the old session is really gone" means —
            // and the call itself then returns as slowly as the platform would.
            active.decrementAndGet()
            val delay = stopDelayMs
            if (delay > 0L) Thread.sleep(delay)
        }
    }

    /**
     * A probe that can be held inside `probe()`, exactly like a socket call that ignores
     * cancellation. [holdingStarted] is what a test waits for to know the probe is really in
     * flight before it pulls the ground out from under it.
     */
    private class ControllableProbe : DiscoveryProbe {
        val probed = ConcurrentHashMap<String, AtomicInteger>()
        private val gate = CountDownLatch(1)

        @Volatile
        var reachable: Set<String> = emptySet()

        @Volatile
        var hold: Boolean = false

        val holdingStarted = AtomicInteger(0)

        fun release() = gate.countDown()

        fun totalProbes(): Int = probed.values.sumOf { it.get() }

        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            probed.computeIfAbsent("$ip:$port") { AtomicInteger(0) }.incrementAndGet()
            if (hold) {
                holdingStarted.incrementAndGet()
                try {
                    // A bounded await so a mis-written test cannot hang the suite forever; the
                    // tests release it long before this.
                    gate.await(20, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
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

    private class Harness(
        val settings: FakeSettings,
        val discovery: CountingDiscovery,
        val probe: ControllableProbe,
        val healthMs: Long,
    ) {
        lateinit var coordinator: DiscoveryCoordinator
        lateinit var viewModel: RecordingViewModel
    }

    private fun harness(
        retryMs: Long = 60_000L,
        health: Long = healthMs,
        timing: TestTiming = TestTiming(),
    ): Harness {
        val h = Harness(FakeSettings(devToken = validToken), CountingDiscovery(), ControllableProbe(), health)
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
            healthCheckIntervalMs = health,
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
     * The single source of truth across every layer, asserted together — the same contract R8's
     * P0-A defect violated, and the one a late probe must not be able to break.
     */
    private fun assertTargetAgrees(h: Harness, expected: DiscoverySelection?, because: String) {
        waitUntil("$because: every layer must agree on $expected", timeoutMs = 5_000L) {
            h.viewModel.currentDestination() == expected &&
                h.viewModel.resolverTargetForTest == expected &&
                h.viewModel.canRecord.value == (expected != null)
        }
        assertEquals("$because (ViewModel target)", expected, h.viewModel.currentDestination())
        assertEquals("$because (Resolver/engine target)", expected, h.viewModel.resolverTargetForTest)
        assertEquals("$because (recording gate)", expected != null, h.viewModel.canRecord.value)
        if (expected != null) {
            assertEquals("$because (UI current target)", expected, h.viewModel.ui.value.currentTarget)
        } else {
            assertEquals("$because (the screen must not claim a connection)", false, h.viewModel.ui.value.connected)
        }
    }

    // ── 1. a health probe in flight when the app goes to the background ──────

    @Test
    fun `a health probe in flight cannot outlive the background pause`() {
        val h = harness()
        connect(h)

        // The next health probe blocks inside probe() like a real socket call.
        h.probe.hold = true
        waitUntil("a health probe must be in flight", timeoutMs = 15_000L) {
            h.probe.holdingStarted.get() >= 1
        }

        val targetAtBackground = h.viewModel.currentDestination()
        val resolverAtBackground = h.viewModel.resolverTargetForTest
        val uiAtBackground = h.viewModel.ui.value
        val savedAtBackground = h.settings.savedCount
        val startsAtBackground = h.discovery.startCount.get()
        val probesAtBackground = h.probe.totalProbes()

        // 1C-D-04@R9 P0-B: the entry point must return at once — R8 blocked it for 1.5 s here.
        val enteredAt = System.currentTimeMillis()
        h.viewModel.onBackground()
        val elapsed = System.currentTimeMillis() - enteredAt
        assertTrue(
            "onBackground must not wait for the in-flight probe (took ${elapsed}ms)",
            elapsed < 500L,
        )

        // Let the old probe come back — long after its round was cancelled.
        h.probe.release()

        // More than two full health periods (120 ms each) must pass with nothing running.
        Thread.sleep(600L)

        assertEquals(
            "a backgrounded probe may not clear the target",
            targetAtBackground,
            h.viewModel.currentDestination(),
        )
        assertEquals(
            "a backgrounded probe may not clear the resolver's target",
            resolverAtBackground,
            h.viewModel.resolverTargetForTest,
        )
        assertEquals("no UI state may change in the background", uiAtBackground, h.viewModel.ui.value)
        assertEquals("no setting may be written in the background", savedAtBackground, h.settings.savedCount)
        assertEquals(
            "no browse may run in the background",
            startsAtBackground,
            h.discovery.startCount.get(),
        )
        assertEquals(
            "no probe may run in the background (before=$probesAtBackground)",
            probesAtBackground,
            h.probe.totalProbes(),
        )
        assertEquals(
            "the recording gate must not change in the background",
            targetAtBackground != null,
            h.viewModel.canRecord.value,
        )
        assertFalse("the owner must not think it is in the foreground", h.viewModel.ownerForegroundForTest)

        // Only returning to the foreground revalidates again.
        h.probe.hold = false
        val probesBeforeReturn = h.probe.totalProbes()
        h.viewModel.onForeground()
        waitUntil("returning to the foreground must revalidate", timeoutMs = 15_000L) {
            h.viewModel.ownerForegroundForTest && h.viewModel.currentDestination() == desktop
        }
        waitUntil("the foreground must really probe again", timeoutMs = 15_000L) {
            h.probe.totalProbes() > probesBeforeReturn
        }
        assertTargetAgrees(h, desktop, "after returning to the foreground")
    }

    // ── 2. an explicit switch while a probe is in flight ────────────────────

    /**
     * The hand-off must WAIT for the blocking probe to come back — the platform call cannot be
     * interrupted — but the UI may not, and the late answer may not be applied.
     */
    private fun switchDuringBlockedProbe(failTheOldProbe: Boolean) {
        val h = harness()
        connect(h)

        h.probe.hold = true
        waitUntil("a health probe must be in flight", timeoutMs = 15_000L) {
            h.probe.holdingStarted.get() >= 1
        }
        val startsBefore = h.discovery.startCount.get()

        // The user opens the picker while the probe is still blocked.
        val enteredAt = System.currentTimeMillis()
        h.viewModel.requestSwitch()
        val elapsed = System.currentTimeMillis() - enteredAt
        assertTrue(
            "requestSwitch must not wait for the in-flight probe (took ${elapsed}ms)",
            elapsed < 500L,
        )

        // While the old round is still unwinding, no second listener may exist.
        Thread.sleep(200L)
        assertEquals(
            "no browse may start before the old round is really cancelled",
            startsBefore,
            h.discovery.startCount.get(),
        )
        assertEquals("the old browse must be gone", 0, h.discovery.active.get())

        // Release it, successfully or not: either answer arrives after its round was superseded.
        if (failTheOldProbe) h.probe.reachable = emptySet()
        h.probe.release()

        waitUntil("the switch must browse once the old round is over", timeoutMs = 15_000L) {
            h.discovery.startCount.get() > startsBefore
        }
        waitUntil("the switch browse must settle", timeoutMs = 15_000L) {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }

        assertTargetAgrees(
            h,
            desktop,
            if (failTheOldProbe) "a late FAILED probe may not clear the target" else "a late successful probe changes nothing",
        )
        assertEquals("two listeners must never overlap", 1, h.discovery.peakActive.get())
        assertEquals(
            "the hand-off must have browsed exactly once",
            startsBefore + 1,
            h.discovery.startCount.get(),
        )
    }

    @Test
    fun `a switch during a blocked probe waits for it and a late success changes nothing`() {
        switchDuringBlockedProbe(failTheOldProbe = false)
    }

    @Test
    fun `a switch during a blocked probe survives a late FAILED probe without losing the target`() {
        switchDuringBlockedProbe(failTheOldProbe = true)
    }

    // ── 3. a slow platform stop must still serialize the hand-off ───────────

    @Test
    fun `a slow stop hands over only after the old listener is really gone`() {
        val h = harness()
        connect(h)

        // Every platform stop now takes 2 s — longer than R8's whole cancel-and-join budget.
        h.discovery.stopDelayMs = 2_000L
        h.discovery.hold = true
        h.viewModel.requestSwitch()
        waitUntil("the first browse must open", timeoutMs = 15_000L) {
            h.discovery.active.get() == 1
        }
        val startsAfterFirst = h.discovery.startCount.get()

        // A second tap must replace it: the old browse has to be stopped first.
        val enteredAt = System.currentTimeMillis()
        h.viewModel.requestSwitch()
        val elapsed = System.currentTimeMillis() - enteredAt
        assertTrue("the UI entry must return at once (took ${elapsed}ms)", elapsed < 500L)

        // Inside the slow stop: the old listener is already gone and the new one may not exist.
        Thread.sleep(600L)
        assertEquals(
            "no second listener may exist while the old stop is still returning",
            0,
            h.discovery.active.get(),
        )
        assertEquals(
            "no replacement browse may start before the old one finished stopping",
            startsAfterFirst,
            h.discovery.startCount.get(),
        )

        // Once the stop returns, the replacement browses — and still never overlaps.
        waitUntil("the replacement must browse after the slow stop", timeoutMs = 15_000L) {
            h.discovery.startCount.get() > startsAfterFirst
        }
        assertEquals("two listen sessions must never overlap", 1, h.discovery.peakActive.get())

        h.discovery.stopDelayMs = 0L
        h.discovery.hold = false
        h.viewModel.dismissSwitchPicker()
        waitUntil("the picker browse must be stopped on return", timeoutMs = 15_000L) {
            h.discovery.active.get() == 0
        }
        assertTargetAgrees(h, desktop, "after a slow stop hand-off")
    }

    // ── 4. a cancelled switch really crosses health periods ─────────────────

    @Test
    fun `a cancelled switch keeps the target across two real health periods without browsing`() {
        val h = harness()
        connect(h)

        h.discovery.hold = true
        h.viewModel.requestSwitch()
        waitUntil("the switch browse must open", timeoutMs = 15_000L) {
            h.discovery.active.get() == 1
        }
        val startsDuringSwitch = h.discovery.startCount.get()

        h.viewModel.dismissSwitchPicker()
        waitUntil("the browse must be cancelled", timeoutMs = 15_000L) {
            h.discovery.active.get() == 0
        }
        val probesAtCancel = h.probe.totalProbes()

        // Cross at least two REAL periods of this harness's short health interval. R8 slept 700 ms
        // against a 60 s interval, so it never crossed a single one.
        val requiredPeriods = 2
        Thread.sleep(h.healthMs * (requiredPeriods + 1))

        val probesNow = h.probe.totalProbes()
        assertTrue(
            "at least $requiredPeriods health periods must really have run " +
                "(before=$probesAtCancel, after=$probesNow, period=${h.healthMs}ms)",
            probesNow >= probesAtCancel + requiredPeriods,
        )
        assertEquals(
            "a cancelled switch must not keep browsing",
            startsDuringSwitch,
            h.discovery.startCount.get(),
        )
        assertTargetAgrees(h, desktop, "across two real health periods after a cancelled switch")
    }

    @Test
    fun `the idle health cadence probes without ever browsing and still reports idle`() {
        val h = harness()
        connect(h)
        val startsBefore = h.discovery.startCount.get()
        val probesBefore = h.probe.totalProbes()

        // Two real periods again, but this time nothing is cancelled: the loop must simply probe.
        Thread.sleep(h.healthMs * 3)

        assertTrue(
            "the idle health check must keep probing (before=$probesBefore)",
            h.probe.totalProbes() >= probesBefore + 2,
        )
        assertEquals(
            "an idle connected watch must never open a browse",
            startsBefore,
            h.discovery.startCount.get(),
        )
        assertEquals(
            "the loop must report the idle connected state",
            ConnectionTaskOwner.RoundKind.IDLE_CONNECTED,
            h.viewModel.ownerRoundKindForTest,
        )
        assertFalse("connecting must not be shown while merely idle", h.viewModel.ui.value.connecting)
        assertTargetAgrees(h, desktop, "across idle health periods")
    }

    // ── 5. the notes the package really ships ───────────────────────────────

    @Test
    fun `the packaging script generates notes that name the real config path in their own language`() {
        // 1C-D-04@R9 P1: the notes are asserted on the ARTEFACT the packaging script generates, not
        // on a copy of the template in its source text. R8 asserted a Chinese phrase against an
        // English README, so the delivery head was red while the test claimed to verify it.
        val notesDir = generatePortableNotes()
        val readme = File(notesDir, "README-PORTABLE.txt")
        assertTrue("the script must have written README-PORTABLE.txt: $readme", readme.isFile)
        val text = readme.readText(Charsets.UTF_8)

        assertTrue(
            "the notes must name the receiver configuration file",
            text.contains("%LOCALAPPDATA%\\com.sayit.app\\watch-receiver.config.json"),
        )
        // The shipped notes are English. Assert in the language they are actually written in.
        assertTrue(
            "the notes must explain that the token is brought over by the user",
            text.contains("Bring the token over yourself"),
        )
        assertTrue(
            "the notes must name what the user has to put into the config file",
            text.contains("watch token"),
        )
        assertTrue(
            "the notes must still explain that the frontend is embedded",
            text.contains("frontendDist"),
        )
        assertFalse(
            "the shipped notes must not point at a settings entry that does not exist",
            text.contains("SayIt 设置"),
        )
        assertFalse(
            "the shipped notes must not advertise an unrelated token field",
            text.contains("服务器访问令牌"),
        )
        assertFalse(
            "the notes must be clean UTF-8, not a garbled re-encoding",
            text.contains('\uFFFD'),
        )

        // A control character in the shipped README means an escape was interpreted while it was
        // generated — a form feed once reached it that way. Tab, LF and CR are the only legal ones.
        // The comparison is unsigned: Kotlin's Byte is signed, so the 0xE2 opening an em dash would
        // otherwise read as "less than 0x20" and this test would fail on perfectly good prose.
        val offending = readme.readBytes().firstOrNull {
            val value = it.toInt() and 0xFF
            value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D
        }
        if (offending != null) {
            fail("README-PORTABLE.txt contains a control character: 0x%02X".format(offending.toInt() and 0xFF))
        }

        val buildInfo = File(notesDir, "BUILD-INFO.txt")
        assertTrue("the script must have written BUILD-INFO.txt: $buildInfo", buildInfo.isFile)
        val buildInfoText = buildInfo.readText(Charsets.UTF_8)
        assertTrue(
            "BUILD-INFO must record the product commit",
            buildInfoText.contains("git_head="),
        )
        assertTrue(
            "BUILD-INFO must record that the frontend is embedded",
            buildInfoText.contains("frontend=embedded"),
        )
        assertTrue(
            "BUILD-INFO must name the real receiver config path",
            buildInfoText.contains("receiver_config=%LOCALAPPDATA%\\com.sayit.app\\watch-receiver.config.json"),
        )
    }

    /**
     * Runs the REAL packaging script in its `-NotesOnly` mode into an isolated directory.
     *
     * The script is the single source of the shipped wording, so the test cannot pass on text that
     * a real package would never contain.
     */
    private fun generatePortableNotes(): File {
        val script = File("../../client/scripts/package-watch-portable.ps1").absoluteFile
        assertTrue("the packaging script must exist: $script", script.isFile)
        val destination = temporaryFolder.newFolder("portable-notes")
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
            "-NotesOnly",
            "-OutputDir", destination.absolutePath,
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        assertTrue("generating the portable notes must finish:\n$output", finished)
        assertEquals("generating the portable notes must succeed:\n$output", 0, process.exitValue())
        return destination
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
