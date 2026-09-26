package com.mohdshayan.dutyclock.core.plan

import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode

enum class StepKind { DRIVE, BREAK }

data class PlanStep(val kind: StepKind, val start: Long, val end: Long) {
    val length get() = end - start
}

enum class Blocker(val label: String) {
    DAILY_DRIVING("daily driving"),
    DAILY_REST("time before your daily rest"),
    WEEKLY_REST("time before your weekly rest"),
    WEEK_56("driving this week"),
    FORTNIGHT_90("driving this fortnight"),
    RTD_WEEK("working time this week"),
    GB_DRIVING("GB daily driving"),
    GB_DUTY("GB daily duty"),
}

sealed interface PlanResult {
    /** No log and no catch-up yet: the planner has nothing to start from. */
    data object NeedsLog : PlanResult

    data class Fits(val steps: List<PlanStep>, val arrival: Long) : PlanResult

    data class Blocked(
        val steps: List<PlanStep>,
        val blocker: Blocker,
        val shortByMs: Long,
        val fix: String?,
    ) : PlanResult {
        fun message(): String = "You run out of ${blocker.label} ${Fmt.minutesOrHours(shortByMs)} short."
    }
}

private fun Fmt.minutesOrHours(ms: Long): String {
    val m = (ms + MINUTE_MS - 1) / MINUTE_MS
    return if (m < 60) "$m minutes" else "${m / 60} h ${m % 60} min"
}

/**
 * "Can I make it home": the soonest order of driving and breaks the rules allow from the
 * driver's own log, or the first limit that runs out. It plans no daily rest on the way; the
 * question is whether tonight's drive fits.
 */
object HomePlanner {
    private const val SYNTH = -1L

    fun plan(input: EngineInput, now: Long, leaveAt: Long, drivingNeeded: Long): PlanResult {
        if (input.entries.isEmpty() && input.seed == null) return PlanResult.NeedsLog
        val first = run(input, now, leaveAt, drivingNeeded)
        if (first !is PlanResult.Blocked) return first
        val ev = RuleEngine.evaluate(input, now)
        val ds = ev.limits.dayStart
        // Offer the one fix the driver can choose: a 10-hour day or a reduced rest, if it covers the gap.
        if (first.blocker == Blocker.DAILY_DRIVING && !ev.limits.tenHourToday && ev.limits.tenHourDaysLeft > 0 && ds != null) {
            val retry = run(input.copy(choices = input.choices.copy(tenHourDayFor = ds)), now, leaveAt, drivingNeeded)
            if (retry is PlanResult.Fits) return first.copy(fix = "A 10-hour day would cover it: ${ev.limits.tenHourDaysLeft} of 2 left.")
        }
        if (first.blocker == Blocker.DAILY_REST && !ev.limits.reducedToday && ev.limits.reducedRestsLeft > 0 && ds != null) {
            val retry = run(input.copy(choices = input.choices.copy(reducedRestFor = ds)), now, leaveAt, drivingNeeded)
            if (retry is PlanResult.Fits) return first.copy(fix = "A reduced 9-hour rest tonight would cover it: ${ev.limits.reducedRestsLeft} of 3 left.")
        }
        return first
    }

    private fun run(input: EngineInput, now: Long, leaveAt: Long, drivingNeeded: Long): PlanResult {
        val extra = mutableListOf<Entry>()
        val steps = mutableListOf<PlanStep>()
        val rs = input.ruleSet
        val current = input.entries.filter { it.startUtc <= now }.maxByOrNull { it.startUtc }
        var t = maxOf(now, leaveAt)
        if (t > now && current?.mode != Mode.REST) extra += Entry(SYNTH, now, Mode.REST, rs)
        var remaining = drivingNeeded
        repeat(60) {
            val inp = input.copy(entries = input.entries + extra)
            val r = RuleEngine.replay(inp, t)
            val l = RuleEngine.limits(inp, r, t)
            val caps: List<Pair<Blocker?, Long>> = if (rs.isEuFamily) buildList {
                add(Pair<Blocker?, Long>(null, l.breakLeft))
                add(Blocker.DAILY_DRIVING to l.dailyDrivingLeft)
                l.dailyRestLatestStart?.let { add(Blocker.DAILY_REST to it - t) }
                l.weeklyRestLatestStart?.let { add(Blocker.WEEKLY_REST to it - t) }
                add(Blocker.WEEK_56 to l.weekLeft)
                add(Blocker.FORTNIGHT_90 to l.fortnightLeft)
                add(Pair<Blocker?, Long>(null, l.rtdBreakLeft))
                add(Blocker.RTD_WEEK to l.rtdWeekLeft)
            } else listOf<Pair<Blocker?, Long>>(Blocker.GB_DRIVING to l.gbDrivingLeft, Blocker.GB_DUTY to l.gbDutyLeft)
            val can = caps.minOf { it.second }
            if (can >= MINUTE_MS) {
                // Whole minutes only, so the steps shown add up to the drive asked for.
                val d = minOf(remaining, can - can % MINUTE_MS)
                extra += Entry(SYNTH, t, Mode.DRIVE, rs)
                steps += PlanStep(StepKind.DRIVE, t, t + d)
                t += d
                remaining -= d
                if (remaining <= 0) return PlanResult.Fits(steps, t)
                extra += Entry(SYNTH, t, Mode.REST, rs)
            } else {
                val binding = caps.filter { it.second < MINUTE_MS }
                val hard = binding.firstOrNull { it.first != null }
                if (hard != null) return PlanResult.Blocked(steps, hard.first!!, remaining, null)
                // Only breaks bind: take the longer of the driving break and the working time break.
                val need = maxOf(
                    if (l.breakLeft < MINUTE_MS) l.breakNeeded else 0L,
                    if (l.rtdBreakLeft < MINUTE_MS) l.rtdBreakNeeded else 0L,
                )
                if ((extra.lastOrNull() ?: current)?.mode != Mode.REST) extra += Entry(SYNTH, t, Mode.REST, rs)
                steps += PlanStep(StepKind.BREAK, t, t + need)
                t += need
            }
        }
        return PlanResult.Blocked(steps, Blocker.DAILY_DRIVING, remaining, null)
    }
}
