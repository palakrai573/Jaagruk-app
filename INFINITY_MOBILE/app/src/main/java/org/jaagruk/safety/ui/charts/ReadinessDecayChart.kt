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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.core.retention.ReadinessCalculator
import org.jaagruk.safety.ui.theme.Motion
import org.jaagruk.safety.ui.theme.SignalAmberTint
import org.jaagruk.safety.ui.theme.SignalColors
import org.jaagruk.safety.ui.theme.SignalGreenTint
import org.jaagruk.safety.ui.theme.SignalRedTint
import org.jaagruk.safety.ui.theme.signalsFor
import kotlin.math.ceil
import kotlin.math.ln

/**
 * The readiness decay curve.
 *
 * This is the chart that makes Jaagruk's whole argument, and it is the reason there is no chart
 * library in this project. What it has to show is not a line: it is a line *against four named
 * bands*, with the day the line crosses each one called out, and a marker for today. No
 * off-the-shelf chart annotates that without being fought, and fighting a chart library produces
 * more code than drawing the thing.
 *
 * Every value comes from [ReadinessCalculator]. The curve is not a re-derivation of
 * `0.5^(days/halfLife)` that happens to look similar - it is the same function, with the same
 * constants, that decides whether the worker is certified. A chart that drifted from the engine
 * would be worse than no chart.
 *
 * @param baseScorePermille the consolidated score, 0..1000.
 * @param refresherStage completed refreshers. Each one flattens the curve.
 * @param daysElapsed where "today" sits on the curve.
 * @param horizonDays how far right to draw.
 */
@Composable
fun ReadinessDecayChart(
    baseScorePermille: Int,
    refresherStage: Int,
    daysElapsed: Int,
    modifier: Modifier = Modifier,
    horizonDays: Int = 180,
    darkTheme: Boolean = false,
    height: Dp = 200.dp,
) {
    val measurer = rememberTextMeasurer()
    val signals = signalsFor(darkTheme)

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(baseScorePermille, refresherStage, horizonDays) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.draw(),
        label = "decay-curve",
    )

    val todayReadiness = readinessAtDay(baseScorePermille, refresherStage, daysElapsed)
    val todayBand = ReadinessCalculator.band(todayReadiness)
    val daysToReady = daysUntil(baseScorePermille, refresherStage, ReadinessCalculator.READY_THRESHOLD)

    // Spoken aloud rather than described as "a chart": TalkBack reading "line graph" to a worker
    // who cannot see it conveys nothing, whereas the numbers and the deadline do.
    val spoken = buildString {
        append("Readiness today $todayReadiness of 1000, band ${todayBand.name.lowercase()}. ")
        if (daysToReady != null && daysToReady > daysElapsed) {
            append("Drops below ready in ${daysToReady - daysElapsed} days.")
        } else {
            append("Already below ready.")
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = spoken },
    ) {
        val leftGutter = 44f
        val rightPad = 12f
        val topPad = 14f
        val bottomGutter = 26f
        val plotLeft = leftGutter
        val plotTop = topPad
        val plotRight = size.width - rightPad
        val plotBottom = size.height - bottomGutter
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun yFor(permille: Int): Float = plotBottom - (permille / 1000f) * plotHeight
        fun xFor(day: Int): Float = plotLeft + (day.toFloat() / horizonDays) * plotWidth

        // ── Band regions ────────────────────────────────────────────────────
        // Painted as tints rather than lines. A worker is not reading a value off an axis; they
        // are asking "which colour am I in", and a filled region answers that pre-attentively.
        drawBand(plotLeft, plotWidth, yFor(1000), yFor(700), SignalGreenTint, darkTheme)
        drawBand(plotLeft, plotWidth, yFor(700), yFor(500), SignalAmberTint, darkTheme)
        drawBand(plotLeft, plotWidth, yFor(500), yFor(300), Color(0xFFF6E2DC), darkTheme)
        drawBand(plotLeft, plotWidth, yFor(300), yFor(0), SignalRedTint, darkTheme)

        // ── Threshold guides + labels ───────────────────────────────────────
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        listOf(
            ReadinessCalculator.READY_THRESHOLD to ReadinessBand.READY,
            ReadinessCalculator.DUE_THRESHOLD to ReadinessBand.DUE,
            ReadinessCalculator.STALE_THRESHOLD to ReadinessBand.STALE,
        ).forEach { (threshold, band) ->
            val y = yFor(threshold)
            drawLine(
                color = bandColor(band, signals).copy(alpha = 0.55f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.5f,
                pathEffect = dash,
            )
            val label = measurer.measure(
                text = threshold.toString(),
                style = TextStyle(fontSize = 10.sp, color = axisInk(darkTheme)),
            )
            drawText(label, topLeft = Offset(2f, y - label.size.height / 2f))
        }

        // ── The curve ───────────────────────────────────────────────────────
        // Sampled per day rather than per pixel so the shape is the model's, not the canvas's.
        val visibleDays = (horizonDays * progress).toInt().coerceAtLeast(1)
        val path = Path()
        for (day in 0..visibleDays) {
            val x = xFor(day)
            val y = yFor(readinessAtDay(baseScorePermille, refresherStage, day))
            if (day == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = bandColor(todayBand, signals),
            style = Stroke(width = 3.5f),
        )

        // ── Today ───────────────────────────────────────────────────────────
        if (progress > 0.35f && daysElapsed <= horizonDays) {
            val x = xFor(daysElapsed)
            val y = yFor(todayReadiness)
            drawLine(
                color = axisInk(darkTheme).copy(alpha = 0.35f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1.5f,
                pathEffect = dash,
            )
            // Halo then dot, so the marker survives on top of any band tint.
            drawCircle(color = if (darkTheme) Color(0xFF14181A) else Color.White, radius = 7f, center = Offset(x, y))
            drawCircle(color = bandColor(todayBand, signals), radius = 4.5f, center = Offset(x, y))

            val callout = measurer.measure(
                text = "$todayReadiness",
                style = TextStyle(fontSize = 12.sp, color = bandColor(todayBand, signals)),
            )
            // Flipped to the left of the marker when it would otherwise run off the right edge.
            val calloutX = if (x + 10f + callout.size.width < plotRight) x + 10f else x - 10f - callout.size.width
            drawText(callout, topLeft = Offset(calloutX, y - callout.size.height - 6f))
        }

        // ── Day axis ────────────────────────────────────────────────────────
        listOf(0, horizonDays / 3, 2 * horizonDays / 3, horizonDays).forEach { day ->
            val tick = measurer.measure(
                text = if (day == 0) "today" else "${day}d",
                style = TextStyle(fontSize = 10.sp, color = axisInk(darkTheme)),
            )
            val x = (xFor(day) - tick.size.width / 2f).coerceIn(0f, size.width - tick.size.width)
            drawText(tick, topLeft = Offset(x, plotBottom + 6f))
        }
    }
}

private fun DrawScope.drawBand(
    left: Float,
    width: Float,
    top: Float,
    bottom: Float,
    tint: Color,
    darkTheme: Boolean,
) {
    // Dark mode gets the same hue at low alpha instead of the printed tint: a pale tint designed
    // for paper turns into a bright slab on a near-black background.
    val color = if (darkTheme) tint.copy(alpha = 0.10f) else tint
    drawRect(color = color, topLeft = Offset(left, top), size = Size(width, bottom - top))
}

private fun bandColor(band: ReadinessBand, signals: SignalColors): Color =
    when (band) {
        ReadinessBand.READY -> signals.green
        ReadinessBand.DUE -> signals.amber
        ReadinessBand.STALE -> Color(0xFFB4523F)
        ReadinessBand.EXPIRED -> signals.red
    }

private fun axisInk(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFFA7ADA9) else Color(0xFF5C5F5C)

/**
 * Readiness on a given day, straight from the engine.
 *
 * Expressed as an epoch offset because that is the signature [ReadinessCalculator] exposes, and
 * calling it with day zero as the pass date is exactly equivalent to asking "what is readiness
 * after N days" without adding a second code path that could disagree.
 */
private fun readinessAtDay(baseScorePermille: Int, refresherStage: Int, day: Int): Int =
    ReadinessCalculator.readiness(
        baseScore = baseScorePermille,
        lastPassAtEpochSec = 0L,
        nowEpochSec = day.toLong() * ReadinessCalculator.SECONDS_PER_DAY,
        refresherStage = refresherStage,
    )

/** First day readiness is at or below [targetPermille], or null if it starts there or never gets there. */
private fun daysUntil(baseScorePermille: Int, refresherStage: Int, targetPermille: Int): Int? {
    if (baseScorePermille <= targetPermille) return null
    val halfLife = ReadinessCalculator.halfLifeDays(refresherStage)
    // base * 0.5^(d/hl) = target  ->  d = hl * log2(base/target)
    val days = halfLife * (ln(baseScorePermille.toDouble() / targetPermille) / ln(2.0))
    return ceil(days).toInt()
}
