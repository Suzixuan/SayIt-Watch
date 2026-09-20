package com.sayit.watch.net

/**
 * Delivery 1C 1C-D-04@R3 蹇呬慨 2 鈥?the browse-scoped resolve worker.
 *
 * R2 started one single-thread executor when the browser object was CONSTRUCTED and
 * never shut it down: `resolveLoop()` re-checked the queue every 25 ms forever, so
 * leaving Ready or destroying the ViewModel left a thread waking ~40x/second, and
 * every newly constructed browser left another one.
 *
 * This class binds the worker to the browse session instead:
 *
 * - [start] opens a session and spawns EXACTLY ONE worker thread;
 * - the thread BLOCKS on a condition while the queue is empty, so an idle session
 *   costs no wakeups at all (no empty polling);
 * - [stop] closes the session, wakes the thread, and **joins** it, so when `stop()`
 *   returns the thread is provably gone 鈥?even if it was parked in a platform
 *   callback wait;
 * - [start] after [stop] spawns a fresh thread, so the browser stays reusable
 *   without ever accumulating threads.
 *
 * Plain Kotlin + `java.util.concurrent`, so the whole lifecycle is JVM-tested with a
 * counter instead of an `NsdManager`.
 */
class BrowseWorker<T>(
    /** Thread name, so a leaked worker is visible in a thread dump. */
    private val threadName: String = "sayit-nsd-resolve",
    /** Maximum time [stop] waits for the worker to leave its loop. */
    private val joinTimeoutMs: Long = 2_000L,
    /** Hard cap on items waiting to be processed. */
    private val capacity: Int = 8,
    /** Identity of an item, used for the "already queued" rule. */
    private val keyOf: (T) -> String = { it.toString() },
    /** Processes one item; must return when the platform callback arrives. */
    private val process: (T) -> Unit,
) {

    enum class State {
        /** No session and no worker thread. */
        CLOSED,
        /** A session is open and the worker is draining or waiting for work. */
        RUNNING,
        /**
         * 1C-D-04@R4: the session is closed but its thread had not exited inside the
         * bounded join. No new session may start until it does.
         */
        CLOSING,
    }

    /**
     * Handed out by [start]; a result produced for a session that has since closed is
     * dropped instead of publishing into a dead browse.
     */
    class Session internal constructor() {
        @Volatile
        internal var open: Boolean = true
    }

    private val lock = Object()
    private var queue: ArrayDeque<T> = ArrayDeque()
    private val queuedKeys = LinkedHashSet<String>()
    private var state: State = State.CLOSED

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var session: Session? = null

    /** Items actually handed to [process] since construction. */
    @Volatile
    var processedCount: Int = 0
        private set

    /** Items dropped because their session closed or they were duplicates. */
    @Volatile
    var discardedCount: Int = 0
        private set

    /** Items whose process step threw; the worker itself survives them. */
    @Volatile
    var failedCount: Int = 0
        private set

    /** True while a worker thread exists — including one that has not exited yet. */
    val isRunning: Boolean get() = thread?.isAlive == true

    /** The current worker state. */
    val stateNow: State get() = synchronized(lock) { state }

    /** True when no worker thread is alive. */
    fun isStopped(): Boolean = !isRunning

    /**
     * 1C-D-04@R4 必修 2: how many times [stop]'s bounded join ran out before the
     * worker thread had exited. Non-zero means the "stop() returned ⇒ thread is gone"
     * claim does NOT hold for that session, and [isRunning] keeps reporting the truth
     * because the handle is retained.
     */
    @Volatile
    var joinTimeouts: Int = 0
        private set

    /** True while a previous session's thread is still terminating. */
    val hasLingeringThread: Boolean get() = isRunning

    /** True when a new browse session may be opened. */
    val canStart: Boolean get() = synchronized(lock) { thread?.isAlive != true }

    /**
     * Opens the session and ensures exactly one worker thread is running.
     *
     * 1C-D-04@R4 必修 2: a previous session's thread that has NOT exited yet blocks the
     * new session. Starting a second thread while the first one is still alive is the
     * accumulation the contract forbids, so the caller must wait for the old thread —
     * [isRunning] tells the truth in the meantime.
     *
     * @return the session token, or null when a previous worker has not exited yet.
     */
    fun start(): Session? {
        synchronized(lock) {
            val existing = session
            if (thread?.isAlive == true) {
                // Never two live workers: reuse an OPEN session, otherwise refuse.
                if (existing != null && existing.open) return existing
                state = State.CLOSING
                return null
            }
            val fresh = Session()
            session = fresh
            state = State.RUNNING
            queue = ArrayDeque()
            queuedKeys.clear()
            thread = null
            val worker = Thread({ runLoop(fresh) }, threadName).apply { isDaemon = true }
            thread = worker
            worker.start()
            return fresh
        }
    }

    /**
     * Closes the session and waits a BOUNDED time for the worker thread to exit.
     *
     * 1C-D-04@R4 必修 2: R3 cleared the thread handle BEFORE the bounded join, so a
     * join that timed out left `isStopped()` (and every leak test built on it)
     * reporting a lie while the thread was still alive and a later `start()` could add
     * a second one.
     *
     * Now the handle is retained until the thread is PROVABLY gone. When the bounded
     * join times out, [isRunning] stays true, [joinTimeouts] is counted, and [start]
     * refuses to open a new session. Because an unresponsive platform call cannot be
     * force-killed from here, **`stop()` returning does NOT by itself prove the thread
     * is dead** — callers must consult [isRunning] / [canStart].
     *
     * @return true when the worker thread is confirmed gone.
     */
    fun stop(): Boolean {
        val toJoin: Thread?
        synchronized(lock) {
            session?.open = false
            session = null
            state = State.CLOSING
            queue.clear()
            queuedKeys.clear()
            toJoin = thread
            lock.notifyAll()
        }
        toJoin?.interrupt()
        if (toJoin != null && toJoin !== Thread.currentThread()) {
            try {
                toJoin.join(joinTimeoutMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val exited = toJoin == null || !toJoin.isAlive
        synchronized(lock) {
            if (toJoin == null || !toJoin.isAlive) {
                // The runLoop finally-block normally clears this; doing it here as well
                // covers the "process ignored the interrupt and finished late" path.
                if (thread === toJoin) thread = null
                session = null
                state = State.CLOSED
            } else {
                joinTimeouts++
                state = State.CLOSING
                // `thread` deliberately keeps the live handle: no false "stopped".
            }
        }
        return exited
    }

    /**
     * Queues one item.
     *
     * @return false when no session is open (a late platform callback after `stop()`
     *   must be dropped) or when the same item is already waiting. A re-offer of the
     *   item currently being processed is accepted, which is what a bounded
     *   transient-failure retry needs. The oldest pending item is dropped when
     *   [capacity] is exceeded, because in an 8 s window the freshest instances are
     *   the ones that can still be probed.
     */
    fun offer(item: T): Boolean {
        val key = keyOf(item)
        synchronized(lock) {
            if (state != State.RUNNING || session == null) {
                discardedCount++
                return false
            }
            if (queuedKeys.contains(key)) {
                discardedCount++
                return false
            }
            queue.addLast(item)
            queuedKeys.add(key)
            while (queue.size > capacity) {
                val evicted = queue.removeFirst()
                queuedKeys.remove(keyOf(evicted))
                discardedCount++
            }
            lock.notifyAll()
            return true
        }
    }

    /** Number of items currently waiting. */
    fun pendingCount(): Int = synchronized(lock) { queue.size }

    private fun runLoop(ownSession: Session) {
        try {
            while (ownSession.open && session === ownSession) {
                val next = pollOrPark(ownSession) ?: return
                if (!ownSession.open || session !== ownSession) return
                // The key is already free: `pollOrPark` removed it, and the process
                // step may legitimately re-offer the same item as a retry.
                try {
                    process(next)
                } catch (failure: Throwable) {
                    // A platform call that throws must not kill the worker for the rest
                    // of the browse window; the item is simply counted as attempted.
                    failedCount++
                }
                processedCount++
            }
        } catch (interrupted: InterruptedException) {
            // stop() interrupts the worker; leaving the loop is the intended exit.
        } finally {
            synchronized(lock) {
                if (session === ownSession) {
                    session = null
                    state = State.CLOSED
                }
                queuedKeys.clear()
            }
        }
    }

    /**
     * @return the next item, or null when this session ended. Blocks while the queue
     *   is empty, so an idle session performs no polling at all.
     */
    private fun pollOrPark(ownSession: Session): T? {
        synchronized(lock) {
            while (true) {
                if (!ownSession.open || session !== ownSession || state != State.RUNNING) return null
                val entry = queue.removeFirstOrNull()
                if (entry != null) {
                    // 1C-D-04@R3: the key must be released as part of the hand-off,
                    // BEFORE returning. Leaving it in `queuedKeys` made every later
                    // re-offer of the same instance look like a duplicate, which is
                    // exactly what a bounded transient retry needs to do.
                    queuedKeys.remove(keyOf(entry))
                    return entry
                }
                lock.wait()
            }
        }
        return null
    }
}
