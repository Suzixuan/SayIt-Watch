package com.sayit.watch.net

import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.DestinationValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Platform service browser. Implemented by [AndroidNsdDiscovery] (NsdManager) and
 * by a fake in the JVM tests, so all selection/timeout/lifecycle logic below is
 * testable without Android.
 */
interface ServiceDiscovery {
    /**
     * Starts browsing. Must be safe to call when already browsing.
     *
     * [observer] receives the full snapshot of resolved candidates so far, so a
     * concurrent `resolveService` completion can never be lost: every accepted
     * candidate is delivered exactly once and deduplicated by endpoint.
     */
    fun start(observer: (List<ResolvedService>) -> Unit)

    /** Stops browsing; the observer must not be called afterwards. */
    fun stop()
}

/** Persistence the coordinator needs, narrowed so tests do not need Android. */
interface DiscoverySettings {
    val savedIp: String
    val savedPort: String
    val devToken: String

    /**
     * 1C-D-04@R3: the token gate the ViewModel and the resolver both ask for. It was
     * already implemented by the concrete store; declaring it here is what lets a JVM
     * test drive the real `requestSwitch()` entry point with a fake.
     */
    fun hasValidToken(): Boolean

    /**
     * 1C-D-04@R3: Save & Apply is the one place that writes the token. It is a write
     * operation on the settings port, so the interface declares it instead of letting
     * the ViewModel reach into the concrete store.
     */
    fun saveDevToken(token: String)

    /** Persists an address. Called ONLY after that address authenticated. */
    fun saveDestination(ip: String, port: Int)
}

/** Concrete adapter over the app-private [com.sayit.watch.settings.SettingsStore]. */
class SettingsDiscoverySettings(
    private val store: com.sayit.watch.settings.SettingsStore,
) : DiscoverySettings {
    override val savedIp: String get() = store.receiverIp
    override val savedPort: String get() = store.receiverPort
    override val devToken: String get() = store.devToken

    override fun hasValidToken(): Boolean = store.hasValidToken()

    override fun saveDevToken(token: String) {
        store.devToken = token
    }

    override fun saveDestination(ip: String, port: Int) {
        store.receiverIp = ip
        store.receiverPort = port.toString()
    }
}

/** Observable discovery lifecycle exposed to the UI. */
sealed class DiscoveryState {
    /** Nothing in flight and no known-good address. */
    object Idle : DiscoveryState()

    /** A saved address is being re-probed, or mDNS browsing is running. */
    object Searching : DiscoveryState()

    /** The saved address still answered the authenticated probe. */
    object ExistingAddress : DiscoveryState()

    /** Exactly one discovered address authenticated and was saved. */
    object Discovered : DiscoveryState()

    /** 0 or ≥2 authenticated endpoints (or no discovery at all): manual entry. */
    object ManualFallback : DiscoveryState()
}

/** Bounded timings for Delivery 1C (端到端总上限, Repair 1 必修 4). */
interface DiscoveryTiming {
    /** Saved-address re-probe phase: 3 000 ms wall-clock total, never more. */
    val savedProbeTotalMs: Long

    /** Browsing phase: 8 000 ms wall-clock total, never more. */
    val discoveryWindowMs: Long

    /** One saved-address attempt may never exceed this. */
    val savedProbeAttemptMs: Long

    /** One browse-window probe may never exceed this. */
    val discoveryProbeAttemptMs: Long

    /** How often the browse loop looks for newly resolved candidates. */
    val pollIntervalMs: Long
}

/** Production timings, frozen by the Delivery 1C contract. */
object DefaultDiscoveryTiming : DiscoveryTiming {
    override val savedProbeTotalMs: Long = 3_000L
    override val discoveryWindowMs: Long = 8_000L
    override val savedProbeAttemptMs: Long = 1_000L
    override val discoveryProbeAttemptMs: Long = 1_500L
    override val pollIntervalMs: Long = 50L
}

/**
 * Delivery 1C discovery coordinator (Repair 1).
 *
 * Flow (frozen contract §4):
 * 1. with a saved+valid destination, re-probe it for at most 3 s of wall clock —
 *    the *phase* is bounded, not just the individual attempt, because every probe
 *    is handed the remaining budget as its own timeout;
 * 2. otherwise browse `_sayit-watch._tcp` for at most 8 s of wall clock, probing
 *    every distinct endpoint that passed pure validation;
 * 3. at the end of a phase, count the authenticated endpoints:
 *    - exactly 1 → save it and report [DiscoveryState.Discovered] /
 *      [DiscoveryState.ExistingAddress];
 *    - 0 or ≥2 → [DiscoveryState.ManualFallback], saving nothing. A same-token
 *      second PC is a genuinely different endpoint, so it is ambiguity, not a
 *      tie to be broken by arrival order;
 * 4. [resolved] is only ever set from an address that authenticated in THIS phase
 *    (or from an explicit [adopt] of an address the caller already authenticated).
 *
 * A single in-flight job guards the lifecycle: repeated entries into Ready do not
 * stack listeners, and re-entering with a known-good address only re-probes
 * (never starts a browse). The probe is always issued to a candidate that already
 * passed pure validation, so no audio and no unauthenticated request ever goes to
 * a hostname, IPv6, public, link-local, loopback or `0.0.0.0` address.
 */
class DiscoveryCoordinator(
    private val settings: DiscoverySettings,
    private val discovery: ServiceDiscovery,
    private val probe: DiscoveryProbe,
    private val isEnabled: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /**
     * Context used for the blocking probes. Production passes `Dispatchers.IO`;
     * tests pass null so the probe runs on the coordinator's own context (the test
     * scheduler) with no scheduler hop.
     */
    private val probeDispatcher: kotlin.coroutines.CoroutineContext? = Dispatchers.IO,
    private val timing: DiscoveryTiming = DefaultDiscoveryTiming,
    /** Injectable clock so the wall-clock budgets stay testable. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * 1C-D-04@R2: fixed-category stage log for this coordinator's runs. Category
     * only — no IP, host name, exception text or HTTP data is ever recorded.
     */
    private val diagnostics: DiscoveryDiagnostics = CategoryLog(),
) {

    @Volatile
    private var inFlight: Job? = null

    /** Guards the run generation counter below. */
    private val runLock = Any()
    private var stopBrowser: (() -> Unit)? = null

    /** Bumped on every run start and on every stop; identifies the active run. */
    private var runCounter = 0L

    @Volatile
    var state: DiscoveryState = DiscoveryState.Idle
        private set

    /** The full result of the last completed run (addresses included). */
    @Volatile
    var lastOutcome: DiscoveryOutcome? = null
        private set

    /**
     * 1C-D-04@R2 stage trail: which of saved-probe / browse / found / resolve /
     * candidate / probe / verdict each stage reached, as fixed categories.
     */
    fun diagnosticsSnapshot(): List<String> =
        (diagnostics as? CategoryLog)?.snapshot() ?: emptyList()

    /**
     * The endpoint authenticated during the current (or last completed) phase.
     * Null means "no verified upload target" — the ViewModel must then refuse to
     * record or upload.
     */
    @Volatile
    var resolved: DiscoverySelection? = null
        private set

    /** True when a discovery run is currently in flight. */
    val isRunning: Boolean get() = inFlight?.isCompleted == false

    /**
     * True from [ensureResolved] until the run publishes its verdict. The resolver
     * engine uses it as the "a resolution is already running" input, so the Ready
     * entry point never stacks a second run (Repair 2 必修 2).
     */
    val isResolving: Boolean get() = isRunning

    /**
     * 1C-D-04@R2 (verdict gap, re-closed): runs one automatic resolution and waits
     * for its REAL asynchronous verdict.
     *
     * The caller is the resolution owner (the engine already decided that no other
     * mode is active), so this run is the only one that can be in flight. The final
     * state is taken from the run's own callback and the complete
     * [DiscoveryOutcome] — including the list of every computer that authenticated —
     * is returned once the job has actually finished.
     *
     * @return the run's outcome, or null when a run was already in flight (in which
     *   case the caller's generation is dropped by the engine).
     */
    suspend fun awaitOutcome(onState: (DiscoveryState) -> Unit = {}): DiscoveryOutcome? {
        val job = synchronized(runLock) {
            if (isRunning) return null
            val runId = ++runCounter
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runDiscovery(runId, onState)
                } finally {
                    synchronized(runLock) {
                        if (runId == runCounter && inFlight === coroutineContext[Job]) {
                            inFlight = null
                        }
                    }
                }
            }.also { inFlight = it }
        }
        publish(DiscoveryState.Searching, onState)
        job.start()
        job.join()
        return lastOutcome
    }

    /**
     * Starts discovery for the current saved configuration. No-op (and no second
     * listener) while a run is already in flight.
     *
     * [onState] receives EVERY state of this run, including the final verdict
     * (`ExistingAddress` / `Discovered` / `ManualFallback`) — the caller needs the
     * terminal state to adopt an authenticated target or fall back to manual entry.
     * A run whose generation has been superseded never publishes at all, so a
     * cancelled run cannot overwrite a newer one's verdict.
     */
    fun ensureResolved(onState: (DiscoveryState) -> Unit = {}): Job? {
        val job = synchronized(runLock) {
            if (isRunning) return null
            val runId = ++runCounter
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runDiscovery(runId, onState)
                } finally {
                    synchronized(runLock) {
                        if (runId == runCounter && inFlight === coroutineContext[Job]) {
                            inFlight = null
                        }
                    }
                }
            }.also { inFlight = it }
        }
        publish(DiscoveryState.Searching, onState)
        job.start()
        return job
    }

    /** True while [runId] is still the newest run. */
    private fun isCurrentRun(runId: Long): Boolean = synchronized(runLock) { runId == runCounter }

    /** Cancels any in-flight run and stops browsing. Safe to call repeatedly. */
    fun stop() {
        // Invalidate first: a cancellation that loses the race with a publish must
        // still be unable to deliver a stale verdict or write settings.
        synchronized(runLock) {
            runCounter++
            inFlight?.cancel()
            inFlight = null
            resolved = null
            stopBrowser?.invoke()
            publish(DiscoveryState.Idle) {}
        }
    }

    /**
     * 1C-D-04@R8 P0-A — stops an in-flight browse and invalidates its result WITHOUT dropping
     * the endpoint authenticated in this session.
     *
     * `stop()` is the "the connection is over" path (it clears `resolved`). This is the
     * hand-over path used before an explicit switch browse: the old browse/NSD session must be
     * gone before a new listener opens, but the computer the user is on must survive, so the
     * engine, the bridge, the ViewModel's target and the recording gate keep agreeing.
     */
    fun stopBrowseOnly() {
        synchronized(runLock) {
            runCounter++
            inFlight?.cancel()
            inFlight = null
            stopBrowser?.invoke()
            stopBrowser = null
        }
    }

    /**
     * Authenticated probe of one explicit destination (the manual IP/port path),
     * bounded by the same saved-address phase budget. Never browses and never
     * writes settings.
     */
    suspend fun probeCandidate(candidate: DiscoverySelection): Boolean =
        probeCandidateResult(candidate) is DiscoveryProbeResult.Authenticated

    /**
     * 1C-D-04@R2: the same manual probe, but reporting the outcome CLASS
     * (authenticated / rejected) and recording the fixed diagnostic category, so a
     * manual failure is distinguishable from a discovery failure.
     */
    suspend fun probeCandidateResult(candidate: DiscoverySelection): DiscoveryProbeResult {
        val start = nowMs()
        return probeWithBudget(candidate, start, timing.savedProbeTotalMs, diagnostics = diagnostics)
    }

    /**
     * 1C-D-04@R2 — the explicit "switch computer" browse.
     *
     * Runs the same bounded 8 s window as an automatic run, but NEVER consults the
     * saved address (the user explicitly asked for other computers) and NEVER
     * publishes a verdict or persists anything on its own: it only returns the
     * endpoints that authenticated. The caller shows them and persists exactly the
     * one the user picks. An endpoint equal to [excluding] (the computer already in
     * use) and any ambiguous result are withheld, so the caller can only ever offer
     * a real choice or fall back — never a silent winner.
     *
     * @return the single newly authenticated endpoint, or an empty list.
     */
    suspend fun collectAndSelect(
        excluding: DiscoverySelection? = null,
        onState: (DiscoveryState) -> Unit = {},
    ): List<DiscoverySelection> {
        val job = synchronized(runLock) {
            if (isRunning) return emptyList()
            val runId = ++runCounter
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runSwitchBrowse(runId)
                } finally {
                    synchronized(runLock) {
                        if (runId == runCounter && inFlight === coroutineContext[Job]) {
                            inFlight = null
                        }
                    }
                }
            }.also { inFlight = it }
        }
        publish(DiscoveryState.Searching, onState)
        job.start()
        job.join()
        val candidates = currentCollected
        val distinct = candidates.filter { it != excluding }
        return distinct
    }

    /** The authenticated endpoints collected by the last explicit switch browse. */
    @Volatile
    private var currentCollected: List<DiscoverySelection> = emptyList()

    private suspend fun runSwitchBrowse(runId: Long) {
        if (!isEnabled()) {
            publishCurrent(runId, DiscoveryState.ManualFallback, {})
            return
        }
        if (DevTokenValidator.canonicalOrNull(settings.devToken) == null) {
            publishCurrent(runId, DiscoveryState.ManualFallback, {})
            return
        }
        val (authenticated, candidates) = browseAndAuthenticate(runId)
        if (!isCurrentRun(runId)) return
        if (candidates == 0) diagnostics.note(DiscoveryDiagnostic.BROWSE_NO_CANDIDATE)
        val list = authenticated.values.toList()
        diagnostics.note(
            when {
                list.size == 1 -> DiscoveryDiagnostic.VERDICT_ONE
                list.isEmpty() -> DiscoveryDiagnostic.VERDICT_NONE
                else -> DiscoveryDiagnostic.VERDICT_AMBIGUOUS
            },
        )
        synchronized(runLock) {
            currentCollected = list
        }
        // Deliberately no publishCurrent: an explicit switch must not move the
        // current target or overwrite the verdict before the user chooses.
        publish(DiscoveryState.Idle, {})
    }

    /**
     * Runs [block] on the configured probe context, or directly on the current
     * one when no context was supplied (tests, where a scheduler hop would break
     * virtual-time control).
     */
    private suspend fun <T> withProbeContext(block: suspend () -> T): T {
        val context = probeDispatcher ?: return block()
        return withContext(context) { block() }
    }

    /**
     * Adopts a destination the caller has ALREADY authenticated (manual entry
     * flow). This is the only other way [resolved] can be set, and callers must
     * not call it with an unverified address.
     */
    fun adopt(candidate: DiscoverySelection) {
        resolved = candidate
        settings.saveDestination(candidate.ip, candidate.port)
    }

    /** Forgets the current destination so the next upload failure re-discovers. */
    fun invalidate() {
        resolved = null
    }

    private suspend fun runDiscovery(runId: Long, onState: (DiscoveryState) -> Unit) {
        // A build/environment that cannot do debug LAN transport has no business
        // browsing for a receiver: fail straight to the manual fallback.
        if (!isEnabled()) {
            publishCurrent(runId, DiscoveryState.ManualFallback, onState)
            return
        }
        if (DevTokenValidator.canonicalOrNull(settings.devToken) == null) {
            publishCurrent(runId, DiscoveryState.ManualFallback, onState)
            return
        }

        // 1. Bounded re-probe of the last known address (3 s wall clock total).
        val saved = savedDestination()
        val existing = if (saved != null) reProbeSaved(runId, saved) else {
            diagnostics.note(DiscoveryDiagnostic.SAVED_PROBE_NONE)
            null
        }
        if (saved != null) {
            if (existing != null) {
                diagnostics.note(DiscoveryDiagnostic.SAVED_PROBE_ACCEPTED)
                diagnostics.note(DiscoveryDiagnostic.VERDICT_ONE)
                publishCurrent(runId, DiscoveryState.ExistingAddress, onState, existing)
                lastOutcome = DiscoveryOutcome(existing, null, listOf(existing))
                return
            }
            // The saved address is stale (a DHCP move or a powered-off PC):
            // forget it so nothing keeps sending audio to the old host.
            diagnostics.note(DiscoveryDiagnostic.SAVED_PROBE_REJECTED)
            synchronized(runLock) { if (isCurrentRun(runId)) resolved = null }
        }

        // A superseded run must not open a browse window at all.
        if (!isCurrentRun(runId)) return

        // 2. Bounded browse + authenticated counting (8 s wall clock total).
        val (authenticated, candidates) = browseAndAuthenticate(runId)
        val list = authenticated.values.toList()
        val choice = when (list.size) {
            1 -> list.first()
            else -> null
        }
        // Re-check after the window: a stop/newer run during browsing wins.
        if (!isCurrentRun(runId)) return
        if (candidates == 0) diagnostics.note(DiscoveryDiagnostic.BROWSE_NO_CANDIDATE)
        diagnostics.note(
            when {
                choice != null -> DiscoveryDiagnostic.VERDICT_ONE
                list.isEmpty() -> DiscoveryDiagnostic.VERDICT_NONE
                else -> DiscoveryDiagnostic.VERDICT_AMBIGUOUS
            },
        )
        lastOutcome = DiscoveryOutcome(null, choice, list)
        if (choice != null) {
            publishCurrent(runId, DiscoveryState.Discovered, onState, choice, persist = true)
        } else {
            // 0 or ≥2 authenticated endpoints: never guess, never save.
            publishCurrent(runId, DiscoveryState.ManualFallback, onState)
        }
    }

    /**
     * Bounded re-probe loop for the last known address: at most
     * [DiscoveryTiming.savedProbeTotalMs] of wall clock, each attempt capped.
     *
     * @return the endpoint when it answered the authenticated probe, else null.
     */
    private suspend fun reProbeSaved(runId: Long, saved: DiscoverySelection): DiscoverySelection? {
        val start = nowMs()
        var attempts = 0
        while (nowMs() - start < timing.savedProbeTotalMs) {
            val budget = timing.savedProbeTotalMs - (nowMs() - start)
            if (budget <= 0L) break
            val attemptBudget = minOf(budget, timing.savedProbeAttemptMs).coerceAtLeast(1L)
            attempts++
            val result = probeWithBudget(saved, start, timing.savedProbeTotalMs, attemptBudget, diagnostics)
            if (result is DiscoveryProbeResult.Authenticated) return saved
            val left = timing.savedProbeTotalMs - (nowMs() - start)
            if (left <= 0L) break
            delay(minOf(remainingRetryDelayMs, left))
        }
        if (attempts == 0) diagnostics.note(DiscoveryDiagnostic.SAVED_PROBE_REJECTED)
        return null
    }

    /** Publishes only while [runId] is still the active run. */
    private fun publishCurrent(
        runId: Long,
        next: DiscoveryState,
        onState: (DiscoveryState) -> Unit,
        selection: DiscoverySelection? = null,
        persist: Boolean = false,
    ) {
        synchronized(runLock) {
            if (!isCurrentRun(runId)) return
            resolved = selection
            if (persist && selection != null) settings.saveDestination(selection.ip, selection.port)
            publish(next, onState)
        }
    }

    private fun savedDestination(): DiscoverySelection? {
        val validated = DestinationValidator.validate(settings.savedIp, settings.savedPort)
        if (validated !is DestinationValidator.ValidationResult.Valid) return null
        return DiscoverySelection(validated.ip, validated.port)
    }

    private suspend fun probeWithBudget(
        candidate: DiscoverySelection,
        phaseStartMs: Long,
        phaseBudgetMs: Long,
        attemptCapMs: Long = phaseBudgetMs,
        diagnostics: DiscoveryDiagnostics? = null,
    ): DiscoveryProbeResult {
        val token = DevTokenValidator.canonicalOrNull(settings.devToken)
            ?: return DiscoveryProbeResult.Rejected("dev token must be exactly 64 hex characters")
        val remaining = phaseBudgetMs - (nowMs() - phaseStartMs)
        if (remaining <= 0L) {
            diagnostics?.note(DiscoveryDiagnostic.PROBE_REJECTED)
            return DiscoveryProbeResult.Rejected("budget exhausted")
        }
        val timeoutMs = minOf(remaining, attemptCapMs).coerceAtLeast(1L).toInt()
        val result = runProbe(candidate, token, timeoutMs)
        diagnostics?.note(
            when (result) {
                is DiscoveryProbeResult.Authenticated -> DiscoveryDiagnostic.PROBE_AUTHENTICATED
                is DiscoveryProbeResult.Rejected -> DiscoveryDiagnostic.PROBE_REJECTED
            },
        )
        return result
    }

    /** Runs one blocking probe, hopping off the caller's context when configured. */
    private suspend fun runProbe(
        candidate: DiscoverySelection,
        token: String,
        timeoutMs: Int,
    ): DiscoveryProbeResult = withProbeContext {
        probe.probe(candidate.ip, candidate.port, token, timeoutMs)
    }

    /**
     * Browses for the whole 8 s window and returns EVERY distinct endpoint that
     * authenticated with our token. Nothing is selected here: the caller applies
     * the frozen 0/1/≥2 rule afterwards.
     */
    private suspend fun browseAndAuthenticate(
        runId: Long,
    ): Pair<Map<String, DiscoverySelection>, Int> {
        val candidates = LinkedHashMap<String, DiscoverySelection>()
        val authenticated = LinkedHashMap<String, DiscoverySelection>()
        val listening = java.util.concurrent.atomic.AtomicBoolean(true)
        val stopThisBrowser: () -> Unit = {
            if (listening.getAndSet(false)) discovery.stop()
        }
        try {
            synchronized(runLock) {
                if (!isCurrentRun(runId)) return emptyMap<String, DiscoverySelection>() to 0
                stopBrowser = stopThisBrowser
                // The browse stage is observable at the coordinator too, so "opened
                // and found nothing" is distinguishable from "never opened", even
                // when the platform adapter is replaced by a test double.
                diagnostics.note(DiscoveryDiagnostic.BROWSE_STARTED)
                discovery.start { services ->
                    // Delivered on every resolve completion (concurrent resolves included).
                    synchronized(candidates) {
                        for (service in services) {
                            when (val validated = DiscoveryPolicy.candidate(service)) {
                                is DestinationValidator.ValidationResult.Valid -> {
                                    val selection = DiscoverySelection(validated.ip, validated.port)
                                    candidates.putIfAbsent(key(selection), selection)
                                }
                                is DestinationValidator.ValidationResult.Invalid -> Unit
                            }
                        }
                    }
                }
            }
            val start = nowMs()
            val attempted = LinkedHashSet<String>()
            // Candidates keep arriving during the window, so poll until the wall
            // clock runs out rather than stopping at the first batch. Each distinct
            // endpoint is probed EXACTLY ONCE: a rejection cannot be improved by
            // retrying inside the same window, and re-probing would flood the
            // network (and any other SayIt on it) for the whole 8 s.
            while (nowMs() - start < timing.discoveryWindowMs) {
                val next = synchronized(candidates) {
                    candidates.values.firstOrNull { key(it) !in attempted }
                }
                if (next == null) {
                    val left = timing.discoveryWindowMs - (nowMs() - start)
                    if (left <= 0L) break
                    delay(minOf(timing.pollIntervalMs, left).coerceAtLeast(1L))
                    continue
                }
                // Mark first so a budget-exhausted attempt cannot reselect it.
                attempted.add(key(next))
                val budget = timing.discoveryWindowMs - (nowMs() - start)
                if (budget <= 0L) break
                val result = probeWithBudget(
                    next,
                    start,
                    timing.discoveryWindowMs,
                    timing.discoveryProbeAttemptMs,
                    diagnostics,
                )
                if (result is DiscoveryProbeResult.Authenticated) {
                    authenticated[key(next)] = next
                }
                // A rejected candidate is simply skipped: never trust a service
                // that did not authenticate with our token.
            }
            val candidateCount = synchronized(candidates) { candidates.size }
            return authenticated.toMap() to candidateCount
        } finally {
            synchronized(runLock) {
                if (stopBrowser === stopThisBrowser) {
                    stopThisBrowser()
                    stopBrowser = null
                }
            }
        }
    }

    private fun key(selection: DiscoverySelection) = "${selection.ip}:${selection.port}"

    private fun publish(next: DiscoveryState, onState: (DiscoveryState) -> Unit) {
        state = next
        onState(next)
    }

    private companion object {
        /** Pause between two saved-address attempts inside the 3 s phase. */
        const val remainingRetryDelayMs = 250L

        /**
         * 1C-D-04@R2: cap on the remembered authenticated computers. A household or
         * office debug setup only ever has a few; the cap keeps the explicit switch
         * list bounded and the Watch's memory flat.
         */
        const val MAX_REMEMBERED_TARGETS = 4
    }
}

/**
 * Real authenticated probe: `GET /api/watch/discovery` with the existing Bearer
 * token. Success requires HTTP 200 and the exact frozen identity — a 401, a
 * timeout, or any malformed body is a rejection.
 *
 * Repair 2 必修 1: connect, the response-header wait and the body read share ONE
 * absolute deadline via [ProbeBudget]. `HttpURLConnection` could not express that
 * (its connect and read timeouts are independent serial phases that sum), and a
 * bare `soTimeout` is not enough either because it is an *idle* timeout that every
 * low-level read restarts — a peer dripping one byte per tick would never trip it.
 * So the socket's timeout is re-armed with the remaining budget before EVERY read,
 * the loops themselves abort once [ProbeBudget.remainingMs] hits zero, and the body
 * is capped in bytes.
 */
class HttpDiscoveryProbe(
    /** Upper bound for the connect phase, regardless of the caller's budget. */
    private val maxConnectTimeoutMs: Int = 1_000,
) : DiscoveryProbe {

    override fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult {
        val canonical = DevTokenValidator.canonicalOrNull(token)
            ?: return DiscoveryProbeResult.Rejected("dev token must be exactly 64 hex characters")
        val budgetMs = timeoutMs.coerceAtLeast(1)
        val budget = ProbeBudget(budgetMs, System.nanoTime() / 1_000_000L)

        var socket: java.net.Socket? = null
        var deadlineTask: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            // Phase 1: connect, bounded by the budget and the connect cap.
            val connectMs = minOf(budget.nextTimeoutMs(ProbeBudget.Phase.CONNECT), maxConnectTimeoutMs.toLong())
            if (connectMs <= 0L) return DiscoveryProbeResult.Rejected("budget exhausted")
            socket = java.net.Socket()
            val deadlineSocket = socket
            deadlineTask = deadlines.schedule(
                { try { deadlineSocket.close() } catch (_: Exception) { } },
                budget.remainingMs(), java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            socket.connect(java.net.InetSocketAddress(ip, port), connectMs.toInt())

            // Phase 2: request. Writing is bounded by the same deadline.
            val writeMs = budget.nextTimeoutMs(ProbeBudget.Phase.HEADERS)
            if (writeMs <= 0L) return DiscoveryProbeResult.Rejected("budget exhausted")
            socket.soTimeout = writeMs.toInt()
            socket.getOutputStream().apply {
                write(
                    (
                        "GET $SAYIT_DISCOVERY_PATH HTTP/1.1\r\n" +
                            "Host: $ip:$port\r\n" +
                            "Authorization: Bearer $canonical\r\n" +
                            "Accept: application/json\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                )
                flush()
            }

            // Phases 2-3: headers then body, every read re-armed from the budget.
            val stream = socket.getInputStream()
            val arm: (Int) -> Unit = { socket.soTimeout = it }
            val status = DiscoveryHttpResponse.readStatusAndHeaders(stream, budget, arm)
                ?: return DiscoveryProbeResult.Rejected("malformed HTTP response or deadline exceeded")
            val body = DiscoveryHttpResponse.readBody(stream, budget, arm)
                ?: return DiscoveryProbeResult.Rejected("deadline exceeded while reading the body")
            return DiscoveryPolicy.parseProbeResponse(status, body)
        } catch (e: Exception) {
            // A socket timeout surfaces here as SocketTimeoutException: the deadline
            // is the bound, not the peer's cooperation.
            return DiscoveryProbeResult.Rejected("network error: ${e.message}")
        } finally {
            deadlineTask?.cancel(false)
            try {
                socket?.close()
            } catch (ignored: Exception) {
                // best effort
            }
        }
    }

    private companion object {
        // Also bounds writes, for which Socket.soTimeout provides no timeout.
        val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "sayit-discovery-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/**
 * Delivery 1C Repair 2 必修 2 — the single-resolution-mode state machine.
 *
 * Exactly one resolution mode may be active at a time: automatic (saved-address
 * re-probe / mDNS browse) or a manual address probe. Every run carries a
 * generation, and a result is only accepted when its generation is still the
 * active one, so a late automatic answer can never overwrite a manual choice and
 * a cancelled run can never persist anything or unlock recording.
 *
 * Pure Kotlin (no Android, no coroutines), so the five contract scenarios are
 * covered by behaviour tests instead of source-string assertions.
 */
sealed class ResolverMode {
    /** Nothing is being resolved. */
    object Idle : ResolverMode()

    /** Automatic discovery (saved-address re-probe, then mDNS browse) is running. */
    data class Automatic(val generation: Long) : ResolverMode()

    /** A manually entered address is being probed. */
    data class Manual(val generation: Long) : ResolverMode()
}

/** The action the owner must perform for a transition. */
sealed class ResolverAction {
    /** Start automatic discovery, tagging the results with this generation. */
    data class StartAutomatic(val generation: Long) : ResolverAction()

    /** Probe this manual candidate, tagging the result with this generation. */
    data class StartManual(val generation: Long, val candidate: DiscoverySelection) : ResolverAction()

    /** Nothing to do. */
    object NoOp : ResolverAction()
}

/** A generation-tagged resolution outcome. */
sealed class ResolverOutcome {
    data class AutomaticSucceeded(val generation: Long, val selection: DiscoverySelection) : ResolverOutcome()
    data class AutomaticFailed(val generation: Long) : ResolverOutcome()
    data class ManualSucceeded(val generation: Long, val selection: DiscoverySelection) : ResolverOutcome()
    data class ManualFailed(val generation: Long) : ResolverOutcome()
}

/**
 * Owns the "one resolution mode at a time" rule.
 *
 * The owner must:
 * 1. execute exactly the [ResolverAction] returned here;
 * 2. ask whether a resolution is already running ([acceptsAutomatic]) instead of
 *    recollecting it from its own state;
 * 3. drop any coordinator/UI callback whose generation is not the one this engine
 *    handed out for that run.
 */
class ResolverEngine(
    /**
     * Invoked whenever an automatic run must actually stop (Config entry, manual
     * request, invalidation, teardown). The engine owns the decisions; the owner
     * only performs the side effect on its coordinator.
     */
    private val onCancelAutomatic: () -> Unit = {},
) {

    @Volatile
    var mode: ResolverMode = ResolverMode.Idle
        private set

    /** True only when a probe-authenticated target exists for the current mode. */
    @Volatile
    var hasVerifiedTarget: Boolean = false
        private set

    /** The target that passed the probe, or null. */
    @Volatile
    var verifiedTarget: DiscoverySelection? = null
        private set

    /**
     * True when an automatic run may be started: no target yet AND no resolution
     * already in flight. This is the Ready-entry gate.
     */
    fun acceptsAutomatic(isResolvingElsewhere: Boolean = false): Boolean =
        !hasVerifiedTarget && mode is ResolverMode.Idle && !isResolvingElsewhere

    /**
     * Ready entry point (also the retry path after an upload failure cleared the
     * target). Never stacks a second mode.
     *
     * @param force 1C-D-04@R2: the user explicitly asked to switch computers. An
     *   existing verified target must NOT suppress the run then — but a manual probe
     *   still owns the resolution, so an explicit switch can never race it.
     */
    fun onReadyEntered(isResolvingElsewhere: Boolean = false, force: Boolean = false): ResolverAction {
        if (hasVerifiedTarget && !force) {
            // The authenticated target is still valid: returning from Recording or
            // re-entering Ready must not restart discovery.
            return ResolverAction.NoOp
        }
        if (mode is ResolverMode.Automatic) {
            // A run for this same need is already in flight.
            return ResolverAction.NoOp
        }
        if (mode is ResolverMode.Manual) {
            // The user's manual probe owns the resolution; do not race it.
            return ResolverAction.NoOp
        }
        if (isResolvingElsewhere) return ResolverAction.NoOp
        return startAutomaticLocked()
    }

    /** Opens the Config screen: the automatic run no longer owns the resolution. */
    fun onConfigEntered() {
        cancelLocked()
    }

    /**
     * A manual address was submitted. Cancels and stops any automatic run first,
     * so the two modes can never be active together.
     */
    fun onManualRequested(candidate: DiscoverySelection): ResolverAction {
        cancelLocked()
        val generation = nextGeneration()
        mode = ResolverMode.Manual(generation)
        return ResolverAction.StartManual(generation, candidate)
    }

    /**
     * Applies an automatic verdict.
     *
     * @return true only when this outcome was the active run's own final result; a
     *   superseded or cancelled generation returns false and changes nothing.
     */
    fun onAutomaticResult(outcome: ResolverOutcome): Boolean {
        val current = mode
        if (current !is ResolverMode.Automatic) return false
        when (outcome) {
            is ResolverOutcome.AutomaticSucceeded -> {
                if (outcome.generation != current.generation) return false
                verifiedTarget = outcome.selection
                hasVerifiedTarget = true
                mode = ResolverMode.Idle
                return true
            }
            is ResolverOutcome.AutomaticFailed -> {
                if (outcome.generation != current.generation) return false
                clearTargetLocked()
                mode = ResolverMode.Idle
                return true
            }
            else -> return false // a manual outcome can never finish an automatic run
        }
    }

    /**
     * Applies a manual verdict.
     *
     * @return true only when this outcome was the active manual run's own result.
     */
    fun onManualResult(outcome: ResolverOutcome): Boolean {
        val current = mode
        if (current !is ResolverMode.Manual) return false
        when (outcome) {
            is ResolverOutcome.ManualSucceeded -> {
                if (outcome.generation != current.generation) return false
                verifiedTarget = outcome.selection
                hasVerifiedTarget = true
                mode = ResolverMode.Idle
                return true
            }
            is ResolverOutcome.ManualFailed -> {
                if (outcome.generation != current.generation) return false
                clearTargetLocked()
                mode = ResolverMode.Idle
                return true
            }
            else -> return false
        }
    }

    /**
     * The authenticated target stopped working (upload failed): the NEXT recording
     * may resolve again, but nothing is allowed to re-send the failed audio.
     */
    fun onTargetInvalidated() {
        cancelLocked()
        mode = ResolverMode.Idle
    }

    /** Screen/ViewModel teardown: no run survives and no late result is accepted. */
    fun onStopped() {
        cancelLocked()
        mode = ResolverMode.Idle
    }

    private var generationCounter = 0L

    private fun nextGeneration(): Long = ++generationCounter

    private fun startAutomaticLocked(): ResolverAction {
        val generation = nextGeneration()
        mode = ResolverMode.Automatic(generation)
        return ResolverAction.StartAutomatic(generation)
    }

    /**
     * 1C-D-04@R2: true while [generation] is still the automatic run that owns the
     * resolution. The explicit switch uses it to drop a browse whose run was
     * superseded (the user picked, opened Config, or left the screen meanwhile) —
     * such a browse must not change the picker or the target list.
     */
    fun isCurrentAutomaticRun(generation: Long): Boolean {
        val current = mode
        return current is ResolverMode.Automatic && current.generation == generation
    }

    /**
     * 1C-D-04@R2: the user explicitly picked this already-authenticated computer.
     *
     * Only a caller that authenticated the endpoint may use this, and the engine is
     * already Idle/cancelled when it is called: the pick must not be overwritable by
     * a late outcome from the run that was just cancelled, which is why the
     * generation is bumped first.
     */
    fun stateAdoptedByUser(candidate: DiscoverySelection) {
        generationCounter++
        mode = ResolverMode.Idle
        verifiedTarget = candidate
        hasVerifiedTarget = true
    }

    /**
     * 1C-D-04@R8 P0-A — ends the automatic run WITHOUT touching the verified target.
     *
     * `cancelLocked()` (used by `onConfigEntered` / `onTargetInvalidated`) clears the target,
     * which is right when the connection is genuinely gone and wrong when the resolution is
     * merely being handed over (an explicit switch browse). This variant bumps the generation —
     * so a late result from the cancelled run can never land — and leaves the authenticated
     * target in place, which keeps one single source of truth across the engine, the bridge, the
     * ViewModel and the recording gate.
     */
    fun cancelAutomaticRunKeepTarget() {
        generationCounter++
        mode = ResolverMode.Idle
        onCancelAutomatic()
    }

    /**
     * Releases the automatic run without touching the verified target. Used when a browse ends
     * and the resolution is no longer owned, while the computer in use must survive (an explicit
     * switch that was cancelled or that found nothing).
     */
    fun releaseAutomaticRun() {
        if (mode is ResolverMode.Automatic) {
            generationCounter++
            mode = ResolverMode.Idle
        }
    }

    /** Bumps the generation and drops the target so late results cannot land. */
    private fun cancelLocked() {
        generationCounter++
        mode = ResolverMode.Idle
        clearTargetLocked()
        onCancelAutomatic()
    }

    /**
     * Cancels only when an automatic run owns the resolution: a manual probe is not
     * interruptible (its result is filtered by generation instead), and an idle
     * engine has nothing to stop.
     */
    private fun cancelAutomaticIfActive() {
        if (mode is ResolverMode.Automatic) cancelLocked()
    }

    private fun clearTargetLocked() {
        verifiedTarget = null
        hasVerifiedTarget = false
    }
}

/**
 * Delivery 1C Repair 2 — the glue between the per-run [ResolverEngine] and the
 * automatic/manual transports.
 *
 * It exists so the WHOLE resolution chain can be behaviour-tested: the engine's
 * mutual exclusion, the coordinator's final-state callback (the Repair 2 verdict
 * gap where terminal states were published with an empty callback), and the
 * generation gate that keeps a superseded run from publishing or persisting. The
 * ViewModel owns one instance and forwards UI events to it.
 *
 * The transports are injected and report leaf data only:
 * - [executeAutomatic] returns the authenticated endpoint the coordinator observed
 *   (null means the run ended in the manual fallback);
 * - [executeManual] returns whether the manual probe authenticated.
 * So this file stays pure Kotlin with no Android dependency.
 */
class ResolverBridge(
    /** Token gate: a probe is only launched with a well-formed 64-hex token. */
    private val isValidToken: () -> Boolean,
    /**
     * Runs automatic discovery. 1C-D-04@R2: it reports the WHOLE run
     * ([DiscoveryOutcome]) instead of only the frozen exactly-one verdict, so the
     * "which computers authenticated" list survives into the explicit switch UI.
     * `null` means the transport refused to run (already in flight / no browser).
     */
    private val executeAutomatic: suspend (runId: Long) -> DiscoveryOutcome? = { null },
    /** Runs one manual probe; returns true only when it authenticated. */
    private val executeManual: suspend (candidate: DiscoverySelection, runId: Long) -> Boolean,
    /** Stops the automatic transport (Config entry, manual request, teardown). */
    private val stopAutomatic: () -> Unit = {},
    private val persistManual: (DiscoverySelection) -> Unit = {},
    /** Runs the explicit switch browse; `null` means no collector is attached. */
    private val executeSwitch: suspend (runId: Long) -> List<DiscoverySelection>? = { null },
    /**
     * Confirms ONE candidate before the user's explicit pick adopts it. It is deliberately
     * separate from [executeAutomatic]: confirming a pick must not disturb the target in use
     * (the default would stop the coordinator's run and clear the stored destination).
     */
    private val executePickProbe: suspend (candidate: DiscoverySelection) -> Boolean = { false },
) {

    private val engine = ResolverEngine(onCancelAutomatic = { stopAutomatic() })

    /** The final state of the last accepted run, or null when none finished yet. */
    @Volatile
    var lastPublishedState: DiscoveryState? = null
        private set

    /**
     * 1C-D-04@R2: every distinct computer that passed the authenticated Bearer probe
     * in this session, so a switch can offer the ones already seen even before a new
     * browse returns. Addresses stay inside the Watch: never logged, never broadcast.
     */
    @Volatile
    private var knownTargets: List<DiscoverySelection> = emptyList()

    /** The computers authenticated in this session (bounded, address data). */
    val rememberedTargets: List<DiscoverySelection> get() = knownTargets

    /** Bumped by [newSwitchRun] so a superseded browse cannot publish. */
    @Volatile
    private var switchGeneration: Long = 0L

    /** The target the bridge accepted, or null. Only authenticated targets appear. */
    val verifiedTarget: DiscoverySelection? get() = engine.verifiedTarget

    /** True only when recording may start (exactly one authenticated target). */
    val hasVerifiedTarget: Boolean get() = engine.hasVerifiedTarget

    /** True when a Ready entry would start automatic discovery (Repair 2 必修 2). */
    fun acceptsAutomatic(isResolvingElsewhere: Boolean = false): Boolean =
        engine.acceptsAutomatic(isResolvingElsewhere)

    /**
     * Ready entry point. Returns true when a new automatic run ran and published a
     * verdict, so the owner can refresh its UI from [lastPublishedState].
     */
    suspend fun onReadyEntered(isResolvingElsewhere: Boolean = false): Boolean =
        runAutomatic(isResolvingElsewhere, force = false)

    /** True when a manual probe currently owns the resolution. */
    val isManualResolution: Boolean get() = engine.mode is ResolverMode.Manual

    /** True when an automatic run still owns the resolution. */
    val isAutomaticResolution: Boolean get() = engine.mode is ResolverMode.Automatic

    /**
     * 1C-D-04@R3 必修 1/3 — the single owner of the explicit "switch computer" state.
     *
     * R2 kept this state in the ViewModel, which produced a deterministic failure:
     * `requestSwitch()` adopted a freshly discovered computer before it had been
     * added to the picker's list, so the membership check rejected the user's own
     * first switch. The list and the accept/reject decision now live here, where the
     * browse result is merged, so "what was authenticated" and "what the user may
     * pick" can never drift apart again.
     *
     * Invariants:
     * - [openPicker] snapshots the currently authenticated computers;
     * - [handleBrowseResult] merges a browse and returns the single NEW computer for
     *   adoption, so the caller never has to add it to the list itself;
     * - [pick] accepts ONLY an endpoint that was authenticated in this session and is
     *   still in the list, so a typed or spoofed address can never be adopted;
     * - the current target survives an empty/ambiguous browse untouched.
     */
    @Volatile
    private var pickerOpen: Boolean = false

    /** The authenticated computers offered by the open picker. */
    val pickerTargets: List<DiscoverySelection> get() = knownTargets

    /** True while the explicit switch picker is open. */
    val isPickerOpen: Boolean get() = pickerOpen

    /**
     * 1C-D-04@R7 必修 — re-authenticates a computer before it becomes the target.
     *
     * A historical candidate may have gone away (its address is reassigned, the PC
     * sleeps). Adopting it from the remembered list without asking again would show a
     * connection that does not exist, so the pick is confirmed with one bounded
     * authenticated probe first; a failure changes nothing and writes no setting.
     */
    sealed class PickResult {
        /** The candidate answered the probe and is now the target. */
        data class Adopted(val target: DiscoverySelection) : PickResult()

        /** The candidate did not authenticate: the current target is untouched. */
        object Refused : PickResult()

        /** A newer pick or a cancel superseded this one: nothing changed. */
        object Superseded : PickResult()
    }

    /** Bumped by [pick] so a superseded probe cannot adopt afterwards. */
    @Volatile
    private var pickGeneration: Long = 0L

    /**
     * 1C-D-04@R7 必修 1/2 — the user explicitly picked a computer.
     *
     * The endpoint must already be in [knownTargets] (this session authenticated it),
     * and it is probed once more before adoption. The current target is replaced only
     * on that success; a refusal or a superseded probe leaves everything as it was.
     */
    suspend fun pick(candidate: DiscoverySelection): PickResult {
        val allowed = knownTargets.contains(candidate) || engine.verifiedTarget == candidate
        if (!allowed) return PickResult.Refused
        pickerOpen = false
        // Already the target in use and still healthy: nothing to re-verify.
        if (engine.verifiedTarget == candidate) {
            engine.stateAdoptedByUser(candidate)
            lastPublishedState = DiscoveryState.Discovered
            return PickResult.Adopted(candidate)
        }
        val generation = ++pickGeneration
        if (!isValidToken()) return PickResult.Refused
        val ok = executePickProbe(candidate)
        if (generation != pickGeneration) return PickResult.Superseded
        if (!ok) {
            lastPublishedState =
                if (engine.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
            return PickResult.Refused
        }
        // Adopt without touching the automatic run: the probe above is the confirmation,
        // and `stateAdoptedByUser` bumps the generation so no late result can land on top.
        engine.stateAdoptedByUser(candidate)
        mergeKnown(listOf(candidate))
        persistManual(candidate)
        lastPublishedState = DiscoveryState.Discovered
        return PickResult.Adopted(candidate)
    }

    /** Cancels a pick that is still probing: its result must not be adopted. */
    fun cancelPendingPick() {
        pickGeneration++
    }

    /**
     * The computers the open picker offers: every computer authenticated in this
     * session, with the one in use first so it is always visible.
     */
    val pickerCandidates: List<DiscoverySelection>
        get() {
            val out = LinkedHashSet<DiscoverySelection>()
            engine.verifiedTarget?.let { out.add(it) }
            out.addAll(knownTargets)
            return out.toList()
        }

    /** The computer in use, even when it is not (or no longer) in the browse list. */
    val currentTarget: DiscoverySelection? get() = engine.verifiedTarget

    /**
     * Opens the picker: snapshots the authenticated computers. Returns nothing to
     * adopt — an ordinary automatic run must first SUPERSEDE the previous target and
     * verified state, which is the caller's Ready-entry decision, not the switch's.
     */
    fun openPicker() {
        pickerOpen = true
    }

    /** Closes the picker without changing the target. Idempotent. */
    fun cancelPicker() {
        pickerOpen = false
        cancelPendingPick()
    }

    /**
     * 1C-D-04@R7 必修 2 — applies the explicit browse result.
     *
     * R4/R5/R6 adopted the single newly found computer here. R7's contract forbids
     * that: an explicit switch must ALWAYS show the candidates so the user picks,
     * including when exactly one new computer was found. This method therefore only
     * merges what authenticated and refreshes the picker — it never adopts.
     *
     * The computer in use is preserved deliberately. It is not re-probed inside the
     * browse, so "did not appear in this 8 s window" is not evidence that it is gone;
     * the bounded idle health check is what decides that (1C-D-04@R7 §2A).
     */
    suspend fun handleSwitchBrowse(runId: Long): List<DiscoverySelection> {
        val action = engine.onReadyEntered(force = true)
        if (action !is ResolverAction.StartAutomatic) {
            endPickerSearch()
            return emptyList()
        }
        val generation = action.generation
        if (!isValidToken()) {
            endPickerSearch()
            return emptyList()
        }
        val collected = executeSwitch(runId)
        if (collected != null) mergeKnown(collected)
        if (!engine.isCurrentAutomaticRun(generation)) {
            endPickerSearch()
            return emptyList()
        }
        // "The search is over" and "the user has a target" are different transitions
        // and are never merged (1C-D-04@R4 必修 1): the resolution is released to Idle
        // and the target stays exactly as it was.
        endPickerSearch()
        lastPublishedState =
            if (engine.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
        return collected ?: emptyList()
    }

    /**
     * 1C-D-04@R3 必修 3: the picker's search is over and no browse owns the
     * resolution any more.
     */
    private fun endPickerSearch() {
        // 1C-D-04@R7 必修 2: the browse is finished, but "the search is over" must NOT clear
        // the computer in use — cancelling the picker has to leave it selected and usable.
        engine.releaseAutomaticRun()
    }

    /**
     * 1C-D-04@R2 stage B of the explicit switch: browse for a computer that is not
     * the one in use, while KEEPING the current target until the user actually
     * picks.
     *
     * Kept as the low-level entry used by tests and by [handleSwitchBrowse]; it is
     * [handleSwitchBrowse] that owns the picker/list bookkeeping.
     */
    suspend fun onSwitchBrowse(runId: Long): List<DiscoverySelection> = handleSwitchBrowse(runId)

    /** Opens Config: an automatic run no longer owns the resolution. */
    fun onConfigEntered() {
        engine.onConfigEntered()
        lastPublishedState = null
        knownTargets = emptyList()
        switchGeneration++
    }

    /**
     * 1C-D-04@R2: opens a new explicit-switch generation. A browse whose generation is
     * no longer current must not change the target list or the picker.
     */
    fun newSwitchRun(): Long = ++switchGeneration

    /** True while [runId] is still the newest explicit-switch generation. */
    fun isCurrentSwitchRun(runId: Long): Boolean = runId == switchGeneration

    /**
     * A manual address was submitted; automatic discovery is cancelled first, so
     * the two modes can never overlap. Returns true when a probe ran.
     */
    suspend fun onManualRequested(candidate: DiscoverySelection): Boolean {
        return when (val action = engine.onManualRequested(candidate)) {
            is ResolverAction.StartManual -> {
                val ok = if (isValidToken()) {
                    executeManual(action.candidate, action.generation)
                } else {
                    false
                }
                routeManual(action.generation, action.candidate, ok)
            }
            else -> false
        }
    }

    /**
     * 1C-D-04@R2: adopts the computer the USER explicitly picked from the
     * authenticated list. Only an address this session authenticated can be passed
     * here; the engine's generation is bumped first so no in-flight run can land on
     * top of the user's choice afterwards.
     */
    fun onTargetChosen(candidate: DiscoverySelection) {
        engine.onConfigEntered() // cancels any automatic run and clears the target
        engine.stateAdoptedByUser(candidate)
        mergeKnown(listOf(candidate))
        // The user's pick is the ONE thing allowed to persist a destination other
        // than the frozen exactly-one verdict, so it goes through the same
        // post-authentication persistence as the manual path.
        persistManual(candidate)
        lastPublishedState = DiscoveryState.Discovered
    }

    /** The authenticated target stopped working (upload failed). */
    fun onTargetInvalidated() {
        engine.onTargetInvalidated()
        lastPublishedState = null
    }

    /**
     * 1C-D-04@R8 P0-A — releases the automatic run and stops the transport while KEEPING the
     * computer in use.
     *
     * Used when the resolution is handed over (an explicit switch browse) as opposed to
     * cancelled because the connection is gone. The engine's generation is bumped so a late
     * result cannot publish, the transport is stopped so the old browse/NSD session really ends,
     * and `verifiedTarget`/`hasVerifiedTarget` are left untouched — the UI, the ViewModel's
     * `verifiedDestination` and the recording gate therefore keep describing the same computer.
     */
    fun releaseAutomaticRunKeepTarget() {
        engine.cancelAutomaticRunKeepTarget()
    }

    /** Screen/ViewModel teardown. */
    fun onStopped() {
        engine.onStopped()
        lastPublishedState = null
        knownTargets = emptyList()
    }

    /** Records every authenticated endpoint, newest last, unbounded duplicates removed. */
    private fun mergeKnown(targets: List<DiscoverySelection>) {
        if (targets.isEmpty()) return
        val merged = LinkedHashSet(knownTargets)
        merged.addAll(targets)
        knownTargets = merged.toList().take(MAX_REMEMBERED_TARGETS)
    }

    /**
     * Runs one automatic resolution and routes its verdict through the generation
     * gate. The [DiscoveryOutcome] (not just the frozen single) is what makes the
     * authenticated computer list available to an explicit switch.
     */
    private suspend fun runAutomatic(
        isResolvingElsewhere: Boolean = false,
        force: Boolean,
    ): Boolean {
        val action = engine.onReadyEntered(isResolvingElsewhere, force)
        if (action !is ResolverAction.StartAutomatic) return false
        val generation = action.generation
        if (!isValidToken()) return routeAutomatic(generation, null, emptyList())
        val outcome = executeAutomatic(generation)
        // 1C-D-04@R2: a re-probed saved address is a first-class verdict too. The
        // frozen rule is "the saved computer still answers → keep using it", so it
        // must reach the engine exactly like an exactly-one discovery does.
        val target = outcome?.single ?: outcome?.existingAddress
        return routeAutomatic(generation, target, outcome?.authenticated ?: emptyList())
    }

    /**
     * Routes an automatic verdict. The generation gate lives in the engine, so a
     * result whose run was superseded or cancelled is dropped here — it neither
     * changes the target nor publishes a state.
     */
    private fun routeAutomatic(
        generation: Long,
        target: DiscoverySelection?,
        authenticated: List<DiscoverySelection>,
    ): Boolean {
        val outcome = if (target != null) {
            ResolverOutcome.AutomaticSucceeded(generation, target)
        } else {
            ResolverOutcome.AutomaticFailed(generation)
        }
        if (!engine.onAutomaticResult(outcome)) return false
        mergeKnown(authenticated)
        lastPublishedState =
            if (engine.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
        return true
    }

    private fun routeManual(generation: Long, candidate: DiscoverySelection, ok: Boolean): Boolean {
        val outcome = if (ok) {
            ResolverOutcome.ManualSucceeded(generation, candidate)
        } else {
            ResolverOutcome.ManualFailed(generation)
        }
        if (!engine.onManualResult(outcome)) return false
        if (ok) persistManual(candidate)
        lastPublishedState =
            if (engine.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
        return true
    }

    companion object {
        /**
         * 1C-D-04@R2: cap on the remembered authenticated computers. A debug setup
         * has a handful at most; the cap keeps the picker and memory bounded.
         */
        private const val MAX_REMEMBERED_TARGETS = 4

        /** The production adapter is shared with integration tests. */
        fun forCoordinator(coordinator: DiscoveryCoordinator?, isValidToken: () -> Boolean) = ResolverBridge(
            isValidToken = isValidToken,
            executeAutomatic = { coordinator?.awaitOutcome() },
            executeManual = { candidate, _ -> coordinator?.probeCandidate(candidate) ?: false },
            stopAutomatic = { coordinator?.stop() },
            persistManual = { coordinator?.adopt(it) },
            executeSwitch = { runId -> coordinator?.collectAndSelect() },
            executePickProbe = { candidate -> coordinator?.probeCandidate(candidate) ?: false },
        )
    }
}

/** Debug-only factories, mirroring [Transport]: release builds get none. */
object Discovery {
    /** Address-free category tag for the platform log; the stages are the evidence. */
    private const val LOG_TAG = "SayItDiscovery"

    /** One shared, bounded category log for both halves of the discovery chain. */
    val diagnostics: CategoryLog = CategoryLog(capacity = 96, sink = { android.util.Log.i(LOG_TAG, it) })

    /** @return the platform browser for debug builds, or null in release. */
    fun browser(context: android.content.Context): ServiceDiscovery? =
        if (com.sayit.watch.BuildConfig.DEBUG) AndroidNsdDiscovery(context, diagnostics) else null

    /** @return the authenticated HTTP probe for debug builds, or null in release. */
    fun probe(): DiscoveryProbe? =
        if (com.sayit.watch.BuildConfig.DEBUG) HttpDiscoveryProbe() else null
}
