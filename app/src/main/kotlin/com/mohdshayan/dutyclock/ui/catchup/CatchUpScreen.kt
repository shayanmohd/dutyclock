package com.mohdshayan.dutyclock.ui.catchup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohdshayan.dutyclock.core.engine.Durations
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.ChoiceChip
import com.mohdshayan.dutyclock.ui.components.DatePickDialog
import com.mohdshayan.dutyclock.ui.components.PickerField
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.SectionTitle
import com.mohdshayan.dutyclock.ui.components.TimePickDialog
import com.mohdshayan.dutyclock.ui.nav.LocalShellScope
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import com.mohdshayan.dutyclock.ui.theme.RadiusSm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.launch

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/**
 * The catch-up sheet for a week already under way: what the tachograph shows so far, entered
 * once. The engine starts from these numbers now and replays only what is logged after them.
 */
@Composable
fun CatchUpScreen(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val shell = LocalShellScope.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val repo = ServiceLocator.logRepository
    val existing by ServiceLocator.database.seedDao().observe().collectAsStateWithLifecycle(initialValue = null)

    var sinceBreak by rememberSaveable { mutableStateOf("") }
    var started by rememberSaveable { mutableStateOf(true) }
    var startTime by rememberSaveable { mutableStateOf(LocalTime.of(6, 0)) }
    var drivingToday by rememberSaveable { mutableStateOf("") }
    var thisWeek by rememberSaveable { mutableStateOf("") }
    var lastWeek by rememberSaveable { mutableStateOf("") }
    var workWeek by rememberSaveable { mutableStateOf("") }
    var workPeriod by rememberSaveable { mutableStateOf("") }
    var tenUsed by rememberSaveable { mutableStateOf(0) }
    var reducedUsed by rememberSaveable { mutableStateOf(0) }
    var weeklyDate by rememberSaveable { mutableStateOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) }
    var weeklyTime by rememberSaveable { mutableStateOf(LocalTime.of(6, 0)) }
    var weeklyLen by rememberSaveable { mutableStateOf("45") }
    var owed by rememberSaveable { mutableStateOf("") }
    var dueDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }

    fun dur(label: String, v: String): Long? {
        if (v.isBlank()) return 0L
        return Durations.parse(v) ?: run { error = "$label: use hours and minutes, like 3.40."; null }
    }

    fun save() {
        error = null
        val sb = dur("Driving since your last break", sinceBreak) ?: return
        val dt = dur("Driving today", drivingToday) ?: return
        val tw = dur("Driving this week", thisWeek) ?: return
        val lw = dur("Driving last week", lastWeek) ?: return
        val ww = dur("Work this week", workWeek) ?: return
        val wp = dur("Work earlier in the period", workPeriod) ?: return
        val wl = dur("Weekly rest length", weeklyLen) ?: return
        val ow = dur("Compensation owed", owed) ?: return
        val hour = 60 * MINUTE_MS
        when {
            sb > 24 * hour || dt > 24 * hour -> { error = "Today's driving cannot be more than 24 hours. Check it."; return }
            tw > 168 * hour || lw > 168 * hour || ww > 168 * hour -> { error = "A week holds at most 168 hours. Check this week and last."; return }
            wl < 24 * hour -> { error = "A weekly rest is at least 24 hours. Check how long it was."; return }
            ow > 21 * hour -> { error = "Compensation owed is at most 21 hours, 45 less 24. Check it."; return }
        }
        val now = System.currentTimeMillis()
        val dayStart = if (started) today.atTime(startTime).atZone(zone).toInstant().toEpochMilli() else null
        if (dayStart != null && dayStart > now) { error = "Today's work cannot start in the future. Pick an earlier time."; return }
        if (sb > dt && started) { error = "Driving since your last break is more than today's driving. Check both."; return }
        if (dt > tw) { error = "Today's driving is more than this week's. Check both."; return }
        val weeklyEnd = weeklyDate.atTime(weeklyTime).atZone(zone).toInstant().toEpochMilli()
        if (weeklyEnd > now) { error = "Your last weekly rest cannot end in the future."; return }
        val due = when {
            ow == 0L -> null
            dueDate != null -> dueDate!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            else -> FixedWeek.start(weeklyEnd - wl) + 4 * WEEK_MS
        }
        val seed = Seed(
            seededAtUtc = now,
            drivingSinceBreakMin = (sb / MINUTE_MS).toInt(),
            dayStartUtc = dayStart,
            drivingTodayMin = (dt / MINUTE_MS).toInt(),
            thisWeekDrivingMin = (tw / MINUTE_MS).toInt(),
            lastWeekDrivingMin = (lw / MINUTE_MS).toInt(),
            thisWeekWorkMin = (ww / MINUTE_MS).toInt(),
            rtdPeriodWorkMin = (wp / MINUTE_MS).toInt(),
            tenHourDaysUsed = tenUsed,
            reducedDailyRestsUsed = reducedUsed,
            lastWeeklyRestEndUtc = weeklyEnd,
            lastWeeklyRestMin = (wl / MINUTE_MS).toInt(),
            compensationOwedMin = (ow / MINUTE_MS).toInt(),
            compensationDueUtc = due,
        )
        scope.launch {
            repo.saveSeed(seed)
            onDone()
            shell.launch { snackbar.showSnackbar("Week brought in") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Text(
                "Read these off your tachograph or its printout. Type hours and minutes like 3.40, and leave a box empty for zero. From now on, Dutyclock counts from these numbers plus what you tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            existing?.let {
                Text(
                    "This replaces the numbers you brought in on ${Fmt.shortDay(it.seededAtUtc, zone)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onBackground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionTitle("Today")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(started, role = Role.Switch) { started = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("I have started work today", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
                Switch(started, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary))
            }
            if (started) {
                PickerField("Work started at", "%02d:%02d".format(startTime.hour, startTime.minute), { picker = "start" })
                Field("Driving since your last break", sinceBreak) { sinceBreak = it }
                Field("Driving today", drivingToday) { drivingToday = it }
            }

            SectionTitle("This week and last")
            Field("Driving this week, since Monday", thisWeek) { thisWeek = it }
            Field("Driving last week", lastWeek) { lastWeek = it }
            Text("10-hour days used this week", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 0..2) ChoiceChip("$n", tenUsed == n, { tenUsed = n }) }
            Text("Reduced daily rests since your weekly rest", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 0..3) ChoiceChip("$n", reducedUsed == n, { reducedUsed = n }) }

            SectionTitle("Last weekly rest")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PickerField("Ended on", dayFmt.format(weeklyDate), { picker = "weeklyDate" }, Modifier.weight(1f))
                PickerField("At", "%02d:%02d".format(weeklyTime.hour, weeklyTime.minute), { picker = "weeklyTime" }, Modifier.weight(1f))
            }
            Field("How long it was, in hours", weeklyLen) { weeklyLen = it }
            Field("Compensation still owed, in hours", owed) { owed = it }
            if (owed.isNotBlank()) {
                PickerField("Owed by the end of", dueDate?.let { dayFmt.format(it) } ?: "Third week after that rest", { picker = "due" })
            }

            SectionTitle("Working time")
            Field("Work this week: driving and other work", workWeek) { workWeek = it }
            Field("Work earlier in this reference period", workPeriod) { workPeriod = it }

            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = cs.error, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Bring my week in", ::save, Modifier.fillMaxWidth())
            if (existing != null) {
                TextButton(onClick = { scope.launch { repo.clearSeed(); onDone(); shell.launch { snackbar.showSnackbar("Catch-up cleared") } } }) {
                    Text("Clear the catch-up", color = cs.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picker) {
        "start" -> TimePickDialog("Work started at", startTime, { picker = null }) { startTime = it; picker = null }
        "weeklyTime" -> TimePickDialog("Weekly rest ended at", weeklyTime, { picker = null }) { weeklyTime = it; picker = null }
        "weeklyDate" -> DatePickDialog(weeklyDate, today, { picker = null }) { weeklyDate = it; picker = null }
        "due" -> DatePickDialog(dueDate ?: today.plusWeeks(3), today.plusWeeks(5), { picker = null }) { dueDate = it; picker = null }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(6)) },
        label = { Text(label) },
        placeholder = { Text("0.00") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(RadiusSm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onBackground, unfocusedBorderColor = cs.outline, cursorColor = cs.onBackground,
            focusedLabelColor = cs.onBackground, unfocusedLabelColor = cs.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
