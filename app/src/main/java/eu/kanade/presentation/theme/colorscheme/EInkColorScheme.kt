package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Pure black and white high-contrast scheme intended for E-Ink displays.
 *
 * Surface container roles intentionally use the same color as their base surface so elevation
 * does not introduce low-contrast gray bands. Surface tint is also set to the base surface color
 * for the same reason.
 */
internal object EInkColorScheme : BaseColorScheme() {

    private val black = Color(0xFF000000)
    private val white = Color(0xFFFFFFFF)

    override val lightScheme = lightColorScheme(
        primary = black,
        onPrimary = white,
        primaryContainer = black,
        onPrimaryContainer = white,
        inversePrimary = white,
        secondary = black,
        onSecondary = white,
        secondaryContainer = black,
        onSecondaryContainer = white,
        tertiary = black,
        onTertiary = white,
        tertiaryContainer = black,
        onTertiaryContainer = white,
        background = white,
        onBackground = black,
        surface = white,
        onSurface = black,
        surfaceVariant = white,
        onSurfaceVariant = black,
        surfaceTint = white,
        inverseSurface = black,
        inverseOnSurface = white,
        error = black,
        onError = white,
        errorContainer = black,
        onErrorContainer = white,
        outline = black,
        outlineVariant = black,
        scrim = black,
        surfaceBright = white,
        surfaceContainer = white,
        surfaceContainerHigh = white,
        surfaceContainerHighest = white,
        surfaceContainerLow = white,
        surfaceContainerLowest = white,
        surfaceDim = white,
        primaryFixed = white,
        primaryFixedDim = white,
        onPrimaryFixed = black,
        onPrimaryFixedVariant = black,
        secondaryFixed = white,
        secondaryFixedDim = white,
        onSecondaryFixed = black,
        onSecondaryFixedVariant = black,
        tertiaryFixed = white,
        tertiaryFixedDim = white,
        onTertiaryFixed = black,
        onTertiaryFixedVariant = black,
    )

    override val darkScheme = darkColorScheme(
        primary = white,
        onPrimary = black,
        primaryContainer = white,
        onPrimaryContainer = black,
        inversePrimary = black,
        secondary = white,
        onSecondary = black,
        secondaryContainer = white,
        onSecondaryContainer = black,
        tertiary = white,
        onTertiary = black,
        tertiaryContainer = white,
        onTertiaryContainer = black,
        background = black,
        onBackground = white,
        surface = black,
        onSurface = white,
        surfaceVariant = black,
        onSurfaceVariant = white,
        surfaceTint = black,
        inverseSurface = white,
        inverseOnSurface = black,
        error = white,
        onError = black,
        errorContainer = white,
        onErrorContainer = black,
        outline = white,
        outlineVariant = white,
        scrim = black,
        surfaceBright = black,
        surfaceContainer = black,
        surfaceContainerHigh = black,
        surfaceContainerHighest = black,
        surfaceContainerLow = black,
        surfaceContainerLowest = black,
        surfaceDim = black,
        primaryFixed = black,
        primaryFixedDim = black,
        onPrimaryFixed = white,
        onPrimaryFixedVariant = white,
        secondaryFixed = black,
        secondaryFixedDim = black,
        onSecondaryFixed = white,
        onSecondaryFixedVariant = white,
        tertiaryFixed = black,
        tertiaryFixedDim = black,
        onTertiaryFixed = white,
        onTertiaryFixedVariant = white,
    )
}
