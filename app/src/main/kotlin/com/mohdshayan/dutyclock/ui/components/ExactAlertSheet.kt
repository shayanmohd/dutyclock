package com.mohdshayan.dutyclock.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.ui.theme.SheetShape

/** Why Dutyclock asks for exact alarms, and the way to the system setting. Shared by Today and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExactAlertSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SheetShape, containerColor = cs.surfaceVariant) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Allow exact alerts", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                "A break alert is only useful at the minute the limit is reached. Android asks you to allow Dutyclock to set exact alarms. It sets at most three at a time, and only for your own limits. While one is set, Android shows an alarm icon.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Open the setting", {
                onDismiss()
                if (Build.VERSION.SDK_INT >= 31) {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                }
            }, Modifier.fillMaxWidth())
            InkTextButton("Not now", onDismiss, Modifier.fillMaxWidth())
        }
    }
}
