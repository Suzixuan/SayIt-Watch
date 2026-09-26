package com.sayit.watch.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Delivery 1C 1C-D-04@R2 coverage — the discovery regression slice.
 *
 * The regression could not be narrowed because the automatic chain had no
 * observable stage boundaries at all. These tests therefore cover two things:
 *
 * 1. the new fixed-category diagnostics and the tolerant service-type match, which
 *    are what turn "自动发现失败" into an attributable stage; and
 * 2. the behaviour the user actually asked for — moving between two computers on
 *    the same Wi-Fi without retyping an IP, while two authenticated computers can
 *    never be switched between silently.
 *
 * Everything except the two probe tests uses plain fakes and finishes instantly.
 * The two real-path tests use a raw `ServerSocket` responder and real wall-clock
 * budgets, matching how the production probe actually blocks.
 */
class DiscoveryRegressionTest {

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"

    private fun realService(
        ip: String? = "192.168.12.144",
        port: Int = 18099,
        serviceType: String = SAYIT_SERVICE_TYPE_ANDROID,
        protocol: String? = "1",
        instanceName: String = "SayIt",
    ) = ResolvedService(instanceName, serviceType, ip, port, protocol)

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

    private class FakeDiscovery : ServiceDiscovery {
        var startCount = 0
        var stopCount = 0
        private var observer: ((List<ResolvedService>) -> Unit)? = null
        private val pushed = mutableListOf<ResolvedService>()

        override fun start(observer: (List<ResolvedService>) -> Unit) {
            startCount++
            this.observer = observer
            if (pushed.isNotEmpty()) observer(pushed.toList())
        }

        override fun stop() {
            stopCount++
            observer = null
        }

        fun push(service: ResolvedService) {
            pushed.add(service)
            observer?.invoke(pushed.toList())
        }

        fun pushAll(services: List<ResolvedService>) {
            pushed.addAll(services)
            observer?.invoke(pushed.toList())
        }
    }

    private class FakeProbe(private val authenticated: Set<Pair<String, Int>>) : DiscoveryProbe {
        val probed = mutableListOf<Pair<String, Int>>()

        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            probed.add(ip to port)
            return if (authenticated.contains(ip to port)) {
                DiscoveryPolicy.parseProbeResponse(200, DISCOVERY_BODY)
            } else {
                DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
            }
        }
    }

    /**
     * Test timings that keep the frozen phase ORDER (saved probe, then browse)
     * while finishing in milliseconds. The production 3 s / 8 s values are pinned
     * separately by `DiscoveryPureTest`; a virtual-time fake would make these
     * budgets meaningless, so the real wall clock is used here too.
     */
    private val fastTiming = object : DiscoveryTiming {
        override val savedProbeTotalMs = 60L
        override val discoveryWindowMs = 400L
        override val savedProbeAttemptMs = 25L
        override val discoveryProbeAttemptMs = 60L
        override val pollIntervalMs = 10L
    }

    private fun coordinator(
        settings: FakeSettings,
        discovery: ServiceDiscovery,
        probe: DiscoveryProbe,
        diagnostics: DiscoveryDiagnostics = CategoryLog(),
    ) = DiscoveryCoordinator(
        settings = settings,
        discovery = discovery,
        probe = probe,
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ),
        probeDispatcher = kotlinx.coroutines.Dispatchers.IO,
        timing = fastTiming,
        diagnostics = diagnostics,
    )

    private fun awaitRun(coordinator: DiscoveryCoordinator, timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("the run must finish inside its budgets", coordinator.isRunning)
    }

    // ── 1. The diagnostics are fixed categories and cannot carry an address ────

    @Test
    fun `the diagnostic vocabulary is a frozen set of address free categories`() {
        val all = DiscoveryDiagnostic.ALL
        assertTrue("the vocabulary must not be empty", all.isNotEmpty())
        assertEquals("categories must be unique", all.size, all.toSet().size)
        for (category in all) {
            assertTrue("category '$category' must be lowercase", category == category.lowercase())
            assertTrue("category '$category' must not carry whitespace", !category.contains(' '))
            // The whole point of a category is that it can never leak a network
            // layout: no IPv4 shape and no dotted decimal at all.
            assertFalse(
                "category '$category' must not contain an address-like value",
                Regex("""\d{1,3}\.\d{1,3}""").containsMatchIn(category),
            )
            assertFalse("category '$category' must not name a host", category.contains(".local"))
        }
        // The stages the contract asks to keep distinguishable must all exist.
        for (required in listOf(
            DiscoveryDiagnostic.SAVED_PROBE_ACCEPTED,
            DiscoveryDiagnostic.SAVED_PROBE_REJECTED,
            DiscoveryDiagnostic.BROWSE_STARTED,
            DiscoveryDiagnostic.BROWSE_START_FAILED,
            DiscoveryDiagnostic.FOUND_OTHER_TYPE,
            DiscoveryDiagnostic.RESOLVE_FAILED,
            DiscoveryDiagnostic.CANDIDATE_REJECTED_HOST,
            DiscoveryDiagnostic.PROBE_REJECTED,
            DiscoveryDiagnostic.VERDICT_NONE,
        )) {
            assertTrue("missing stage category $required", all.contains(required))
        }
    }

    @Test
    fun `the category log is bounded and reports insertion order`() {
        val log = CategoryLog(capacity = 3)
        listOf("a", "b", "c", "d", "e").forEach { log.note(it) }
        assertEquals(listOf("c", "d", "e"), log.snapshot())
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    // ── 2. The service-type match tolerates what the platform really reports ───

    @Test
    fun `the platform service type variants are all recognized`() {
        for (type in listOf(
            "_sayit-watch._tcp.",
            "_sayit-watch._tcp",
            "_sayit-watch._tcp.local.",
            "_sayit-watch._tcp.local",
            "_SAYIT-WATCH._TCP.",
            " _sayit-watch._tcp. ",
            // 1C-D-04@R10: what onServiceResolved really reports on a Galaxy Watch 7
            // (Android 16 / API 36) — the SAME type with a single LEADING dot. R9 accepted the
            // found callback's trailing-dot form and then refused this one, so the device
            // resolved its own receiver and reported candidate:rejected-type. This list is
            // exactly where the miss hid: the real device spelling was simply absent.
            "._sayit-watch._tcp",
            "._sayit-watch._tcp.",
            "._sayit-watch._tcp.local",
            "._sayit-watch._tcp.local.",
            "._SAYIT-WATCH._TCP. ",
        )) {
            assertTrue("type '$type' must be accepted", DiscoveryPolicy.isOurServiceType(type))
        }
        for (type in listOf(
            null, "", "   ", "_http._tcp.", "_sayit-watch._udp.", "_sayit-watch._udp.local.",
            "_sayit-watch._tcp.example.", "_other._tcp.", "_sayit._tcp.", "sayit-watch._tcp.",
            // 1C-D-04@R10: ONE leading dot is a platform variant, two is a different name, and
            // the widening must not leak into unrelated types.
            ".", "..", ".._sayit-watch._tcp", ".._sayit-watch._tcp.local.",
            "._http._tcp", "._sayit-watch._udp", "._sayit-watch._tcp.example.",
        )) {
            assertFalse("type '$type' must be rejected", DiscoveryPolicy.isOurServiceType(type))
        }
    }

    @Test
    fun `a rejection is reported as a fixed category for each reason`() {
        assertNull("a valid instance must classify as acceptable", DiscoveryPolicy.classify(realService()))
        assertEquals(
            CandidateRejection.SERVICE_TYPE,
            DiscoveryPolicy.classify(realService(serviceType = "_http._tcp.")),
        )
        assertEquals(
            CandidateRejection.PROTOCOL,
            DiscoveryPolicy.classify(realService(protocol = "2")),
        )
        assertEquals(CandidateRejection.HOST, DiscoveryPolicy.classify(realService(ip = null)))
        assertEquals(CandidateRejection.HOST, DiscoveryPolicy.classify(realService(ip = "pc.local")))
        assertEquals(CandidateRejection.HOST, DiscoveryPolicy.classify(realService(ip = "10.0.0.300")))
        assertEquals(CandidateRejection.HOST, DiscoveryPolicy.classify(realService(ip = "8.8.8.8")))
        assertEquals(CandidateRejection.PORT, DiscoveryPolicy.classify(realService(port = 0)))
        assertEquals(CandidateRejection.PORT, DiscoveryPolicy.classify(realService(port = 65536)))
        // The category itself is the frozen diagnostic string, never a value dump.
        assertEquals(
            DiscoveryDiagnostic.CANDIDATE_REJECTED_TYPE,
            CandidateRejection.SERVICE_TYPE.category,
        )
    }

    @Test
    fun `a rejection reason no longer echoes the observed value`() {
        val result = DiscoveryPolicy.candidate(realService(serviceType = "_http._tcp."))
        assertTrue(result is com.sayit.watch.settings.DestinationValidator.ValidationResult.Invalid)
        val reason = (result as com.sayit.watch.settings.DestinationValidator.ValidationResult.Invalid).reason
        assertFalse("the reason must not echo the platform type", reason.contains("_http"))
        assertFalse("the reason must not name a host", reason.contains("192.168"))
    }

    // ── 3. Old address dead → the current computer is discovered and saved ─────

    @Test
    fun `a stale saved address is forgotten and the current computer is adopted`() = runBlocking {
        val settings = FakeSettings("192.168.12.100", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.142" to 18099))
        val logged = CategoryLog()
        val coordinator = coordinator(settings, discovery, probe, logged)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        discovery.push(realService(ip = "192.168.12.142"))
        assertTrue(bridge.onReadyEntered())

        // The scenario the user reported: manual reachability was fine, the old
        // address was not, and the laptop/desktop moved.
        assertEquals(DiscoverySelection("192.168.12.142", 18099), bridge.verifiedTarget)
        assertEquals("192.168.12.142" to 18099, settings.lastSaved)
        assertEquals(1, settings.savedCount)
        assertEquals("192.168.12.100" to 18099, probe.probed.first())

        val stages = logged.snapshot()
        assertTrue("the saved probe must be reported stale: $stages", stages.contains(DiscoveryDiagnostic.SAVED_PROBE_REJECTED))
        assertTrue("a browse must have started: $stages", stages.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertTrue("the probe must have authenticated: $stages", stages.contains(DiscoveryDiagnostic.PROBE_AUTHENTICATED))
        assertTrue("the verdict must be exactly one: $stages", stages.contains(DiscoveryDiagnostic.VERDICT_ONE))
        assertFalse("no fallback may be recorded: $stages", stages.contains(DiscoveryDiagnostic.VERDICT_NONE))
    }

    // ── 4. Old address alive → keep it; an explicit switch goes looking ────────

    @Test
    fun `a live saved address is kept and an explicit switch bypasses it`() = runBlocking {
        val settings = FakeSettings("192.168.12.100", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.100" to 18099, "192.168.12.142" to 18099))
        val coordinator = coordinator(settings, discovery, probe)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        // Frozen behaviour: the old computer still answers, so it stays in use and
        // no browse would be opened by an ordinary Ready entry.
        assertTrue(bridge.onReadyEntered())
        assertEquals(DiscoverySelection("192.168.12.100", 18099), bridge.verifiedTarget)
        assertEquals(0, discovery.startCount)

        // The user taps 切换电脑: the explicit browse must NOT consult the saved
        // priority, so the other computer becomes selectable without typing an IP.
        // 1C-D-04@R7 必修 2: the browse only OFFERS what authenticated; it never adopts.
        // The current computer is not re-probed inside the browse, so it stays in use.
        val keep = DiscoverySelection("192.168.12.100", 18099)
        val runId = bridge.newSwitchRun()
        discovery.push(realService(ip = "192.168.12.142"))
        assertEquals(
            "the explicit switch must offer exactly the other computer",
            listOf(DiscoverySelection("192.168.12.142", 18099)),
            bridge.onSwitchBrowse(runId),
        )
        assertEquals(
            "the picker must offer the current computer plus the new one",
            listOf(keep, DiscoverySelection("192.168.12.142", 18099)),
            bridge.pickerCandidates,
        )
        assertEquals(
            "the current computer must still be in use until the picker confirms",
            keep,
            bridge.verifiedTarget,
        )
        assertTrue(
            "both computers must be remembered: ${bridge.rememberedTargets}",
            bridge.rememberedTargets.contains(DiscoverySelection("192.168.12.142", 18099)),
        )
        assertEquals(1, discovery.startCount)
        assertEquals("the picker persists, not the browse", 0, settings.savedCount)

        // The user confirms the switch: now — and only now — the new target is stored.
        bridge.onTargetChosen(DiscoverySelection("192.168.12.142", 18099))
        assertEquals(DiscoverySelection("192.168.12.142", 18099), bridge.verifiedTarget)
        assertEquals("192.168.12.142" to 18099, settings.lastSaved)
        assertEquals(1, settings.savedCount)
    }

    // ── 5. Two authenticated computers must be chosen explicitly ──────────────

    @Test
    fun `two authenticated computers are never switched between silently`() = runBlocking {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.100" to 18099, "192.168.12.142" to 18099))
        val logged = CategoryLog()
        val coordinator = coordinator(settings, discovery, probe, logged)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        discovery.pushAll(listOf(realService(ip = "192.168.12.100"), realService(ip = "192.168.12.142")))
        assertTrue(bridge.onReadyEntered())

        assertFalse("an ambiguous discovery must verify nothing", bridge.hasVerifiedTarget)
        assertNull(bridge.verifiedTarget)
        assertEquals("an ambiguous discovery must persist nothing", 0, settings.savedCount)
        assertTrue(
            "the ambiguity must be recorded as such: ${logged.snapshot()}",
            logged.snapshot().contains(DiscoveryDiagnostic.VERDICT_AMBIGUOUS),
        )
        assertEquals(
            "both authenticated computers must be offered",
            setOf(DiscoverySelection("192.168.12.100", 18099), DiscoverySelection("192.168.12.142", 18099)),
            bridge.rememberedTargets.toSet(),
        )

        // Only the user's explicit pick persists a target.
        bridge.onTargetChosen(DiscoverySelection("192.168.12.142", 18099))
        assertEquals(DiscoverySelection("192.168.12.142", 18099), bridge.verifiedTarget)
        assertTrue(bridge.hasVerifiedTarget)
        assertEquals("192.168.12.142" to 18099, settings.lastSaved)
        assertEquals(1, settings.savedCount)
    }

    // ── 6. Candidates rejected by type/address are never probed ───────────────

    @Test
    fun `rejected candidates are never probed and are reported by category`() = runBlocking {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.142" to 18099))
        val logged = CategoryLog()
        val coordinator = coordinator(settings, discovery, probe, logged)

        discovery.pushAll(
            listOf(
                realService(ip = "192.168.12.50", serviceType = "_http._tcp."),
                realService(ip = "pc.local"),
                realService(ip = "8.8.8.8"),
                realService(ip = "192.168.12.142"),
            ),
        )
        coordinator.ensureResolved {}
        awaitRun(coordinator)

        assertEquals(DiscoveryState.Discovered, coordinator.state)
        assertEquals(
            "only the RFC1918 literal may ever be probed",
            listOf("192.168.12.142" to 18099),
            probe.probed,
        )
        // The per-candidate refusal category is produced where the platform object
        // is translated (AndroidNsdDiscovery) and is pinned by the classify test
        // above; here the observable contract is that a refused candidate never
        // reaches the network.
        val stages = logged.snapshot()
        assertTrue("the browse must have opened: $stages", stages.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertTrue("the surviving candidate must authenticate: $stages", stages.contains(DiscoveryDiagnostic.PROBE_AUTHENTICATED))
    }

    @Test
    fun `a browse that resolves nothing reports the browse stage, not a probe`() = runBlocking {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(emptySet())
        val logged = CategoryLog()
        val coordinator = coordinator(settings, discovery, probe, logged)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        assertTrue(bridge.onReadyEntered())
        assertFalse(bridge.hasVerifiedTarget)
        assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)

        val stages = logged.snapshot()
        assertTrue("the browse must have started: $stages", stages.contains(DiscoveryDiagnostic.BROWSE_STARTED))
        assertTrue(
            "a browse with no candidate must be distinguishable from a failed probe: $stages",
            stages.contains(DiscoveryDiagnostic.BROWSE_NO_CANDIDATE),
        )
        assertFalse("no probe may have been attempted: $stages", stages.contains(DiscoveryDiagnostic.PROBE_REJECTED))
        assertTrue("the zero verdict must be recorded: $stages", stages.contains(DiscoveryDiagnostic.VERDICT_NONE))
        assertEquals("a failed discovery persists nothing", 0, settings.savedCount)
    }

    // ── 7. Cancellation and late callbacks ────────────────────────────────────

    @Test
    fun `a cancelled browse still stops the browser exactly once and persists nothing`() = runBlocking {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.142" to 18099))
        val coordinator = coordinator(settings, discovery, probe)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        val runId = bridge.newSwitchRun()
        val pending = launch(kotlinx.coroutines.Dispatchers.Default) {
            bridge.onSwitchBrowse(runId)
        }
        Thread.sleep(50)
        // The user closes the picker / leaves the page while the window is open.
        bridge.onStopped()
        discovery.push(realService(ip = "192.168.12.142"))
        pending.join()

        assertNull("a cancelled switch must not verify a target", bridge.verifiedTarget)
        assertEquals("a cancelled switch must persist nothing", 0, settings.savedCount)
        assertEquals("the browser must be stopped", discovery.startCount, discovery.stopCount)
        assertFalse(coordinator.isRunning)
    }

    /**
     * 1C-D-04@R2: `NsdManager.resolveService` is single-flight, so issuing it once per
     * `onServiceFound` loses everything after the first instance. These are the rules
     * of the replacement queue — plain Kotlin, no emulator.
     */
    @Test
    fun `the resolve queue issues exactly one request at a time`() {
        val queue = SerialResolveQueue<String>()
        assertTrue(queue.offer("a"))
        assertTrue(queue.offer("b"))
        assertTrue(queue.offer("c"))

        assertEquals("a", queue.poll())
        assertTrue("the in-flight request must block the next one", queue.hasInFlight)
        assertEquals("nothing else may be handed out yet", null, queue.poll())
        queue.complete("a", transientFailure = false, retries = 0)
        assertEquals("b", queue.poll())
    }

    @Test
    fun `the resolve queue never doubles the same instance`() {
        val queue = SerialResolveQueue<String>()
        assertTrue(queue.offer("SayIt"))
        assertFalse("a repeat found-callback is a duplicate, not a new resolve", queue.offer("SayIt"))
        assertEquals(1, queue.droppedAsDuplicate)
        assertEquals(1, queue.pendingCount)
        queue.poll()
        assertFalse("the in-flight instance is still a duplicate", queue.offer("SayIt"))
        assertEquals(2, queue.droppedAsDuplicate)
    }

    @Test
    fun `the resolve queue is bounded and drops the oldest pending item`() {
        val queue = SerialResolveQueue<String>(capacity = 3)
        listOf("a", "b", "c", "d", "e").forEach { queue.offer(it) }
        assertEquals(3, queue.pendingCount)
        assertEquals(2, queue.droppedByCapacity)
        assertEquals("the oldest pending items must be the ones dropped", "c", queue.poll())
    }

    @Test
    fun `the resolve queue retries a busy framework only a bounded number of times`() {
        val queue = SerialResolveQueue<String>(maxRetries = 2)
        queue.offer("SayIt")
        queue.poll()
        assertTrue("attempt 0 may retry", queue.complete("SayIt", transientFailure = true, retries = 0))
        queue.poll()
        assertTrue("attempt 1 may retry", queue.complete("SayIt", transientFailure = true, retries = 1))
        queue.poll()
        assertFalse("attempt 2 must give up", queue.complete("SayIt", transientFailure = true, retries = 2))
        // A hard failure never retries, and clearing forgets the whole session.
        queue.offer("SayIt")
        queue.poll()
        assertFalse(queue.complete("SayIt", transientFailure = false, retries = 0))
        queue.offer("SayIt")
        queue.clear()
        assertEquals(0, queue.pendingCount)
        assertFalse(queue.hasInFlight)
    }

    // ── 9. An invalidated target never re-sends the audio that failed ─────────

    @Test
    fun `an invalidated target is re-discovered but the failed endpoint is not reconsidered`() = runBlocking {
        val settings = FakeSettings("192.168.12.100", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.100" to 18099))
        val coordinator = coordinator(settings, discovery, probe)
        val bridge = ResolverBridge.forCoordinator(coordinator) { true }

        assertTrue(bridge.onReadyEntered())
        assertEquals(DiscoverySelection("192.168.12.100", 18099), bridge.verifiedTarget)
        val probedBeforeFailure = probe.probed.size

        // The upload failed: the target is dropped, and the WAV is discarded by the
        // ViewModel. Only the NEXT resolution may look again, and it starts from an
        // empty saved address rather than replaying the dead endpoint forever.
        bridge.onTargetInvalidated()
        assertNull(bridge.verifiedTarget)
        assertFalse(bridge.hasVerifiedTarget)
        settings.savedIp = ""
        settings.savedPort = ""

        assertTrue(bridge.onReadyEntered())
        assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)
        assertEquals(
            "the dead endpoint must not be probed again by a browse that has no candidate",
            probedBeforeFailure,
            probe.probed.size,
        )
        assertEquals(
            "a re-probed saved address saves nothing new",
            0,
            settings.savedCount,
        )
    }

    // ── 8. The real HTTP probe is the same for manual and discovered targets ──

    @Test
    fun `a discovered address and a manual address go through the same authenticated probe`() {
        val server = RawProbeServer()
        try {
            val probe = HttpDiscoveryProbe()
            val manual = probe.probe("127.0.0.1", server.port, validToken, 2_000)
            assertTrue("the manual path must authenticate", manual is DiscoveryProbeResult.Authenticated)
            assertTrue(
                "the discovery path must use the very same probe",
                probe.probe("127.0.0.1", server.port, "b".repeat(64), 2_000) is DiscoveryProbeResult.Rejected,
            )
        } finally {
            server.close()
        }
    }

    private fun goodBody() = DISCOVERY_BODY

    companion object {
        /** The frozen Windows success body: exactly service + protocol. */
        private val DISCOVERY_BODY =
            """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}"""
    }

    /**
     * Minimal raw-socket responder serving the frozen discovery success body to the
     * correct Bearer token, so [HttpDiscoveryProbe] is exercised without a test HTTP
     * library on the classpath.
     */
    private class RawProbeServer : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        reader.readLine()
                        var auth: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) {
                                auth = line.substringAfter(':').trim()
                            }
                        }
                        val ok = auth == "Bearer $validToken"
                        val body = if (ok) {
                            """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}"""
                        } else {
                            """{"error":"unauthorized"}"""
                        }
                        val header = "HTTP/1.1 ${if (ok) "200 OK" else "401 Unauthorized"}\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                            "Connection: close\r\n\r\n"
                        write(it.getOutputStream(), header + body)
                    }
                }
            } catch (e: Exception) {
                // closed by close()
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = socket.localPort

        private fun write(out: OutputStream, text: String) {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        override fun close() {
            socket.close()
        }

        private val validToken =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    }
}
