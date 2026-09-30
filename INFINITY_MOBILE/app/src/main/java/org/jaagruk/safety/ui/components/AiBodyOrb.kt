package org.jaagruk.safety.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.ui.theme.*

/**
 * OrbState — a simplified state enum used only by the orb for animation decisions.
 * We map both the old AiState and new AIInferenceState to this.
 */
enum class OrbState { Idle, Loading, Thinking, Responding, Error }

/** Map AIInferenceState → OrbState */
fun AIInferenceState.toOrbState(): OrbState = when (this) {
    is AIInferenceState.Idle      -> OrbState.Idle
    is AIInferenceState.Loading   -> OrbState.Loading
    is AIInferenceState.Thinking  -> OrbState.Thinking
    is AIInferenceState.Responding -> OrbState.Responding
    is AIInferenceState.Error     -> OrbState.Error
}

@Composable
fun AiBodyOrb(
    orbState: OrbState,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    val inf = rememberInfiniteTransition(label = "orb")

    // ── Pulse (breathing) ────────────────────────────────────────────────────
    val pulseSpec = when (orbState) {
        OrbState.Idle      -> tween<Float>(2800, easing = EaseInOut)
        OrbState.Loading   -> tween(1200, easing = EaseInOut)
        OrbState.Thinking  -> tween(1000, easing = EaseInOut)
        OrbState.Responding -> tween(900, easing = EaseInOut)
        OrbState.Error     -> tween(400,  easing = EaseInOut)
    }
    val pulseMin = when (orbState) {
        OrbState.Idle      -> 0.96f
        OrbState.Loading   -> 0.92f
        OrbState.Thinking  -> 0.93f
        OrbState.Responding -> 0.92f
        OrbState.Error     -> 0.88f
    }
    val pulseMax = when (orbState) {
        OrbState.Idle      -> 1.04f
        OrbState.Loading   -> 1.06f
        OrbState.Thinking  -> 1.07f
        OrbState.Responding -> 1.08f
        OrbState.Error     -> 1.12f
    }
    val pulse by inf.animateFloat(
        initialValue = pulseMin, targetValue = pulseMax,
        animationSpec = infiniteRepeatable(pulseSpec, RepeatMode.Reverse),
        label = "pulse"
    )

    // ── Rotation ─────────────────────────────────────────────────────────────
    val rotationSpeed = when (orbState) {
        OrbState.Thinking  -> 6000
        OrbState.Responding -> 4000
        OrbState.Loading   -> 8000
        else               -> 20000
    }
    val rotation by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(rotationSpeed, easing = LinearEasing)),
        label = "rotation"
    )

    // ── Drift ─────────────────────────────────────────────────────────────────
    val drift by inf.animateFloat(
        initialValue = -8f, targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(3400, easing = EaseInOut), RepeatMode.Reverse),
        label = "drift"
    )

    // ── Ripple ────────────────────────────────────────────────────────────────
    val ripple by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "ripple"
    )

    // ── Colors ────────────────────────────────────────────────────────────────
    val coreInner by animateColorAsState(
        targetValue = when (orbState) {
            // Teal family, with amber reserved for THINKING so the one state where the worker is
            // waiting on the model is the one state that changes hue. The orb is no longer the
            // app's identity - it is off the dashboard and survives only as this indicator - so it
            // borrows the brand rather than owning a palette of its own.
            OrbState.Idle      -> if (isDarkTheme) Teal500 else Teal700
            OrbState.Loading   -> Teal700
            OrbState.Thinking  -> Amber600
            OrbState.Responding -> Teal500
            OrbState.Error     -> SignalRed
        },
        animationSpec = tween(700), label = "coreInner"
    )
    val coreMid by animateColorAsState(
        targetValue = when (orbState) {
            OrbState.Idle      -> if (isDarkTheme) Teal900 else Teal200
            OrbState.Loading   -> Teal900
            OrbState.Thinking  -> Color(0xFF4A2708)
            OrbState.Responding -> Teal900
            OrbState.Error     -> Color(0xFF5C0713)
        },
        animationSpec = tween(700), label = "coreMid"
    )
    val ringColor by animateColorAsState(
        targetValue = when (orbState) {
            OrbState.Idle      -> Teal500.copy(alpha = 0.18f)
            OrbState.Loading   -> Indigo500.copy(alpha = 0.30f)
            OrbState.Thinking  -> Indigo500.copy(alpha = 0.28f)
            OrbState.Responding -> Teal500.copy(alpha = 0.32f)
            OrbState.Error     -> Clay500.copy(alpha = 0.30f)
        },
        animationSpec = tween(700), label = "ringColor"
    )
    val glowColor by animateColorAsState(
        targetValue = when (orbState) {
            OrbState.Idle      -> Teal500.copy(alpha = 0.08f)
            OrbState.Loading   -> Indigo500.copy(alpha = 0.14f)
            OrbState.Thinking  -> Indigo500.copy(alpha = 0.16f)
            OrbState.Responding -> Teal500.copy(alpha = 0.18f)
            OrbState.Error     -> Clay500.copy(alpha = 0.18f)
        },
        animationSpec = tween(700), label = "glowColor"
    )

    val showRipple = orbState == OrbState.Responding || orbState == OrbState.Loading
    val showArcs   = orbState == OrbState.Thinking || orbState == OrbState.Responding || orbState == OrbState.Loading

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.toPx() / 2f
            val cy = size.toPx() / 2f
            val r  = size.toPx() / 2f

            drawAmbientGlow(cx, cy, r, glowColor, pulse)
            if (showRipple) drawRippleRing(cx, cy, r, ringColor, ripple)
            drawOuterRing(cx, cy, r, ringColor, pulse, drift)
            if (showArcs) {
                rotate(rotation, pivot = Offset(cx, cy)) {
                    drawArcSegments(cx, cy, r * 0.72f, coreInner)
                }
            }
            drawMidSphere(cx, cy, r, coreMid, coreInner, pulse)
            drawCoreSphere(cx, cy, r, coreInner, coreMid)
            drawSpecular(cx, cy, r)
        }
    }
}

// ── Draw helpers ──────────────────────────────────────────────────────────────

private fun DrawScope.drawAmbientGlow(cx: Float, cy: Float, r: Float, color: Color, pulse: Float) {
    val glowR = r * 1.35f * pulse
    drawCircle(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), Offset(cx, cy), glowR),
        radius = glowR, center = Offset(cx, cy)
    )
}

private fun DrawScope.drawRippleRing(cx: Float, cy: Float, r: Float, color: Color, ripple: Float) {
    val rippleR = r * (0.85f + ripple * 0.55f)
    val alpha   = (1f - ripple) * color.alpha
    drawCircle(
        color = color.copy(alpha = alpha.coerceIn(0f, 1f)), radius = rippleR,
        center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
    )
    val ripple2 = (ripple + 0.4f) % 1f
    val rippleR2 = r * (0.85f + ripple2 * 0.55f)
    val alpha2   = (1f - ripple2) * color.alpha * 0.6f
    drawCircle(
        color = color.copy(alpha = alpha2.coerceIn(0f, 1f)), radius = rippleR2,
        center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.drawOuterRing(cx: Float, cy: Float, r: Float, color: Color, pulse: Float, drift: Float) {
    val ringR = r * 0.88f * pulse
    val ox = cx + drift * 0.3f; val oy = cy + drift * 0.2f
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.Transparent, color, Color.Transparent), Offset(ox, oy), ringR),
        radius = ringR, center = Offset(ox, oy)
    )
}

private fun DrawScope.drawArcSegments(cx: Float, cy: Float, r: Float, color: Color) {
    val strokeW = 1.2.dp.toPx()
    listOf(0f to 55f, 80f to 40f, 145f to 65f, 230f to 35f, 290f to 50f).forEach { (start, sweep) ->
        drawArc(
            color = color.copy(alpha = 0.45f), startAngle = start, sweepAngle = sweep,
            useCenter = false, topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawMidSphere(cx: Float, cy: Float, r: Float, colorOuter: Color, colorInner: Color, pulse: Float) {
    val midR = r * 0.72f * pulse
    drawCircle(
        brush = Brush.radialGradient(
            listOf(colorInner.copy(0.55f), colorOuter.copy(0.35f), colorOuter.copy(0.08f), Color.Transparent),
            Offset(cx, cy), midR
        ), radius = midR, center = Offset(cx, cy)
    )
}

private fun DrawScope.drawCoreSphere(cx: Float, cy: Float, r: Float, colorInner: Color, colorOuter: Color) {
    val coreR = r * 0.46f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(colorInner.copy(0.90f), colorInner.copy(0.70f), colorOuter.copy(0.50f), colorOuter.copy(0.15f)),
            Offset(cx - coreR * 0.15f, cy - coreR * 0.15f), coreR * 1.2f
        ), radius = coreR, center = Offset(cx, cy)
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(0.18f)),
            Offset(cx + coreR * 0.3f, cy + coreR * 0.3f), coreR
        ), radius = coreR, center = Offset(cx, cy)
    )
}

private fun DrawScope.drawSpecular(cx: Float, cy: Float, r: Float) {
    val coreR = r * 0.46f
    val specR = coreR * 0.28f
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White.copy(0.55f), Color.Transparent),
            Offset(cx - coreR * 0.32f, cy - coreR * 0.32f), specR),
        radius = specR, center = Offset(cx - coreR * 0.32f, cy - coreR * 0.32f)
    )
    val spec2R = coreR * 0.10f
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White.copy(0.30f), Color.Transparent),
            Offset(cx + coreR * 0.25f, cy - coreR * 0.40f), spec2R),
        radius = spec2R, center = Offset(cx + coreR * 0.25f, cy - coreR * 0.40f)
    )
}
