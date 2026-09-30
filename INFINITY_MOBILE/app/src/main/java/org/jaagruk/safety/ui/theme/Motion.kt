package org.jaagruk.safety.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/*
 * The motion inventory, in one place, with a rule.
 *
 * ## The rule
 *
 * Nothing animates on the path of a decision that is being timed.
 *
 * Every drill step measures latency from a monotonic clock and compares it to an expert
 * baseline, and that number is signed into a certificate. A 300 ms entrance animation on an
 * option card adds 300 ms to every measurement taken through it. Worse, it adds it unevenly:
 * a device under thermal load drops frames and the animation takes longer, so the same worker
 * scores differently on a hot phone.
 *
 * So the split is deliberate. Entrances, transitions and charts animate freely, because nobody
 * is being scored while reading a dashboard. Drill option cards, confirm targets and the AR
 * reticle appear immediately and only ever animate *feedback* - a dwell ring filling, a colour
 * shift as a window closes - never their own arrival.
 */
object Motion {

    // ── Durations ────────────────────────────────────────────────────────────
    /** Colour and alpha changes. Below this a fade reads as a flicker. */
    const val Quick = 120

    /** Standard: chips, badges, small state changes. */
    const val Short = 200

    /** Screen and card transitions. */
    const val Medium = 320

    /** Rings and gauges sweeping to a value. Long enough to be legible as motion. */
    const val Long = 700

    /** Chart series drawing in. The longest thing in the app, and it earns it once per screen. */
    const val Chart = 900

    // ── Easings ──────────────────────────────────────────────────────────────
    /** Material's emphasised decelerate. Things arriving. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Emphasised accelerate. Things leaving. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Standard in-out, for anything that changes without arriving or leaving. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    // ── Springs ──────────────────────────────────────────────────────────────
    /**
     * Card entrance. Low bounce on purpose: this is a safety tool, and a playful overshoot on a
     * card that says a certificate expired is the wrong register.
     */
    fun <T> cardSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Button press. Crisper, and paired with haptics rather than carrying the feedback alone. */
    fun <T> pressSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.6f,
        stiffness = Spring.StiffnessHigh,
    )

    // ── Tweens ───────────────────────────────────────────────────────────────
    fun <T> enter(durationMillis: Int = Medium): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Enter)

    fun <T> exit(durationMillis: Int = Short): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Exit)

    fun <T> standard(durationMillis: Int = Short): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Standard)

    /** A gauge sweeping from zero to its value on first composition. */
    fun <T> sweep(): FiniteAnimationSpec<T> = tween(durationMillis = Long, easing = Enter)

    /** A chart path trimming in, or a series drawing left to right. */
    fun <T> draw(): FiniteAnimationSpec<T> = tween(durationMillis = Chart, easing = Standard)

    // ── Stagger ──────────────────────────────────────────────────────────────
    /**
     * Delay between successive dashboard cards, in ms.
     *
     * Capped rather than multiplied without limit: at 40 ms each, the ninth card would wait 360 ms,
     * and a supervisor opening the app to check one number should not watch a wave finish.
     */
    fun staggerDelay(index: Int, step: Int = 40, maxDelay: Int = 240): Int =
        (index * step).coerceAtMost(maxDelay)

    /** Press scale. 0.97, not 0.9: a card that shrinks a tenth looks broken rather than pressed. */
    const val PressScale = 0.97f

    /** How long an AR dwell must hold to count as a confirm. */
    const val DwellMillis = 900
}
