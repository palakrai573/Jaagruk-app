package org.jaagruk.safety.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Jaagruk's palette. See docs/REVAMP_PLAN.md section 4.1.
 *
 * Two rules govern everything below.
 *
 * 1. **No blue in the brand.** Not navy, not indigo, not the light blue the base build used.
 *    Teal reads industrial rather than SaaS, and more importantly it leaves the ISO 7010 signal
 *    colours free to mean only what they mean on a real sign. If the header is blue and
 *    "mandatory PPE" is also blue, the sign has lost its meaning inside the app.
 *
 * 2. **A signal colour is never decoration.** Red, amber, green and blue below appear only as
 *    status. Every status also carries a shape and a label, because roughly one man in twelve is
 *    red-green colour blind and this screen decides whether he enters a confined space.
 */

// ══ BRAND ════════════════════════════════════════════════════════════════════

val Teal900 = Color(0xFF00363A) // deep surfaces, dark-mode base
val Teal700 = Color(0xFF00696E) // PRIMARY: buttons, active nav, focus rings
val Teal500 = Color(0xFF1E9298) // hover, pressed
val Teal200 = Color(0xFF7FD4D9) // dark-mode primary, chart fills
val Teal50  = Color(0xFFE4F5F6) // selected row tint

// ══ ACCENT ═══════════════════════════════════════════════════════════════════
// The colour the base build was missing. Chart series come from here, in order.

val Amber600  = Color(0xFFC2691A) // secondary actions, streaks
val Amber400  = Color(0xFFE8942F) // chart series 2
val Clay500   = Color(0xFFB4523F) // chart series 3, hazard density
val Moss500   = Color(0xFF5B7F3E) // chart series 4, compliance gains
val Indigo500 = Color(0xFF4C5B9E) // chart series 5. The only blue, and not a light one.

/** Chart series in a fixed order, so the same measure is the same colour on every screen. */
val ChartSeries: List<Color> = listOf(Teal700, Amber400, Clay500, Moss500, Indigo500)

// ══ SURFACES ═════════════════════════════════════════════════════════════════
// Warm, not cold grey. The base build's #F8FAFC is blue-tinted, which fights any warm accent
// placed on it and is the main reason that UI read as cold.

val Sand50  = Color(0xFFFFFFFF) // light background
val Sand100 = Color(0xFFF5F5F5) // light elevated
val Sand200 = Color(0xFFE5E5E5) // light border
val White   = Color(0xFFFFFFFF) // light card

val Ink900 = Color(0xFF0A0A0A) // dark background
val Ink800 = Color(0xFF19191A) // dark elevated
val Ink700 = Color(0xFF262626) // dark border

// ══ TEXT ═════════════════════════════════════════════════════════════════════

val InkText      = Color(0xFF0A0A0A) // on light
val InkTextMuted = Color(0xFF666666)
val InkTextFaint = Color(0xFF8A8D89)

val SandText      = Color(0xFFFAFAFA) // on dark
val SandTextMuted = Color(0xFFA3A3A3)
val SandTextFaint = Color(0xFF6B7370)

// ══ ISO 7010 SIGNAL — STATUS ONLY ════════════════════════════════════════════
// Prohibition red, warning amber, safe-condition green, mandatory-action blue.
// Never used for branding, never used to make something look nice.

val SignalRed   = Color(0xFFC8102E) // prohibition, fire, expired, critical hazard
val SignalAmber = Color(0xFFE07B00) // warning, refresher due
val SignalGreen = Color(0xFF007A33) // safe condition, ready, escape route
val SignalBlue  = Color(0xFF005EB8) // mandatory action, PPE

/*
 * Dark-mode signal variants.
 *
 * ISO 7010 specifies these colours for printed signs, viewed in reflected light. On an emissive
 * display against Ink 900, the printed values fail contrast badly - #C8102E on #14181A is around
 * 2.4:1, well under the 4.5:1 a worker needs to read a warning in a dusty haulage road.
 *
 * So hue is preserved and lightness is lifted. The colour still reads as "the red one", and the
 * shape-and-label rule is what actually carries the meaning either way, which is the reason it
 * exists rather than a belt-and-braces nicety.
 */
val SignalRedDark   = Color(0xFFFF8A93)
val SignalAmberDark = Color(0xFFFFB65C)
val SignalGreenDark = Color(0xFF6BC98A)
val SignalBlueDark  = Color(0xFF7FB3E8)

// Containers: a signal colour behind text needs a tint, not the full-strength colour.
val SignalRedTint   = Color(0xFFFBE3E6)
val SignalAmberTint = Color(0xFFFDEEDB)
val SignalGreenTint = Color(0xFFE0F1E5)
val SignalBlueTint  = Color(0xFFE1ECF8)

val AmberTint = Color(0xFFFBEEE0)

// ══ TRANSLUCENT ══════════════════════════════════════════════════════════════

val GlassOnDark  = Color(0x18FFFFFF)
val GlassOnLight = Color(0xF2FFFFFF)

/*
 * ══ COMPATIBILITY LAYER ══════════════════════════════════════════════════════
 *
 * The thirteen screens inherited from the base build reference these names about four hundred
 * times between them. Renaming would mean editing every one of those call sites in the same
 * change that introduces the palette, so a single mistake would look like a palette problem.
 *
 * Instead the old names stay and point at the new values. The effect is that every existing
 * screen re-skins itself with no edits at all: `Blue500` was the primary, so it is now Teal 700,
 * and eighty-six call sites become correct at once.
 *
 * These are deliberately temporary. Phases 5 and 10 rebuild these screens, and each one that is
 * rebuilt drops its aliases. When the list is empty, delete this block.
 */

val Blue500 = CoralInk

val Blue600 = CoralInk

val Blue400 = Coral

val Blue50 = Color(0xFFFFEDEE)

val Blue100 = Blue50

val BlueAlpha12 = Coral.copy(alpha = 0.12f)

val DarkBg = Ink900

val DarkSurface = Ink800

val DarkSurfaceElevated = Ink800

val DarkBorder = Ink700

val DarkGlass = GlassOnDark

val LightBg = Sand50

val LightSurface = White

val LightSurfaceElevated = Sand100

val LightBorder = Sand200

val LightGlass = GlassOnLight

val GradStart = Sand50

val GradMid = Sand100

val GradEnd = Sand200

// The pulsing orb is off the dashboard and survives only as the AI thinking indicator, so its
// colours become the brand teal rather than a second identity.
val OrbColor1 = Teal200

val OrbColor2 = Teal500

val OrbColor3 = Teal50

val TextPrimary = SandText

val TextSecondary = SandTextMuted

val TextDisabled = SandTextFaint

val TextPrimaryLight = InkText

val TextSecondaryLight = InkTextMuted

val TextTertiary = InkTextFaint

// These three were already semantic, so they simply move onto the ISO 7010 values.
val SuccessGreen = SignalGreen

val ErrorRed = SignalRed

val WarnAmber = SignalAmber
