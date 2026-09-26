package com.mohdshayan.dutyclock.ui.plan

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.dutyclock.core.engine.Durations
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.plan.Blocker
import com.mohdshayan.dutyclock.core.plan.HomePlanner
import com.mohdshayan.dutyclock.core.plan.PlanResult
import com.mohdshayan.dutyclock.core.plan.StepKind
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.EmptyState
import com.mohdshayan.dutyclock.ui.components.PickerField
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SecondaryButton
import com.mohdshayan.dutyclock.ui.components.TimePickDialog
import com.mohdshayan.dutyclock.ui.theme.RadiusLg
import com.mohdshayan.dutyclock.ui.theme.RadiusSm
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlanUi {
    data object Idle : PlanUi
    data object Working : PlanUi
    data object Failed : PlanUi
    data class Done(val result: PlanResult, val zone: ZoneId, val start: Long) : PlanUi
}

class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.logRepository
    val driving = MutableStateFlow("")
    val leaveAt = MutableStateFlow<LocalTime?>(null)
    val inputError = MutableStateFlow<String?>(null)
    val ui = MutableStateFlow<PlanUi>(PlanUi.Idle)

    fun plan() {
        val need = Durations.parse(driving.value)
        if (need == null || need <= 0) {
            inputError.value = "Use hours and minutes, like 3.40."
            return
        }
        inputError.value = null
        ui.value = PlanUi.Working
        viewModelScope.launch {
            try {
                val (input, _) = repo.snapshot()
                val now = System.currentTimeMillis()
                val zone = input.zone
                val leave = leaveAt.value?.let { t ->
                    val at = LocalDate.now(zone).atTime(t).atZone(zone).toInstant().toEpochMilli()
                    if (at < now) at + 24 * 3_600_000L else at
                } ?: now
                val result = withContext(Dispatchers.Default) { HomePlanner.plan(input, now, leave, need) }
                ServiceLocator.appPrefs.bump(AppPrefs.Count.PLANNER_RUNS)
                ui.value = PlanUi.Done(result, zone, maxOf(now, leave))
            } catch (e: Exception) {
                ui.value = PlanUi.Failed
            }
        }
    }

    fun applyFix(blocker: Blocker) {
        viewModelScope.launch {
            val (input, _) = repo.snapshot()
            val ds = RuleEngine.evaluate(input, System.currentTimeMillis()).limits.dayStart ?: return@launch
            if (blocker == Blocker.DAILY_DRIVING) repo.setChoice(tenHourFor = ds) else repo.setChoice(reducedFor = ds)
            plan()
        }
    }
}

@Composable
fun PlanScreen(onLog: () -> Unit, onRecords: () -> Unit, vm: PlanViewModel = viewModel()) {
    val cs = MaterialTheme.colorScheme
    val driving by vm.driving.collectAsStateWithLifecycle()
    val leaveAt by vm.leaveAt.collectAsStateWithLifecycle()
    val inputError by vm.inputError.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pickTime by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val plan = { keyboard?.hide(); vm.plan() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Driving left to get home", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            OutlinedTextField(
                value = driving,
                onValueChange = { vm.driving.value = it.take(6) },
                placeholder = { Text("3.40") },
                singleLine = true,
                isError = inputError != null,
                supportingText = { Text(inputError ?: "Hours and minutes of driving, like 3.40, as your route planner shows it.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { plan() }),
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(RadiusSm),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.onBackground,
                    unfocusedBorderColor = cs.outline,
                    cursorColor = cs.onBackground,
                    errorSupportingTextColor = cs.error,
                    unfocusedSupportingTextColor = cs.onSurfaceVariant,
                    focusedSupportingTextColor = cs.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField("Leaving at", leaveAt?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "Now", { pickTime = true })
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Plan my drive", plan, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            when (val u = ui) {
                PlanUi.Idle -> EmptyState(
                    title = "Enter the driving left to get home",
                    body = "Dutyclock lays out the soonest order of driving and breaks your own log allows, or shows the limit that runs out first.",
                    modifier = Modifier.fillMaxWidth(),
                )
                PlanUi.Working -> Column {
                    com.mohdshayan.dutyclock.ui.components.SkeletonBlock(200.dp, 40.dp)
                    Spacer(Modifier.height(12.dp))
                    com.mohdshayan.dutyclock.ui.components.SkeletonRows(3)
                }
                PlanUi.Failed -> EmptyState(
                    title = "Your log could not be read",
                    body = "Restore a backup from Records.",
                    actionLabel = "Open Records",
                    onAction = onRecords,
                    modifier = Modifier.fillMaxWidth(),
                )
                is PlanUi.Done -> when (val r = u.result) {
                    PlanResult.NeedsLog -> EmptyState(
                        title = "Log today's driving first",
                        body = "The plan starts from your own log. Add today's entries, or bring your week in from Today.",
                        actionLabel = "Open Log",
                        onAction = onLog,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is PlanResult.Fits -> ResultCard(u.zone, u.start, r.steps, "Arrive ${Fmt.clock(r.arrival, u.zone)}", r.arrival, null, null, null)
                    is PlanResult.Blocked -> ResultCard(
                        u.zone, u.start, r.steps, "Not tonight", null, r.message(), r.fix,
                        if (r.fix != null) ({ vm.applyFix(r.blocker) }) else null,
                        fixLabel = if (r.blocker == Blocker.DAILY_DRIVING) "Use a 10-hour day" else "Take a reduced rest",
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickTime) {
        TimePickDialog("Leaving at", leaveAt ?: LocalTime.now().withSecond(0).withNano(0), { pickTime = false }) {
            vm.leaveAt.value = it; pickTime = false
        }
    }
}

@Composable
private fun ResultCard(
    zone: ZoneId,
    start: Long,
    steps: List<com.mohdshayan.dutyclock.core.plan.PlanStep>,
    headline: String,
    arrival: Long?,
    blocked: String?,
    fix: String?,
    onFix: (() -> Unit)?,
    fixLabel: String = "",
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant, RoundedCornerShape(RadiusLg))
            .padding(20.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.displayMedium, color = if (blocked != null) cs.error else cs.onBackground)
        if (blocked != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = cs.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(blocked, style = MaterialTheme.typography.bodyLarge, color = cs.onBackground)
            }
            if (fix != null) {
                Spacer(Modifier.height(4.dp))
                Text(fix, style = MaterialTheme.typography.bodyLarge, color = cs.onBackground, modifier = Modifier.padding(start = 28.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        var t = start
        if (steps.isEmpty()) {
            Text("No driving fits before the limit.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        for (s in steps) {
            StepRow(Fmt.clock(s.start, zone), if (s.kind == StepKind.DRIVE) "Drive" else "Break", Fmt.hm(s.length))
            RowDivider()
            t = s.end
        }
        if (arrival != null) StepRow(Fmt.clock(arrival, zone), "Arrive", "")
        else if (steps.isNotEmpty()) StepRow(Fmt.clock(t, zone), "Limit reached", "")
        Spacer(Modifier.height(12.dp))
        Text(
            "Advisory, from your own log. Traffic, loading and your tachograph are not in it.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        if (onFix != null) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(fixLabel, onFix)
        }
    }
}

@Composable
private fun StepRow(time: String, what: String, length: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.titleMedium, color = cs.onBackground, modifier = Modifier.width(64.dp))
        Text(what, style = MaterialTheme.typography.bodyLarge, color = cs.onBackground, modifier = Modifier.weight(1f))
        Text(length, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
    }
}
