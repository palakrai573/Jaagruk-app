package org.jaagruk.safety.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.ui.components.*
import org.jaagruk.safety.ui.theme.*
import androidx.compose.ui.res.stringResource
import org.jaagruk.safety.R

@Composable
fun VoiceScreen(
    isDarkTheme: Boolean,
    orbState: OrbState,
    aiState: AIInferenceState,
    onSetListening: () -> Unit,
    onSetIdle: () -> Unit,
    onDismiss: () -> Unit
) {
    val isListening = aiState is AIInferenceState.Thinking || aiState is AIInferenceState.Responding
    val transcript  = if (aiState is AIInferenceState.Responding) aiState.partialText else ""

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // ── Top bar ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp)
                        .background(if (isDarkTheme) DarkGlass else LightGlass, CircleShape)
                        .border(0.5.dp,
                            if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.6f),
                            CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null,
                        tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                        modifier = Modifier.size(16.dp))
                }

                Text("∞", fontSize = 22.sp, color = Blue500, fontWeight = FontWeight.Light)

                AnimatedVisibility(visible = aiState is AIInferenceState.Responding) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Speaking", style = MaterialTheme.typography.labelSmall,
                            color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
                        WaveformAnimation(isActive = true,
                            modifier = Modifier.height(16.dp).width(48.dp), color = Blue500)
                    }
                }
            }

            Spacer(Modifier.weight(0.25f))

            AiBodyOrb(orbState = orbState, isDarkTheme = isDarkTheme, size = 220.dp)

            Spacer(Modifier.height(24.dp))

            // ── Status label ──────────────────────────────────────────
            AnimatedContent(
                targetState = aiState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "voiceStatus"
            ) { state ->
                Text(
                    text = when (state) {
                        is AIInferenceState.Idle      -> stringResource(R.string.ai_state_ready)
                        is AIInferenceState.Loading   -> stringResource(R.string.ai_state_loading)
                        is AIInferenceState.Thinking  -> stringResource(R.string.ai_state_thinking)
                        is AIInferenceState.Responding -> stringResource(R.string.voice_responding)
                        is AIInferenceState.Error     -> stringResource(R.string.ai_state_error)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (state) {
                        is AIInferenceState.Error -> ErrorRed
                        is AIInferenceState.Idle  -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                        else -> Blue500
                    },
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Transcript card ───────────────────────────────────────
            AnimatedVisibility(
                visible = transcript.isNotEmpty(),
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                exit  = fadeOut(tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isDarkTheme) DarkGlass else LightGlass)
                        .border(0.5.dp,
                            if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.7f),
                            RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Text(transcript, style = MaterialTheme.typography.bodyLarge,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        textAlign = TextAlign.Center, lineHeight = 26.sp)
                }
            }

            Spacer(Modifier.weight(0.35f))

            // ── Bottom controls ───────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = Icons.Default.Close, isDarkTheme = isDarkTheme,
                    tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    onClick = onSetIdle
                )

                Box(modifier = Modifier.width(120.dp).height(40.dp), contentAlignment = Alignment.Center) {
                    if (isListening) {
                        WaveformAnimation(isActive = true, modifier = Modifier.fillMaxSize(), color = Blue500)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (isListening)
                                Brush.linearGradient(listOf(Blue500, Indigo500))
                            else
                                Brush.linearGradient(listOf(
                                    if (isDarkTheme) DarkSurface else LightSurface,
                                    if (isDarkTheme) DarkSurfaceElevated else LightSurfaceElevated
                                )),
                            CircleShape
                        )
                        .clickable { if (isListening) onSetIdle() else onSetListening() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isListening) Icons.Default.Pause else Icons.Default.Mic,
                        null,
                        tint = if (isListening) Color.White
                               else if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDarkTheme: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(if (isDarkTheme) DarkGlass else LightGlass, CircleShape)
            .border(0.5.dp,
                if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.6f),
                CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
