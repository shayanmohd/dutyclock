package com.mohdshayan.dutyclock.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Evaluation
import com.mohdshayan.dutyclock.core.engine.Piece
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.data.prefs.Settings
import com.mohdshayan.dutyclock.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TodayState {
    data object Loading : TodayState
    data object Error : TodayState
    data class Ready(
        val ev: Evaluation,
        val pieces: List<Piece>,
        val nowMin: Int,
        val currentFromMin: Int?,
        val settings: Settings,
        val showCatchUp: Boolean,
        val completedDays: Int,
    ) : TodayState
}

sealed interface TodayEvent {
    data class Started(val mode: Mode, val id: Long) : TodayEvent
    data class Message(val text: String) : TodayEvent
}

fun ticker(periodMs: Long = 1000L) = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs - System.currentTimeMillis() % periodMs)
    }
}

class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.logRepository
    private val prefs = ServiceLocator.appPrefs

    private val _events = MutableSharedFlow<TodayEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TodayEvent> = _events

    val state: StateFlow<TodayState> = combine(repo.input, ticker()) { (input, settings), now ->
        val ev = RuleEngine.evaluate(input, now)
        val zone = input.zone
        val today = DayTotals.localDate(now, zone)
        val strip = DayTotals.strips(input.entries, today, today, now, zone).first()
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMin = DayTotals.minuteOfDay(now, zone)
        val since = ev.modeSince
        val currentFromMin = since?.let { if (it <= midnight) 0 else DayTotals.minuteOfDay(it, zone) }
        val firstEntry = input.entries.minOfOrNull { it.startUtc }
        val showCatchUp = input.seed == null && !settings.catchupDismissed && (firstEntry == null || now - firstEntry < WEEK_MS)
        val completed = ev.replay.days.count { it.drivingMs > 0 }
        TodayState.Ready(ev, strip.pieces, nowMin, currentFromMin, settings, showCatchUp, completed) as TodayState
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(TodayState.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState.Loading)

    fun tap(mode: Mode) {
        viewModelScope.launch {
            val id = repo.tap(mode) ?: return@launch
            _events.emit(TodayEvent.Started(mode, id))
        }
    }

    fun undo(id: Long) {
        viewModelScope.launch { repo.undo(id) }
    }

    fun useTenHourDay(dayStart: Long?) {
        if (dayStart == null) return
        viewModelScope.launch {
            repo.setChoice(tenHourFor = dayStart)
            _events.emit(TodayEvent.Message("10-hour day in use"))
        }
    }

    fun takeReducedRest(dayStart: Long?) {
        if (dayStart == null) return
        viewModelScope.launch {
            repo.setChoice(reducedFor = dayStart)
            _events.emit(TodayEvent.Message("Reduced rest in use"))
        }
    }

    fun dismissCatchUp() {
        viewModelScope.launch { prefs.set(AppPrefs.Keys.CATCHUP_DISMISSED, true) }
    }

    fun markReviewPrompted() {
        viewModelScope.launch { prefs.set(AppPrefs.Keys.REVIEW_PROMPTED, true) }
    }
}
