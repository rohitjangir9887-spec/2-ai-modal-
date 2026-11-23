package com.phoneuse.overlay

import com.phoneuse.i18n.tr
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.ui.chat.CardHeader
import com.phoneuse.ui.chat.ConfirmDetails
import com.phoneuse.ui.chat.OptionButtons
import com.phoneuse.ui.chat.confirmCopy
import com.phoneuse.ui.chat.look
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.ButtonPair
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.SendCircle
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.delay

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag

private val GlowEdge = Color(0xFFE4572E)
private val GlowSoft = Color(0xFFF08A66)
private val Halo = Color(0xFFE4572E)

private const val GLOW_STEPS = 24
private const val CORNER_DP = 36f
private const val EDGE_DP = 3f
private const val GLOW_DP = 26f

@Composable
fun BorderGlow(phase: AgentPhase) {
    val pulsing = phase == AgentPhase.THINKING
    // The animation exists only while THINKING; its value is read in the graphics layer (no recomposition).
    val animated: State<Float>? = if (pulsing) {
        rememberInfiniteTransition(label = "border").animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
            label = "borderAlpha",
        )
    } else {
        null
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = animated?.value ?: 0.75f }
            .drawBehind { drawInnerGlow() },
    )
}

private fun DrawScope.drawInnerGlow() {
    val edge = EDGE_DP.dp.toPx()
    val glow = GLOW_DP.dp.toPx()
    val corner = CORNER_DP.dp.toPx()
    // Fading rings first (outermost = strongest), then the crisp edge on top.
    val stepPx = glow / GLOW_STEPS
    for (i in 0 until GLOW_STEPS) {
        val inset = edge + i * stepPx + stepPx / 2
        val falloff = 1f - i / GLOW_STEPS.toFloat()
        drawRoundRect(
            color = GlowSoft.copy(alpha = 0.32f * falloff * falloff),
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
            style = Stroke(stepPx + 0.5f),
        )
    }
    drawRoundRect(
        color = GlowEdge,
        topLeft = Offset(edge / 2, edge / 2),
        size = Size(size.width - edge, size.height - edge),
        cornerRadius = CornerRadius(corner),
        style = Stroke(edge),
    )
}

/**
 * The docked panel at the bottom of the screen while the agent works (OverlayRunning board): status and
 * a stop chip, the last three log lines, and an input to steer. A question card takes the panel's place
 * (OverlayConfirm board), with the input and stop below it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DockContent(
    state: AgentUiState,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    focusClears: Int,
    onStop: () -> Unit,
    onSubmit: (DockCard?) -> Boolean,
    onCardResult: (id: Long, result: String) -> Unit,
    onInputFocus: (Boolean) -> Unit,
    onVoice: () -> Unit = {},
    onMoveHandoff: () -> Unit = {},
    handoffSize: IntSize? = null,
    onPauseToggle: () -> Unit = {},
    onQueueSubmit: (DockCard?) -> Boolean = { false },
) {
    val card = state.card
    if (card is DockCard.HandOff) {
        HandoffBar(
            card = card,
            availableSize = handoffSize,
            onMove = onMoveHandoff,
            onDone = { onCardResult(card.id, OverlayUserGate.HAND_OFF_DONE) },
            onStop = onStop,
        )
        return
    }

    var wantKeyboard by remember { mutableStateOf(false) }
    var sendRefused by remember(draft.text) { mutableStateOf(false) }
    var queueMode by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(wantKeyboard) {
        if (wantKeyboard) {
            delay(120) // the window must become focusable before the IME can attach
            keyboard?.show()
            wantKeyboard = false
        }
    }

    // The overlay asks for this before gestures and system keys so they reach the app, not the dock.
    LaunchedEffect(focusClears) {
        if (focusClears > 0) focusManager.clearFocus()
    }

    // Back or IME dismissal leaves the text field focused; drop focus so the dock gives the window focus back.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }

    // Text typed while a question card is open answers that card (with its id); otherwise it steers or queues.
    fun send() {
        if (draft.text.isBlank()) return
        val sent = if (queueMode && card == null) onQueueSubmit(card) else onSubmit(state.card)
        if (!sent) {
            sendRefused = true
            return
        }
        sendRefused = false
        queueMode = false
        focusManager.clearFocus()
    }

    val c = pu
    val border = when (card) {
        is DockCard.Confirm -> card.category.look(c).border
        null -> if (c.dark) c.line else Color(0xFFF6DCD1)
        else -> c.line
    }
    val shape = RoundedCornerShape(26.dp)

    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 10.dp, end = 10.dp, bottom = 14.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = Color(0x2E14161A), spotColor = Color(0x2E14161A))
                .clip(shape)
                .background(if (card == null) c.surface.copy(alpha = 0.96f) else c.surface)
                .border(1.dp, border, shape)
                .padding(
                    start = if (card == null) 12.dp else 16.dp,
                    end = if (card == null) 12.dp else 16.dp,
                    top = if (card == null) 14.dp else 16.dp,
                    bottom = if (card == null) 10.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (card == null) 10.dp else 12.dp),
        ) {
            val input: @Composable (String, Boolean) -> Unit = { placeholder, stopInPill ->
                InputPill(
                    draft = draft,
                    onText = onDraftChange,
                    placeholder = placeholder,
                    stopInPill = stopInPill,
                    onSend = { send() },
                    onStop = onStop,
                    onVoice = onVoice,
                    onFocus = { focused ->
                        onInputFocus(focused)
                        if (focused) wantKeyboard = true
                    },
                )
            }
            if (card == null) {
                StatusHeader(
                    state = state,
                    expanded = logsExpanded,
                    onToggleLogs = { logsExpanded = !logsExpanded },
                    onPauseToggle = onPauseToggle,
                    onStop = onStop,
                )
                LogLines(state, expanded = logsExpanded)
                val placeholder = if (queueMode) {
                    tr("Queue next task…", "다음 작업 대기열에 추가…")
                } else {
                    tr("Add instruction…", "지시 추가…")
                }
                input(placeholder, false)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PuIcons.Lock, null, tint = c.textSub, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (state.paused) tr("Paused · Screen unlocked", "일시중지됨 · 화면 잠금 해제")
                            else tr("Screen touch is locked while I work", "작업 중에는 화면 터치가 잠겨요"),
                            fontSize = 11.5.sp,
                            color = c.textSub,
                        )
                    }
                    Text(
                        if (queueMode) tr("Steer instead", "현재 작업 지시") else tr("+ Queue next", "+ 다음 작업 예약"),
                        Modifier
                            .testTag("overlay_queue_toggle")
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { queueMode = !queueMode }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (queueMode) c.accentText else c.textSub,
                    )
                }
            } else {
                CardBody(card) { result -> onCardResult(card.id, result) }
                input(if (card is DockCard.Ask) tr("You can type another answer", "다른 답을 써도 돼요") else tr("Tell me if you want it different", "다르게 하려면 말해 주세요"), true)
            }
            if (sendRefused) {
                Text(
                    tr("Still here. Send again when the task finishes", "입력창에 남아 있어요. 작업이 끝나면 다시 보내 주세요"),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 13.sp, color = c.textSub,
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(
    state: AgentUiState,
    expanded: Boolean,
    onToggleLogs: () -> Unit,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val c = pu
    val live = state.phase == AgentPhase.THINKING || state.phase == AgentPhase.ACTING
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(live)
            Spacer(Modifier.width(10.dp))
            Text(
                when (state.phase) {
                    AgentPhase.THINKING -> tr("Thinking", "생각 중")
                    AgentPhase.ACTING -> tr("Operating the phone", "폰 조작 중")
                    AgentPhase.WAITING_USER -> tr("Waiting for your answer", "답을 기다리는 중")
                    AgentPhase.PAUSED -> tr("Paused", "일시중지")
                    AgentPhase.IDLE -> tr("Wrapping up", "정리 중")
                },
                Modifier.weight(1f),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = c.text,
            )
            if (state.allLines.size > 3) {
                Text(
                    if (expanded) tr("Less", "접기") else tr("Logs", "로그"),
                    Modifier
                        .testTag("overlay_logs_toggle")
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onToggleLogs)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = c.textSub,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (state.phase != AgentPhase.IDLE) {
                Row(
                    Modifier
                        .testTag("overlay_pause_resume")
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (state.paused) c.accentSoft else c.sunken)
                        .clickable(role = Role.Button, onClick = onPauseToggle)
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.paused) tr("Resume", "재개") else tr("Pause", "일시중지"),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.paused) c.accentText else c.text2,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Row(
                Modifier
                    .testTag("overlay_stop_button")
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.dangerSoft)
                    .clickable(role = Role.Button, onClick = onStop)
                    .padding(start = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c.danger))
                Spacer(Modifier.width(6.dp))
                Text(tr("Stop", "중단"), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = c.danger)
            }
        }
        if (state.activeRole.isNotBlank() || state.queuedCount > 0) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.activeRole.isNotBlank()) {
                    val shortModel = state.activeModel.substringAfterLast('/').take(28)
                    Text(
                        if (shortModel.isNotBlank()) "${state.activeRole} · $shortModel" else state.activeRole,
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.sunken)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.queuedCount > 0) {
                    Text(
                        tr("+${state.queuedCount} queued", "+${state.queuedCount} 대기 중"),
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accentSoft)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accentText,
                    )
                }
            }
        }
    }
}

/** A 9dp dot in the text colour with a soft blue halo that breathes while the agent works. */
@Composable
private fun StatusDot(live: Boolean) {
    val halo: Float = if (live) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(0.10f, 0.26f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "halo")
        a
    } else {
        0.16f
    }
    Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(19.dp).clip(CircleShape).background(Halo.copy(alpha = halo)))
        Box(Modifier.size(9.dp).clip(CircleShape).background(pu.text))
    }
}

@Composable
private fun LogLines(state: AgentUiState, expanded: Boolean) {
    val c = pu
    val lines = if (expanded) state.allLines else state.lines.takeLast(3)
    if (lines.isEmpty()) return
    Column(
        Modifier
            .padding(horizontal = 4.dp)
            .then(if (expanded) Modifier.height(140.dp).verticalScroll(rememberScrollState()) else Modifier),
    ) {
        lines.forEachIndexed { i, line ->
            Text(
                line,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                color = if (i == lines.lastIndex) c.text else c.textSub,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InputPill(
    draft: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    placeholder: String,
    stopInPill: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onVoice: () -> Unit,
) {
    val c = pu
    val text = draft.text
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.sunken)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp)) {
            if (text.isEmpty()) Text(placeholder, fontSize = 15.sp, color = c.textSub, maxLines = 1)
            BasicTextField(
                value = draft,
                onValueChange = onText,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocus(it.isFocused) }
                    .semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }
        IconBtn(PuIcons.Mic, tr("Voice input", "음성 입력"), onClick = onVoice, tint = c.textSub, modifier = Modifier.size(48.dp))
        if (stopInPill && text.isBlank()) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.dangerSoft)
                    .clickable(role = Role.Button, onClick = onStop)
                    .semantics { contentDescription = tr("Stop", "중단") },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(c.danger))
            }
        } else {
            SendCircle(onSend, enabled = text.isNotBlank(), size = 40.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.CardBody(card: DockCard, onResult: (String) -> Unit) {
    val c = pu
    when (card) {
        is DockCard.Confirm -> {
            val look = card.category.look(c)
            val copy = confirmCopy(card.category, card.summary)
            CardHeader(look.icon, look.fg, look.soft, copy.kicker, copy.question)
            ConfirmDetails(copy)
            copy.note?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub) }
            ButtonPair {
                PuButton(tr("Deny", "거부"), { onResult(OverlayUserGate.CONFIRM_NO) }, Modifier.weight(1f), ButtonKind.OUTLINE)
                PuButton(
                    copy.allowLabel,
                    { onResult(OverlayUserGate.CONFIRM_YES) },
                    Modifier.weight(1f),
                    if (look.danger) ButtonKind.DANGER else ButtonKind.PRIMARY,
                )
            }
        }
        is DockCard.Ask -> {
            CardHeader(PuIcons.Question, c.accentText, c.accentSoft, null, card.question)
            OptionButtons(card.options, onResult)
        }
        is DockCard.HandOff -> Unit // Uses the compact handoff bar in place of the dock.
    }
}
