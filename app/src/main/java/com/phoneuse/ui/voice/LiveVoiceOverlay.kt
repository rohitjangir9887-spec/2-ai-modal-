package com.phoneuse.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import com.phoneuse.voice.LiveVoicePhase
import com.phoneuse.voice.LiveVoiceSession

/**
 * Premium ChatGPT-style Live Voice interface.
 * Displays audio-reactive glowing orb, live streaming transcription,
 * natural Indian female TTS output, and instant barge-in controls.
 */
@Composable
fun LiveVoiceOverlay(
    conversationId: Long?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    session: LiveVoiceSession = LiveVoiceSession.shared,
) {
    val context = LocalContext.current
    val phase by session.phase.collectAsState()
    val partialTranscript by session.partialTranscript.collectAsState()
    val lastUserSpeech by session.lastUserSpeech.collectAsState()
    val lastReply by session.lastAssistantReply.collectAsState()
    val amplitude by session.audioAmplitude.collectAsState()
    val isMuted by session.isMuted.collectAsState()
    val errorMsg by session.errorMessage.collectAsState()

    DisposableEffect(conversationId) {
        session.startSession(context, conversationId)
        onDispose {
            session.stopSession()
        }
    }

    val c = pu
    val darkBackdrop = Color(0xF2101318)
    val accentCyan = Color(0xFF00E5FF)
    val accentPurple = Color(0xFF7C4DFF)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackdrop)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Bar: Branding, Live Status Chip, Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (phase == LiveVoicePhase.IDLE) Color.Gray else Color(0xFF00E676)),
                    )
                    Text(
                        text = "JULI AI LIVE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = 1.2.sp,
                    )
                }

                // Phase Badge
                val phaseText = when (phase) {
                    LiveVoicePhase.IDLE -> if (isMuted) "Muted" else "Ready"
                    LiveVoicePhase.LISTENING -> "Listening…"
                    LiveVoicePhase.THINKING -> "Thinking…"
                    LiveVoicePhase.ACTING -> "Automating Phone…"
                    LiveVoicePhase.SPEAKING -> "Speaking…"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = phaseText,
                        fontSize = 12.sp,
                        color = accentCyan,
                        fontWeight = FontWeight.Medium,
                    )
                }

                IconBtn(
                    icon = PuIcons.Close,
                    label = "Close Live Voice",
                    onClick = onDismiss,
                    tint = Color.White.copy(alpha = 0.7f),
                    iconSize = 22.dp,
                )
            }

            // Middle Content: Reactive Orb & Live Transcripts
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Interactive Audio-Reactive Orb
                LiveAudioOrb(
                    phase = phase,
                    amplitude = amplitude,
                    onClick = {
                        if (phase == LiveVoicePhase.SPEAKING) {
                            session.interruptSpeech()
                        } else {
                            session.toggleMute()
                        }
                    },
                )

                Spacer(Modifier.height(32.dp))

                // Streaming / Partial Speech Display
                val activeSpeech = when {
                    partialTranscript.isNotBlank() -> partialTranscript
                    lastUserSpeech.isNotBlank() && phase != LiveVoicePhase.SPEAKING -> lastUserSpeech
                    phase == LiveVoicePhase.LISTENING -> "Aap bol sakte hain, main sun rahi hoon…"
                    phase == LiveVoicePhase.THINKING -> "Soch rahi hoon…"
                    phase == LiveVoicePhase.ACTING -> "Aapka task phone par chal raha hai…"
                    else -> ""
                }

                Text(
                    text = activeSpeech,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(horizontal = 8.dp),
                )

                Spacer(Modifier.height(16.dp))

                // Assistant Spoken Reply Bubble
                AnimatedVisibility(
                    visible = lastReply.isNotBlank(),
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                    label = "assistantReply",
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = PuIcons.Step,
                                contentDescription = null,
                                tint = accentCyan,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp),
                            )
                            Text(
                                text = lastReply,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (errorMsg != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorMsg.orEmpty(),
                        fontSize = 12.sp,
                        color = Color(0xFFFF5252),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Bottom Controls: Mute, Interrupt / Ruko, Done
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            ) {
                // Mute / Unmute Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (isMuted) Color(0xFFFF5252).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
                        .border(1.dp, if (isMuted) Color(0xFFFF5252) else Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable(role = Role.Button) { session.toggleMute() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PuIcons.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) Color(0xFFFF5252) else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Instant Interrupt / Ruko Button (Barge-in)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(accentCyan, accentPurple)
                            )
                        )
                        .clickable(role = Role.Button) { session.interruptSpeech() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = PuIcons.Bolt,
                            contentDescription = "Ruko (Interrupt)",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Ruko",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                    }
                }

                // Close / Done Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable(role = Role.Button) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PuIcons.Check,
                        contentDescription = "Done",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Animated glowing orb that expands, contracts, and ripples with speech volume.
 */
@Composable
fun LiveAudioOrb(
    phase: LiveVoicePhase,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val smoothLevel by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "liveOrbAmplitude",
    )

    val transition = rememberInfiniteTransition(label = "orbPulse")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbRotation",
    )

    val baseScale = when (phase) {
        LiveVoicePhase.IDLE -> 1.0f
        LiveVoicePhase.LISTENING -> 1.05f + smoothLevel * 0.35f
        LiveVoicePhase.THINKING -> 1.08f
        LiveVoicePhase.ACTING -> 1.15f
        LiveVoicePhase.SPEAKING -> 1.10f + smoothLevel * 0.40f
    }

    val animatedScale by animateFloatAsState(
        targetValue = baseScale,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "liveOrbScale",
    )

    val orbGradient = when (phase) {
        LiveVoicePhase.SPEAKING -> listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFFFF4081))
        LiveVoicePhase.LISTENING -> listOf(Color(0xFF00E676), Color(0xFF00E5FF), Color(0xFF2979FF))
        LiveVoicePhase.THINKING -> listOf(Color(0xFFFF9100), Color(0xFFFFD600), Color(0xFFFF3D00))
        LiveVoicePhase.ACTING -> listOf(Color(0xFF7C4DFF), Color(0xFF651FFF), Color(0xFF3D5AFE))
        LiveVoicePhase.IDLE -> listOf(Color(0xFF455A64), Color(0xFF263238), Color(0xFF102027))
    }

    Box(
        modifier = modifier
            .size(170.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Outer Halo 1
        Box(
            modifier = Modifier
                .size(170.dp)
                .scale(animatedScale * 1.25f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orbGradient.first().copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )

        // Outer Halo 2
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(animatedScale * 1.12f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orbGradient[1].copy(alpha = 0.5f), Color.Transparent),
                    ),
                ),
        )

        // Core Glowing Orb
        Box(
            modifier = Modifier
                .size(105.dp)
                .scale(animatedScale)
                .shadow(24.dp, CircleShape, spotColor = orbGradient.first(), ambientColor = orbGradient[1])
                .clip(CircleShape)
                .background(Brush.sweepGradient(orbGradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (phase == LiveVoicePhase.SPEAKING) PuIcons.Bolt else PuIcons.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
