package com.sayit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sayit.watch.R
import com.sayit.watch.net.DiscoveryState
import com.sayit.watch.recording.WavWriter
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.SettingsStore
import kotlin.math.cos
import kotlin.math.sin

/** 0.2.0-dev.4 Watch presentation layer; recording and transport logic stay untouched. */
private val SayItBlue = Color(0xFF1976E9)
private val RecordingRed = Color(0xFFE14B52)
private val WarningRed = Color(0xFFE0A24B)
private val FieldSurface = Color(0xFF252A34)
private val PanelSurface = Color(0xFF20252E)
private val MutedText = Color(0xFFB5BFCC)

/** Responsive metrics: fixed dp is only used for touch targets and icon sizes. */
object WatchUiMetrics {
    val WideChipHeightDp = 52.dp
    val RowMinHeightDp = 48.dp
    val MainActionSizeDp = 92.dp
    val ScreenSidePaddingDp = 22.dp
    const val WideChipWidthFraction = 1f
    const val HalfChipWeight = 1f
    val HalfChipGapDp = 10.dp
}

/** Duration is derived by the ViewModel from captured audio data, never wall time. */
fun formatRecordingDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

/** Keeps the visible timer byte-for-byte aligned with the recorder's WAV duration rule. */
fun formatRecordingDurationFromSamples(sampleCount: Int): String =
    formatRecordingDuration(WavWriter.durationMs(sampleCount))

fun maskToken(token: String): String =
    if (token.length <= 8) "••••••••" else token.take(4) + "••••••••" + token.takeLast(4)

@Composable
private fun PillAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = SayItBlue, enabled: Boolean = true) {
    Box(
        modifier = modifier.height(WatchUiMetrics.WideChipHeightDp)
            .background(if (enabled) background else FieldSurface, RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else MutedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
    }
}

@Composable
private fun SmallIconAction(label: String, icon: IconType, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 58.dp, height = 48.dp).clickable(onClick = onClick)) {
        Canvas(Modifier.size(24.dp)) { drawIcon(icon, SayItBlue) }
        Text(label, fontSize = 10.sp, color = MutedText)
    }
}

private enum class IconType { MICROPHONE, REFRESH, SETTINGS, CLOSE }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIcon(type: IconType, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * .10f, cap = StrokeCap.Round)
    when (type) {
        IconType.MICROPHONE -> {
            drawRoundRect(color, Offset(w * .34f, h * .08f), Size(w * .32f, h * .48f), CornerRadius(w * .16f, w * .16f))
            drawArc(color, 0f, 180f, false, Offset(w * .20f, h * .28f), Size(w * .60f, h * .48f), style = stroke)
            drawLine(color, Offset(w * .50f, h * .76f), Offset(w * .50f, h * .94f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .30f, h * .94f), Offset(w * .70f, h * .94f), stroke.width, StrokeCap.Round)
        }
        IconType.REFRESH -> {
            drawArc(color, 35f, 285f, false, Offset(w * .12f, h * .12f), Size(w * .76f, h * .76f), style = stroke)
            drawLine(color, Offset(w * .80f, h * .12f), Offset(w * .88f, h * .36f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .80f, h * .12f), Offset(w * .58f, h * .17f), stroke.width, StrokeCap.Round)
        }
        IconType.SETTINGS -> {
            drawCircle(color, w * .17f, Offset(w * .50f, h * .50f), style = stroke)
            repeat(4) { index ->
                val x = if (index % 2 == 0) w * .50f else if (index == 1) w * .84f else w * .16f
                val y = if (index % 2 == 1) h * .50f else if (index == 0) h * .16f else h * .84f
                drawLine(color, Offset(w * .50f, h * .50f), Offset(x, y), stroke.width, StrokeCap.Round)
            }
            drawCircle(color, w * .09f, Offset(w * .50f, h * .50f))
        }
        IconType.CLOSE -> {
            drawLine(color, Offset(w * .25f, h * .25f), Offset(w * .75f, h * .75f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .75f, h * .25f), Offset(w * .25f, h * .75f), stroke.width, StrokeCap.Round)
        }
    }
}

@Composable
fun RecordingScreen(viewModel: RecordingViewModel, settings: SettingsStore, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val discovery by viewModel.discovery.collectAsState()
    val canRecord by viewModel.canRecord.collectAsState()
    // Delivery 1C lifecycle (Repair 2 必修 2): entering Ready only *asks* the
    // ViewModel/engine whether a resolution is needed. When a verified target
    // already exists (including right after a manual probe) the call is a no-op
    // instead of a run that would clear the target and re-discover. Leaving the
    // screen stops everything, so a late run cannot survive the page.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopDiscovery() }
    }
    LaunchedEffect(ui.screen) {
        when (ui.screen) {
            WatchUiState.Screen.READY -> viewModel.onReadyEntered()
            WatchUiState.Screen.CONFIG -> viewModel.openConfig()
            WatchUiState.Screen.RECORDING -> Unit
        }
    }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (ui.screen) {
                WatchUiState.Screen.CONFIG -> ConfigScreen(viewModel, settings, ui)
                WatchUiState.Screen.READY -> ReadyScreen(viewModel, ui, discovery, canRecord, hasPermission, onRequestPermission)
                WatchUiState.Screen.RECORDING -> RecordingActiveScreen(viewModel)
            }
        }
    }
}

@Composable
private fun ConfigScreen(viewModel: RecordingViewModel, settings: SettingsStore, ui: WatchUiState) {
    var ipText by remember { mutableStateOf(settings.receiverIp) }
    var portText by remember { mutableStateOf(settings.receiverPort) }
    var tokenText by remember { mutableStateOf(settings.devToken) }
    var tokenRevealed by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    // Delivery 1C: only the token is mandatory. The manual address fields open
    // when the user asks for them or when automatic discovery gave up.
    val manualOpen = ui.manualOpen
    val canApply = DevTokenValidator.isValid(tokenText) &&
        (!manualOpen || ipText.isBlank() || DestinationValidator.validate(ipText, portText) is DestinationValidator.ValidationResult.Valid)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WatchUiMetrics.ScreenSidePaddingDp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.screen_config_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        FieldCard(stringResource(R.string.config_dev_token), if (tokenText.isEmpty()) stringResource(R.string.config_tap_to_edit) else if (tokenRevealed) tokenText else maskToken(tokenText), if (tokenRevealed) stringResource(R.string.config_hide_token) else stringResource(R.string.config_show_token), { editingToken = true }) { tokenRevealed = !tokenRevealed }
        Spacer(Modifier.height(10.dp))
        FieldCard(
            stringResource(R.string.config_manual_settings),
            if (manualOpen) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
            onClick = { viewModel.toggleManualSettings() },
        )
        if (manualOpen) {
            Spacer(Modifier.height(8.dp))
            SettingsField(stringResource(R.string.config_pc_ip), ipText) { ipText = it }
            Spacer(Modifier.height(8.dp))
            SettingsField(stringResource(R.string.config_port), portText) { portText = it }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.config_manual_hint), fontSize = 9.sp, color = MutedText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        PillAction(stringResource(R.string.config_save_apply), { viewModel.applySettings(ipText, portText, tokenText) }, Modifier.fillMaxWidth(), enabled = canApply)
        if (!canApply) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.config_validation_hint), fontSize = 10.sp, color = MutedText, textAlign = TextAlign.Center) }
    }
    if (editingToken) WearTextInputDialog(stringResource(R.string.config_dev_token), tokenText, { tokenText = it; editingToken = false }, { editingToken = false })
}

@Composable
private fun ReadyScreen(viewModel: RecordingViewModel, ui: WatchUiState, discovery: DiscoveryState, canRecord: Boolean, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    var settingsMenuOpen by remember { mutableStateOf(false) }
    WatchDial {
        if (!hasPermission) {
            PillAction(stringResource(R.string.ready_grant_mic), onRequestPermission, Modifier.fillMaxWidth().padding(horizontal = 40.dp))
        } else {
            // Keep the original minimal dial; discovery actions live behind Settings.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.dial_title_ready),
                    fontSize = 9.sp,
                    color = DialMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                // Line microphone: largest element, dead center. Repair 1 必修 2:
                // disabled (dimmed, non-clickable) until an endpoint authenticated.
                Box(
                    Modifier.size(96.dp).clickable(enabled = canRecord) { viewModel.recordButtonPressed() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(72.dp)) {
                        drawIcon(IconType.MICROPHONE, if (canRecord) DialIcon else DialMuted)
                    }
                }
                Spacer(Modifier.height(2.dp))
                // Transport-only discovery status; a saved-address re-probe is never
                // worded as a new automatic discovery (R5 必修 3).
                Text(
                    stringResource(readyStatusTextRes(discovery, ui.transportAvailable == true)),
                    fontSize = 8.sp,
                    color = if (discovery is DiscoveryState.ManualFallback) WarningRed else DialMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                if (ui.targets.isEmpty() && !ui.switchSearching) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ready_search_hint),
                        fontSize = 8.sp,
                        color = DialMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // One low-key entry on the dial. Opening this menu does not call
                // openConfig(), which would invalidate the verified current PC.
                Text(
                    stringResource(R.string.ready_open_config),
                    fontSize = 9.sp,
                    color = DialMuted,
                    modifier = Modifier.heightIn(min = WatchUiMetrics.RowMinHeightDp)
                        .clickable(enabled = !ui.isUploading) { settingsMenuOpen = true },
                )
            }
        }
        if (settingsMenuOpen) {
            ReadySettingsMenu(
                ui = ui,
                onDismiss = { settingsMenuOpen = false },
                onSwitch = {
                    settingsMenuOpen = false
                    viewModel.requestSwitch()
                },
                onConnectionSettings = {
                    settingsMenuOpen = false
                    viewModel.openConfig()
                },
            )
        }
        if (ui.switchOpen) {
            ComputerSwitchDialog(viewModel, ui)
        }
    }
}

@Composable
private fun ReadySettingsMenu(
    ui: WatchUiState,
    onDismiss: () -> Unit,
    onSwitch: () -> Unit,
    onConnectionSettings: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth(0.82f)
                .verticalScroll(rememberScrollState())
                .background(PanelSurface, RoundedCornerShape(18.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.ready_open_config), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            PillAction(
                stringResource(switchEntryLabelRes(ui.switchSearching, ui.targets.isNotEmpty())),
                onSwitch,
                Modifier.fillMaxWidth(),
                background = SayItBlue,
                enabled = !ui.switchSearching && !ui.isUploading,
            )
            Spacer(Modifier.height(8.dp))
            PillAction(stringResource(R.string.screen_config_title), onConnectionSettings, Modifier.fillMaxWidth(), background = FieldSurface)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = WatchUiMetrics.RowMinHeightDp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.action_cancel), fontSize = 9.sp, color = MutedText)
            }
        }
    }
}

/**
 * 1C-D-04@R5 / 1C-PM-UI-01@R1 — is search/switch reachable from Ready?
 *
 * A pure decision so the invariant is JVM-testable without Compose rendering: the
 * The Settings menu entry must exist for **every** Ready state, including the normal
 * "one saved computer is online" case. R4 gated it on `targets.size > 1`.
 */
internal fun searchEntryAvailable(ui: WatchUiState): Boolean = ui.screen == WatchUiState.Screen.READY

/** 1C-D-04@R5: the entry's label resource for the current state. */
internal fun switchEntryLabelRes(searching: Boolean, hasKnownTargets: Boolean): Int = when {
    searching -> R.string.ready_entry_searching
    hasKnownTargets -> R.string.ready_switch_computer
    else -> R.string.ready_search_computer
}

/**
 * 1C-D-04@R5/R6 必修 3 — the Ready status wording.
 *
 * R5 mapped `Discovered` to "已自动发现电脑". The real-device run proved that is a
 * false statement on the user's screen: a cold start whose log contains only
 * `saved-probe:accepted` / `verdict:one` (no `browse:*`) still reaches the UI as
 * `Discovered`, because `ResolverBridge.routeAutomatic()` normalises every verified
 * run to `Discovered` and `RecordingViewModel.applyResolverVerdict()` publishes
 * `Discovered` for the saved-address verdict as well. The UI therefore cannot tell a
 * fresh browse from a saved-address re-probe.
 *
 * R6 fix (UI-only, no protocol change): an authenticated, usable target is ALWAYS
 * described neutrally as "已连接电脑". Only non-ready states keep their specific
 * wording, and none of them may claim a discovery. This is deliberately fail-safe: if
 * the internal source tracking is ever fixed, this mapping does not have to change
 * before the wording becomes truthful again.
 */
internal fun readyStatusTextRes(state: DiscoveryState, hasVerifiedTarget: Boolean): Int = when (state) {
    is DiscoveryState.Searching -> R.string.discovery_searching
    // Neutral for BOTH branches: while the UI cannot distinguish a fresh browse from a
    // saved-address re-probe, it must not claim one. `discovery_found` is intentionally
    // NOT reachable from here.
    is DiscoveryState.Discovered ->
        if (hasVerifiedTarget) R.string.discovery_saved_neutral else R.string.discovery_awaiting
    is DiscoveryState.ExistingAddress ->
        if (hasVerifiedTarget) R.string.discovery_saved_neutral else R.string.discovery_awaiting
    is DiscoveryState.ManualFallback -> R.string.discovery_manual
    is DiscoveryState.Idle ->
        if (hasVerifiedTarget) R.string.discovery_saved_neutral else R.string.discovery_none_yet
}

/**
 * 1C-D-04@R2 — the explicit "switch computer" picker.
 *
 * Shows the 局域网 address of each computer purely so the user can tell two PCs
 * apart; the address is never logged, never broadcast and never sent anywhere.
 * Only endpoints that passed the authenticated Bearer probe can appear here, and a
 * manual address field is deliberately absent: switching must not require typing an
 * address.
 */
@Composable
private fun ComputerSwitchDialog(viewModel: RecordingViewModel, ui: WatchUiState) {
    Dialog(onDismissRequest = { viewModel.dismissSwitchPicker() }) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(PanelSurface, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.switch_dialog_title), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.switch_dialog_hint),
                fontSize = 9.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            ui.targets.forEach { target ->
                val isCurrent = target == ui.currentTarget
                Column(
                    Modifier.fillMaxWidth()
                        .selectable(selected = isCurrent, onClick = { viewModel.onTargetPicked(target) })
                        .background(if (isCurrent) SayItBlue else FieldSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(
                            if (isCurrent) R.string.switch_current_computer else R.string.switch_other_computer,
                        ),
                        fontSize = 9.sp,
                        color = if (isCurrent) Color.White else MutedText,
                    )
                    Text("${target.ip}:${target.port}", fontSize = 13.sp, color = Color.White, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
            if (ui.switchSearching) {
                Text(
                    stringResource(R.string.switch_searching),
                    fontSize = 9.sp,
                    color = SayItBlue,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
            }
            PillAction(stringResource(R.string.switch_search_again), {
                viewModel.requestSwitch()
            }, Modifier.fillMaxWidth(), background = SayItBlue, enabled = !ui.switchSearching)
            Spacer(Modifier.height(8.dp))
            PillAction(stringResource(R.string.action_cancel), { viewModel.dismissSwitchPicker() }, Modifier.fillMaxWidth(), background = FieldSurface)
        }
    }
}

/**
 * Transport-only discovery wording; never claims Provider/ASR readiness.
 *
 * 1C-D-04@R5 必修 3: `ExistingAddress` is the SAVED address answering the probe
 * again — it must not be worded as a new automatic discovery, and nothing here may
 * claim mDNS succeeded. [hasVerifiedTarget] still distinguishes "no address yet"
 * from "an address was verified in this session".
 */
internal fun discoveryLabel(state: DiscoveryState, hasVerifiedTarget: Boolean): String = when (state) {
    is DiscoveryState.Searching -> "正在搜索电脑…"
    is DiscoveryState.Discovered -> if (hasVerifiedTarget) "已自动发现电脑" else "等待认证结果…"
    is DiscoveryState.ExistingAddress -> if (hasVerifiedTarget) "已连接电脑" else "等待认证结果…"
    is DiscoveryState.ManualFallback -> "未找到电脑，点此搜索或手动设置地址"
    is DiscoveryState.Idle -> if (hasVerifiedTarget) "已连接电脑" else "尚未找到电脑"
}

@Composable
private fun RecordingActiveScreen(viewModel: RecordingViewModel) {
    val sampleCount by viewModel.sampleCount.collectAsState()
    WatchDial {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.dial_title_recording), fontSize = 9.sp, color = DialMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.Center).offset(y = -56.dp))
            RecordingWaveform(SayItBlue, Modifier.size(width = 156.dp, height = 46.dp).align(Alignment.Center).clickable { viewModel.stopRecording() })
            Text(formatRecordingDurationFromSamples(sampleCount), fontSize = 20.sp, color = DialIcon, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).offset(y = 52.dp))
            Text(stringResource(R.string.recording_cancel_hint), fontSize = 8.sp, color = DialMuted, modifier = Modifier.align(Alignment.Center).offset(y = 84.dp).clickable { viewModel.cancelRecording() })
        }
    }
}

// ─── Watch dial (card) presentation ───────────────────────────────────────────

private val DialFace = Color(0xFFF6F7F9)
private val DialTick = Color(0xFF1C1E22)
private val DialIcon = Color(0xFF1C1E22)
private val DialMuted = Color(0xFF9AA3AE)

@Composable
private fun WatchDial(content: @Composable BoxScope.() -> Unit) {
    // Galaxy Watch 7 (SM-L310): 480×480 px @ density 340 → dial ≈ 226dp, radius ≈ 113dp.
    // The dial face fills the round screen; ticks sit just inside the bezel.
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f
            drawCircle(DialFace, radius, center)
            drawDialTicks(center, radius)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

private fun DrawScope.drawDialTicks(center: Offset, radius: Float) {
    // 60 uniform minute ticks in gray, hugging the bezel; 4 longer/thicker blue
    // marks at 12/3/6/9 o'clock.
    val outer = radius * 0.99f
    val minuteInner = radius * 0.95f
    val majorInner = radius * 0.89f
    repeat(60) { i ->
        val angle = i * 6.0 * Math.PI / 180.0
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val major = i % 15 == 0
        val end = center + dir * (if (major) majorInner else minuteInner)
        drawLine(
            color = if (major) SayItBlue else DialMuted,
            start = center + dir * outer,
            end = end,
            strokeWidth = if (major) radius * 0.030f else radius * 0.008f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun RecordingWaveform(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
    )
    Canvas(modifier) {
        // 11 symmetric bars: center tallest, tapering to the sides, thin round caps.
        val barCount = 11
        val maxAmp = size.height * 0.42f
        val midY = size.height / 2f
        val gap = size.width / barCount
        val barWidth = gap * 0.30f
        val phaseRad = phase.toDouble() * Math.PI
        for (i in 0 until barCount) {
            val envelope = (1.0 - kotlin.math.abs(i - (barCount - 1) / 2.0) / ((barCount - 1) / 2.0)).toFloat()
            val wave = (sin(phaseRad + i.toDouble() * 0.7) * 0.5 + 0.5).toFloat()
            val amp = maxAmp * (0.18f + 0.82f * envelope) * (0.55f + 0.45f * wave)
            val x = gap * i + gap / 2f
            drawLine(color, Offset(x, midY - amp), Offset(x, midY + amp), barWidth, StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    FieldCard(label, if (value.isEmpty()) stringResource(R.string.config_tap_to_edit) else value, onClick = { editing = true })
    if (editing) WearTextInputDialog(label, value, { onChange(it); editing = false }, { editing = false })
}

@Composable
private fun FieldCard(label: String, value: String, trailing: String? = null, onClick: () -> Unit, onTrailingClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).background(FieldSurface, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 10.sp, color = MutedText); Text(value, fontSize = 13.sp, maxLines = 1) }
        if (trailing != null && onTrailingClick != null) Text(trailing, fontSize = 11.sp, color = SayItBlue, modifier = Modifier.padding(start = 8.dp).clickable(onClick = onTrailingClick))
    }
}

@Composable
private fun WearTextInputDialog(label: String, initialValue: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PanelSurface, RoundedCornerShape(18.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 13.sp, color = MutedText); Spacer(Modifier.height(8.dp))
            BasicTextField(value = text, onValueChange = { text = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onConfirm(text) }), modifier = Modifier.fillMaxWidth().background(FieldSurface, RoundedCornerShape(10.dp)).padding(10.dp).focusRequester(focusRequester))
            Spacer(Modifier.height(12.dp))
            PillAction(stringResource(R.string.action_ok), { keyboard?.hide(); onConfirm(text) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PillAction(stringResource(R.string.action_cancel), { keyboard?.hide(); onDismiss() }, Modifier.fillMaxWidth(), background = FieldSurface)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
}
