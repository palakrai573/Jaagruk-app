package org.jaagruk.safety.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.ui.components.*
import org.jaagruk.safety.ui.theme.*
import androidx.compose.ui.res.stringResource
import org.jaagruk.safety.R

@Composable
fun DashboardScreen(
    isDarkTheme            : Boolean,
    orbState               : OrbState,
    bottomPadding          : Dp,
    onNavigateToChat       : () -> Unit,
    onNavigateToVoice      : () -> Unit,
    onOrbTap               : () -> Unit,
    onNavigateToCircle     : () -> Unit = onOrbTap,
    onNavigateToOcr        : () -> Unit = onNavigateToChat,
    onNavigateToPdf        : () -> Unit = onNavigateToChat,

    onNavigateToScreenshot : () -> Unit = onNavigateToChat
) {
    val dark = isDarkTheme
    val bg   = if (dark) DarkBg else LightBg

    GradientBackground(darkTheme = dark, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(20.dp))

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Blue50, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("∞", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Blue500)
                    }
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (dark) TextPrimary else TextPrimaryLight,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            if (dark) DarkSurface else LightSurface,
                            CircleShape
                        )
                        .border(1.dp, if (dark) DarkBorder else LightBorder, CircleShape)
                        .clickable {},
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Notifications, null,
                        tint = if (dark) TextSecondary else TextSecondaryLight,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Hero greeting ─────────────────────────────────────────────────
            val greeting = remember {
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                when { h < 12 -> "Good morning" ; h < 17 -> "Good afternoon" ; else -> "Good evening" }
            }
            Text(
                greeting,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) TextSecondary else TextSecondaryLight
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "What can I help\nyou with?",
                style = MaterialTheme.typography.headlineLarge,
                color = if (dark) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold,
                lineHeight = 40.sp
            )

            Spacer(Modifier.height(20.dp))

            // ── Search / Ask bar ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (dark) DarkSurface else LightSurface)
                    .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onNavigateToChat)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Search, null,
                    tint = if (dark) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    stringResource(R.string.dashboard_ask_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dark) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Blue500, CircleShape)
                        .clickable(onClick = onNavigateToVoice),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            // ══════════════════════════════════════════════════════════════════
            // ROW 1 — Chat HERO (full width)
            // ══════════════════════════════════════════════════════════════════
            ChatHeroCard(
                orbState  = orbState,
                dark      = dark,
                onClick   = onNavigateToChat,
                modifier  = Modifier.fillMaxWidth().height(160.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ══════════════════════════════════════════════════════════════════
            // ROW 2 — Circle Learn (wide-left) | Voice + OCR (stacked-right)
            // ══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BentoFeatureCard(
                    icon        = Icons.Default.RadioButtonChecked,
                    title       = "Circle\nLearn",
                    subtitle    = "Circle to explain",
                    accent      = Blue500,
                    dark        = dark,
                    onClick     = onNavigateToCircle,
                    modifier    = Modifier.weight(1.15f).height(248.dp)
                )
                Column(
                    modifier = Modifier.weight(0.85f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BentoSmallCard(
                        icon    = Icons.Default.Mic,
                        label   = "Voice",
                        dark    = dark,
                        onClick = onNavigateToVoice,
                        modifier = Modifier.fillMaxWidth().height(118.dp)
                    )
                    BentoSmallCard(
                        icon    = Icons.Default.DocumentScanner,
                        label   = "OCR Scan",
                        dark    = dark,
                        onClick = onNavigateToOcr,
                        modifier = Modifier.fillMaxWidth().height(118.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ══════════════════════════════════════════════════════════════════
            // ROW 3 — PDF | Screenshot
            //
            // The Quiz tile is gone; the assessment engine replaces it. This whole
            // dashboard is rebuilt in phase 10 around a readiness ring and a single
            // next action, rather than a grid of equally-weighted tiles.
            // ══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BentoMiniCard(
                    icon    = Icons.Default.FolderOpen,
                    label   = "PDF",
                    dark    = dark,
                    onClick = onNavigateToPdf,
                    modifier = Modifier.weight(1f).height(92.dp)
                )
                BentoMiniCard(
                    icon    = Icons.Default.ScreenSearchDesktop,
                    label   = "Screenshot",
                    dark    = dark,
                    onClick = onNavigateToScreenshot,
                    modifier = Modifier.weight(1f).height(92.dp)
                )
            }

            Spacer(Modifier.height(bottomPadding + 28.dp))
        }
    }
}

// ── Chat Hero Card ─────────────────────────────────────────────────────────────

@Composable
private fun ChatHeroCard(
    orbState : OrbState,
    dark     : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.98f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "hs"
    )

    val statusLabel = when (orbState) {
        OrbState.Idle       -> "Ready · tap to start"
        OrbState.Loading    -> "Loading model…"
        OrbState.Thinking   -> "Thinking…"
        OrbState.Responding -> "Responding…"
        OrbState.Error      -> "Error · tap to retry"
    }
    val statusColor = when (orbState) {
        OrbState.Idle       -> SuccessGreen
        OrbState.Loading    -> WarnAmber
        OrbState.Thinking   -> Blue400
        OrbState.Responding -> Blue400
        OrbState.Error      -> ErrorRed
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (dark)
                    Brush.linearGradient(listOf(Teal900, Ink800))
                else
                    Brush.linearGradient(listOf(Blue500, Blue600))
            )
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(22.dp)
    ) {
        // Subtle orb glow in top-right
        Box(
            modifier = Modifier
                .size(120.dp)
                .align(Alignment.TopEnd)
                .offset(x = 20.dp, y = (-20).dp)
                .background(
                    Brush.radialGradient(listOf(Blue400.copy(0.25f), Color.Transparent)),
                    CircleShape
                )
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Chat, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        stringResource(R.string.dashboard_chat_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(modifier = Modifier.size(5.dp).background(statusColor, CircleShape))
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(0.7f)
                        )
                    }
                }
            }
            Text(
                "Ask anything — fully offline, on-device AI.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(0.65f),
                lineHeight = 18.sp
            )
        }
    }
}

// ── Bento Feature Card (tall, for Circle Learn) ───────────────────────────────

@Composable
private fun BentoFeatureCard(
    icon     : ImageVector,
    title    : String,
    subtitle : String,
    accent   : Color,
    dark     : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.97f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "fs"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Blue50, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (dark) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (dark) TextSecondary else TextSecondaryLight
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "Open",
                    style = MaterialTheme.typography.labelSmall,
                    color = Blue500,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(Icons.Default.ArrowForward, null, tint = Blue500, modifier = Modifier.size(11.dp))
            }
        }
    }
}

// ── Bento Small Card ──────────────────────────────────────────────────────────

@Composable
private fun BentoSmallCard(
    icon     : ImageVector,
    label    : String,
    dark     : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "ss"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(18.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(Blue50, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(17.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Bento Mini Card (bottom row) ──────────────────────────────────────────────

@Composable
private fun BentoMiniCard(
    icon     : ImageVector,
    label    : String,
    dark     : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.95f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "ms"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(Blue50, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (dark) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )
    }
}
