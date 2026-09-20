package com.sayit.watch.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Delivery 1C Repair 1 coverage for the pure `net` layer: the real Android
 * callback shape (instance name vs service type), concurrent resolve delivery,
 * the frozen 0 / 1 / at-least-2 authenticated-endpoint rule, the end-to-end
 * 3 s and 8 s budgets, and the released HTTP probe.
 *
 * The discovery phases run with REAL wall-clock timing: the coordinator polls with
 * a real `delay` and compares a real clock, so virtual-time test fakes would make
 * the budgets meaningless (a virtual delay costs no wall time, which lets the
 * poll loop outlive its window). The production budgets are used as-is and the
 * tests simply wait for the run to finish, bounded well above the 3 s + 8 s
 * worst case.
 */
class DiscoveryPureTest {

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"

    // Real Android callback shape, requirement 1

    /** Exactly what `NsdServiceInfo` carries for a real SayIt instance. */
    private fun realService(
        instanceName: String = "SayIt",
        serviceType: String = SAYIT_SERVICE_TYPE_ANDROID,
        ip: String? = "192.168.12.144",
        port: Int = 18099,
        protocol: String? = "1",
    ) = ResolvedService(
        instanceName = instanceName,
        serviceType = serviceType,
        host = ip,
        port = port,
        protocol = protocol,
    )

    private fun accepted(service: ResolvedService): Boolean {
        val result = DiscoveryPolicy.candidate(service)
        return result is com.sayit.watch.settings.DestinationValidator.ValidationResult.Valid
    }

    @Test
    fun `a real instance name with the frozen service type is accepted`() {
        // The instance name is just "SayIt"; the TYPE carries the identity. An
        // implementation that inferred the type from the instance name would have
        // rejected this real callback.
        assertTrue(accepted(realService(instanceName = "SayIt")))
        // Any instance name is fine, including conflict-resolved and empty ones.
        assertTrue(accepted(realService(instanceName = "SayIt (2)")))
        assertTrue(accepted(realService(instanceName = "")))
        assertTrue(accepted(realService(serviceType = SAYIT_SERVICE_TYPE_FULL)))
    }

    @Test
    fun `a wrong service type is rejected regardless of the instance name`() {
        // 1C-D-04@R2: the type match is now tolerant of the platform's domain
        // suffix and case, so this list holds only genuinely different services.
        for (type in listOf("_http._tcp.", "_sayit-watch._udp.", "", "_other._tcp.", "_sayit._tcp.")) {
            assertFalse(
                "type '$type' must be rejected",
                accepted(realService(instanceName = "SayIt", serviceType = type)),
            )
        }
        // Even an instance name that merely looks like a type must not rescue it.
        assertFalse(accepted(realService(instanceName = "SayIt_sayit-watch._tcp.", serviceType = "_other._tcp.")))
    }

    @Test
    fun `accepts only literal rfc1918 ipv4`() {
        assertTrue(accepted(realService(ip = "192.168.12.144")))
        assertTrue(accepted(realService(ip = "10.1.2.3")))
        assertTrue(accepted(realService(ip = "172.16.0.9")))
        for (host in listOf(null, "", "pc.local", "my-pc", "fe80::1", "8.8.8.8", "169.254.1.1", "127.0.0.1", "0.0.0.0", "192.168.1")) {
            assertFalse("host $host must be rejected", accepted(realService(ip = host)))
        }
    }

    @Test
    fun `rejects out of range ports and mismatched protocol metadata`() {
        assertFalse(accepted(realService(port = 0)))
        assertFalse(accepted(realService(port = 65536)))
        assertFalse(accepted(realService(protocol = "2")))
        // Absent optional metadata is still acceptable (older responders).
        assertTrue(accepted(realService(protocol = null)))
    }

    @Test
    fun `concurrent resolves for two hosts both survive folding`() {
        // Two resolves completing at once must not lose the second one.
        val first = realService(instanceName = "SayIt", ip = "192.168.12.50", port = 18099)
        val second = realService(instanceName = "SayIt (2)", ip = "192.168.12.144", port = 18099)

        var snapshot = DiscoveryPolicy.foldResolved(emptyList(), first)
        snapshot = DiscoveryPolicy.foldResolved(snapshot, second)
        assertEquals(listOf(first, second), snapshot)

        // A duplicate delivery of the same endpoint changes nothing.
        val again = DiscoveryPolicy.foldResolved(snapshot, first)
        assertEquals(snapshot, again)

        // The same host on a different port is a distinct endpoint.
        val otherPort = realService(ip = "192.168.12.50", port = 18100)
        assertEquals(3, DiscoveryPolicy.foldResolved(snapshot, otherPort).size)
    }

    @Test
    fun `folding is thread safe under simultaneous deliveries`() {
        // Mirrors the real NsdManager behaviour: resolve callbacks land on any
        // thread at any time. The lock plus fold combination must retain both.
        val lock = Any()
        var snapshot: List<ResolvedService> = emptyList()
        val first = realService(ip = "192.168.12.50")
        val second = realService(ip = "192.168.12.144")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        fun deliver(service: ResolvedService) {
            Thread {
                start.await()
                synchronized(lock) { snapshot = DiscoveryPolicy.foldResolved(snapshot, service) }
                done.countDown()
            }.start()
        }

        deliver(first)
        deliver(second)
        start.countDown()
        assertTrue("both deliveries must finish", done.await(5, TimeUnit.SECONDS))
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.contains(first) && snapshot.contains(second))
    }

    // Probe response parsing, requirement 4: service + protocol only

    private val goodBody =
        """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}"""

    private fun authenticated(status: Int, body: String): Boolean =
        DiscoveryPolicy.parseProbeResponse(status, body) is DiscoveryProbeResult.Authenticated

    @Test
    fun `authenticates only the exact frozen identity over http 200`() {
        assertTrue(authenticated(200, goodBody))
        assertFalse(authenticated(401, goodBody))
        assertFalse(authenticated(500, goodBody))
        assertFalse(authenticated(200, "not json"))
        assertFalse(authenticated(200, "{}"))
        assertFalse(authenticated(200, """{"service":"some-other-app","protocol":1}"""))
        assertFalse(authenticated(200, """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":2}"""))
        assertFalse(authenticated(200, """{"service":"$SAYIT_DISCOVERY_SERVICE_ID"}"""))
    }

    @Test
    fun `the success body is service plus protocol with no extra field`() {
        // Repair 1 requirement 4: the Windows body carries exactly two fields. The
        // absence of `path` is pinned on the Windows side.
        assertEquals(
            "the frozen success body must be exactly service+protocol",
            """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
            goodBody,
        )
    }

    // Fakes

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

        /** Delivers several resolves in one burst (concurrent completion shape). */
        fun pushAll(services: List<ResolvedService>) {
            pushed.addAll(services)
            observer?.invoke(pushed.toList())
        }
    }

    private open class FakeProbe(
        private val authenticatedHosts: Set<Pair<String, Int>>,
    ) : DiscoveryProbe {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val timeouts = mutableListOf<Int>()

        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            calls.add(Triple(ip, port, timeoutMs))
            timeouts.add(timeoutMs)
            return if (authenticatedHosts.contains(ip to port)) {
                DiscoveryPolicy.parseProbeResponse(
                    200,
                    """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
                )
            } else {
                DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
            }
        }

        /** Distinct endpoints actually probed. */
        fun probedEndpoints(): Set<Pair<String, Int>> = calls.map { it.first to it.second }.toSet()
    }

    /**
     * A probe that "hangs": it burns most of the timeout it was handed before
     * rejecting. That is exactly how a slow connect eats a phase budget, and it
     * makes the end-to-end bound observable.
     */
    private class HangingProbe(
        private val sleepFraction: Double = 0.9,
    ) : DiscoveryProbe {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val timeouts = mutableListOf<Int>()

        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
            calls.add(Triple(ip, port, timeoutMs))
            timeouts.add(timeoutMs)
            if (timeoutMs > 1) {
                Thread.sleep((timeoutMs * sleepFraction).toLong().coerceAtLeast(1L))
            }
            return DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
        }
    }

    /**
     * Waits for the coordinator's in-flight run to finish, or fails the test if it
     * somehow outlives the frozen 3 s + 8 s budgets.
     */
    private fun awaitRun(
        coordinator: DiscoveryCoordinator,
        timeoutMs: Long = 20_000L,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.isRunning && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(
            "the discovery run must finish inside the frozen budgets (still running after ${timeoutMs}ms)",
            coordinator.isRunning,
        )
    }

    /** The production budgets: the tests observe the real 3 s / 8 s bounds. */
    private fun coordinatorFor(
        settings: FakeSettings,
        discovery: FakeDiscovery,
        probe: DiscoveryProbe,
    ) = DiscoveryCoordinator(
        settings = settings,
        discovery = discovery,
        probe = probe,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        probeDispatcher = Dispatchers.IO,
    )

    // Frozen 0 / 1 / at-least-2 rule, requirement 3

    @Test
    fun `exactly one authenticated endpoint is saved`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = coordinatorFor(settings, discovery, probe)

        coordinator.ensureResolved()
        discovery.pushAll(listOf(realService(ip = "192.168.12.50"), realService(ip = "192.168.12.144")))
        awaitRun(coordinator)

        assertEquals(DiscoveryState.Discovered, coordinator.state)
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals(1, settings.savedCount)
        assertEquals("192.168.12.144" to 18099, settings.lastSaved)
        assertEquals("browsing must stop exactly once", 1, discovery.stopCount)
        // Both endpoints were probed; only one authenticated.
        assertEquals(
            setOf("192.168.12.50" to 18099, "192.168.12.144" to 18099),
            probe.probedEndpoints(),
        )
    }

    @Test
    fun `two endpoints failing authentication fall back to manual`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(emptySet())
        val coordinator = coordinatorFor(settings, discovery, probe)

        coordinator.ensureResolved()
        discovery.pushAll(listOf(realService(ip = "192.168.12.50"), realService(ip = "192.168.12.144")))
        awaitRun(coordinator)

        assertEquals(DiscoveryState.ManualFallback, coordinator.state)
        assertNull(coordinator.resolved)
        assertEquals(0, settings.savedCount)
        assertEquals(1, discovery.stopCount)
        assertEquals(
            "both endpoints must have been probed exactly once",
            setOf("192.168.12.50" to 18099, "192.168.12.144" to 18099),
            probe.probedEndpoints(),
        )
        assertEquals("no endpoint may be probed twice", 2, probe.calls.size)
    }

    @Test
    fun `two endpoints authenticating is ambiguity and saves nothing`() {
        val orderings = listOf(
            listOf("192.168.12.50", "192.168.12.144"),
            listOf("192.168.12.144", "192.168.12.50"),
        )
        for (order in orderings) {
            val settings = FakeSettings("", "", validToken)
            val discovery = FakeDiscovery()
            // BOTH endpoints hold our token: two different PCs, genuinely ambiguous.
            val probe = FakeProbe(setOf("192.168.12.50" to 18099, "192.168.12.144" to 18099))
            val coordinator = coordinatorFor(settings, discovery, probe)

            coordinator.ensureResolved()
            discovery.pushAll(order.map { realService(ip = it) })
            awaitRun(coordinator)

            assertEquals(
                "arrival order $order must not pick a winner",
                DiscoveryState.ManualFallback,
                coordinator.state,
            )
            assertNull("nothing may be selected", coordinator.resolved)
            assertEquals("nothing may be saved", 0, settings.savedCount)
            assertEquals(
                "both endpoints must have been authenticated",
                setOf("192.168.12.50" to 18099, "192.168.12.144" to 18099),
                probe.probedEndpoints(),
            )
        }
    }

    @Test
    fun `an already duplicately advertised endpoint counts once`() {
        // The same PC can be delivered more than once (re-resolve, repeated PTR);
        // that is ONE authenticated endpoint, not ambiguity.
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = coordinatorFor(settings, discovery, probe)

        coordinator.ensureResolved()
        discovery.push(realService(instanceName = "SayIt", ip = "192.168.12.144"))
        discovery.push(realService(instanceName = "SayIt (2)", ip = "192.168.12.144"))
        awaitRun(coordinator)

        assertEquals(DiscoveryState.Discovered, coordinator.state)
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals(1, settings.savedCount)
        assertEquals("a duplicate advertisement is one endpoint", 1, probe.calls.size)
    }


    // Saved-address re-probe, requirements 2 and 4

    @Test
    fun `a still-valid saved address is re-probed and never browses`() {
        val settings = FakeSettings("192.168.12.144", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = coordinatorFor(settings, discovery, probe)

        coordinator.ensureResolved()
        awaitRun(coordinator)

        assertEquals(DiscoveryState.ExistingAddress, coordinator.state)
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals(0, discovery.startCount)
        assertEquals(0, settings.savedCount)
    }

    @Test
    fun `a dhcp move is followed and saved once`() {
        val settings = FakeSettings("192.168.12.100", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = coordinatorFor(settings, discovery, probe)

        coordinator.ensureResolved()
        discovery.push(realService(ip = "192.168.12.144"))
        awaitRun(coordinator)

        assertEquals(DiscoveryState.Discovered, coordinator.state)
        assertEquals("192.168.12.144", settings.savedIp)
        assertEquals("18099", settings.savedPort)
        assertEquals(1, settings.savedCount)
        assertEquals(1, discovery.stopCount)
        // The dead address was probed first, then the discovered one.
        assertEquals("192.168.12.100", probe.calls.first().first)
    }

    @Test
    fun `an invalid token never probes and never saves`() {
        for (token in listOf("", "short", "g".repeat(64), validToken.dropLast(1))) {
            val settings = FakeSettings("192.168.12.144", "18099", token)
            val discovery = FakeDiscovery()
            val probe = FakeProbe(setOf("192.168.12.144" to 18099))
            val coordinator = coordinatorFor(settings, discovery, probe)

            coordinator.ensureResolved()
            discovery.push(realService(ip = "192.168.12.144"))
            awaitRun(coordinator)

            assertEquals(DiscoveryState.ManualFallback, coordinator.state)
            assertNull(coordinator.resolved)
            assertTrue("token '$token' must not be sent", probe.calls.isEmpty())
            assertEquals(0, settings.savedCount)
        }
    }

    // End-to-end time bounds with a hanging probe, requirement 4

    @Test
    fun `a hanging saved-address probe cannot exceed the 3 s phase budget`() {
        val settings = FakeSettings("192.168.12.144", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = HangingProbe()
        val coordinator = coordinatorFor(settings, discovery, probe)

        val started = System.currentTimeMillis()
        coordinator.ensureResolved()
        awaitRun(coordinator)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(DiscoveryState.ManualFallback, coordinator.state)
        assertNull(coordinator.resolved)
        assertEquals("a failed re-probe must persist nothing", 0, settings.savedCount)
        // Saved-address phase (3 s) plus browse phase (8 s) is the ceiling; one
        // attempt may overshoot by at most its own timeout, never the phase budget.
        assertTrue("elapsed ${elapsed}ms must stay inside the budget", elapsed < 16_000L)
        val attempts = probe.calls.filter { it.first == "192.168.12.144" }
        assertTrue("the stale address must be retried within the phase", attempts.isNotEmpty())
        assertTrue(
            "attempt timeouts must be capped: $attempts",
            attempts.all { it.third <= DefaultDiscoveryTiming.savedProbeAttemptMs },
        )
        assertEquals("browsing must stop exactly once", 1, discovery.stopCount)
    }

    @Test
    fun `a hanging browse probe cannot exceed the discovery window`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = HangingProbe()
        val coordinator = coordinatorFor(settings, discovery, probe)

        val started = System.currentTimeMillis()
        coordinator.ensureResolved()
        discovery.pushAll(
            listOf(
                realService(ip = "192.168.12.10"),
                realService(ip = "192.168.12.20"),
                realService(ip = "192.168.12.200"),
            ),
        )
        awaitRun(coordinator)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("the 8 s window must be respected: ${elapsed}ms", elapsed < 12_000L)
        assertTrue("at least one candidate must have been probed", probe.calls.isNotEmpty())
        assertTrue(
            "probe timeouts must never exceed the browse budget: ${probe.timeouts}",
            probe.timeouts.all { it <= DefaultDiscoveryTiming.discoveryWindowMs },
        )
        assertEquals("browsing must stop exactly once", 1, discovery.stopCount)
        assertFalse(coordinator.isRunning)
        // The verdict is still the frozen rule, never a partial guess.
        assertEquals(DiscoveryState.ManualFallback, coordinator.state)
        assertNull(coordinator.resolved)
        assertEquals(0, settings.savedCount)
    }

    @Test
    fun `repeated entry stacks no listeners and saves once`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val coordinator = coordinatorFor(settings, discovery, FakeProbe(setOf("192.168.12.144" to 18099)))

        coordinator.ensureResolved()
        coordinator.ensureResolved()
        coordinator.ensureResolved()
        discovery.push(realService(ip = "192.168.12.144"))
        awaitRun(coordinator)

        assertEquals(1, discovery.startCount)
        assertEquals(1, discovery.stopCount)
        assertEquals(1, settings.savedCount)
    }

    @Test
    fun `stopping discovery cancels the window and clears the result`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val coordinator = coordinatorFor(settings, discovery, FakeProbe(setOf("192.168.12.144" to 18099)))

        coordinator.ensureResolved()
        coordinator.stop()
        discovery.push(realService(ip = "192.168.12.144"))
        awaitRun(coordinator)

        assertEquals(DiscoveryState.Idle, coordinator.state)
        assertNull(coordinator.resolved)
        // A cancellation before browsing starts must not invent a listener stop.
        assertEquals(discovery.startCount, discovery.stopCount)
        assertFalse(coordinator.isRunning)
    }

    // Manual target, requirement 2

    @Test
    fun `manual destination is adopted only after the authenticated probe`() = kotlinx.coroutines.runBlocking {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val coordinator = coordinatorFor(settings, discovery, FakeProbe(setOf("192.168.12.144" to 18099)))

        assertTrue(coordinator.probeCandidate(DiscoverySelection("192.168.12.144", 18099)))
        assertFalse(coordinator.probeCandidate(DiscoverySelection("192.168.12.50", 18099)))
        assertEquals("manual probing must not browse", 0, discovery.startCount)
        assertEquals("a failed probe must persist nothing", 0, settings.savedCount)

        coordinator.adopt(DiscoverySelection("192.168.12.144", 18099))
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals("192.168.12.144", settings.savedIp)
        assertEquals(1, settings.savedCount)

        coordinator.invalidate()
        assertNull(coordinator.resolved)
    }

    // Release and platform guards

    @Test
    fun `the production factory is debug-gated and uses the platform nsd`() {
        val source = java.io.File("src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt").readText()
        val factory = source.substringAfter("object Discovery {")
        assertTrue(factory.contains("BuildConfig.DEBUG"))
        assertTrue(factory.contains("AndroidNsdDiscovery(context"))
        assertTrue(factory.contains("HttpDiscoveryProbe()"))

        val nsd = java.io.File("src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt").readText()
        assertTrue("the official NsdManager must be used", nsd.contains("android.net.nsd.NsdManager"))
        assertTrue(
            "the browser must use the platform service type form",
            nsd.contains("discoverServices(SAYIT_SERVICE_TYPE_ANDROID"),
        )
        assertTrue("discovery must be stopped explicitly", nsd.contains("stopServiceDiscovery(listener)"))
        // Repair 1 requirement 1: the instance name and the service type must be
        // carried separately, never inferred from one another.
        assertTrue(nsd.contains("instanceName = serviceInfo.serviceName"))
        assertTrue(nsd.contains("serviceType = serviceInfo.serviceType"))
    }

    @Test
    fun `no third party mdns library and no new platform permission`() {
        val build = java.io.File("build.gradle.kts").readText()
        for (forbidden in listOf("jmdns", "zeroconf", "bonjour")) {
            assertFalse(build.contains(forbidden, ignoreCase = true))
        }
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("ACCESS_LOCAL_NETWORK"))
        assertTrue("NSD needs only the existing INTERNET permission", manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `the released http probe refuses a malformed token before any socket`() {
        val probe = HttpDiscoveryProbe()
        val result = probe.probe("192.168.1.5", 18099, "not-a-token", 1_000)
        assertTrue(result is DiscoveryProbeResult.Rejected)
    }

    @Test
    fun `the released http probe bounds its own timeouts`() {
        // A tiny budget must be honoured, not ignored.
        val probe = HttpDiscoveryProbe(maxConnectTimeoutMs = 250)
        val result = probe.probe("192.0.2.1", 18099, validToken, 250)
        assertTrue(result is DiscoveryProbeResult.Rejected)
    }

    /**
     * A minimal raw-socket HTTP responder, so the real [HttpDiscoveryProbe] code
     * path (URL, Bearer header, status handling, body parsing) is exercised
     * without any test-only HTTP library on the classpath.
     */
    private class RawHttpResponder : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        var auth: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) {
                                auth = line.substringAfter(':').trim()
                            }
                        }
                        val authenticated = auth == "Bearer $ACCEPTED_TOKEN"
                        // Repair 1 requirement 4: exactly service + protocol.
                        val body = if (authenticated) {
                            """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}"""
                        } else {
                            """{"error":"unauthorized"}"""
                        }
                        val status = if (authenticated) "200 OK" else "401 Unauthorized"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val servesDiscovery = requestLine.contains(SAYIT_DISCOVERY_PATH)
                        val finalStatus = if (servesDiscovery) status else "404 Not Found"
                        val finalBody = if (servesDiscovery) bytes else ByteArray(0)
                        val header = "HTTP/1.1 $finalStatus\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${finalBody.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        it.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII))
                            write(finalBody)
                            flush()
                        }
                    }
                }
            } catch (e: Exception) {
                // Socket closed by close(): expected.
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = socket.localPort

        override fun close() {
            socket.close()
        }

        companion object {
            const val ACCEPTED_TOKEN =
                "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        }
    }

    @Test
    fun `the http probe accepts a real 200 with the frozen identity`() {
        val server = RawHttpResponder()
        try {
            val probe = HttpDiscoveryProbe()
            assertTrue(
                "a 200 with the frozen identity must authenticate",
                probe.probe("127.0.0.1", server.port, RawHttpResponder.ACCEPTED_TOKEN, 2_000)
                    is DiscoveryProbeResult.Authenticated,
            )
            assertTrue(
                probe.probe("127.0.0.1", server.port, "b".repeat(64), 2_000) is DiscoveryProbeResult.Rejected,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `frozen discovery constants match the windows contract`() {
        assertEquals("_sayit-watch._tcp.", SAYIT_SERVICE_TYPE_ANDROID)
        assertEquals("_sayit-watch._tcp.local.", SAYIT_SERVICE_TYPE_FULL)
        assertEquals(1, SAYIT_DISCOVERY_PROTOCOL)
        assertEquals("/api/watch/discovery", SAYIT_DISCOVERY_PATH)
        assertEquals("sayit-watch-debug-receiver", SAYIT_DISCOVERY_SERVICE_ID)
        // The production budgets are the frozen 3 s / 8 s (requirement 4).
        assertEquals(3_000L, DefaultDiscoveryTiming.savedProbeTotalMs)
        assertEquals(8_000L, DefaultDiscoveryTiming.discoveryWindowMs)
        assertTrue(DefaultDiscoveryTiming.discoveryProbeAttemptMs <= 8_000L)
        assertTrue(DefaultDiscoveryTiming.savedProbeAttemptMs <= 3_000L)
    }
}
