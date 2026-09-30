package org.jaagruk.safety.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * One step up from the base build throughout.
 *
 * The reader is at arm's length, in bad light, possibly in a helmet, possibly with a cracked
 * screen protector, and quite possibly not a confident reader in any script. Body text went
 * 14 -> 17 sp, titles 16 -> 22 sp, and the display size exists so a readiness score can be the
 * largest thing on the dashboard.
 *
 * Line heights go up more than proportionally. Devanagari and Ol Chiki both have marks above and
 * below the baseline that Latin does not, and the tight leading that looks crisp in English
 * clips them. Ol Chiki in particular needs the room.
 *
 * `fontFeatureSettings = "tnum"` is on every style that can contain a digit. Proportional
 * numerals change width as they change value, so a readiness score animating from 850 to 696
 * visibly jitters, and a column of scores does not line up. Tabular figures fix both. It is a
 * one-line change that is very hard to notice and very hard to un-notice.
 */
private const val TABULAR = "tnum"

val Typography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    // The readiness number. Large enough to read across a room, which is the point of a
    // supervisor holding up a phone.
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 46.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),

    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 42.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 38.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 33.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),

    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 27.sp,
        fontFeatureSettings = TABULAR,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp,
        fontFeatureSettings = TABULAR,
    ),

    // 17 sp, not 14. This is the size a safety instruction is read at.
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 28.sp,
        fontFeatureSettings = TABULAR,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp,
        fontFeatureSettings = TABULAR,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp,
        fontFeatureSettings = TABULAR,
    ),

    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    // Bottom-nav labels and chart axis ticks. Held at 12 sp because five nav labels have to fit
    // across a 1080 px screen in three scripts, and Devanagari is wider than Latin at the same
    // size. Anything larger truncates in Hindi before it truncates in English, which is exactly
    // the failure mode nobody tests for.
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
)
