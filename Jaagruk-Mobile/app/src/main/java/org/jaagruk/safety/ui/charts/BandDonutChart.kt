package org.jaagruk.safety.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.safety.ui.theme.Clay500
import org.jaagruk.safety.ui.theme.Motion
import org.jaagruk.safety.ui.theme.Space
import org.jaagruk.safety.ui.theme.signalsFor

/** One band's headcount. */
class BandCount(val band: ReadinessBand, val workers: Int)

/**
 * Workforce readiness split, as a donut.
 *
 * The centre does not show the total. It shows **statutorily valid but operationally stale** - the
 * count of workers whose certificate a regulator would accept today and whose readiness this model
 * says has decayed past useful. That cohort is the finding this whole project exists to surface,
 * and a blended "78% compliant" number is precisely what hides it. Putting the total in the middle
 * would waste the one place on the chart everybody looks.
 *
 * @param statutorilyValidButStale workers holding a valid certificate whose band is not READY.
 */
@Composable
fun BandDonutChart(
    counts: List<BandCount>,
    statutorilyValidButStale: Int,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    diameter: Dp = 168.dp,
) {
    val signals = signalsFor(darkTheme)
    val total = counts.sumOf { it.workers }.coerceAtLeast(1)

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(counts) { target = 1f }
    val progress by animateFloatAsState(target, Motion.sweep(), label = "band-donut")

    val spoken = counts.joinToString(", ") { "${it.band.name.lowercase()} ${it.workers}" } +
        ". $statutorilyValidButStale workers hold a valid certificate but are no longer ready."

    Row(
        modifier = modifier.semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(diameter)) {
                val ringWidth = size.minDimension * 0.16f
                val inset = ringWidth / 2f
                val arcSize = Size(size.width - ringWidth, size.height - ringWidth)

                // Track, so the ring reads as a whole even when one band dominates.
                drawArc(
                    color = if (darkTheme) Color(0xFF2A3236) else Color(0xFFE8E2D8),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = ringWidth),
                )

                // Starts at -90 so the first band begins at twelve o'clock, which is where the eye
                // starts reading a dial.
                var startAngle = -90f
                counts.forEach { entry ->
                    val sweep = (entry.workers.toFloat() / total) * 360f * progress
                    drawArc(
                        color = bandPaint(entry.band, signals),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = ringWidth),
                    )
                    startAngle += (entry.workers.toFloat() / total) * 360f
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$statutorilyValidButStale",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "valid but\nnot ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            counts.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    // Colour AND shape AND label. Roughly one man in twelve is red-green colour
                    // blind, and this legend is how a site officer decides who to pull off shift.
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(bandPaint(entry.band, signals), bandShape(entry.band)),
                    )
                    Text(
                        text = entry.band.name.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = "${entry.workers}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun bandPaint(
    band: ReadinessBand,
    signals: org.jaagruk.safety.ui.theme.SignalColors,
): Color = when (band) {
    ReadinessBand.READY -> signals.green
    ReadinessBand.DUE -> signals.amber
    ReadinessBand.STALE -> Clay500
    ReadinessBand.EXPIRED -> signals.red
}

/**
 * A distinct silhouette per band, so the legend still works in greyscale, in direct sunlight, or
 * to somebody who cannot tell the amber from the green.
 */
private fun bandShape(band: ReadinessBand): Shape = when (band) {
    ReadinessBand.READY -> CircleShape
    ReadinessBand.DUE -> RoundedCornerShape(3.dp)
    ReadinessBand.STALE -> CutCornerShape(4.dp)
    ReadinessBand.EXPIRED -> CutCornerShape(percent = 50)
}
