package com.mohdshayan.dutyclock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.core.engine.Counter
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.ui.theme.RadiusMd
import com.mohdshayan.dutyclock.ui.theme.SheetShape

/** The mode bar: four 72dp pictogram buttons, the current mode filled HiVis. */
@Composable
fun ModeBar(current: Mode?, onTap: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in Mode.entries) {
            val on = mode == current
            Column(
                Modifier
                    .weight(1f)
                    .height(72.dp)
                    .background(if (on) cs.primary else cs.surfaceVariant, RoundedCornerShape(RadiusMd))
                    .border(1.dp, if (on) cs.primary else cs.outlineVariant, RoundedCornerShape(RadiusMd))
                    .clickable(role = Role.Tab, onClickLabel = "Start ${mode.label.lowercase()}") { onTap(mode) }
                    .semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(TachoIcons.of(mode), contentDescription = null, tint = if (on) cs.onPrimary else cs.onBackground, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    shortLabel(mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) cs.onPrimary else cs.onBackground,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

fun shortLabel(mode: Mode) = when (mode) {
    Mode.DRIVE -> "Driving"
    Mode.WORK -> "Work"
    Mode.AVAILABLE -> "Available"
    Mode.REST -> "Rest"
}

/** One limit: title and detail on the left, what is left on the right, "Over" with an icon on a breach. */
@Composable
fun LimitRow(counter: Counter, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Show the rule", onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(counter.title, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            Spacer(Modifier.height(2.dp))
            Text(counter.detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (counter.over) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = cs.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Over", style = MaterialTheme.typography.titleLarge, color = cs.error)
                    Text(Fmt.hm(-counter.remainingMs), style = MaterialTheme.typography.labelMedium, color = cs.error)
                }
            }
        } else {
            Text(counter.value, style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
        }
    }
}

/** The rule behind a counter, with its working and its source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(counter: Counter, onDismiss: () -> Unit, onOffer: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss, shape = SheetShape, containerColor = cs.surfaceVariant,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(counter.title, style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                if (counter.over) "Over by ${Fmt.hm(-counter.remainingMs)}" else counter.value,
                style = MaterialTheme.typography.displayMedium,
                color = if (counter.over) cs.error else cs.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(counter.detail, style = MaterialTheme.typography.bodyLarge, color = cs.onBackground)
            Spacer(Modifier.height(16.dp))
            Text("How it is counted", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            Spacer(Modifier.height(6.dp))
            for (line in counter.working) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground, modifier = Modifier.padding(vertical = 3.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Source: ${counter.source}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "Advisory. Your tachograph and your operator's records are the legal record.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            if (onOffer != null && counter.offerLabel != null) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton(counter.offerLabel!!, onOffer, Modifier.fillMaxWidth())
            }
        }
    }
}
