package com.sayit.watch.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wear OS platform implementation of [ServiceDiscovery] using the official
 * Android `NsdManager` (no third-party mDNS library, per the Delivery 1C
 * contract).
 *
 * Responsibilities are deliberately thin: start/stop browsing, resolve each
 * found instance, and translate `NsdServiceInfo` into the pure
 * [ResolvedService]. Every selection, timeout, and authentication decision lives
 * in [DiscoveryCoordinator] / [DiscoveryPolicy], which are JVM-testable.
 *
 * Repair 1 必修 1: `NsdServiceInfo.serviceName` is the *instance* name (`SayIt`)
 * and `serviceType` is the type (`_sayit-watch._tcp.`). Both are carried through
 * separately; the policy checks the type only.
 *
 * Repair 1 必修 1 (concurrency): several `resolveService` calls can be in flight
 * at once. Each resolve callback appends its own result and publishes the full
 * snapshot, so a second PC is never dropped; a generation guard drops callbacks
 * that belong to a browse session which has already been stopped.
 *
 * 1C-D-04@R2 (discovery regression) — three evidence-driven changes:
 *
 * 1. **Resolve serialization.** The framework's deprecated `resolveService` is
 *    effectively single-flight: issuing another resolve while one is outstanding
 *    fails with `FAILURE_ALREADY_ACTIVE`. The previous code called it directly
 *    from every `onServiceFound`, so a burst of found callbacks lost all but the
 *    first instance with only a warning line and no retry. Resolves now go
 *    through a bounded single-worker queue: exactly one outstanding at a time,
 *    `FAILURE_ALREADY_ACTIVE` retried a bounded number of times, and the queue
 *    itself capped so an unstable network cannot spawn unbounded work.
 * 2. **No silent drops.** Every stage of the browse/resolve chain records a fixed
 *    [DiscoveryDiagnostic] category. Nothing carries an address, host name,
 *    exception message, HTTP request or audio.
 * 3. **Tolerant service type.** `NsdManager` does not normalize the `.local`
 *    domain or the case, so the filter uses [DiscoveryPolicy.isOurServiceType].
 */
class AndroidNsdDiscovery(
    private val context: Context,
    /** Fixed-category stage log; category-only, so it can never leak an address. */
    private val diagnostics: DiscoveryDiagnostics = CategoryLog(sink = { Log.i(TAG, it) }),
) : ServiceDiscovery {

    /**
     * 1C-D-04@R2/R3: the bounded, single-flight resolve worker.
     *
     * R3 必修 2 moved the thread lifecycle here: the worker is created by [start] and
     * destroyed by [stop], blocks (no polling) while idle, and reuses
     * [SerialResolveQueue]'s rules — one outstanding request, one pending copy per
     * instance, bounded capacity, bounded transient retries.
     */
    private val resolveWorker = BrowseWorker<ResolveAttempt>(
        threadName = "sayit-nsd-resolve",
        capacity = SerialResolveQueue.DEFAULT_CAPACITY,
        keyOf = { it.key },
        process = { request -> resolveOne(request) },
    )

    /** Test seam: the browse-scoped worker must not outlive the browse session. */
    val isResolveWorkerAlive: Boolean get() = resolveWorker.isRunning

    /** Test seam: bounded-join timeouts observed by the worker. */
    val resolveWorkerJoinTimeouts: Int get() = resolveWorker.joinTimeouts

    /** Test seam: the worker's lifecycle state. */
    val resolveWorkerState: BrowseWorker.State get() = resolveWorker.stateNow

    /** Test seam: what the worker actually processed in this session. */
    val resolvedAttempts: Int get() = resolveWorker.processedCount

    private var nsdManager: NsdManager? = null

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var observer: ((List<ResolvedService>) -> Unit)? = null

    /** Bumped on every start/stop so late resolve callbacks become no-ops. */
    @Volatile
    private var generation: Int = 0

    /** Cached resolved instances; the observer always receives the full list. */
    private val resolved = mutableListOf<ResolvedService>()

    /**
     * The browse session every queued resolve belongs to. A resolve whose session is
     * no longer current is abandoned immediately.
     */
    @Volatile
    private var queueSession: Int = -1

    override fun start(observer: (List<ResolvedService>) -> Unit) {
        if (discoveryListener != null) {
            // A browse session is already open: this start is a real no-op, and the
            // category makes that distinguishable from "browsing, nothing found".
            diagnostics.note(DiscoveryDiagnostic.BROWSE_START_FAILED)
            return
        }
        this.observer = observer
        val session = ++generation
        queueSession = session
        // 1C-D-04@R3/R4: the worker exists only for the duration of a browse, and a
        // previous session's thread that has not exited yet blocks a new one — the
        // contract forbids accumulating resolve threads. The browse still opens; only
        // resolving is refused until the old thread is gone.
        if (resolveWorker.start() == null) {
            diagnostics.note(DiscoveryDiagnostic.BROWSE_START_FAILED)
            Log.w(TAG, "previous resolve worker has not exited; not starting a second one")
        }
        synchronized(resolved) { resolved.clear() }
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            diagnostics.note(DiscoveryDiagnostic.BROWSE_START_FAILED)
            Log.w(TAG, "NsdManager unavailable; automatic discovery cannot start")
            return
        }
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Only a SayIt service type is resolved; anything else on the
                // network is ignored — but never silently again.
                val type = service.serviceType
                when {
                    type == null -> diagnostics.note(DiscoveryDiagnostic.FOUND_NULL_TYPE)
                    DiscoveryPolicy.isOurServiceType(type) ->
                        diagnostics.note(DiscoveryDiagnostic.FOUND_ACCEPTED_TYPE)
                    else -> {
                        diagnostics.note(DiscoveryDiagnostic.FOUND_OTHER_TYPE)
                        return
                    }
                }
                enqueueResolve(manager, service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Browsing stays open for the rest of the window: a lost
                // instance must not abort a run that may still authenticate.
                Log.i(TAG, "service lost")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostics.note(DiscoveryDiagnostic.BROWSE_START_FAILED)
                Log.w(TAG, "start discovery failed (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed (error $errorCode)")
            }
        }

        discoveryListener = listener
        try {
            // `discoverServices(type, PROTOCOL_DNS_SD, listener)` is the API level 34
            // form; the modern `DiscoveryRequest` overload is API 35 and the
            // deprecated 3-arg form is gone from current platform stubs.
            // PROTOCOL_DNS_SD == 1 (the platform's DNS-SD protocol id).
            manager.discoverServices(SAYIT_SERVICE_TYPE_ANDROID, NSD_PROTOCOL_DNS_SD, listener)
            diagnostics.note(DiscoveryDiagnostic.BROWSE_STARTED)
        } catch (e: Exception) {
            // The exception message is deliberately NOT logged or recorded: it can
            // embed platform-internal detail. The category carries what is needed.
            diagnostics.note(DiscoveryDiagnostic.BROWSE_START_FAILED)
            Log.w(TAG, "discoverServices threw")
            discoveryListener = null
        }
    }

    override fun stop() {
        val manager = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        generation++ // invalidate any resolve still in flight
        queueSession = -1
        observer = null
        // 1C-D-04@R3/R4: close the browse session and wait a BOUNDED time for the
        // worker to exit. A platform callback that arrives afterwards finds a closed
        // session and is discarded. 1C-D-04@R4 必修 2: this call does not claim the
        // thread is dead — when the bounded join times out, `isResolveWorkerAlive`
        // keeps reporting true and the next `start()` refuses a second thread.
        val workerGone = resolveWorker.stop()
        if (!workerGone) {
            Log.w(TAG, "resolve worker has not exited within the bounded join; keeping its handle")
        }
        synchronized(resolved) { resolved.clear() }
        if (manager != null && listener != null) {
            try {
                manager.stopServiceDiscovery(listener)
                diagnostics.note(DiscoveryDiagnostic.BROWSE_STOPPED)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery threw")
            }
        }
    }

    /**
     * Folds one resolve completion into the snapshot. Public so a JVM test can
     * drive concurrent completions without an emulator.
     */
    fun addResolved(serviceInfo: NsdServiceInfo) = addResolved(generation, serviceInfo)

    private fun addResolved(session: Int, serviceInfo: NsdServiceInfo) {
        if (session != generation) {
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_DROPPED_STALE)
            return // a browse session that already stopped
        }
        val currentObserver = observer ?: run {
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_DROPPED_STALE)
            return
        }
        val host = serviceInfo.host?.hostAddress
        val service = ResolvedService(
            instanceName = serviceInfo.serviceName.orEmpty(),
            serviceType = serviceInfo.serviceType.orEmpty(),
            host = host,
            port = serviceInfo.port,
            protocol = attribute(serviceInfo, TXT_KEY_PROTOCOL),
        )
        // Fixed-category classification makes "the platform resolved an instance
        // but our rules refused it" distinguishable from "nothing resolved at all".
        val rejection = DiscoveryPolicy.classify(service)
        if (rejection == null) {
            diagnostics.note(DiscoveryDiagnostic.CANDIDATE_ACCEPTED)
        } else {
            diagnostics.note(rejection.category)
        }
        val snapshot = synchronized(resolved) {
            val folded = DiscoveryPolicy.foldResolved(resolved, service)
            resolved.clear()
            resolved.addAll(folded)
            resolved.toList()
        }
        currentObserver(snapshot)
    }

    /**
     * Queues one resolve. The worker's [BrowseWorker.offer] enforces "bounded, one
     * pending copy per instance"; a duplicate or an overflow item is reported as the
     * same fixed stage category, because both mean "this found-callback produced no
     * new resolve".
     */
    private fun enqueueResolve(manager: NsdManager, service: NsdServiceInfo) {
        if (resolveWorker.offer(ResolveAttempt(service))) {
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_QUEUED)
        } else {
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_DROPPED_ALREADY_PENDING)
        }
    }

    /**
     * One queued instance: one outstanding resolve, bounded transient retries.
     *
     * `FAILURE_ALREADY_ACTIVE` means the framework still considers the previous
     * request in flight; the item is waited out and retried a bounded number of times
     * instead of being lost. The wait itself is caller-bounded: the coordinator's 8 s
     * window stops the run, and a session that closed abandons the wait immediately so
     * the worker can exit.
     */
    private fun resolveOne(request: ResolveAttempt) {
        val session = queueSession
        val manager = nsdManager
        if (session < 0 || manager == null) {
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_FAILED)
            return
        }
        request.ownerSession = session
        diagnostics.note(DiscoveryDiagnostic.RESOLVE_STARTED)
        var retries = 0
        while (queueSession == session) {
            // A FRESH call per try: a retry must wait for its own callback rather than
            // re-observe the previous request's outcome.
            val call = ResolveCall(session, CountDownLatch(1))
            if (!issueResolve(manager, call, request.service)) {
                diagnostics.note(DiscoveryDiagnostic.RESOLVE_FAILED)
                return
            }
            // Wait for THIS callback. A browse session that ended abandons the wait,
            // so a platform callback that never arrives cannot block the worker.
            while (queueSession == session) {
                if (call.latch.await(RESOLVE_POLL_MS, TimeUnit.MILLISECONDS)) break
            }
            if (queueSession != session) return
            val code = call.result
            if (code == RESOLVE_OK) return
            if (code == FAILURE_ALREADY_ACTIVE && retries < SerialResolveQueue.DEFAULT_MAX_RETRIES) {
                retries++
                diagnostics.note(DiscoveryDiagnostic.RESOLVE_RETRIED_BUSY)
                try {
                    Thread.sleep(RESOLVE_RETRY_DELAY_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            // A transient failure that ran out of retries and a hard platform failure
            // share the same fixed stage category; RESOLVE_RETRIED_BUSY above
            // separates the two histories.
            diagnostics.note(DiscoveryDiagnostic.RESOLVE_FAILED)
            return
        }
    }

    /** @return false when the platform call threw before any callback. */
    private fun issueResolve(manager: NsdManager, call: ResolveCall, service: NsdServiceInfo): Boolean =
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(service, resolveListener(call, service))
            true
        } catch (e: Exception) {
            // No raw message: the category is the evidence.
            false
        }

    private fun resolveListener(call: ResolveCall, service: NsdServiceInfo): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                call.signal(errorCode)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                diagnostics.note(DiscoveryDiagnostic.RESOLVE_SUCCEEDED)
                addResolved(call.ownerSession, serviceInfo)
                call.signal(RESOLVE_OK)
            }
        }

    private fun attribute(serviceInfo: NsdServiceInfo, key: String): String? {
        val raw = serviceInfo.attributes?.get(key) ?: return null
        return try {
            raw.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** One queued resolve: the service to resolve and the session that queued it. */
    private class ResolveAttempt(
        val service: NsdServiceInfo,
        @Volatile var ownerSession: Int = -1,
    ) {
        /** Identity used for the "already queued / already being resolved" rule. */
        val key: String = service.serviceName.orEmpty() + "|" + service.serviceType.orEmpty()
    }

    /** One resolve request: it waits for the callback of exactly that request. */
    private class ResolveCall(
        val ownerSession: Int,
        val latch: CountDownLatch,
    ) {
        @Volatile
        var result: Int = RESOLVE_IN_FLIGHT

        /** Records the FIRST outcome; later callbacks of a stale request are ignored. */
        fun signal(code: Int) {
            synchronized(this) {
                if (result != RESOLVE_IN_FLIGHT) return
                result = code
            }
            latch.countDown()
        }
    }

    private companion object {
        const val TAG = "SayItNsd"

        /** `NsdManager.PROTOCOL_DNS_SD` mirrored as a constant (value 1). */
        const val NSD_PROTOCOL_DNS_SD = 1

        /** `NsdManager.FAILURE_ALREADY_ACTIVE` (mirrored; value 3). */
        const val FAILURE_ALREADY_ACTIVE = 3

        /** Sentinel meaning "the resolve completed successfully". */
        const val RESOLVE_OK = -1

        /** No outcome recorded yet. */
        const val RESOLVE_IN_FLIGHT = 0

        const val RESOLVE_RETRY_DELAY_MS = 60L

        /** How often the worker re-checks a pending resolve against its session. */
        const val RESOLVE_POLL_MS = 25L
    }
}
