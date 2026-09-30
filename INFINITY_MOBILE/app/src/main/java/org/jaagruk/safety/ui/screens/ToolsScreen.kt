package org.jaagruk.safety.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.theme.*

@Composable
fun ToolsScreen(
    isDarkTheme            : Boolean,
    bottomPadding          : Dp,
    onNavigateToPdf        : () -> Unit = {},
    onNavigateToOcr        : () -> Unit = {},
    onNavigateToScreenshot : () -> Unit = {},
    onNavigateToCircle     : () -> Unit = {}
) {
    val dark = isDarkTheme

    GradientBackground(darkTheme = dark, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(28.dp))

            // ── Header ────────────────────────────────────────────────────────
            Text(
                "Tools",
                style = MaterialTheme.typography.headlineLarge,
                color = if (dark) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "AI-powered capabilities",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) TextSecondary else TextSecondaryLight
            )

            Spacer(Modifier.height(28.dp))

            // ════════════════════════════════════════════════════════════════
            // ROW 1 — Circle Learn HERO (full width, accent gradient)
            // ════════════════════════════════════════════════════════════════
            ToolHeroCard(
                icon        = Icons.Default.RadioButtonChecked,
                title       = "Circle Learn",
                description = "Circle anything on screen — explain, summarize, translate, or quiz yourself with on-device AI.",
                badge       = "Flagship",
                dark        = dark,
                onClick     = onNavigateToCircle,
                modifier    = Modifier.fillMaxWidth().height(168.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ════════════════════════════════════════════════════════════════
            // ROW 2 — File Analyzer (tall) | OCR + Screenshot (stacked)
            // ════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToolLargeCard(
                    icon        = Icons.Default.FolderOpen,
                    title       = "File Analyzer",
                    description = "Summarize PDFs and documents with AI",
                    dark        = dark,
                    onClick     = onNavigateToPdf,
                    modifier    = Modifier.weight(1f).height(272.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToolMediumCard(
                        icon        = Icons.Default.DocumentScanner,
                        title       = "OCR Scanner",
                        description = "Extract text from images",
                        dark        = dark,
                        onClick     = onNavigateToOcr,
                        modifier    = Modifier.fillMaxWidth().height(130.dp)
                    )
                    ToolMediumCard(
                        icon        = Icons.Default.ScreenSearchDesktop,
                        title       = "Screenshot",
                        description = "Explain errors & code",
                        dark        = dark,
                        onClick     = onNavigateToScreenshot,
                        modifier    = Modifier.fillMaxWidth().height(130.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ════════════════════════════════════════════════════════════════
            // ROW 3 — Smart Notes
            //
            // The Quiz Generator tile is gone. Jaagruk's assessment engine measures a
            // decision under time pressure and signs the result into a certificate; a
            // second, unscored quiz generator alongside it would invite exactly the
            // "I passed the quiz" confusion the whole project exists to correct.
            // ════════════════════════════════════════════════════════════════
            ToolMediumCard(
                icon        = Icons.Default.EditNote,
                title       = "Smart Notes",
                description = "AI-powered note taking",
                dark        = dark,
                onClick     = {},
                modifier    = Modifier.fillMaxWidth().height(144.dp)
            )

            Spacer(Modifier.height(bottomPadding + 28.dp))
        }
    }
}

// ── Hero card ─────────────────────────────────────────────────────────────────

@Composable
private fun ToolHeroCard(
    icon        : ImageVector,
    title       : String,
    description : String,
    badge       : String,
    dark        : Boolean,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.98f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "h"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(listOf(Blue500, Blue400)),
                    RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 22.dp, end = 18.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Blue50, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = Blue500, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (dark) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .background(Blue50, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Blue500,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Icon(
                    Icons.Default.ArrowForward, null,
                    tint = Blue500,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (dark) TextSecondary else TextSecondaryLight,
                lineHeight = 18.sp
            )
        }
    }
}

// ── Large card ────────────────────────────────────────────────────────────────

@Composable
private fun ToolLargeCard(
    icon        : ImageVector,
    title       : String,
    description : String,
    dark        : Boolean,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.97f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "l"
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
            modifier = Modifier.size(48.dp).background(Blue50, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(23.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (dark) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (dark) TextSecondary else TextSecondaryLight,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(2.dp))
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

// ── Medium card ───────────────────────────────────────────────────────────────

@Composable
private fun ToolMediumCard(
    icon        : ImageVector,
    title       : String,
    description : String,
    dark        : Boolean,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier
) {
    val src     = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale   by animateFloatAsState(
        if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "m"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(18.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier.size(38.dp).background(Blue50, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (dark) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (dark) TextSecondary else TextSecondaryLight,
                maxLines = 2,
                lineHeight = 16.sp
            )
        }
    }
}
