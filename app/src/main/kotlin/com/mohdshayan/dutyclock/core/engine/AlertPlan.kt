package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode

data class PlannedAlert(val at: Long, val title: String, val text: String, val key: String)

/**
 * Which alarms to arm: a lead alert and an at-limit alert for every limit that is counting down,
 * plus "break complete" while resting, earliest first, at most [max] (AlarmManager is asked for
 * no more than three at a time and re-armed after every write and every alarm).
 */
object AlertPlan {
    private fun noun(kind: CounterKind): Pair<String, String>? = when (kind) {
        CounterKind.BREAK -> "Break due in %d minutes." to "Break due now. Stop when it is safe to."
        CounterKind.DAILY_DRIVING -> "Daily driving ends in %d minutes." to "Daily driving limit reached."
        CounterKind.DAILY_REST -> "Daily rest must start in %d minutes." to "Daily rest must start now."
        CounterKind.WEEKLY_REST -> "Weekly rest must start in %d minutes." to "Weekly rest must start now."
        CounterKind.WEEK_56 -> "Weekly driving limit in %d minutes." to "56 hours of driving reached this week."
        CounterKind.FORTNIGHT_90 -> "Fortnight driving limit in %d minutes." to "90 hours of driving reached this fortnight."
        CounterKind.COMPENSATION -> "Compensation deadline in %d minutes." to "Compensation deadline reached."
        CounterKind.RTD_BREAK -> "Working time break due in %d minutes." to "Working time break due now."
        CounterKind.RTD_WEEK_60 -> "60-hour working week in %d minutes." to "60 hours of work reached this week."
        CounterKind.GB_DRIVING -> "GB daily driving ends in %d minutes." to "GB daily driving limit reached."
        CounterKind.GB_DUTY -> "GB daily duty ends in %d minutes." to "GB daily duty limit reached."
        CounterKind.RTD_AVERAGE -> null
    }

    /**
     * While a longer legal limit is left (a 10-hour day, a reduced rest), the regular limit's
     * alerts say so instead of claiming the limit is reached.
     */
    private fun soft(c: Counter, ev: Evaluation, fallbackAt: Long): Pair<String, String>? = when (c.kind) {
        CounterKind.DAILY_DRIVING -> {
            val left = "A 10-hour day allows 1 more hour: ${ev.limits.tenHourDaysLeft} of 2 left."
            "9 hours of driving in %d minutes. $left" to "9 hours of driving reached. $left"
        }
        CounterKind.DAILY_REST -> {
            val until = "A reduced 9-hour rest can start until ${Fmt.clock(fallbackAt, ev.zone)}."
            "A regular 11-hour rest must start in %d minutes. $until" to "Start now for a regular 11-hour rest. $until"
        }
        else -> null
    }

    /** Leaves out an alert already posted ("key@at" in [posted]) before taking the earliest [max]. */
    fun plan(ev: Evaluation, leadMin: Int, breakComplete: Boolean, max: Int = 3, posted: Set<String> = emptySet()): List<PlannedAlert> {
        val now = ev.now
        val out = mutableListOf<PlannedAlert>()
        for (c in ev.counters) {
            if (c.met || c.over || c.unknown) continue
            // A rest already under way meets a "rest must start by" deadline as long as it goes on;
            // the next duty tap re-arms everything, so no alarm wakes a driver who is resting.
            if (ev.mode == Mode.REST && (c.kind == CounterKind.DAILY_REST || c.kind == CounterKind.WEEKLY_REST)) continue
            val (lead, limit) = noun(c.kind) ?: continue
            val at = c.limitAt
            val fb = c.fallbackAt
            val lead2At = fb?.let { it - leadMin * MINUTE_MS }
            if (at != null) {
                val (l1, l2) = fb?.let { soft(c, ev, it) } ?: (lead to limit)
                val leadAt = at - leadMin * MINUTE_MS
                if (leadAt > now) out += PlannedAlert(leadAt, c.title, l1.format(leadMin), "${c.kind}-lead")
                if (at > now) out += PlannedAlert(at, c.title, l2, "${c.kind}-limit")
            }
            if (fb != null && lead2At != null) {
                if (lead2At > now && (at == null || lead2At > at)) out += PlannedAlert(lead2At, c.title, lead.format(leadMin), "${c.kind}-lead2")
                if (fb > now) out += PlannedAlert(fb, c.title, limit, "${c.kind}-limit2")
            }
        }
        val bc = ev.breakCompleteAt
        if (breakComplete && ev.mode == Mode.REST && bc != null && bc > now) {
            out += PlannedAlert(
                bc, if (ev.breakCompleteIsSplit) "Split break complete" else "Break complete",
                "4:30 of driving available after this break.", "break-complete",
            )
        }
        return out.filter { "${it.key}@${it.at}" !in posted }.sortedBy { it.at }.distinctBy { it.key }.take(max)
    }
}
