package com.mohdshayan.dutyclock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccent,
    onPrimaryContainer = LightOnAccent,
    secondaryContainer = LightAccent,
    onSecondaryContainer = LightOnAccent,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurface,
    outline = LightOnSurfaceVariant,
    outlineVariant = LightRule,
    error = LightError,
    onError = LightOnError,
    inverseSurface = LightOnBackground,
    inverseOnSurface = LightBackground,
    inversePrimary = LightAccent,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccent,
    onPrimaryContainer = DarkOnAccent,
    secondaryContainer = DarkAccent,
    onSecondaryContainer = DarkOnAccent,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkBackground,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurface,
    outline = DarkOnSurfaceVariant,
    outlineVariant = DarkRule,
    error = DarkError,
    onError = DarkOnError,
    inverseSurface = DarkOnBackground,
    inverseOnSurface = DarkBackground,
    inversePrimary = DarkAccent,
)

/** Tokens Material 3 does not model: the Day Disc plate and its ink. */
@Immutable
data class DiscColors(val plate: Color, val ink: Color, val live: Color)

val LocalDiscColors = staticCompositionLocalOf { DiscColors(LightDiscPlate, LightDiscInk, LightAccent) }

/**
 * The app theme. Dynamic colour is off: the accent is part of the identity. [themeMode] is
 * "system", "light" or "dark" from Settings. MainActivity sets the system bar icons to match.
 */
@Composable
fun AppTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val disc = if (darkTheme) DiscColors(DarkDiscPlate, DarkDiscInk, DarkAccent) else DiscColors(LightDiscPlate, LightDiscInk, LightAccent)

    CompositionLocalProvider(
        LocalReducedMotion provides rememberReducedMotion(),
        LocalDiscColors provides disc,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
