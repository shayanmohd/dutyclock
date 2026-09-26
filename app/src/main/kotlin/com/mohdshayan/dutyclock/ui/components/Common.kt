package com.mohdshayan.dutyclock.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.core.engine.Piece
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.ui.theme.RadiusMd
import com.mohdshayan.dutyclock.ui.theme.RadiusSm

/** Group heading: a plain title in the display face, never an all-caps label. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

/** Primary action: HiVis fill under Tarmac text, 48dp tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(RadiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(RadiusMd),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Centred empty state: what to do next, with one button. [art] is drawn above the title.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    art: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (art != null) {
            art(); Spacer(Modifier.height(20.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(actionLabel, onAction)
        }
    }
}

/** The shared log error, in the app's voice: what happened and how to fix it. */
@Composable
fun LogErrorState(modifier: Modifier = Modifier, onRecords: (() -> Unit)? = null) {
    EmptyState(
        title = "Your log could not be read",
        body = "Restore a backup from Records.",
        modifier = modifier,
        actionLabel = if (onRecords != null) "Open Records" else null,
        onAction = onRecords,
        art = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp)) },
    )
}

/** A block of the skeleton: a flat shape in the surface colour, no shimmer. */
@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, round: Boolean = false) {
    Box(
        modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, if (round) CircleShape else RoundedCornerShape(RadiusSm)),
    )
}

@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SkeletonBlock(140.dp, 16.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBlock(220.dp, 12.dp)
                }
                SkeletonBlock(56.dp, 24.dp)
            }
            RowDivider()
        }
    }
}

/** A day's activity as a flat strip, the Week and Log screens' miniature of the disc bands. */
@Composable
fun DayStripBar(pieces: List<Piece>, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val ink = MaterialTheme.colorScheme.onBackground
    val track = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier.height(height)) {
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        for (p in pieces) {
            val frac = when (p.mode) {
                Mode.DRIVE -> 1f
                Mode.WORK -> 0.62f
                Mode.AVAILABLE -> 0.34f
                Mode.REST -> 0f
            }
            if (frac == 0f) continue
            val x = size.width * p.fromMin / 1440f
            val w = (size.width * (p.toMin - p.fromMin) / 1440f).coerceAtLeast(1f)
            val h = size.height * frac
            drawRect(ink, androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h))
        }
    }
}

/** A text button in Tarmac: HiVis text would fail contrast on the light surfaces. */
@Composable
fun InkTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A choice chip in the 4dp radius, HiVis fill when selected. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(if (selected) cs.primary else cs.background, RoundedCornerShape(RadiusSm))
            .border(1.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(RadiusSm))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimary else cs.onBackground)
    }
}
