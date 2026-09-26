package com.mohdshayan.dutyclock.ui.week

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.DailyRestKind
import com.mohdshayan.dutyclock.core.engine.DayStrip
import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.engine.text
import com.mohdshayan.dutyclock.core.model.AbsenceKind
import com.mohdshayan.dutyclock.core.model.HOUR_MS
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.DayStripBar
import com.mohdshayan.dutyclock.ui.components.EmptyState
import com.mohdshayan.dutyclock.ui.components.LogErrorState
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SectionTitle
import com.mohdshayan.dutyclock.ui.components.SkeletonRows
import com.mohdshayan.dutyclock.ui.today.ticker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WeekDay(
    val date: LocalDate,
    val strip: DayStrip,
    val tenHour: Boolean,
    val reducedRest: Boolean,
    val absence: AbsenceKind?,
    val future: Boolean,
)

data class TotalRow(val title: String, val value: String, val detail: String, val over: Boolean)

sealed interface WeekState {
    data object Loading : WeekState
    data object Error : WeekState
    data class Ready(
        val offset: Int,
        val label: String,
        val days: List<WeekDay>,
        val totals: List<TotalRow>,
        val findings: List<String>,
        val hasActivity: Boolean,
    ) : WeekState
}

class WeekViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.logRepository
    val offset = MutableStateFlow(0)
    private val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    val state: StateFlow<WeekState> = combine(repo.input, offset, ticker(30_000)) { (input, _), off, now ->
        val zone = input.zone
        val ev = RuleEngine.evaluate(input, now)
        val r = ev.replay
        val ws = FixedWeek.start(now) + off * WEEK_MS
        val monday = Instant.ofEpochMilli(ws).atZone(ZoneOffset.UTC).toLocalDate()
        val strips = DayTotals.strips(input.entries, monday, monday.plusDays(6), now, zone)
        val today = DayTotals.localDate(now, zone)
        val absences = input.absences.associate { it.date to it.kind }
        val days = strips.map { s ->
            WeekDay(
                date = s.date,
                strip = s,
                tenHour = r.days.any { it.drivingMs > 9 * HOUR_MS && DayTotals.localDate(it.start, zone) == s.date },
                reducedRest = r.dailyRests.any { it.kind == DailyRestKind.REDUCED && DayTotals.localDate(it.end, zone) == s.date },
                absence = absences[s.date.toString()],
                future = s.date.isAfter(today),
            )
        }
        val drive = r.weekDriving[ws] ?: 0L
        val prev = r.weekDriving[ws - WEEK_MS] ?: 0L
        val work = r.weekWork[ws] ?: 0L
        val totals = buildList {
            add(TotalRow("Driving this week", Fmt.hm(drive), "of 56:00", drive > 56 * HOUR_MS))
            add(TotalRow("Driving over two weeks", Fmt.hm(drive + prev), "of 90:00, the week before ${Fmt.hm(prev)}", drive + prev > 90 * HOUR_MS))
            if (off == 0) {
                ev.counters.firstOrNull { it.kind == CounterKind.WEEKLY_REST }?.let { add(TotalRow(it.title, it.value, it.detail, it.over)) }
                ev.counters.firstOrNull { it.kind == CounterKind.COMPENSATION }?.let { add(TotalRow(it.title, it.value, it.detail, it.over)) }
            }
            add(TotalRow("Working time this week", Fmt.hm(work), "of 60:00, driving and other work", work > 60 * HOUR_MS))
            if (off == 0) {
                ev.counters.firstOrNull { it.kind == CounterKind.RTD_AVERAGE }?.let { add(TotalRow(it.title, it.value, it.detail, it.over)) }
            }
        }
        val findings = r.findings.filter { it.at >= ws && it.at < ws + WEEK_MS }.sortedBy { it.at }
            .map { "${Fmt.dayClock(it.at, zone)}  ${it.text()}" }
        WeekState.Ready(
            offset = off,
            label = if (off == 0) "This week" else "${fmt.format(monday)} to ${fmt.format(monday.plusDays(6))}",
            days = days,
            totals = totals,
            findings = findings,
            hasActivity = days.any { it.strip.hasActivity || it.absence != null },
        ) as WeekState
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(WeekState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekState.Loading)

    fun move(by: Int) {
        offset.value = (offset.value + by).coerceIn(-8, 0)
    }

    fun mark(date: LocalDate, kind: AbsenceKind?) {
        viewModelScope.launch { repo.setAbsence(date.toString(), kind) }
    }
}

@Composable
fun WeekScreen(onRecords: () -> Unit, onToday: () -> Unit, vm: WeekViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        WeekState.Loading -> SkeletonRows(9, Modifier.padding(16.dp))
        WeekState.Error -> LogErrorState(Modifier.fillMaxSize(), onRecords)
        is WeekState.Ready -> WeekContent(s, vm, onToday)
    }
}

@Composable
private fun WeekContent(s: WeekState.Ready, vm: WeekViewModel, onToday: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.move(-1) }, enabled = s.offset > -8) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous week")
                }
                Text(s.label, style = MaterialTheme.typography.titleLarge, color = cs.onBackground, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.move(1) }, enabled = s.offset < 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next week")
                }
            }
            Text(
                "Fixed week, Monday 00:00 to Sunday 24:00 UTC.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (!s.hasActivity) {
                EmptyState(
                    title = "No driving this week yet",
                    body = "Tap the mode you are in on Today and the week fills in day by day.",
                    actionLabel = if (s.offset == 0) "Go to Today" else null,
                    onAction = if (s.offset == 0) onToday else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            for (d in s.days) {
                DayRow(d, vm)
                RowDivider()
            }
            SectionTitle("Totals")
            for (t in s.totals) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    if (t.over) {
                        Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = cs.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Over ${t.value}", style = MaterialTheme.typography.titleLarge, color = cs.error)
                    } else {
                        Text(t.value, style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                    }
                }
                RowDivider()
            }
            if (s.findings.isNotEmpty()) {
                SectionTitle("Limits passed")
                for (f in s.findings) {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(f, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val dayName = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)

@Composable
private fun DayRow(d: WeekDay, vm: WeekViewModel) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            dayName.format(d.date),
            style = MaterialTheme.typography.titleMedium,
            color = if (d.future) cs.onSurfaceVariant else cs.onBackground,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 64.dp).padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            DayStripBar(d.strip.pieces, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            val marks = listOfNotNull(
                d.absence?.label,
                if (d.tenHour) "10-hour day" else null,
                if (d.reducedRest) "Reduced rest" else null,
                if (d.strip.edited) "Edited" else null,
            )
            Text(
                if (marks.isEmpty()) " " else marks.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
        Text(
            Fmt.hm(d.strip.driveMs),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onBackground,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(min = 44.dp),
        )
        Box {
            IconButton(onClick = { menu = true }, enabled = !d.future) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Leave for ${dayName.format(d.date)}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mark annual leave") }, onClick = { vm.mark(d.date, AbsenceKind.ANNUAL_LEAVE); menu = false })
                DropdownMenuItem(text = { Text("Mark sick leave") }, onClick = { vm.mark(d.date, AbsenceKind.SICK); menu = false })
                if (d.absence != null) {
                    DropdownMenuItem(text = { Text("Clear leave") }, onClick = { vm.mark(d.date, null); menu = false })
                }
            }
        }
    }
}
