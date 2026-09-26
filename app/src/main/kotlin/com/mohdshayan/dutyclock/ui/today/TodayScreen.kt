package com.mohdshayan.dutyclock.ui.today

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.play.core.review.ReviewManagerFactory
import com.mohdshayan.dutyclock.core.engine.Counter
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.Offer
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.ui.components.DayDisc
import com.mohdshayan.dutyclock.ui.components.DiscCentre
import com.mohdshayan.dutyclock.ui.components.LimitRow
import com.mohdshayan.dutyclock.ui.components.LimitSheet
import com.mohdshayan.dutyclock.ui.components.LogErrorState
import com.mohdshayan.dutyclock.ui.components.ModeBar
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SecondaryButton
import com.mohdshayan.dutyclock.ui.components.SkeletonBlock
import com.mohdshayan.dutyclock.ui.components.SkeletonRows
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import com.mohdshayan.dutyclock.ui.theme.RadiusLg
import com.mohdshayan.dutyclock.ui.theme.RadiusMd
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun startedText(mode: Mode) = when (mode) {
    Mode.DRIVE -> "Driving started"
    Mode.WORK -> "Other work started"
    Mode.AVAILABLE -> "Availability started"
    Mode.REST -> "Break or rest started"
}

private fun centreLabel(c: Counter): String = when (c.kind) {
    CounterKind.BREAK -> "Driving left before a break"
    CounterKind.DAILY_DRIVING -> "Daily driving left"
    CounterKind.DAILY_REST -> if (c.met) "Until your daily rest is complete" else "Until your daily rest must start"
    CounterKind.WEEKLY_REST -> if (c.met) "Until 45 hours of weekly rest" else "Until your weekly rest must start"
    CounterKind.WEEK_56 -> "Driving left this week"
    CounterKind.FORTNIGHT_90 -> "Driving left this fortnight"
    CounterKind.COMPENSATION -> "Until compensation is due"
    CounterKind.RTD_BREAK -> "Work left before a working time break"
    CounterKind.RTD_WEEK_60 -> "Work left this week"
    CounterKind.RTD_AVERAGE -> "Work left in this period"
    CounterKind.GB_DRIVING -> "Driving left today"
    CounterKind.GB_DUTY -> "Duty left today"
}

private fun split(ms: Long, seconds: Boolean): Pair<String, String> {
    val hm = Fmt.hm(ms)
    if (!seconds) return hm to ""
    val s = (kotlin.math.abs(ms) / 1000) % 60
    return hm to ":" + s.toString().padStart(2, '0')
}

@Composable
fun TodayScreen(
    onCatchUp: () -> Unit,
    onRecords: () -> Unit,
    viewModel: TodayViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is TodayEvent.Started -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val job = launch { delay(5_000); snackbar.currentSnackbarData?.dismiss() }
                    val r = snackbar.showSnackbar(startedText(e.mode), actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
                    job.cancel()
                    if (r == SnackbarResult.ActionPerformed) viewModel.undo(e.id)
                }
                is TodayEvent.Message -> scope.launch { snackbar.showSnackbar(e.text) }
            }
        }
    }

    when (val s = state) {
        TodayState.Loading -> TodaySkeleton()
        TodayState.Error -> LogErrorState(Modifier.fillMaxSize(), onRecords)
        is TodayState.Ready -> {
            // In-app review: after the third completed working day, never on first launch, never while driving.
            LaunchedEffect(s.completedDays >= 3 && !s.settings.reviewPrompted && s.ev.mode != Mode.DRIVE) {
                if (s.completedDays >= 3 && !s.settings.reviewPrompted && s.ev.mode != Mode.DRIVE) {
                    val activity = context as? Activity ?: return@LaunchedEffect
                    viewModel.markReviewPrompted()
                    val manager = ReviewManagerFactory.create(activity)
                    manager.requestReviewFlow().addOnCompleteListener { t ->
                        if (t.isSuccessful) manager.launchReviewFlow(activity, t.result)
                    }
                }
            }
            TodayContent(s, viewModel, onCatchUp)
        }
    }
}

@Composable
private fun TodayContent(s: TodayState.Ready, vm: TodayViewModel, onCatchUp: () -> Unit) {
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 600
    // On a phone the disc gives up some size so at least two limits show above the mode bar.
    val discSize = when {
        wide -> 360.dp
        config.screenHeightDp < 800 -> 248.dp
        else -> 280.dp
    }
    var sheet by rememberSaveable { mutableStateOf<CounterKind?>(null) }
    val ev = s.ev

    val centre: DiscCentre? = when {
        ev.empty -> null
        ev.mode == Mode.REST && ev.breakCompleteAt != null -> {
            val (b, sm) = split(ev.breakCompleteAt!! - ev.now, true)
            DiscCentre("break-complete", b, sm, if (ev.breakCompleteIsSplit) "Until the split break is complete" else "Until the break counts", false)
        }
        else -> ev.counters.firstOrNull()?.let { c ->
            val (b, sm) = split(if (c.over) -c.remainingMs else c.remainingMs, c.running || c.clock == com.mohdshayan.dutyclock.core.engine.Clock.WALL)
            DiscCentre(c.kind.name + c.met, b, sm, centreLabel(c), c.over)
        }
    }

    val disc: @Composable (Modifier, Dp) -> Unit = { m, size ->
        Box(m, contentAlignment = Alignment.Center) {
            DayDisc(
                pieces = s.pieces,
                currentMode = ev.mode,
                currentFromMin = s.currentFromMin,
                nowMin = s.nowMin,
                centre = centre,
                diameter = size,
            )
            if (ev.empty) {
                Text(
                    "Tap the mode you are in now",
                    style = MaterialTheme.typography.titleMedium,
                    color = com.mohdshayan.dutyclock.ui.theme.LocalDiscColors.current.ink,
                    modifier = Modifier.width(150.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    val limits: @Composable () -> Unit = {
        Column {
            if (s.showCatchUp) {
                CatchUpCard(onCatchUp, vm::dismissCatchUp)
                Spacer(Modifier.height(8.dp))
            }
            for (c in ev.counters) {
                LimitRow(c, onClick = { sheet = c.kind })
                RowDivider()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // On a wide screen the prompt sits above the limits, so a phone on its side keeps its height for the disc.
        if (!wide) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                NotificationPrompt()
            }
        }
        if (wide) {
            // Wide but possibly short (a phone on its side): the disc takes the height there is,
            // never less than 200dp, and scrolls on its own if even that does not fit.
            BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                val fit = (maxHeight - 16.dp).coerceIn(200.dp, discSize)
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                        disc(Modifier.padding(top = 8.dp, end = 24.dp), fit)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        NotificationPrompt()
                        limits()
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                disc(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), discSize)
                limits()
                Spacer(Modifier.height(12.dp))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (ev.mode == Mode.DRIVE) {
                Text(
                    "Change modes only when stopped.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            ModeBar(ev.mode, vm::tap)
        }
    }

    val open = sheet?.let { k -> ev.counters.firstOrNull { it.kind == k } }
    if (open != null) {
        LimitSheet(
            counter = open,
            onDismiss = { sheet = null },
            onOffer = when (open.offer) {
                Offer.TEN_HOUR_DAY -> ({ vm.useTenHourDay(ev.limits.dayStart); sheet = null })
                Offer.REDUCED_REST -> ({ vm.takeReducedRest(ev.limits.dayStart); sheet = null })
                null -> null
            },
        )
    }
}

/** The rule set, shown as Today's title: tap it to change the rules for what you tap next. */
@Composable
fun RuleSetTitle(ruleSet: RuleSet, onRuleSet: (RuleSet) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Change rule set") { menu = true }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ruleSet.label, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Change rule set", tint = MaterialTheme.colorScheme.onBackground)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (rs in RuleSet.entries) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(rs.label, style = MaterialTheme.typography.titleMedium)
                            Text(rs.scope, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onRuleSet(rs); menu = false },
                )
            }
        }
    }
}

@Composable
private fun NotificationPrompt() {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; asked = true
    }
    LifecycleResumeEffect(Unit) {
        granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    if (!granted) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(RadiusMd))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Allow notifications so break alerts can reach you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (!asked && Build.VERSION.SDK_INT >= 33) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }) { Text("Allow", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun CatchUpCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(RadiusLg))
            .padding(16.dp),
    ) {
        Text("Bring your week in", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(
            "Started mid-week? Enter what your tachograph shows so far and every limit starts from the right place. It takes a minute.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Bring your week in", onOpen)
            SecondaryButton("Not now", onDismiss)
        }
    }
}

@Composable
private fun TodaySkeleton() {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonBlock(140.dp, 28.dp, Modifier.align(Alignment.Start))
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(280.dp, 280.dp, round = true)
        Spacer(Modifier.height(16.dp))
        SkeletonRows(4)
    }
}
