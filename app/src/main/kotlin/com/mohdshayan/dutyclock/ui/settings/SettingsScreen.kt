package com.mohdshayan.dutyclock.ui.settings

import com.mohdshayan.dutyclock.R

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohdshayan.dutyclock.alerts.AlertScheduler
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.ChoiceChip
import com.mohdshayan.dutyclock.ui.components.InkTextButton
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SecondaryButton
import com.mohdshayan.dutyclock.ui.components.SectionTitle
import com.mohdshayan.dutyclock.ui.components.SkeletonRows
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import com.mohdshayan.dutyclock.ui.theme.RadiusMd
import com.mohdshayan.dutyclock.ui.theme.RadiusSm
import com.mohdshayan.dutyclock.ui.theme.SheetShape
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onCatchUp: () -> Unit) {
    val prefs = ServiceLocator.appPrefs
    val settings by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    if (s == null) {
        SkeletonRows(8, Modifier.padding(16.dp)); return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val cs = MaterialTheme.colorScheme
    var exact by remember { mutableStateOf(AlertScheduler.canScheduleExact(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var explainExact by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    LifecycleResumeEffect(Unit) {
        exact = AlertScheduler.canScheduleExact(context)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        scope.launch { AlertScheduler.reschedule(context) }
        onPauseOrDispose { }
    }

    fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, v: T) = scope.launch {
        prefs.set(key, v); AlertScheduler.reschedule(context)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            // Alerts first: they are what can go wrong silently.
            SectionTitle("Alerts")
            if (!exact) {
                Column(
                    Modifier.fillMaxWidth().background(cs.surfaceVariant, RoundedCornerShape(RadiusMd)).padding(14.dp),
                ) {
                    Text("Alerts may arrive a few minutes late", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    Text(
                        "Android is holding back exact alarms for Dutyclock. The countdown notification still runs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Allow exact alerts", { explainExact = true })
                }
            } else {
                StatusLine("Exact alerts allowed", "Break and limit alerts fire at the minute.")
            }
            RowDivider()
            if (notifications) StatusLine("Notifications allowed", "The countdown and the alerts can reach you.")
            else Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications are off", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    Text("Countdowns stay in the app until you allow them.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Open settings", color = cs.onBackground) }
            }
            RowDivider()
            Text("Warn me before a limit", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(5, 10, 15, 30)) ChoiceChip("$m min", s.alertLeadMin == m, { set(AppPrefs.Keys.ALERT_LEAD_MIN, m) })
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Alarm sound", "Plays once, only when Do Not Disturb allows alarms.", s.alertSound) { set(AppPrefs.Keys.ALERT_SOUND, it) }
            Toggle("Vibrate", "For a noisy cab.", s.alertVibrate) { set(AppPrefs.Keys.ALERT_VIBRATE, it) }
            Toggle("Break complete", "Tells you when a break starts to count.", s.alertBreakComplete) { set(AppPrefs.Keys.ALERT_BREAK_COMPLETE, it) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Send a test alert", {
                AlertScheduler.test(context)
                scope.launch { snackbar.showSnackbar("Test alert sent. It arrives in 5 seconds.") }
            })

            SectionTitle("Rule set")
            for (rs in RuleSet.entries) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(rs == s.ruleSet, role = Role.RadioButton) { scope.launch { ServiceLocator.logRepository.setRuleSet(rs) } }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(rs == s.ruleSet, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = cs.onBackground, unselectedColor = cs.onSurfaceVariant))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(rs.label, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(rs.scope, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }

            SectionTitle("Working time period")
            val p = RtdPeriod.containing(System.currentTimeMillis())
            val df = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
            Text(
                "${df.format(p.startDate)} to ${df.format(p.lastDate)}, ${p.weeks} weeks, ${p.limitMin / 60} hours allowed. The default fixed period starts on the Monday on or after 1 April, 1 August and 1 December. Your employer may use another period under an agreement.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Bring your week in", onCatchUp)

            SectionTitle("On the PDF")
            NameField("Driver name", s.driverName, 60) { scope.launch { prefs.set(AppPrefs.Keys.DRIVER_NAME, it) } }
            NameField("Vehicle registration", s.vehicleReg, 20) { scope.launch { prefs.set(AppPrefs.Keys.VEHICLE_REG, it) } }

            SectionTitle("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((v, label) in listOf("system" to "Follow phone", "light" to "Light", "dark" to "Dark")) {
                    ChoiceChip(label, s.themeMode == v, { scope.launch { prefs.set(AppPrefs.Keys.THEME_MODE, v) } })
                }
            }

            SectionTitle("Usage counts")
            Toggle("Count my use on this phone", "Off by default. Kept on this phone and never sent anywhere.", s.usageCountsOptIn) {
                scope.launch { prefs.set(AppPrefs.Keys.USAGE_OPT_IN, it) }
            }
            if (s.usageCountsOptIn) {
                for (c in AppPrefs.Count.entries) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
                        Text("${s.counts[c.key] ?: 0}", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    }
                }
                TextButton(onClick = { scope.launch { prefs.clearCounts() } }) { Text("Clear counts", color = cs.onBackground) }
            }

            SectionTitle("About")
            Text(
                "Dutyclock is an advisory planner worked out from your own taps. It is not a tachograph, not an ELD and not a legal record, and it does not replace your tachograph or operator records. It is not affiliated with DVSA, the Department for Transport, the European Commission or any government body. Check it only when parked.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { dialog = "Privacy" to PRIVACY }) { Text("Privacy", color = cs.onBackground) }
                TextButton(onClick = {
                    val text = context.resources.openRawResource(R.raw.licences).bufferedReader().use { it.readText() }
                    dialog = "Licences" to text
                }) { Text("Licences", color = cs.onBackground) }
                TextButton(onClick = { dialog = "Sources" to SOURCES }) { Text("Sources", color = cs.onBackground) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (explainExact) {
        ModalBottomSheet(onDismissRequest = { explainExact = false }, shape = SheetShape, containerColor = cs.surfaceVariant) {
            Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
                Text("Allow exact alerts", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A break alert is only useful at the minute the limit is reached. Android asks you to allow Dutyclock to set exact alarms. It sets at most three at a time, and only for your own limits.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Open the setting", {
                    explainExact = false
                    if (Build.VERSION.SDK_INT >= 31) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)),
                        )
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }

    dialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body, style = MaterialTheme.typography.bodyMedium) } },
            confirmButton = { InkTextButton("Close", { dialog = null }) },
            containerColor = cs.surfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, body: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Switch(
            checked = value,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary,
                uncheckedTrackColor = cs.surfaceVariant, uncheckedThumbColor = cs.onSurfaceVariant, uncheckedBorderColor = cs.outline,
            ),
        )
    }
}

@Composable
private fun NameField(label: String, value: String, max: Int, onSave: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(max); onSave(text.trim()) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(RadiusSm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onBackground, unfocusedBorderColor = cs.outline, cursorColor = cs.onBackground,
            focusedLabelColor = cs.onBackground, unfocusedLabelColor = cs.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

private const val PRIVACY =
    "Dutyclock keeps everything on this phone: your log, leave days, catch-up numbers and settings, in the app's private storage. " +
        "It has no internet permission, no account, no ads, no analytics and no crash reporting, so nothing is collected or sent. " +
        "Files leave the phone only when you save or share them yourself from Records. Uninstalling the app deletes its data.\n\n" +
        "Permissions: notifications, for the countdown and the alerts; exact alarms, so an alert fires at the minute a limit is reached; " +
        "start at boot, to re-arm alerts after a restart; vibration, for alerts in a noisy cab."

private const val SOURCES =
    "Limits follow Regulation (EC) 561/2006 as amended by Regulation (EU) 2020/1054, the AETR agreement, the GB domestic rules " +
        "of the Transport Act 1968, and the Road Transport (Working Time) Regulations 2005, as explained in the GOV.UK guide " +
        "\"Drivers' hours and tachographs: goods vehicles\" and the Department for Transport's working time guidance.\n\n" +
        "Optional derogations are never applied: no ferry or train interruptions, no multi-manning, no coach 12-day rule, " +
        "no two reduced weekly rests in a row, and a 10-hour day or reduced rest only when you choose it."
