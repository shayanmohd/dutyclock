package com.mohdshayan.dutyclock.core.time

import com.mohdshayan.dutyclock.core.model.WEEK_MS
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * The fixed week of 561/2006 Article 4(i): 00:00 on Monday to 24:00 on Sunday. Weeks are counted
 * in UTC, the time the tachograph records; screens show local time.
 */
object FixedWeek {
    fun start(utcMs: Long): Long {
        val date = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun end(utcMs: Long): Long = start(utcMs) + WEEK_MS

    /** Week boundaries strictly inside (from, to), in order. */
    fun boundariesBetween(from: Long, to: Long): List<Long> {
        val out = mutableListOf<Long>()
        var b = end(from)
        while (b < to) {
            out += b; b += WEEK_MS
        }
        return out
    }
}

/**
 * A Road Transport (Working Time) reference period. By default (no workforce agreement) the
 * fixed periods begin at 00:00 on the Monday falling on or first after 1 April, 1 August and
 * 1 December, which makes each 17 or 18 weeks long (DfT RTD guidance, section 3.6, option 1).
 * The 48-hour average allows 48 hours times the number of weeks in the period.
 */
data class RtdPeriod(val startUtc: Long, val endUtc: Long) {
    val weeks: Int get() = ((endUtc - startUtc) / WEEK_MS).toInt()
    val limitMin: Int get() = 48 * 60 * weeks
    val startDate: LocalDate get() = Instant.ofEpochMilli(startUtc).atZone(ZoneOffset.UTC).toLocalDate()
    /** The Sunday the period ends on. */
    val lastDate: LocalDate get() = Instant.ofEpochMilli(endUtc).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)

    companion object {
        private fun anchor(year: Int, month: Int): Long =
            LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        private fun anchors(year: Int) = listOf(anchor(year, 4), anchor(year, 8), anchor(year, 12))

        fun containing(utcMs: Long): RtdPeriod {
            val year = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).year
            val all = (anchors(year - 1) + anchors(year) + anchors(year + 1)).sorted()
            val i = all.indexOfLast { it <= utcMs }
            return RtdPeriod(all[i], all[i + 1])
        }
    }
}
