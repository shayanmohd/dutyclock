package com.mohdshayan.dutyclock.core.model

import kotlinx.serialization.Serializable

/*
 * The rules engine's vocabulary. Everything under core/ is plain Kotlin with java.time and no
 * Android imports, so every rule runs and is tested on the JVM.
 */

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS
const val WEEK_MS = 7 * DAY_MS

/** The four tachograph modes. */
enum class Mode(val label: String) {
    DRIVE("Driving"),
    WORK("Other work"),
    AVAILABLE("Availability"),
    REST("Break or rest");

    val isDuty get() = this != REST

    /** Road Transport (Working Time) work: driving and other work. Availability and breaks are not work. */
    val isRtdWork get() = this == DRIVE || this == WORK
}

enum class RuleSet(val label: String, val scope: String) {
    EU("EU 561/2006", "Lorries over 3.5 t in the UK, the EU and between them"),
    AETR("AETR", "Journeys to, from or through AETR countries outside the EU"),
    VAN("Van 2.5 to 3.5 t", "International hire or reward van work, in scope from 1 July 2026"),
    GB_GOODS("GB domestic goods", "Goods vehicles out of EU scope, driven in Great Britain");

    /** EU, AETR and the van set share the 561/2006 limits and run the working time rules alongside. */
    val isEuFamily get() = this != GB_GOODS
}

/** One mode change: a start instant and a mode, lasting until the next entry. */
data class Entry(
    val id: Long,
    val startUtc: Long,
    val mode: Mode,
    val ruleSet: RuleSet,
    val edited: Boolean = false,
)

/** A stretch of one mode, [start, end). The last segment of a log ends at "now". */
data class Segment(
    val start: Long,
    val end: Long,
    val mode: Mode,
    val ruleSet: RuleSet,
) {
    val length get() = end - start
}

enum class AbsenceKind(val label: String) { ANNUAL_LEAVE("Annual leave"), SICK("Sick leave") }

/** A day of leave or sickness, credited to the working time average. [date] is ISO yyyy-MM-dd. */
@Serializable
data class Absence(
    val date: String,
    val kind: AbsenceKind,
    val creditedMin: Int = 480,
)

/**
 * The catch-up sheet: the state of a week already under way, as the driver reads it off the
 * tachograph. The engine starts from this at [seededAtUtc] and replays entries logged after it.
 */
@Serializable
data class Seed(
    val seededAtUtc: Long,
    val drivingSinceBreakMin: Int = 0,
    val dayStartUtc: Long? = null,
    val drivingTodayMin: Int = 0,
    val thisWeekDrivingMin: Int = 0,
    val lastWeekDrivingMin: Int = 0,
    val thisWeekWorkMin: Int = 0,
    val rtdPeriodWorkMin: Int = 0,
    val tenHourDaysUsed: Int = 0,
    val reducedDailyRestsUsed: Int = 0,
    val lastWeeklyRestEndUtc: Long? = null,
    val lastWeeklyRestMin: Int? = null,
    val compensationOwedMin: Int = 0,
    val compensationDueUtc: Long? = null,
)

/** Choices the driver makes for the current day. Nothing is assumed: the default is the stricter limit. */
data class DayChoices(
    /** The daily period (its start instant) for which the driver chose a 10-hour day. */
    val tenHourDayFor: Long? = null,
    /** The daily period (its start instant) for which the driver chose a reduced 9-hour rest. */
    val reducedRestFor: Long? = null,
)

/**
 * Turns entries into segments; the last one runs until [now]. Back-to-back entries in the same
 * mode are one stretch (a rest is one rest however many times it was tapped), so they merge.
 */
fun segmentsOf(entries: List<Entry>, now: Long): List<Segment> {
    val sorted = entries.sortedBy { it.startUtc }.filter { it.startUtc <= now }
    val raw = sorted.mapIndexed { i, e ->
        val end = if (i + 1 < sorted.size) sorted[i + 1].startUtc else now
        Segment(e.startUtc, end, e.mode, e.ruleSet)
    }.filter { it.length > 0 }
    val out = ArrayList<Segment>(raw.size)
    for (s in raw) {
        val last = out.lastOrNull()
        if (last != null && last.mode == s.mode && last.end == s.start && (s.mode == Mode.REST || last.ruleSet == s.ruleSet)) {
            out[out.lastIndex] = last.copy(end = s.end)
        } else {
            out += s
        }
    }
    return out
}
