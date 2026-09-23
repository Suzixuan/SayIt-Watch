package com.sayit.watch.net

import com.sayit.watch.settings.DestinationValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery 1C 1C-D-04@R10 — the service type the platform's RESOLVE callback really reports.
 *
 * PM installed R9 on a Galaxy Watch 7 (Android 16 / API 36) with a Windows receiver that was
 * provably reachable. The device log showed the whole chain working and then refusing its own
 * hardware:
 *
 * ```
 * found:type-accepted → resolve:queued → resolve:started → resolve:succeeded
 *                     → candidate:rejected-type → browse:no-candidate → verdict:none
 * ```
 *
 * The two `NsdServiceInfo` callbacks disagree about dots: `onServiceFound` reports
 * `_sayit-watch._tcp.` (trailing dot) while `onServiceResolved` reports `._sayit-watch._tcp` (ONE
 * LEADING dot). [DiscoveryPolicy.isOurServiceType] tolerated the first and not the second, so the
 * found check passed, the resolve succeeded, and the resolved result was thrown away.
 *
 * These tests drive the REAL [DiscoveryPolicy] and the REAL [DiscoveryCoordinator] — no test-local
 * copy of the normalization rule — and they assert the refusal side just as hard as the acceptance
 * side, because a type filter that accepts too much would trust an unrelated service on the LAN.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryNsdTypeR10Test {

    /** The exact string a real device handed to `onServiceResolved`. */
    private val resolvedSpelling = "._sayit-watch._tcp"

    private val newPc = DiscoverySelection("192.168.12.142", 18099)
    private val stalePc = DiscoverySelection("192.168.12.10", 18099)

    // ── test doubles ────────────────────────────────────────────────────────

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

    /** Reports the given services verbatim, i.e. in whatever spelling the platform used. */
    private class Browser(private val services: List<ResolvedService>) : ServiceDiscovery {
        var starts = 0
        var stops = 0
        override fun start(observer: (List<ResolvedService>) -> Unit) {
            starts++
            if (services.isNotEmpty()) observer(services)
        }
        override fun stop() {
            stops++
        }
    }

    private val timing = object : DiscoveryTiming {
        override val savedProbeTotalMs = 30L
        override val discoveryWindowMs = 80L
        override val savedProbeAttemptMs = 10L
        override val discoveryProbeAttemptMs = 10L
        override val pollIntervalMs = 5L
    }

    private fun authenticated() = DiscoveryPolicy.parseProbeResponse(
        200,
        """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
    )

    private fun rejected() = DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")

    private val goodProbe = object : DiscoveryProbe {
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int) = authenticated()
    }

    private fun service(
        ip: String,
        port: Int = 18099,
        serviceType: String = resolvedSpelling,
        protocol: String? = "1",
    ) = ResolvedService("SayIt", serviceType, ip, port, protocol)

    // ── 1. the spelling itself ──────────────────────────────────────────────

    @Test
    fun `the spelling the resolve callback reported is recognized`() {
        assertTrue(
            "the exact string a real device reported must be accepted",
            DiscoveryPolicy.isOurServiceType(resolvedSpelling),
        )
        // ...in every equivalent form the platform may combine it with.
        for (type in listOf(
            "._sayit-watch._tcp",
            "._sayit-watch._tcp.",
            "._sayit-watch._tcp.local",
            "._sayit-watch._tcp.local.",
            "._SAYIT-WATCH._TCP.",
            " ._sayit-watch._tcp. ",
            "\t._SayIt-Watch._Tcp.local.\n",
        )) {
            assertTrue("type '$type' must be accepted", DiscoveryPolicy.isOurServiceType(type))
        }
        // The forms R2 already tolerated must keep working.
        for (type in listOf(
            "_sayit-watch._tcp.",
            "_sayit-watch._tcp",
            "_sayit-watch._tcp.local.",
            "_sayit-watch._tcp.local",
        )) {
            assertTrue("type '$type' must stay accepted", DiscoveryPolicy.isOurServiceType(type))
        }
    }

    // ── 4. …while everything that is not exactly one leading dot is refused ──

    @Test
    fun `only one leading dot is tolerated and everything else is still refused`() {
        for (type in listOf(
            null,
            "",
            "   ",
            ".",
            "..",
            // two leading dots is a malformed, different name
            ".._sayit-watch._tcp",
            ".._sayit-watch._tcp.",
            ".._sayit-watch._tcp.local.",
            // an extra label / a different domain
            "._sayit-watch._tcp.example.",
            "._sayit-watch._tcp.local.example",
            "_sayit-watch._tcp.example.",
            "._sayit-watch._tcp..",
            // wrong protocol
            "._sayit-watch._udp",
            "._sayit-watch._udp.",
            "_sayit-watch._udp.local.",
            // similar but different names
            "_http._tcp.",
            "._http._tcp",
            "_other._tcp.",
            "_sayit._tcp.",
            "_sayit-watch._tcp.x",
            "sayit-watch._tcp.",
            "._sayit-watch.tcp",
            "._sayit watch._tcp",
        )) {
            assertFalse("type '$type' must be refused", DiscoveryPolicy.isOurServiceType(type))
        }
    }

    // ── 2. the resolved object passes the candidate rules ───────────────────

    @Test
    fun `a resolved service in that spelling passes classify and candidate`() {
        val resolved = service(newPc.ip)

        assertNull(
            "classify must not report a rejection for the real platform spelling",
            DiscoveryPolicy.classify(resolved),
        )
        assertTrue(
            "candidate must accept the real platform spelling",
            DiscoveryPolicy.candidate(resolved) is DestinationValidator.ValidationResult.Valid,
        )

        // The fix must not have widened anything else: the same object with a wrong type,
        // protocol, host or port is still refused by the frozen rules.
        assertEquals(
            CandidateRejection.SERVICE_TYPE,
            DiscoveryPolicy.classify(service(newPc.ip, serviceType = ".._sayit-watch._tcp")),
        )
        assertEquals(
            CandidateRejection.SERVICE_TYPE,
            DiscoveryPolicy.classify(service(newPc.ip, serviceType = "._sayit-watch._udp")),
        )
        assertEquals(
            CandidateRejection.PROTOCOL,
            DiscoveryPolicy.classify(service(newPc.ip, protocol = "2")),
        )
        assertEquals(
            CandidateRejection.HOST,
            DiscoveryPolicy.classify(service("8.8.8.8")),
        )
        assertEquals(
            CandidateRejection.PORT,
            DiscoveryPolicy.classify(service(newPc.ip, port = 0)),
        )
    }

    // ── 3. the real coordinator turns it into the one usable target ─────────

    @Test
    fun `the real coordinator authenticates the device-reported spelling into one target`() = runTest {
        val settings = Settings()
        val browser = Browser(listOf(service(newPc.ip)))
        val diagnostics = CategoryLog()
        val coordinator = DiscoveryCoordinator(
            settings = settings,
            discovery = browser,
            probe = goodProbe,
            scope = this,
            probeDispatcher = null,
            timing = timing,
            nowMs = { testScheduler.currentTime },
            diagnostics = diagnostics,
        )
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        advanceUntilIdle()

        assertTrue("the run must publish a verdict", pending.await())
        assertEquals("exactly one authenticated computer must become the target", newPc, bridge.verifiedTarget)
        assertEquals(DiscoveryState.Discovered, bridge.lastPublishedState)
        assertEquals("only the authenticated endpoint may be persisted", listOf(newPc), settings.writes)
        assertEquals(1, browser.starts)
        assertEquals(1, browser.stops)

        // The device-visible stage trail must show the run completing, and must NOT contain the
        // refusal R9 produced. (`candidate:accepted` is emitted by the platform adapter
        // `AndroidNsdDiscovery.addResolved()`, which needs a real `NsdServiceInfo`; this test hands
        // the coordinator the very snapshot that adapter publishes, so the equivalent assertions
        // are "no candidate was refused for its type" plus the direct classify/candidate
        // acceptance asserted above.)
        val trail = diagnostics.snapshot()
        assertTrue("the browse must have started: $trail", trail.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertTrue(
            "the probe must have authenticated: $trail",
            trail.contains(DiscoveryDiagnostic.PROBE_AUTHENTICATED),
        )
        assertTrue(
            "the run must end in the exactly-one verdict: $trail",
            trail.contains(DiscoveryDiagnostic.VERDICT_ONE),
        )
        assertFalse(
            "the real platform spelling must never be rejected as a type: $trail",
            trail.contains(DiscoveryDiagnostic.CANDIDATE_REJECTED_TYPE),
        )
    }

    // ── 5. the whole device story: stale address → browse → that spelling ───

    @Test
    fun `a stale saved address browses and connects through the resolve spelling`() = runTest {
        // The saved address answers nothing (the PC moved), so the run must fall through to a
        // browse — and the browse reports exactly what the device reported.
        val settings = Settings().apply {
            savedIp = stalePc.ip
            savedPort = stalePc.port.toString()
        }
        val browser = Browser(listOf(service(newPc.ip)))
        val probe = object : DiscoveryProbe {
            override fun probe(ip: String, port: Int, token: String, timeoutMs: Int) =
                if (ip == newPc.ip) authenticated() else rejected()
        }
        val diagnostics = CategoryLog()
        val coordinator = DiscoveryCoordinator(
            settings = settings,
            discovery = browser,
            probe = probe,
            scope = this,
            probeDispatcher = null,
            timing = timing,
            nowMs = { testScheduler.currentTime },
            diagnostics = diagnostics,
        )
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        val pending = async { bridge.onReadyEntered() }
        runCurrent()
        advanceUntilIdle()

        assertTrue(pending.await())
        assertEquals(
            "the computer found in the browse must become the upload target",
            newPc,
            bridge.verifiedTarget,
        )
        assertEquals(listOf(newPc), settings.writes)
        assertTrue("the stale address must have been probed first", browser.starts == 1)

        val trail = diagnostics.snapshot()
        assertTrue("the saved address must have been refused: $trail", trail.contains(DiscoveryDiagnostic.SAVED_PROBE_REJECTED))
        assertTrue("a browse must have followed it: $trail", trail.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertTrue("the target must have authenticated: $trail", trail.contains(DiscoveryDiagnostic.PROBE_AUTHENTICATED))
        assertTrue(
            "the run must end in the exactly-one verdict: $trail",
            trail.contains(DiscoveryDiagnostic.VERDICT_ONE),
        )
        assertFalse(
            "no stage may have refused the real platform spelling: $trail",
            trail.contains(DiscoveryDiagnostic.CANDIDATE_REJECTED_TYPE),
        )
    }
}
