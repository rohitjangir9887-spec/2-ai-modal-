package com.phoneuse.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.ReadAloud
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import com.phoneuse.voice.VoiceSessionManager

/**
 * The assistant's voice sheet (AssistVoiceActivity): a compact card at the bottom, styled like the assistant
 * sheet, with the [VoiceOrb], the live words and a × to cancel. Slides in, and listening starts right away
 * when [autoStart]. Tapping the scrim calls [onDismiss].
 */
@Composable
fun VoiceSheet(
    voice: VoiceInput,
    autoStart: Boolean,
    onDismiss: () -> Unit,
    continuousMode: Boolean = false,
    onContinuousChange: ((Boolean) -> Unit)? = null,
) {
    val c = pu
    val speaking by ReadAloud.speaking.collectAsState()
    val liveVoice by VoiceSessionManager.shared.state.collectAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        if (autoStart) voice.toggle()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 },
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .padding(8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Like the side-button sheet: a neutral shadow, never a coloured glow.
                    .shadow(16.dp, shape, ambientColor = Color(0x2E14161A), spotColor = Color(0x2E14161A))
                    .clip(shape)
                    .background(c.surface)
                    // Taps inside the card must not reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = tr("Voice input", "음성 입력") }
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onContinuousChange != null) {
                            Box(
                                Modifier
                                    .testTag("voice_continuous_toggle")
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (continuousMode) c.accentSoft else c.sunken)
                                    .clickable { onContinuousChange(!continuousMode) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (continuousMode) tr("Continuous: On", "연속 대화: 켜짐") else tr("Continuous", "연속 대화"),
                                    color = if (continuousMode) c.accentText else c.textSub,
                                    fontFamily = PlexSans,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (speaking || liveVoice.muted) {
                            Box(
                                Modifier
                                    .testTag("voice_mute_or_stop_tts")
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.sunken)
                                    .clickable {
                                        if (speaking) {
                                            VoiceSessionManager.shared.bargeIn()
                                            ReadAloud.stop()
                                        } else {
                                            VoiceSessionManager.shared.toggleMute()
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (speaking) tr("Interrupt speech", "말 끊기") else tr("Unmute", "음소거 해제"),
                                    color = c.text,
                                    fontFamily = PlexSans,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    IconBtn(
                        PuIcons.Close,
                        tr("Cancel voice input", "음성 입력 취소"),
                        onDismiss,
                        tint = pu.textSub,
                        iconSize = 20.dp,
                        modifier = Modifier.testTag("voice_sheet_close"),
                    )
                }
                VoiceOrb(voice.state, {
                    if (speaking) ReadAloud.stop()
                    voice.toggle()
                }, size = 76.dp)
                VoiceCaption(
                    voice.state,
                    idleTitle = tr("Tap to speak", "눌러서 말하기"),
                    idleHint = tr("Say what you want me to do", "할 일을 말해 주세요"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    align = TextAlign.Center,
                )
            }
        }
    }
}
