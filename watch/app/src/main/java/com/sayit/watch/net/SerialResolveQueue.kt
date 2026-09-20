package com.sayit.watch.net

/**
 * Delivery 1C 1C-D-04@R2 — the bounded, single-flight resolve queue.
 *
 * `NsdManager.resolveService` is effectively **single-flight**: issuing a second
 * resolve while one is outstanding fails with `FAILURE_ALREADY_ACTIVE`. The
 * previous implementation called it directly from every `onServiceFound`, so a
 * burst of found callbacks lost every instance after the first — silently, with a
 * warning line and no retry. This queue is the fix and its exact semantics:
 *
 * 1. **At most one outstanding request.** [pop] hands out an item and marks its key
 *    in-flight; nothing else is handed out until [complete] acknowledges it.
 * 2. **At most one pending copy per key.** A repeated `onServiceFound` for an
 *    instance that is already queued or in flight is reported as a duplicate, never
 *    queued twice.
 * 3. **Bounded memory.** The queue holds at most [capacity] items. Overflow evicts
 *    the OLDEST pending item and reports it, because in an 8 s window the freshest
 *    instances are the ones that can still be probed in time.
 * 4. **Bounded retries.** [needsRetry] allows at most [maxRetries] re-issues for a
 *    transient `FAILURE_ALREADY_ACTIVE`, so a busy framework cannot spin forever.
 *
 * Everything here is plain Kotlin (no Android imports), so all four rules are
 * JVM-unit-tested with a `String` key instead of an `NsdServiceInfo`.
 */
class SerialResolveQueue<T>(
    /** Hard cap on items waiting to be resolved. */
    val capacity: Int = DEFAULT_CAPACITY,
    /** Bound on transient `FAILURE_ALREADY_ACTIVE` re-issues per item. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** Identity of an item, used for the "already queued/in flight" rule. */
    private val keyOf: (T) -> String = { it.toString() },
) {

    companion object {
        /** Default hard cap on queued instances inside one browse window. */
        const val DEFAULT_CAPACITY = 8

        /** Default bound on transient re-issues per instance. */
        const val DEFAULT_MAX_RETRIES = 3
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, T>()
    private val inFlight = LinkedHashSet<String>()

    /** Number of items still waiting (excludes the in-flight one). */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** True while an item has been handed out and not yet acknowledged. */
    val hasInFlight: Boolean get() = synchronized(lock) { inFlight.isNotEmpty() }

    /** How many items were ever dropped because the queue was full. */
    @Volatile
    var droppedByCapacity: Int = 0
        private set

    /** How many duplicate found-callbacks were collapsed. */
    @Volatile
    var droppedAsDuplicate: Int = 0
        private set

    /**
     * @return true when the item was accepted, false when it was a duplicate of one
     *   already pending or in flight (which is what a repeated PTR delivery is).
     */
    fun offer(item: T): Boolean {
        val key = keyOf(item)
        synchronized(lock) {
            if (inFlight.contains(key) || pending.containsKey(key)) {
                droppedAsDuplicate++
                return false
            }
            pending[key] = item
            while (pending.size > capacity) {
                val oldest = pending.keys.first()
                pending.remove(oldest)
                droppedByCapacity++
            }
            return true
        }
    }

    /**
     * @return the next item to resolve, or null when nothing is pending OR a request
     *   is already outstanding. The second case is the whole point: the framework
     *   cannot accept a second concurrent resolve.
     */
    fun poll(): T? = synchronized(lock) {
        if (inFlight.isNotEmpty()) return null
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        iterator.remove()
        inFlight.add(entry.key)
        entry.value
    }

    /**
     * Acknowledges the item handed out by [poll] and reports whether a transient
     * `FAILURE_ALREADY_ACTIVE` may be re-issued. It does NOT re-queue: the caller
     * decides whether the item deserves another [offer], so the ordering and the
     * retry budget stay visible in one place.
     *
     * @param retries how many times this item has already been re-issued.
     * @return true when the caller should issue the resolve again.
     */
    fun complete(item: T, transientFailure: Boolean, retries: Int): Boolean {
        val key = keyOf(item)
        synchronized(lock) {
            inFlight.remove(key)
        }
        return transientFailure && retries < maxRetries
    }

    /** Forgets everything: a new browse session never inherits the previous one. */
    fun clear() = synchronized(lock) {
        pending.clear()
        inFlight.clear()
    }
}
