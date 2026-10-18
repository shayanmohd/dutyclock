package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.DAY_MS
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.HOUR_MS
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.model.Segment
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.model.segmentsOf
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** The seed's numbers at seededAtUtc, never below zero. [short] is the mode of the first piece that took a number below zero, else null. */
data class SeedStart(
    val drivingSinceBreak: Long,
    val drivingToday: Long,
    val thisWeekDriving: Long,
    val lastWeekDriving: Long,
    val thisWeekWork: Long,
    val periodWork: Long,
    val short: Mode?,
    /** Other work since the last daily rest at seededAtUtc. Optional on the sheet, so it clamps without setting [short]. */
    val otherWorkSinceRest: Long = 0,
)

/**
 * "Bring your week in": the rules that turn a tachograph reading into the numbers the replay
 * starts from. Plain Kotlin, no Android, so every rule here has a golden test.
 */
object Setup {
    /**
     * What the log says happened between the start of counting and the reading: the segments of
     * [entries] clipped to [seededAtUtc, readAtUtc). Empty for a 1.0.0 seed (no reading instant).
     */
    fun between(seed: Seed, entries: List<Entry>): List<Segment> {
        val readAt = seed.readAtUtc ?: return emptyList()
        val s = seed.seededAtUtc
        if (readAt <= s) return emptyList()
        return segmentsOf(entries.filter { it.startUtc < readAt }, readAt)
            .filter { it.end > s }
            .map { if (it.start < s) it.copy(start = s) else it }
    }

    /**
     * The reading less what [between] adds back in the replay, so the replay arrives at the
     * reading exactly at readAtUtc. Weeks and the working time period are those of the reading.
     * A number that would go below zero stops at zero and [SeedStart.short] names the mode; the
     * sheet and the editor refuse such a state, and a clamp can only overcount. Work earlier in the
     * reference period clamps without setting [SeedStart.short].
     */
    fun start(seed: Seed, between: List<Segment>): SeedStart {
        val ref = seed.readAtUtc ?: seed.seededAtUtc
        val w = FixedWeek.start(ref)
        val p = RtdPeriod.containing(ref).startUtc

        // The tachograph resets driving since the break where a break completes, and driving since
        // the daily rest at a rest of 9 hours or more, so only pieces after those points come off.
        var part15 = seed.part15Taken
        var lastBreak = -1
        var lastDaily = -1
        between.forEachIndexed { i, seg ->
            if (seg.mode != Mode.REST) return@forEachIndexed
            val l = seg.length
            if (l >= 45 * MINUTE_MS || (part15 && l >= 30 * MINUTE_MS)) {
                lastBreak = i; part15 = false
            } else if (l >= 15 * MINUTE_MS) {
                part15 = true
            }
            if (l >= 9 * HOUR_MS) lastDaily = i
        }

        var short: Mode? = null
        fun take(v: Long, d: Long, mode: Mode): Long {
            val r = v - d
            if (r < 0 && short == null) short = mode
            return r.coerceAtLeast(0)
        }

        var dsb = seed.drivingSinceBreakMin * MINUTE_MS
        var dt = seed.drivingTodayMin * MINUTE_MS
        var tw = seed.thisWeekDrivingMin * MINUTE_MS
        var lw = seed.lastWeekDrivingMin * MINUTE_MS
        var work = maxOf(seed.thisWeekWorkMin, seed.thisWeekDrivingMin) * MINUTE_MS
        var period = seed.rtdPeriodWorkMin * MINUTE_MS
        var other = seed.otherWorkSinceRestMin * MINUTE_MS

        between.forEachIndexed { i, seg ->
            if (!seg.mode.isRtdWork) return@forEachIndexed
            var from = seg.start
            for (cut in FixedWeek.boundariesBetween(seg.start, seg.end) + seg.end) {
                val d = cut - from
                val x = FixedWeek.start(from)
                if (seg.mode == Mode.DRIVE) {
                    if (x == w) tw = take(tw, d, Mode.DRIVE) else if (x == w - WEEK_MS) lw = take(lw, d, Mode.DRIVE)
                    if (i > lastBreak) dsb = take(dsb, d, Mode.DRIVE)
                    if (i > lastDaily) dt = take(dt, d, Mode.DRIVE)
                } else if (i > lastDaily) {
                    other = (other - d).coerceAtLeast(0)
                }
                // Work earlier in the period is a number many drivers leave empty; clamping it only
                // overcounts the average, so it never refuses a setup.
                if (x == w) work = take(work, d, seg.mode) else if (x < w && x >= p) period = (period - d).coerceAtLeast(0)
                from = cut
            }
        }
        return SeedStart(dsb, dt, tw, lw, maxOf(work, tw), period, short, other)
    }

    /**
     * Checks the sheet and turns it into a seed: counting starts at [SetupForm.sinceUtc], the
     * numbers stay the reading at [now], and the current mode is logged from since. Refuses any
     * form whose current mode used more than the reading holds.
     */
    fun build(form: SetupForm, now: Long, zone: ZoneId = ZoneOffset.UTC): SetupResult {
        fun no(message: String) = SetupResult.Refused(message)
        val mode = form.mode ?: return no("Pick what you are doing right now.")
        val since = form.sinceUtc ?: return no("Pick when you started it.")
        if (since > now) return no("That start is in the future. Pick a time up to now.")
        if (since < now - 7 * DAY_MS) return no("Pick a start in the last 7 days.")
        if (mode.isDuty && !form.started) {
            return no("You are on duty now, so your working day has started. Switch on I have started work since my last daily rest.")
        }
        val dayStart = form.dayStartUtc.takeIf { form.started }
        if (dayStart != null && dayStart > since) return no("Work cannot start after your current mode began. Check both times.")
        if (dayStart != null && dayStart < since - DAY_MS) return no("Work started more than 24 hours before your current mode began. Check both times.")
        // Not started since the last daily rest: that rest reset both driving numbers.
        val sb = if (form.started) form.drivingSinceBreak else 0L
        val dt = if (form.started) form.drivingToday else 0L
        val ow = if (form.started) form.otherWorkSinceRest else 0L
        val week = 168 * HOUR_MS
        when {
            sb > DAY_MS || dt > DAY_MS -> return no("Driving since your last daily rest cannot be more than 24 hours. Check it.")
            dt + ow > DAY_MS -> return no("Driving and other work since your last daily rest cannot be more than 24 hours. Check them.")
            form.thisWeekDriving > week || form.lastWeekDriving > week || form.thisWeekWork > week ->
                return no("A week holds at most 168 hours. Check this week and last.")
            form.lastWeeklyRest < 24 * HOUR_MS -> return no("A weekly rest is at least 24 hours. Check how long it was.")
            form.compensationOwed > 21 * HOUR_MS -> return no("Compensation owed is at most 21 hours, 45 less 24. Check it.")
            sb > dt -> return no("Driving since your last break is more than your driving since your last daily rest. Check both.")
            // A shift begun on Sunday counts partly in last week.
            dt > form.thisWeekDriving + form.lastWeekDriving ->
                return no("Driving since your last daily rest is more than this week and last week together. Check them.")
            form.lastWeeklyRestEndUtc > now -> return no("Your last weekly rest cannot end in the future.")
            since < form.lastWeeklyRestEndUtc -> return no("Your current mode cannot start before your last weekly rest ended. Check both times.")
        }
        val due = when {
            form.compensationOwed == 0L -> null
            form.compensationDueUtc != null -> form.compensationDueUtc
            else -> FixedWeek.start(form.lastWeeklyRestEndUtc - form.lastWeeklyRest) + 4 * WEEK_MS
        }
        val seed = Seed(
            seededAtUtc = since,
            drivingSinceBreakMin = (sb / MINUTE_MS).toInt(),
            dayStartUtc = dayStart,
            drivingTodayMin = (dt / MINUTE_MS).toInt(),
            thisWeekDrivingMin = (form.thisWeekDriving / MINUTE_MS).toInt(),
            lastWeekDrivingMin = (form.lastWeekDriving / MINUTE_MS).toInt(),
            thisWeekWorkMin = (form.thisWeekWork / MINUTE_MS).toInt(),
            rtdPeriodWorkMin = (form.rtdPeriodWork / MINUTE_MS).toInt(),
            tenHourDaysUsed = form.tenHourDaysUsed,
            reducedDailyRestsUsed = form.reducedDailyRestsUsed,
            lastWeeklyRestEndUtc = form.lastWeeklyRestEndUtc,
            lastWeeklyRestMin = (form.lastWeeklyRest / MINUTE_MS).toInt(),
            compensationOwedMin = (form.compensationOwed / MINUTE_MS).toInt(),
            compensationDueUtc = due,
            part15Taken = form.started && form.part15Taken,
            readAtUtc = now,
            otherWorkSinceRestMin = (ow / MINUTE_MS).toInt(),
        )
        val at = Fmt.when_(since, now, zone)
        return when (start(seed, listOf(Segment(since, now, mode, RuleSet.EU))).short) {
            Mode.DRIVE -> no("You have been driving since $at, which is more than the driving you entered. Check the time and the totals.")
            Mode.WORK -> no("You have been working since $at, which is more than the work you entered for this week. Check the time and the totals.")
            else -> SetupResult.Ok(seed, mode, since)
        }
    }

    /** Moves the start of counting back to [newStart]. No number changes: the reading stays the reading. */
    fun reanchor(seed: Seed, newStart: Long): Seed =
        seed.copy(seededAtUtc = newStart, readAtUtc = seed.readAtUtc ?: seed.seededAtUtc)

    /** Whether counting may move back to [newStart] with the log as it will be after the edit. */
    fun canReanchor(seed: Seed, newStart: Long, entriesAfterEdit: List<Entry>): Boolean {
        val readAt = seed.readAtUtc ?: seed.seededAtUtc
        if (seed.dayStartUtc != null && newStart < seed.dayStartUtc) return false
        if (seed.lastWeeklyRestEndUtc != null && newStart < seed.lastWeeklyRestEndUtc) return false
        if (newStart < readAt - 7 * DAY_MS) return false
        val moved = reanchor(seed, newStart)
        return start(moved, between(moved, entriesAfterEdit)).short == null
    }

    /**
     * What saving an edit does, before it is saved: entry [editedId] gets [newStart] and
     * [newMode], or a new entry is added when [editedId] is null (an add or a split).
     */
    fun editImpact(seed: Seed?, entries: List<Entry>, editedId: Long?, newStart: Long, newMode: Mode): EditImpact {
        if (seed == null) return EditImpact.Counted
        val edited = if (editedId == null) {
            entries + Entry(NEW_ENTRY, newStart, newMode, RuleSet.EU)
        } else {
            entries.map { if (it.id == editedId) it.copy(startUtc = newStart, mode = newMode) else it }
        }
        val s = seed.seededAtUtc
        if (newStart >= s) {
            return if (start(seed, between(seed, edited)).short != null) EditImpact.Exceeds(seed.readAtUtc ?: s) else EditImpact.Counted
        }
        val next = edited.filter { it.startUtc > newStart }.minOfOrNull { it.startUtc }
        if (next != null && next <= s) return EditImpact.BeforeStart(s)
        return if (canReanchor(seed, newStart, edited)) EditImpact.Reanchor(newStart) else EditImpact.CannotMove(s)
    }

    /** The default "last weekly rest ended": the most recent Monday 06:00 local time that is not after [now]. */
    fun defaultWeeklyRestEnd(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // atZone settles a DST change at 06:00 for us.
        val thisWeek = monday.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        return if (thisWeek <= now) thisWeek else monday.minusWeeks(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
    }

    private const val NEW_ENTRY = Long.MIN_VALUE
}

/** What the setup sheet holds, in instants and milliseconds. */
data class SetupForm(
    val mode: Mode?,
    val sinceUtc: Long?,
    val started: Boolean,
    val dayStartUtc: Long?,
    val part15Taken: Boolean,
    val drivingSinceBreak: Long,
    val drivingToday: Long,
    val thisWeekDriving: Long,
    val lastWeekDriving: Long,
    val thisWeekWork: Long,
    val rtdPeriodWork: Long,
    val tenHourDaysUsed: Int,
    val reducedDailyRestsUsed: Int,
    val lastWeeklyRestEndUtc: Long,
    val lastWeeklyRest: Long,
    val compensationOwed: Long,
    val compensationDueUtc: Long?,
    val otherWorkSinceRest: Long = 0,
)

sealed interface SetupResult {
    data class Ok(val seed: Seed, val entryMode: Mode, val entryStart: Long) : SetupResult
    data class Refused(val message: String) : SetupResult
}

/** What an edit, add or split in the Log does to the start of counting. */
sealed interface EditImpact {
    /** Starts at or after the start of counting: Today counts it. */
    data object Counted : EditImpact
    /** Starts before it and runs across it: counting moves back to its start. */
    data class Reanchor(val to: Long) : EditImpact
    /** As Reanchor, but counting cannot move that far back. */
    data class CannotMove(val start: Long) : EditImpact
    /** Ends at or before the start of counting, so the brought-in numbers already hold it. */
    data class BeforeStart(val start: Long) : EditImpact
    /** Would put more driving or work before the reading than the reading holds. */
    data class Exceeds(val readAt: Long) : EditImpact
}
