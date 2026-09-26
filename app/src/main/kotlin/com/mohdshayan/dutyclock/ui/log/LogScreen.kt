package com.mohdshayan.dutyclock.ui.log

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.data.repo.EditRefused
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.DatePickDialog
import com.mohdshayan.dutyclock.ui.components.DayStripBar
import com.mohdshayan.dutyclock.ui.components.EmptyState
import com.mohdshayan.dutyclock.ui.components.LogErrorState
import com.mohdshayan.dutyclock.ui.components.ModeBar
import com.mohdshayan.dutyclock.ui.components.PickerField
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SecondaryButton
import com.mohdshayan.dutyclock.ui.components.SkeletonRows
import com.mohdshayan.dutyclock.ui.components.TachoIcons
import com.mohdshayan.dutyclock.ui.components.TimePickDialog
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import com.mohdshayan.dutyclock.ui.theme.SheetShape
import com.mohdshayan.dutyclock.ui.today.ticker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogRow(
    val id: Long,
    val start: Long,
    val end: Long,
    val mode: Mode,
    val ruleSet: RuleSet,
    val edited: Boolean,
    val carried: Boolean,
)

sealed interface LogState {
    data object Loading : LogState
    data object Error : LogState
    data class Ready(val date: LocalDate, val today: LocalDate, val rows: List<LogRow>, val strip: com.mohdshayan.dutyclock.core.engine.DayStrip, val zone: ZoneId) : LogState
}

class LogViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.logRepository
    val date = MutableStateFlow(LocalDate.now())
    val editorError = MutableStateFlow<String?>(null)
    val done = MutableSharedFlow<String>(extraBufferCapacity = 2)

    val state: StateFlow<LogState> = combine(repo.input, date, ticker(30_000)) { (input, _), d, now ->
        val zone = input.zone
        val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sorted = input.entries.sortedBy { it.startUtc }
        val rows = sorted.mapIndexedNotNull { i, e ->
            val end = sorted.getOrNull(i + 1)?.startUtc ?: now
            val inDay = e.startUtc in from until to
            val carried = e.startUtc < from && end > from
            if (inDay || carried) LogRow(e.id, e.startUtc, end, e.mode, e.ruleSet, e.edited, carried) else null
        }
        val strip = DayTotals.strips(input.entries, d, d, now, zone).first()
        LogState.Ready(d, DayTotals.localDate(now, zone), rows, strip, zone) as LogState
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(LogState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogState.Loading)

    fun move(days: Long) {
        val next = date.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) date.value = next
    }

    private fun run(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                editorError.value = null
                done.emit(success)
            } catch (e: EditRefused) {
                editorError.value = e.message
            }
        }
    }

    fun save(id: Long?, start: Long, mode: Mode) =
        if (id == null) run("Entry added") { repo.add(start, mode) } else run("Entry saved") { repo.saveEdit(id, start, mode) }

    fun split(id: Long, at: Long, mode: Mode) = run("Entry split") { repo.split(id, at, mode) }
    fun delete(id: Long) = run("Entry deleted") { repo.delete(id) }
    fun clearError() { editorError.value = null }
}

private val dayTitle = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** What the editor is working on: an existing entry, or a new one on the chosen day. */
private data class Editing(val id: Long?, val start: Long, val end: Long?, val mode: Mode)

private val EditingSaver = androidx.compose.runtime.saveable.Saver<Editing?, LongArray>(
    save = { e -> e?.let { longArrayOf(it.id ?: -1L, it.start, it.end ?: -1L, it.mode.ordinal.toLong()) } },
    restore = { a -> Editing(a[0].takeIf { it >= 0 }, a[1], a[2].takeIf { it >= 0 }, Mode.entries[a[3].toInt()]) },
)

@Composable
fun LogScreen(onRecords: () -> Unit, vm: LogViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    // Saved across rotation and process death, so an edit in progress is not lost.
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf<Editing?>(null) }

    LaunchedEffect(Unit) {
        vm.done.collect { msg ->
            editing = null
            snackbar.showSnackbar(msg)
        }
    }

    when (val s = state) {
        LogState.Loading -> SkeletonRows(6, Modifier.padding(16.dp))
        LogState.Error -> LogErrorState(Modifier.fillMaxSize(), onRecords)
        is LogState.Ready -> {
            val cs = MaterialTheme.colorScheme
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Column(Modifier.widthIn(max = 720.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.move(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Day before") }
                        Text(
                            if (s.date == s.today) "Today, ${shortDate.format(s.date)}" else dayTitle.format(s.date),
                            style = MaterialTheme.typography.titleLarge,
                            color = cs.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.move(1) }, enabled = s.date.isBefore(s.today)) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Day after")
                        }
                    }
                    DayStripBar(s.strip.pieces, Modifier.fillMaxWidth().padding(vertical = 8.dp), height = 24.dp)
                    Text(
                        "Driving ${Fmt.hm(s.strip.driveMs)}, other work ${Fmt.hm(s.strip.workMs)}, availability ${Fmt.hm(s.strip.availableMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.rows.isEmpty()) {
                        EmptyState(
                            title = "Nothing logged on ${dayTitle.format(s.date)}",
                            body = "Add the modes you were in, starting with the first one of the day.",
                            actionLabel = "Add entry",
                            onAction = {
                                vm.clearError()
                                editing = Editing(null, s.date.atTime(6, 0).atZone(s.zone).toInstant().toEpochMilli(), null, Mode.WORK)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        RowDivider()
                        for (r in s.rows) {
                            EntryRow(r, s.zone) {
                                vm.clearError()
                                editing = Editing(r.id, r.start, r.end, r.mode)
                            }
                            RowDivider()
                        }
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton("Add entry", {
                            vm.clearError()
                            val base = if (s.date == s.today) System.currentTimeMillis() - 15 * 60_000L else s.date.atTime(12, 0).atZone(s.zone).toInstant().toEpochMilli()
                            editing = Editing(null, base - base % 60_000L, null, Mode.REST)
                        })
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            editing?.let { e -> EditorSheet(e, s.zone, s.today, vm) { editing = null } }
        }
    }
}

@Composable
private fun EntryRow(r: LogRow, zone: ZoneId, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClickLabel = "Edit entry", onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hhmm.format(Instant.ofEpochMilli(r.start).atZone(zone)),
            style = MaterialTheme.typography.titleMedium,
            color = if (r.carried) cs.onSurfaceVariant else cs.onBackground,
            modifier = Modifier.width(56.dp),
        )
        Icon(TachoIcons.of(r.mode), contentDescription = null, tint = cs.onBackground, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.mode.label, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            val notes = listOfNotNull(
                if (r.carried) "From ${Fmt.shortDay(r.start, zone)}" else null,
                if (r.edited) "Edited" else null,
                r.ruleSet.label,
            )
            Text(notes.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Text(Fmt.hm(r.end - r.start), style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(e: Editing, zone: ZoneId, today: LocalDate, vm: LogViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val error by vm.editorError.collectAsStateWithLifecycle()
    val start0 = Instant.ofEpochMilli(e.start).atZone(zone)
    var mode by rememberSaveable { mutableStateOf(e.mode) }
    var date by rememberSaveable { mutableStateOf(start0.toLocalDate()) }
    var time by rememberSaveable { mutableStateOf(start0.toLocalTime().withSecond(0).withNano(0)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val startMs = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    ModalBottomSheet(onDismissRequest = onDismiss, shape = SheetShape, containerColor = cs.surfaceVariant, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(if (e.id == null) "Add entry" else "Edit entry", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground)
            Spacer(Modifier.height(16.dp))
            ModeBar(mode, { mode = it })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PickerField("Date", shortDate.format(date), { pickDate = true }, Modifier.weight(1f))
                PickerField("Start", hhmm.format(time), { pickTime = true }, Modifier.weight(1f))
            }
            if (e.id != null && e.end != null) {
                Text(
                    "Runs until ${Fmt.when_(e.end, System.currentTimeMillis(), zone)}. To split it, pick a time inside it and a mode, then Split here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = cs.error)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (e.id == null) "Add entry" else "Save entry", { vm.save(e.id, startMs, mode) }, Modifier.fillMaxWidth())
            if (e.id != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Split here", { vm.split(e.id, startMs, mode) }, Modifier.weight(1f))
                    TextButton(onClick = { vm.delete(e.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Delete", color = cs.error, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    if (pickDate) DatePickDialog(date, today, { pickDate = false }) { date = it; pickDate = false }
    if (pickTime) TimePickDialog("Start time", time, { pickTime = false }) { time = it; pickTime = false }
}
