package com.sayit.watch.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery 1C Repair 2 coverage.
 *
 * 必修 1: one absolute deadline shared by connect + headers + body, verified with
 * REAL sockets that stall and with a peer that drips one byte per tick (an idle
 * `soTimeout` alone cannot stop that).
 *
 * 必修 2: the manual/automatic exclusion, its generation isolation, and the
 * coordinator's terminal callback (the verdict gap where final states used to be
 * published with an empty callback).
 */
class DiscoveryRepairTest {

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val goodBody =
        """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}"""

    private fun realService(
        ip: String = "192.168.12.144",
        port: Int = 18099,
        instanceName: String = "SayIt",
    ) = ResolvedService(instanceName, SAYIT_SERVICE_TYPE_ANDROID, ip, port, "1")

    // ── 必修 1: budget allocation ────────────────────────────────────────────

    @Test
    fun `every serial blocking slice together never exceeds the caller budget`() {
        // Simulates time passing as each phase is entered, and asserts the sum of
        // the granted slices can never exceed timeoutMs.
        for (timeoutMs in listOf(3, 10, 250, 1_000, 2_000, 8_000)) {
            for (elapsed in listOf(0L, timeoutMs / 4L, timeoutMs / 2L, timeoutMs - 1L)) {
                var now = 1_000L + elapsed
                val budget = ProbeBudget(timeoutMs, 1_000L, nowMs = { now })
                var total = 0L
                for (phase in listOf(
                    ProbeBudget.Phase.CONNECT,
                    ProbeBudget.Phase.HEADERS,
                    ProbeBudget.Phase.BODY,
                )) {
                    val slice = budget.nextTimeoutMs(phase)
                    if (slice <= 0L) break
                    total += slice
                    now += slice
                }
                assertTrue(
                    "timeout=$timeoutMs elapsed=$elapsed granted=$total must stay within budget",
                    total <= timeoutMs.toLong(),
                )
            }
        }
    }

    @Test
    fun `the final phase may use what is left and an exhausted budget grants nothing`() {
        var now = 5_000L
        val budget = ProbeBudget(300, 5_000L, nowMs = { now })
        assertEquals(100L, budget.nextTimeoutMs(ProbeBudget.Phase.CONNECT))
        now += 100
        assertEquals(100L, budget.nextTimeoutMs(ProbeBudget.Phase.HEADERS))
        now += 90
        // 110 ms left: the body phase may take all of it.
        assertEquals(110L, budget.nextTimeoutMs(ProbeBudget.Phase.BODY))
        now += 110
        assertTrue(budget.exhausted())
        assertEquals(0L, budget.nextTimeoutMs(ProbeBudget.Phase.BODY))
    }

    @Test
    fun `a header block is read byte-bounded and a status line is parsed`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
        val status = DiscoveryHttpResponse.readStatusAndHeaders(
            response.byteInputStream(Charsets.ISO_8859_1),
            neverEnding(),
        ) { }
        assertEquals(200, status)
        assertEquals(null, DiscoveryHttpResponse.parseStatusLine("NOT-HTTP 200"))
        assertEquals(null, DiscoveryHttpResponse.parseStatusLine("HTTP/1.1 abc"))
        assertEquals(404, DiscoveryHttpResponse.parseStatusLine("HTTP/1.1 404 Not Found"))
    }

    @Test
    fun `an oversized header block is rejected without unbounded buffering`() {
        // A peer that never sends the blank line must hit the hard cap, not memory.
        val flood: InputStream = object : InputStream() {
            override fun read(): Int = 'A'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'A'.code.toByte())
                return len
            }
        }
        val status = DiscoveryHttpResponse.readStatusAndHeaders(flood, neverEnding()) { }
        assertNull("an endless header block must be rejected", status)
    }

    @Test
    fun `the body reader stops at the byte cap`() {
        val flood: InputStream = object : InputStream() {
            override fun read(): Int = 'B'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'B'.code.toByte())
                return len
            }
        }
        val body = DiscoveryHttpResponse.readBody(flood, neverEnding()) { }
        assertEquals(DiscoveryHttpResponse.MAX_BODY_BYTES, body?.length)
    }

    @Test
    fun `the body reader aborts when the deadline is already gone`() {
        val body = DiscoveryHttpResponse.readBody(
            "ignored".byteInputStream(),
            object : ReadDeadline {
                override fun remainingMs(): Long = 0L
            },
        ) { }
        assertNull("an exhausted deadline must not read at all", body)
    }

    private fun neverEnding(): ReadDeadline = object : ReadDeadline {
        override fun remainingMs(): Long = 5_000L
    }

    // ── 必修 1: real sockets ────────────────────────────────────────────────

    /** A local peer whose responder runs per accepted connection. */
    private class RawServer(private val handler: (java.net.Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            try {
                while (!server.isClosed) {
                    val client = server.accept()
                    // Handle each connection on its own thread so a stalled peer
                    // does not block the next accept.
                    Thread { handler(client) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                // closed
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = server.localPort

        override fun close() {
            server.close()
        }
    }

    private fun drainRequest(socket: java.net.Socket) {
        val input = socket.getInputStream()
        val buffer = ByteArray(1)
        var match = 0
        // Read exactly one request head, one byte at a time (the probe sends it whole).
        while (match < 4) {
            val read = input.read(buffer)
            if (read < 0) return
            val b = buffer[0].toInt()
            match = when {
                b == '\r'.code && (match == 0 || match == 2) -> match + 1
                b == '\n'.code && (match == 1 || match == 3) -> match + 1
                else -> 0
            }
        }
    }

    private fun writeAll(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    @Test
    fun `a peer that never answers is rejected inside the caller budget`() {
        // Accepts the connection, reads the request, then stalls forever.
        val server = RawServer { socket ->
            try {
                drainRequest(socket)
                Thread.sleep(10_000)
            } catch (ignored: Exception) {
                // test teardown
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            val budgetMs = 400
            val started = System.currentTimeMillis()
            val result = HttpDiscoveryProbe().probe("127.0.0.1", server.port, validToken, budgetMs)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("a stalled peer must be rejected, got $result", result is DiscoveryProbeResult.Rejected)
            assertTrue(
                "elapsed ${elapsed}ms must stay within ${budgetMs}ms plus tolerance",
                elapsed < budgetMs + 1_500L,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a peer dripping the header one byte per tick cannot extend the total budget`() {
        val bytesWritten = AtomicInteger(0)
        // Each byte arrives well inside the socket idle timeout, so only the total
        // deadline can stop this. The header is never completed.
        val server = RawServer { socket ->
            try {
                drainRequest(socket)
                val out = socket.getOutputStream()
                while (true) {
                    writeAll(out, "A")
                    bytesWritten.incrementAndGet()
                    Thread.sleep(5)
                }
            } catch (ignored: Exception) {
                // client closed
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            val budgetMs = 600
            val started = System.currentTimeMillis()
            val result = HttpDiscoveryProbe().probe("127.0.0.1", server.port, validToken, budgetMs)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("a dribbling peer must be rejected, got $result", result is DiscoveryProbeResult.Rejected)
            assertTrue(
                "elapsed ${elapsed}ms must stay within ${budgetMs}ms plus tolerance",
                elapsed < budgetMs + 1_500L,
            )
            assertTrue("the drip must have started", bytesWritten.get() > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a peer dripping the body one byte per tick cannot extend the total budget`() {
        val bytesWritten = AtomicInteger(0)
        // Complete headers immediately, then drip a body forever without the closing
        // byte count: only the total deadline can stop this.
        val server = RawServer { socket ->
            try {
                drainRequest(socket)
                val out = socket.getOutputStream()
                writeAll(out, "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n")
                while (true) {
                    writeAll(out, "C")
                    bytesWritten.incrementAndGet()
                    Thread.sleep(5)
                }
            } catch (ignored: Exception) {
                // client closed
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            val budgetMs = 600
            val started = System.currentTimeMillis()
            val result = HttpDiscoveryProbe().probe("127.0.0.1", server.port, validToken, budgetMs)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("a dribbling body must be rejected, got $result", result is DiscoveryProbeResult.Rejected)
            assertTrue(
                "elapsed ${elapsed}ms must stay within ${budgetMs}ms plus tolerance",
                elapsed < budgetMs + 1_500L,
            )
            assertTrue("the drip must have started", bytesWritten.get() > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a normal local discovery response still authenticates`() {
        // The byte-level rewrite must not break the real success path.
        val server = RawServer { socket ->
            try {
                drainRequest(socket)
                writeAll(
                    socket.getOutputStream(),
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${goodBody.length}\r\nConnection: close\r\n\r\n$goodBody",
                )
            } catch (ignored: Exception) {
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            val result = HttpDiscoveryProbe().probe("127.0.0.1", server.port, validToken, 2_000)
            assertTrue("$result", result is DiscoveryProbeResult.Authenticated)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a 401 with a drained body is a rejection`() {
        val server = RawServer { socket ->
            try {
                drainRequest(socket)
                writeAll(
                    socket.getOutputStream(),
                    "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n{\"error\":\"unauthorized\"}",
                )
            } catch (ignored: Exception) {
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            val result = HttpDiscoveryProbe().probe("127.0.0.1", server.port, validToken, 2_000)
            assertTrue(result is DiscoveryProbeResult.Rejected)
        } finally {
            server.close()
        }
    }

    // ── 必修 2: manual/automatic exclusion ──────────────────────────────────

    private fun candidate(ip: String = "192.168.12.144") = DiscoverySelection(ip, 18099)

    /**
     * 1C-D-04@R2: the bridge consumes a whole [DiscoveryOutcome]; this builds the
     * exactly-one case without repeating the constructor everywhere.
     */
    private fun outcomeFor(target: DiscoverySelection?): DiscoveryOutcome =
        if (target == null) {
            DiscoveryOutcome(existingAddress = null, single = null, authenticated = emptyList())
        } else {
            DiscoveryOutcome(existingAddress = null, single = target, authenticated = listOf(target))
        }

    /**
     * The bridge with a controllable automatic transport: [park] makes the
     * automatic call return only after [release], so a late automatic result can be
     * delivered after a manual choice.
     */
    private class FakeAutomatic {
        var startCount = 0
        var stopCount = 0
        var manualCount = 0
        var manualShouldSucceed = true
        var automaticResult: DiscoverySelection? = null
        private val gate = CountDownLatch(1)

        /** Releases a parked automatic call. */
        fun release() = gate.countDown()

        fun parkUntilReleased() = gate.await(2, TimeUnit.SECONDS)

        fun executeAutomatic(): DiscoverySelection? {
            startCount++
            return automaticResult
        }

        fun bridge() = ResolverBridge(
            isValidToken = { true },
            // 1C-D-04@R2: the bridge takes the whole run outcome, not just the
            // frozen exactly-one verdict.
            executeAutomatic = { _ ->
                startCount++
                parkUntilReleased()
                outcomeOf(automaticResult)
            },
            executeManual = { _, _ ->
                manualCount++
                manualShouldSucceed
            },
            stopAutomatic = { stopCount++ },
        )

        private fun outcomeOf(target: DiscoverySelection?) =
            if (target == null) {
                DiscoveryOutcome(existingAddress = null, single = null, authenticated = emptyList())
            } else {
                DiscoveryOutcome(existingAddress = null, single = target, authenticated = listOf(target))
            }
    }

    @Test
    fun `a late automatic result cannot overwrite a manual success`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.automaticResult = candidate("192.168.12.50")
            val bridge = fake.bridge()

            // Automatic discovery is running (parked).
            val running = Thread {
                kotlinx.coroutines.runBlocking { bridge.onReadyEntered() }
            }.apply { isDaemon = true; start() }
            // Wait until the automatic run actually started.
            val waitUntil = System.currentTimeMillis() + 2_000
            while (fake.startCount == 0 && System.currentTimeMillis() < waitUntil) Thread.sleep(5)
            assertEquals(1, fake.startCount)

            // The user opens Config and submits a manual address.
            bridge.onConfigEntered()
            assertTrue("Config must stop the automatic transport", fake.stopCount >= 1)
            bridge.onManualRequested(candidate("192.168.12.144"))
            assertEquals(candidate("192.168.12.144"), bridge.verifiedTarget)
            assertTrue(bridge.hasVerifiedTarget)

            // The superseded automatic run now finishes with its own success.
            fake.release()
            running.join(2_000)

            assertEquals(
                "the late automatic result must not overwrite the manual target",
                candidate("192.168.12.144"),
                bridge.verifiedTarget,
            )
            assertTrue(bridge.hasVerifiedTarget)
        }

    @Test
    fun `a successful manual target is not re-discovered on Ready entry`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            val bridge = fake.bridge()

            bridge.onManualRequested(candidate("192.168.12.144"))
            assertTrue(bridge.hasVerifiedTarget)
            val startsBefore = fake.startCount

            // Entering Ready (or returning from Recording) must be a no-op.
            assertFalse(bridge.onReadyEntered())
            assertFalse(bridge.onReadyEntered())
            assertEquals("no automatic run may start", startsBefore, fake.startCount)
            assertEquals(candidate("192.168.12.144"), bridge.verifiedTarget)

            // And the repeated Ready entries still report a real target.
            assertTrue(bridge.acceptsAutomatic().not())
        }

    @Test
    fun `a failed manual probe keeps the manual fallback and no target`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.manualShouldSucceed = false
            val bridge = fake.bridge()

            bridge.onManualRequested(candidate("192.168.12.1"))
            assertFalse("a failed probe must not verify anything", bridge.hasVerifiedTarget)
            assertNull(bridge.verifiedTarget)
            assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)
        }

    @Test
    fun `an automatic failure publishes the fallback and verifies nothing`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.automaticResult = null
            val bridge = fake.bridge()

            assertTrue(bridge.onReadyEntered())
            assertFalse(bridge.hasVerifiedTarget)
            assertNull(bridge.verifiedTarget)
            assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)
        }

    @Test
    fun `returning from Recording with a valid target does not restart discovery`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.automaticResult = candidate("192.168.12.50")
            val bridge = fake.bridge()

            assertTrue(bridge.onReadyEntered())
            assertEquals(candidate("192.168.12.50"), bridge.verifiedTarget)
            val startsAfterFirstRun = fake.startCount

            // Gone to Recording and back: nothing may restart.
            assertFalse(bridge.onReadyEntered())
            assertEquals(startsAfterFirstRun, fake.startCount)
            assertEquals(candidate("192.168.12.50"), bridge.verifiedTarget)
        }

    @Test
    fun `an upload failure clears the target and only then allows re-resolution`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.automaticResult = candidate("192.168.12.50")
            val bridge = fake.bridge()

            assertTrue(bridge.onReadyEntered())
            assertTrue(bridge.hasVerifiedTarget)

            bridge.onTargetInvalidated()
            assertFalse("the failed target must be gone", bridge.hasVerifiedTarget)
            assertNull(bridge.verifiedTarget)

            fake.automaticResult = candidate("192.168.12.99")
            assertTrue("the next attempt may resolve again", bridge.onReadyEntered())
            assertEquals(candidate("192.168.12.99"), bridge.verifiedTarget)
        }

    @Test
    fun `a cancelled run cannot publish after teardown`() =
        kotlinx.coroutines.runBlocking {
            val fake = FakeAutomatic()
            fake.automaticResult = candidate("192.168.12.50")
            val bridge = fake.bridge()

            val running = Thread {
                kotlinx.coroutines.runBlocking { bridge.onReadyEntered() }
            }.apply { isDaemon = true; start() }
            val waitUntil = System.currentTimeMillis() + 2_000
            while (fake.startCount == 0 && System.currentTimeMillis() < waitUntil) Thread.sleep(5)

            bridge.onStopped()
            fake.release()
            running.join(2_000)

            assertNull("a stopped run must not publish a target", bridge.verifiedTarget)
            assertFalse(bridge.hasVerifiedTarget)
        }

    @Test
    fun `an invalid token fails closed without any transport call`() =
        kotlinx.coroutines.runBlocking {
            var automaticCalls = 0
            var manualCalls = 0
            val bridge = ResolverBridge(
                isValidToken = { false },
                executeAutomatic = { automaticCalls++; outcomeFor(candidate()) },
                executeManual = { _, _ -> manualCalls++; true },
            )

            assertTrue(bridge.onReadyEntered())
            assertEquals("no probe may run with an invalid token", 0, automaticCalls)
            assertFalse(bridge.hasVerifiedTarget)
            assertEquals(DiscoveryState.ManualFallback, bridge.lastPublishedState)

            bridge.onManualRequested(candidate("192.168.12.7"))
            assertEquals(0, manualCalls)
            assertFalse(bridge.hasVerifiedTarget)
        }

    // ── 必修 2 + verdict gap: the production coordinator's real callback ─────

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
    }

    private class FakeProbe(
        private val authenticatedHosts: Set<Pair<String, Int>>,
    ) : DiscoveryProbe {
        override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult =
            if (authenticatedHosts.contains(ip to port)) {
                DiscoveryPolicy.parseProbeResponse(
                    200,
                    """{"service":"$SAYIT_DISCOVERY_SERVICE_ID","protocol":$SAYIT_DISCOVERY_PROTOCOL}""",
                )
            } else {
                DiscoveryPolicy.parseProbeResponse(401, """{"error":"unauthorized"}""")
            }
    }

    private fun productionCoordinator(
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

    private fun awaitRun(coordinator: DiscoveryCoordinator, timeoutMs: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("the run must finish inside the frozen budgets", coordinator.isRunning)
    }

    @Test
    fun `the coordinator publishes its final authenticated verdict to the caller`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = productionCoordinator(settings, discovery, probe)

        val published = java.util.Collections.synchronizedList(mutableListOf<DiscoveryState>())
        discovery.push(realService(ip = "192.168.12.144"))
        coordinator.ensureResolved { state -> published.add(state) }
        awaitRun(coordinator)

        assertEquals(
            "the caller must receive the SEARCHING state and then the final verdict: $published",
            listOf(DiscoveryState.Searching, DiscoveryState.Discovered),
            published.toList(),
        )
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals(1, settings.savedCount)
        assertEquals("192.168.12.144" to 18099, settings.lastSaved)
    }

    @Test
    fun `the coordinator publishes the manual fallback when nothing authenticates`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(emptySet())
        val coordinator = productionCoordinator(settings, discovery, probe)

        val published = java.util.Collections.synchronizedList(mutableListOf<DiscoveryState>())
        discovery.push(realService(ip = "192.168.12.50"))
        coordinator.ensureResolved { state -> published.add(state) }
        awaitRun(coordinator)

        assertEquals(
            "a failed run must publish the fallback, not stop at Searching: $published",
            listOf(DiscoveryState.Searching, DiscoveryState.ManualFallback),
            published.toList(),
        )
        assertNull(coordinator.resolved)
        assertEquals("a failed run must persist nothing", 0, settings.savedCount)
    }

    @Test
    fun `a stale generation neither publishes nor persists after a stop`() {
        val settings = FakeSettings("", "", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = productionCoordinator(settings, discovery, probe)

        val firstRun = java.util.Collections.synchronizedList(mutableListOf<DiscoveryState>())
        discovery.push(realService(ip = "192.168.12.144"))
        coordinator.ensureResolved { state -> firstRun.add(state) }
        // Supersede the run before it can finish.
        coordinator.stop()
        Thread.sleep(200)

        assertTrue(
            "a superseded run must not publish a verdict: $firstRun",
            firstRun.none { it is DiscoveryState.Discovered || it is DiscoveryState.ExistingAddress },
        )
        assertEquals("a superseded run must not persist", 0, settings.savedCount)
        assertNull(coordinator.resolved)

        // A fresh run afterwards still works.
        val secondRun = java.util.Collections.synchronizedList(mutableListOf<DiscoveryState>())
        coordinator.ensureResolved { state -> secondRun.add(state) }
        awaitRun(coordinator)
        assertEquals(
            listOf(DiscoveryState.Searching, DiscoveryState.Discovered),
            secondRun.toList(),
        )
        assertEquals(1, settings.savedCount)
    }

    @Test
    fun `a saved address verdict is published as ExistingAddress and persists nothing new`() {
        val settings = FakeSettings("192.168.12.144", "18099", validToken)
        val discovery = FakeDiscovery()
        val probe = FakeProbe(setOf("192.168.12.144" to 18099))
        val coordinator = productionCoordinator(settings, discovery, probe)

        val published = java.util.Collections.synchronizedList(mutableListOf<DiscoveryState>())
        coordinator.ensureResolved { state -> published.add(state) }
        awaitRun(coordinator)

        assertEquals(
            listOf(DiscoveryState.Searching, DiscoveryState.ExistingAddress),
            published.toList(),
        )
        assertEquals(DiscoverySelection("192.168.12.144", 18099), coordinator.resolved)
        assertEquals(0, discovery.startCount)
        assertEquals(0, settings.savedCount)
        assertNotNull(coordinator.resolved)
    }
}
