package com.sayit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sayit.watch.R
import com.sayit.watch.net.DiscoverySelection
import com.sayit.watch.net.DiscoveryState
import com.sayit.watch.recording.WavWriter
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.ComputerAliasPolicy
import com.sayit.watch.settings.LowPowerRecordingPolicy
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

private enum class IconType { MICROPHONE, REFRESH, SETTINGS, CLOSE, SWITCH, SLIDERS }

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
        IconType.SWITCH -> {
            drawLine(color, Offset(w * .16f, h * .32f), Offset(w * .84f, h * .32f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .72f, h * .18f), Offset(w * .84f, h * .32f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .72f, h * .46f), Offset(w * .84f, h * .32f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .84f, h * .68f), Offset(w * .16f, h * .68f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .28f, h * .54f), Offset(w * .16f, h * .68f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .28f, h * .82f), Offset(w * .16f, h * .68f), stroke.width, StrokeCap.Round)
        }
        IconType.SLIDERS -> {
            drawLine(color, Offset(w * .12f, h * .33f), Offset(w * .88f, h * .33f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .12f, h * .67f), Offset(w * .88f, h * .67f), stroke.width, StrokeCap.Round)
            drawCircle(color, w * .10f, Offset(w * .40f, h * .33f))
            drawCircle(color, w * .10f, Offset(w * .68f, h * .67f))
        }
    }
}

@Composable
fun RecordingScreen(viewModel: RecordingViewModel, settings: SettingsStore, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val discovery by viewModel.discovery.collectAsState()
    val canRecord by viewModel.canRecord.collectAsState()
    // Delivery 1C lifecycle (Repair 2 必修 2, extended by 1C-D-04@R7 §2A): entering Ready
    // only *asks* the ViewModel/engine whether a resolution is needed; the single task
    // owner decides, keeps retrying while the screen stays disconnected, and is driven by
    // the Activity's real foreground state. Leaving the composition stops the round in
    // flight, which is also what clears the "searching" row if the screen goes away mid
    // browse — the authenticated computer in use deliberately survives it.
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
                WatchUiState.Screen.READY -> ReadyScreen(viewModel, settings, ui, discovery, canRecord, hasPermission, onRequestPermission)
                WatchUiState.Screen.RECORDING -> RecordingActiveScreen(
                    viewModel,
                    settings.lowPowerAfterSeconds,
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(viewModel: RecordingViewModel, settings: SettingsStore, ui: WatchUiState) {
    var ipText by remember { mutableStateOf(settings.receiverIp) }
    var portText by remember { mutableStateOf(settings.receiverPort) }
    var tokenText by remember { mutableStateOf(settings.devToken) }
    var lowPowerAfterText by remember { mutableStateOf(settings.lowPowerAfterSeconds.toString()) }
    var tokenRevealed by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    // Delivery 1C: only the token is mandatory. The manual address fields open
    // when the user asks for them or when automatic discovery gave up.
    val manualOpen = ui.manualOpen
    val lowPowerAfterSeconds = LowPowerRecordingPolicy.parseSeconds(lowPowerAfterText)
    val canApply = DevTokenValidator.isValid(tokenText) && lowPowerAfterSeconds != null &&
        (!manualOpen || ipText.isBlank() || DestinationValidator.validate(ipText, portText) is DestinationValidator.ValidationResult.Valid)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WatchUiMetrics.ScreenSidePaddingDp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.screen_config_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        FieldCard(stringResource(R.string.config_dev_token), if (tokenText.isEmpty()) stringResource(R.string.config_tap_to_edit) else if (tokenRevealed) tokenText else maskToken(tokenText), if (tokenRevealed) stringResource(R.string.config_hide_token) else stringResource(R.string.config_show_token), { editingToken = true }) { tokenRevealed = !tokenRevealed }
        Spacer(Modifier.height(10.dp))
        SettingsField(
            stringResource(R.string.config_low_power_after),
            lowPowerAfterText,
            keyboardType = KeyboardType.Number,
        ) { lowPowerAfterText = it.filter(Char::isDigit) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.config_low_power_hint), fontSize = 9.sp, color = MutedText, textAlign = TextAlign.Center)
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
        PillAction(
            stringResource(R.string.config_save_apply),
            {
                settings.lowPowerAfterSeconds = checkNotNull(lowPowerAfterSeconds)
                viewModel.applySettings(ipText, portText, tokenText)
            },
            Modifier.fillMaxWidth(),
            enabled = canApply,
        )
        if (!canApply) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (lowPowerAfterSeconds == null) R.string.config_low_power_validation
                    else R.string.config_validation_hint,
                ),
                fontSize = 10.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (editingToken) WearTextInputDialog(stringResource(R.string.config_dev_token), tokenText, { tokenText = it; editingToken = false }, { editingToken = false })
}

@Composable
private fun ReadyScreen(viewModel: RecordingViewModel, settings: SettingsStore, ui: WatchUiState, discovery: DiscoveryState, canRecord: Boolean, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    var settingsMenuOpen by remember { mutableStateOf(false) }
    WatchDial {
        if (!hasPermission) {
            PillAction(stringResource(R.string.ready_grant_mic), onRequestPermission, Modifier.fillMaxWidth().padding(horizontal = 40.dp))
        } else {
            // Match the frozen dev.3 dial: title above a centered microphone, with
            // transport status and the low-key Settings entry below it. A centered
            // column moved both title and microphone noticeably upward on the Watch.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.dial_title_ready),
                    fontSize = 9.sp,
                    color = DialMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.23f),
                )
                // Line microphone: largest element, dead center. Repair 1 必修 2:
                // disabled (dimmed, non-clickable) until an endpoint authenticated.
                Box(
                    Modifier.align(Alignment.Center).size(96.dp)
                        .clickable(enabled = canRecord) { viewModel.recordButtonPressed() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(72.dp)) {
                        drawIcon(IconType.MICROPHONE, if (canRecord) DialIcon else DialMuted)
                    }
                }
                // Transport-only discovery status; a saved-address re-probe is never
                // worded as a new automatic discovery (R5 必修 3), and a round that is
                // still checking never reuses the previous authentication (R7 §2A).
                val statusLabelRes = readyStatusTextRes(
                    discovery,
                    connected = ui.connected,
                    connecting = ui.connecting,
                )
                Text(
                    stringResource(statusLabelRes),
                    fontSize = 8.sp,
                    color = if (discovery is DiscoveryState.ManualFallback) WarningRed else DialMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .offset(
                            x = if (statusLabelRes == R.string.discovery_saved_neutral) (-2).dp else 0.dp,
                            y = maxHeight * 0.68f,
                        )
                        .padding(horizontal = 30.dp),
                )
                // One low-key entry on the dial. Opening this menu does not call
                // openConfig(), which would invalidate the verified current PC.
                Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = (-22).dp)
                        .width(96.dp).heightIn(min = WatchUiMetrics.RowMinHeightDp)
                        .clickable(enabled = !ui.isUploading) { settingsMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.ready_open_config), fontSize = 9.sp, color = DialMuted)
                }
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
            ComputerSwitchDialog(viewModel, settings, ui)
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
    val face = Color(0xFF151A22)
    val card = Color(0xFF262D38)
    val quiet = Color(0xFFA4ADBA)
    val accent = Color(0xFF3488F5)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(face)) {
            Text(
                stringResource(R.string.ready_open_config),
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .10f),
            )
            Text(
                stringResource(R.string.settings_section_title),
                fontSize = 8.sp,
                color = quiet,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .19f),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = maxWidth * .11f, top = maxHeight * .06f)
                    .size(48.dp).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) { drawIcon(IconType.CLOSE, quiet) }
            }
            SettingsMenuAction(
                title = stringResource(switchEntryLabelRes(ui.switchSearching, ui.targets.isNotEmpty())),
                detail = stringResource(R.string.settings_switch_hint),
                icon = IconType.SWITCH,
                iconColor = accent,
                cardColor = card,
                detailColor = quiet,
                onClick = onSwitch,
                enabled = !ui.switchSearching && !ui.isUploading,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * .296f).fillMaxWidth(.68f),
            )
            SettingsMenuAction(
                title = stringResource(R.string.screen_config_title),
                detail = stringResource(R.string.settings_connection_hint),
                icon = IconType.SLIDERS,
                iconColor = quiet,
                cardColor = card,
                detailColor = quiet,
                onClick = onConnectionSettings,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset(y = maxHeight * .55f).fillMaxWidth(.68f),
            )
        }
    }
}

@Composable
private fun SettingsMenuAction(
    title: String,
    detail: String,
    icon: IconType,
    iconColor: Color,
    cardColor: Color,
    detailColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.height(52.dp).background(cardColor, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(if (icon == IconType.SWITCH) Color(0xFF1B2B43) else Color(0xFF313946), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(15.dp)) { drawIcon(icon, iconColor) }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp, color = if (enabled) Color.White else detailColor, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 7.sp, color = detailColor, maxLines = 1)
        }
        Canvas(Modifier.size(10.dp)) {
            drawLine(detailColor, Offset(size.width * .2f, size.height * .1f), Offset(size.width * .75f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(detailColor, Offset(size.width * .75f, size.height * .5f), Offset(size.width * .2f, size.height * .9f), 2.dp.toPx(), StrokeCap.Round)
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
 * 1C-D-04@R5/R6/R7 必修 3 — the Ready status wording.
 *
 * R5 mapped `Discovered` to "已自动发现电脑". The real-device run proved that is a
 * false statement on the user's screen: a cold start whose log contains only
 * `saved-probe:accepted` / `verdict:one` (no `browse:*`) still reaches the UI as
 * `Discovered`, because `ResolverBridge.routeAutomatic()` normalises every verified
 * run to `Discovered`. The UI therefore cannot tell a fresh browse from a
 * saved-address re-probe, so it never claims one.
 *
 * 1C-D-04@R7 §2A adds the connection state to the same projection:
 * - `connecting` is a round of the task owner being in flight, so the screen says
 *   "正在连接…" instead of reusing the previous authentication;
 * - wording is only positive while the connection is actually usable AND no round is
 *   re-checking it.
 */
internal fun readyStatusTextRes(
    state: DiscoveryState,
    connected: Boolean,
    connecting: Boolean,
): Int = when {
    state is DiscoveryState.Searching || connecting -> R.string.discovery_searching
    // A fallback never claims a live connection: the round ended without a usable
    // computer, so the screen names the next action instead.
    state is DiscoveryState.ManualFallback -> R.string.discovery_manual
    connected -> R.string.discovery_saved_neutral
    state is DiscoveryState.Discovered -> R.string.discovery_awaiting
    state is DiscoveryState.ExistingAddress -> R.string.discovery_awaiting
    else -> R.string.discovery_none_yet
}

/** Human-readable fallback until an authenticated desktop supplies an optional nickname. */
internal fun computerFallbackName(ip: String): String {
    val octets = ip.split('.')
    val suffix = octets.takeIf {
        it.size == 4 && it.all { octet -> octet.toIntOrNull()?.let { value -> value in 0..255 } == true }
    }?.last()
    return suffix?.let { "电脑 · $it" } ?: "电脑"
}

/**
 * 1C-PM-UI-02@R1 — the explicit "switch computer" picker.
 *
 * A machine-like `IP:port` is no longer the visual identity. Until the authenticated
 * desktop response carries optional friendly metadata, each real RFC1918 endpoint gets a
 * deterministic fallback name (`电脑 · 142`) and the IP remains secondary diagnostics.
 *
 * Authenticated progress candidates are selectable immediately, but only an explicit tap changes
 * the selected target. While this dialog is visible, bounded live-presence cycles add and remove
 * non-current rows; the selected row stays pinned and can be shown offline.
 */
@Composable
private fun ComputerSwitchDialog(viewModel: RecordingViewModel, settings: SettingsStore, ui: WatchUiState) {
    var editingTarget by remember { mutableStateOf<DiscoverySelection?>(null) }
    var aliasRevision by remember { mutableStateOf(0) }
    val current = ui.currentTarget
    val connected = ui.connected && ui.transportAvailable == true
    val entries = viewModel.switchEntries()
    val rows = if (entries.isEmpty() && current != null) {
        listOf(SwitchEntry(current, true, connected))
    } else {
        entries
    }
    val availableCount = rows.count { it.isOnline && (!it.isCurrent || connected) }
    val noCandidate = !ui.switchSearching && availableCount == 0

    Dialog(
        onDismissRequest = {
            if (editingTarget != null) editingTarget = null else viewModel.dismissSwitchPicker()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val targetBeingEdited = editingTarget
        if (targetBeingEdited != null) {
            val savedAlias = remember(targetBeingEdited, aliasRevision) {
                settings.computerAlias(targetBeingEdited.ip, targetBeingEdited.port)
            }
            ComputerCustomizationPane(
                target = targetBeingEdited,
                savedAlias = savedAlias,
                onSave = { value ->
                    settings.setComputerAlias(targetBeingEdited.ip, targetBeingEdited.port, value)
                    aliasRevision += 1
                    editingTarget = null
                },
                onReset = {
                    settings.setComputerAlias(targetBeingEdited.ip, targetBeingEdited.port, "")
                    aliasRevision += 1
                    editingTarget = null
                },
                onClose = { editingTarget = null },
            )
        } else Box(Modifier.fillMaxSize().background(Color(0xFF12161D))) {
            Column(
                Modifier.fillMaxSize()
                    .padding(start = 12.dp, top = 7.dp, end = 12.dp, bottom = 60.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.switch_dialog_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Text(
                    when {
                        ui.switchSearching -> stringResource(R.string.switch_searching_count, availableCount)
                        noCandidate -> stringResource(R.string.discovery_none_yet)
                        else -> stringResource(R.string.switch_available_count, availableCount)
                    },
                    fontSize = 9.sp,
                    color = if (noCandidate) WarningRed else MutedText,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(5.dp))
                rows.forEach { entry ->
                    SwitchComputerCard(
                        entry = entry,
                        displayName = remember(entry.target, aliasRevision) {
                            settings.computerAlias(entry.target.ip, entry.target.port)
                                .ifBlank { computerFallbackName(entry.target.ip) }
                        },
                        connected = connected,
                        searching = ui.switchSearching,
                        onClick = { viewModel.onTargetPicked(entry.target) },
                        onEdit = { editingTarget = entry.target },
                    )
                    Spacer(Modifier.height(5.dp))
                }
                SwitchRefreshAction(
                    label = stringResource(
                        if (ui.switchSearching) R.string.switch_restart_search else R.string.switch_search_again,
                    ),
                    onClick = { viewModel.requestSwitch() },
                )
            }
            SwitchCloseAction(
                onClick = { viewModel.dismissSwitchPicker() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SwitchComputerCard(
    entry: SwitchEntry,
    displayName: String,
    connected: Boolean,
    searching: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val foreground = Color.White
    val secondary = when {
        entry.isCurrent && connected && entry.isOnline -> stringResource(R.string.switch_status_current, entry.target.ip)
        entry.isCurrent -> stringResource(R.string.switch_status_offline, entry.target.ip)
        entry.isOnline && searching -> stringResource(R.string.switch_status_verified_now)
        entry.isOnline -> stringResource(R.string.switch_status_online, entry.target.ip)
        else -> stringResource(R.string.switch_status_checking, entry.target.ip)
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .semantics {
                selected = entry.isCurrent
                role = Role.RadioButton
            }
            .background(
                if (entry.isCurrent) Color(0xFF247CF0) else PanelSurface,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchComputerGlyph(active = entry.isCurrent, foreground = foreground, onEdit = onEdit)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).heightIn(min = 40.dp).combinedClickable(
                onClick = onClick,
                onLongClick = onEdit,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = foreground,
                    maxLines = 1,
                )
                Text(secondary, fontSize = 9.sp, color = if (entry.isCurrent) Color.White else MutedText, maxLines = 1)
            }
            if (entry.isCurrent) {
                SwitchCurrentCheck()
            } else {
                Text("›", fontSize = 22.sp, color = foreground)
            }
        }
    }
}

@Composable
private fun SwitchComputerGlyph(active: Boolean, foreground: Color, onEdit: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onEdit),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp)
                .background(if (active) Color(0xFF1858B5) else FieldSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
        Canvas(Modifier.size(16.dp)) {
            drawRoundRect(
                color = foreground,
                topLeft = Offset(size.width * 0.13f, size.height * 0.08f),
                size = Size(size.width * 0.74f, size.height * 0.62f),
                cornerRadius = CornerRadius(size.width * 0.09f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawLine(
                color = foreground,
                start = Offset(size.width * 0.5f, size.height * 0.7f),
                end = Offset(size.width * 0.5f, size.height * 0.87f),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = foreground,
                start = Offset(size.width * 0.25f, size.height * 0.89f),
                end = Offset(size.width * 0.75f, size.height * 0.89f),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(13.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(8.dp)) {
                drawLine(
                    SayItBlue,
                    Offset(size.width * .18f, size.height * .82f),
                    Offset(size.width * .82f, size.height * .18f),
                    1.5.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ComputerCustomizationPane(
    target: DiscoverySelection,
    savedAlias: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val fallback = computerFallbackName(target.ip)
    var draft by remember(target, savedAlias) { mutableStateOf(savedAlias.ifBlank { fallback }) }
    var editing by remember { mutableStateOf(false) }
    val normalized = ComputerAliasPolicy.normalize(draft)
    Box(Modifier.fillMaxSize().background(Color(0xFF12161D))) {
        Column(
            Modifier.fillMaxSize()
                .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 58.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.computer_customize_title), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(52.dp).background(Color(0xFF1858B5), CircleShape), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(28.dp)) {
                    drawRoundRect(
                        Color.White,
                        Offset(size.width * .13f, size.height * .08f),
                        Size(size.width * .74f, size.height * .62f),
                        CornerRadius(size.width * .09f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawLine(Color.White, Offset(size.width * .5f, size.height * .7f), Offset(size.width * .5f, size.height * .87f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width * .25f, size.height * .89f), Offset(size.width * .75f, size.height * .89f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(normalized.ifBlank { fallback }, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${target.ip}:${target.port}", fontSize = 9.sp, color = MutedText)
            Spacer(Modifier.height(8.dp))
            FieldCard(
                stringResource(R.string.computer_name_label),
                normalized.ifBlank { fallback },
                onClick = { editing = true },
            )
            Spacer(Modifier.height(8.dp))
            PillAction(
                stringResource(R.string.computer_name_save),
                { onSave(normalized) },
                Modifier.fillMaxWidth(),
                enabled = normalized.isNotEmpty(),
            )
            Box(
                Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable(onClick = onReset),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.computer_name_reset), fontSize = 10.sp, color = MutedText)
            }
            Text(stringResource(R.string.computer_customize_hint), fontSize = 8.sp, color = MutedText, textAlign = TextAlign.Center)
        }
        SwitchCloseAction(onClick = onClose, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
    if (editing) {
        WearTextInputDialog(
            stringResource(R.string.computer_name_label),
            draft,
            { draft = it; editing = false },
            { editing = false },
        )
    }
}

@Composable
private fun SwitchCurrentCheck() {
    Box(Modifier.size(24.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            drawLine(
                color = SayItBlue,
                start = Offset(size.width * 0.12f, size.height * 0.52f),
                end = Offset(size.width * 0.42f, size.height * 0.80f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = SayItBlue,
                start = Offset(size.width * 0.42f, size.height * 0.80f),
                end = Offset(size.width * 0.90f, size.height * 0.20f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SwitchCloseAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).background(PanelSurface, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(12.dp)) {
                drawLine(MutedText, Offset.Zero, Offset(size.width, size.height), 1.8.dp.toPx(), StrokeCap.Round)
                drawLine(MutedText, Offset(size.width, 0f), Offset(0f, size.height), 1.8.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SwitchRefreshAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth(0.64f).height(36.dp).background(Color(0xFF247CF0), RoundedCornerShape(20.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(16.dp)) {
                drawArc(
                    color = Color.White,
                    startAngle = 20f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawLine(
                    Color.White,
                    Offset(size.width * 0.88f, size.height * 0.18f),
                    Offset(size.width * 0.62f, size.height * 0.15f),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
                drawLine(
                    Color.White,
                    Offset(size.width * 0.88f, size.height * 0.18f),
                    Offset(size.width * 0.85f, size.height * 0.43f),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

internal fun isLowPowerRecording(
    sampleCount: Int,
    afterSeconds: Int,
    sampleRate: Int = WavWriter.SAMPLE_RATE,
): Boolean {
    val safeDelay = LowPowerRecordingPolicy.sanitizeStored(afterSeconds)
    return sampleCount.toLong() >= safeDelay.toLong() * sampleRate
}

@Composable
private fun RecordingActiveScreen(viewModel: RecordingViewModel, lowPowerAfterSeconds: Int) {
    val sampleCount by viewModel.visibleSampleCount.collectAsState()
    if (isLowPowerRecording(sampleCount, lowPowerAfterSeconds)) {
        LowPowerWatchDial {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.align(Alignment.Center).offset(y = (-56).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Canvas(Modifier.size(6.dp)) { drawCircle(SayItBlue) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.dial_title_recording),
                        fontSize = 9.sp,
                        color = LowPowerMuted,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                }
                StaticRecordingWaveform(
                    SayItBlue,
                    Modifier.size(width = 156.dp, height = 46.dp).align(Alignment.Center)
                        .clickable { viewModel.stopRecording() },
                )
                Text(
                    formatRecordingDurationFromSamples(sampleCount),
                    fontSize = 20.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).offset(y = 52.dp),
                )
                Text(
                    stringResource(R.string.recording_stop_hint),
                    fontSize = 7.sp,
                    color = LowPowerMuted,
                    modifier = Modifier.align(Alignment.Center).offset(y = 76.dp),
                )
                Box(
                    Modifier.align(Alignment.Center).offset(y = 98.dp).width(96.dp).heightIn(min = 30.dp)
                        .clickable { viewModel.cancelRecording() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.recording_cancel_discard), fontSize = 8.sp, color = LowPowerDim)
                }
            }
        }
    } else {
        WatchDial {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.dial_title_recording), fontSize = 9.sp, color = DialMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.Center).offset(y = -56.dp))
                RecordingWaveform(SayItBlue, Modifier.size(width = 156.dp, height = 46.dp).align(Alignment.Center).clickable { viewModel.stopRecording() })
                Text(formatRecordingDurationFromSamples(sampleCount), fontSize = 20.sp, color = DialIcon, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).offset(y = 52.dp))
                Text(stringResource(R.string.recording_cancel_hint), fontSize = 8.sp, color = DialMuted, modifier = Modifier.align(Alignment.Center).offset(y = 84.dp).clickable { viewModel.cancelRecording() })
            }
        }
    }
}

// ─── Watch dial (card) presentation ───────────────────────────────────────────

private val DialFace = Color(0xFFF6F7F9)
private val DialTick = Color(0xFF1C1E22)
private val DialIcon = Color(0xFF1C1E22)
private val DialMuted = Color(0xFF9AA3AE)
private val LowPowerFace = Color(0xFF030508)
private val LowPowerTick = Color(0xFF4B5562)
private val LowPowerMuted = Color(0xFF949EAC)
private val LowPowerDim = Color(0xFF4B5562)

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

@Composable
private fun LowPowerWatchDial(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f
            drawCircle(LowPowerFace, radius, center)
            drawLowPowerDialTicks(center, radius)
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

private fun DrawScope.drawLowPowerDialTicks(center: Offset, radius: Float) {
    val outer = radius * 0.99f
    val minorInner = radius * 0.945f
    val majorInner = radius * 0.87f
    repeat(24) { i ->
        val angle = i * 15.0 * Math.PI / 180.0
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val major = i % 6 == 0
        drawLine(
            color = if (major) SayItBlue else LowPowerTick,
            start = center + dir * outer,
            end = center + dir * (if (major) majorInner else minorInner),
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
private fun StaticRecordingWaveform(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val relativeHeights = floatArrayOf(0.24f, 0.43f, 0.61f, 0.81f, 1.0f, 0.81f, 0.61f, 0.43f, 0.24f)
        val gap = size.width / relativeHeights.size
        val midY = size.height / 2f
        val maxHeight = size.height * 0.80f
        val barWidth = gap * 0.30f
        relativeHeights.forEachIndexed { index, relativeHeight ->
            val halfHeight = maxHeight * relativeHeight / 2f
            val x = gap * index + gap / 2f
            drawLine(
                color,
                Offset(x, midY - halfHeight),
                Offset(x, midY + halfHeight),
                barWidth,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    FieldCard(label, if (value.isEmpty()) stringResource(R.string.config_tap_to_edit) else value, onClick = { editing = true })
    if (editing) WearTextInputDialog(
        label,
        value,
        { onChange(it); editing = false },
        { editing = false },
        keyboardType,
    )
}

@Composable
private fun FieldCard(label: String, value: String, trailing: String? = null, onClick: () -> Unit, onTrailingClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).background(FieldSurface, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 10.sp, color = MutedText); Text(value, fontSize = 13.sp, maxLines = 1) }
        if (trailing != null && onTrailingClick != null) Text(trailing, fontSize = 11.sp, color = SayItBlue, modifier = Modifier.padding(start = 8.dp).clickable(onClick = onTrailingClick))
    }
}

@Composable
private fun WearTextInputDialog(
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PanelSurface, RoundedCornerShape(18.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 13.sp, color = MutedText); Spacer(Modifier.height(8.dp))
            BasicTextField(value = text, onValueChange = { text = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.White), keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onConfirm(text) }), modifier = Modifier.fillMaxWidth().background(FieldSurface, RoundedCornerShape(10.dp)).padding(10.dp).focusRequester(focusRequester))
            Spacer(Modifier.height(12.dp))
            PillAction(stringResource(R.string.action_ok), { keyboard?.hide(); onConfirm(text) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PillAction(stringResource(R.string.action_cancel), { keyboard?.hide(); onDismiss() }, Modifier.fillMaxWidth(), background = FieldSurface)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
}
