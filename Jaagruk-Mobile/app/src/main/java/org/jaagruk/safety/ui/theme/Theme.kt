package org.jaagruk.safety.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalSignals = staticCompositionLocalOf { LightSignals }

/**
 * Signal colours resolved for the current scheme.
 *
 * Not part of [MaterialTheme.colorScheme] on purpose. Material's slots are semantic in a
 * design-system sense (primary, error, tertiary); these are semantic in a *safety* sense, and
 * mapping "prohibition" onto "error" would let a future refactor quietly recolour a sign.
 *
 * Read them through [JaagrukTheme.signals] so a screen never has to know which mode it is in.
 */
class SignalColors(
    val red: Color,
    val amber: Color,
    val green: Color,
    val blue: Color,
    val redContainer: Color,
    val amberContainer: Color,
    val greenContainer: Color,
    val blueContainer: Color,
)

private val LightSignals = SignalColors(
    red = SignalRed,
    amber = SignalAmber,
    green = SignalGreen,
    blue = SignalBlue,
    redContainer = SignalRedTint,
    amberContainer = SignalAmberTint,
    greenContainer = SignalGreenTint,
    blueContainer = SignalBlueTint,
)

private val DarkSignals = SignalColors(
    red = SignalRedDark,
    amber = SignalAmberDark,
    green = SignalGreenDark,
    blue = SignalBlueDark,
    redContainer = Color(0xFF4A0A14),
    amberContainer = Color(0xFF432C05),
    greenContainer = Color(0xFF13361F),
    blueContainer = Color(0xFF10243A),
)

/** Namespace for the things Material's theme has no slot for. */
object JaagrukTheme {
    /** ISO 7010 signal colours, resolved light or dark. */
    val signals: SignalColors
        @Composable get() = LocalSignals.current
}

/**
 * @param darkTheme defaults to the system setting. The base build hardcoded `true`.
 */
@Composable
fun JaagrukTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSignals provides signalsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) CoralDarkScheme else CoralLightScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}

/**
 * Signal colours for an explicitly known mode.
 *
 * Legacy screens pass their selected mode explicitly. New screens read the same
 * resolved mode from [JaagrukTheme.signals].
 */
fun signalsFor(darkTheme: Boolean): SignalColors = if (darkTheme) DarkSignals else LightSignals
