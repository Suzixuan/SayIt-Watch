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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Monotonic round identity: a late round cleanup must never clear a newer round's slot. */
private val ROUND_IDS = AtomicLong(0)

/**
 * 1C-D-04@R7/R8 — the single owner of "which connection task is running".
 *
 * The user-visible regression this class fixes: with the Watch already on the Ready screen,
 * starting the PC afterwards did nothing. `RecordingScreen` only asked for a search when
 * `ui.screen` **changed**, so one failed first round left the screen with no scheduler and the
 * user had to kill the app and reopen it.
 *
 * R8 seals the lifecycle. The owner is one serialized loop with exactly two modes:
 *
 * | state | what runs | what must NOT happen |
 * |---|---|---|
 * | **connected** | one bounded authenticated probe of the current target every [healthCheckIntervalMs]; target and recording gate stay untouched between probes | no browse (`ServiceDiscovery.start()` stays 0), no full search, no target clearing |
 * | **searching** | one full resolution round, then wait [retryDelayMs] and repeat | rounds must not stack or overlap |
 *
 * Only a FAILED probe clears the usable state; a successful probe goes back to waiting, so an
 * idle connected Watch never loses its connection or its recording entry point.
 *
 * Hand-off is serialized: a new intent cancels the round in flight, WAITS for it (bounded) and
 * only then starts the replacement, so the old browse/NSD session is really stopped before a
 * second listener can exist. The synchronous transport stop runs before any suspension point
 * in a round, so it is delivered even when the round is cancelled mid-flight.
 *
 * Everything is foreground-gated. [pauseForeground] ends probing as well as searching, and the
 * suspended loop cannot touch UI state, the target or the settings afterwards.
 *
 * The owner schedules; it never decides policy. Authentication, target selection and the frozen
 * 3 s/8 s budgets stay in [DiscoveryCoordinator] / [ResolverBridge] — the injected callbacks are
 * the only things this class knows about.
 *
 * Rounds run on their OWN scheduler instead of the caller's scope: `viewModelScope` is
 * `Dispatchers.Main`, where the multi-second waits would hold the UI thread, and a test
 * dispatcher would freeze the loop's virtual clock and stall it.
 */
class ConnectionTaskOwner(
    /**
     * Revalidates the target in use.
     *
     * @param force true when the resolution must start from scratch (a failed upload, an explicit
     *   re-search); false when a live target may simply be re-probed instead of re-browsed. This
     *   is what keeps an already-connected Watch from running a full search just because a round
     *   was scheduled.
     */
    private val resolveNow: suspend (force: Boolean) -> Unit,
    /** Executes the explicit switch browse. Never adopts anything by itself. */
    private val browseForPicker: suspend () -> Unit,
    /**
     * Drops the current usable state and stops the transport. Only a failed probe, a failed
     * upload or an explicit user action may reach this — never a browse hand-off.
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
    /**
     * Releases an in-flight browse before a switch round WITHOUT dropping the computer in use
     * (1C-D-04@R7 必修 2 / R8 P0-A). Defaults to [cancelTransport] for callers that do not
     * distinguish the two.
     */
    private val cancelForRefresh: () -> Unit = cancelTransport,
    /** Reports that an intent round started (the UI renders "connecting"). */
    private val onStarted: () -> Unit = {},
    /** Reports that an intent round finished. */
    private val onFinished: () -> Unit = {},
    /** Injectable clock; only the tests change it. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * Upper bound for waiting on a cancelled round during a hand-off or a stop.
     *
     * The transport teardown itself is synchronous and already runs before the cancelled round can
     * suspend, so a healthy cancel returns in microseconds and this bound is only reached when a
     * platform call is genuinely stuck. It is kept short because the callers include
     * `Activity.onStop` and the recording entry point, where blocking the UI thread for seconds
     * would be its own defect.
     */
    private val cancelJoinBudgetMs: Long = DefaultCancelJoinBudgetMs,
) {

    /**
     * The intents a foreground Ready screen can express; the newest one wins.
     *
     * [SEARCH_KEEP] exists because "check the connection I already have" and "find a connection"
     * are different jobs. R7 ran the second for both, which is why an idle connected Watch kept
     * tearing its own target down.
     */
    enum class Pending {
        /** Revalidate the current target if there is one; only search when there is none. */
        SEARCH_KEEP,

        /** Drop any usable state and run a full resolution from scratch. */
        SEARCH,

        /** Refresh the explicit-switch candidate list. Never adopts. */
        BROWSE,
    }

    /** What the loop is doing right now. Address-free; safe to expose. */
    enum class RoundKind { IDLE_CONNECTED, SEARCH_KEEP, SEARCH, BROWSE }

    private class Round {
        /** Identity of this round; a late cleanup must not clear a NEWER round's slot. */
        val id: Long = ROUND_IDS.incrementAndGet()

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

    private var round: Round? = null

    /** The intent queued for the next round, or null. */
    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var kind: RoundKind = RoundKind.IDLE_CONNECTED

    /** True while a round is in flight. */
    val isTaskRunning: Boolean get() = synchronized(lock) { round?.job?.isActive == true }

    /** True while the app is in the foreground and the owner may schedule. */
    val isForeground: Boolean get() = foreground

    /** The intent queued for the next round, or null. */
    val pendingIntent: Pending? get() = pending

    /** What the loop is doing right now. */
    val roundKind: RoundKind get() = kind

    /**
     * The app entered or left the foreground (1C-D-04@R7 §2A).
     *
     * Returning to the foreground revalidates immediately through the normal round: with a
     * computer in use that is one bounded authenticated probe, otherwise a search.
     */
    fun onForegroundChanged(inForeground: Boolean) {
        if (inForeground) {
            revalidateNow()
        } else {
            pauseForeground()
        }
    }

    /**
     * Foreground revalidation / explicit re-search entry point.
     *
     * It runs ONE round that revalidates the computer in use when there is one (no browse, no
     * "clearing" of the connection) and searches when there is none. A queued intent always wins.
     */
    fun revalidateNow() {
        foreground = true
        supersede(Pending.SEARCH_KEEP)
    }

    /** Suspends everything: cancels the round, stops the transport and ends probing. */
    fun pauseForeground() {
        foreground = false
        stop()
        kind = RoundKind.IDLE_CONNECTED
    }

    /** Resumes after [pauseForeground]; no-op when already in the foreground. */
    fun resumeForeground() {
        if (foreground) return
        foreground = true
        supersede(Pending.SEARCH_KEEP)
    }

    /**
     * Cancels the round in flight and runs [intent] as the next round. A later call always wins,
     * so rapid taps collapse into the newest intent instead of stacking work.
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
                pending = intent
                return
            }
        }
        supersede(intent)
    }

    /** Cancels the round, stops the transport and ends all scheduling. */
    fun stop() {
        val running = synchronized(lock) {
            val current = round
            round = null
            pending = null
            current
        }
        running?.job?.cancel()
        // The round's synchronous transport stop runs before any suspension point, so it is
        // delivered even though the job was cancelled. The bounded join below then guarantees
        // the cancelled browse/NSD session is over before this returns.
        joinBounded(running?.job)
    }

    /** Teardown: no scheduling survives the ViewModel. */
    fun onDestroyed() {
        foreground = false
        stop()
    }

    /**
     * Replaces the round: the old one is cancelled and JOINED first, then a new round for [intent]
     * starts. Because the hand-off waits, the old browse/NSD session is fully stopped before the
     * replacement opens a listener, so two listeners can never overlap.
     *
     * The old round is also detached by IDENTITY, so a cancelled round that is still unwinding
     * cannot clear the slot of the round that replaced it (which would leave the owner with no
     * round at all and silently drop the user's newest intent).
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
        joinBounded(previous?.job)
        launch(intent)
    }

    /** Starts one round for [intent] and registers it before it can run. */
    private fun launch(intent: Pending) {
        val placeholder = Round()
        synchronized(lock) {
            if (!foreground) return
            round = placeholder
            kind = kindOf(intent)
        }
        val job = scheduler.launch(start = CoroutineStart.LAZY) { run(intent, placeholder) }
        placeholder.job = job
        job.start()
    }

    /** Bounded wait for a cancelled round; never throws and never blocks the caller for long. */
    private fun joinBounded(job: Job?) {
        if (job == null || job.isCompleted) return
        try {
            runBlocking { withTimeoutOrNull(cancelJoinBudgetMs) { job.join() } }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // A join must never be able to break the caller's hand-off.
        }
    }

    /** Consumes the queued intent, if any. */
    private fun takePending(): Pending? = synchronized(lock) { pending.also { pending = null } }

    private fun kindOf(intent: Pending): RoundKind = when (intent) {
        Pending.SEARCH_KEEP -> RoundKind.SEARCH_KEEP
        Pending.SEARCH -> RoundKind.SEARCH
        Pending.BROWSE -> RoundKind.BROWSE
    }

    /** One entry into the loop: run the round, then keep the loop alive until it must end. */
    private suspend fun run(intent: Pending, self: Round) {
        try {
            runRound(intent)
            loop()
        } finally {
            synchronized(lock) {
                if (round?.id == self.id) round = null
            }
        }
    }

    /**
     * The owner loop.
     *
     * While disconnected it waits [retryDelayMs] and searches again (a PC that comes up later is
     * found without restarting the app). While connected it probes the current target once per
     * [healthCheckIntervalMs] and leaves everything else alone; only a FAILED probe drops the
     * target and starts one recovery search.
     */
    private suspend fun loop() {
        while (foreground) {
            if (isConnected()) {
                // Between probes the Watch is simply connected: no round, no "connecting".
                synchronized(lock) { kind = RoundKind.IDLE_CONNECTED }
                if (!waitInterval(healthCheckIntervalMs)) return
                if (!foreground) return
                val queued = takePending()
                if (queued != null) {
                    runRound(queued)
                    continue
                }
                val stillUp = withContext(NonCancellable) { probeCurrentTarget() }
                if (!foreground) return
                if (stillUp) continue
                // A failed probe enters ONE recovery round (re-probe, then browse).
                runRound(Pending.SEARCH)
            } else {
                if (!waitInterval(retryDelayMs)) return
                runRound(Pending.SEARCH)
            }
        }
    }

    /**
     * Runs one round to completion INSIDE the loop, so exactly one coroutine is ever responsible
     * for scheduling and two loops cannot coexist.
     *
     * [cancelTransport] / [cancelForRefresh] are synchronous and run BEFORE the first suspension
     * point, which is what keeps them delivered when a hand-off cancels this round mid-flight.
     */
    private suspend fun runRound(intent: Pending) {
        synchronized(lock) {
            if (!foreground) return
            kind = kindOf(intent)
        }
        onStarted()
        try {
            when (intent) {
                // Revalidate what is in use. The target is NOT dropped first: the round only
                // reports whether it still answers, so a healthy connection and its recording
                // gate survive the round untouched.
                Pending.SEARCH_KEEP -> resolveNow(false)
                // A full resolution starts from scratch, so any previous usable state is dropped.
                Pending.SEARCH -> {
                    cancelTransport()
                    resolveNow(true)
                }
                Pending.BROWSE -> {
                    cancelForRefresh()
                    browseForPicker()
                }
            }
        } finally {
            onFinished()
        }
    }

    /** @return false when scheduling must end (background/teardown). */
    private suspend fun waitInterval(millis: Long): Boolean {
        val until = nowMs() + millis
        while (foreground && pending == null) {
            val left = until - nowMs()
            if (left <= 0L) break
            delay(left)
        }
        return foreground
    }

    companion object {
        /**
         * 1C-D-04@R7 §2A: after a failed round the Watch waits 5 s and searches again, so a PC
         * started later is picked up without restarting the app.
         */
        const val DefaultRetryDelayMs: Long = 5_000L

        /** 1C-D-04@R7 §2A: idle revalidation cadence for the computer in use. */
        const val DefaultHealthCheckIntervalMs: Long = 5_000L

        /** How long a hand-off waits for the cancelled round before continuing anyway. */
        const val DefaultCancelJoinBudgetMs: Long = 1_500L
    }
}
