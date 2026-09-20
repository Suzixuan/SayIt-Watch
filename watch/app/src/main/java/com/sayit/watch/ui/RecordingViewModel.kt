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
            set(state.copy(transportAvailable = false))
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
        set(state.copy(transportAvailable = available))
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


    init {
        // Startup rule: a valid saved token is enough to reach Ready. The address
        // is re-probed / discovered there instead of being a manual prerequisite.
        // Until that completes, `transportAvailable` is false and recording is a
        // no-op (Repair 1 必修 2).
        uiEvent { it.startupWith(settings.hasValidToken()) }
    }

    private fun syncUi() {
        _ui.value = machine.state
    }

    private fun uiEvent(event: (WatchUiStateMachine) -> Unit) {
        event(machine)
        syncUi()
    }

    /**
     * Records the authenticated target and publishes it to the UI.
     *
     * Repair 1 必修 2/3: this is the single point where an upload target comes into
     * existence, and it is only ever called with an endpoint that authenticated —
     * either the coordinator's exactly-one verdict or a successful manual probe.
     */
    private fun setVerifiedDestination(selection: DiscoverySelection?) {
        verifiedDestination = selection
        _canRecord.value = selection != null
        if (selection != null) {
            uiEvent(WatchUiStateMachine::destinationResolved)
        } else {
            uiEvent { it.healthChecked(false) }
        }
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
     * Only the token is required. A manual destination is NEVER persisted here:
     * it is only adopted after the authenticated probe succeeds (Repair 1 必修 2),
     * so a typo, a stale address, or an address now owned by another device can
     * never become an upload target.
     */
    fun applySettings(ip: String, port: String, token: String) {
        resolver.onConfigEntered()
        setVerifiedDestination(null)
        settings.saveDevToken(token.trim())
        if (!settings.hasValidToken()) return

        val manual = DestinationValidator.validate(ip, port)
        prepare()
        if (manual is DestinationValidator.ValidationResult.Valid) {
            // Repair 2 必修 2: the manual probe owns the resolution. It cancels and
            // stops any automatic run first (the bridge does that), and the target
            // only appears when the probe authenticated.
            val candidate = DiscoverySelection(manual.ip, manual.port)
            uiEvent(WatchUiStateMachine::searchingStarted)
            viewModelScope.launch {
                if (!resolver.onManualRequested(candidate)) return@launch
                if (resolver.verifiedTarget == candidate) {
                    _discovery.value = DiscoveryState.Discovered
                    uiEvent(WatchUiStateMachine::settingsApplied)
                    setVerifiedDestination(candidate)
                } else {
                    _discovery.value = DiscoveryState.ManualFallback
                    uiEvent(WatchUiStateMachine::discoveryFailed)
                }
            }
        } else {
            // No (valid) manual address: let authenticated discovery decide.
            uiEvent(WatchUiStateMachine::settingsApplied)
            startDiscovery()
        }
    }

    /** Delivery 1C: reveal/hide the manual IP + port section. */
    fun toggleManualSettings() {
        uiEvent(WatchUiStateMachine::manualSettingsToggled)
    }

    /**
     * Ready entry point (Repair 2 必修 2).
     *
     * Automatic discovery starts ONLY when there is no verified target and no
     * resolution in flight. Returning from Recording with a still-valid target, or
     * arriving right after a successful manual probe, is therefore a no-op instead
     * of a run that would clear the target and re-discover.
     */
    fun onReadyEntered() {
        if (!settings.hasValidToken()) return
        // Reflect a search only when this call is actually going to start one.
        if (!resolver.acceptsAutomatic(coordinator?.isResolving == true)) {
            // A verified target already exists: make sure the UI reflects it.
            resolver.verifiedTarget?.let { setVerifiedDestination(it) }
            return
        }
        uiEvent(WatchUiStateMachine::searchingStarted)
        viewModelScope.launch { resolveAutomatically() }
    }

    /**
     * Explicit (re)resolution: the upload-failure path and the health check.
     *
     * Repair 2 必修 2: cancels any run first so the new one is the only owner, then
     * delegates to the same bridge as the Ready entry point.
     */
    fun startDiscovery() {
        if (!settings.hasValidToken()) {
            uiEvent(WatchUiStateMachine::discoveryFailed)
            return
        }
        resolver.onTargetInvalidated()
        setVerifiedDestination(null)
        coordinator?.invalidate()
        _discovery.value = DiscoveryState.Searching
        uiEvent(WatchUiStateMachine::searchingStarted)
        viewModelScope.launch { resolveAutomatically() }
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
        if (resolver.lastPublishedState is DiscoveryState.ManualFallback) {
            _discovery.value = DiscoveryState.ManualFallback
            setVerifiedDestination(null)
            uiEvent(WatchUiStateMachine::discoveryFailed)
        }
    }

    /**
     * 1C-D-04@R2/R3 — the explicit "switch computer" entry point.
     *
     * 1C-D-04@R3 必修 1: the picker's list and its membership check are owned by the
     * resolver bridge, and the bridge's own `handleSwitchBrowse()` merges the browse
     * result BEFORE returning it. The first switch can therefore never be rejected
     * by a stale list.
     *
     * 1C-D-04@R3 必修 3: exactly ONE bounded browse happens here. R2 ran a full
     * automatic resolution (up to 3 s saved-address re-probe + 8 s browse) first and
     * then browsed another 8 s; the user's explicit switch is one bounded search.
     */
    fun requestSwitch() {
        if (!settings.hasValidToken()) return
        if (resolver.isManualResolution) return
        resolver.openPicker()
        uiEvent(WatchUiStateMachine::switchOpened)
        publishTargets()
        viewModelScope.launch {
            _discovery.value = DiscoveryState.Searching
            uiEvent { it.switchSearching(true) }
            val browseRunId = resolver.newSwitchRun()
            // One bounded browse, bypassing the saved-address priority.
            val found = resolver.handleSwitchBrowse(browseRunId)
            refreshStageTrail()
            uiEvent { it.switchSearching(false) }
            if (!resolver.isCurrentSwitchRun(browseRunId)) {
                // A newer run (or the user's own pick) superseded this browse.
                _discovery.value = if (resolver.hasVerifiedTarget) {
                    DiscoveryState.Discovered
                } else {
                    DiscoveryState.ManualFallback
                }
                return@launch
            }
            if (found != null) {
                // 1C-D-04@R3 必修 1: the bridge already merged `found` into the
                // authenticated list BEFORE returning it, so the picker's own
                // membership check accepts it and the adoption (target + persistence)
                // takes the exact same path as a manual pick. R2 called the picker with
                // a list that had not been updated yet, which rejected the user's very
                // first switch.
                onTargetPicked(found)
                return@launch
            }
            // 0 or ≥2 new computers: nothing is selected. Refresh the picker so the
            // user can choose among the authenticated ones, retry, or fall back.
            _discovery.value =
                if (resolver.hasVerifiedTarget) DiscoveryState.Discovered else DiscoveryState.ManualFallback
            publishTargets()
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
        val accepted = resolver.pick(candidate) ?: run {
            // Not an authenticated computer: refuse and keep the current target.
            uiEvent(WatchUiStateMachine::switchClosed)
            return
        }
        applyTargetChoice(accepted)
    }

    /** Closes the picker without changing the target. */
    fun dismissSwitchPicker() {
        resolver.cancelPicker()
        uiEvent(WatchUiStateMachine::switchClosed)
    }

    /**
     * 1C-D-04@R3: reports whether this Ready entry actually started a run.
     *
     * Kept separate from `onReadyEntered()` (whose declaration is source-guarded by
     * `RecordingEntryGuardTest`) so a JVM test can assert "a verified target makes
     * Ready entry a no-op" without duplicating the decision logic.
     */
    fun onReadyEnteredForTest(): Boolean =
        settings.hasValidToken() && resolver.acceptsAutomatic(coordinator?.isResolving == true)

    /**
     * 1C-D-04@R4 test seam: true when an automatic (or explicit-switch) browse still
     * owns the resolution. After a switch finishes this must be false — the contract
     * forbids leaving the engine in Automatic.
     */
    val isAutomaticResolutionActiveForTest: Boolean get() = resolver.isAutomaticResolution

    /**
     * Runs automatic discovery through the bridge, then reflects its verdict.
     *
     * The generation gate lives in [ResolverBridge]/[ResolverEngine], so a run that
     * was superseded (Config opened, manual submitted, upload failure, teardown)
     * neither publishes a verdict nor writes settings.
     */
    private suspend fun resolveAutomatically() {
        if (resolver.onReadyEntered(isResolvingElsewhere = coordinator?.isResolving == true)) {
            applyResolverVerdict()
        }
    }

    /**
     * Opens the developer configuration screen. Repair 2 必修 2: an automatic run
     * no longer owns the resolution, so it is cancelled and cannot publish later.
     */
    fun openConfig() {
        resolver.onConfigEntered()
        setVerifiedDestination(null)
        uiEvent(WatchUiStateMachine::backToConfig)
    }

    /** Stops browsing (leaving the page / ViewModel destroyed). Idempotent. */
    fun stopDiscovery() {
        resolver.onStopped()
        setVerifiedDestination(null)
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
        // A failed/undeliverable upload re-runs discovery for the *next* attempt
        // only — it never re-sends the audio that already failed.
        if (reDiscover) startDiscovery()
    }

    fun reset() {
        session.reset()
        syncState()
    }

    override fun onCleared() {
        // Never leave a browser running behind a destroyed screen, and make sure a
        // late run can no longer publish into a dead ViewModel.
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
