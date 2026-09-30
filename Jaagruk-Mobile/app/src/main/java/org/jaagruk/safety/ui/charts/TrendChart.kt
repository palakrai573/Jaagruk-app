package org.jaagruk.safety.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.core.assessment.AssessmentConfig
import org.jaagruk.safety.ui.theme.Motion
import org.jaagruk.safety.ui.theme.Teal200
import org.jaagruk.safety.ui.theme.Teal700
import org.jaagruk.safety.ui.theme.signalsFor

/** One period: how many drills ran, and what share of them passed. */
class TrendPoint(
    val label: String,
    val drills: Int,
    val passRatePermille: Int,
)

/**
 * Drill volume as bars, pass rate as a line over the top.
 *
 * Two measures on one chart because they are only meaningful together. Volume alone rewards a site
 * for running drills nobody passes; pass rate alone looks excellent on a site that ran four drills
 * all month. The pair is what a compliance officer actually needs, and the pass line from
 * [AssessmentConfig] is drawn so "above or below the bar we certify at" is readable at a glance.
 *
 * Volume is on its own scale, normalised to the largest period, and deliberately has no axis
 * labels: the bars are there for shape and relative height, and a second numeric axis would imply
 * a precision that a bar three pixels wide cannot carry.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    height: Dp = 180.dp,
) {
    if (points.isEmpty()) return

    val measurer = rememberTextMeasurer()
    val signals = signalsFor(darkTheme)
    val ink = if (darkTheme) Color(0xFFA7ADA9) else Color(0xFF5C5F5C)
    val barColour = if (darkTheme) Teal200.copy(alpha = 0.45f) else Teal700.copy(alpha = 0.22f)
    val lineColour = if (darkTheme) Teal200 else Teal700

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(points) { target = 1f }
    val progress by animateFloatAsState(target, Motion.draw(), label = "trend")

    val maxDrills = (points.maxOfOrNull { it.drills } ?: 1).coerceAtLeast(1)
    val spoken = points.joinToString(", ") {
        "${it.label}: ${it.drills} drills, ${it.passRatePermille / 10}% passed"
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = spoken },
    ) {
        val leftGutter = 36f
        val bottomGutter = 24f
        val topPad = 14f
        val rightPad = 10f
        val plotLeft = leftGutter
        val plotTop = topPad
        val plotRight = size.width - rightPad
        val plotBottom = size.height - bottomGutter
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        val slot = plotW / points.size
        val barWidth = (slot * 0.52f).coerceAtMost(28f)

        fun yForRate(permille: Int) = plotBottom - (permille / 1000f) * plotH
        fun centreX(index: Int) = plotLeft + slot * index + slot / 2f

        // ── Pass threshold ──────────────────────────────────────────────────
        val passY = yForRate(AssessmentConfig.DEFAULT_PASS_THRESHOLD_PERMILLE)
        drawLine(
            color = signals.green.copy(alpha = 0.7f),
            start = Offset(plotLeft, passY),
            end = Offset(plotRight, passY),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        // ── Volume bars ─────────────────────────────────────────────────────
        // Grown from the baseline rather than faded in, so the animation reads as counting up.
        points.forEachIndexed { index, point ->
            val full = (point.drills.toFloat() / maxDrills) * plotH
            val h = full * progress
            drawRoundRect(
                color = barColour,
                topLeft = Offset(centreX(index) - barWidth / 2f, plotBottom - h),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }

        // ── Pass-rate line ──────────────────────────────────────────────────
        val shown = (points.size * progress).toInt().coerceAtLeast(1)
        val path = Path()
        points.take(shown).forEachIndexed { index, point ->
            val x = centreX(index)
            val y = yForRate(point.passRatePermille)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColour, style = Stroke(width = 3f))

        points.take(shown).forEachIndexed { index, point ->
            val centre = Offset(centreX(index), yForRate(point.passRatePermille))
            // Halo first so the dot stays legible where the line crosses a bar.
            drawCircle(if (darkTheme) Color(0xFF1D2326) else Color.White, radius = 5.5f, center = centre)
            drawCircle(
                color = if (point.passRatePermille >= AssessmentConfig.DEFAULT_PASS_THRESHOLD_PERMILLE) {
                    signals.green
                } else {
                    signals.amber
                },
                radius = 3.5f,
                center = centre,
            )
        }

        // ── Axes ────────────────────────────────────────────────────────────
        listOf(0, 500, 1000).forEach { permille ->
            val label = measurer.measure(
                text = "${permille / 10}%",
                style = TextStyle(fontSize = 10.sp, color = ink),
            )
            drawText(label, topLeft = Offset(2f, yForRate(permille) - label.size.height / 2f))
        }
        // Every period label if they fit, otherwise first and last only: overlapping tick labels
        // are worse than absent ones.
        val sample = measurer.measure(points.first().label, TextStyle(fontSize = 10.sp, color = ink))
        val everyLabelFits = sample.size.width < slot * 0.9f
        points.forEachIndexed { index, point ->
            if (!everyLabelFits && index != 0 && index != points.lastIndex) return@forEachIndexed
            val label = measurer.measure(point.label, TextStyle(fontSize = 10.sp, color = ink))
            val x = (centreX(index) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, plotBottom + 5f))
        }
    }
}
