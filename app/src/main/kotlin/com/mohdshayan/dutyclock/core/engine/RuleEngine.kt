package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.DAY_MS
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.HOUR_MS
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.model.segmentsOf
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class CounterKind {
    BREAK, DAILY_DRIVING, DAILY_REST, WEEKLY_REST, WEEK_56, FORTNIGHT_90, COMPENSATION,
    RTD_BREAK, RTD_WEEK_60, RTD_AVERAGE, GB_DRIVING, GB_DUTY,
}

/** What makes a counter move: driving time, working time, any duty, or the wall clock. */
enum class Clock { DRIVING, WORK, DUTY, WALL }

enum class Offer { TEN_HOUR_DAY, REDUCED_REST }

data class Counter(
    val kind: CounterKind,
    val title: String,
    val remainingMs: Long,
    val clock: Clock,
    val running: Boolean,
    /** The instant the limit is reached if the current mode continues; null while paused or met. */
    val limitAt: Long?,
    val over: Boolean,
    val value: String,
    val detail: String,
    val working: List<String>,
    val source: String,
    val offer: Offer? = null,
    val offerLabel: String? = null,
    /** A rest under way that is meeting this limit: the countdown is to its completion. */
    val met: Boolean = false,
)

/** Every number the counters, the planner and the alerts are built from. Durations in ms. */
data class Limits(
    val breakLeft: Long,
    val breakNeeded: Long,
    val dayStart: Long?,
    val drivingToday: Long,
    val dayLimit: Long,
    val tenHourToday: Boolean,
    val tenHourDaysLeft: Int,
    val weekDriving: Long,
    val lastWeekDriving: Long,
    val dailyRestLatestStart: Long?,
    val dailyRestRequired: Long,
    val reducedRestsLeft: Int,
    val reducedToday: Boolean,
    val weeklyRestLatestStart: Long?,
    val weeklyRestRequired: Long,
    val rtdBreakLeft: Long,
    val rtdBreakNeeded: Long,
    val rtdWeekWork: Long,
    val rtdPeriod: RtdPeriod,
    val rtdPeriodTotal: Long,
    val rtdLeaveCredit: Long,
    val gbDayEnd: Long?,
    val gbDriving: Long,
    val gbDuty: Long,
) {
    val dailyDrivingLeft get() = dayLimit - drivingToday
    val weekLeft get() = 56 * HOUR_MS - weekDriving
    val fortnightLeft get() = 90 * HOUR_MS - weekDriving - lastWeekDriving
    val rtdWeekLeft get() = 60 * HOUR_MS - rtdWeekWork
    val rtdPeriodLeft get() = rtdPeriod.limitMin * MINUTE_MS - rtdPeriodTotal
    val gbDrivingLeft get() = 10 * HOUR_MS - gbDriving
    val gbDutyLeft get() = 11 * HOUR_MS - gbDuty
}

data class EngineInput(
    val entries: List<Entry>,
    val seed: Seed?,
    val absences: List<Absence>,
    val choices: DayChoices,
    val ruleSet: RuleSet,
    val zone: ZoneId,
)

data class Evaluation(
    val now: Long,
    val mode: Mode?,
    val modeSince: Long?,
    val ruleSet: RuleSet,
    val limits: Limits,
    val counters: List<Counter>,
    val replay: Replay,
    val empty: Boolean,
    /** When resting, the instant the current break becomes a qualifying one; null otherwise. */
    val breakCompleteAt: Long?,
    val breakCompleteIsSplit: Boolean,
)

object RuleEngine {
    /** History older than this adds nothing: the longest window is an 18-week RTD period. */
    const val WINDOW_MS = 20 * WEEK_MS

    fun replay(input: EngineInput, now: Long): Replay {
        val clipStart = maxOf(input.seed?.seededAtUtc ?: Long.MIN_VALUE, now - WINDOW_MS)
        val segs = segmentsOf(input.entries, now)
            .filter { it.end > clipStart }
            .map { if (it.start < clipStart) it.copy(start = clipStart) else it }
        val ongoing = segs.isNotEmpty() && segs.last().end == now
        return Replay.run(input.seed, segs, ongoing)
    }

    /** Leave credit for the working time average: 8 hours a day, at most 48 in any fixed week. */
    fun leaveCredit(absences: List<Absence>, period: RtdPeriod): Long {
        val start = period.startDate
        val last = period.lastDate
        return absences.mapNotNull { a -> runCatching { LocalDate.parse(a.date) }.getOrNull()?.let { it to a } }
            .filter { (d, _) -> !d.isBefore(start) && !d.isAfter(last) }
            .groupBy { (d, _) -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .values.sumOf { week -> minOf(48 * HOUR_MS, week.sumOf { it.second.creditedMin * MINUTE_MS }) }
    }

    fun limits(input: EngineInput, r: Replay, now: Long): Limits {
        val wk = FixedWeek.start(now)
        val ds = r.dayStart
        val tenUsed = r.tenHourDaysIn(FixedWeek.start(ds ?: now))
        val tenChosen = ds != null && input.choices.tenHourDayFor == ds
        val tenToday = (tenChosen || r.drivingToday > 9 * HOUR_MS) && tenUsed < 2
        val reducedLeft = (3 - r.reducedCount).coerceAtLeast(0)
        val reducedToday = ds != null && input.choices.reducedRestFor == ds && reducedLeft > 0 && !r.splitFirst
        val req = if (r.splitFirst || reducedToday) 9 * HOUR_MS else 11 * HOUR_MS

        val rtd = mutableListOf(6 * HOUR_MS - r.workSinceRtdBreak to 15 * MINUTE_MS)
        if (r.shiftBreaks < 30 * MINUTE_MS) rtd += (6 * HOUR_MS - r.shiftWork) to (30 * MINUTE_MS - r.shiftBreaks)
        if (r.shiftBreaks < 45 * MINUTE_MS) rtd += (9 * HOUR_MS - r.shiftWork) to (45 * MINUTE_MS - r.shiftBreaks)
        // When two steps fall due together (no break yet: 6 hours in a row is also 6 in the shift),
        // the break must satisfy both, so the longer one is needed.
        val rtdLeft = rtd.minOf { it.first }
        val rtdNeed = rtd.filter { it.first == rtdLeft }.maxOf { it.second }

        val period = RtdPeriod.containing(now)
        val seedOffset = r.rtdPeriodSeed?.takeIf { it.first == period.startUtc }?.second ?: 0L
        val periodWork = r.weekWork.filterKeys { it >= period.startUtc && it < period.endUtc }.values.sum()
        val credit = leaveCredit(input.absences, period)

        val gbStart = r.gbDayStart
        val gbActive = gbStart != null && now < gbStart + DAY_MS

        return Limits(
            breakLeft = 270 * MINUTE_MS - r.drivingSinceBreak,
            breakNeeded = if (r.part15) 30 * MINUTE_MS else 45 * MINUTE_MS,
            dayStart = ds,
            drivingToday = r.drivingToday,
            dayLimit = if (tenToday) 10 * HOUR_MS else 9 * HOUR_MS,
            tenHourToday = tenToday,
            tenHourDaysLeft = (2 - tenUsed - (if (tenToday) 1 else 0)).coerceAtLeast(0),
            weekDriving = r.weekDriving[wk] ?: 0L,
            lastWeekDriving = r.weekDriving[wk - WEEK_MS] ?: 0L,
            dailyRestLatestStart = ds?.let { it + DAY_MS - req },
            dailyRestRequired = req,
            reducedRestsLeft = reducedLeft,
            reducedToday = reducedToday,
            weeklyRestLatestStart = r.lastWeeklyRestEnd?.let { it + 6 * DAY_MS },
            weeklyRestRequired = if (r.lastWeeklyReduced) 45 * HOUR_MS else 24 * HOUR_MS,
            rtdBreakLeft = rtdLeft,
            rtdBreakNeeded = rtdNeed.coerceAtLeast(15 * MINUTE_MS),
            rtdWeekWork = r.weekWork[wk] ?: 0L,
            rtdPeriod = period,
            rtdPeriodTotal = seedOffset + periodWork + credit,
            rtdLeaveCredit = credit,
            gbDayEnd = if (gbActive) gbStart!! + DAY_MS else null,
            gbDriving = if (gbActive) r.gbDriving else 0L,
            gbDuty = if (gbActive) r.gbDuty else 0L,
        )
    }

    fun evaluate(input: EngineInput, now: Long): Evaluation {
        val r = replay(input, now)
        val l = limits(input, r, now)
        val last = input.entries.filter { it.startUtc <= now }.maxByOrNull { it.startUtc }
        val mode = last?.mode
        val zone = input.zone
        val ongoing = r.ongoingRest
        val restNow = ongoing?.length ?: 0L
        val restStart = ongoing?.start

        val breakCompleteAt = if (ongoing != null) {
            val need = if (r.part15BeforeOngoing) 30 * MINUTE_MS else 45 * MINUTE_MS
            if (restNow < need) ongoing.start + need else null
        } else null

        val counters = mutableListOf<Counter>()
        fun add(
            kind: CounterKind, title: String, remaining: Long, clock: Clock, value: String, detail: String,
            working: List<String>, source: String, met: Boolean = false, offer: Offer? = null, offerLabel: String? = null,
        ) {
            val running = when (clock) {
                Clock.DRIVING -> mode == Mode.DRIVE
                Clock.WORK -> mode?.isRtdWork == true
                Clock.DUTY -> mode?.isDuty == true
                Clock.WALL -> !met
            }
            val over = !met && remaining < 0
            val limitAt = if (running && remaining > 0) now + remaining else if (met && remaining > 0) now + remaining else null
            counters += Counter(kind, title, remaining, clock, running, limitAt, over, value, detail, working, source, offer, offerLabel, met)
        }

        val empty = input.entries.isEmpty() && input.seed == null
        if (!empty) {
            if (input.ruleSet.isEuFamily) euCounters(l, r, now, zone, mode, restNow, restStart, ::add)
            else gbCounters(l, now, zone, ::add)
            if (input.ruleSet.isEuFamily) rtdCounters(l, ::add)
            compensationCounters(r, now, zone, ::add)
        }

        counters.sortWith(compareByDescending<Counter> { it.over }.thenBy { it.remainingMs })
        return Evaluation(
            now = now, mode = mode, modeSince = last?.startUtc, ruleSet = input.ruleSet, limits = l,
            counters = counters, replay = r, empty = empty,
            breakCompleteAt = breakCompleteAt, breakCompleteIsSplit = r.part15BeforeOngoing,
        )
    }

    private fun euCounters(
        l: Limits, r: Replay, now: Long, zone: ZoneId, mode: Mode?, restNow: Long, restStart: Long?,
        add: (CounterKind, String, Long, Clock, String, String, List<String>, String, Boolean, Offer?, String?) -> Unit,
    ) {
        // Break from driving.
        val breakDetail = when {
            mode == Mode.REST && restNow in 1 until 15 * MINUTE_MS && !r.part15BeforeOngoing ->
                "Break under way. It counts from 15 minutes."
            mode == Mode.REST && restStart != null && !r.part15BeforeOngoing && restNow in 15 * MINUTE_MS until 45 * MINUTE_MS ->
                "${restNow / MINUTE_MS} of 45 taken. Rest ${(45 * MINUTE_MS - restNow + MINUTE_MS - 1) / MINUTE_MS} more, or take 30 later before 4:30 of driving."
            mode == Mode.REST && r.part15BeforeOngoing && restNow < 30 * MINUTE_MS ->
                "15 of 45 taken. Second part: ${restNow / MINUTE_MS} of 30."
            r.part15 -> "15 of 45 taken. Take 30 more before 4:30 of driving."
            r.lastBreakCompleteAt != null && now - r.lastBreakCompleteAt!! in 0..(20 * MINUTE_MS) ->
                if (r.lastBreakWasSplit) "Split break complete." else "Break complete."
            else -> "Take 45 minutes, or 15 then 30, before 4:30 of driving."
        }
        add(
            CounterKind.BREAK, "Break due", l.breakLeft, Clock.DRIVING, Fmt.hm(l.breakLeft), breakDetail,
            listOf(
                "Driving since your last full break: ${Fmt.hm(r.drivingSinceBreak)}",
                "Limit: 4:30 of driving, then a 45-minute break",
                "A split counts only as 15 minutes or more, then 30 minutes or more, in that order",
                "Breaks under 15 minutes count for nothing",
                if (r.part15) "First part of a split taken: yes" else "First part of a split taken: no",
            ),
            "Regulation 561/2006 Article 7; GOV.UK drivers' hours guide, section 1.4", false, null, null,
        )

        // Daily driving.
        val tenLabel = when {
            l.tenHourToday -> null
            l.tenHourDaysLeft > 0 -> "Use a 10-hour day: ${l.tenHourDaysLeft} of 2 left"
            else -> null
        }
        add(
            CounterKind.DAILY_DRIVING, "Daily driving", l.dailyDrivingLeft, Clock.DRIVING, Fmt.hm(l.dailyDrivingLeft),
            "${Fmt.hm(l.drivingToday)} of ${Fmt.hm(l.dayLimit)} driven since your last daily rest." +
                if (l.tenHourToday) " 10-hour day." else if (l.tenHourDaysLeft == 0) " No 10-hour days left this week." else "",
            listOf(
                "Driving since the end of your last daily or weekly rest: ${Fmt.hm(l.drivingToday)}",
                "Limit today: ${Fmt.hm(l.dayLimit)}",
                "10-hour days left this fixed week: ${l.tenHourDaysLeft} of 2",
                "A 10-hour day is never assumed. Choose it here when you need it.",
            ),
            "Regulation 561/2006 Article 6(1); GOV.UK drivers' hours guide, section 1.4", false,
            if (tenLabel != null) Offer.TEN_HOUR_DAY else null, tenLabel,
        )

        // Daily rest.
        val latest = l.dailyRestLatestStart
        if (restStart != null && restNow >= 9 * HOUR_MS && restNow < 24 * HOUR_MS) {
            val target = restStart + 11 * HOUR_MS
            add(
                CounterKind.DAILY_REST, "Daily rest", target - now, Clock.WALL, Fmt.hm(restNow),
                if (restNow >= 11 * HOUR_MS) "Regular daily rest taken: ${Fmt.hm(restNow)}."
                else "Reduced rest reached. Regular at ${Fmt.when_(target, now, zone)}.",
                listOf("Resting since ${Fmt.when_(restStart, now, zone)}", "Regular daily rest: 11 hours", "Reduced daily rests left: ${l.reducedRestsLeft} of 3"),
                "Regulation 561/2006 Articles 4(g) and 8; GOV.UK drivers' hours guide, section 1.5", true, null, null,
            )
        } else if (latest != null) {
            val met = restStart != null && restStart <= latest && mode == Mode.REST
            val remaining = if (met) restStart!! + l.dailyRestRequired - now else latest - now
            val reducedLabel = if (!l.reducedToday && !r.splitFirst && l.reducedRestsLeft > 0) "Take a reduced rest: ${l.reducedRestsLeft} of 3 left" else null
            add(
                CounterKind.DAILY_REST, "Daily rest starts by", remaining, Clock.WALL, Fmt.when_(latest, now, zone),
                when {
                    met -> "Rest under way. ${Fmt.hours(l.dailyRestRequired)} complete at ${Fmt.when_(restStart!! + l.dailyRestRequired, now, zone)}."
                    r.splitFirst -> "Split rest: 3 hours taken, 9 more to start by ${Fmt.when_(latest, now, zone)}."
                    l.reducedToday -> "Reduced 9-hour rest chosen. ${l.reducedRestsLeft - 1} left after tonight."
                    else -> "11 hours within 24 of ${Fmt.when_(l.dayStart!!, now, zone)}. Reduced 9 hours: ${l.reducedRestsLeft} of 3 left."
                },
                listOf(
                    "Your 24-hour period began at ${Fmt.when_(l.dayStart!!, now, zone)}",
                    "Rest needed: ${Fmt.hours(l.dailyRestRequired)}",
                    "Latest start: ${Fmt.dayClock(latest, zone)}",
                    "Regular 11 hours, split 3 then 9, or reduced 9 (three times between weekly rests)",
                    "Reduced rests left: ${l.reducedRestsLeft} of 3",
                ),
                "Regulation 561/2006 Article 8(1) and 8(2); GOV.UK drivers' hours guide, section 1.5", met,
                if (reducedLabel != null && !met) Offer.REDUCED_REST else null, if (!met) reducedLabel else null,
            )
        }

        // Weekly rest.
        val wLatest = l.weeklyRestLatestStart
        if (restStart != null && restNow >= 24 * HOUR_MS) {
            val target = restStart + 45 * HOUR_MS
            add(
                CounterKind.WEEKLY_REST, "Weekly rest", target - now, Clock.WALL, Fmt.hm(restNow),
                if (restNow >= 45 * HOUR_MS) "Regular weekly rest taken: ${Fmt.hm(restNow)}."
                else if (l.weeklyRestRequired > 24 * HOUR_MS) "Must reach 45 hours at ${Fmt.dayClock(target, zone)}: your last weekly rest was reduced."
                else "Reduced weekly rest so far. Regular at ${Fmt.dayClock(target, zone)}.",
                listOf("Resting since ${Fmt.dayClock(restStart, zone)}", "Regular weekly rest: 45 hours", "Reduced: 24 hours, compensated by the end of the third week after"),
                "Regulation 561/2006 Article 8(6); GOV.UK drivers' hours guide, section 1.6", true, null, null,
            )
        } else if (wLatest != null) {
            val met = restStart != null && restStart <= wLatest && mode == Mode.REST && now > wLatest
            val remaining = if (met) restStart!! + l.weeklyRestRequired - now else wLatest - now
            add(
                CounterKind.WEEKLY_REST, "Weekly rest starts by", remaining, Clock.WALL, Fmt.dayClock(wLatest, zone),
                if (met) "Keep resting: ${Fmt.hours(l.weeklyRestRequired)} reached at ${Fmt.dayClock(restStart!! + l.weeklyRestRequired, zone)}."
                else if (l.weeklyRestRequired > 24 * HOUR_MS) "Must be a full 45 hours: your last weekly rest was reduced."
                else "45 hours, or a reduced 24 with compensation.",
                listOf(
                    (if (r.weekAnchorAssumed) "Counted from your first logged duty (no weekly rest logged yet): " else "Last weekly rest ended: ") +
                        Fmt.dayClock(wLatest - 6 * DAY_MS, zone),
                    "Latest start: six 24-hour periods later, ${Fmt.dayClock(wLatest, zone)}",
                    "This weekly rest must be at least ${Fmt.hours(l.weeklyRestRequired)}",
                    "Two reduced weekly rests in a row are never assumed",
                ),
                "Regulation 561/2006 Article 8(6); GOV.UK drivers' hours guide, section 1.6", met, null, null,
            )
        }

        // 56 and 90.
        add(
            CounterKind.WEEK_56, "Driving this week", l.weekLeft, Clock.DRIVING, Fmt.hm(l.weekLeft),
            "${Fmt.hm(l.weekDriving)} of 56:00 in this fixed week.",
            listOf("Driving since Monday 00:00 UTC: ${Fmt.hm(l.weekDriving)}", "Limit: 56 hours in a fixed week", "Weeks run Monday 00:00 to Sunday 24:00 UTC, the tachograph clock"),
            "Regulation 561/2006 Article 6(2); GOV.UK drivers' hours guide, section 1.4", false, null, null,
        )
        add(
            CounterKind.FORTNIGHT_90, "Driving this fortnight", l.fortnightLeft, Clock.DRIVING, Fmt.hm(l.fortnightLeft),
            "${Fmt.hm(l.weekDriving + l.lastWeekDriving)} of 90:00. Last week ${Fmt.hm(l.lastWeekDriving)}.",
            listOf("This week: ${Fmt.hm(l.weekDriving)}", "Last week: ${Fmt.hm(l.lastWeekDriving)}", "Limit: 90 hours in any two consecutive fixed weeks"),
            "Regulation 561/2006 Article 6(3); GOV.UK drivers' hours guide, section 1.4", false, null, null,
        )
    }

    private fun gbCounters(
        l: Limits, now: Long, zone: ZoneId,
        add: (CounterKind, String, Long, Clock, String, String, List<String>, String, Boolean, Offer?, String?) -> Unit,
    ) {
        val ends = l.gbDayEnd?.let { "Your day runs to ${Fmt.when_(it, now, zone)}." } ?: "Your day starts when you go on duty."
        add(
            CounterKind.GB_DRIVING, "Driving today (GB)", l.gbDrivingLeft, Clock.DRIVING, Fmt.hm(l.gbDrivingLeft),
            "${Fmt.hm(l.gbDriving)} of 10:00. $ends",
            listOf("Driving in this 24-hour day: ${Fmt.hm(l.gbDriving)}", "Limit: 10 hours", "The day is the 24 hours from the start of duty"),
            "Transport Act 1968 Part VI; GOV.UK drivers' hours guide, section 2", false, null, null,
        )
        add(
            CounterKind.GB_DUTY, "Duty today (GB)", l.gbDutyLeft, Clock.DUTY, Fmt.hm(l.gbDutyLeft),
            "${Fmt.hm(l.gbDuty)} of 11:00 on duty. $ends",
            listOf("Duty in this 24-hour day: ${Fmt.hm(l.gbDuty)}", "Limit: 11 hours on any day you drive", "Availability is counted as duty here, which can only overcount"),
            "Transport Act 1968 Part VI; GOV.UK drivers' hours guide, section 2", false, null, null,
        )
    }

    private fun rtdCounters(
        l: Limits,
        add: (CounterKind, String, Long, Clock, String, String, List<String>, String, Boolean, Offer?, String?) -> Unit,
    ) {
        add(
            CounterKind.RTD_BREAK, "Working time break", l.rtdBreakLeft, Clock.WORK, Fmt.hm(l.rtdBreakLeft),
            "Take ${Fmt.minutes(l.rtdBreakNeeded)} before ${Fmt.hm(l.rtdBreakLeft.coerceAtLeast(0))} more work.",
            listOf(
                "No more than 6 hours of work in a row without a break",
                "Work of 6 to 9 hours needs 30 minutes of breaks, over 9 hours needs 45",
                "Each break counts from 15 minutes. Availability is not work.",
            ),
            "Road Transport (Working Time) Regulations 2005, regulation 5; GOV.UK Annex 2", false, null, null,
        )
        add(
            CounterKind.RTD_WEEK_60, "Working time this week", l.rtdWeekLeft, Clock.WORK, Fmt.hm(l.rtdWeekLeft),
            "${Fmt.hm(l.rtdWeekWork)} of 60:00 worked this week.",
            listOf("Driving and other work since Monday 00:00 UTC: ${Fmt.hm(l.rtdWeekWork)}", "Limit: 60 hours in a single week"),
            "Road Transport (Working Time) Regulations 2005, regulation 4; GOV.UK Annex 2", false, null, null,
        )
        val p = l.rtdPeriod
        val df = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        add(
            CounterKind.RTD_AVERAGE, "48-hour average", l.rtdPeriodLeft, Clock.WORK,
            "${l.rtdPeriodTotal / HOUR_MS} of ${p.limitMin / 60} h",
            "Period ${df.format(p.startDate)} to ${df.format(p.lastDate)}, ${p.weeks} weeks.",
            listOf(
                "Reference period: ${df.format(p.startDate)} to ${df.format(p.lastDate)} (${p.weeks} weeks)",
                "Periods start on the Monday on or after 1 April, 1 August and 1 December",
                "Allowed: 48 hours times ${p.weeks} weeks = ${p.limitMin / 60} hours",
                "Worked plus leave credit: ${Fmt.hm(l.rtdPeriodTotal)}",
                "Leave credited: ${Fmt.hm(l.rtdLeaveCredit)} (8 hours a day, 48 a week)",
                "Your employer may use a different period under an agreement",
            ),
            "Road Transport (Working Time) Regulations 2005, regulation 4; DfT RTD guidance, sections 3.4 to 3.6", false, null, null,
        )
    }

    private fun compensationCounters(
        r: Replay, now: Long, zone: ZoneId,
        add: (CounterKind, String, Long, Clock, String, String, List<String>, String, Boolean, Offer?, String?) -> Unit,
    ) {
        if (r.debts.isEmpty()) return
        val total = r.debts.sumOf { it.owedMs }
        val first = r.debts.minBy { it.dueUtc }
        val sunday = java.time.Instant.ofEpochMilli(first.dueUtc - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val dueText = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(sunday)
        add(
            CounterKind.COMPENSATION, "Compensation owed", first.dueUtc - now, Clock.WALL, Fmt.hours(total),
            "Owed ${Fmt.hours(total)}, attach to a rest by the end of $dueText.",
            r.debts.sortedBy { it.dueUtc }.map {
                "${Fmt.hours(it.owedMs)} from the reduced rest of ${Fmt.shortDay(it.fromRestStart, zone)}, due by ${Fmt.dayClock(it.dueUtc - 1, zone)}"
            } + listOf(
                "Take it in one block on top of a full rest",
                "Dutyclock counts it paid when a rest holds it on top of 11 hours, or on top of 45 hours for a weekly rest",
            ),
            "Regulation 561/2006 Article 8(6) and 8(7); GOV.UK drivers' hours guide, section 1.6", false, null, null,
        )
    }
}
