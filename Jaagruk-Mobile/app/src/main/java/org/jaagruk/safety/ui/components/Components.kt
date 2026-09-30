package org.jaagruk.safety.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jaagruk.safety.ui.theme.*

@Composable
fun GradientBackground(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // This is the background of every screen in the app, and it was the last thing still making
    // the UI read blue-grey: the light branch was a flat #F8FAFC, which is a cold blue-tinted
    // white, and the dark branch was a navy gradient. Both are now warm neutrals from the palette.
    //
    // The gradient is deliberately shallow. A visible sweep behind a chart competes with the chart,
    // and behind Devanagari or Ol Chiki it reduces contrast unevenly down the page.
    val bgModifier = if (darkTheme) {
        modifier.background(Ink900)
    } else {
        modifier.background(Sand50)
    }
    Box(
        modifier = bgModifier,
        content  = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (darkTheme) DarkSurface else LightSurface)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun WaveformAnimation(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    color: Color = Blue500
) {
    if (!isActive) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(20) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color.copy(alpha = 0.35f))
                )
            }
        }
        return
    }
    val inf = rememberInfiniteTransition(label = "wave")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(20) { i ->
            val height by inf.animateFloat(
                initialValue = 3f,
                targetValue = if (isActive) (5 + (i % 5) * 5).toFloat() else 3f,
                animationSpec = infiniteRepeatable(
                    tween(300 + i * 30, easing = EaseInOut),
                    RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun AITaskCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (darkTheme) DarkSurface else LightSurface)
            .border(1.dp, if (darkTheme) DarkBorder else LightBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Blue50, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (darkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (darkTheme) TextSecondary else TextSecondaryLight
            )
        }
        Icon(
            Icons.Default.ChevronRight, null,
            tint = Blue500.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}
