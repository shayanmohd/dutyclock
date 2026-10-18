package com.mohdshayan.dutyclock.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.ui.theme.RadiusSm

/**
 * A duration as two digits-only boxes, hours and minutes. It needs only the keys 0 to 9, so it
 * works on every keyboard, whichever separator keys it shows. The focused box scrolls above the
 * keyboard as the keyboard opens.
 */
@Composable
fun DurationField(
    label: String,
    hours: String,
    minutes: String,
    onHours: (String) -> Unit,
    onMinutes: (String) -> Unit,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(hours, { onHours(it.filter(Char::isDigit).take(3)) }, "0", "h", "$label, hours", ImeAction.Next, null, Modifier.weight(1f))
            Box(minutes, { onMinutes(it.filter(Char::isDigit).take(2)) }, "00", "min", "$label, minutes", if (last) ImeAction.Done else ImeAction.Next, onDone, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Box(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    suffix: String,
    description: String,
    ime: ImeAction,
    onDone: (() -> Unit)?,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Runs again as the keyboard's inset grows, so the box ends above the keyboard once it settles.
    LaunchedEffect(focused, imeBottom) {
        if (focused && imeBottom > 0) requester.bringIntoView()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = cs.onSurfaceVariant) },
        suffix = { Text(suffix, color = cs.onSurfaceVariant) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ime),
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        shape = RoundedCornerShape(RadiusSm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onBackground, unfocusedBorderColor = cs.outline, cursorColor = cs.onBackground,
        ),
        modifier = modifier
            .widthIn(max = 160.dp)
            .heightIn(min = 56.dp)
            .bringIntoViewRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
    )
}
