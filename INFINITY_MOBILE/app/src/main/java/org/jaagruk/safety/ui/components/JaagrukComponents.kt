package org.jaagruk.safety.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.safety.ui.theme.Clay500
import org.jaagruk.safety.ui.theme.Ink700
import org.jaagruk.safety.ui.theme.MinTouchTarget
import org.jaagruk.safety.ui.theme.Sand200
import org.jaagruk.safety.ui.theme.Motion
import org.jaagruk.safety.ui.theme.SignalColors
import org.jaagruk.safety.ui.theme.Space
import org.jaagruk.safety.ui.theme.signalsFor

/*
 * The component set. Two rules run through all of it.
 *
 * 1. **64 dp minimum touch target**, not Material's 48. A gloved contact patch is 15-20 mm and the
 *    finger cannot see its own centre. At 48 dp a glove slip lands on the neighbouring option, and
 *    in a drill that is recorded as a wrong decision and signed into a certificate.
 *
 * 2. **Status is never colour alone.** Every band and every state carries a colour, a distinct
 *    silhouette, and a text label. Roughly one man in twelve is red-green colour blind, and these
 *    are the controls that decide whether he enters a confined space.
 */

// ══ READINESS RING ═══════════════════════════════════════════════════════════

/**
 * The one number a worker sees first.
 *
 * Sweeps from zero on first composition, because the movement is what communicates that this is a
 * decaying quantity rather than a static grade. It sweeps once - re-entering the screen does not
 * replay it, or the animation becomes noise on the fifth visit of a shift.
 */
@Composable
fun ReadinessRing(
    readinessPermille: Int,
    band: ReadinessBand,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    diameter: Dp = 172.dp,
    caption: String? = null,
) {
    val signals = signalsFor(darkTheme)
    val colour = bandColour(band, signals)

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(readinessPermille) { target = readinessPermille / 1000f }
    val sweep by animateFloatAsState(target, Motion.sweep(), label = "readiness-ring")

    Box(
        modifier = modifier
            .size(diameter)
            // One description for the whole thing. Left to itself, TalkBack would read the ring,
            // the number and the label as three separate nodes.
            .clearAndSetSemantics {
                contentDescription = "Readiness $readinessPermille of 1000, " +
                    "${band.name.lowercase()}." + (caption?.let { " $it" } ?: "")
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = if (darkTheme) Ink700 else Sand200,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = colour,
                startAngle = -90f,
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$readinessPermille",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BandChip(band = band, darkTheme = darkTheme)
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
    }
}

// ══ STATUS ═══════════════════════════════════════════════════════════════════

/** A readiness band as a chip: colour, silhouette and word, all three. */
@Composable
fun BandChip(band: ReadinessBand, modifier: Modifier = Modifier, darkTheme: Boolean = false) {
    val signals = signalsFor(darkTheme)
    StatusChip(
        label = band.name.lowercase(),
        colour = bandColour(band, signals),
        shape = bandSilhouette(band),
        modifier = modifier,
        darkTheme = darkTheme,
    )
}

/**
 * @param shape the silhouette of the leading marker, which is what carries the meaning for a
 *   colour-blind reader. Never pass the same shape for two different statuses.
 */
@Composable
fun StatusChip(
    label: String,
    colour: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
) {
    Row(
        modifier = modifier
            .background(colour.copy(alpha = if (darkTheme) 0.20f else 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(modifier = Modifier.size(9.dp).background(colour, shape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (darkTheme) colour else colour,
        )
    }
}

// ══ BUTTONS ══════════════════════════════════════════════════════════════════

/**
 * Primary action. 64 dp tall, press-scales on a spring, and fires haptics.
 *
 * Haptics are not decoration here: a worker in gloves cannot feel a capacitive press and cannot
 * always see the button under their own hand, so the vibration is the confirmation that the tap
 * registered. Without it people double-tap, and a double-tap in a drill is a stale answer.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = Motion.pressSpring(),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = MinTouchTarget)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = Space.xl, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            androidx.compose.material3.Icon(
                imageVector = leading,
                // The label already says what this does; announcing the icon too would make
                // TalkBack read the action twice.
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            Box(Modifier.size(Space.sm))
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Secondary action: outlined, same 64 dp floor. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = Motion.pressSpring(),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = MinTouchTarget)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = Space.xl, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ══ SURFACES ═════════════════════════════════════════════════════════════════

/** The standard card: warm surface, hairline border, 20 dp radius. */
@Composable
fun JaagrukCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) Motion.PressScale else 1f,
        animationSpec = Motion.pressSpring(),
        label = "card-press",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(Space.lg),
        content = content,
    )
}

/** Section heading with an optional trailing count. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A single figure with a caption. Tabular numerals mean a row of these lines up. */
@Composable
fun StatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(accent.copy(alpha = 0.10f))
            .padding(Space.lg),
    ) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ══ SHARED MAPPINGS ══════════════════════════════════════════════════════════

internal fun bandColour(band: ReadinessBand, signals: SignalColors): Color = when (band) {
    ReadinessBand.READY -> signals.green
    ReadinessBand.DUE -> signals.amber
    ReadinessBand.STALE -> Clay500
    ReadinessBand.EXPIRED -> signals.red
}

/**
 * A distinct silhouette per band.
 *
 * These four must stay visually distinguishable at 9 dp in greyscale. Circle, rounded square, cut
 * corner and diamond were chosen because they differ in outline rather than in fill.
 */
internal fun bandSilhouette(band: ReadinessBand): Shape = when (band) {
    ReadinessBand.READY -> CircleShape
    ReadinessBand.DUE -> RoundedCornerShape(2.dp)
    ReadinessBand.STALE -> CutCornerShape(3.dp)
    ReadinessBand.EXPIRED -> CutCornerShape(percent = 50)
}
