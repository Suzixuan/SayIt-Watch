package com.sayit.watch.net

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1C-D-04@R7 — the single owner of "which connection task is running".
 *
 * The user-visible regression this class fixes: with the Watch already on the Ready
 * screen, starting the PC afterwards did nothing. `RecordingScreen` only asked for a
 * search when `ui.screen` **changed**, so one failed first round left the screen with no
 * scheduler and the user had to kill the app and reopen it.
 *
 * Every search therefore goes through exactly one owner:
 *
 * 1. **One round at a time.** A new intent cancels the round in flight (whose result the
 *    transport's own generation gate then drops) and starts a replacement immediately. An
 *    explicit "重新搜索" is therefore never silently refused because an older round still
 *    runs, and rounds never stack.
 * 2. **Foreground recovery.** While the app is in the foreground, Ready has a valid token
 *    and no usable computer, the owner searches, and after every failed round it waits
 *    [retryDelayMs] and searches again. A PC that comes up later is found by one of those
 *    rounds — no restart required.
 * 3. **Bounded idle revalidation.** While a computer IS in use the owner runs a bounded
 *    authenticated probe every [healthCheckIntervalMs]. Rounds are strictly successive, so
 *    a probe can never overlap a browse or a manual probe. A failed probe clears the
 *    usable state and the recovery loop takes over.
 * 4. **Foreground only.** [stop] cancels the round and ends all scheduling; nothing polls
 *    in the background.
 *
 * The owner schedules; it never decides policy. Authentication, target selection and the
 * frozen 3 s/8 s budgets stay in [DiscoveryCoordinator] / [ResolverBridge] — the injected
 * callbacks are the only things this class knows about.
 *
 * The rounds run on their OWN scheduler instead of the caller's scope. That matters
 * twice: `viewModelScope` is `Dispatchers.Main`, where the multi-second waits would hold
 * the UI thread, and a test dispatcher would freeze the loop's virtual clock and stall it.
 * The transport calls still hop to their own I/O dispatchers inside.
 */
class ConnectionTaskOwner(
    /** Executes one full automatic resolution (saved-address re-probe, then browse). */
    private val resolveNow: suspend () -> Unit,
    /** Executes the explicit switch browse. Never adopts anything by itself. */
    private val browseForPicker: suspend () -> Unit,
    /**
     * Cancels whatever the transport is doing and drops the current usable state. Called
     * before every resolution round, so two resolutions can never overlap.
     */
    private val cancelTransport: () -> Unit,
    /** True only when an authenticated, usable computer exists right now. */
    private val isConnected: () -> Boolean,
    /** Wait before the next recovery round after a failed one. */
    private val retryDelayMs: Long = DefaultRetryDelayMs,
    /** Gap between two bounded health checks while a computer is in use. */
    private val healthCheckIntervalMs: Long = DefaultHealthCheckIntervalMs,
    /**
     * Revalidates the computer in use with ONE bounded authenticated probe.
     * @return true when it still answered.
     */
    private val probeCurrentTarget: suspend () -> Boolean,
    /** Reports that a round started (the UI renders "connecting"). */
    private val onStarted: () -> Unit = {},
    /** Reports that the current round finished (success or failure). */
    private val onFinished: () -> Unit = {},
    /**
     * Releases an in-flight browse before a switch round without dropping the computer in
     * use (1C-D-04@R7 必修 2). Defaults to [cancelTransport] for callers that do not
     * distinguish the two.
     */
    private val cancelForRefresh: () -> Unit = cancelTransport,
    /** Injectable clock; only the tests change it. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /** The intents a foreground Ready screen can express; the newest one wins. */
    enum class Pending { SEARCH, BROWSE }

    /**
     * One scheduled round. The placeholder is registered BEFORE its coroutine starts, so a
     * round that finishes immediately still finds (and clears) its own slot.
     */
    private class Round {
        @Volatile
        var job: Job? = null
    }

    private val lock = Any()

    /** Own scheduling scope: never the caller's (main or virtual-time) dispatcher. */
    private val scheduler = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("sayit-connection-owner"),
    )

    @Volatile
    private var foreground: Boolean = false

    /** The round in flight. Replaced atomically under [lock]. */
    private var round: Round? = null

    /** The intent waiting to be run, or null. Address-free; safe to expose. */
    @Volatile
    private var pending: Pending? = null

    /** True while a round is in flight. */
    val isTaskRunning: Boolean get() = synchronized(lock) { round?.job?.isActive == true }

    /** True while the app is in the foreground and the owner may schedule. */
    val isForeground: Boolean get() = foreground

    /** The intent waiting to be run, or null. */
    val pendingIntent: Pending? get() = pending

    /**
     * The app entered or left the foreground (1C-D-04@R7 §2A).
     *
     * Returning to the foreground revalidates IMMEDIATELY through the normal round: with a
     * computer in use the round re-probes it (one bounded authenticated probe, no browse),
     * otherwise it searches. Historical authentication is never reused as proof of being
     * online.
     */
    fun onForegroundChanged(inForeground: Boolean) {
        foreground = inForeground
        if (!inForeground) {
            stop()
            return
        }
        supersede(Pending.SEARCH)
    }

    /**
     * Cancels the round in flight and runs [intent] as the next round. A later call always
     * wins, so rapid taps collapse into the newest intent instead of stacking work.
     */
    fun restartNow(intent: Pending) {
        if (!foreground) return
        supersede(intent)
    }

    /**
     * Expresses an intent: coalesces into the running round when it is already working, so
     * re-entering the Ready screen is not "cancel and redo".
     */
    fun requestRefresh(intent: Pending) {
        if (!foreground) return
        synchronized(lock) {
            if (round?.job?.isActive == true) {
                // Already working: the newest intent becomes the next round.
                pending = intent
                return
            }
        }
        supersede(intent)
    }

    /** Cancels the round and ends all scheduling (background, teardown). */
    fun stop() {
        val running = synchronized(lock) {
            val current = round
            round = null
            pending = null
            current
        }
        running?.job?.cancel()
    }

    /** Teardown: no scheduling survives the ViewModel. */
    fun onDestroyed() {
        foreground = false
        stop()
    }

    /**
     * Replaces the round: the old one is cancelled first and a new round for [intent] starts
     * immediately. The old round cannot revive itself — its result is already dropped by the
     * transport's generation gate, and it exits at its own cleanup.
     */
    private fun supersede(intent: Pending) {
        if (!foreground) return
        val previous = synchronized(lock) {
            val current = round
            round = null
            pending = null
            current
        }
        previous?.job?.cancel()
        launch(intent)
    }

    /** Starts one round for [intent] and registers it before it can run. */
    private fun launch(intent: Pending) {
        val placeholder = Round()
        synchronized(lock) {
            if (!foreground) return
            round = placeholder
        }
        val job = scheduler.launch(start = CoroutineStart.LAZY) { run(intent, placeholder) }
        placeholder.job = job
        job.start()
    }

    /** Consumes the queued intent, if any. */
    private fun takePending(): Pending? = synchronized(lock) { pending.also { pending = null } }

    /**
     * One round: runs its intent, waits out the gap, then hands over to the next round —
     * which is how the recovery retry and the idle health check keep going.
     */
    private suspend fun run(intent: Pending, self: Round) {
        try {
            onStarted()
            try {
                when (intent) {
                    Pending.SEARCH -> {
                        cancelTransport()
                        resolveNow()
                    }
                    Pending.BROWSE -> {
                        cancelForRefresh()
                        browseForPicker()
                    }
                }
            } finally {
                onFinished()
            }
            val next = takePending() ?: waitForNextRound() ?: return
            supersede(next)
        } finally {
            synchronized(lock) {
                if (round === self) round = null
            }
        }
    }

    /**
     * Waits out one round's gap and reports what the next round should be.
     *
     * The idle health check runs here — between rounds, never inside one — and its result
     * is applied under [NonCancellable] so a replacement round cannot observe a
     * half-applied transition.
     *
     * @return the next intent, or null when scheduling must end.
     */
    private suspend fun waitForNextRound(): Pending? {
        if (!foreground) return null
        if (isConnected()) {
            val stillUp = withContext(NonCancellable) { probeCurrentTarget() }
            if (!stillUp) withContext(NonCancellable) { cancelTransport() }
        }
        if (!foreground) return null
        val wait = if (isConnected()) healthCheckIntervalMs else retryDelayMs
        val until = nowMs() + wait
        while (foreground && pending == null) {
            val left = until - nowMs()
            if (left <= 0L) break
            delay(left)
        }
        if (!foreground) return null
        return takePending() ?: Pending.SEARCH
    }

    companion object {
        /**
         * 1C-D-04@R7 §2A: after a failed round the Watch waits 5 s and searches again, so a
         * PC started later is picked up without restarting the app.
         */
        const val DefaultRetryDelayMs: Long = 5_000L

        /** 1C-D-04@R7 §2A: idle revalidation cadence for the computer in use. */
        const val DefaultHealthCheckIntervalMs: Long = 5_000L
    }
}
