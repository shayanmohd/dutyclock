package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.DAY_MS
import com.mohdshayan.dutyclock.core.model.HOUR_MS
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.model.Segment
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.core.time.RtdPeriod

enum class FindingKind {
    BREAK_LATE, DAILY_DRIVING_OVER, THIRD_TEN_HOUR_DAY, DAILY_REST_LATE, FOURTH_REDUCED_REST,
    WEEKLY_REST_LATE, TWO_REDUCED_WEEKLY, WEEK_56_OVER, FORTNIGHT_90_OVER, COMPENSATION_LATE,
    RTD_BREAK_LATE, RTD_60_OVER, GB_DRIVING_OVER, GB_DUTY_OVER,
}

/** Something in the past log that went over a limit, at the instant it happened. */
data class Finding(val at: Long, val kind: FindingKind)

/** Rest owed for a reduced weekly rest, due in one block by [dueUtc]. */
data class Debt(val owedMs: Long, val dueUtc: Long, val fromRestStart: Long)

enum class DailyRestKind { REGULAR, SPLIT, REDUCED }

data class DailyRest(val start: Long, val end: Long, val kind: DailyRestKind, val late: Boolean)

data class WeeklyRest(val start: Long, val end: Long, val effectiveMs: Long, val reduced: Boolean, val compensationMs: Long)

/** A daily driving period: from the end of one daily or weekly rest to the start of the next. */
data class DayRecord(val start: Long, val drivingMs: Long)

/**
 * Replays segments in order and keeps the running totals every rule needs. It is a reducer: the
 * same log always produces the same state, so any edit simply replays. Sources are cited on each
 * rule; "GOV.UK 1.x" means the section of "Drivers' hours and tachographs: goods vehicles" (the
 * guide formerly published as GV262).
 *
 * Conservative by construction: every driving minute counts toward every EU counter whatever the
 * rule set of the day, optional derogations are never applied, and a rest in progress never
 * clears a debt or spends a reduced rest until it ends.
 */
class Replay(seed: Seed?) {
    // Breaks from driving, GOV.UK 1.4: 45 minutes, or 15 then 30, within 4.5 hours of driving.
    var drivingSinceBreak = 0L; private set
    var part15 = false; private set
    var lastBreakCompleteAt: Long? = null; private set
    var lastBreakWasSplit = false; private set

    // The daily period: 24 hours from the end of the last daily or weekly rest, GOV.UK 1.5.
    var dayStart: Long? = null; private set
    var drivingToday = 0L; private set
    var splitFirst = false; private set
    var reducedCount = 0; private set

    // Weekly rest, GOV.UK 1.6.
    var lastWeeklyRestEnd: Long? = null; private set
    var lastWeeklyReduced = false; private set
    var weekAnchorAssumed = false; private set

    val weekDriving = HashMap<Long, Long>()
    val weekWork = HashMap<Long, Long>()
    private val tenHourSeed = HashMap<Long, Int>()
    val days = mutableListOf<DayRecord>()
    val debts = mutableListOf<Debt>()
    val weeklyRests = mutableListOf<WeeklyRest>()
    val dailyRests = mutableListOf<DailyRest>()
    val findings = mutableListOf<Finding>()

    // Road Transport (Working Time) shift: work and breaks since the last daily or weekly rest.
    var shiftWork = 0L; private set
    var shiftBreaks = 0L; private set
    var workSinceRtdBreak = 0L; private set
    var rtdPeriodSeed: Pair<Long, Long>? = null; private set

    // GB domestic goods: the day is the 24 hours from the start of duty (GOV.UK section 2).
    var gbDayStart: Long? = null; private set
    var gbDriving = 0L; private set
    var gbDuty = 0L; private set

    /** Set when the last segment fed was an unfinished rest. */
    var ongoingRest: Segment? = null; private set
    var part15BeforeOngoing = false; private set

    private var breakFlagged = false
    private var dayFlagged = false
    private var rtdFlagged = false
    private var gbDriveFlagged = false
    private var gbDutyFlagged = false
    private val weekFlags = HashSet<String>()

    init {
        if (seed != null) {
            val t = seed.seededAtUtc
            val wk = FixedWeek.start(t)
            drivingSinceBreak = seed.drivingSinceBreakMin * MINUTE_MS
            dayStart = seed.dayStartUtc
            drivingToday = seed.drivingTodayMin * MINUTE_MS
            weekDriving[wk] = seed.thisWeekDrivingMin * MINUTE_MS
            weekDriving[wk - WEEK_MS] = seed.lastWeekDrivingMin * MINUTE_MS
            // Driving is working time, so the week's work is at least its driving.
            weekWork[wk] = maxOf(seed.thisWeekWorkMin, seed.thisWeekDrivingMin) * MINUTE_MS
            // Today's driving is work in this shift. Driving since the last break less than today's
            // means a qualifying break (45 minutes, or 15 then 30) was taken, which covers the
            // working time breaks too. Other work the catch-up does not ask for is not assumed.
            shiftWork = drivingToday
            workSinceRtdBreak = drivingSinceBreak
            shiftBreaks = if (seed.dayStartUtc != null && drivingSinceBreak < drivingToday) 45 * MINUTE_MS else 0L
            tenHourSeed[wk] = seed.tenHourDaysUsed
            reducedCount = seed.reducedDailyRestsUsed
            lastWeeklyRestEnd = seed.lastWeeklyRestEndUtc
            lastWeeklyReduced = (seed.lastWeeklyRestMin ?: (45 * 60)) < 45 * 60
            if (seed.compensationOwedMin > 0) {
                debts += Debt(seed.compensationOwedMin * MINUTE_MS, seed.compensationDueUtc ?: (wk + 4 * WEEK_MS), t)
            }
            rtdPeriodSeed = RtdPeriod.containing(t).startUtc to seed.rtdPeriodWorkMin * MINUTE_MS
            if (seed.dayStartUtc != null) {
                gbDayStart = seed.dayStartUtc
                gbDriving = drivingToday
                gbDuty = drivingToday
            }
        }
    }

    /** Ten-hour days already completed in the fixed week starting [weekStart], seed included. */
    fun tenHourDaysIn(weekStart: Long): Int =
        (tenHourSeed[weekStart] ?: 0) + days.count { it.drivingMs > 9 * HOUR_MS && FixedWeek.start(it.start) == weekStart }

    fun feed(seg: Segment, ongoing: Boolean) {
        ongoingRest = null
        if (seg.mode == Mode.REST) rest(seg, ongoing) else duty(seg)
    }

    private fun duty(seg: Segment) {
        var s = seg.start
        val cuts = FixedWeek.boundariesBetween(seg.start, seg.end) + seg.end
        for (cut in cuts) {
            dutyPiece(s, cut, seg.mode, seg.ruleSet)
            s = cut
        }
    }

    private fun dutyPiece(from: Long, to: Long, mode: Mode, ruleSet: RuleSet) {
        if (dayStart == null) dayStart = from
        if (lastWeeklyRestEnd == null) {
            lastWeeklyRestEnd = from
            weekAnchorAssumed = true
        }
        var s = from
        while (s < to) {
            val gbStart = gbDayStart
            if (gbStart == null || s >= gbStart + DAY_MS) {
                gbDayStart = s; gbDriving = 0; gbDuty = 0; gbDriveFlagged = false; gbDutyFlagged = false
            }
            val e = minOf(to, gbDayStart!! + DAY_MS)
            sub(s, e, mode, ruleSet)
            s = e
        }
    }

    private fun sub(s: Long, e: Long, mode: Mode, ruleSet: RuleSet) {
        val d = e - s
        val wk = FixedWeek.start(s)
        if (mode == Mode.DRIVE) {
            val before = drivingSinceBreak
            drivingSinceBreak += d
            if (!breakFlagged && drivingSinceBreak > 270 * MINUTE_MS) {
                findings += Finding(s + (270 * MINUTE_MS - before).coerceAtLeast(0), FindingKind.BREAK_LATE)
                breakFlagged = true
            }
            val dayBefore = drivingToday
            drivingToday += d
            if (!dayFlagged && drivingToday > 10 * HOUR_MS) {
                findings += Finding(s + (10 * HOUR_MS - dayBefore).coerceAtLeast(0), FindingKind.DAILY_DRIVING_OVER)
                dayFlagged = true
            }
            val wBefore = weekDriving[wk] ?: 0L
            weekDriving[wk] = wBefore + d
            if (wBefore + d > 56 * HOUR_MS && weekFlags.add("56-$wk")) {
                findings += Finding(s + (56 * HOUR_MS - wBefore).coerceAtLeast(0), FindingKind.WEEK_56_OVER)
            }
            val fBefore = wBefore + (weekDriving[wk - WEEK_MS] ?: 0L)
            if (fBefore + d > 90 * HOUR_MS && weekFlags.add("90-$wk")) {
                findings += Finding(s + (90 * HOUR_MS - fBefore).coerceAtLeast(0), FindingKind.FORTNIGHT_90_OVER)
            }
            gbDriving += d
            if (ruleSet == RuleSet.GB_GOODS && !gbDriveFlagged && gbDriving > 10 * HOUR_MS) {
                findings += Finding(e, FindingKind.GB_DRIVING_OVER); gbDriveFlagged = true
            }
        }
        if (mode.isRtdWork) {
            shiftWork += d
            workSinceRtdBreak += d
            val before = weekWork[wk] ?: 0L
            weekWork[wk] = before + d
            if (ruleSet.isEuFamily) {
                if (before + d > 60 * HOUR_MS && weekFlags.add("60-$wk")) {
                    findings += Finding(s + (60 * HOUR_MS - before).coerceAtLeast(0), FindingKind.RTD_60_OVER)
                }
                val late = workSinceRtdBreak > 6 * HOUR_MS ||
                    (shiftWork > 6 * HOUR_MS && shiftBreaks < 30 * MINUTE_MS) ||
                    (shiftWork > 9 * HOUR_MS && shiftBreaks < 45 * MINUTE_MS)
                if (late && !rtdFlagged) {
                    findings += Finding(e, FindingKind.RTD_BREAK_LATE); rtdFlagged = true
                }
            }
        }
        gbDuty += d
        if (ruleSet == RuleSet.GB_GOODS && !gbDutyFlagged && gbDriving > 0 && gbDuty > 11 * HOUR_MS) {
            findings += Finding(e, FindingKind.GB_DUTY_OVER); gbDutyFlagged = true
        }
    }

    private fun rest(seg: Segment, ongoing: Boolean) {
        val l = seg.length
        val s = seg.start
        if (ongoing) {
            ongoingRest = seg
            part15BeforeOngoing = part15
        }
        // Breaks from driving. Under 15 minutes counts for nothing; 15 then 30 completes a split.
        if (l >= 45 * MINUTE_MS || (part15 && l >= 30 * MINUTE_MS)) {
            lastBreakWasSplit = part15
            lastBreakCompleteAt = s + if (part15) 30 * MINUTE_MS else 45 * MINUTE_MS
            drivingSinceBreak = 0; part15 = false; breakFlagged = false
        } else if (l >= 15 * MINUTE_MS) {
            part15 = true
        }
        // Working time breaks count in pieces of 15 minutes or more.
        if (l >= 15 * MINUTE_MS) {
            shiftBreaks += l
            workSinceRtdBreak = 0
        }
        when {
            l >= 24 * HOUR_MS -> weekly(seg, ongoing)
            l >= 9 * HOUR_MS -> daily(seg, ongoing)
            // A rest still under way may yet become the daily rest itself, so it is only the first
            // part of a split once it has ended.
            l >= 3 * HOUR_MS -> if (dayStart != null && !ongoing) splitFirst = true
        }
    }

    /** Compensation rides on top of a full rest: 11 hours for a daily rest, 45 for a weekly one. */
    private fun attach(length: Long, base: Long, restEnd: Long): Long {
        var eff = length
        val it = debts.sortedBy { it.dueUtc }.iterator()
        while (it.hasNext()) {
            val debt = it.next()
            if (eff - debt.owedMs >= base) {
                eff -= debt.owedMs
                debts.remove(debt)
                if (restEnd > debt.dueUtc) findings += Finding(debt.dueUtc, FindingKind.COMPENSATION_LATE)
            }
        }
        return eff
    }

    private fun daily(seg: Segment, ongoing: Boolean) {
        val s = seg.start
        val eff = if (ongoing) seg.length else attach(seg.length, 11 * HOUR_MS, seg.end)
        val window = dayStart?.let { it + DAY_MS - s } ?: Long.MAX_VALUE
        var late = false
        val kind = when {
            splitFirst && window >= 9 * HOUR_MS -> DailyRestKind.SPLIT
            eff >= 11 * HOUR_MS && window >= 11 * HOUR_MS -> DailyRestKind.REGULAR
            window >= 9 * HOUR_MS -> DailyRestKind.REDUCED
            else -> {
                late = true
                if (eff >= 11 * HOUR_MS) DailyRestKind.REGULAR else DailyRestKind.REDUCED
            }
        }
        if (late) findings += Finding(dayStart!! + DAY_MS - 9 * HOUR_MS, FindingKind.DAILY_REST_LATE)
        if (!ongoing) {
            if (kind == DailyRestKind.REDUCED) {
                reducedCount++
                if (reducedCount > 3) findings += Finding(s, FindingKind.FOURTH_REDUCED_REST)
            }
            dailyRests += DailyRest(s, seg.end, kind, late)
        }
        closeDay()
    }

    private fun weekly(seg: Segment, ongoing: Boolean) {
        val s = seg.start
        val eff = if (ongoing) seg.length else attach(seg.length, 45 * HOUR_MS, seg.end)
        val anchor = lastWeeklyRestEnd
        if (anchor != null && s > anchor + 6 * DAY_MS) {
            findings += Finding(anchor + 6 * DAY_MS, FindingKind.WEEKLY_REST_LATE)
        }
        val ds = dayStart
        if (ds != null) {
            val window = ds + DAY_MS - s
            if (window < 11 * HOUR_MS && (window < 9 * HOUR_MS || reducedCount >= 3)) {
                findings += Finding(ds + DAY_MS - 9 * HOUR_MS, FindingKind.DAILY_REST_LATE)
            }
        }
        if (!ongoing) {
            val reduced = eff < 45 * HOUR_MS
            if (reduced && lastWeeklyReduced) findings += Finding(s, FindingKind.TWO_REDUCED_WEEKLY)
            if (reduced) {
                // Due by the end of the third week following the week the reduced rest began in.
                debts += Debt(45 * HOUR_MS - eff, FixedWeek.start(s) + 4 * WEEK_MS, s)
            }
            weeklyRests += WeeklyRest(s, seg.end, eff, reduced, seg.length - eff)
            lastWeeklyReduced = reduced
        }
        closeDay()
        lastWeeklyRestEnd = seg.end
        weekAnchorAssumed = false
        reducedCount = 0
    }

    private fun closeDay() {
        val ds = dayStart
        if (ds != null) {
            days += DayRecord(ds, drivingToday)
            if (drivingToday > 9 * HOUR_MS && tenHourDaysIn(FixedWeek.start(ds)) > 2) {
                findings += Finding(ds, FindingKind.THIRD_TEN_HOUR_DAY)
            }
        }
        dayStart = null
        drivingToday = 0
        dayFlagged = false
        splitFirst = false
        drivingSinceBreak = 0
        part15 = false
        breakFlagged = false
        shiftWork = 0; shiftBreaks = 0; workSinceRtdBreak = 0; rtdFlagged = false
    }

    companion object {
        /** Replays [segments] from [seed]; the last one is treated as still running when [lastOngoing]. */
        fun run(seed: Seed?, segments: List<Segment>, lastOngoing: Boolean): Replay {
            val r = Replay(seed)
            segments.forEachIndexed { i, seg -> r.feed(seg, lastOngoing && i == segments.lastIndex) }
            return r
        }
    }
}

/** Plain words for a past breach, used by the Week screen and the PDF. */
fun Finding.text(): String = when (kind) {
    FindingKind.BREAK_LATE -> "Drove past 4:30 without a qualifying break"
    FindingKind.DAILY_DRIVING_OVER -> "Drove over 10 hours in a day"
    FindingKind.THIRD_TEN_HOUR_DAY -> "Third 10-hour day in a fixed week"
    FindingKind.DAILY_REST_LATE -> "Daily rest started too late"
    FindingKind.FOURTH_REDUCED_REST -> "Fourth reduced daily rest between weekly rests"
    FindingKind.WEEKLY_REST_LATE -> "Weekly rest started after six 24-hour periods"
    FindingKind.TWO_REDUCED_WEEKLY -> "Two reduced weekly rests in a row"
    FindingKind.WEEK_56_OVER -> "Drove over 56 hours in a fixed week"
    FindingKind.FORTNIGHT_90_OVER -> "Drove over 90 hours in two weeks"
    FindingKind.COMPENSATION_LATE -> "Compensation taken after its deadline"
    FindingKind.RTD_BREAK_LATE -> "Worked past a working time break"
    FindingKind.RTD_60_OVER -> "Worked over 60 hours in a week"
    FindingKind.GB_DRIVING_OVER -> "Drove over 10 hours in a GB day"
    FindingKind.GB_DUTY_OVER -> "Over 11 hours on duty in a GB day"
}
