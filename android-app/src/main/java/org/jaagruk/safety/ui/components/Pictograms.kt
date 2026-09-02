package org.jaagruk.safety.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.core.catalog.Pictogram
import org.jaagruk.core.catalog.PictogramColour
import org.jaagruk.core.catalog.PictogramFamily

/**
 * Draws the pictogram vocabulary.
 *
 * **Why these are drawn rather than shipped as assets.** ISO 7010 artwork is copyrighted by ISO; redrawing
 * the standard's *shapes and colours* is what any compliant sign manufacturer does, and shipping traced
 * copies of the official files would not be ours to ship. So these are schematic renderings in the ISO
 * families — green square for safe condition, red square for fire equipment, blue circle for mandatory,
 * red circle and bar for prohibition, yellow triangle for warning — recognisable to a worker who has seen
 * the real signs bolted to a mine wall, and unambiguous between options in a drill.
 *
 * **Why Compose rather than vector drawables.** Seventy-three drawables in three densities is a lot of XML
 * to keep in step with an enum, and the enum is the contract. Here the mapping from [Pictogram] to a glyph
 * is an exhaustive `when`, so adding a pictogram to `:core` without artwork **fails to compile** — which is
 * exactly the guarantee the enum's own documentation promises.
 *
 * **Colour is never the only signal.** Every glyph is distinguishable in monochrome: family shape plus a
 * distinct outline. A red-green colour-blind worker sees a circle-with-bar versus a triangle, not two grey
 * blobs. The four extinguisher types differ by a colour band *and* a fill pattern, which is how they are
 * actually told apart on a wall in India.
 */
@Composable
fun PictogramIcon(
    pictogram: Pictogram,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    highlighted: Boolean = false,
    dimmed: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            // Non-empty by construction: the caller resolves a localised label, and lint enforces it.
            .semantics { this.contentDescription = contentDescription },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val alpha = if (dimmed) 0.45f else 1f
            drawFamilyBackdrop(pictogram.family, this.size, alpha, highlighted)
            drawPictogramGlyph(pictogram, this.size, alpha)
        }
    }
}

// ---------------------------------------------------------------------------
// Family backdrops
// ---------------------------------------------------------------------------

private val IsoGreen = Color(0xFF007A33)
private val IsoRed = Color(0xFFC8102E)
private val IsoBlue = Color(0xFF005EB8)
private val IsoYellow = Color(0xFFFFD100)
private val IsoGrey = Color(0xFF3F4A54)
private val IsoWhite = Color(0xFFFFFFFF)
private val IsoBlack = Color(0xFF1A1A1A)
private val HighlightRing = Color(0xFF00E5FF)

private fun PictogramColour.toColor(): Color = when (this) {
    PictogramColour.GREEN -> IsoGreen
    PictogramColour.RED -> IsoRed
    PictogramColour.BLUE -> IsoBlue
    PictogramColour.YELLOW -> IsoYellow
    PictogramColour.GREY -> IsoGrey
}

/** Ink colour a glyph must use to stay legible on its family's backdrop. */
private fun PictogramFamily.inkColor(): Color = when (this) {
    PictogramFamily.WARNING -> IsoBlack
    PictogramFamily.PROHIBITION -> IsoBlack
    else -> IsoWhite
}

private fun DrawScope.drawFamilyBackdrop(
    family: PictogramFamily,
    size: Size,
    alpha: Float,
    highlighted: Boolean,
) {
    val colour = family.signalColour.toColor().copy(alpha = alpha)
    val inset = size.minDimension * 0.04f

    when (family) {
        PictogramFamily.SAFE_CONDITION, PictogramFamily.FIRE_EQUIPMENT -> {
            drawRoundRectShape(colour, size, inset, cornerFraction = 0.08f)
        }

        PictogramFamily.MANDATORY -> {
            drawCircle(
                color = colour,
                radius = size.minDimension / 2f - inset,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        PictogramFamily.PROHIBITION -> {
            // White field, red annulus, red bar. The bar is drawn after the glyph so it crosses it, which
            // is what makes the sign read as a prohibition rather than an instruction.
            drawCircle(
                color = IsoWhite.copy(alpha = alpha),
                radius = size.minDimension / 2f - inset,
                center = Offset(size.width / 2f, size.height / 2f),
            )
            drawCircle(
                color = colour,
                radius = size.minDimension / 2f - inset - size.minDimension * 0.055f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = size.minDimension * 0.11f),
            )
        }

        PictogramFamily.WARNING -> {
            val triangle = Path().apply {
                val w = size.width
                val h = size.height
                moveTo(w / 2f, inset)
                lineTo(w - inset, h - inset * 1.4f)
                lineTo(inset, h - inset * 1.4f)
                close()
            }
            drawPath(triangle, colour)
            drawPath(
                triangle,
                IsoBlack.copy(alpha = alpha),
                style = Stroke(width = size.minDimension * 0.07f, join = StrokeJoin.Round),
            )
        }

        PictogramFamily.NEUTRAL -> {
            drawRoundRectShape(colour, size, inset, cornerFraction = 0.16f)
        }
    }

    if (highlighted) {
        drawCircle(
            color = HighlightRing,
            radius = size.minDimension / 2f - size.minDimension * 0.01f,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = size.minDimension * 0.055f),
        )
    }
}

private fun DrawScope.drawRoundRectShape(
    colour: Color,
    size: Size,
    inset: Float,
    cornerFraction: Float,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                radiusX = size.minDimension * cornerFraction,
                radiusY = size.minDimension * cornerFraction,
            ),
        )
    }
    drawPath(path, colour)
}

// ---------------------------------------------------------------------------
// Glyph vocabulary
// ---------------------------------------------------------------------------

/**
 * The drawing primitives, one per visually distinct glyph.
 *
 * Fewer entries than there are pictograms, because several pictograms genuinely share a glyph and differ
 * by a marker: the four extinguisher types are one extinguisher with four bands, matching how they are
 * distinguished on a real wall. Two pictograms only ever share a glyph when a worker would also see them
 * as the same thing in the field.
 */
private enum class Glyph {
    RUNNING_PERSON_DOOR_LEFT,
    RUNNING_PERSON_DOOR_RIGHT,
    RUNNING_PERSON_DOOR_UP,
    ASSEMBLY_POINT,
    FIRST_AID_CROSS,
    TELEPHONE,
    EYEWASH,
    REFUGE_SHELTER,
    EXTINGUISHER,
    HOSE_REEL,
    ALARM_CALL_POINT,
    HELMET,
    GOGGLES,
    EARMUFFS,
    RESPIRATOR,
    BREATHING_APPARATUS,
    HARNESS,
    SAFETY_BOOT,
    GLOVE,
    HIGH_VIS_VEST,
    PLUG_DISCONNECT,
    PADLOCK,
    VENT_FAN,
    GAS_DETECTOR,
    ALARM_BELL,
    RADIO_CALL,
    TWO_PEOPLE,
    PEDESTRIAN,
    FLAME,
    LIFT_CAR,
    HAND_TOUCH,
    PERMIT_DOCUMENT,
    RUNNING_PERSON,
    LONE_PERSON,
    EXCLAMATION,
    EXPLOSION,
    SKULL,
    LIGHTNING,
    GEARS,
    ROLLER_HAND,
    FALLING_OBJECTS,
    FALLING_PERSON,
    CONFINED_BOX,
    ROOF_FALL,
    HOT_SURFACE,
    SLIPPERY,
    TICK,
    CROSS_MARK,
    ARROW,
    STOP_PALM,
    STAND_STILL,
    SPEAKER,
    SMOKE,
    GAS_CLOUD,
    VALVE_WHEEL,
    GUARD_SHIELD,
    CONVEYOR,
    WINCH,
    LADDER,
    PANEL_BOX,
    CRAWLING_PERSON,
    CLOSING_DOOR,
    DRAG_CASUALTY,
}

/** Extinguisher band colours, matching Indian practice. */
private enum class ExtinguisherBand(val colour: Color?) {
    NONE(null),
    CO2(Color(0xFF1A1A1A)),
    DRY_POWDER(Color(0xFF0B6BCB)),
    FOAM(Color(0xFFF2B200)),
    WATER(Color(0xFFE8EDF2)),
}

/** How far an [Glyph.ARROW] points, in degrees clockwise from "up". */
private data class GlyphSpec(
    val glyph: Glyph,
    val rotationDegrees: Float = 0f,
    val band: ExtinguisherBand = ExtinguisherBand.NONE,
)

/**
 * Pictogram to glyph.
 *
 * Exhaustive by construction — no `else` branch. Adding a pictogram in `:core` without deciding how it
 * looks breaks this build, which is the guarantee the enum documentation promises and the reason zero-text
 * mode can be trusted.
 */
private fun Pictogram.spec(): GlyphSpec = when (this) {
    Pictogram.EMERGENCY_EXIT_LEFT -> GlyphSpec(Glyph.RUNNING_PERSON_DOOR_LEFT)
    Pictogram.EMERGENCY_EXIT_RIGHT -> GlyphSpec(Glyph.RUNNING_PERSON_DOOR_RIGHT)
    Pictogram.EMERGENCY_EXIT_UP -> GlyphSpec(Glyph.RUNNING_PERSON_DOOR_UP)
    Pictogram.ASSEMBLY_POINT -> GlyphSpec(Glyph.ASSEMBLY_POINT)
    Pictogram.FIRST_AID -> GlyphSpec(Glyph.FIRST_AID_CROSS)
    Pictogram.EMERGENCY_TELEPHONE -> GlyphSpec(Glyph.TELEPHONE)
    Pictogram.EYEWASH_STATION -> GlyphSpec(Glyph.EYEWASH)
    Pictogram.REFUGE_CHAMBER -> GlyphSpec(Glyph.REFUGE_SHELTER)

    Pictogram.FIRE_EXTINGUISHER -> GlyphSpec(Glyph.EXTINGUISHER)
    Pictogram.FIRE_EXTINGUISHER_CO2 -> GlyphSpec(Glyph.EXTINGUISHER, band = ExtinguisherBand.CO2)
    Pictogram.FIRE_EXTINGUISHER_DRY_POWDER ->
        GlyphSpec(Glyph.EXTINGUISHER, band = ExtinguisherBand.DRY_POWDER)
    Pictogram.FIRE_EXTINGUISHER_FOAM -> GlyphSpec(Glyph.EXTINGUISHER, band = ExtinguisherBand.FOAM)
    Pictogram.FIRE_EXTINGUISHER_WATER -> GlyphSpec(Glyph.EXTINGUISHER, band = ExtinguisherBand.WATER)
    Pictogram.FIRE_HOSE_REEL -> GlyphSpec(Glyph.HOSE_REEL)
    Pictogram.FIRE_ALARM_CALL_POINT -> GlyphSpec(Glyph.ALARM_CALL_POINT)

    Pictogram.WEAR_HELMET -> GlyphSpec(Glyph.HELMET)
    Pictogram.WEAR_EYE_PROTECTION -> GlyphSpec(Glyph.GOGGLES)
    Pictogram.WEAR_EAR_PROTECTION -> GlyphSpec(Glyph.EARMUFFS)
    Pictogram.WEAR_RESPIRATOR -> GlyphSpec(Glyph.RESPIRATOR)
    Pictogram.WEAR_SELF_CONTAINED_BREATHING_APPARATUS -> GlyphSpec(Glyph.BREATHING_APPARATUS)
    Pictogram.WEAR_SAFETY_HARNESS -> GlyphSpec(Glyph.HARNESS)
    Pictogram.WEAR_SAFETY_BOOTS -> GlyphSpec(Glyph.SAFETY_BOOT)
    Pictogram.WEAR_GLOVES -> GlyphSpec(Glyph.GLOVE)
    Pictogram.WEAR_HIGH_VIS -> GlyphSpec(Glyph.HIGH_VIS_VEST)
    Pictogram.DISCONNECT_BEFORE_WORK -> GlyphSpec(Glyph.PLUG_DISCONNECT)
    Pictogram.LOCKOUT_TAGOUT -> GlyphSpec(Glyph.PADLOCK)
    Pictogram.VENTILATE_BEFORE_ENTRY -> GlyphSpec(Glyph.VENT_FAN)
    Pictogram.TEST_ATMOSPHERE -> GlyphSpec(Glyph.GAS_DETECTOR)
    Pictogram.RAISE_ALARM -> GlyphSpec(Glyph.ALARM_BELL)
    Pictogram.CALL_SUPERVISOR -> GlyphSpec(Glyph.RADIO_CALL)
    Pictogram.BUDDY_CHECK -> GlyphSpec(Glyph.TWO_PEOPLE)

    Pictogram.NO_ENTRY -> GlyphSpec(Glyph.PEDESTRIAN)
    Pictogram.NO_OPEN_FLAME -> GlyphSpec(Glyph.FLAME)
    Pictogram.DO_NOT_USE_LIFT_IN_FIRE -> GlyphSpec(Glyph.LIFT_CAR)
    Pictogram.DO_NOT_TOUCH -> GlyphSpec(Glyph.HAND_TOUCH)
    Pictogram.NO_ENTRY_WITHOUT_PERMIT -> GlyphSpec(Glyph.PERMIT_DOCUMENT)
    Pictogram.DO_NOT_RUN -> GlyphSpec(Glyph.RUNNING_PERSON)
    Pictogram.DO_NOT_ENTER_ALONE -> GlyphSpec(Glyph.LONE_PERSON)

    Pictogram.WARNING_GENERAL -> GlyphSpec(Glyph.EXCLAMATION)
    Pictogram.WARNING_FLAMMABLE -> GlyphSpec(Glyph.FLAME)
    Pictogram.WARNING_EXPLOSIVE -> GlyphSpec(Glyph.EXPLOSION)
    Pictogram.WARNING_TOXIC_GAS -> GlyphSpec(Glyph.SKULL)
    Pictogram.WARNING_ASPHYXIANT -> GlyphSpec(Glyph.GAS_CLOUD)
    Pictogram.WARNING_ELECTRICITY -> GlyphSpec(Glyph.LIGHTNING)
    Pictogram.WARNING_MOVING_MACHINERY -> GlyphSpec(Glyph.GEARS)
    Pictogram.WARNING_ENTANGLEMENT -> GlyphSpec(Glyph.ROLLER_HAND)
    Pictogram.WARNING_FALLING_OBJECTS -> GlyphSpec(Glyph.FALLING_OBJECTS)
    Pictogram.WARNING_DROP -> GlyphSpec(Glyph.FALLING_PERSON)
    Pictogram.WARNING_CONFINED_SPACE -> GlyphSpec(Glyph.CONFINED_BOX)
    Pictogram.WARNING_ROOF_FALL -> GlyphSpec(Glyph.ROOF_FALL)
    Pictogram.WARNING_HOT_SURFACE -> GlyphSpec(Glyph.HOT_SURFACE)
    Pictogram.WARNING_SLIPPERY -> GlyphSpec(Glyph.SLIPPERY)

    Pictogram.ANSWER_YES -> GlyphSpec(Glyph.TICK)
    Pictogram.ANSWER_NO -> GlyphSpec(Glyph.CROSS_MARK)
    Pictogram.ARROW_LEFT -> GlyphSpec(Glyph.ARROW, rotationDegrees = 270f)
    Pictogram.ARROW_RIGHT -> GlyphSpec(Glyph.ARROW, rotationDegrees = 90f)
    Pictogram.ARROW_STRAIGHT -> GlyphSpec(Glyph.ARROW, rotationDegrees = 0f)
    Pictogram.ARROW_BACK -> GlyphSpec(Glyph.ARROW, rotationDegrees = 180f)
    Pictogram.STOP_HAND -> GlyphSpec(Glyph.STOP_PALM)
    Pictogram.STAY_PUT -> GlyphSpec(Glyph.STAND_STILL)
    Pictogram.LISTEN_AGAIN -> GlyphSpec(Glyph.SPEAKER)
    Pictogram.SMOKE -> GlyphSpec(Glyph.SMOKE)
    Pictogram.GAS_CLOUD -> GlyphSpec(Glyph.GAS_CLOUD)
    Pictogram.VALVE -> GlyphSpec(Glyph.VALVE_WHEEL)
    Pictogram.MACHINE_GUARD -> GlyphSpec(Glyph.GUARD_SHIELD)
    Pictogram.CONVEYOR -> GlyphSpec(Glyph.CONVEYOR)
    Pictogram.WINCH -> GlyphSpec(Glyph.WINCH)
    Pictogram.GAS_DETECTOR -> GlyphSpec(Glyph.GAS_DETECTOR)
    Pictogram.LADDER -> GlyphSpec(Glyph.LADDER)
    Pictogram.LV_PANEL -> GlyphSpec(Glyph.PANEL_BOX)
    Pictogram.CRAWL_LOW -> GlyphSpec(Glyph.CRAWLING_PERSON)
    Pictogram.CLOSE_DOOR -> GlyphSpec(Glyph.CLOSING_DOOR)
    Pictogram.DRAG_CASUALTY -> GlyphSpec(Glyph.DRAG_CASUALTY)
}

private fun DrawScope.drawPictogramGlyph(pictogram: Pictogram, size: Size, alpha: Float) {
    val spec = pictogram.spec()
    val ink = pictogram.family.inkColor().copy(alpha = alpha)
    val unit = size.minDimension

    // Glyph box: centred, and pushed down inside a warning triangle where the usable area is lower.
    val boxSize = unit * if (pictogram.family == PictogramFamily.WARNING) 0.44f else 0.56f
    val centreY = if (pictogram.family == PictogramFamily.WARNING) size.height * 0.60f else size.height / 2f
    val left = (size.width - boxSize) / 2f
    val top = centreY - boxSize / 2f

    translate(left, top) {
        if (spec.rotationDegrees != 0f) {
            rotate(spec.rotationDegrees, pivot = Offset(boxSize / 2f, boxSize / 2f)) {
                drawGlyph(spec, ink, boxSize, alpha)
            }
        } else {
            drawGlyph(spec, ink, boxSize, alpha)
        }
    }

    // The prohibition bar goes on top of the glyph, because that is what makes it a prohibition.
    if (pictogram.family == PictogramFamily.PROHIBITION) {
        val inset = unit * 0.04f
        val radius = unit / 2f - inset - unit * 0.055f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val delta = radius * 0.707f
        drawLine(
            color = IsoRed.copy(alpha = alpha),
            start = Offset(centre.x - delta, centre.y + delta),
            end = Offset(centre.x + delta, centre.y - delta),
            strokeWidth = unit * 0.11f,
            cap = StrokeCap.Butt,
        )
    }
}

// ---------------------------------------------------------------------------
// Glyph drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawGlyph(spec: GlyphSpec, ink: Color, box: Float, alpha: Float) {
    val stroke = box * 0.12f
    when (spec.glyph) {
        Glyph.RUNNING_PERSON_DOOR_LEFT -> exitGlyph(ink, box, stroke, mirrored = true, upward = false)
        Glyph.RUNNING_PERSON_DOOR_RIGHT -> exitGlyph(ink, box, stroke, mirrored = false, upward = false)
        Glyph.RUNNING_PERSON_DOOR_UP -> exitGlyph(ink, box, stroke, mirrored = false, upward = true)

        Glyph.ASSEMBLY_POINT -> {
            // Four arrows converging on a group of people: the ISO E007 idea, simplified.
            person(ink, box * 0.42f, box * 0.52f, box * 0.42f, stroke * 0.8f)
            person(ink, box * 0.62f, box * 0.56f, box * 0.34f, stroke * 0.7f)
            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                rotate(angle, pivot = Offset(box / 2f, box / 2f)) {
                    arrowHead(ink, Offset(box / 2f, box * 0.10f), box * 0.16f, stroke * 0.8f)
                }
            }
        }

        Glyph.FIRST_AID_CROSS -> {
            val arm = box * 0.26f
            drawRect(ink, Offset(box / 2f - arm / 2f, box * 0.14f), Size(arm, box * 0.72f))
            drawRect(ink, Offset(box * 0.14f, box / 2f - arm / 2f), Size(box * 0.72f, arm))
        }

        Glyph.TELEPHONE -> {
            val handset = Path().apply {
                moveTo(box * 0.18f, box * 0.30f)
                quadraticBezierTo(box * 0.50f, box * 0.92f, box * 0.82f, box * 0.62f)
                lineTo(box * 0.66f, box * 0.46f)
                quadraticBezierTo(box * 0.50f, box * 0.62f, box * 0.36f, box * 0.44f)
                close()
            }
            drawPath(handset, ink)
            listOf(0.62f, 0.74f, 0.86f).forEach { x ->
                drawLine(
                    ink,
                    Offset(box * x, box * 0.26f),
                    Offset(box * x, box * 0.12f),
                    stroke * 0.5f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Glyph.EYEWASH -> {
            drawOval(ink, Offset(box * 0.18f, box * 0.20f), Size(box * 0.64f, box * 0.30f))
            drawCircle(
                IsoBlack.copy(alpha = alpha * 0.7f),
                box * 0.08f,
                Offset(box * 0.50f, box * 0.35f),
            )
            listOf(0.34f, 0.50f, 0.66f).forEach { x ->
                drawLine(
                    ink,
                    Offset(box * x, box * 0.58f),
                    Offset(box * x, box * 0.88f),
                    stroke * 0.6f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Glyph.REFUGE_SHELTER -> {
            val roof = Path().apply {
                moveTo(box * 0.08f, box * 0.46f)
                lineTo(box * 0.50f, box * 0.12f)
                lineTo(box * 0.92f, box * 0.46f)
                close()
            }
            drawPath(roof, ink)
            drawRect(ink, Offset(box * 0.20f, box * 0.46f), Size(box * 0.60f, box * 0.42f))
            person(IsoGreen.copy(alpha = alpha), box * 0.50f, box * 0.68f, box * 0.30f, stroke * 0.6f)
        }

        Glyph.EXTINGUISHER -> {
            drawRoundRect(ink, box * 0.32f, box * 0.28f, box * 0.36f, box * 0.60f, box * 0.08f)
            drawRect(ink, Offset(box * 0.44f, box * 0.14f), Size(box * 0.12f, box * 0.16f))
            val hose = Path().apply {
                moveTo(box * 0.68f, box * 0.34f)
                quadraticBezierTo(box * 0.92f, box * 0.44f, box * 0.80f, box * 0.72f)
            }
            drawPath(hose, ink, style = Stroke(width = stroke * 0.6f, cap = StrokeCap.Round))
            spec.band.colour?.let { band ->
                drawRect(
                    band.copy(alpha = alpha),
                    Offset(box * 0.32f, box * 0.46f),
                    Size(box * 0.36f, box * 0.16f),
                )
            }
        }

        Glyph.HOSE_REEL -> {
            drawCircle(ink, box * 0.30f, Offset(box * 0.44f, box * 0.52f), style = Stroke(stroke))
            drawCircle(ink, box * 0.08f, Offset(box * 0.44f, box * 0.52f))
            val nozzle = Path().apply {
                moveTo(box * 0.72f, box * 0.42f)
                quadraticBezierTo(box * 0.94f, box * 0.52f, box * 0.86f, box * 0.82f)
            }
            drawPath(nozzle, ink, style = Stroke(width = stroke * 0.6f, cap = StrokeCap.Round))
        }

        Glyph.ALARM_CALL_POINT -> {
            drawRoundRect(ink, box * 0.14f, box * 0.14f, box * 0.72f, box * 0.72f, box * 0.08f)
            drawCircle(
                IsoWhite.copy(alpha = alpha),
                box * 0.16f,
                Offset(box * 0.50f, box * 0.42f),
            )
            drawLine(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.30f, box * 0.72f),
                Offset(box * 0.70f, box * 0.72f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.HELMET -> {
            val dome = Path().apply {
                moveTo(box * 0.14f, box * 0.62f)
                quadraticBezierTo(box * 0.50f, box * 0.08f, box * 0.86f, box * 0.62f)
                close()
            }
            drawPath(dome, ink)
            drawRoundRect(ink, box * 0.06f, box * 0.62f, box * 0.88f, box * 0.14f, box * 0.06f)
            headOutline(ink, box, alpha, stroke)
        }

        Glyph.GOGGLES -> {
            headOutline(ink, box, alpha, stroke)
            drawRoundRect(ink, box * 0.12f, box * 0.40f, box * 0.76f, box * 0.22f, box * 0.10f)
        }

        Glyph.EARMUFFS -> {
            headOutline(ink, box, alpha, stroke)
            drawRoundRect(ink, box * 0.06f, box * 0.40f, box * 0.16f, box * 0.30f, box * 0.07f)
            drawRoundRect(ink, box * 0.78f, box * 0.40f, box * 0.16f, box * 0.30f, box * 0.07f)
            val band = Path().apply {
                moveTo(box * 0.14f, box * 0.40f)
                quadraticBezierTo(box * 0.50f, box * 0.06f, box * 0.86f, box * 0.40f)
            }
            drawPath(band, ink, style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round))
        }

        Glyph.RESPIRATOR -> {
            headOutline(ink, box, alpha, stroke)
            val mask = Path().apply {
                moveTo(box * 0.18f, box * 0.48f)
                quadraticBezierTo(box * 0.50f, box * 0.92f, box * 0.82f, box * 0.48f)
                quadraticBezierTo(box * 0.50f, box * 0.60f, box * 0.18f, box * 0.48f)
                close()
            }
            drawPath(mask, ink)
        }

        Glyph.BREATHING_APPARATUS -> {
            headOutline(ink, box, alpha, stroke)
            drawCircle(ink, box * 0.22f, Offset(box * 0.44f, box * 0.56f))
            drawRoundRect(ink, box * 0.76f, box * 0.30f, box * 0.18f, box * 0.56f, box * 0.08f)
            drawLine(
                ink,
                Offset(box * 0.64f, box * 0.60f),
                Offset(box * 0.78f, box * 0.52f),
                stroke * 0.6f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.HARNESS -> {
            person(ink, box * 0.50f, box * 0.50f, box * 0.86f, stroke * 0.8f)
            val straps = Path().apply {
                moveTo(box * 0.34f, box * 0.34f)
                lineTo(box * 0.66f, box * 0.62f)
                moveTo(box * 0.66f, box * 0.34f)
                lineTo(box * 0.34f, box * 0.62f)
            }
            drawPath(straps, ink, style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round))
            drawLine(
                ink,
                Offset(box * 0.50f, box * 0.30f),
                Offset(box * 0.92f, box * 0.06f),
                stroke * 0.5f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.SAFETY_BOOT -> {
            val boot = Path().apply {
                moveTo(box * 0.20f, box * 0.24f)
                lineTo(box * 0.44f, box * 0.24f)
                lineTo(box * 0.48f, box * 0.60f)
                lineTo(box * 0.88f, box * 0.68f)
                lineTo(box * 0.88f, box * 0.82f)
                lineTo(box * 0.20f, box * 0.82f)
                close()
            }
            drawPath(boot, ink)
        }

        Glyph.GLOVE -> {
            drawRoundRect(ink, box * 0.28f, box * 0.16f, box * 0.44f, box * 0.44f, box * 0.16f)
            drawRoundRect(ink, box * 0.30f, box * 0.56f, box * 0.40f, box * 0.30f, box * 0.08f)
            drawRoundRect(ink, box * 0.14f, box * 0.34f, box * 0.14f, box * 0.26f, box * 0.07f)
        }

        Glyph.HIGH_VIS_VEST -> {
            val vest = Path().apply {
                moveTo(box * 0.24f, box * 0.20f)
                lineTo(box * 0.76f, box * 0.20f)
                lineTo(box * 0.82f, box * 0.86f)
                lineTo(box * 0.18f, box * 0.86f)
                close()
            }
            drawPath(vest, ink)
            drawLine(
                IsoBlack.copy(alpha = alpha * 0.8f),
                Offset(box * 0.22f, box * 0.56f),
                Offset(box * 0.78f, box * 0.56f),
                stroke * 0.6f,
            )
        }

        Glyph.PLUG_DISCONNECT -> {
            drawRoundRect(ink, box * 0.30f, box * 0.42f, box * 0.40f, box * 0.34f, box * 0.06f)
            drawLine(
                ink,
                Offset(box * 0.40f, box * 0.42f),
                Offset(box * 0.40f, box * 0.16f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(box * 0.60f, box * 0.42f),
                Offset(box * 0.60f, box * 0.16f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(box * 0.50f, box * 0.76f),
                Offset(box * 0.50f, box * 0.92f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.PADLOCK -> {
            val shackle = Path().apply {
                moveTo(box * 0.30f, box * 0.44f)
                lineTo(box * 0.30f, box * 0.28f)
                quadraticBezierTo(box * 0.50f, box * 0.06f, box * 0.70f, box * 0.28f)
                lineTo(box * 0.70f, box * 0.44f)
            }
            drawPath(shackle, ink, style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round))
            drawRoundRect(ink, box * 0.20f, box * 0.44f, box * 0.60f, box * 0.44f, box * 0.08f)
        }

        Glyph.VENT_FAN -> {
            drawCircle(ink, box * 0.40f, Offset(box / 2f, box / 2f), style = Stroke(stroke * 0.7f))
            listOf(0f, 120f, 240f).forEach { angle ->
                rotate(angle, pivot = Offset(box / 2f, box / 2f)) {
                    val blade = Path().apply {
                        moveTo(box * 0.50f, box * 0.50f)
                        quadraticBezierTo(box * 0.68f, box * 0.26f, box * 0.50f, box * 0.14f)
                        quadraticBezierTo(box * 0.42f, box * 0.32f, box * 0.50f, box * 0.50f)
                        close()
                    }
                    drawPath(blade, ink)
                }
            }
        }

        Glyph.GAS_DETECTOR -> {
            drawRoundRect(ink, box * 0.26f, box * 0.14f, box * 0.48f, box * 0.72f, box * 0.08f)
            drawRect(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.34f, box * 0.24f),
                Size(box * 0.32f, box * 0.22f),
            )
            listOf(0.30f, 0.50f, 0.70f).forEach { x ->
                drawCircle(
                    IsoWhite.copy(alpha = alpha),
                    box * 0.045f,
                    Offset(box * x, box * 0.62f),
                )
            }
        }

        Glyph.ALARM_BELL -> {
            val bell = Path().apply {
                moveTo(box * 0.22f, box * 0.66f)
                quadraticBezierTo(box * 0.24f, box * 0.18f, box * 0.50f, box * 0.16f)
                quadraticBezierTo(box * 0.76f, box * 0.18f, box * 0.78f, box * 0.66f)
                close()
            }
            drawPath(bell, ink)
            drawCircle(ink, box * 0.09f, Offset(box * 0.50f, box * 0.78f))
            listOf(-1f, 1f).forEach { side ->
                drawLine(
                    ink,
                    Offset(box * (0.50f + side * 0.34f), box * 0.30f),
                    Offset(box * (0.50f + side * 0.46f), box * 0.22f),
                    stroke * 0.5f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Glyph.RADIO_CALL -> {
            person(ink, box * 0.40f, box * 0.54f, box * 0.72f, stroke * 0.8f)
            drawRoundRect(ink, box * 0.66f, box * 0.30f, box * 0.16f, box * 0.34f, box * 0.05f)
            listOf(0.20f, 0.30f).forEach { r ->
                drawArc(
                    color = ink,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(box * (0.74f - r), box * (0.32f - r)),
                    size = Size(box * r * 2f, box * r * 2f),
                    style = Stroke(width = stroke * 0.4f, cap = StrokeCap.Round),
                )
            }
        }

        Glyph.TWO_PEOPLE -> {
            person(ink, box * 0.34f, box * 0.52f, box * 0.72f, stroke * 0.8f)
            person(ink, box * 0.68f, box * 0.52f, box * 0.72f, stroke * 0.8f)
            drawLine(
                ink,
                Offset(box * 0.44f, box * 0.48f),
                Offset(box * 0.58f, box * 0.48f),
                stroke * 0.6f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.PEDESTRIAN -> person(ink, box * 0.50f, box * 0.50f, box * 0.90f, stroke * 0.9f)

        Glyph.FLAME -> {
            val flame = Path().apply {
                moveTo(box * 0.50f, box * 0.08f)
                quadraticBezierTo(box * 0.86f, box * 0.44f, box * 0.66f, box * 0.72f)
                quadraticBezierTo(box * 0.58f, box * 0.90f, box * 0.38f, box * 0.86f)
                quadraticBezierTo(box * 0.14f, box * 0.76f, box * 0.30f, box * 0.46f)
                quadraticBezierTo(box * 0.40f, box * 0.32f, box * 0.50f, box * 0.08f)
                close()
            }
            drawPath(flame, ink)
        }

        Glyph.LIFT_CAR -> {
            drawRoundRect(ink, box * 0.16f, box * 0.14f, box * 0.68f, box * 0.72f, box * 0.05f)
            drawLine(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.50f, box * 0.14f),
                Offset(box * 0.50f, box * 0.86f),
                stroke * 0.5f,
            )
            arrowHead(IsoWhite.copy(alpha = alpha), Offset(box * 0.33f, box * 0.34f), box * 0.10f, stroke * 0.5f)
            rotate(180f, pivot = Offset(box * 0.67f, box * 0.62f)) {
                arrowHead(IsoWhite.copy(alpha = alpha), Offset(box * 0.67f, box * 0.62f), box * 0.10f, stroke * 0.5f)
            }
        }

        Glyph.HAND_TOUCH -> {
            drawRoundRect(ink, box * 0.34f, box * 0.30f, box * 0.34f, box * 0.56f, box * 0.12f)
            listOf(0.36f, 0.48f, 0.60f).forEach { x ->
                drawRoundRect(ink, box * x, box * 0.14f, box * 0.09f, box * 0.24f, box * 0.045f)
            }
            drawLine(
                ink,
                Offset(box * 0.14f, box * 0.86f),
                Offset(box * 0.86f, box * 0.86f),
                stroke * 0.8f,
            )
        }

        Glyph.PERMIT_DOCUMENT -> {
            drawRoundRect(ink, box * 0.22f, box * 0.10f, box * 0.56f, box * 0.80f, box * 0.05f)
            listOf(0.30f, 0.44f, 0.58f).forEach { y ->
                drawLine(
                    IsoWhite.copy(alpha = alpha),
                    Offset(box * 0.32f, box * y),
                    Offset(box * 0.68f, box * y),
                    stroke * 0.4f,
                )
            }
            tick(IsoWhite.copy(alpha = alpha), box, stroke * 0.6f, scale = 0.34f, dx = 0.42f, dy = 0.62f)
        }

        Glyph.RUNNING_PERSON -> runningPerson(ink, box, stroke)

        Glyph.LONE_PERSON -> {
            person(ink, box * 0.50f, box * 0.52f, box * 0.80f, stroke * 0.9f)
            drawCircle(
                ink,
                box * 0.40f,
                Offset(box / 2f, box / 2f),
                style = Stroke(
                    width = stroke * 0.4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(box * 0.08f, box * 0.06f)),
                ),
            )
        }

        Glyph.EXCLAMATION -> {
            drawRoundRect(ink, box * 0.42f, box * 0.10f, box * 0.16f, box * 0.52f, box * 0.06f)
            drawCircle(ink, box * 0.10f, Offset(box * 0.50f, box * 0.82f))
        }

        Glyph.EXPLOSION -> {
            val burst = Path()
            val points = 10
            for (i in 0 until points * 2) {
                val radius = if (i % 2 == 0) box * 0.44f else box * 0.20f
                val angle = Math.PI * i / points - Math.PI / 2
                val x = box / 2f + (radius * kotlin.math.cos(angle)).toFloat()
                val y = box / 2f + (radius * kotlin.math.sin(angle)).toFloat()
                if (i == 0) burst.moveTo(x, y) else burst.lineTo(x, y)
            }
            burst.close()
            drawPath(burst, ink)
        }

        Glyph.SKULL -> {
            drawCircle(ink, box * 0.30f, Offset(box / 2f, box * 0.40f))
            drawRoundRect(ink, box * 0.36f, box * 0.62f, box * 0.28f, box * 0.20f, box * 0.06f)
            drawCircle(IsoYellow.copy(alpha = alpha), box * 0.08f, Offset(box * 0.40f, box * 0.38f))
            drawCircle(IsoYellow.copy(alpha = alpha), box * 0.08f, Offset(box * 0.60f, box * 0.38f))
            drawLine(
                ink,
                Offset(box * 0.14f, box * 0.86f),
                Offset(box * 0.86f, box * 0.70f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(box * 0.14f, box * 0.70f),
                Offset(box * 0.86f, box * 0.86f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.LIGHTNING -> {
            val bolt = Path().apply {
                moveTo(box * 0.62f, box * 0.06f)
                lineTo(box * 0.26f, box * 0.54f)
                lineTo(box * 0.48f, box * 0.54f)
                lineTo(box * 0.34f, box * 0.94f)
                lineTo(box * 0.78f, box * 0.42f)
                lineTo(box * 0.54f, box * 0.42f)
                close()
            }
            drawPath(bolt, ink)
        }

        Glyph.GEARS -> {
            drawCircle(ink, box * 0.24f, Offset(box * 0.40f, box * 0.44f), style = Stroke(stroke * 0.8f))
            for (i in 0 until 8) {
                rotate(i * 45f, pivot = Offset(box * 0.40f, box * 0.44f)) {
                    drawRect(
                        ink,
                        Offset(box * 0.365f, box * 0.14f),
                        Size(box * 0.07f, box * 0.10f),
                    )
                }
            }
            drawCircle(ink, box * 0.16f, Offset(box * 0.74f, box * 0.72f), style = Stroke(stroke * 0.7f))
            for (i in 0 until 6) {
                rotate(i * 60f, pivot = Offset(box * 0.74f, box * 0.72f)) {
                    drawRect(
                        ink,
                        Offset(box * 0.715f, box * 0.52f),
                        Size(box * 0.05f, box * 0.07f),
                    )
                }
            }
        }

        Glyph.ROLLER_HAND -> {
            drawCircle(ink, box * 0.17f, Offset(box * 0.30f, box * 0.28f))
            drawCircle(ink, box * 0.17f, Offset(box * 0.68f, box * 0.28f))
            drawRoundRect(ink, box * 0.38f, box * 0.52f, box * 0.24f, box * 0.40f, box * 0.10f)
            listOf(0.40f, 0.50f, 0.60f).forEach { x ->
                drawRoundRect(ink, box * x, box * 0.42f, box * 0.06f, box * 0.14f, box * 0.03f)
            }
        }

        Glyph.FALLING_OBJECTS -> {
            drawRect(ink, Offset(box * 0.10f, box * 0.08f), Size(box * 0.80f, box * 0.10f))
            drawRect(ink, Offset(box * 0.24f, box * 0.34f), Size(box * 0.20f, box * 0.20f))
            drawRect(ink, Offset(box * 0.56f, box * 0.50f), Size(box * 0.16f, box * 0.16f))
            person(ink, box * 0.44f, box * 0.80f, box * 0.36f, stroke * 0.6f)
        }

        Glyph.FALLING_PERSON -> {
            drawLine(
                ink,
                Offset(box * 0.08f, box * 0.86f),
                Offset(box * 0.50f, box * 0.86f),
                stroke * 0.8f,
            )
            rotate(28f, pivot = Offset(box * 0.60f, box * 0.44f)) {
                person(ink, box * 0.60f, box * 0.44f, box * 0.66f, stroke * 0.8f)
            }
            listOf(0.62f, 0.74f, 0.86f).forEach { y ->
                drawLine(
                    ink,
                    Offset(box * 0.86f, box * y),
                    Offset(box * 0.94f, box * (y + 0.06f)),
                    stroke * 0.35f,
                )
            }
        }

        Glyph.CONFINED_BOX -> {
            drawRoundRect(ink, box * 0.10f, box * 0.22f, box * 0.80f, box * 0.64f, box * 0.05f)
            drawCircle(
                IsoYellow.copy(alpha = alpha),
                box * 0.13f,
                Offset(box * 0.50f, box * 0.22f),
            )
            person(IsoYellow.copy(alpha = alpha), box * 0.50f, box * 0.58f, box * 0.42f, stroke * 0.6f)
        }

        Glyph.ROOF_FALL -> {
            drawRect(ink, Offset(box * 0.06f, box * 0.10f), Size(box * 0.88f, box * 0.10f))
            listOf(
                Triple(0.24f, 0.34f, 0.14f),
                Triple(0.52f, 0.44f, 0.18f),
                Triple(0.74f, 0.30f, 0.11f),
            ).forEach { (cx, cy, r) ->
                val rock = Path().apply {
                    moveTo(box * cx, box * (cy - r))
                    lineTo(box * (cx + r), box * cy)
                    lineTo(box * cx, box * (cy + r))
                    lineTo(box * (cx - r), box * cy)
                    close()
                }
                drawPath(rock, ink)
            }
            person(ink, box * 0.44f, box * 0.82f, box * 0.32f, stroke * 0.6f)
        }

        Glyph.HOT_SURFACE -> {
            drawLine(
                ink,
                Offset(box * 0.10f, box * 0.80f),
                Offset(box * 0.90f, box * 0.80f),
                stroke * 0.9f,
            )
            listOf(0.28f, 0.50f, 0.72f).forEach { x ->
                val wave = Path().apply {
                    moveTo(box * x, box * 0.74f)
                    quadraticBezierTo(box * (x + 0.10f), box * 0.56f, box * x, box * 0.40f)
                    quadraticBezierTo(box * (x - 0.08f), box * 0.28f, box * x, box * 0.14f)
                }
                drawPath(wave, ink, style = Stroke(width = stroke * 0.55f, cap = StrokeCap.Round))
            }
        }

        Glyph.SLIPPERY -> {
            drawLine(
                ink,
                Offset(box * 0.06f, box * 0.84f),
                Offset(box * 0.94f, box * 0.84f),
                stroke * 0.8f,
            )
            rotate(-22f, pivot = Offset(box * 0.52f, box * 0.46f)) {
                person(ink, box * 0.52f, box * 0.46f, box * 0.62f, stroke * 0.8f)
            }
            listOf(0.16f, 0.30f).forEach { x ->
                drawLine(
                    ink,
                    Offset(box * x, box * 0.72f),
                    Offset(box * (x + 0.10f), box * 0.62f),
                    stroke * 0.4f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Glyph.TICK -> tick(ink, box, stroke, scale = 1f, dx = 0f, dy = 0f)

        Glyph.CROSS_MARK -> {
            drawLine(
                ink,
                Offset(box * 0.18f, box * 0.18f),
                Offset(box * 0.82f, box * 0.82f),
                stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(box * 0.82f, box * 0.18f),
                Offset(box * 0.18f, box * 0.82f),
                stroke,
                cap = StrokeCap.Round,
            )
        }

        Glyph.ARROW -> {
            drawLine(
                ink,
                Offset(box / 2f, box * 0.86f),
                Offset(box / 2f, box * 0.26f),
                stroke,
                cap = StrokeCap.Round,
            )
            arrowHead(ink, Offset(box / 2f, box * 0.10f), box * 0.22f, stroke)
        }

        Glyph.STOP_PALM -> {
            drawRoundRect(ink, box * 0.28f, box * 0.34f, box * 0.44f, box * 0.52f, box * 0.12f)
            listOf(0.30f, 0.42f, 0.54f, 0.66f).forEach { x ->
                drawRoundRect(ink, box * x, box * 0.14f, box * 0.08f, box * 0.26f, box * 0.04f)
            }
        }

        Glyph.STAND_STILL -> {
            person(ink, box * 0.50f, box * 0.46f, box * 0.76f, stroke * 0.9f)
            drawLine(
                ink,
                Offset(box * 0.16f, box * 0.90f),
                Offset(box * 0.84f, box * 0.90f),
                stroke * 0.7f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.SPEAKER -> {
            val cone = Path().apply {
                moveTo(box * 0.14f, box * 0.38f)
                lineTo(box * 0.30f, box * 0.38f)
                lineTo(box * 0.52f, box * 0.16f)
                lineTo(box * 0.52f, box * 0.84f)
                lineTo(box * 0.30f, box * 0.62f)
                lineTo(box * 0.14f, box * 0.62f)
                close()
            }
            drawPath(cone, ink)
            listOf(0.16f, 0.28f).forEach { r ->
                drawArc(
                    color = ink,
                    startAngle = -55f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(box * (0.60f - r), box * (0.50f - r)),
                    size = Size(box * r * 2f, box * r * 2f),
                    style = Stroke(width = stroke * 0.5f, cap = StrokeCap.Round),
                )
            }
        }

        Glyph.SMOKE -> {
            listOf(0.30f, 0.52f, 0.74f).forEach { y ->
                val wisp = Path().apply {
                    moveTo(box * 0.12f, box * y)
                    quadraticBezierTo(box * 0.36f, box * (y - 0.14f), box * 0.56f, box * y)
                    quadraticBezierTo(box * 0.76f, box * (y + 0.14f), box * 0.92f, box * y)
                }
                drawPath(wisp, ink, style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round))
            }
        }

        Glyph.GAS_CLOUD -> {
            val cloud = Path().apply {
                addOval(Rect(box * 0.10f, box * 0.36f, box * 0.54f, box * 0.68f))
                addOval(Rect(box * 0.36f, box * 0.24f, box * 0.82f, box * 0.60f))
                addOval(Rect(box * 0.30f, box * 0.48f, box * 0.90f, box * 0.80f))
            }
            drawPath(cloud, ink)
        }

        Glyph.VALVE_WHEEL -> {
            drawCircle(ink, box * 0.34f, Offset(box / 2f, box * 0.42f), style = Stroke(stroke * 0.8f))
            listOf(0f, 60f, 120f).forEach { angle ->
                rotate(angle, pivot = Offset(box / 2f, box * 0.42f)) {
                    drawLine(
                        ink,
                        Offset(box * 0.16f, box * 0.42f),
                        Offset(box * 0.84f, box * 0.42f),
                        stroke * 0.5f,
                    )
                }
            }
            drawRect(ink, Offset(box * 0.44f, box * 0.76f), Size(box * 0.12f, box * 0.20f))
        }

        Glyph.GUARD_SHIELD -> {
            val shield = Path().apply {
                moveTo(box * 0.50f, box * 0.08f)
                lineTo(box * 0.88f, box * 0.26f)
                quadraticBezierTo(box * 0.88f, box * 0.78f, box * 0.50f, box * 0.94f)
                quadraticBezierTo(box * 0.12f, box * 0.78f, box * 0.12f, box * 0.26f)
                close()
            }
            drawPath(shield, ink)
            drawLine(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.30f, box * 0.40f),
                Offset(box * 0.70f, box * 0.40f),
                stroke * 0.5f,
            )
            drawLine(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.30f, box * 0.56f),
                Offset(box * 0.70f, box * 0.56f),
                stroke * 0.5f,
            )
        }

        Glyph.CONVEYOR -> {
            drawCircle(ink, box * 0.14f, Offset(box * 0.22f, box * 0.56f))
            drawCircle(ink, box * 0.14f, Offset(box * 0.78f, box * 0.56f))
            drawRect(ink, Offset(box * 0.22f, box * 0.40f), Size(box * 0.56f, box * 0.08f))
            drawRect(ink, Offset(box * 0.22f, box * 0.64f), Size(box * 0.56f, box * 0.08f))
            drawRect(ink, Offset(box * 0.42f, box * 0.20f), Size(box * 0.18f, box * 0.18f))
        }

        Glyph.WINCH -> {
            drawRoundRect(ink, box * 0.18f, box * 0.34f, box * 0.64f, box * 0.34f, box * 0.06f)
            drawCircle(IsoWhite.copy(alpha = alpha), box * 0.11f, Offset(box * 0.50f, box * 0.51f))
            drawLine(
                ink,
                Offset(box * 0.50f, box * 0.68f),
                Offset(box * 0.50f, box * 0.94f),
                stroke * 0.5f,
            )
            drawRect(ink, Offset(box * 0.40f, box * 0.14f), Size(box * 0.20f, box * 0.10f))
        }

        Glyph.LADDER -> {
            drawLine(
                ink,
                Offset(box * 0.30f, box * 0.06f),
                Offset(box * 0.24f, box * 0.94f),
                stroke * 0.7f,
            )
            drawLine(
                ink,
                Offset(box * 0.70f, box * 0.06f),
                Offset(box * 0.76f, box * 0.94f),
                stroke * 0.7f,
            )
            listOf(0.22f, 0.40f, 0.58f, 0.76f).forEach { y ->
                drawLine(
                    ink,
                    Offset(box * 0.26f, box * y),
                    Offset(box * 0.74f, box * y),
                    stroke * 0.55f,
                )
            }
        }

        Glyph.PANEL_BOX -> {
            drawRoundRect(ink, box * 0.14f, box * 0.14f, box * 0.72f, box * 0.72f, box * 0.06f)
            drawLine(
                IsoWhite.copy(alpha = alpha),
                Offset(box * 0.50f, box * 0.14f),
                Offset(box * 0.50f, box * 0.86f),
                stroke * 0.4f,
            )
            listOf(0.30f, 0.46f, 0.62f).forEach { y ->
                drawRect(
                    IsoWhite.copy(alpha = alpha),
                    Offset(box * 0.24f, box * y),
                    Size(box * 0.16f, box * 0.07f),
                )
            }
            drawCircle(IsoWhite.copy(alpha = alpha), box * 0.06f, Offset(box * 0.68f, box * 0.34f))
        }

        Glyph.CRAWLING_PERSON -> {
            drawCircle(ink, box * 0.11f, Offset(box * 0.24f, box * 0.50f))
            val body = Path().apply {
                moveTo(box * 0.34f, box * 0.56f)
                lineTo(box * 0.72f, box * 0.52f)
            }
            drawPath(body, ink, style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round))
            listOf(0.42f, 0.66f).forEach { x ->
                drawLine(
                    ink,
                    Offset(box * x, box * 0.56f),
                    Offset(box * (x + 0.04f), box * 0.84f),
                    stroke * 0.7f,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                ink,
                Offset(box * 0.10f, box * 0.86f),
                Offset(box * 0.90f, box * 0.86f),
                stroke * 0.5f,
            )
        }

        Glyph.CLOSING_DOOR -> {
            drawRect(ink, Offset(box * 0.16f, box * 0.10f), Size(box * 0.10f, box * 0.80f))
            drawRoundRect(ink, box * 0.30f, box * 0.10f, box * 0.36f, box * 0.80f, box * 0.04f)
            drawCircle(IsoWhite.copy(alpha = alpha), box * 0.05f, Offset(box * 0.60f, box * 0.50f))
            drawLine(
                ink,
                Offset(box * 0.90f, box * 0.50f),
                Offset(box * 0.72f, box * 0.50f),
                stroke * 0.6f,
                cap = StrokeCap.Round,
            )
            rotate(-90f, pivot = Offset(box * 0.72f, box * 0.50f)) {
                arrowHead(ink, Offset(box * 0.72f, box * 0.50f), box * 0.12f, stroke * 0.6f)
            }
        }

        Glyph.DRAG_CASUALTY -> {
            person(ink, box * 0.28f, box * 0.44f, box * 0.64f, stroke * 0.8f)
            drawCircle(ink, box * 0.10f, Offset(box * 0.62f, box * 0.72f))
            drawLine(
                ink,
                Offset(box * 0.70f, box * 0.76f),
                Offset(box * 0.94f, box * 0.80f),
                stroke * 0.9f,
                cap = StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(box * 0.38f, box * 0.60f),
                Offset(box * 0.56f, box * 0.70f),
                stroke * 0.5f,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/** Standing figure, centred on ([cx], [cy]) with total height [height]. */
private fun DrawScope.person(ink: Color, cx: Float, cy: Float, height: Float, stroke: Float) {
    val headRadius = height * 0.16f
    val headY = cy - height * 0.34f
    drawCircle(ink, headRadius, Offset(cx, headY))

    val torsoTop = headY + headRadius * 1.3f
    val torsoBottom = cy + height * 0.10f
    drawLine(ink, Offset(cx, torsoTop), Offset(cx, torsoBottom), stroke, cap = StrokeCap.Round)

    val armY = torsoTop + height * 0.10f
    drawLine(
        ink,
        Offset(cx - height * 0.20f, armY + height * 0.10f),
        Offset(cx + height * 0.20f, armY + height * 0.10f),
        stroke * 0.85f,
        cap = StrokeCap.Round,
    )
    drawLine(
        ink,
        Offset(cx, torsoBottom),
        Offset(cx - height * 0.16f, cy + height * 0.40f),
        stroke * 0.9f,
        cap = StrokeCap.Round,
    )
    drawLine(
        ink,
        Offset(cx, torsoBottom),
        Offset(cx + height * 0.16f, cy + height * 0.40f),
        stroke * 0.9f,
        cap = StrokeCap.Round,
    )
}

/** Figure mid-stride, for the exit and do-not-run glyphs. */
private fun DrawScope.runningPerson(ink: Color, box: Float, stroke: Float) {
    drawCircle(ink, box * 0.11f, Offset(box * 0.42f, box * 0.18f))
    val torso = Path().apply {
        moveTo(box * 0.44f, box * 0.30f)
        lineTo(box * 0.52f, box * 0.56f)
    }
    drawPath(torso, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
    drawLine(
        ink,
        Offset(box * 0.46f, box * 0.38f),
        Offset(box * 0.20f, box * 0.30f),
        stroke * 0.8f,
        cap = StrokeCap.Round,
    )
    drawLine(
        ink,
        Offset(box * 0.48f, box * 0.42f),
        Offset(box * 0.74f, box * 0.34f),
        stroke * 0.8f,
        cap = StrokeCap.Round,
    )
    drawLine(
        ink,
        Offset(box * 0.52f, box * 0.56f),
        Offset(box * 0.30f, box * 0.86f),
        stroke * 0.9f,
        cap = StrokeCap.Round,
    )
    drawLine(
        ink,
        Offset(box * 0.52f, box * 0.56f),
        Offset(box * 0.80f, box * 0.78f),
        stroke * 0.9f,
        cap = StrokeCap.Round,
    )
}

/** Running figure plus a doorway. [upward] draws the door above rather than beside. */
private fun DrawScope.exitGlyph(
    ink: Color,
    box: Float,
    stroke: Float,
    mirrored: Boolean,
    upward: Boolean,
) {
    if (upward) {
        drawRect(ink, Offset(box * 0.62f, box * 0.10f), Size(box * 0.30f, box * 0.62f))
        drawRect(
            IsoGreen,
            Offset(box * 0.68f, box * 0.16f),
            Size(box * 0.18f, box * 0.50f),
        )
        runningPerson(ink, box * 0.86f, stroke * 0.9f)
        arrowHead(ink, Offset(box * 0.22f, box * 0.14f), box * 0.14f, stroke * 0.7f)
        return
    }

    if (mirrored) {
        // Mirroring rather than drawing a second glyph keeps the two exits visually identical apart
        // from direction, which is exactly the distinction the drill is testing.
        translate(left = box) {
            scaleHorizontallyByMinusOne {
                exitBody(ink, box, stroke)
            }
        }
    } else {
        exitBody(ink, box, stroke)
    }
}

private fun DrawScope.exitBody(ink: Color, box: Float, stroke: Float) {
    drawRect(ink, Offset(box * 0.66f, box * 0.10f), Size(box * 0.26f, box * 0.80f))
    drawRect(IsoGreen, Offset(box * 0.72f, box * 0.16f), Size(box * 0.14f, box * 0.68f))
    runningPerson(ink, box * 0.82f, stroke * 0.85f)
}

/**
 * Horizontal mirror.
 *
 * `DrawScope.scale` with a negative x is the documented way to do this, and wrapping it keeps the sign
 * convention in one place rather than repeated at each mirrored glyph.
 */
private inline fun DrawScope.scaleHorizontallyByMinusOne(crossinline block: DrawScope.() -> Unit) {
    scale(scaleX = -1f, scaleY = 1f, pivot = Offset.Zero) {
        block()
    }
}

private fun DrawScope.arrowHead(ink: Color, tip: Offset, size: Float, stroke: Float) {
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - size * 0.62f, tip.y + size)
        lineTo(tip.x + size * 0.62f, tip.y + size)
        close()
    }
    drawPath(head, ink)
    if (stroke > 0f) {
        drawPath(head, ink, style = Stroke(width = stroke * 0.3f, join = StrokeJoin.Round))
    }
}

private fun DrawScope.tick(ink: Color, box: Float, stroke: Float, scale: Float, dx: Float, dy: Float) {
    val path = Path().apply {
        moveTo(box * (dx + 0.18f * scale), box * (dy + 0.52f * scale))
        lineTo(box * (dx + 0.42f * scale), box * (dy + 0.76f * scale))
        lineTo(box * (dx + 0.84f * scale), box * (dy + 0.22f * scale))
    }
    drawPath(
        path,
        ink,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.headOutline(ink: Color, box: Float, alpha: Float, stroke: Float) {
    drawCircle(
        ink.copy(alpha = alpha * 0.45f),
        box * 0.30f,
        Offset(box * 0.50f, box * 0.56f),
        style = Stroke(width = stroke * 0.5f),
    )
}

private fun DrawScope.drawRoundRect(
    ink: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
) {
    drawPath(
        Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = Rect(left, top, left + width, top + height),
                    radiusX = corner,
                    radiusY = corner,
                ),
            )
        },
        ink,
    )
}
