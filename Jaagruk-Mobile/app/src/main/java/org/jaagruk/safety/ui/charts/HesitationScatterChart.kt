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
import org.jaagruk.safety.ui.theme.Space
import org.jaagruk.safety.ui.theme.signalsFor

/**
 * One worker's run, plotted.
 *
 * @param accuracyPermille share of steps answered correctly, 0..1000.
 * @param medianLatencyMs the measurement that matters, from a monotonic clock.
 */
class HesitationPoint(
    val accuracyPermille: Int,
    val medianLatencyMs: Long,
    val label: String = "",
)

/**
 * Accuracy against decision latency, with the correct-but-slow quadrant called out.
 *
 * `CORRECT_SLOW` is the reason this project exists. A quiz records it as a pass; here it lands in
 * its own visible region of the chart, above the pass line and to the right of the slow threshold -
 * workers who know the answer and would freeze anyway. That cohort is invisible in every
 * pass/fail report, and it is the one a site officer can actually do something about.
 *
 * Both boundaries come from [AssessmentConfig]: the pass line is the same threshold that decides
 * certification, and the slow line is `expertMs * SLOW_FACTOR`, the same product that classifies an
 * outcome. Drawing either from a different number would put a worker on the wrong side of a line
 * the engine disagrees with.
 *
 * @param expertBaselineMs the authored expert time for this scenario.
 */
@Composable
fun HesitationScatterChart(
    points: List<HesitationPoint>,
    expertBaselineMs: Long,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    height: Dp = 230.dp,
) {
    val measurer = rememberTextMeasurer()
    val signals = signalsFor(darkTheme)
    val ink = if (darkTheme) Color(0xFFA7ADA9) else Color(0xFF5C5F5C)

    val slowMs = (expertBaselineMs * AssessmentConfig.SLOW_FACTOR).toLong()
    val passLine = AssessmentConfig.DEFAULT_PASS_THRESHOLD_PERMILLE

    // The axis has to hold the slow line with room to its right, or every hesitant worker piles up
    // against the edge and the quadrant it exists to show has no area.
    val maxLatency = maxOf(
        points.maxOfOrNull { it.medianLatencyMs } ?: slowMs,
        slowMs * 3 / 2,
    ).toFloat()

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(points) { target = 1f }
    val progress by animateFloatAsState(target, Motion.draw(), label = "hesitation-scatter")

    val slowAndCorrect = points.count {
        it.accuracyPermille >= passLine && it.medianLatencyMs > slowMs
    }
    val spoken = "${points.size} runs plotted. $slowAndCorrect were correct but slower than " +
        "twice the expert baseline of ${expertBaselineMs}ms, so they are recorded as hesitation."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = spoken },
    ) {
        val leftGutter = 40f
        val bottomGutter = 28f
        val topPad = 18f
        val rightPad = 12f
        val plotLeft = leftGutter
        val plotTop = topPad
        val plotRight = size.width - rightPad
        val plotBottom = size.height - bottomGutter
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        fun xFor(latencyMs: Long) = plotLeft + (latencyMs / maxLatency).coerceIn(0f, 1f) * plotW
        fun yFor(permille: Int) = plotBottom - (permille / 1000f) * plotH

        val slowX = xFor(slowMs)
        val passY = yFor(passLine)

        // ── The quadrant that matters ───────────────────────────────────────
        // Right of the slow line, above the pass line: knows it, would freeze.
        drawRect(
            color = signals.amber.copy(alpha = if (darkTheme) 0.14f else 0.12f),
            topLeft = Offset(slowX, plotTop),
            size = Size(plotRight - slowX, passY - plotTop),
        )
        val quadLabel = measurer.measure(
            text = "correct but slow",
            style = TextStyle(fontSize = 10.sp, color = signals.amber),
        )
        if (plotRight - slowX > quadLabel.size.width + 8f) {
            drawText(quadLabel, topLeft = Offset(slowX + 6f, plotTop + 4f))
        }

        // ── Suspiciously fast ───────────────────────────────────────────────
        // Below human reaction time. Three of these void a run as a tap-through, so the region is
        // marked rather than left as an unexplained cluster against the y axis.
        val fastX = xFor(AssessmentConfig.SUSPICIOUS_FAST_MS)
        if (fastX > plotLeft + 2f) {
            drawRect(
                color = signals.red.copy(alpha = if (darkTheme) 0.16f else 0.10f),
                topLeft = Offset(plotLeft, plotTop),
                size = Size(fastX - plotLeft, plotH),
            )
        }

        // ── Boundaries ──────────────────────────────────────────────────────
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        drawLine(
            color = signals.amber,
            start = Offset(slowX, plotTop),
            end = Offset(slowX, plotBottom),
            strokeWidth = 2f,
            pathEffect = dash,
        )
        drawLine(
            color = signals.green,
            start = Offset(plotLeft, passY),
            end = Offset(plotRight, passY),
            strokeWidth = 2f,
            pathEffect = dash,
        )
        // The expert baseline itself, solid: this is a real measured-against value, not a threshold.
        drawLine(
            color = ink.copy(alpha = 0.5f),
            start = Offset(xFor(expertBaselineMs), plotTop),
            end = Offset(xFor(expertBaselineMs), plotBottom),
            strokeWidth = 1.5f,
        )

        // ── Points ──────────────────────────────────────────────────────────
        val shown = (points.size * progress).toInt()
        points.take(shown).forEach { p ->
            val cx = xFor(p.medianLatencyMs)
            val cy = yFor(p.accuracyPermille)
            val correct = p.accuracyPermille >= passLine
            val slow = p.medianLatencyMs > slowMs
            val colour = when {
                p.medianLatencyMs < AssessmentConfig.SUSPICIOUS_FAST_MS -> signals.red
                correct && slow -> signals.amber
                correct -> signals.green
                else -> signals.red
            }
            // A ring rather than a filled dot for the hesitation cohort, so it is distinguishable
            // without relying on the amber-versus-green difference.
            if (correct && slow) {
                drawCircle(color = colour, radius = 5.5f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
            } else {
                drawCircle(color = colour.copy(alpha = 0.85f), radius = 5f, center = Offset(cx, cy))
            }
        }

        // ── Axes ────────────────────────────────────────────────────────────
        listOf(0, 500, 1000).forEach { permille ->
            val label = measurer.measure(
                text = "$permille",
                style = TextStyle(fontSize = 10.sp, color = ink),
            )
            drawText(label, topLeft = Offset(2f, yFor(permille) - label.size.height / 2f))
        }
        listOf(0L, (maxLatency / 2).toLong(), maxLatency.toLong()).forEach { ms ->
            val label = measurer.measure(
                text = if (ms == 0L) "0s" else "${"%.1f".format(ms / 1000f)}s",
                style = TextStyle(fontSize = 10.sp, color = ink),
            )
            val x = (xFor(ms) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, plotBottom + 6f))
        }
    }
}
