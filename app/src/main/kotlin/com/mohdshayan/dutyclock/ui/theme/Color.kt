package com.mohdshayan.dutyclock.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Hi-vis chartreuse and wet tarmac. Light mode carries the brand; dark mode is lifted blue slate.
 * HiVis is the one accent and never carries text on light Kerb (1.4:1): in light mode it is a fill
 * under Tarmac text or a mark on the Tarmac disc plate. Stop is the error role only.
 */

// Light mode
val LightBackground = Color(0xFFECEFEA) // Kerb
val LightSurface = Color(0xFFF8F9F5) // Signboard
val LightOnBackground = Color(0xFF1D2529) // Tarmac
val LightOnSurfaceVariant = Color(0xFF55616A) // Slate
val LightRule = Color(0xFFD2D8D1) // dividers between rows
val LightAccent = Color(0xFFB9D839) // HiVis
val LightOnAccent = Color(0xFF1D2529) // Tarmac
val LightError = Color(0xFFB3261E) // Stop
val LightOnError = Color(0xFFF8F9F5)
val LightDiscPlate = Color(0xFF1D2529) // Tarmac plate
val LightDiscInk = Color(0xFFECEFEA) // Kerb ink on the plate, 13.4:1

// Dark mode
val DarkBackground = Color(0xFF1F272B) // Kerb
val DarkSurface = Color(0xFF2A3439) // Signboard
val DarkOnBackground = Color(0xFFE8ECE6) // Tarmac
val DarkOnSurfaceVariant = Color(0xFFA3AEB4) // Slate
val DarkRule = Color(0xFF3A464C)
val DarkAccent = Color(0xFFB9D839) // HiVis
val DarkOnAccent = Color(0xFF1D2529)
val DarkError = Color(0xFFEE8A7F) // Stop
val DarkOnError = Color(0xFF1F272B)
val DarkDiscPlate = Color(0xFF2A3439) // Signboard plate
val DarkDiscInk = Color(0xFFE8ECE6) // 10.7:1 on the plate
