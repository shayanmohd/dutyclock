package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.time.FixedWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Week screen counts the fixed week in UTC, as the tachograph does. Its days are the local
 * days that week touches, each holding only the part inside the week, so the days and the totals
 * always describe the same week, whatever the phone's zone.
 */
class WeekDaysTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val chicago = ZoneId.of("America/Chicago")

    private fun dates(from: String, to: String): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = LocalDate.parse(from)
        while (!d.isAfter(LocalDate.parse(to))) { out += d; d = d.plusDays(1) }
        return out
    }

    /** Logged driving in the week starting [ws], as the totals count it. */
    private fun weekDriving(s: Scenario, ws: Long) = RuleEngine.replay(s.input(), s.end).weekDriving[ws] ?: 0L

    @Test
    fun `just after local midnight ahead of UTC the days are the week the totals count`() {
        // Monday 12 October 05:00 to 06:00 in Kolkata straddles the week's start, 05:30 there (00:00 UTC).
        val s = Scenario(at("2026-10-12T05:00", kolkata), zone = kolkata).drive(60)
        s.rest(6 * 24 * 60 + 20 * 60 + 43)
        assertEquals(at("2026-10-19T02:43", kolkata), s.end)
        s.drive(4) // the S22+ case: Monday 19 October 02:47 in Kolkata is Sunday 21:17 UTC
        val ws = FixedWeek.start(s.end)
        assertEquals(at("2026-10-12T00:00"), ws)

        val days = DayTotals.weekStrips(s.entries, ws, s.end, kolkata)
        assertEquals(dates("2026-10-12", "2026-10-19"), days.map { it.date })
        assertEquals(m(30), days.first().driveMs)
        assertEquals(5 * 60 + 30, days.first().pieces.first().fromMin)
        assertEquals(m(4), days.last().driveMs)
        assertEquals(weekDriving(s, ws), days.sumOf { it.driveMs })
        assertEquals("Mon 12 Oct 05:30 to Mon 19 Oct 05:30", DayTotals.weekSpan(ws, kolkata))
    }

    @Test
    fun `just before local midnight behind UTC the days are the week the totals count`() {
        // Sunday 18 October 18:30 to 19:30 in Chicago straddles the week's start, 19:00 there (00:00 UTC Monday).
        val s = Scenario(at("2026-10-18T18:30", chicago), zone = chicago).drive(60)
        val ws = FixedWeek.start(s.end)
        assertEquals(at("2026-10-19T00:00"), ws)

        val days = DayTotals.weekStrips(s.entries, ws, s.end, chicago)
        assertEquals(dates("2026-10-18", "2026-10-25"), days.map { it.date })
        assertEquals(m(30), days.first().driveMs)
        assertEquals(19 * 60, days.first().pieces.first().fromMin)
        assertEquals(weekDriving(s, ws), days.sumOf { it.driveMs })
        assertEquals("Sun 18 Oct 19:00 to Sun 25 Oct 19:00", DayTotals.weekSpan(ws, chicago))

        // The week before ends at 19:00 on Sunday 18 there, with the first half of that drive.
        val before = DayTotals.weekStrips(s.entries, ws - WEEK_MS, s.end, chicago)
        assertEquals(dates("2026-10-11", "2026-10-18"), before.map { it.date })
        assertEquals(m(30), before.last().driveMs)
        assertEquals(19 * 60, before.last().pieces.last().toMin)
        assertEquals(weekDriving(s, ws - WEEK_MS), before.sumOf { it.driveMs })
    }

    @Test
    fun `across the autumn clock change in London the weeks keep to UTC`() {
        // 00:30 to 01:30 BST on Monday 19 October is 23:30 Sunday to 00:30 Monday UTC.
        val s = Scenario(at("2026-10-19T00:30", LONDON), zone = LONDON).drive(60)
        s.rest(7 * 24 * 60)
        val ws = at("2026-10-19T00:00")

        // In summer time the week runs 01:00 Monday to 01:00 Monday: eight local days.
        val before = DayTotals.weekStrips(s.entries, ws - WEEK_MS, s.end, LONDON)
        assertEquals(dates("2026-10-12", "2026-10-19"), before.map { it.date })
        assertEquals(m(30), before.last().driveMs)
        assertEquals(weekDriving(s, ws - WEEK_MS), before.sumOf { it.driveMs })
        assertEquals("Mon 12 Oct 01:00 to Mon 19 Oct 01:00", DayTotals.weekSpan(ws - WEEK_MS, LONDON))

        // Clocks go back on Sunday 25 October, so this week ends at local midnight: seven days.
        val days = DayTotals.weekStrips(s.entries, ws, s.end, LONDON)
        assertEquals(dates("2026-10-19", "2026-10-25"), days.map { it.date })
        assertEquals(m(30), days.first().driveMs)
        assertEquals(60, days.first().pieces.first().fromMin)
        assertEquals(weekDriving(s, ws), days.sumOf { it.driveMs })
        assertEquals("Mon 19 Oct 01:00 to Mon 26 Oct 00:00", DayTotals.weekSpan(ws, LONDON))

        // The next week is on GMT, the same as UTC: seven days and no local span to explain.
        assertEquals(dates("2026-10-26", "2026-11-01"), DayTotals.weekStrips(s.entries, ws + WEEK_MS, s.end, LONDON).map { it.date })
        assertNull(DayTotals.weekSpan(ws + WEEK_MS, LONDON))
    }

    @Test
    fun `across the spring clock change in London the week gains a last hour on Monday`() {
        val ws = at("2026-03-23T00:00")
        assertEquals(dates("2026-03-23", "2026-03-30"), DayTotals.weekStrips(emptyList(), ws, ws + WEEK_MS, LONDON).map { it.date })
        assertEquals("Mon 23 Mar 00:00 to Mon 30 Mar 01:00", DayTotals.weekSpan(ws, LONDON))
    }

    @Test
    fun `on UTC the week is Monday to Sunday with no local span`() {
        val ws = at("2026-10-12T00:00")
        assertEquals(dates("2026-10-12", "2026-10-18"), DayTotals.weekStrips(emptyList(), ws, ws + WEEK_MS, UTC).map { it.date })
        assertNull(DayTotals.weekSpan(ws, UTC))
    }
}
