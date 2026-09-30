package org.jaagruk.safety.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Coral = Color(0xFFFF6B6E)
val CoralInk = Color(0xFFA52D3C)

// Coral carries the brand; darker ink carries small text and selected controls.
internal val CoralLightScheme = lightColorScheme(
    primary = CoralInk, onPrimary = Color.White,
    primaryContainer = Coral, onPrimaryContainer = Color(0xFF171717),
    inversePrimary = Coral,
    secondary = Color(0xFF525252), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F5F5), onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF155DFF), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9F2FF), onTertiaryContainer = Color(0xFF022FCA),
    background = Color.White, onBackground = Color(0xFF0A0A0A),
    surface = Color.White, onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFF737373), outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFB91C1C), onError = Color.White,
    errorContainer = Color(0xFFFFE9E9), onErrorContainer = Color(0xFF930909),
    inverseSurface = Color(0xFF171717), inverseOnSurface = Color(0xFFFAFAFA),
)

internal val CoralDarkScheme = darkColorScheme(
    primary = Coral, onPrimary = Color(0xFF171717),
    primaryContainer = Coral, onPrimaryContainer = Color(0xFF171717),
    inversePrimary = CoralInk,
    secondary = Color(0xFFD4D4D4), onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color(0xFFFAFAFA),
    tertiary = Color(0xFF92C5FF), onTertiary = Color(0xFF022FCA),
    tertiaryContainer = Color(0xFF10243A), onTertiaryContainer = Color(0xFF92C5FF),
    background = Color(0xFF0A0A0A), onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF0A0A0A), onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF19191A),
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0xFFA3A3A3), outlineVariant = Color(0xFF282828),
    error = Color(0xFFFF8A93), onError = Color(0xFF3F0009),
    errorContainer = Color(0xFF930909), onErrorContainer = Color(0xFFFAFAFA),
    inverseSurface = Color(0xFFFAFAFA), inverseOnSurface = Color(0xFF171717),
)
