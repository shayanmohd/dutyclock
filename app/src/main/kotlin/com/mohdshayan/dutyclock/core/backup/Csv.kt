package com.mohdshayan.dutyclock.core.backup

import com.mohdshayan.dutyclock.core.model.Entry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV of the log: one row per entry, UTF-8 with a header, durations in whole minutes. */
object Csv {
    const val HEADER = "start_local,start_utc,mode,duration_min,rule_set,edited"
    private val local = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun build(entries: List<Entry>, now: Long, zone: ZoneId): String {
        val sorted = entries.sortedBy { it.startUtc }.filter { it.startUtc <= now }
        val sb = StringBuilder(HEADER).append('\n')
        sorted.forEachIndexed { i, e ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].startUtc else now
            sb.append(local.format(Instant.ofEpochMilli(e.startUtc).atZone(zone))).append(',')
                .append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(e.startUtc))).append(',')
                .append(e.mode.name).append(',')
                .append((end - e.startUtc) / 60_000L).append(',')
                .append(e.ruleSet.name).append(',')
                .append(if (e.edited) "yes" else "no").append('\n')
        }
        return sb.toString()
    }
}
