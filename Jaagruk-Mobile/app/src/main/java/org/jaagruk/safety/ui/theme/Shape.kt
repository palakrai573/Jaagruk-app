package org.jaagruk.safety.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii. Generous, because a glove is imprecise and a softer shape forgives a near miss
 * visually even though the touch target is what actually forgives it.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // chips, badges, small tags
    small = RoundedCornerShape(8.dp),        // inputs, list rows
    medium = RoundedCornerShape(8.dp),       // buttons
    large = RoundedCornerShape(8.dp),        // cards
    extraLarge = RoundedCornerShape(28.dp),  // bottom sheets, dialogs
)

/**
 * The touch-target floor, and it is not Material's.
 *
 * Material specifies 48 dp. A gloved contact patch is 15-20 mm, which at a typical ~400 dpi is
 * roughly 60-80 px, and the finger cannot see where its own centre is. At 48 dp a glove slip
 * lands on the neighbouring option - and in a drill, that is recorded as a wrong decision and
 * signed into a certificate.
 *
 * So 64 dp, everywhere a worker can tap during a drill. This is a correctness constant, not a
 * comfort one: the cost of getting it wrong is a competent worker with a failed run.
 */
val MinTouchTarget = 64.dp

/** Spacing scale. Four-point grid; named so a review can argue about the name, not the number. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 40.dp
}
