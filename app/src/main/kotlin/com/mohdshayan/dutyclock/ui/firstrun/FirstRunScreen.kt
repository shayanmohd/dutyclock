package com.mohdshayan.dutyclock.ui.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import kotlinx.coroutines.launch

/** One screen: pick the rules, accept that this is not a tachograph, start counting. */
@Composable
fun FirstRunScreen() {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var ruleSet by rememberSaveable { mutableStateOf(RuleSet.EU) }
    var understood by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            Text("Dutyclock", style = MaterialTheme.typography.displayMedium, color = cs.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                "The drivers' hours you owe, counted down. Pick the rules you drive under today. You can change them for any day.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            RowDivider()
            for (rs in RuleSet.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .selectable(selected = rs == ruleSet, role = Role.RadioButton) { ruleSet = rs }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = rs == ruleSet,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = cs.onBackground, unselectedColor = cs.onSurfaceVariant),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(rs.label, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(rs.scope, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                RowDivider()
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Dutyclock is an advisory planner worked out from your own taps. It is not a tachograph, not an ELD and not a legal record, and it does not replace your tachograph or your operator's records. Check it only when parked.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onBackground,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = understood, role = Role.Checkbox) { understood = it }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = understood,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = cs.primary, checkmarkColor = cs.onPrimary, uncheckedColor = cs.onSurfaceVariant),
                )
                Text(
                    "I understand this is not a tachograph",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onBackground,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                "Start counting",
                onClick = { scope.launch { ServiceLocator.appPrefs.finishOnboarding(ruleSet) } },
                enabled = understood,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
