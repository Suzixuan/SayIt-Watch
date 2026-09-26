package com.sayit.watch.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sayit.watch.net.DefaultDiscoveryTiming
import com.sayit.watch.net.ConnectionTaskOwner
import com.sayit.watch.net.Discovery
import com.sayit.watch.net.DiscoveryCoordinator
import com.sayit.watch.net.DiscoveryProbe
import com.sayit.watch.net.DiscoveryProbeResult
import com.sayit.watch.net.DiscoverySelection
import com.sayit.watch.net.DiscoverySettings
import com.sayit.watch.net.DiscoveryState
import com.sayit.watch.net.ResolverBridge
import com.sayit.watch.net.ServiceDiscovery
import com.sayit.watch.net.SettingsDiscoverySettings
import com.sayit.watch.net.Transport
import com.sayit.watch.net.TransportClient
import com.sayit.watch.recording.AudioCapture
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.recording.WavWriter
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure UI state machine for the Watch screens.
 *
 * Screens: CONFIG -> READY -> RECORDING. Uploading remains an internal busy
 * latch only: it returns visually to Ready, blocks another recording, and is
 * cleared silently after either HTTP result.
 *
 * Delivery 1C: the address fields are no longer part of the required first-run
 * flow. Only the 64-hex token is mandatory; the PC IP/port arrive from
 * authenticated automatic discovery, and the manual fields are revealed either
 * by the user ("手动设置") or after discovery fails
 * ([discoveryExhausted]).
 *
 * The multi-generation Cancel/completion coordinator lives in
 * RecordingRequestLatch.kt (Z3 Repair 4 必修 1).
 */
data class WatchUiState(
    val screen: Screen,
    /** Null until the first health check; true only when /api/health answered. */
    val transportAvailable: Boolean?,
    /** Internal only: no visible upload state is rendered while this is true. */
    val isUploading: Boolean,
    /** Delivery 1C: the manual IP/port section is expanded on the config screen. */
    val manualOpen: Boolean = false,
    /** Delivery 1C: automatic discovery gave up, so manual entry is offered. */
    val discoveryExhausted: Boolean = false,
    /** 1C-D-04@R2: the explicit "switch computer" picker is open. */
    val switchOpen: Boolean = false,
    /** 1C-D-04@R2: a fresh switch browse is in flight inside the picker. */
    val switchSearching: Boolean = false,
    /**
     * 1C-D-04@R7 §2A: the single connection task owner has a round in flight, so the
     * screen may say "connecting" instead of reusing historical authentication.
     */
    val connecting: Boolean = false,
    /**
     * 1C-D-04@R7 §2A/§2B: true only while an authenticated computer is usable right
     * now. `transportAvailable` stays the recording gate; this is the same fact
     * projected for wording, so nothing can claim a connection from history.
     */
    val connected: Boolean = false,
    /**
     * 1C-D-04@R2: every computer that passed the authenticated Bearer probe in this
     * session, so the user can pick one instead of retyping an address. Address data
     * stays on the Watch: never logged, never broadcast, never sent anywhere.
     */
    val targets: List<DiscoverySelection> = emptyList(),
    /** The endpoint currently in use, marked in the picker. */
    val currentTarget: DiscoverySelection? = null,
) {
    enum class Screen { CONFIG, READY, RECORDING }

    val busy: Boolean
        get() = screen == Screen.RECORDING || isUploading

    /**
     * Delivery 1C Repair 1 必修 2: recording is only possible with a destination
     * verified by an authenticated probe (`transportAvailable == true`). Without
     * one — re-probe in flight, browse in flight, manual probe in flight or
     * failed, fallback — the record entry point must be a no-op.
     */
    val canRecord: Boolean
        get() = screen == Screen.READY && !isUploading && transportAvailable == true

    /** MainActivity maps this to WindowManager FLAG_KEEP_SCREEN_ON. */
    val keepScreenOn: Boolean get() = busy
}

/**
 * 1C-D-04@R7 §2B — one row of the explicit switch picker.
 *
 * The computer in use always comes first and is marked, so the switch page never
 * hides the connection the user is on (R5's page only listed the session's other
 * authenticated computers).
 */
data class SwitchEntry(
    val target: DiscoverySelection,
    val isCurrent: Boolean,
)

class WatchUiStateMachine(
    initial: WatchUiState =
        WatchUiState(
            screen = WatchUiState.Screen.CONFIG,
            transportAvailable = null,
            isUploading = false,
        ),
) {
    var state: WatchUiState = initial
        private set

    private fun set(state: WatchUiState) {
        this.state = state
    }

    /**
     * Startup decision: a valid saved token starts directly on Ready (the PC
     * address may still be unknown and is then discovered automatically);
     * a missing/invalid token starts on Config.
     * Idempotent — later calls do not move a RECORDING screen.
     */
    fun startupWith(configValid: Boolean) {
        if (state.screen == WatchUiState.Screen.CONFIG && !state.isUploading) {
            if (configValid) {
                set(state.copy(screen = WatchUiState.Screen.READY))
            }
            // invalid/missing -> stay CONFIG
        }
    }

    /** Save & Apply on the config screen: valid token -> READY. */
    fun settingsApplied() {
        if (state.screen == WatchUiState.Screen.CONFIG) {
            set(state.copy(screen = WatchUiState.Screen.READY))
        }
    }

    /** Delivery 1C: discovery authenticated a PC address and it was saved. */
    fun destinationResolved() {
        if (state.screen != WatchUiState.Screen.RECORDING) {
            set(
                state.copy(
                    screen = WatchUiState.Screen.READY,
                    discoveryExhausted = false,
                    transportAvailable = true,
                    connected = true,
                    connecting = false,
                )
            )
        }
    }

    /** Delivery 1C: the 8 s window ended without a matching, authenticated host. */
    fun discoveryFailed() {
        if (state.screen != WatchUiState.Screen.RECORDING) {
            set(
                state.copy(
                    discoveryExhausted = true,
                    manualOpen = true,
                    transportAvailable = false,
                    connected = false,
                    connecting = false,
                    switchSearching = false,
                )
            )
        }
    }

    /**
     * Delivery 1C Repair 1 必修 2: a run (re-probe, browse, or manual probe) is in
     * flight, so there is no verified upload target right now. Recording must stay
     * unavailable until exactly one endpoint authenticates.
     */
    fun searchingStarted() {
        if (state.screen != WatchUiState.Screen.RECORDING) {
            set(state.copy(transportAvailable = false, connected = false))
        }
    }

    /** Delivery 1C: user tapped "手动设置" on the config screen. */
    fun manualSettingsToggled() {
        set(state.copy(manualOpen = !state.manualOpen))
    }

    /** 1C-D-04@R2: publishes the authenticated computer list to the picker. */
    fun targetsUpdated(targets: List<DiscoverySelection>, current: DiscoverySelection?) {
        set(state.copy(targets = targets, currentTarget = current))
    }

    /**
     * 1C-D-04@R7 §2A: a connection round started. The historical authentication is
     * NOT online proof, so the connected flag drops while the round runs.
     */
    fun connectionChecking() {
        if (state.screen != WatchUiState.Screen.RECORDING) {
            set(state.copy(connecting = true, connected = false, transportAvailable = false))
        }
    }

    /** 1C-D-04@R7 §2A: the round finished; nothing is in flight any more. */
    fun connectionRoundFinished() {
        set(state.copy(connecting = false))
    }

    /**
     * 1C-D-04@R7 §2A: the computer in use stopped answering, or a browse superseded
     * the round. The address may still be remembered as "last used" but it must not
     * keep being presented as a live connection.
     */
    fun connectionCleared() {
        if (state.screen != WatchUiState.Screen.RECORDING) {
            set(state.copy(connected = false, transportAvailable = false))
        }
    }

    /** 1C-D-04@R2: the user opened the explicit "switch computer" picker. */
    fun switchOpened() {
        set(state.copy(switchOpen = true))
    }

    /** 1C-D-04@R2: a fresh switch browse is running (8 s bounded window). */
    fun switchSearching(searching: Boolean) {
        set(state.copy(switchSearching = searching))
    }

    /** 1C-D-04@R2: the picker closed without a choice (no target change). */
    fun switchClosed() {
        set(state.copy(switchOpen = false, switchSearching = false))
    }

    /** 1C-D-04@R2: the user picked a different authenticated computer. */
    fun targetChosen(candidate: DiscoverySelection) {
        set(
            state.copy(
                switchOpen = false,
                switchSearching = false,
                currentTarget = candidate,
                transportAvailable = true,
                connected = true,
                connecting = false,
                discoveryExhausted = false,
            )
        )
    }

    fun backToConfig() {
        if (state.screen != WatchUiState.Screen.RECORDING && !state.isUploading) {
            set(state.copy(screen = WatchUiState.Screen.CONFIG))
        }
    }

    /** Result of the explicit /api/health check on the Ready screen. */
    fun healthChecked(available: Boolean) {
        set(state.copy(transportAvailable = available, connected = available))
    }

    /** Ready is visible during a silent upload, but cannot start another capture. */
    fun canStartRecording(): Boolean = state.screen == WatchUiState.Screen.READY && !state.isUploading

    fun recordingStarted() {
        if (canStartRecording()) {
            set(state.copy(screen = WatchUiState.Screen.RECORDING))
        }
    }

    /** Stop uploads automatically but immediately returns the visible screen to Ready. */
    fun uploadStarted() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY, isUploading = true))
        }
    }

    /** Cancel pressed on the recording screen: stop and discard, never upload. */
    fun cancelPressed() {
        if (state.screen == WatchUiState.Screen.RECORDING) {
            set(state.copy(screen = WatchUiState.Screen.READY))
        }
    }

    /** Both success and failure finish with the same silent Ready state. */
    fun uploadFinished() {
        if (state.isUploading) {
            set(state.copy(isUploading = false))
        }
    }
}

/**
 * ViewModel owning the Delivery 1A recording/transport lifecycle plus the UI
 * state machine. The HTTP sender is created through [Transport.sender]; in
 * release builds that returns null and sending fails closed.
 *
 * Delivery 1C adds the authenticated discovery coordinator: startup re-probes the
 * last known address, otherwise browses `_sayit-watch._tcp` for at most 8 s, and
 * only saves an address that answered `GET /api/watch/discovery` with 200 under
 * the stored 64-hex token. Manual IP/port remains the fallback and is probed the
 * same way before it is adopted.
 *
 * Stop auto-uploads the completed WAV silently; either HTTP result clears it and
 * makes the Watch recordable again. A failed upload invalidates the known address
 * so the *next* attempt re-discovers — the same audio is never re-sent.
 *
 * 1C-D-04@R7 §2A: every search is one round of a single [ConnectionTaskOwner], which
 * keeps retrying every 5 s while the app is in the foreground and no computer is
 * usable, revalidates the computer in use every 5 s with one bounded authenticated
 * probe, and stops entirely in the background. This is what removes the old "start
 * the PC after the Watch and you must restart the app" behaviour.
 */
class RecordingViewModel(
    /**
     * 1C-D-04@R3: the narrow settings port. It is not nullable, so every use site
     * keeps its existing guarantee, and a JVM test can supply a fake — which is what
     * makes the REAL `requestSwitch()` entry point testable without a `Context`.
     */
    settings: DiscoverySettings,
    private val session: RecordingSession = RecordingSession(),
    private val capture: AudioCapture = AudioCapture(),
    private val clientFactory: (() -> TransportClient?) = { Transport.sender() },
    /**
     * Delivery 1C: application context for the platform NSD browser (null in
     * tests). Discovery itself is injected as [discoveryBrowser] / [discoveryProbe];
     * both are null in release builds, so no discovered address can ever be
     * authenticated there.
     */
    context: Context? = null,
    private val discoveryBrowser: ServiceDiscovery? = context?.let { Discovery.browser(it) },
    private val discoveryProbe: DiscoveryProbe? = Discovery.probe(),
    /**
     * 1C-D-04@R3: overrides the coordinator construction, which is what lets the REAL
     * `requestSwitch()` entry point be driven by a JVM test through a fake browser.
     * `null` means "build the ordinary scope-bound production coordinator".
     */
    private val coordinatorOverride: DiscoveryCoordinator? = null,
    /**
     * 1C-D-04@R7 §2A: the recovery/health-check cadence. Production keeps the frozen
     * 5 s retry and 5 s idle revalidation; a JVM test shortens them so the timing rule
     * is verified without a five-second sleep.
     */
    private val retryDelayMs: Long = ConnectionTaskOwner.DefaultRetryDelayMs,
    private val healthCheckIntervalMs: Long = ConnectionTaskOwner.DefaultHealthCheckIntervalMs,
) : ViewModel() {

    private val settings: DiscoverySettings = settings

    /**
     * Android entry point: adapts the app-private store onto the settings port.
     * Auxiliary (not a primary default argument) because evaluating it needs a real
     * `Context`; a JVM test uses the primary constructor with a fake instead.
     */
    constructor(
        store: SettingsStore,
        context: Context? = null,
    ) : this(
        settings = SettingsDiscoverySettings(store),
        context = context,
    )

    private val _state = MutableStateFlow(RecordingSession.State.IDLE)
    val state: StateFlow<RecordingSession.State> = _state.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** UI state machine (screens + inline upload states). */
    private val machine = WatchUiStateMachine()

    /**
     * 1C-D-04@R7 §2A: set by [onForeground]/[onBackground] from the Activity lifecycle.
     * The task owner only schedules while this is true, so the connection loop can never
     * become background polling.
     */
    @Volatile
    private var appInForeground: Boolean = false
    private val _ui = MutableStateFlow(machine.state)
    val ui: StateFlow<WatchUiState> = _ui.asStateFlow()

    private val _discovery = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    /**
     * True only while a probe-authenticated destination exists. The UI uses it to
     * enable the record entry point (Repair 1 必修 2).
     */
    private val _canRecord = MutableStateFlow(false)
    val canRecord: StateFlow<Boolean> = _canRecord.asStateFlow()

    /**
     * 1C-D-04@R3: every computer that authenticated in this session, owned by the
     * resolver bridge so the picker's list and its membership check can never drift
     * apart. Nothing here is ever logged or broadcast.
     */
    private val _targets = MutableStateFlow<List<DiscoverySelection>>(emptyList())
    val targets: StateFlow<List<DiscoverySelection>> = _targets.asStateFlow()

    /**
     * 1C-D-04@R2 diagnostic surface: the LAST fixed stage category the discovery
     * chain reached (`DiscoveryDiagnostic.*`). Address-free by construction, so it is
     * safe to show and to report; it never carries an IP, host name or exception.
     */
    private val _lastStage = MutableStateFlow<String?>(null)
    val lastStage: StateFlow<String?> = _lastStage.asStateFlow()

    /**
     * Delivery 1C Repair 1 必修 2: the ONLY address an upload may go to. It is set
     * exactly when an endpoint authenticated in this session (discovery verdict or
     * a successful manual probe) and cleared on failure/invalidation. It never
     * falls back to a merely well-formed saved value.
     */
    @Volatile
    private var verifiedDestination: DiscoverySelection? = null

    /**
     * Delivery 1C coordinator. Present only in debug builds with a working
     * browser + probe: release builds get null client/probe, so no discovered
     * address can ever be authenticated there.
     *
     * 1C-D-04@R3: built through [coordinatorFactory] so a JVM test can supply the
     * same production coordinator driven by a fake browser — the real
     * `requestSwitch()` path is then testable without Android.
     */
    private val coordinator: DiscoveryCoordinator? =
        coordinatorOverride ?: if (discoveryBrowser != null && discoveryProbe != null) {
            DiscoveryCoordinator(
                settings = settings,
                discovery = discoveryBrowser,
                probe = discoveryProbe,
                isEnabled = { Transport.isDebug() },
                scope = viewModelScope,
                // 1C-D-04@R2: one address-free category trail shared with the
                // platform browser, so the whole discovery chain is one logcat view.
                diagnostics = Discovery.diagnostics,
            )
        } else {
            null
        }

    /**
     * Delivery 1C Repair 2 必修 2: the single owner of "which mode is resolving".
     *
     * Automatic and manual resolution can never run together, every run has a
     * generation, and only an exactly-one authenticated verdict reaches
     * [setVerifiedDestination]. The bridge also closes the verdict gap: the
     * coordinator's FINAL state (`ExistingAddress` / `Discovered` /
     * `ManualFallback`) is what drives the target, not just the initial `Searching`.
     */
    private val resolver = ResolverBridge.forCoordinator(coordinator) { settings.hasValidToken() }

    /**
     * 1C-D-04@R7 §2A — the single task owner. It replaces the old "search only when
     * the screen changes" behaviour: every search (Ready entry, foreground return,
     * explicit re-search, recovery retry) is one round of this loop, so a PC started
     * after the Watch no longer needs an app restart.
     */
    private val taskOwner = ConnectionTaskOwner(
        resolveNow = { force -> resolveAutomatically(force) },
        browseForPicker = { browseForSwitch() },
        /** A SEARCH round starts from scratch, so any previous usable state is dropped. */
        cancelTransport = {
            resolver.onTargetInvalidated()
            coordinator?.invalidate()
            setVerifiedDestination(null)
        },
        /**
         * 1C-D-04@R8 P0-A: handing the resolution over to an explicit switch browse must NOT
         * touch the computer in use. R7 used `onTargetInvalidated()`, which clears the engine's
         * verified target while the ViewModel's own copy still pointed at the old computer — one
         * switch and the two sources of truth disagreed, so the scheduler searched while the page
         * still offered recording. Now the engine's run is released (generation bumped, late
         * results dropped) and the live browse is stopped, but the target survives.
         */
        cancelForRefresh = {
            resolver.releaseAutomaticRunKeepTarget()
            coordinator?.stopBrowseOnly()
        },
        isConnected = { resolver.hasVerifiedTarget },
        /**
         * 1C-D-04@R9 P0-A: the probe itself is pure — it reports the answer and nothing else. The
         * OWNER decides whether that answer may still be applied, so a probe that returns after its
         * round was superseded, or after the app went to the background, is dropped instead of
         * clearing the target, the UI or the recording gate.
         */
        probeCurrentTarget = { probeVerifiedTarget() },
        onProbeFailed = { onHealthProbeFailed() },
        onStarted = { ownerRoundStarted() },
        onFinished = { ownerRoundFinished() },
        retryDelayMs = retryDelayMs,
        healthCheckIntervalMs = healthCheckIntervalMs,
    )

    init {
        // Startup rule: a valid saved token is enough to reach Ready. The address is re-probed /
        // discovered there instead of being a manual prerequisite. Until that completes,
        // `transportAvailable` is false and recording is a no-op (Repair 1 必修 2).
        uiEvent { it.startupWith(settings.hasValidToken()) }
    }

    private fun syncUi() {
        _ui.value = machine.state
    }

    /**
     * 1C-D-04@R7 §2A: the app entered the foreground. The connection is revalidated
     * immediately (one bounded probe) instead of trusting the last authentication, and
     * if no computer is usable a search starts at once and keeps retrying every 5 s.
     */
    fun onForeground() {
        appInForeground = true
        if (!settings.hasValidToken()) return
        uiEvent(WatchUiStateMachine::searchingStarted)
        taskOwner.resumeForeground()
    }

    /**
     * 1C-D-04@R7 §2A / R8 P0-C: leaving the foreground cancels the round in flight, stops the
     * transport and ends all scheduling — probing included. Every unfinished result is
     * invalidated by the transport's generation gate, so nothing may update the UI, the target
     * or the settings afterwards.
     */
    fun onBackground() {
        appInForeground = false
        taskOwner.pauseForeground()
        uiEvent(WatchUiStateMachine::connectionRoundFinished)
    }

    /**
     * True while the automatic task owner may schedule: the app is in the foreground,
     * the screen is not Config, and the single transport is not busy with recording or
     * an upload.
     */
    private fun schedulingAllowed(): Boolean {
        if (!appInForeground) return false
        val current = machine.state
        val busy = current.screen == WatchUiState.Screen.RECORDING || current.isUploading
        return !busy && current.screen != WatchUiState.Screen.CONFIG
    }

    private fun uiEvent(event: (WatchUiStateMachine) -> Unit) {
        event(machine)
        syncUi()
    }

    /**
     * Records the authenticated target and publishes it to the UI.
     *
     * Repair 1 必修 2/3: this is the single point where an upload target comes into existence, and
     * it is only ever called with an endpoint that authenticated — either the coordinator's
     * exactly-one verdict or a successful manual probe.
     *
     * 1C-D-04@R8 P0-A: the field is written FIRST and both UI updates are derived from it, so the
     * state machine's `currentTarget` can never be published from the previous value. (R7 called
     * `destinationResolved()` before refreshing the target, which is how the page could briefly
     * advertise a computer the resolver had already dropped.)
     */
    private fun setVerifiedDestination(selection: DiscoverySelection?) {
        verifiedDestination = selection
        _canRecord.value = selection != null
        if (selection != null) {
            uiEvent(WatchUiStateMachine::destinationResolved)
        } else {
            uiEvent { it.healthChecked(false) }
        }
        // Runs AFTER the state update above, so it reads the value that was just written.
        publishTargets()
    }

    /**
     * 1C-D-04@R3: mirrors the resolver bridge's authenticated-computer list — the
     * single owner of that list — into the UI state.
     */
    private fun publishTargets() {
        val known = resolver.rememberedTargets
        _targets.value = known
        val current = verifiedDestination
        uiEvent { it.targetsUpdated(known, current) }
    }

    /**
     * 1C-D-04@R2: refreshes the address-free stage trail from the coordinator, so a
     * failed discovery reports WHERE it stopped instead of only "not found".
     */
    private fun refreshStageTrail() {
        _lastStage.value = coordinator?.diagnosticsSnapshot()?.lastOrNull()
    }

    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

    /** Duration in ms derived from the captured sample count (never a wall clock). */
    val durationMs: Long get() = WavWriter.durationMs(_sampleCount.value)

    /** Result of the last 16 kHz capability check, if it succeeded. */
    var initInfo: AudioCapture.InitResult.Ready? = null
        private set

    private fun syncState() {
        _state.value = session.state
        _sampleCount.value = session.sampleCount
        _lastError.value = session.lastError
        _canSend.value = session.canSend()
    }

    /**
     * Startup rule: a valid saved token starts directly on Ready; first install
     * or missing/invalid token starts on Config. Decided in the constructor init
     * block; this method only verifies the 16 kHz capture capability.
     */
    fun prepare() {
        val result = capture.verifySupported()
        when (result) {
            is AudioCapture.InitResult.Ready -> {
                initInfo = result
                session.toReady()
            }
            is AudioCapture.InitResult.Failed -> {
                session.recordingFailed(result.reason)
            }
        }
        syncState()
    }

    /** Requires RECORD_AUDIO permission; caller must have requested it. */
    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Save & Apply on the config screen.
     *
     * Only the token is required. A manual destination is NEVER persisted here: it is
     * only adopted after the authenticated probe succeeds (Repair 1 必修 2), so a typo,
     * a stale address, or an address now owned by another device can never become an
     * upload target.
     *
     * 1C-D-04@R7 §2B: submitting an UNCHANGED form is not a connection change. Opening
     * the settings page and coming back must leave the computer in use usable, so the
     * target is only invalidated when the token or address actually differs — typing in
     * the form is not a saved configuration change.
     */
    fun applySettings(ip: String, port: String, token: String) {
        val canonicalToken = token.trim()
        val manual = DestinationValidator.validate(ip, port)
        val manualCandidate = (manual as? DestinationValidator.ValidationResult.Valid)
            ?.let { DiscoverySelection(it.ip, it.port) }
        val previous = verifiedDestination
        val unchanged = canonicalToken == settings.devToken &&
            (manualCandidate == null || manualCandidate == previous)
        if (unchanged && previous != null) {
            // Nothing was actually changed: keep the authenticated connection AND do not
            // rewrite the token. An unchanged submission is a no-op, not a new config.
            uiEvent(WatchUiStateMachine::settingsApplied)
            publishTargets()
            return
        }

        stopConnectionTask()
        resolver.onConfigEntered()
        setVerifiedDestination(null)
        settings.saveDevToken(canonicalToken)
        if (!settings.hasValidToken()) return

        prepare()
        if (manualCandidate != null) {
            // Repair 2 必修 2: the manual probe owns the resolution. It cancels and
            // stops any automatic run first (the bridge does that), and the target
            // only appears when the probe authenticated.
            uiEvent(WatchUiStateMachine::searchingStarted)
            viewModelScope.launch {
                if (!resolver.onManualRequested(manualCandidate)) return@launch
                if (resolver.verifiedTarget == manualCandidate) {
                    _discovery.value = DiscoveryState.Discovered
                    uiEvent(WatchUiStateMachine::settingsApplied)
                    setVerifiedDestination(manualCandidate)
                } else {
                    _discovery.value = DiscoveryState.ManualFallback
                    uiEvent(WatchUiStateMachine::discoveryFailed)
                }
            }
        } else {
            // No (valid) manual address: let authenticated discovery decide. The screen
            // moves to Ready FIRST, because scheduling is only allowed there.
            uiEvent(WatchUiStateMachine::settingsApplied)
            startDiscovery()
        }
    }

    /** Delivery 1C: reveal/hide the manual IP + port section. */
    fun toggleManualSettings() {
        uiEvent(WatchUiStateMachine::manualSettingsToggled)
    }

    /**
     * Ready entry point (Repair 2 必修 2, extended by 1C-D-04@R7 §2A).
     *
     * R7: Ready entry no longer performs its own one-shot resolution. It expresses the
     * "search" intent to the single task owner, which already knows whether a usable
     * computer exists and keeps retrying while the screen stays disconnected. A
     * verified target therefore costs nothing, and a failed round is followed by
     * another one instead of leaving the screen stuck.
     */
    fun onReadyEntered() {
        if (!settings.hasValidToken()) return
        if (!appInForeground) return
        // 1C-D-04@R8 P0-B: entering Ready must never cost the connection. With a computer in use
        // this expresses a REVALIDATION (one bounded probe, target kept); only a disconnected
        // screen asks for a full search.
        if (resolver.hasVerifiedTarget) {
            resolver.verifiedTarget?.let { setVerifiedDestination(it) }
        }
        taskOwner.requestRefresh(ConnectionTaskOwner.Pending.SEARCH_KEEP)
    }

    /**
     * Explicit (re)resolution: the upload-failure path and the user's "重新搜索".
     *
     * 1C-D-04@R7 §2A: this cancels the round in flight and starts the newest one — the user's
     * re-search is never silently refused because an older round is running. R8: it is a FULL
     * search, so the previous usable state is dropped first, which is what makes it a real retry.
     */
    fun startDiscovery() {
        if (!settings.hasValidToken()) {
            uiEvent(WatchUiStateMachine::discoveryFailed)
            return
        }
        if (!schedulingAllowed()) return
        uiEvent(WatchUiStateMachine::searchingStarted)
        taskOwner.restartNow(ConnectionTaskOwner.Pending.SEARCH)
    }

    /** Reflects the bridge's verdict on the UI/target state. */
    private fun applyResolverVerdict() {
        refreshStageTrail()
        val target = resolver.verifiedTarget
        if (target != null) {
            _discovery.value = DiscoveryState.Discovered
            setVerifiedDestination(target)
            return
        }
        // 1C-D-04@R7: a failed round must clear the connected state too, otherwise the
        // screen keeps describing the last authentication as a live connection.
        _discovery.value = DiscoveryState.ManualFallback
        setVerifiedDestination(null)
        uiEvent(WatchUiStateMachine::discoveryFailed)
    }

    /**
     * 1C-D-04@R2/R3 — the explicit "switch computer" entry point.
     *
     * 1C-D-04@R7 必修 1/2: the picker opens and a single bounded browse refreshes the
     * candidate list. The browse NEVER adopts anything — not even a single new
     * candidate — so switching is always an explicit user pick. The computer in use
     * stays selected and stays usable until the user confirms another one.
     */
    fun requestSwitch() {
        if (!settings.hasValidToken()) return
        resolver.openPicker()
        uiEvent(WatchUiStateMachine::switchOpened)
        publishTargets()
        taskOwner.restartNow(ConnectionTaskOwner.Pending.BROWSE)
    }

    /**
     * One bounded switch browse, run as a round of the task owner.
     *
     * 1C-D-04@R3 必修 3: exactly ONE browse happens here (no saved-address re-probe
     * first). 1C-D-04@R7: its result only refreshes the list.
     */
    private suspend fun browseForSwitch() {
        _discovery.value = DiscoveryState.Searching
        uiEvent { it.switchSearching(true) }
        val browseRunId = resolver.newSwitchRun()
        val found = try {
            resolver.handleSwitchBrowse(browseRunId)
        } finally {
            // The picker must never stay in "searching" — not even when the round was cancelled by
            // a newer intent. 1C-D-04@R9: no NonCancellable wraps a cancelled round's
            // consequences, and the candidate refresh is generation-gated, so a browse that was
            // superseded cannot publish a list the newer round never produced. Closing the
            // "searching" flag is idempotent bookkeeping for state this same round opened.
            if (resolver.isCurrentSwitchRun(browseRunId)) {
                refreshStageTrail()
                publishTargets()
                uiEvent { it.switchSearching(false) }
            }
        }
        if (!resolver.isCurrentSwitchRun(browseRunId)) return
        _discovery.value =
            if (resolver.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
        if (found.isEmpty() && !resolver.hasVerifiedTarget) {
            // Nothing new and nothing in use: tell the user plainly.
            uiEvent(WatchUiStateMachine::discoveryFailed)
        }
    }

    /**
     * 1C-D-04@R3: adopts a resolved decision from the bridge.
     *
     * The bridge is the authority on which endpoints are authenticated: it already
     * refused anything outside its list, so this only applies the accepted value.
     */
    private fun applyTargetChoice(candidate: DiscoverySelection) {
        setVerifiedDestination(candidate)
        _discovery.value = DiscoveryState.Discovered
        uiEvent { machine -> machine.targetChosen(candidate) }
    }

    /**
     * 1C-D-04@R2/R3: the user picked one of the already-authenticated computers.
     *
     * 1C-D-04@R3 必修 1: the membership check now asks the bridge — the same object
     * that merged the browse result — so it can never disagree with the list the
     * picker rendered. An endpoint this session did not authenticate is still
     * refused, so widening the check is impossible.
     */
    fun onTargetPicked(candidate: DiscoverySelection) {
        viewModelScope.launch {
            when (val result = resolver.pick(candidate)) {
                is ResolverBridge.PickResult.Adopted -> applyTargetChoice(result.target)
                // Not an authenticated computer, or it stopped answering the probe:
                // refuse and keep the current target.
                ResolverBridge.PickResult.Refused -> uiEvent(WatchUiStateMachine::switchClosed)
                // A newer pick (or a cancel) superseded this probe.
                ResolverBridge.PickResult.Superseded -> Unit
            }
        }
    }

    /**
     * Closes the picker without changing the target.
     *
     * 1C-D-04@R8 P0-C: closing the picker must also end the browse it started. R7 only closed the
     * sheet, so a hidden picker could keep browsing (and publishing candidates) behind the user's
     * back. The run is cancelled first — and awaited, because the owner joins the cancelled round
     * — then the owner is returned to its stable loop over the CURRENT target: the health loop
     * while a computer is in use, the recovery search when there is none.
     */
    fun dismissSwitchPicker() {
        resolver.cancelPicker()
        uiEvent(WatchUiStateMachine::switchClosed)
        val kept = resolver.verifiedTarget
        // The picker browse projects Searching independently from the retained target. A
        // cancellation unwinds by throwing out of handleSwitchBrowse, so the normal terminal
        // projection below browseForSwitch is skipped. Restore the discovery projection from the
        // same authenticated source as the connection before the health loop resumes; with no
        // target, keep the disconnected fallback instead of implying a live connection.
        _discovery.value =
            if (kept != null) DiscoveryState.Discovered else DiscoveryState.ManualFallback
        // Cancel-first: the owner stops and JOINS the BROWSE round, so the browse/NSD session is
        // really over before anything else is scheduled (no hidden browse, no second listener).
        taskOwner.stop()
        resolver.releaseAutomaticRunKeepTarget()
        if (!appInForeground) return
        // Then hand over to the stable loop for the CURRENT target (health loop when a computer is
        // in use, recovery search when there is none) — never another hidden BROWSE.
        taskOwner.revalidateNow()
        if (kept != null) setVerifiedDestination(kept)
    }

    /**
     * 1C-D-04@R7 §2B: the picker's rows — the computer in use first (always visible,
     * even when it is not in the discovered list), then the session's other
     * authenticated computers.
     */
    fun switchEntries(): List<SwitchEntry> =
        resolver.pickerCandidates.map { SwitchEntry(it, it == verifiedDestination) }

    /**
     * 1C-D-04@R7: reports whether this Ready entry started a round. R7 keeps Ready
     * entry cheap (it expresses an intent to the task owner) and never restarts a
     * search that is already running for the same need.
     */
    fun onReadyEnteredForTest(): Boolean =
        settings.hasValidToken() && schedulingAllowed() && !resolver.hasVerifiedTarget

    /**
     * 1C-D-04@R4 test seam: true when an automatic (or explicit-switch) browse still
     * owns the resolution. After a switch finishes this must be false — the contract
     * forbids leaving the engine in Automatic.
     */
    val isAutomaticResolutionActiveForTest: Boolean get() = resolver.isAutomaticResolution

    /** 1C-D-04@R7 test seam: the task owner has a round in flight. */
    val isConnectionTaskRunningForTest: Boolean get() = taskOwner.isTaskRunning

    /** 1C-D-04@R7 test seam: the owner believes the app is in the foreground. */
    val ownerForegroundForTest: Boolean get() = taskOwner.isForeground

    /** 1C-D-04@R7 test seam: the intent waiting to run. */
    val ownerPendingForTest: ConnectionTaskOwner.Pending? get() = taskOwner.pendingIntent

    /** 1C-D-04@R8 test seam: what the connection loop is doing right now. */
    val ownerRoundKindForTest: ConnectionTaskOwner.RoundKind get() = taskOwner.roundKind

    /**
     * 1C-D-04@R8 test seam: true while the ViewModel believes the app is in the foreground.
     *
     * Used to pin that a transfer finishing in the background does not flip the lifecycle flag.
     */
    val appInForegroundForTest: Boolean get() = appInForeground

    /**
     * 1C-D-04@R8 test seam: the resolver's own idea of the target.
     *
     * It must always equal [currentDestination]. The R8 P0-A defect was precisely these two
     * drifting apart the moment an explicit switch started.
     */
    val resolverTargetForTest: DiscoverySelection? get() = resolver.verifiedTarget

    /**
     * Runs one resolution round and reflects its verdict.
     *
     * The verdict is applied when the bridge accepted the round AND whenever a verified target
     * exists afterwards, so a target that became valid while a superseded round was winding down
     * still reaches the UI.
     */
    private suspend fun useRound(run: suspend () -> Boolean) {
        val accepted = run()
        if (accepted || resolver.hasVerifiedTarget) applyResolverVerdict()
    }

    /**
     * One round of the task owner's SEARCH intent.
     *
     * @param force true for a full resolution (the usable state was already dropped), false for a
     *   revalidation: with a computer in use that is ONE bounded authenticated probe of the
     *   current target, which keeps the connection and the recording gate intact and never opens
     *   a browse. Only when there is nothing to revalidate does it fall through to a full search.
     */
    private suspend fun resolveAutomatically(force: Boolean) {
        if (!force && resolver.hasVerifiedTarget) {
            if (revalidateCurrentTarget()) return
            // The target stopped answering: recovery owns the rest of this round.
        }
        useRound {
            resolver.onReadyEntered(isResolvingElsewhere = coordinator?.isResolving == true)
        }
    }

    /**
     * 1C-D-04@R7 §2A — one bounded authenticated probe of the computer in use.
     *
     * Used as the idle health check and as the foreground revalidation. It probes the
     * CURRENT target (not the stored address), so a computer the user picked by hand is
     * revalidated too, and it never browses.
     *
     * @return true while the target is still authenticated.
     */
    private suspend fun probeVerifiedTarget(): Boolean {
        val current = verifiedDestination ?: return false
        val coordinatorLocal = coordinator ?: return false
        val ok = coordinatorLocal.probeCandidate(current)
        if (ok) uiEvent(WatchUiStateMachine::connectionRoundFinished)
        return ok
    }

    /**
     * 1C-D-04@R9 P0-A — applies a FAILED health probe.
     *
     * This is the consequence that must never run late: clearing the target, the UI and the
     * recording gate from a superseded round is exactly what R8's `NonCancellable` probe did. The
     * owner calls it only while that exact round is still the current, foreground one, and a probe
     * cancelled by `onBackground()` unwinds before it ever gets here.
     */
    private fun onHealthProbeFailed() {
        resolver.onTargetInvalidated()
        coordinator?.invalidate()
        setVerifiedDestination(null)
        _discovery.value = DiscoveryState.ManualFallback
        uiEvent(WatchUiStateMachine::connectionCleared)
    }

    /** Revalidate-then-recover: the SEARCH_KEEP round's own probe, failure included. */
    private suspend fun revalidateCurrentTarget(): Boolean {
        if (probeVerifiedTarget()) return true
        onHealthProbeFailed()
        return false
    }

    /**
     * 1C-D-04@R7 §2A / R8 P0-B: an intent round started.
     *
     * Only a FULL search says "connecting": it really did drop the usable state, so the screen
     * must not keep claiming a connection while it runs. A revalidation probe keeps the current
     * computer and its recording gate, so it is deliberately not presented as a connection change
     * — that is what stops an idle connected Watch from flickering between "已连接电脑" and
     * "正在连接…" every five seconds.
     */
    private fun ownerRoundStarted() {
        if (!schedulingAllowed()) return
        if (taskOwner.roundKind != ConnectionTaskOwner.RoundKind.SEARCH) return
        uiEvent(WatchUiStateMachine::connectionChecking)
    }

    /** 1C-D-04@R7 §2A: the round finished. */
    private fun ownerRoundFinished() {
        uiEvent(WatchUiStateMachine::connectionRoundFinished)
    }

    /** Cancels the connection task and every scheduled round. */
    private fun stopConnectionTask() {
        taskOwner.stop()
        uiEvent(WatchUiStateMachine::connectionRoundFinished)
    }

    /**
     * 1C-D-04@R7 §2A: the one-shot transfer chain (recording, then its upload) owns the
     * transport, so every scheduled connection round is cancelled and nothing new is
     * started until it is over. Recording must never be preempted by an idle probe.
     */
    internal fun pauseSchedulingForTransfer() {
        stopConnectionTask()
    }

    /**
     * 1C-D-04@R7/R8 test seam: reports that the one-shot transfer is over.
     *
     * It goes through the same production path the upload completion uses, so a test can pin that
     * a transfer finishing in the background resumes NOTHING and does not flip the lifecycle flag.
     */
    internal fun resumeConnectionTaskForTest() {
        resumeConnectionTask()
    }

    /**
     * 1C-D-04@R7 test seam: pauses the automatic rounds so a test can measure exactly one
     * explicit switch browse. Production pauses through the same
     * [pauseSchedulingForTransfer] call before it starts a transfer.
     */
    internal fun pauseConnectionTaskForTest() {
        stopConnectionTask()
        // 1C-D-04@R9: the production stop is asynchronous on purpose — the UI must never wait for a
        // platform teardown — so a test that is about to MEASURE the paused state waits for the
        // previous round to be really over here instead of racing it.
        taskOwner.awaitRoundSettledForTest()
    }

    /**
     * Opens the developer configuration screen.
     *
     * 1C-D-04@R7 §2B: opening settings must NOT lose the computer in use. Only the
     * in-flight resolution is cancelled; [applySettings] decides whether a submitted
     * form actually changed the connection. Reading a saved address is never a reason
     * to invalidate an authenticated target.
     */
    fun openConfig() {
        stopConnectionTask()
        resolver.onConfigEntered()
        uiEvent(WatchUiStateMachine::backToConfig)
        publishTargets()
    }

    /**
     * Stops the connection task (leaving the page / ViewModel destroyed). The
     * authenticated target is deliberately NOT dropped: the user asked to stop
     * searching, not to forget the computer in use.
     */
    fun stopDiscovery() {
        stopConnectionTask()
        resolver.onStopped()
        _discovery.value = DiscoveryState.Idle
    }

    /**
     * Explicit health check. Re-runs the authenticated discovery flow (which also
     * proves the token) instead of trusting a merely well-formed saved value.
     */
    fun checkHealth() {
        startDiscovery()
    }

    /**
     * Ready is visible during upload, but the state-machine latch makes this a
     * no-op. Repair 1 必修 2: without an authenticated destination this is an
     * explicit no-op too, so no capture can ever start before the target is
     * verified.
     */
    fun recordButtonPressed() {
        if (!_canRecord.value) return
        startRecording()
    }

    private val requestLatch = RecordingRequestLatch()

    /**
     * Starts recording on a dedicated I/O coroutine.
     *
     * Repair 1 必修 2: two independent gates must be open — the UI machine allows a
     * Ready capture, and an authenticated destination exists. While a re-probe,
     * browse, or manual probe is in flight, [canRecord] is false and this is a
     * no-op.
     */
    fun startRecording(maxDurationSec: Int = 180) {
        if (verifiedDestination == null || !_canRecord.value) return
        if (!machine.canStartRecording()) return
        // 1C-D-04@R7 §2A: recording must not be preempted by an idle health check.
        pauseSchedulingForTransfer()
        prepare()
        if (session.state != RecordingSession.State.READY) return
        session.startRecording()
        val generation = requestLatch.begin()
        recordingActive = true
        syncState()
        uiEvent(WatchUiStateMachine::recordingStarted)
        vibrate(RecordingSession.State.RECORDING)
        viewModelScope.launch {
            // I/O produces ONLY an outcome — it never touches the RecordingSession,
            // the UI, vibration, or upload from the IO dispatcher.
            val outcome = withContext(Dispatchers.IO) {
                val pcm = java.io.ByteArrayOutputStream()
                try {
                    val count = capture.record(
                        maxDurationSec * 1000,
                        { recordingActive },
                        { bytes, _ -> pcm.write(bytes) },
                        // Publish the cumulative captured sample count live so the
                        // UI can render a sample-derived duration while recording.
                        { cumulative -> _sampleCount.value = cumulative },
                    )
                    val wav = WavWriter.buildWav(pcm.toByteArray(), pcm.size())
                    RecordingOutcome.Completed(count, wav)
                } catch (e: Exception) {
                    RecordingOutcome.Failed(e.message ?: "recording failed")
                }
            }
            // Back on the main coroutine: the atomic settle gate decides whether
            // this generation may write its outcome. A Cancel (or a superseded /
            // already-settled generation) drops it silently: session untouched,
            // no auto-upload, no stop vibration.
            if (requestLatch.settle(generation)) {
                when (outcome) {
                    is RecordingOutcome.Completed -> {
                        session.recordingCompleted(outcome.samples, outcome.wav)
                        syncState()
                        vibrate(RecordingSession.State.RECORDED)
                        // Stop always auto-uploads the completed WAV (no separate Send page).
                        send()
                    }
                    is RecordingOutcome.Failed -> {
                        session.recordingFailed(outcome.reason)
                        syncState()
                    }
                }
            }
        }
    }

    /** Stops the running recording (idempotent). The capture coroutine then
     *  completes the WAV and auto-uploads it — no separate Send step. */
    fun stopRecording() {
        recordingActive = false
    }

    /**
     * Cancel on the recording screen: stop capture, discard the partial
     * recording, never upload, back to Ready.
     */
    fun cancelRecording() {
        recordingActive = false
        // Invalidate the in-flight generation FIRST: the capture coroutine's late
        // completion is then dropped by the latch (final state stays READY with
        // no WAV — never FAILURE, never an upload).
        requestLatch.cancel()
        session.reset()
        prepare()
        syncState()
        uiEvent(WatchUiStateMachine::cancelPressed)
        // The connection task was suspended for the recording; the computer in use is
        // still the verified target, so its bounded health check resumes.
        resumeConnectionTask()
    }

    /** Cross-thread stop signal (UI thread writes, Dispatchers.IO reads). */
    @Volatile
    private var recordingActive = false

    /**
     * Sends one completed WAV. There is no user-visible upload result or retry path.
     *
     * Repair 1 必修 2: a completed recording is only uploaded to
     * [currentDestination]. If the target disappeared (invalidated while the run
     * was in flight), the audio is discarded like any other transport failure —
     * it is never re-sent, so a lost response cannot duplicate a transcription.
     */
    fun send() {
        if (!session.canSend()) return
        session.beginUpload()
        syncState()
        uiEvent(WatchUiStateMachine::uploadStarted)
        val client = clientFactory()
        if (client == null) {
            session.transportFailed("cleartext HTTP sender is unavailable in this build")
            // A failed send means transport is not actually available — reflect it
            // instead of leaving the Ready state showing "available".
            setVerifiedDestination(null)
            finishSilentUpload(reDiscover = false)
            return
        }
        val wav = session.wavBytes
        if (wav == null) {
            finishSilentUpload(reDiscover = false)
            return
        }
        viewModelScope.launch {
            val dest = currentDestination()
            val result = if (dest == null) {
                TransportClient.UploadResult.Failure("no authenticated PC target for this recording")
            } else {
                withContext(Dispatchers.IO) {
                    client.upload(dest.ip, dest.port, settings.devToken, wav)
                }
            }
            val reDiscover = when (result) {
                is TransportClient.UploadResult.Success -> {
                    session.transportSucceeded()
                    false
                }
                is TransportClient.UploadResult.Failure -> {
                    session.transportFailed(result.reason)
                    // Failed upload means transport is not actually available: drop
                    // the target so the NEXT recording re-discovers. The WAV is
                    // discarded and never re-sent. Going through the resolver keeps
                    // the engine's mode/target state in step (Repair 2 必修 2).
                    resolver.onTargetInvalidated()
                    coordinator?.invalidate()
                    setVerifiedDestination(null)
                    true
                }
            }
            finishSilentUpload(reDiscover)
        }
    }

    /**
     * Destination used by one upload: ONLY the address authenticated in this
     * session (Repair 1 必修 2). There is deliberately no fallback to a saved value
     * that merely passed format validation — such a value may be stale, or after a
     * DHCP change may now belong to a different device.
     */
    fun currentDestination(): DiscoverySelection? = verifiedDestination

    /** Clears audio after either HTTP result, then revalidates capture for the next round. */
    private fun finishSilentUpload(reDiscover: Boolean) {
        resetSessionAfterSilentUpload(session)
        prepare()
        syncState()
        uiEvent(WatchUiStateMachine::uploadFinished)
        // A failed/undeliverable upload re-runs a full discovery for the *next* attempt only —
        // it never re-sends the audio that already failed.
        if (reDiscover) startDiscovery() else resumeConnectionTask()
    }

    /**
     * 1C-D-04@R7 §2A / R8 P0-C: recording and upload are finished, so the bounded health check
     * for the computer in use may resume.
     *
     * It NEVER writes the lifecycle itself: an upload that completes while the app is in the
     * background must not switch `appInForeground` back on. The real state is the only input, so a
     * background completion resumes nothing (and the next `onForeground()` revalidates).
     */
    private fun resumeConnectionTask() {
        if (!appInForeground) return
        if (!settings.hasValidToken()) return
        if (!schedulingAllowed()) return
        if (taskOwner.isForeground) {
            taskOwner.requestRefresh(ConnectionTaskOwner.Pending.SEARCH_KEEP)
        } else {
            taskOwner.resumeForeground()
        }
    }

    fun reset() {
        session.reset()
        syncState()
    }

    override fun onCleared() {
        // Never leave a browser running behind a destroyed screen, and make sure a
        // late run can no longer publish into a dead ViewModel.
        taskOwner.onDestroyed()
        resolver.onStopped()
        coordinator?.stop()
        super.onCleared()
    }

    private fun vibrate(forState: RecordingSession.State) {
        val pattern = recordingHapticPattern(forState) ?: return
        // Vibrator is resolved lazily by the UI host (needs Context); see MainActivity.
        onVibrate?.invoke(pattern)
    }

    /** Set by MainActivity with a Context-bound vibrator. */
    var onVibrate: ((LongArray) -> Unit)? = null

    /**
     * @param context application context, used to build the platform NSD browser
     *   in debug builds. `MainActivity` passes it so the browser has a real
     *   `NsdManager`; a null context (tests) yields no browser at all.
     */
    class Factory(
        private val settings: SettingsStore,
        private val context: Context? = null,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingViewModel(settings, context = context) as T
    }

}

/** Product-level cleanup: the Watch never retains audio for a retry. */
internal fun resetSessionAfterSilentUpload(session: RecordingSession) {
    session.reset()
    session.toReady()
}

/** Start and stop retain simple haptics; transport outcomes are intentionally silent. */
fun recordingHapticPattern(forState: RecordingSession.State): LongArray? = when (forState) {
    RecordingSession.State.RECORDING -> longArrayOf(0, 60)
    RecordingSession.State.RECORDED -> longArrayOf(0, 40, 60, 40)
    else -> null
}

/** Vibrates using the modern VibratorManager API (API 31+; minSdk 30 fallback below). */
fun vibratePattern(context: Context, pattern: LongArray) {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
}
