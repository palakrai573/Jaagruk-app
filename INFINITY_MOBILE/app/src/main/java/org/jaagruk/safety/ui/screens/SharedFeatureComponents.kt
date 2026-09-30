package org.jaagruk.safety.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.ui.components.GlassCard
import org.jaagruk.safety.ui.theme.*

// ── Shared header used by OCR, Screenshot, Quiz screens ──────────────────────

@Composable
fun FeatureHeader(
    title      : String,
    isDarkTheme: Boolean,
    uiState    : String,
    dotColor   : Color,
    onBack     : () -> Unit,
    showReset  : Boolean,
    onReset    : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (isDarkTheme) DarkGlass else LightGlass, CircleShape)
                .border(0.5.dp,
                    if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.6f),
                    CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ArrowBack, "Back",
                tint = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                modifier = Modifier.size(18.dp))
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(if (isDarkTheme) DarkGlass else LightGlass)
                .border(0.5.dp,
                    if (isDarkTheme) Color.White.copy(0.1f) else Color.White.copy(0.6f),
                    RoundedCornerShape(50.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
            Text(uiState, style = MaterialTheme.typography.labelSmall, color = dotColor)
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(visible = showReset, enter = fadeIn(), exit = fadeOut()) {
            IconButton(
                onClick = onReset,
                modifier = Modifier.size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) {
                Icon(Icons.Default.Refresh, "Reset",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Shared pick button ────────────────────────────────────────────────────────

@Composable
fun PickButton(
    label      : String,
    icon       : ImageVector,
    color      : Color,
    isDarkTheme: Boolean,
    modifier   : Modifier = Modifier,
    onClick    : () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) DarkGlass else LightGlass)
            .border(0.5.dp,
                if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.7f),
                RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(40.dp).background(color.copy(0.15f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
    }
}

// ── Shared streaming result card ──────────────────────────────────────────────

@Composable
fun StreamingResultCard(
    text          : String,
    isStreaming   : Boolean,
    isDarkTheme   : Boolean,
    scrollState   : ScrollState,
    onStop        : () -> Unit,
    modifier      : Modifier = Modifier,
    accentColor   : Color  = Blue500,
    streamingLabel: String = "Generating...",
    completeLabel : String = "Complete",
    completeColor : Color  = SuccessGreen
) {
    Column(modifier = modifier) {
        // Status row
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isStreaming) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(modifier = Modifier.size(6.dp).background(accentColor.copy(a), CircleShape))
                Text(streamingLabel, style = MaterialTheme.typography.labelMedium, color = accentColor)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrorRed.copy(0.12f))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Stop, null, tint = ErrorRed, modifier = Modifier.size(14.dp))
                        Text("Stop", style = MaterialTheme.typography.labelMedium, color = ErrorRed)
                    }
                }
            } else {
                Box(modifier = Modifier.size(6.dp).background(completeColor, CircleShape))
                Text(completeLabel, style = MaterialTheme.typography.labelMedium, color = completeColor)
            }
        }

        GlassCard(
            darkTheme = isDarkTheme,
            modifier  = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
            ) {
                if (text.isEmpty() && isStreaming) {
                    val inf = rememberInfiniteTransition(label = "typing")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            val scale by inf.animateFloat(0.6f, 1f,
                                infiniteRepeatable(
                                    tween(400, delayMillis = i * 130, easing = EaseInOut),
                                    RepeatMode.Reverse), label = "d$i")
                            Box(modifier = Modifier
                                .size((6 * scale).dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f), CircleShape))
                        }
                    }
                } else {
                    Text(
                        if (isStreaming && text.isNotEmpty()) "$text▍" else text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 24.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ── Shared centered spinner ───────────────────────────────────────────────────

@Composable
fun CenteredSpinner(label: String, color: Color, isDarkTheme: Boolean) {
    val inf = rememberInfiniteTransition(label = "spin")
    val alpha by inf.animateFloat(0.4f, 1f,
        infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse), label = "a")

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = color, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = color.copy(alpha = alpha))
    }
}

// ── Saved-to-Library banner ─────────────────────────────────────────────────

@Composable
fun SavedBanner(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn() + slideInVertically { -it },
        exit    = fadeOut() + slideOutVertically { -it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SuccessGreen.copy(alpha = 0.92f))
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Saved to Library", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

// ── Shared error state ────────────────────────────────────────────────────────

@Composable
fun FeatureErrorState(isDarkTheme: Boolean, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(88.dp).background(ErrorRed.copy(0.12f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Blue500)
                .clickable(onClick = onRetry)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Try Again", style = MaterialTheme.typography.titleSmall,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
