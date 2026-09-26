package com.mohdshayan.dutyclock.core.engine

import com.mohdshayan.dutyclock.core.model.HOUR_MS
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Durations as the tachograph shows them: hours and minutes, no leading zero on hours. */
object Fmt {
    fun hm(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val m = abs(ms) / MINUTE_MS
        return "$sign${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    fun hms(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val s = abs(ms) / 1000
        return "$sign${s / 3600}:${((s / 60) % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** "21 h" for whole hours, else "20:30". */
    fun hours(ms: Long): String =
        if (ms % HOUR_MS == 0L) "${ms / HOUR_MS} h" else hm(ms)

    /** "45 minutes" style for short spans. */
    fun minutes(ms: Long): String {
        val m = ms / MINUTE_MS
        return if (m == 1L) "1 minute" else "$m minutes"
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayClock = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun clock(utcMs: Long, zone: ZoneId): String = clock.format(Instant.ofEpochMilli(utcMs).atZone(zone))
    fun dayClock(utcMs: Long, zone: ZoneId): String = dayClock.format(Instant.ofEpochMilli(utcMs).atZone(zone))
    fun longDay(utcMs: Long, zone: ZoneId): String = longDay.format(Instant.ofEpochMilli(utcMs).atZone(zone))
    fun shortDay(utcMs: Long, zone: ZoneId): String = shortDay.format(Instant.ofEpochMilli(utcMs).atZone(zone))

    /** Clock time today, or with the weekday when it is not today. */
    fun when_(utcMs: Long, now: Long, zone: ZoneId): String {
        val a = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (a == b) clock(utcMs, zone) else dayClock(utcMs, zone)
    }
}

/**
 * Reads what a driver types for a duration: "3:40", "3.40", "3" (hours) or "0:45". Minutes take
 * two digits, so "3.4" is refused rather than guessed as 3:04, 3:24 or 3:40.
 */
object Durations {
    private val re = Regex("""^\s*(\d{1,3})(?:[:.,](\d{2}))?\s*$""")

    fun parse(s: String): Long? {
        val m = re.matchEntire(s) ?: return null
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        if (min >= 60) return null
        return (h * 60 + min) * MINUTE_MS
    }
}
