package com.sayit.watch.net

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1C-D-04@R9 — the single, NON-BLOCKING, serialized owner of "which connection task is running".
 *
 * R8 kept the scheduling in the caller's stack: every UI entry point (`requestSwitch`,
 * `dismissSwitchPicker`, `onBackground`, the recording entry) called `joinBounded()`, which ran
 * `runBlocking { withTimeoutOrNull(1_500) { job.join() } }` on the **UI thread**, and — after the
 * timeout — started the replacement anyway. So the old round could still be alive while the new one
 * opened a second listener, and the UI could freeze for 1.5 s. R9 replaces that with one command
 * loop:
 *
 * | who | what they may do |
 * |---|---|
 * | any caller (UI thread included) | `commands.trySend(...)` — returns immediately, never blocks |
 * | the command loop ([commandLoop]) | the ONLY writer of `current`, `pending` and `generation` |
 * | a round coroutine | run its round and, by IDENTITY, clear its own slot when it finishes |
 *
 * Serialization and the cancel hand-off:
 *
 * - a new intent first **cancels and JOINS** the round in flight — `cancelAndJoin()`, with no
 *   timeout — and only then starts the replacement. Two browse/NSD listeners can therefore never
 *   overlap, and a slow platform stop delays only this loop, never the UI;
 * - a round that is superseded or cancelled can no longer touch anything: every result application
 *   passes through the identity/lifecycle gate [isCurrent], and the rounds themselves no longer run
 *   under `NonCancellable`, so a cancelled probe unwinds instead of writing a stale verdict;
 * - a late `finally` from an old round cannot clear a newer round's slot, because it only clears
 *   its own identity.
 *
 * The loop is one serialized state machine; the stable cadence is a `Tick` command it posts to
 * itself when a round finishes, so "wait 5 s and probe again" can always be interrupted by a real
 * user intent instead of running on its own thread.
 *
 * The owner schedules; it never decides policy. Authentication, target selection and the frozen
 * 3 s/8 s budgets stay in [DiscoveryCoordinator] / [ResolverBridge] — the injected callbacks are
 * the only things this class knows about, and this file stays pure Kotlin with no Android
 * dependency.
 *
 * Rounds run on their OWN scheduler instead of the caller's scope: `viewModelScope` is
 * `Dispatchers.Main`, where the multi-second waits would hold the UI thread.
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
     *
     * It reports the answer and does nothing else: the OWNER decides whether that answer may still
     * be applied (1C-D-04@R9 P0-A), so a probe that returns after its round was superseded or after
     * the app went to the background is simply dropped.
     *
     * @return true when it still answered.
     */
    private val probeCurrentTarget: suspend () -> Boolean,
    /**
     * Applies a FAILED health probe — clears the target, the UI and the discovery verdict.
     *
     * It is a separate callback because it is the one probe consequence that must be generation
     * gated: the owner calls it only while this exact round is still the current, foreground one.
     */
    private val onProbeFailed: () -> Unit = {},
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

    /**
     * Everything the owner can be asked to do. Callers only ever `trySend` one of these; nothing
     * about scheduling happens on the calling thread.
     */
    private sealed class Command {
        /** The app came back to the foreground: revalidate immediately. */
        object Foreground : Command()

        /** The app left the foreground: cancel the round and stop scheduling entirely. */
        object Background : Command()

        /** Cancel the round in flight and stop; the caller decides what comes next. */
        object Stop : Command()

        /** A user/UI intent, in arrival order. */
        data class Intent(val intent: Pending) : Command()

        /**
         * The stable cadence elapsed for [generation].
         *
         * Carrying the generation is what makes a stale tick harmless: a tick that was already in
         * flight when a newer round replaced this one is discarded instead of stacking work.
         */
        data class Tick(val generation: Long) : Command()
    }

    /**
     * One round of work. [generation] identifies it; a round may only clear its OWN slot.
     * [intent] is null for the idle health probe round, which has no user intent behind it.
     */
    private class Round(val generation: Long, val intent: Pending?) {
        @Volatile
        var job: Job? = null

        /**
         * Set the moment a caller asks for this round to stop.
         *
         * It is what makes "the task is over" immediately readable by the caller that asked for the
         * stop (the recording entry, the Activity), while the actual join — the part that must not
         * block a UI thread — is still performed by [commandLoop]. A superseded round is never
         * joined twice: the command loop reads the same slot.
         */
        @Volatile
        var superseded: Boolean = false
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Own scheduling scope: never the caller's (main or virtual-time) dispatcher. */
    private val scheduler = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("sayit-connection-owner"),
    )

    @Volatile
    private var foreground: Boolean = false

    /** The intent queued for the next round, or null. */
    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var kind: RoundKind = RoundKind.IDLE_CONNECTED

    /** The round in flight, or null. Written by [commandLoop] and cleared by IDENTITY. */
    @Volatile
    private var current: Round? = null

    /** The pending stable-cadence tick, cancelled as soon as any real command arrives. */
    @Volatile
    private var tickJob: Job? = null

    /**
     * Identity of the newest round. Bumped by [commandLoop] on every launch, so a `Tick` and a
     * `finally` block from an older round are both recognisable as stale.
     */
    @Volatile
    private var generation: Long = 0L

    /**
     * The single command loop. This is the only place that starts rounds, and it is what makes the
     * hand-off serialized: it cancels and JOINS the previous round before launching the next one.
     */
    private val loopJob: Job = scheduler.launch { commandLoop() }

    /** True while a round is in flight and not already asked to stop. */
    val isTaskRunning: Boolean
        get() = current?.let { !it.superseded && it.job?.isActive == true } == true

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
            resumeForeground()
        } else {
            pauseForeground()
        }
    }

    /**
     * Foreground revalidation / explicit re-search entry point.
     *
     * It queues ONE round that revalidates the computer in use when there is one (no browse, no
     * "clearing" of the connection) and searches when there is none.
     */
    fun revalidateNow() {
        foreground = true
        commands.trySend(Command.Foreground)
    }

    /** Suspends everything: cancels the round, stops the transport and ends probing. */
    fun pauseForeground() {
        // The lifecycle flag is readable immediately (the UI must stop describing a live
        // connection at once); the cancellation itself is serialized by the command loop, so this
        // call never blocks the Activity that is stopping.
        foreground = false
        pending = null
        kind = RoundKind.IDLE_CONNECTED
        commands.trySend(Command.Background)
    }

    /** Resumes after [pauseForeground]; no-op when already in the foreground. */
    fun resumeForeground() {
        if (foreground) return
        foreground = true
        commands.trySend(Command.Foreground)
    }

    /**
     * Queues [intent] as the next round. A later call always wins, so rapid taps collapse into the
     * newest intent instead of stacking work, and the queued round waits for the previous one to
     * be fully cancelled.
     */
    fun restartNow(intent: Pending) {
        if (!foreground) return
        commands.trySend(Command.Intent(intent))
    }

    /**
     * Expresses an intent, coalescing it into the round already running when that round is working
     * on exactly the same thing, so re-entering the Ready screen is not "cancel and redo".
     */
    fun requestRefresh(intent: Pending) {
        if (!foreground) return
        val running = current
        if (running != null && running.intent == intent && running.job?.isActive == true) return
        commands.trySend(Command.Intent(intent))
    }

    /**
     * Cancels the round in flight and stops scheduling. It does not touch the usable state: the
     * caller (Config entry, transfer start, picker dismissal) decides what happens to the target.
     *
     * The cancellation is delivered by the command loop, so this returns immediately; a caller that
     * needs the round to be over before it does something else must express that as a command
     * rather than block on this.
     */
    fun stop() {
        pending = null
        // Readable by the caller immediately ("the task is over"); the join itself is still
        // serialized by the command loop so no UI thread ever waits for it.
        current?.superseded = true
        commands.trySend(Command.Stop)
    }

    /** Teardown: no scheduling survives the ViewModel. */
    fun onDestroyed() {
        foreground = false
        pending = null
        current?.superseded = true
        commands.trySend(Command.Stop)
        commands.close()
    }

    // ── the command loop (the only writer of scheduling state) ───────────────

    private suspend fun commandLoop() {
        for (command in commands) {
            // Any real command invalidates the stable cadence; a Tick re-checks its own
            // generation anyway, so losing this race can never stack work.
            cancelTick()
            when (command) {
                is Command.Foreground -> {
                    foreground = true
                    startRound(Pending.SEARCH_KEEP)
                }

                is Command.Background -> {
                    foreground = false
                    pending = null
                    kind = RoundKind.IDLE_CONNECTED
                    cancelAndJoinCurrent()
                }

                is Command.Stop -> {
                    pending = null
                    cancelAndJoinCurrent()
                }

                is Command.Intent -> {
                    if (!foreground) continue
                    startRound(command.intent)
                }

                is Command.Tick -> {
                    if (!foreground) continue
                    if (command.generation != generation) continue
                    if (current != null) continue
                    val queued = pending
                    pending = null
                    when {
                        // A real intent always beats the cadence.
                        queued != null -> startRound(queued)
                        isConnected() -> startRound(null)
                        else -> startRound(Pending.SEARCH)
                    }
                }
            }
        }
    }

    /**
     * Cancels and JOINS the round in flight. The join is deliberately unbounded: a platform stop
     * that takes longer than any fixed budget still has to finish before a replacement may open a
     * second listener. This runs on the owner's own dispatcher, so the wait blocks nothing the user
     * can see — which is exactly the R8 defect this replaces.
     */
    private suspend fun cancelAndJoinCurrent() {
        val round = current
        current = null
        val job = round?.job ?: return
        job.cancel()
        job.join()
    }

    /** Cancels the round in flight, then launches the next one for [intent] (null = health probe). */
    private suspend fun startRound(intent: Pending?) {
        cancelAndJoinCurrent()
        if (!foreground) return
        launchRound(intent)
    }

    /**
     * Registers the round BEFORE it can run, so an intent that arrives immediately afterwards
     * always finds (and cancels) it.
     */
    private fun launchRound(intent: Pending?) {
        val round = Round(++generation, intent)
        current = round
        kind = if (intent == null) RoundKind.IDLE_CONNECTED else kindOf(intent)
        val job = scheduler.launch(start = CoroutineStart.LAZY) { runRound(round, intent) }
        round.job = job
        job.start()
    }

    private fun cancelTick() {
        tickJob?.cancel()
        tickJob = null
    }

    /**
     * Posts the stable cadence back to the command loop. A round that finishes while a newer round
     * already replaced it does not post at all (see [finishRound]).
     */
    private fun scheduleTick(generationOfRound: Long, delayMs: Long) {
        cancelTick()
        tickJob = scheduler.launch {
            if (delayMs > 0L) delay(delayMs)
            commands.trySend(Command.Tick(generationOfRound))
        }
    }

    // ── rounds ───────────────────────────────────────────────────────────────

    /**
     * The identity/lifecycle gate every result application passes through (1C-D-04@R9 P0-A).
     *
     * A blocking platform call can return long after its coroutine was cancelled; the coroutine
     * itself unwinds on the next suspension check, and this gate is the second line of defence: a
     * consequence may only be applied while this exact round is still the current, foreground one.
     */
    private fun isCurrent(round: Round): Boolean = foreground && current === round

    /** One round, always ending with its own identity-checked cleanup. */
    private suspend fun runRound(round: Round, intent: Pending?) {
        onStarted()
        try {
            if (intent == null) {
                runHealthRound(round)
            } else {
                runIntentRound(round, intent)
            }
        } finally {
            onFinished()
            finishRound(round)
        }
    }

    /**
     * The idle health round: ONE bounded authenticated probe of the computer in use, and nothing
     * else. It never browses, never searches and never drops the target while the probe succeeds.
     *
     * R9 removed the `withContext(NonCancellable)` that used to wrap this call. The probe is now
     * cancellable, and every consequence below is gated on this round still being current, so a
     * probe that comes back after `onBackground()` cannot clear the target or refresh the UI.
     */
    private suspend fun runHealthRound(round: Round) {
        if (!isCurrent(round)) return
        val stillUp = probeCurrentTarget()
        if (!isCurrent(round)) return
        if (stillUp) return
        // Only a FAILED probe may drop the usable state, and only while this round still owns it.
        onProbeFailed()
        if (!isCurrent(round)) return
        cancelTransport()
        if (!isCurrent(round)) return
        resolveNow(true)
    }

    /**
     * One intent round.
     *
     * [Pending.SEARCH_KEEP] revalidates what is in use without dropping it first; [Pending.SEARCH]
     * starts from scratch, so any previous usable state is dropped; [Pending.BROWSE] releases the
     * live browse without touching the target.
     *
     * [cancelTransport] / [cancelForRefresh] are synchronous and run BEFORE the first suspension
     * point, which is what keeps them delivered when a hand-off cancels this round mid-flight.
     */
    private suspend fun runIntentRound(round: Round, intent: Pending) {
        if (!isCurrent(round)) return
        when (intent) {
            Pending.SEARCH_KEEP -> resolveNow(false)
            Pending.SEARCH -> {
                cancelTransport()
                if (!isCurrent(round)) return
                resolveNow(true)
            }
            Pending.BROWSE -> {
                cancelForRefresh()
                if (!isCurrent(round)) return
                browseForPicker()
            }
        }
    }

    /**
     * Clears this round's own slot and re-arms the stable cadence.
     *
     * The identity check is the R8 lesson kept intact: a cancelled round that is still unwinding
     * must not clear the slot of the round that replaced it (which would leave the owner with no
     * round at all and silently drop the user's newest intent).
     */
    private fun finishRound(round: Round) {
        if (current !== round) return
        current = null
        if (!foreground) return
        if (isConnected()) kind = RoundKind.IDLE_CONNECTED
        val waitMs = when {
            pending != null -> 0L
            isConnected() -> healthCheckIntervalMs
            else -> retryDelayMs
        }
        scheduleTick(round.generation, waitMs)
    }

    private fun kindOf(intent: Pending): RoundKind = when (intent) {
        Pending.SEARCH_KEEP -> RoundKind.SEARCH_KEEP
        Pending.SEARCH -> RoundKind.SEARCH
        Pending.BROWSE -> RoundKind.BROWSE
    }

    companion object {
        /**
         * 1C-D-04@R7 §2A: after a failed round the Watch waits 5 s and searches again, so a PC
         * started later is picked up without restarting the app.
         */
        const val DefaultRetryDelayMs: Long = 5_000L

        /** 1C-D-04@R7 §2A: idle revalidation cadence for the computer in use. */
        const val DefaultHealthCheckIntervalMs: Long = 5_000L
    }
}
