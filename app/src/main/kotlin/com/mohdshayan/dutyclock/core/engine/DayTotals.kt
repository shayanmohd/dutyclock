package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.model.segmentsOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One piece of a local calendar day, in minutes from local midnight. */
data class Piece(val fromMin: Int, val toMin: Int, val mode: Mode)

/** A local calendar day as the Week screen, the Day Disc and the PDF draw it. */
data class DayStrip(
    val date: LocalDate,
    val pieces: List<Piece>,
    val driveMs: Long,
    val workMs: Long,
    val availableMs: Long,
    val restMs: Long,
    val edited: Boolean,
    val ruleSets: Set<RuleSet>,
) {
    val hasActivity get() = driveMs + workMs + availableMs > 0
}

object DayTotals {
    /** Local days [from] to [to]; with [clipFrom] and [clipTo], each holds only what falls between them. */
    fun strips(
        entries: List<Entry>, from: LocalDate, to: LocalDate, now: Long, zone: ZoneId,
        clipFrom: Long = Long.MIN_VALUE, clipTo: Long = Long.MAX_VALUE,
    ): List<DayStrip> {
        val segs = segmentsOf(entries, now)
        val sorted = entries.sortedBy { it.startUtc }
        val out = mutableListOf<DayStrip>()
        var date = from
        while (!date.isAfter(to)) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val lo = maxOf(dayStart, clipFrom)
            val hi = minOf(dayEnd, clipTo)
            val pieces = mutableListOf<Piece>()
            val totals = LongArray(4)
            val rules = mutableSetOf<RuleSet>()
            for (s in segs) {
                val a = maxOf(s.start, lo)
                val b = minOf(s.end, hi)
                if (b <= a) continue
                totals[s.mode.ordinal] += b - a
                rules += s.ruleSet
                // Pieces sit on the wall clock, as a tachograph chart does, so a 23 or 25-hour DST day
                // still draws on a 24-hour dial. Totals above stay in real elapsed time.
                val from = minuteOfDay(a, zone)
                val to = if (b >= dayEnd) 1440 else minuteOfDay(b, zone)
                pieces += Piece(from, maxOf(from, to), s.mode)
            }
            val edited = sorted.any { it.edited && it.startUtc in lo until hi }
            out += DayStrip(date, pieces, totals[0], totals[1], totals[2], totals[3], edited, rules)
            date = date.plusDays(1)
        }
        return out
    }

    /**
     * The fixed week starting [weekStart], counted in UTC as the totals count it, as the local days
     * it touches, the first and last holding only their part of it. A zone off UTC at either end of the
     * week gets a part day there: eight days in Kolkata or in British Summer Time.
     */
    fun weekStrips(entries: List<Entry>, weekStart: Long, now: Long, zone: ZoneId): List<DayStrip> {
        val end = weekStart + WEEK_MS
        return strips(entries, localDate(weekStart, zone), localDate(end - 1, zone), now, zone, weekStart, end)
    }

    /** The fixed week on the local clock, "Mon 12 Oct 05:30 to Mon 19 Oct 05:30"; null where it is UTC at both ends. */
    fun weekSpan(weekStart: Long, zone: ZoneId): String? {
        val start = Instant.ofEpochMilli(weekStart)
        val end = Instant.ofEpochMilli(weekStart + WEEK_MS)
        if (zone.rules.getOffset(start).totalSeconds == 0 && zone.rules.getOffset(end).totalSeconds == 0) return null
        return "${spanFmt.format(start.atZone(zone))} to ${spanFmt.format(end.atZone(zone))}"
    }

    private val spanFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun localDate(utcMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDate()

    /** Minutes past local midnight on the wall clock, 0 to 1439. */
    fun minuteOfDay(utcMs: Long, zone: ZoneId): Int = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalTime().toSecondOfDay() / 60
}
