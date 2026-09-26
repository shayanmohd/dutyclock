package com.mohdshayan.dutyclock.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * The whole radius scale, and the rule for using it:
 *   RadiusSm  4dp  chips and fields
 *   RadiusMd  8dp  buttons and groups
 *   RadiusLg 16dp  sheet tops and the two cards (Plan result, catch-up prompt)
 * The Day Disc is the only round thing in the app.
 */
val RadiusSm = 4.dp
val RadiusMd = 8.dp
val RadiusLg = 16.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(RadiusSm),
    small = RoundedCornerShape(RadiusSm),
    medium = RoundedCornerShape(RadiusMd),
    large = RoundedCornerShape(RadiusLg),
    extraLarge = RoundedCornerShape(RadiusLg),
)

val SheetShape = RoundedCornerShape(topStart = RadiusLg, topEnd = RadiusLg)
