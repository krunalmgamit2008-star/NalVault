package com.kavach.vault

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Indigo brand seed, with a translucent glass finish so the page gradient shows through. */
@Composable
fun KavachVaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) {
        darkColorScheme(
            primary = Color(0xFFB4A6FF),
            secondary = Color(0xFF64D2FF),
            tertiary = Color(0xFFFFB340),
            background = Color(0xFF0E1020)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5E5CE6),
            secondary = Color(0xFF0A84FF),
            tertiary = Color(0xFFFF9F0A),
            background = Color(0xFFF3F1FF)
        )
    }
    MaterialTheme(colorScheme = base.withGlass(dark), content = content)
}

private fun ColorScheme.withGlass(dark: Boolean): ColorScheme = copy(
    surface = surface.copy(alpha = if (dark) 0.55f else 0.70f),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = if (dark) 0.45f else 0.60f),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = if (dark) 0.50f else 0.65f),
    surfaceContainer = surfaceContainer.copy(alpha = if (dark) 0.55f else 0.70f),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = if (dark) 0.60f else 0.75f),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = if (dark) 0.65f else 0.80f)
)
