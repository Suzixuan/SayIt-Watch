package com.sayit.watch.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryIntegrationTest {
    private val target = DiscoverySelection("192.168.12.144", 18099)
    private val manual = DiscoverySelection("192.168.12.145", 18099)

    private class Settings : DiscoverySettings {
        override var savedIp = ""
        override var savedPort = ""
        override var devToken = "a".repeat(64)
        val writes = mutableListOf<DiscoverySelection>()
        override fun hasValidToken(): Boolean =
            com.sayit.watch.settings.DevTokenValidator.isValid(devToken)
        override fun saveDevToken(token: String) {
            devToken = token
        }
        override fun saveDestination(ip: String, port: Int) {
            savedIp = ip
            savedPort = port.toString()
            writes.add(DiscoverySelection(ip, port))
        }
    }

    private class Browser(private val target: DiscoverySelection?) : ServiceDiscovery {
        var starts = 0
        var stops = 0
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            starts++
            target?.let {
                observer(listOf(ResolvedService("SayIt", SAYIT_SERVICE_TYPE_ANDROID, it.ip, it.port, "1")))
            }
        }
        override fun stop() { stops++ }
    }

    private val timing = object : DiscoveryTiming {
        override val savedProbeTotalMs = 30L
        override val discoveryWindowMs = 80L
        override val savedProbeAttemptMs = 10L
        override val discoveryProbeAttemptMs = 10L
        override val pollIntervalMs = 5L
    }

    private fun authenticated() = DiscoveryPolicy.parseProbeResponse(
        200, """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
    )

    private val goodProbe = object : DiscoveryProbe {
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int) = authenticated()
    }

    @Test
    fun `production bridge waits for asynchronous coordinator verdict before exposing target`() = runTest {
        val settings = Settings()
        val browser = Browser(target)
        val coordinator = DiscoveryCoordinator(settings, browser, goodProbe,
            scope = this, probeDispatcher = null, timing = timing, nowMs = { testScheduler.currentTime })
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }
        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        assertFalse("launching discovery is not its result", pending.isCompleted)
        assertNull(bridge.verifiedTarget)
        advanceUntilIdle()
        assertTrue(pending.await())
        assertEquals(target, bridge.verifiedTarget)
        assertEquals(listOf(target), settings.writes)
        assertEquals(1, browser.stops)
        assertFalse("returning to Ready must preserve authentication", bridge.onReadyEntered())
        assertEquals(1, browser.starts)
    }

    @Test
    fun `a synchronous saved verdict cannot detach the next asynchronous run`() = runTest {
        val settings = Settings().apply { savedIp = target.ip; savedPort = target.port.toString() }
        val browser = Browser(target)
        val coordinator = DiscoveryCoordinator(settings, browser, goodProbe,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)),
            probeDispatcher = null, timing = timing, nowMs = { testScheduler.currentTime })
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }
        assertTrue(bridge.onReadyEntered())
        bridge.onTargetInvalidated()
        settings.savedIp = ""
        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceUntilIdle()
        assertTrue(pending.await())
        assertEquals(target, bridge.verifiedTarget)
        assertEquals(1, browser.starts)
    }

    @Test
    fun `production automatic failure reaches fallback only after the window`() = runTest {
        val settings = Settings()
        val browser = Browser(null)
        val coordinator = DiscoveryCoordinator(settings, browser, goodProbe,
            scope = this, probeDispatcher = null, timing = timing, nowMs = { testScheduler.currentTime })
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }
        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceUntilIdle()
        assertTrue(pending.await())
        assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)
        assertNull(bridge.verifiedTarget)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `opening Config cancels old automatic result and stops its browser exactly once`() = runTest {
        val settings = Settings()
        val browser = Browser(target)
        val coordinator = DiscoveryCoordinator(settings, browser, goodProbe,
            scope = this, probeDispatcher = null, timing = timing, nowMs = { testScheduler.currentTime })
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }
        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        bridge.onConfigEntered()
        assertTrue(bridge.onManualRequested(manual))
        advanceUntilIdle()
        assertFalse("cancelled result must not update the UI", pending.await())
        assertEquals(manual, bridge.verifiedTarget)
        assertEquals(listOf(manual), settings.writes)
        assertEquals(1, browser.stops)
        assertFalse(bridge.onReadyEntered())
    }

    @Test
    fun `late manual authentication through production adapter cannot persist after Config`() = runBlocking {
        val settings = Settings()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val probe = object : DiscoveryProbe {
            override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
                entered.complete(Unit)
                check(release.await(2, TimeUnit.SECONDS))
                return authenticated()
            }
        }
        val coordinator = DiscoveryCoordinator(settings, Browser(null), probe,
            scope = this, probeDispatcher = Dispatchers.IO)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }
        val pending = async { bridge.onManualRequested(manual) }
        try {
            kotlinx.coroutines.withTimeout(2_000) { entered.await() }
            bridge.onConfigEntered()
        } finally {
            release.countDown()
        }
        assertFalse(pending.await())
        assertNull(bridge.verifiedTarget)
        assertTrue("authentication alone must not bypass generation", settings.writes.isEmpty())
        assertNull(coordinator.resolved)
    }

    @Test
    fun `cancel without replacement rejects a late automatic result`() = runTest {
        val response = CompletableDeferred<DiscoveryOutcome?>()
        val bridge = ResolverBridge({ true }, { response.await() }, { _, _ -> true })
        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        bridge.onConfigEntered()
        response.complete(DiscoveryOutcome(null, target, listOf(target)))
        advanceUntilIdle()
        assertFalse(pending.await())
        assertNull(bridge.verifiedTarget)
        assertNull(bridge.lastPublishedState)
    }
}
