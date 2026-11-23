package com.kavach.vault

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Vibrant per-item colours, so folders and features read apart at a glance. */
val IconPalette = listOf(
    Color(0xFF0A84FF),
    Color(0xFF30D158),
    Color(0xFFFF9F0A),
    Color(0xFFFF375F),
    Color(0xFFBF5AF2),
    Color(0xFF64D2FF),
    Color(0xFFFFD60A),
    Color(0xFF5E5CE6)
)

/** The colour a given name maps to; stable because it comes from the name itself. */
fun iconPaletteColor(name: String): Color =
    IconPalette[abs(name.hashCode()) % IconPalette.size]
