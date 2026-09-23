package com.sayit.watch.net

import com.sayit.watch.settings.DestinationValidator

/**
 * Delivery 1C discovery vocabulary: the frozen protocol constants, the
 * authenticated-probe result parsing, and the decision about which resolved
 * mDNS service may be trusted.
 *
 * Everything here is pure Kotlin (no Android imports) so the whole selection
 * ruleset is JVM-unit-tested with fakes. The platform NSD callbacks live in
 * [AndroidNsdDiscovery] and only translate framework objects into [ResolvedService].
 */

/** Frozen service type. Android requires the platform form without the domain. */
const val SAYIT_SERVICE_TYPE_ANDROID: String = "_sayit-watch._tcp."
const val SAYIT_SERVICE_TYPE_FULL: String = "_sayit-watch._tcp.local."

/** Frozen discovery protocol version, mirrored by the Windows TXT record. */
const val SAYIT_DISCOVERY_PROTOCOL: Int = 1

/** Frozen authenticated probe path (TXT value and request path). */
const val SAYIT_DISCOVERY_PATH: String = "/api/watch/discovery"

/** Frozen service identifier the Windows probe must return. */
const val SAYIT_DISCOVERY_SERVICE_ID: String = "sayit-watch-debug-receiver"

/** TXT key carrying the protocol version. */
const val TXT_KEY_PROTOCOL: String = "protocol"

/** TXT key carrying the authenticated probe path. */
const val TXT_KEY_PATH: String = "path"

/**
 * Delivery 1C 1C-D-04@R2 — fixed-category stage diagnostics.
 *
 * The regression report could not be narrowed because the automatic chain was
 * completely unobservable: `onServiceFound`, every rejection and every probe
 * outcome were silent. These categories split the chain into its seven
 * distinguishable stages:
 *
 *   saved-probe → browse start / browse failure → found → resolve ok/failed →
 *   candidate rejected (fixed category) → authenticated probe outcome class →
 *   final fallback
 *
 * Each value is a FROZEN, address-free category string. It deliberately carries
 * no IP, no host name, no SSID, no token, no raw exception message and no HTTP
 * request/audio: only the stage and its outcome, so a log line can never leak a
 * network layout. The stage is the whole point, so nothing here is optional.
 */
object DiscoveryDiagnostic {
    // ── Saved (last known) address phase ────────────────────────────────────
    const val SAVED_PROBE_ACCEPTED = "saved-probe:accepted"
    const val SAVED_PROBE_REJECTED = "saved-probe:rejected"
    const val SAVED_PROBE_NONE = "saved-probe:none-stored"

    // ── Browse phase ────────────────────────────────────────────────────────
    const val BROWSE_STARTED = "browse:started"
    const val BROWSE_START_FAILED = "browse:start-failed"
    const val BROWSE_STOPPED = "browse:stopped"
    const val BROWSE_NO_CANDIDATE = "browse:no-candidate"

    // ── Per-instance callbacks ──────────────────────────────────────────────
    const val FOUND_ACCEPTED_TYPE = "found:type-accepted"
    const val FOUND_OTHER_TYPE = "found:other-type"
    const val FOUND_NULL_TYPE = "found:null-type"

    // ── Resolve callbacks ───────────────────────────────────────────────────
    const val RESOLVE_STARTED = "resolve:started"
    const val RESOLVE_QUEUED = "resolve:queued"
    const val RESOLVE_DROPPED_ALREADY_PENDING = "resolve:dropped-already-pending"
    const val RESOLVE_RETRIED_BUSY = "resolve:retried-busy"
    const val RESOLVE_SUCCEEDED = "resolve:succeeded"
    const val RESOLVE_FAILED = "resolve:failed"
    const val RESOLVE_DROPPED_STALE = "resolve:dropped-stale"

    // ── Candidate validation (fixed category, never the raw value) ──────────
    const val CANDIDATE_REJECTED_TYPE = "candidate:rejected-type"
    const val CANDIDATE_REJECTED_PROTOCOL = "candidate:rejected-protocol"
    const val CANDIDATE_REJECTED_HOST = "candidate:rejected-host"
    const val CANDIDATE_REJECTED_PORT = "candidate:rejected-port"
    const val CANDIDATE_ACCEPTED = "candidate:accepted"

    // ── Authenticated probe outcome class ───────────────────────────────────
    const val PROBE_AUTHENTICATED = "probe:authenticated"
    const val PROBE_REJECTED = "probe:rejected"

    // ── Final verdict ───────────────────────────────────────────────────────
    const val VERDICT_ONE = "verdict:one"
    const val VERDICT_NONE = "verdict:none"
    const val VERDICT_AMBIGUOUS = "verdict:ambiguous"

    /** Every category, so a test can pin the vocabulary and its address-freedom. */
    val ALL: List<String> = listOf(
        SAVED_PROBE_ACCEPTED, SAVED_PROBE_REJECTED, SAVED_PROBE_NONE,
        BROWSE_STARTED, BROWSE_START_FAILED, BROWSE_STOPPED, BROWSE_NO_CANDIDATE,
        FOUND_ACCEPTED_TYPE, FOUND_OTHER_TYPE, FOUND_NULL_TYPE,
        RESOLVE_STARTED, RESOLVE_QUEUED, RESOLVE_DROPPED_ALREADY_PENDING,
        RESOLVE_RETRIED_BUSY, RESOLVE_SUCCEEDED, RESOLVE_FAILED, RESOLVE_DROPPED_STALE,
        CANDIDATE_REJECTED_TYPE, CANDIDATE_REJECTED_PROTOCOL, CANDIDATE_REJECTED_HOST,
        CANDIDATE_REJECTED_PORT, CANDIDATE_ACCEPTED,
        PROBE_AUTHENTICATED, PROBE_REJECTED,
        VERDICT_ONE, VERDICT_NONE, VERDICT_AMBIGUOUS,
    )
}

/**
 * Bounded, category-only stage log.
 *
 * Used by the pure layer, by the platform NSD adapter and (through
 * [DiscoveryDiagnostics]) by the ViewModel. Deliberately holds a fixed-size
 * window so a long session cannot accumulate an unbounded trace, and it never
 * accepts a value-bearing detail: the parameter IS a category constant.
 */
interface DiscoveryDiagnostics {
    /** Records one fixed category. Never carries an address or free text. */
    fun note(category: String)
}

/**
 * 1C-D-04@R2 — the automatic run's full result.
 *
 * [DiscoverySelection] answers "did the frozen exactly-one rule produce an upload
 * target". It cannot answer "which computers authenticated", which is exactly what
 * an explicit computer switch needs: the user must be able to choose among the
 * endpoints that passed the Bearer probe without retyping an address.
 *
 * [authenticated] therefore carries every distinct endpoint that authenticated
 * inside the window (at most [DiscoveryCoordinator.MAX_REMEMBERED_TARGETS]). It is
 * address data and stays inside the Watch: it is never logged, never broadcast,
 * and never persisted except for the one endpoint the user or the frozen rule
 * actually selects.
 */
data class DiscoveryOutcome(
    /** The saved address still answered: keep using it (frozen behaviour 2). */
    val existingAddress: DiscoverySelection?,
    /** The exactly-one verdict of the browse window, or null. */
    val single: DiscoverySelection?,
    /** Every distinct endpoint that passed the authenticated probe. */
    val authenticated: List<DiscoverySelection>,
) {
    /** True when this run ended in the bounded manual fallback. */
    val isFallback: Boolean get() = existingAddress == null && single == null
}

/** Thread-safe [DiscoveryDiagnostics] over a bounded ring of categories. */
class CategoryLog(
    private val capacity: Int = 64,
    /** Sink for the platform logcat; category-only by construction. */
    private val sink: (String) -> Unit = {},
) : DiscoveryDiagnostics {

    private val lock = Any()
    private val events = ArrayDeque<String>()

    override fun note(category: String) {
        synchronized(lock) {
            events.addLast(category)
            while (events.size > capacity) events.removeFirst()
        }
        sink(category)
    }

    /** The recorded categories, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }
}

/** Why a candidate was refused, as a fixed category (never the raw value). */
enum class CandidateRejection {
    SERVICE_TYPE,
    PROTOCOL,
    HOST,
    PORT,
    ;

    /** @return the frozen diagnostic category for this refusal. */
    val category: String
        get() = when (this) {
            SERVICE_TYPE -> DiscoveryDiagnostic.CANDIDATE_REJECTED_TYPE
            PROTOCOL -> DiscoveryDiagnostic.CANDIDATE_REJECTED_PROTOCOL
            HOST -> DiscoveryDiagnostic.CANDIDATE_REJECTED_HOST
            PORT -> DiscoveryDiagnostic.CANDIDATE_REJECTED_PORT
        }
}

/**
 * One service resolved by the platform browser, reduced to the leaf fields the
 * selection rules need. No Android type crosses this boundary.
 *
 * Delivery 1C Repair 1 必修 1: Android's `NsdServiceInfo` exposes the *instance*
 * name (`SayIt`) and the *service type* (`_sayit-watch._tcp.`) as two separate
 * fields. They are kept separate here as well, and the candidate policy checks
 * the type — never the instance name — so a real SayIt instance is accepted.
 */
data class ResolvedService(
    /** Instance name as broadcast, e.g. `SayIt`. Never used for type matching. */
    val instanceName: String,
    /** Service type as reported by the platform, e.g. `_sayit-watch._tcp.`. */
    val serviceType: String,
    /** Literal host address, or null when the platform did not resolve one. */
    val host: String?,
    val port: Int,
    /** Advertised TXT `protocol`, or null when absent. */
    val protocol: String?,
)

/** A destination that passed both candidate validation and the authenticated probe. */
data class DiscoverySelection(val ip: String, val port: Int)

/**
 * Result of the authenticated discovery probe.
 *
 * [Authenticated] means HTTP 200 plus an exact match of the frozen service id
 * and protocol version — nothing weaker is accepted.
 */
sealed class DiscoveryProbeResult {
    data class Authenticated(val serviceId: String, val protocol: Int) : DiscoveryProbeResult()
    data class Rejected(val reason: String) : DiscoveryProbeResult()
}

/**
 * Authenticated probe transport. The release build has no implementation at all
 * (see `Transport`), so release can never authenticate a discovered address.
 */
interface DiscoveryProbe {
    /**
     * Blocking probe of one candidate, bounded by [timeoutMs].
     *
     * The timeout is mandatory: the caller owns the end-to-end 3 s / 8 s budgets
     * (Repair 1 必修 4), so the probe may never spend more time than the budget
     * still allows.
     *
     * @return [DiscoveryProbeResult.Authenticated] only on an exact frozen match.
     */
    fun probe(ip: String, port: Int, token: String, timeoutMs: Int): DiscoveryProbeResult
}

/** Frozen selection rules. */
object DiscoveryPolicy {

    /**
     * 1C-D-04@R2/R10: does a platform-reported service type belong to this product?
     *
     * `NsdManager` normalizes neither the domain suffix nor the case — and the two callbacks it
     * feeds do not even agree with each other. On a real Galaxy Watch 7 (Android 16 / API 36) the
     * observed chain is:
     *
     * ```
     * onServiceFound:    "_sayit-watch._tcp."     (trailing dot)
     * onServiceResolved: "._sayit-watch._tcp"     (ONE LEADING dot)
     * ```
     *
     * The R2 comparison tolerated case, a trailing dot and the `.local` domain, but not that
     * leading dot, so the device reached `resolve:succeeded` and then refused its own hardware with
     * `candidate:rejected-type` — a silent, complete discovery failure with the responder working
     * perfectly.
     *
     * Exactly ONE optional leading dot is accepted, combined with the existing case/whitespace,
     * trailing-dot and `.local` tolerance. This widens the accepted *spelling* of the frozen type,
     * never the set of services: an empty value, `.._sayit-watch._tcp` (two leading dots), an extra
     * label (`_sayit-watch._tcp.example`), a wrong protocol (`_sayit-watch._udp`) and a similar but
     * different name (`_sayit._tcp`) are all still refused.
     */
    fun isOurServiceType(raw: String?): Boolean {
        val type = raw?.trim()?.lowercase() ?: return false
        if (type.isEmpty()) return false
        // Android's resolve callback historically prefixes the type with a single dot. Strip at
        // most one: a second leading dot is a different (malformed) name, not a variant.
        val withoutLeadingDot = type.removePrefix(".")
        if (withoutLeadingDot.isEmpty() || withoutLeadingDot.startsWith(".")) return false
        val withoutTrailingDot = withoutLeadingDot.removeSuffix(".")
        val normalized = withoutTrailingDot.removeSuffix(".local")
        return normalized == "_sayit-watch._tcp"
    }

    /**
     * 1C-D-04@R2: the same candidate rule as [candidate], but reporting the
     * refusal as a fixed [CandidateRejection] category instead of a string that
     * may embed the observed value.
     */
    fun classify(s: ResolvedService): CandidateRejection? {
        if (!isOurServiceType(s.serviceType)) return CandidateRejection.SERVICE_TYPE
        val protocol = s.protocol
        if (protocol != null && protocol != SAYIT_DISCOVERY_PROTOCOL.toString()) {
            return CandidateRejection.PROTOCOL
        }
        val host = s.host?.trim()
        if (host.isNullOrEmpty()) return CandidateRejection.HOST
        val port = s.port
        if (port !in 1..65535) return CandidateRejection.PORT
        return when (DestinationValidator.validate(host, port.toString())) {
            is DestinationValidator.ValidationResult.Valid -> null
            is DestinationValidator.ValidationResult.Invalid -> CandidateRejection.HOST
        }
    }

    /**
     * A resolved service may only become a probe target when every one of these
     * holds:
     * - its *service type* is exactly the frozen type (the instance name is
     *   irrelevant and may be anything the responder chose);
     * - its host is a literal RFC1918 IPv4 (never a hostname, IPv6, public,
     *   link-local, loopback or `0.0.0.0` address);
     * - its port is in 1..65535;
     * - the advertised protocol, when present, is exactly the frozen version.
     */
    fun candidate(s: ResolvedService): DestinationValidator.ValidationResult {
        // 1C-D-04@R2: the type match is domain/case tolerant (see
        // [isOurServiceType]); the rejection text no longer echoes the value.
        if (!isOurServiceType(s.serviceType)) {
            return DestinationValidator.ValidationResult.Invalid("unexpected service type")
        }
        val protocol = s.protocol
        if (protocol != null && protocol != SAYIT_DISCOVERY_PROTOCOL.toString()) {
            return DestinationValidator.ValidationResult.Invalid("unsupported discovery protocol")
        }
        return DestinationValidator.validate(s.host, s.port.toString())
    }

    /**
     * Parses the authenticated probe's JSON body without a JSON dependency.
     *
     * The frozen success body carries exactly `service` and `protocol` (Repair 1
     * 必修 4 removed the extra `path` field). A missing or different service id or
     * protocol is a rejection. The token is never part of the response and is
     * never parsed.
     */
    fun parseProbeResponse(status: Int, body: String): DiscoveryProbeResult {
        if (status != 200) return DiscoveryProbeResult.Rejected("HTTP $status")
        val serviceId = stringField(body, "service")
            ?: return DiscoveryProbeResult.Rejected("missing service id")
        if (serviceId != SAYIT_DISCOVERY_SERVICE_ID) {
            return DiscoveryProbeResult.Rejected("wrong service id")
        }
        val protocol = intField(body, "protocol")
            ?: return DiscoveryProbeResult.Rejected("missing protocol")
        if (protocol != SAYIT_DISCOVERY_PROTOCOL) {
            return DiscoveryProbeResult.Rejected("unsupported protocol $protocol")
        }
        return DiscoveryProbeResult.Authenticated(serviceId, protocol)
    }

    /** @return the string value of `"key":"value"`, or null when absent. */
    fun stringField(body: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(body)?.groupValues?.get(1)
    }

    /** @return the integer value of `"key":123`, or null when absent/invalid. */
    fun intField(body: String, key: String): Int? {
        val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return pattern.find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Accumulates one resolve completion into the current candidate snapshot.
     *
     * This is the concurrency-critical half of [AndroidNsdDiscovery]: several
     * `resolveService` calls can complete at any time and in any interleaving, so
     * every distinct endpoint must survive. Kept here as a pure function (the
     * Android layer only feeds it under a lock) so the "a second concurrent
     * resolve is not lost" property is JVM-testable without an emulator.
     *
     * Deduplication is by endpoint (host + port); a repeat delivery of the same
     * endpoint returns the unchanged list.
     */
    fun foldResolved(
        current: List<ResolvedService>,
        incoming: ResolvedService,
    ): List<ResolvedService> {
        if (current.any { it.host == incoming.host && it.port == incoming.port }) {
            return current
        }
        return current + incoming
    }
}

/**
 * Delivery 1C Repair 2 必修 1 — the strict shared-deadline budget for one probe.
 *
 * A probe's connect, response-header wait and body read happen serially, so a
 * per-phase timeout cannot bound the total: `connectTimeout + readTimeout` may
 * exceed the caller's budget. This planner hands out ONE slice per serial phase
 * and guarantees the slices never add up to more than the caller's [timeoutMs].
 * Each slice is recomputed against the wall clock right before that phase's
 * blocking call, so already-consumed time is deducted automatically.
 *
 * Pure and clock-injectable, so the split itself is unit-testable without sockets.
 */
class ProbeBudget(
    /** The caller's total budget for this probe, in milliseconds. */
    val timeoutMs: Int,
    /** Wall clock at the moment the probe started. */
    private val startMs: Long,
    /** Phases this probe performs serially, in order. */
    private val phases: List<Phase> = listOf(Phase.CONNECT, Phase.HEADERS, Phase.BODY),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ReadDeadline {
    enum class Phase { CONNECT, HEADERS, BODY }

    /**
     * Per-slice ceiling for the phases that are NOT the last one. The last phase
     * may use everything still left, because the earlier phases only ever used
     * their own capped slices.
     */
    private val maxSliceMs: Long = (timeoutMs.coerceAtLeast(3) / phases.size).toLong()

    /** True once the whole probe budget is used up. */
    fun exhausted(): Boolean = remainingMs() <= 0L

    /** Milliseconds still available to this probe (never negative). */
    override fun remainingMs(): Long = (startMs + timeoutMs - nowMs()).coerceAtLeast(0L)

    /**
     * The blocking limit for [phase]'s next call. Never larger than the time
     * actually left, so the serial phases together can never outlive [timeoutMs].
     * Returns 0 when the budget is exhausted: the caller must not issue the call.
     */
    fun nextTimeoutMs(phase: Phase): Long {
        val left = remainingMs()
        if (left <= 0L) return 0L
        val index = phases.indexOf(phase)
        if (index < 0) return left
        if (index == phases.lastIndex) return left
        return minOf(left, maxSliceMs).coerceAtLeast(1L)
    }

    /**
     * Worst-case total this planner can spend, given the current clock: the sum of
     * the non-final slices plus whatever is left for the final phase. Never above
     * [timeoutMs] — this is the property the contract asks to assert.
     */
    fun worstCaseTotalMs(): Long {
        val left = remainingMs()
        var total = 0L
        phases.forEachIndexed { index, phase ->
            total += if (index == phases.lastIndex) left else nextTimeoutMs(phase)
        }
        return total
    }
}

/** Minimal HTTP outcome the discovery probe needs. Never carries a raw stream. */
data class HttpProbeResponse(val status: Int, val body: String)

/**
 * The strict per-read deadline source for the raw-socket discovery probe.
 *
 * Repair 2 必修 1 (PM follow-up): `Socket.soTimeout` is an *idle* timeout that each
 * low-level read restarts, so a peer dripping one byte just under the timeout can
 * keep the probe alive forever. Every read therefore asks this for the time left
 * and re-arms the socket with it; when it reaches 0 the probe aborts. Implemented
 * by [ProbeBudget].
 */
interface ReadDeadline {
    /** Milliseconds still available; 0 means "abort now". */
    fun remainingMs(): Long
}

/**
 * Pure, byte-level HTTP/1.1 reading for the discovery probe.
 *
 * Deliberately NOT `BufferedReader.readLine()`: that buffers without bound and
 * only lets the caller check the cap after an unbounded accumulation. Here every
 * low-level read is bounded by the caller's remaining deadline (which it re-arms
 * on the socket before each call) and by a hard byte cap, so neither a slow drip
 * nor an endless stream can extend the probe past its budget.
 */
object DiscoveryHttpResponse {

    /**
     * Hard cap on the discovery response body. The Windows success body is about
     * 60 bytes, so this is generous while making an endless slow stream impossible.
     */
    const val MAX_BODY_BYTES: Int = 4_096

    /** Hard cap on the status line plus headers. */
    const val MAX_HEADER_BYTES: Int = 8_192

    /**
     * @return the status code from an `HTTP/1.1 200 OK` line, or null.
     */
    fun parseStatusLine(line: String): Int? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        if (!parts[0].startsWith("HTTP/")) return null
        val code = parts[1].toIntOrNull() ?: return null
        return if (code in 100..599) code else null
    }

    /**
     * Reads the status line plus headers byte by byte.
     *
     * @param stream raw socket input.
     * @param deadline consulted before every single read, so a slow drip cannot
     *   extend the probe; [armTimeout] re-arms the socket's idle timeout with the
     *   same remaining value.
     * @return the status code, or null on malformed input, cap overflow, EOF or an
     *   exhausted deadline.
     */
    fun readStatusAndHeaders(
        stream: java.io.InputStream,
        deadline: ReadDeadline,
        armTimeout: (Int) -> Unit,
    ): Int? {
        val head = readUntilHeaderEnd(stream, deadline, armTimeout) ?: return null
        val statusLine = head.substringBefore('\n').trimEnd('\r')
        return parseStatusLine(statusLine)
    }

    /**
     * Reads at most [MAX_BODY_BYTES] of the body under the same deadline.
     *
     * @return the body (possibly empty), or null when the deadline ran out.
     */
    fun readBody(
        stream: java.io.InputStream,
        deadline: ReadDeadline,
        armTimeout: (Int) -> Unit,
    ): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (out.size() < MAX_BODY_BYTES) {
            val waitMs = deadline.remainingMs()
            if (waitMs <= 0L) return null
            armTimeout(waitMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val limit = minOf(buffer.size, MAX_BODY_BYTES - out.size())
            val read = stream.read(buffer, 0, limit)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Reads up to and including the blank line that ends the header block. */
    private fun readUntilHeaderEnd(
        stream: java.io.InputStream,
        deadline: ReadDeadline,
        armTimeout: (Int) -> Unit,
    ): String? {
        val out = java.io.ByteArrayOutputStream()
        var match = 0
        while (out.size() < MAX_HEADER_BYTES) {
            // The deadline is re-checked and the socket re-armed for EVERY read:
            // an idle timeout alone can be defeated by a one-byte-per-tick drip.
            val waitMs = deadline.remainingMs()
            if (waitMs <= 0L) return null
            armTimeout(waitMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val b = stream.read()
            if (b < 0) return null
            out.write(b)
            // Track the \r\n\r\n terminator without buffering more than needed.
            match = when {
                b == '\r'.code && (match == 0 || match == 2) -> match + 1
                b == '\n'.code && (match == 1 || match == 3) -> match + 1
                else -> 0
            }
            if (match == 4) break
        }
        if (out.size() >= MAX_HEADER_BYTES && match != 4) return null
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }
}
