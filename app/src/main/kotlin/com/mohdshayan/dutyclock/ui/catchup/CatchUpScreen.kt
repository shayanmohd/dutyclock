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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohdshayan.dutyclock.core.engine.Durations
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.Setup
import com.mohdshayan.dutyclock.core.engine.SetupForm
import com.mohdshayan.dutyclock.core.engine.SetupResult
import com.mohdshayan.dutyclock.core.model.DAY_MS
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.data.repo.EditRefused
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.ChoiceChip
import com.mohdshayan.dutyclock.ui.components.DatePickDialog
import com.mohdshayan.dutyclock.ui.components.DurationField
import com.mohdshayan.dutyclock.ui.components.ModeBar
import com.mohdshayan.dutyclock.ui.components.PickerField
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.SectionTitle
import com.mohdshayan.dutyclock.ui.components.TimePickDialog
import com.mohdshayan.dutyclock.ui.nav.LocalShellScope
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** Hours and minutes as typed into a [DurationField], saved across rotation as two strings. */
private class Dur(h: String = "", m: String = "") {
    var h by mutableStateOf(h)
    var m by mutableStateOf(m)
}

private val DurSaver = androidx.compose.runtime.saveable.Saver<Dur, List<String>>(
    save = { listOf(it.h, it.m) },
    restore = { Dur(it[0], it[1]) },
)

@Composable
private fun rememberDur(h: String = "") = rememberSaveable(saver = DurSaver) { Dur(h) }

/**
 * The catch-up sheet for a week already under way: what the tachograph shows now, and the mode
 * the driver is in since when. Counting starts at that mode's start; the numbers stay the
 * reading, and the engine takes off what the log holds between the two (Setup.start).
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
    val entries by repo.entries.collectAsStateWithLifecycle(initialValue = null)

    var mode by rememberSaveable { mutableStateOf<Mode?>(null) }
    var sinceDate by rememberSaveable { mutableStateOf(today) }
    var sinceTime by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    // The exact start of the entry the sheet was prefilled from, so keeping it adds no second entry.
    var prefilledMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var started by rememberSaveable { mutableStateOf(true) }
    var startYesterday by rememberSaveable { mutableStateOf(false) }
    var startTime by rememberSaveable { mutableStateOf(LocalTime.of(6, 0)) }
    var part15 by rememberSaveable { mutableStateOf(false) }
    val sinceBreak = rememberDur()
    val drivingToday = rememberDur()
    val otherWork = rememberDur()
    val thisWeek = rememberDur()
    val lastWeek = rememberDur()
    val workWeek = rememberDur()
    val workPeriod = rememberDur()
    var tenUsed by rememberSaveable { mutableStateOf(0) }
    var reducedUsed by rememberSaveable { mutableStateOf(0) }
    // The most recent Monday 06:00 that is not after now, so a setup early on a Monday is not refused.
    val weeklyDefault = remember { Instant.ofEpochMilli(Setup.defaultWeeklyRestEnd(System.currentTimeMillis(), zone)).atZone(zone) }
    var weeklyDate by rememberSaveable { mutableStateOf(weeklyDefault.toLocalDate()) }
    var weeklyTime by rememberSaveable { mutableStateOf(weeklyDefault.toLocalTime()) }
    val weeklyLen = rememberDur("45")
    val owed = rememberDur()
    var dueDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }

    // A driver who tapped a mode in the last 24 hours sees it here, from when it started.
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        prefilled = true
        val latest = list.maxByOrNull { it.startUtc } ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (latest.startUtc in (now - DAY_MS)..now) {
            mode = runCatching { Mode.valueOf(latest.mode) }.getOrNull()
            prefilledMs = latest.startUtc
            val at = Instant.ofEpochMilli(latest.startUtc).atZone(zone)
            sinceDate = at.toLocalDate()
            sinceTime = at.toLocalTime().withSecond(0).withNano(0)
            if (sinceDate.isBefore(today)) startYesterday = true
        }
    }

    val sinceUtc = sinceTime?.let { sinceDate.atTime(it).atZone(zone).toInstant().toEpochMilli() }?.let { picked ->
        prefilledMs?.takeIf { it - Math.floorMod(it, 60_000L) == picked } ?: picked
    }

    fun dur(label: String, d: Dur): Long? =
        Durations.ofParts(d.h, d.m) ?: run { error = "$label: minutes go up to 59."; null }

    fun save() {
        if (saving) return
        error = null
        val sb = dur("Driving since your last break", sinceBreak) ?: return
        val dt = dur("Driving since your last daily rest", drivingToday) ?: return
        val ot = dur("Other work since your last daily rest", otherWork) ?: return
        val tw = dur("Driving this week", thisWeek) ?: return
        val lw = dur("Driving last week", lastWeek) ?: return
        val ww = dur("Work this week", workWeek) ?: return
        val wp = dur("Work earlier in this reference period", workPeriod) ?: return
        val wl = dur("How long it was", weeklyLen) ?: return
        val ow = dur("Compensation still owed", owed) ?: return
        val now = System.currentTimeMillis()
        val dayStart = (if (startYesterday) today.minusDays(1) else today).atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val form = SetupForm(
            mode = mode, sinceUtc = sinceUtc, started = started, dayStartUtc = if (started) dayStart else null, part15Taken = part15,
            drivingSinceBreak = sb, drivingToday = dt, thisWeekDriving = tw, lastWeekDriving = lw,
            thisWeekWork = ww, rtdPeriodWork = wp, tenHourDaysUsed = tenUsed, reducedDailyRestsUsed = reducedUsed,
            lastWeeklyRestEndUtc = weeklyDate.atTime(weeklyTime).atZone(zone).toInstant().toEpochMilli(), lastWeeklyRest = wl,
            compensationOwed = ow, compensationDueUtc = dueDate?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            otherWorkSinceRest = ot,
        )
        when (val r = Setup.build(form, now, zone)) {
            is SetupResult.Refused -> error = r.message
            is SetupResult.Ok -> {
                saving = true
                scope.launch {
                    try {
                        repo.saveSetup(r.seed, r.entryMode, r.entryStart, now)
                        onDone()
                        shell.launch { snackbar.showSnackbar("Week brought in") }
                    } catch (e: EditRefused) {
                        error = e.message
                        saving = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Text(
                "Read these off your tachograph or its printout, as it shows them now. Leave a box empty for zero. Dutyclock counts from these numbers plus what you tap. It never reads your tachograph or driver card.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            existing?.let {
                Text(
                    "This replaces the numbers you brought in on ${Fmt.shortDay(it.readAtUtc ?: it.seededAtUtc, zone)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onBackground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionTitle("Right now")
            Text("I am", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            ModeBar(mode, { mode = it })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PickerField("Since", dayFmt.format(sinceDate), { picker = "sinceDate" }, Modifier.weight(1f))
                PickerField("At", sinceTime?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "Pick a time", { picker = "sinceTime" }, Modifier.weight(1f))
            }
            Text(
                "When your tachograph started this mode. A break already under way counts from then.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )

            SectionTitle("Since your last daily rest")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(started, role = Role.Switch) { started = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("I have started work since my last daily rest", style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
                Switch(started, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary))
            }
            if (started) {
                Text("Work started on", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Today", !startYesterday, { startYesterday = false })
                    ChoiceChip("Yesterday", startYesterday, { startYesterday = true })
                }
                PickerField("Work started at", "%02d:%02d".format(startTime.hour, startTime.minute), { picker = "start" })
                DurationField("Driving since your last break", sinceBreak.h, sinceBreak.m, { sinceBreak.h = it }, { sinceBreak.m = it })
                DurationField("Driving since your last daily rest", drivingToday.h, drivingToday.m, { drivingToday.h = it }, { drivingToday.m = it })
                DurationField("Other work since your last daily rest", otherWork.h, otherWork.m, { otherWork.h = it }, { otherWork.m = it })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = part15, role = Role.Checkbox) { part15 = it }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = part15,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = cs.primary, checkmarkColor = cs.onPrimary, uncheckedColor = cs.onSurfaceVariant),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("First 15 minutes of a split break already taken", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(
                            "Taken before your current mode, after your last full break. Leave it off if you are not sure.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionTitle("This week and last")
            DurationField("Driving this week, since Monday", thisWeek.h, thisWeek.m, { thisWeek.h = it }, { thisWeek.m = it })
            DurationField("Driving last week", lastWeek.h, lastWeek.m, { lastWeek.h = it }, { lastWeek.m = it })
            Text("10-hour days used this week", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 0..2) ChoiceChip("$n", tenUsed == n, { tenUsed = n }) }
            Text("Reduced daily rests since your weekly rest", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 0..3) ChoiceChip("$n", reducedUsed == n, { reducedUsed = n }) }

            SectionTitle("Last weekly rest")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PickerField("Ended on", dayFmt.format(weeklyDate), { picker = "weeklyDate" }, Modifier.weight(1f))
                PickerField("At", "%02d:%02d".format(weeklyTime.hour, weeklyTime.minute), { picker = "weeklyTime" }, Modifier.weight(1f))
            }
            DurationField("How long it was", weeklyLen.h, weeklyLen.m, { weeklyLen.h = it }, { weeklyLen.m = it })
            DurationField("Compensation still owed", owed.h, owed.m, { owed.h = it }, { owed.m = it })
            if (owed.h.isNotBlank() || owed.m.isNotBlank()) {
                PickerField("Owed by the end of", dueDate?.let { dayFmt.format(it) } ?: "Third week after that rest", { picker = "due" })
            }

            SectionTitle("Working time")
            DurationField("Work this week: driving and other work", workWeek.h, workWeek.m, { workWeek.h = it }, { workWeek.m = it })
            DurationField("Work earlier in this reference period", workPeriod.h, workPeriod.m, { workPeriod.h = it }, { workPeriod.m = it }, last = true)

            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = cs.error, modifier = Modifier.padding(top = 12.dp))
            }
            // Saving never drops earlier entries from Today without a word.
            val before = sinceUtc?.let { s -> entries?.any { it.startUtc < s } } == true
            if (before && sinceTime != null) {
                Text(
                    "Your log has entries before ${"%02d:%02d".format(sinceTime!!.hour, sinceTime!!.minute)}. They stay in the Log, and Today counts these numbers instead of them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Bring my week in", ::save, Modifier.fillMaxWidth(), enabled = !saving)
            if (existing != null) {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch { repo.clearSeed(); onDone(); shell.launch { snackbar.showSnackbar("Catch-up cleared") } }
                    },
                ) {
                    Text("Clear the catch-up", color = cs.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picker) {
        "sinceDate" -> DatePickDialog(sinceDate, today, { picker = null }) { sinceDate = it; picker = null }
        "sinceTime" -> TimePickDialog("Since", sinceTime ?: LocalTime.now(zone).withSecond(0).withNano(0), { picker = null }) { sinceTime = it; picker = null }
        "start" -> TimePickDialog("Work started at", startTime, { picker = null }) { startTime = it; picker = null }
        "weeklyTime" -> TimePickDialog("Weekly rest ended at", weeklyTime, { picker = null }) { weeklyTime = it; picker = null }
        "weeklyDate" -> DatePickDialog(weeklyDate, today, { picker = null }) { weeklyDate = it; picker = null }
        "due" -> DatePickDialog(dueDate ?: today.plusWeeks(3), today.plusWeeks(5), { picker = null }) { dueDate = it; picker = null }
    }
}
