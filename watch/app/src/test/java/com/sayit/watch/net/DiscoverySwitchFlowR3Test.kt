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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery 1C 1C-D-04@R3 coverage — the three R2 acceptance defects.
 *
 * 必修 1: the REAL `RecordingViewModel.requestSwitch()` entry point is driven end to
 * end (request → bounded browse → adopt → persist → Ready). R2 only called
 * `ResolverBridge.onTargetChosen()` directly, which is why the stale-list rejection
 * was invisible to its tests.
 *
 * 必修 2: the resolve worker's lifecycle. `BrowseWorker` is the production class the
 * browser uses, so "stop() guarantees no live thread, no polling afterwards, and
 * threads are not accumulated" is asserted against real threads.
 *
 * 必修 3: no-current-target switch with 0/1/2 authenticated computers, cancel and
 * re-entry, and the engine never being left in Automatic.
 */
class DiscoverySwitchFlowR3Test {

    /**
     * 1C-D-04@R3: `viewModelScope` is bound to `Dispatchers.Main`, which a plain JVM
     * test does not have. An unconfined test dispatcher keeps the production code path
     * (the ViewModel really launches its coroutine) while removing the Android main
     * looper from the equation.
     */
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val oldPc = DiscoverySelection("192.168.12.100", 18099)
    private val newPc = DiscoverySelection("192.168.12.142", 18099)

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

    /**
     * A browser that delivers whatever was queued when the browse OPENED and nothing
     * else — the same shape as a platform callback arriving inside the window.
     *
     * 1C-D-04@R5: each `start` takes the NEXT queued batch, so one test can model
     * "computer A answers, then computer B answers" without rebuilding the harness.
     */
    private class FakeDiscovery : ServiceDiscovery {
        var startCount = 0
        var stopCount = 0
        var next: List<ResolvedService> = emptyList()
        private val batches = ArrayDeque<List<ResolvedService>>()
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount++
            val batch = batches.removeFirstOrNull() ?: next
            if (batch.isNotEmpty()) observer(batch)
        }
        override fun stop() {
            stopCount++
        }

        /** Queues one browse result for the next `start`. */
        fun queue(services: List<ResolvedService>) {
            batches.addLast(services)
        }
    }

    private class FakeProbe(private val authenticated: Set<Pair<String, Int>>) : DiscoveryProbe {
        val probed = mutableListOf<Pair<String, Int>>()
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            probed.add(ip to port)
            return if (authenticated.contains(ip to port)) {
                DiscoveryPolicy.parseProbeResponse(
                    200,
                    """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
                )
            } else {
                DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
            }
        }
    }

    private class FakeTiming : DiscoveryTiming {
        override val savedProbeTotalMs = 60L
        override val discoveryWindowMs = 300L
        override val savedProbeAttemptMs = 25L
        override val discoveryProbeAttemptMs = 60L
        override val pollIntervalMs = 10L
    }

    private class Harness(
        val settings: FakeSettings,
        val discovery: FakeDiscovery,
        val probe: FakeProbe,
    ) {
        lateinit var coordinator: DiscoveryCoordinator
        lateinit var viewModel: RecordingViewModel

        fun build() {
            coordinator = DiscoveryCoordinator(
                settings = settings,
                discovery = discovery,
                probe = probe,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                probeDispatcher = Dispatchers.IO,
                timing = FakeTiming(),
                diagnostics = CategoryLog(),
            )
            viewModel = RecordingViewModel(
                settings = settings,
                session = RecordingSession(),
                // `AudioCapture`'s constructor performs no Android work; every
                // `AudioRecord` call lives inside `verifySupported()`, which this test
                // never asserts on.
                capture = AudioCapture(),
                clientFactory = { null },
                context = null,
                discoveryBrowser = discovery,
                discoveryProbe = probe,
                coordinatorOverride = coordinator,
            )
        }

        /**
         * Runs one switch and waits for the launched browse to settle.
         *
         * 1C-D-04@R7: the round is started by the connection task owner, so the browse
         * may not have opened yet when this is called — the wait is therefore
         * condition-driven rather than assuming the browse is already in flight.
         */
        fun switchAndWait(timeoutMs: Long = 20_000L) {
            viewModel.requestSwitch()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (viewModel.ui.value.switchSearching && coordinator.isRunning) {
                    // The replacement browse really opened; now wait for it to settle.
                    while (System.currentTimeMillis() < deadline) {
                        if (!coordinator.isRunning && !viewModel.ui.value.switchSearching) {
                            Thread.sleep(40)
                            if (!coordinator.isRunning) return
                        }
                        Thread.sleep(10)
                    }
                    break
                }
                // Nothing may be left running once the round has settled.
                if (!coordinator.isRunning && !viewModel.ui.value.switchSearching) {
                    Thread.sleep(40)
                    if (!coordinator.isRunning && !viewModel.isConnectionTaskRunningForTest) return
                }
                Thread.sleep(10)
            }
            assertFalse("the switch browse must finish inside its budget", coordinator.isRunning)
        }

        /**
         * 1C-D-04@R7: the user taps a row. The pick re-authenticates the candidate before
         * adoption, so it is asynchronous and needs a bounded wait. The picker closing is
         * the terminal signal for BOTH an adoption and a refusal.
         */
        fun pickAndWait(candidate: DiscoverySelection, timeoutMs: Long = 15_000L, expectAdopted: Boolean = true) {
            viewModel.onTargetPicked(candidate)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // A pick is only complete once the picker has CLOSED and the target is
                // applied: `applyTargetChoice` publishes the destination before the
                // picker-close event, and closing is what the user sees.
                if (!viewModel.ui.value.switchOpen) {
                    if (viewModel.currentDestination() == candidate) return
                    if (!expectAdopted) return
                    assertEquals("the user's pick must be adopted", candidate, viewModel.currentDestination())
                }
                Thread.sleep(10)
            }
            assertEquals("the user's pick must be adopted", candidate, viewModel.currentDestination())
        }
    }

    private fun harness(
        savedIp: String = "",
        authenticated: Set<Pair<String, Int>> = setOf(oldPc.ip to oldPc.port, newPc.ip to newPc.port),
    ): Harness {
        val settings = FakeSettings(savedIp, if (savedIp.isEmpty()) "" else "18099", validToken)
        return Harness(settings, FakeDiscovery(), FakeProbe(authenticated)).also { it.build() }
    }

    // ── 必修 1: the first switch must be adoptable ────────────────────────────

    @Test
    fun `the real switch entry point offers the first newly discovered computer for an explicit pick`() {
        val h = harness(savedIp = oldPc.ip)
        // The old computer is in use.
        h.viewModel.onForeground()
        waitForTarget(h, oldPc)
        // 1C-D-04@R7: the automatic loop would supersede the explicit switch, so pause it
        // while this test measures that one browse (production pauses the same way for a
        // transfer).
        h.viewModel.pauseConnectionTaskForTest()

        // The user taps 切换电脑 and exactly one NEW computer answers.
        h.discovery.next = listOf(realService(newPc.ip))
        h.switchAndWait()

        // 1C-D-04@R7 必修 2: the browse offers; it never switches on its own.
        assertEquals(
            "the browse must not switch the computer by itself",
            oldPc,
            h.viewModel.currentDestination(),
        )
        assertTrue(
            "the newly authenticated computer must be offered for an explicit pick",
            h.viewModel.switchEntries().any { it.target == newPc && !it.isCurrent },
        )
        assertTrue("the picker stays open for the user", h.viewModel.ui.value.switchOpen)

        // Only the user's own tap switches.
        h.viewModel.onTargetPicked(newPc)
        waitUntil("the user's pick must be adopted") { h.viewModel.currentDestination() == newPc }
        assertEquals("the adopted computer must be persisted", newPc.ip to newPc.port, h.settings.lastSaved)
        assertFalse("the picker must close after picking", h.viewModel.ui.value.switchOpen)
        assertEquals(WatchUiState.Screen.READY, h.viewModel.ui.value.screen)
        assertTrue("recording must be enabled", h.viewModel.canRecord.value)
        assertEquals(
            "exactly one bounded browse — no repeated saved-address re-probe first",
            1,
            h.discovery.startCount,
        )
    }

    @Test
    fun `no current target and one new computer is offered in one browse`() {
        val h = harness()
        // 1C-D-04@R7: the app must be in the foreground for any round to run.
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(newPc.ip))
        h.switchAndWait()

        assertNull("the browse itself must not adopt", h.viewModel.currentDestination())
        assertEquals(1, h.discovery.startCount)
        assertEquals(
            "the single candidate must be offered",
            listOf(newPc),
            h.viewModel.switchEntries().map { it.target },
        )

        h.viewModel.onTargetPicked(newPc)
        waitUntil("the user's pick must be adopted") { h.viewModel.currentDestination() == newPc }
        assertTrue(h.viewModel.canRecord.value)
    }

    @Test
    fun `the legacy target keeps working until the user confirms the switch`() {
        val h = harness(savedIp = oldPc.ip)
        h.viewModel.onForeground()
        waitForTarget(h, oldPc)

        // The browse finds nothing new: the old target must survive untouched.
        h.discovery.next = emptyList()
        h.switchAndWait()
        assertEquals("an empty switch must not clear the working target", oldPc, h.viewModel.currentDestination())
        assertTrue("recording stays possible on the old computer", h.viewModel.canRecord.value)

        // A later browse does find a new one; only the user's own pick changes it.
        h.discovery.next = listOf(realService(newPc.ip))
        h.switchAndWait()
        assertEquals("the browse must still not switch", oldPc, h.viewModel.currentDestination())
        h.pickAndWait(newPc)
        assertEquals(newPc, h.viewModel.currentDestination())
    }

    @Test
    fun `two newly authenticated computers are offered and never chosen silently`() {
        val h = harness()
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(oldPc.ip), realService(newPc.ip))
        h.switchAndWait()
        // 1C-D-04@R7: the automatic loop keeps rounds running, so pause it before asserting
        // on the picker this single browse produced (production pauses the same way while a
        // transfer owns the transport).
        h.viewModel.pauseConnectionTaskForTest()

        assertNull("an ambiguous browse must not adopt anything", h.viewModel.currentDestination())
        assertEquals(
            "both authenticated computers must be offered",
            setOf(oldPc, newPc),
            h.viewModel.switchEntries().map { it.target }.toSet(),
        )
        assertTrue("the picker stays open for the user", h.viewModel.ui.value.switchOpen)

        // The user picks one: now, and only now, a target exists.
        h.pickAndWait(newPc)
        assertEquals(newPc, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
        assertEquals(1, h.settings.savedCount)
    }

    @Test
    fun `an endpoint this session never authenticated can never be picked`() {
        val h = harness()
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(oldPc.ip), realService(newPc.ip))
        h.switchAndWait()

        val stranger = DiscoverySelection("192.168.12.199", 18099)
        h.viewModel.onTargetPicked(stranger)
        assertNull("an unauthenticated address must be refused", h.viewModel.currentDestination())
        assertEquals("a refused pick must persist nothing", 0, h.settings.savedCount)
        assertFalse(h.viewModel.canRecord.value)
    }

    @Test
    fun `dismissing the picker changes nothing and a second switch still works`() {
        val h = harness()
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(newPc.ip))
        h.switchAndWait()
        h.pickAndWait(newPc)
        assertEquals(newPc, h.viewModel.currentDestination())
        val savedAfterFirst = h.settings.savedCount

        h.viewModel.requestSwitch()
        // 1C-D-04@R7: wait for the browse this call started, not just for "nothing runs
        // right now" — the round itself is handed to the task owner asynchronously.
        waitUntil("the switch browse must run") { h.discovery.startCount > 0 }
        waitUntil("the browse must settle") {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }
        assertTrue(
            "the browse must offer the computer again",
            h.viewModel.switchEntries().any { it.target == newPc },
        )
        h.viewModel.dismissSwitchPicker()
        assertFalse(h.viewModel.ui.value.switchOpen)
        assertEquals("dismissing must not change the target", newPc, h.viewModel.currentDestination())
        assertEquals(savedAfterFirst, h.settings.savedCount)
    }

    // ── 必修 3: 0 targets ends the search; cancel; re-entry ───────────────────

    @Test
    fun `a switch that finds nothing ends in the bounded fallback and can be retried`() {
        val h = harness()
        h.viewModel.onForeground()
        // The automatic recovery loop would open its own browse and blur this measurement.
        h.viewModel.pauseConnectionTaskForTest()
        h.discovery.next = emptyList()
        h.switchAndWait()

        assertNull(h.viewModel.currentDestination())
        assertEquals(DiscoveryState.ManualFallback, h.viewModel.discovery.value)
        assertFalse("no search may be left running", h.viewModel.ui.value.switchSearching)
        assertFalse(h.coordinator.isRunning)
        // The engine must not be stuck owning the resolution: a retry works.
        h.discovery.next = listOf(realService(newPc.ip))
        h.viewModel.requestSwitch()
        waitUntil("the retry browse must offer the computer") {
            h.viewModel.switchEntries().any { it.target == newPc }
        }
        assertEquals(
            "a retry must offer the computer, not adopt it",
            listOf(newPc),
            h.viewModel.switchEntries().map { it.target },
        )
        h.pickAndWait(newPc)
        assertEquals(newPc, h.viewModel.currentDestination())
    }

    @Test
    fun `a cancelled switch does not verify a target and does not leave a run behind`() {
        val h = harness()
        h.discovery.next = listOf(realService(newPc.ip))
        h.viewModel.requestSwitch()
        // Cancel while the browse window is still open.
        h.viewModel.stopDiscovery()
        waitIdle(h, 5_000L)

        assertNull("a cancelled switch must not adopt", h.viewModel.currentDestination())
        assertEquals("a cancelled switch must persist nothing", 0, h.settings.savedCount)
        assertFalse(h.coordinator.isRunning)
        assertFalse(h.viewModel.ui.value.switchSearching)
    }

    @Test
    fun `re-entering Ready after a completed switch does not restart discovery`() {
        val h = harness()
        // 1C-D-04@R7: establish the foreground BEFORE the switch, so the automatic round
        // cannot clear the picker's authenticated list between the browse and the pick.
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(newPc.ip))
        h.switchAndWait()
        h.pickAndWait(newPc)
        val startsAfterSwitch = h.discovery.startCount

        assertFalse(
            "a verified target must make Ready entry a no-op",
            h.viewModel.onReadyEnteredForTest(),
        )
        assertEquals(startsAfterSwitch, h.discovery.startCount)
        assertEquals(newPc, h.viewModel.currentDestination())
    }

    // ── 必修 2: worker lifecycle ─────────────────────────────────────────────

    private fun workerThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "sayit-nsd-resolve-r3" }

    /**
     * Waits for a worker thread to actually leave the JVM. The worker's own loop marks
     * itself done before the thread object finishes unwinding, so an `assertEquals(0, …)`
     * that runs immediately after `stop()` is inherently racy; the assertion is the
     * same, it just gets a bounded window.
     */
    private fun waitNoWorkerThreads(message: String = "no worker thread may survive stop()") {
        waitUntil(message) { workerThreads() == 0 }
        assertEquals(message, 0, workerThreads())
    }

    @Test
    fun `the resolve worker exists only for the browse and stop joins it`() {
        val processed = AtomicInteger(0)
        val worker = BrowseWorker<Int>(
            threadName = "sayit-nsd-resolve-r3",
            process = { processed.incrementAndGet() },
        )
        assertTrue("no worker before a browse", worker.isStopped())
        waitNoWorkerThreads()

        assertNotNull(worker.start())
        assertTrue("a browse opens exactly one worker", worker.isRunning)
        assertTrue(worker.offer(1))
        assertTrue(worker.offer(2))
        waitUntil("the worker must drain the queue") { processed.get() == 2 }
        waitUntil("exactly one worker thread") { workerThreads() == 1 }

        worker.stop()
        // 1C-D-04@R4: the honest signal is `isRunning`/`canStart`, not a handle that
        // was cleared before the join.
        assertFalse("stop() must leave no live worker thread", worker.isRunning)
        assertTrue("a clean stop must allow the next session", worker.canStart)
        waitNoWorkerThreads()

        // An idle worker performs no polling: nothing is processed while it waits.
        val before = processed.get()
        Thread.sleep(120)
        assertEquals("an idle worker must not spin or process anything", before, processed.get())
    }

    @Test
    fun `a stopped worker discards late work and threads are not accumulated`() {
        val processed = AtomicInteger(0)
        val worker = BrowseWorker<Int>(
            threadName = "sayit-nsd-resolve-r3",
            process = { processed.incrementAndGet() },
        )
        repeat(5) { round ->
            worker.start()
            assertTrue(worker.offer(round))
            waitUntil("work must be processed in session $round") { processed.get() == round + 1 }
            worker.stop()
            waitNoWorkerThreads("each session must end with zero worker threads")
        }
        assertFalse("a stopped worker must refuse late work", worker.offer(99))
        waitNoWorkerThreads()
    }

    @Test
    fun `the worker stops even while it waits for a platform callback that never arrives`() {
        val entered = CountDownLatch(1)
        val worker = BrowseWorker<Int>(
            threadName = "sayit-nsd-resolve-r3",
            joinTimeoutMs = 2_000L,
            process = {
                entered.countDown()
                // Mimics a platform callback that simply never arrives.
                Thread.sleep(10_000)
            },
        )
        worker.start()
        assertTrue(worker.offer(1))
        assertTrue("the worker must have entered the resolve", entered.await(3, TimeUnit.SECONDS))

        val started = System.currentTimeMillis()
        worker.stop()
        val elapsed = System.currentTimeMillis() - started

        assertTrue("stop() must not block on the platform callback: ${elapsed}ms", elapsed < 2_500L)
        assertFalse("stop() must still guarantee exit for an interruptible call", worker.isRunning)
        waitNoWorkerThreads()
    }

    @Test
    fun `the worker keeps one outstanding request and de-duplicates work`() {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val worker = BrowseWorker<String>(
            threadName = "sayit-nsd-resolve-r3",
            keyOf = { it },
            process = {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, now) }
                Thread.sleep(20)
                concurrent.decrementAndGet()
            },
        )
        worker.start()
        assertTrue(worker.offer("a"))
        assertFalse("the same instance must not queue twice", worker.offer("a"))
        assertTrue(worker.offer("b"))
        waitUntil("both distinct items must be processed") { worker.processedCount == 2 }
        assertEquals("never more than one resolve at a time", 1, maxConcurrent.get())
        assertEquals("the duplicate must be counted as discarded", 1, worker.discardedCount)
        worker.stop()
        waitNoWorkerThreads()
    }

    @Test
    fun `a retry of the item being processed is accepted and queued behind the work`() {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        var worker: BrowseWorker<String>? = null
        worker = BrowseWorker<String>(
            threadName = "sayit-nsd-resolve-r3",
            keyOf = { it },
            process = { item ->
                order.add(item)
                if (item == "a" && order.count { it == "a" } == 1) {
                    // FAILURE_ALREADY_ACTIVE: the item being processed is re-offered.
                    assertTrue("a retry of the in-flight item must be accepted", worker!!.offer("a"))
                }
            },
        )
        worker.start()
        assertTrue(worker.offer("a"))
        waitUntil("the retry must run after the first attempt") { order.count { it == "a" } == 2 }
        worker.stop()
        assertEquals("the retry must be the second attempt, not a concurrent one", 2, order.count { it == "a" })
        waitNoWorkerThreads()
    }

    // ── 必修 1 (R4): no implicit pick when there is no current target ──────────

    @Test
    fun `two candidates with no current target leave no hidden target after Ready re-entry`() {
        val h = harness()
        h.viewModel.onForeground()
        h.discovery.next = listOf(realService(oldPc.ip), realService(newPc.ip))
        h.switchAndWait()

        // Immediately after the browse.
        assertNull("the browse itself must not adopt", h.viewModel.currentDestination())
        assertFalse(h.viewModel.canRecord.value)
        assertTrue(h.viewModel.ui.value.switchOpen)
        assertFalse(
            "the search must not stay in Automatic",
            h.viewModel.isAutomaticResolutionActiveForTest,
        )

        // The R3 hole: a later Ready entry could promote an internally picked first
        // computer into a real upload target. 1C-D-04@R7: Ready entry expresses a round to
        // the task owner; it must settle without exposing a target.
        h.viewModel.onForeground()
        waitUntil("the Ready round must settle") {
            !h.viewModel.ui.value.switchSearching && !h.coordinator.isRunning
        }
        assertNull("Ready re-entry must not expose a hidden target", h.viewModel.currentDestination())
        assertFalse("Ready re-entry must not enable recording", h.viewModel.canRecord.value)
        assertEquals("Ready re-entry must not persist anything", 0, h.settings.savedCount)
        assertFalse(h.viewModel.isAutomaticResolutionActiveForTest)

        // Cancelling and searching again must still leave the choice to the user.
        h.viewModel.dismissSwitchPicker()
        h.switchAndWait()
        // 1C-D-04@R7: the automatic loop keeps rounds running, so pause it before asserting
        // on the picker this browse produced.
        h.viewModel.pauseConnectionTaskForTest()
        assertNull("a second search must not pick by discovery order", h.viewModel.currentDestination())
        assertFalse(h.viewModel.canRecord.value)
        assertEquals("nothing may be persisted by implicit picks", 0, h.settings.savedCount)
        // 1C-D-04@R7: the automatic loop keeps rounds running, so pause it before asserting
        // on the candidate list this browse produced — its own rounds re-authenticate and
        // refresh the list (production pauses the same way for a transfer).
        h.viewModel.pauseConnectionTaskForTest()
        assertEquals(
            "both authenticated computers stay offered",
            setOf(oldPc, newPc),
            h.viewModel.switchEntries().map { it.target }.toSet(),
        )

        // Only the user's own pick creates a target.
        h.pickAndWait(oldPc)
        assertEquals(oldPc, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
        assertEquals(1, h.settings.savedCount)
    }

    @Test
    fun `a current target survives a two-candidate browse and is never re-picked`() {
        val peers = setOf(
            oldPc.ip to oldPc.port,
            "192.168.12.150" to oldPc.port,
            "192.168.12.151" to oldPc.port,
        )
        val h = harness(savedIp = oldPc.ip, authenticated = peers)
        h.viewModel.onForeground()
        waitForTarget(h, oldPc)

        // The browse finds TWO computers, neither of which is the one in use: there is
        // no unique new candidate, so the current target must survive untouched and
        // nothing may be adopted by discovery order.
        h.discovery.next = listOf(
            realService("192.168.12.150"),
            realService("192.168.12.151"),
        )
        val savedBefore = h.settings.savedCount
        h.switchAndWait()

        assertEquals("the working target must be kept", oldPc, h.viewModel.currentDestination())
        assertTrue(h.viewModel.canRecord.value)
        assertEquals("keeping the old target must not persist anything", savedBefore, h.settings.savedCount)
        assertFalse(h.viewModel.isAutomaticResolutionActiveForTest)
        assertEquals(
            "the picker must offer the old computer plus both new ones",
            setOf(oldPc, DiscoverySelection("192.168.12.150", 18099), DiscoverySelection("192.168.12.151", 18099)),
            h.viewModel.switchEntries().map { it.target }.toSet(),
        )

        // And the user's own pick out of that list is the only thing that persists.
        h.pickAndWait(DiscoverySelection("192.168.12.151", 18099))
        assertEquals(DiscoverySelection("192.168.12.151", 18099), h.viewModel.currentDestination())
        assertEquals("192.168.12.151" to 18099, h.settings.lastSaved)
        assertEquals(savedBefore + 1, h.settings.savedCount)
    }

    // ── 必修 2 (R4): an unresponsive process cannot fake a clean stop ─────────

    @Test
    fun `a worker that ignores interrupts is not reported as stopped`() {
        val entered = CountDownLatch(1)
        val worker = BrowseWorker<Int>(
            threadName = "sayit-nsd-resolve-r3",
            joinTimeoutMs = 500L,
            process = {
                entered.countDown()
                // Deliberately ignores interruption for a bounded, finite time: the
                // worst case the contract asks about, without hanging the test.
                val until = System.currentTimeMillis() + 600L
                while (System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(50)
                    } catch (ignored: InterruptedException) {
                        // swallowed on purpose: this is the unresponsive platform call
                    }
                }
            },
        )
        val session = worker.start()
        assertNotNull("a first session must open", session)
        assertTrue(worker.offer(1))
        assertTrue("the worker must have entered the resolve", entered.await(3, TimeUnit.SECONDS))

        val started = System.currentTimeMillis()
        val exited = worker.stop()
        val elapsed = System.currentTimeMillis() - started

        assertFalse("a bounded join that timed out must NOT report a clean stop", exited)
        assertTrue("the UI stop path must stay bounded: ${elapsed}ms", elapsed < 2_500L)
        assertTrue("the live handle must be kept, so isRunning stays honest", worker.isRunning)
        assertFalse("no new session while the old thread lives", worker.canStart)
        assertEquals(1, worker.joinTimeouts)
        assertTrue(
            "state must say the session is still closing: ${worker.stateNow}",
            worker.stateNow == BrowseWorker.State.CLOSING || worker.stateNow == BrowseWorker.State.RUNNING,
        )

        // No second thread may be added while the first one is still alive.
        assertNull("start() must refuse while the old worker lives", worker.start())
        waitUntil("never two workers") { workerThreads() == 1 }

        // Late work is refused, and the still-running process cannot land it.
        val processedAfterStop = worker.processedCount
        assertFalse("a closed session must refuse late work", worker.offer(2))

        // The unresponsive call eventually returns: only then may a new session open.
        waitUntil("the old thread must finally exit") { !worker.isRunning }
        assertTrue("a new session is allowed once the old thread is gone", worker.canStart)
        assertNotNull("a new session must start", worker.start())
        assertTrue("the new session accepts work", worker.offer(3))
        waitUntil("the new session must process its item") { worker.processedCount == processedAfterStop + 1 }
        waitUntil("still exactly one worker thread") { workerThreads() == 1 }
        // The replacement session must be BOUNDED too: whatever the bounded join
        // reports, the thread has to be gone inside the window and no new thread may
        // appear afterwards.
        repeat(3) { worker.offer(10 + it) }
        worker.stop()
        waitNoWorkerThreads("the recovered session must also leave zero threads")
        assertTrue("a later session is still allowed", worker.canStart)
    }

    @Test
    fun `a stop that timed out still lets the next session run exactly one worker`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = BrowseWorker<Int>(
            threadName = "sayit-nsd-resolve-r3",
            joinTimeoutMs = 200L,
            process = {
                entered.countDown()
                // Waits on a latch no interrupt can open: the platform call is busy.
                var opened = false
                while (!opened) {
                    try {
                        opened = release.await(5, TimeUnit.SECONDS)
                    } catch (ignored: InterruptedException) {
                        // swallowed on purpose: an unresponsive platform call
                    }
                }
            },
        )
        assertNotNull(worker.start())
        assertTrue(worker.offer(1))
        assertTrue("the worker must be inside the unresponsive call", entered.await(3, TimeUnit.SECONDS))
        assertEquals(0, worker.processedCount)

        assertFalse("the bounded join must time out", worker.stop())
        assertTrue("the live handle must survive a timed-out stop", worker.isRunning)
        assertNull("no second session while the old thread lives", worker.start())
        waitUntil("exactly one worker thread") { workerThreads() == 1 }

        release.countDown()
        waitUntil("the old worker must exit once released") { !worker.isRunning }
        assertNotNull(worker.start())
        assertTrue(worker.offer(2))
        waitUntil("the replacement session must process") { worker.processedCount == 2 }
        waitUntil("exactly one worker thread after recovery") { workerThreads() == 1 }
        worker.stop()
        waitNoWorkerThreads()
    }

    // ── 必修 2 (R5): the entry the UI calls does ONE bounded browse ────────────

    @Test
    fun `the entry point runs one bounded browse without a saved-address re-probe`() {
        val settings = FakeSettings(oldPc.ip, "18099", validToken)
        val browser = FakeDiscovery()
        val probe = FakeProbe(setOf(oldPc.ip to oldPc.port, newPc.ip to newPc.port))
        val logged = CategoryLog()
        val coordinator = DiscoveryCoordinator(
            settings = settings,
            discovery = browser,
            probe = probe,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            probeDispatcher = Dispatchers.IO,
            timing = FakeTiming(),
            diagnostics = logged,
        )
        val viewModel = RecordingViewModel(
            settings = settings,
            session = RecordingSession(),
            capture = AudioCapture(),
            clientFactory = { null },
            context = null,
            discoveryBrowser = browser,
            discoveryProbe = probe,
            coordinatorOverride = coordinator,
        )

        // Ready first: this is the real screen path, and it re-probes the saved
        // address (no browse at all).
        viewModel.onForeground()
        waitUntil("Ready must verify the saved computer") { viewModel.currentDestination() == oldPc }
        assertEquals("Ready with a live saved address must not browse", 0, browser.startCount)
        // 1C-D-04@R7: the connection owner now keeps scheduling rounds, so it is paused
        // before the explicit switch to measure exactly one browse (production pauses the
        // same way while a transfer owns the transport).
        viewModel.pauseConnectionTaskForTest()
        assertTrue(
            "Ready must be the saved re-probe path: ${logged.snapshot()}",
            logged.snapshot().contains(DiscoveryDiagnostic.SAVED_PROBE_ACCEPTED),
        )
        assertFalse(
            "Ready with a live saved address must not open a browse: ${logged.snapshot()}",
            logged.snapshot().contains(DiscoveryDiagnostic.BROWSE_STARTED),
        )

        // Now the user taps the entry: exactly one bounded browse, and no additional
        // saved-address re-probe. 1C-D-04@R7: the browse offers the computer; the user's
        // own tap is what switches.
        val probesBeforeSwitch = logged.snapshot().count { it == DiscoveryDiagnostic.PROBE_AUTHENTICATED }
        browser.queue(listOf(realService(newPc.ip)))
        viewModel.requestSwitch()
        waitUntil("the switch must offer the new computer") {
            viewModel.switchEntries().any { it.target == newPc }
        }
        assertEquals("the old computer stays in use until the pick", oldPc, viewModel.currentDestination())

        assertEquals("the entry must browse exactly once", 1, browser.startCount)
        val stages = logged.snapshot()
        assertEquals(
            "the switch browse must probe exactly the one new candidate (no hidden 3 s saved re-probe)",
            probesBeforeSwitch + 1,
            stages.count { it == DiscoveryDiagnostic.PROBE_AUTHENTICATED },
        )
        assertTrue("the browse stage must be reached: $stages", stages.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertEquals("the switch must not rewrite the token", 0, settings.tokenWrites)

        // The user's tap re-authenticates and adopts.
        viewModel.onTargetPicked(newPc)
        waitUntil("the user's pick must be adopted") { viewModel.currentDestination() == newPc }
        assertEquals("the adopted computer must be persisted", newPc.ip to newPc.port, settings.lastSaved)
        assertTrue(viewModel.canRecord.value)
    }

    @Test
    fun `a second press while the bounded browse is running restarts it as the newest intent`() {
        val settings = FakeSettings(oldPc.ip, "18099", validToken)
        // Delivers one candidate so the FIRST browse really runs and reports "searching".
        val browser = HoldDiscovery()
        val probe = FakeProbe(setOf(oldPc.ip to oldPc.port, newPc.ip to newPc.port))
        val coordinator = DiscoveryCoordinator(
            settings = settings,
            discovery = browser,
            probe = probe,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            probeDispatcher = Dispatchers.IO,
            timing = FakeTiming(),
            diagnostics = CategoryLog(),
        )
        val viewModel = RecordingViewModel(
            settings = settings,
            session = RecordingSession(),
            capture = AudioCapture(),
            clientFactory = { null },
            context = null,
            discoveryBrowser = browser,
            discoveryProbe = probe,
            coordinatorOverride = coordinator,
        )
        // R7: Ready entry is what establishes the foreground round.
        viewModel.onForeground()
        waitUntil("Ready must verify the saved computer") { viewModel.currentDestination() == oldPc }
        viewModel.pauseConnectionTaskForTest()

        viewModel.requestSwitch()
        waitUntil("the search state must be published") { viewModel.ui.value.switchSearching }
        assertEquals("the old computer stays usable while searching", oldPc, viewModel.currentDestination())
        assertEquals("the browse must have opened once", 1, browser.startCount)

        // 1C-D-04@R7 §2A: the user pressed 重新搜索 again while the window is open. The
        // newest intent must win — the running browse is cancelled and a replacement
        // starts, so the button is never a silent no-op and rounds never accumulate.
        viewModel.requestSwitch()
        waitUntil("the replacement browse must start") { browser.startCount >= 2 }
        Thread.sleep(150)
        assertTrue(
            "clicks must replace, not stack (two presses can open at most two browsers): ${browser.startCount}",
            browser.startCount <= 2,
        )
        assertEquals("the old computer stays usable throughout", oldPc, viewModel.currentDestination())

        viewModel.dismissSwitchPicker()
        viewModel.stopDiscovery()
        waitUntil("the search state must clear on teardown") { !viewModel.ui.value.switchSearching }
        assertFalse("the search state must clear on teardown", viewModel.ui.value.switchSearching)
    }

    /** A browser whose browse never delivers anything, so the window stays open. */
    private class HoldDiscovery : ServiceDiscovery {
        var startCount = 0
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount++
        }
        override fun stop() {}
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun waitIdle(h: Harness, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!h.coordinator.isRunning) {
                Thread.sleep(40)
                if (!h.coordinator.isRunning) return
            }
            Thread.sleep(10)
        }
    }

    private fun waitForTarget(h: Harness, expected: DiscoverySelection) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            if (h.viewModel.currentDestination() == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, h.viewModel.currentDestination())
    }

    private fun waitUntil(message: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue(message, condition())
    }
}
