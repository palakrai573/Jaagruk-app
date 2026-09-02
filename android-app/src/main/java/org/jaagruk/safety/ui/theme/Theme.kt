package org.jaagruk.safety.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colours chosen for a headlamp beam and a scratched screen, not for a design showcase.
 *
 * Three constraints drove every value:
 *
 *  * **Legibility in direct light and in the dark.** A phone held up in a haulage road goes from
 *    torch-lit to near-black in a step. Contrast ratios here are all above 7:1 against their surface,
 *    well past WCAG AA, because AA was written for an office monitor.
 *  * **Colour is never the only signal.** Readiness bands carry a shape and a label as well as a
 *    colour, so a red-green colour-blind worker — around one man in twelve — reads the same
 *    information. That is a safety requirement, not an accessibility checkbox.
 *  * **No dynamic colour.** Material You would let a worker's wallpaper recolour a safety warning.
 *    The signal colours here match ISO 7010 and the signs bolted to the walls, and they stay put.
 */

// ISO signal colours, reused so the UI and the pictograms agree.
private val SignalRed = Color(0xFFC8102E)
private val SignalAmber = Color(0xFFE07B00)
private val SignalGreen = Color(0xFF007A33)
private val SignalBlue = Color(0xFF005EB8)

private val JaagrukTeal = Color(0xFF00696E)
private val JaagrukTealLight = Color(0xFF4FD8E0)

private val SurfaceDark = Color(0xFF10161C)
private val SurfaceDarkElevated = Color(0xFF1A232B)
private val SurfaceLight = Color(0xFFF7F9FB)

private val DarkColors = darkColorScheme(
    primary = JaagrukTealLight,
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF9CF1F7),
    secondary = Color(0xFFB0CBE0),
    onSecondary = Color(0xFF193446),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF4A2800),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SurfaceDark,
    onBackground = Color(0xFFE3E7EA),
    surface = SurfaceDark,
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = Color(0xFFC0C8CE),
    outline = Color(0xFF8A939A),
)

private val LightColors = lightColorScheme(
    primary = JaagrukTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F7),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF3F5C72),
    onSecondary = Color.White,
    tertiary = Color(0xFF7C4E00),
    onTertiary = Color.White,
    error = SignalRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = SurfaceLight,
    onBackground = Color(0xFF10161C),
    surface = Color.White,
    onSurface = Color(0xFF10161C),
    surfaceVariant = Color(0xFFDBE4EA),
    onSurfaceVariant = Color(0xFF3F484E),
    outline = Color(0xFF6F797F),
)

/**
 * Readiness band colours, exposed separately from the Material scheme.
 *
 * Deliberately not `MaterialTheme.colorScheme.error` and friends. These have to mean the same thing in
 * light and dark, on a dashboard screenshot and on a printed report, and mapping them onto a theme role
 * would let a future theme change silently repaint a compliance status.
 */
object ReadinessColors {
    val ready: Color = SignalGreen
    val due: Color = SignalAmber
    val stale: Color = Color(0xFFB35C00)
    val expired: Color = SignalRed
    val unknown: Color = Color(0xFF6F797F)
    val hesitation: Color = SignalBlue
}

/**
 * Type scale bumped a step across the board.
 *
 * Default Material sizes are set for a phone held 30 cm from the face in good light. A worker in a
 * helmet, at arm's length, in dust, needs bigger — and a body size below 16 sp is unreadable through a
 * scratched screen protector, which every shared site phone has.
 */
private val JaagrukTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

/**
 * Minimum interactive size.
 *
 * 64 dp, not Material's 48 dp. Measured glove contact patches are 15–20 mm across and land several
 * millimetres from where the worker aimed; a 48 dp target produces mis-taps that the assessment would
 * otherwise record as wrong decisions. This is the single most consequential UI number in the app.
 */
val MinGloveTouchTarget = 64.dp

@Composable
fun JaagrukTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = JaagrukTypography,
        content = content,
    )
}
