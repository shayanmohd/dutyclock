package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.BackupEntry
import com.mohdshayan.dutyclock.core.backup.BackupFile
import com.mohdshayan.dutyclock.core.backup.Csv
import com.mohdshayan.dutyclock.core.backup.NotABackup
import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Durations
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.FindingKind
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.AbsenceKind
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.plan.HomePlanner
import com.mohdshayan.dutyclock.core.plan.PlanResult
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The edges: nothing logged, zero and huge input, unit boundaries, time zones, DST and damaged files. */
class EdgeCasesTest {
    private val monday = at("2026-10-12T00:00")
    private fun week() = Scenario(monday - h(50)).rest(50 * 60)

    @Test
    fun `an empty log shows nothing, arms nothing and plans nothing`() {
        val input = EngineInput(emptyList(), null, emptyList(), DayChoices(), RuleSet.EU, UTC)
        val e = RuleEngine.evaluate(input, monday)
        assertTrue(e.empty)
        assertTrue(e.counters.isEmpty())
        assertNull(e.mode)
        assertTrue(AlertPlan.plan(e, 15, true).isEmpty())
        assertEquals(PlanResult.NeedsLog, HomePlanner.plan(input, monday, monday, h(3)))
        assertEquals(Csv.HEADER + "\n", Csv.build(emptyList(), monday, UTC))
        val strip = DayTotals.strips(emptyList(), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), monday, UTC).single()
        assertFalse(strip.hasActivity)
    }

    @Test
    fun `an entry tapped this instant has no length yet, and a future entry is ignored`() {
        val now = monday + h(6)
        val justNow = listOf(Entry(1, now, Mode.DRIVE, RuleSet.EU))
        val e = RuleEngine.evaluate(EngineInput(justNow, null, emptyList(), DayChoices(), RuleSet.EU, UTC), now)
        assertEquals(Mode.DRIVE, e.mode)
        assertEquals(h(4, 30), e.limits.breakLeft)
        val future = listOf(Entry(1, now + h(1), Mode.DRIVE, RuleSet.EU))
        val f = RuleEngine.evaluate(EngineInput(future, null, emptyList(), DayChoices(), RuleSet.EU, UTC), now)
        assertNull(f.mode)
        assertEquals(0L, f.limits.drivingToday)
    }

    @Test
    fun `exactly 4h30 of driving is at the limit, one minute more is over`() {
        val at = week().rest(6 * 60).drive(270).close(Mode.WORK)
        val e = at.eval()
        val brk = e.counters.single { it.kind == CounterKind.BREAK }
        assertEquals(0L, brk.remainingMs)
        assertFalse(brk.over)
        assertTrue(e.replay.findings.none { it.kind == FindingKind.BREAK_LATE })

        val over = week().rest(6 * 60).drive(271).close(Mode.WORK).eval()
        assertTrue(over.counters.single { it.kind == CounterKind.BREAK }.over)
        assertEquals(1, over.replay.findings.count { it.kind == FindingKind.BREAK_LATE })
    }

    @Test
    fun `exactly 15 then exactly 30 resets, 14 then 30 does not`() {
        val ok = week().rest(6 * 60).drive(120).rest(15).drive(60).rest(30).close(Mode.DRIVE).eval()
        assertEquals(h(4, 30) - m(1), ok.limits.breakLeft)
        val short = week().rest(6 * 60).drive(120).rest(14).drive(60).rest(30).close(Mode.DRIVE).eval()
        assertEquals(h(4, 30) - h(3) - m(1), short.limits.breakLeft)
    }

    @Test
    fun `a rest still under way is not the first part of a split, so 11 hours are still needed`() {
        val s = week().rest(6 * 60).drive(240).rest(5 * 60)
        val resting = s.eval()
        assertEquals(h(11), resting.limits.dailyRestRequired)
        val daily = resting.counters.single { it.kind == CounterKind.DAILY_REST }
        assertTrue(daily.met)
        assertEquals(monday + h(10) + h(11), resting.now + daily.remainingMs)
        // Once the driver moves on, those 5 hours were the first part of a split: 9 more are due.
        s.close(Mode.DRIVE)
        assertEquals(h(9), s.eval().limits.dailyRestRequired)
    }

    @Test
    fun `a catch-up with no log counts from its own numbers`() {
        val seededAt = monday + h(8)
        val seed = Seed(seededAtUtc = seededAt, drivingSinceBreakMin = 200, dayStartUtc = monday + h(6), drivingTodayMin = 200, thisWeekDrivingMin = 200, lastWeeklyRestEndUtc = monday)
        val e = RuleEngine.evaluate(EngineInput(emptyList(), seed, emptyList(), DayChoices(), RuleSet.EU, UTC), seededAt + h(1))
        assertFalse(e.empty)
        assertEquals(m(70), e.limits.breakLeft)
        assertEquals(h(56) - m(200), e.limits.weekLeft)
    }

    @Test
    fun `a huge drive home is refused cleanly, a one-minute drive fits`() {
        val s = week().rest(6 * 60).drive(60).close(Mode.REST)
        val huge = HomePlanner.plan(s.input(), s.end, s.end, h(999, 59))
        assertTrue(huge is PlanResult.Blocked)
        val tiny = HomePlanner.plan(s.input(), s.end, s.end, m(1))
        assertTrue(tiny is PlanResult.Fits)
        assertEquals(s.end + m(1), (tiny as PlanResult.Fits).arrival)
    }

    @Test
    fun `durations take two-digit minutes and refuse what they cannot read`() {
        assertEquals(0L, Durations.parse("0"))
        assertEquals(h(999, 59), Durations.parse("999.59"))
        assertEquals(h(3, 40), Durations.parse("3,40"))
        assertEquals(h(3, 5), Durations.parse("3:05"))
        assertNull("3.4 is ambiguous", Durations.parse("3.4"))
        assertNull(Durations.parse("3:5"))
        assertNull(Durations.parse("-1"))
        assertNull(Durations.parse("1000"))
        assertNull(Durations.parse("3:60"))
        assertNull(Durations.parse(":30"))
    }

    @Test
    fun `weeks turn at 00 00 UTC, not local midnight`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        // Monday 12 October 00:30 in Warsaw is Sunday 22:30 UTC: that hour belongs to the week before.
        val s = Scenario(at("2026-10-12T00:30", warsaw) - h(50), zone = warsaw).rest(50 * 60).drive(60).close(Mode.REST)
        val e = s.eval(now = at("2026-10-12T02:30", warsaw))
        assertEquals(0L, e.limits.weekDriving)
        assertEquals(h(1), e.limits.lastWeekDriving)
    }

    @Test
    fun `DST days keep real totals and draw on a 24-hour dial`() {
        // Clocks go forward at 01:00 on 29 March 2026: 00:30 to 03:30 on the wall is 2 real hours.
        val spring = Scenario(at("2026-03-29T00:30", LONDON), zone = LONDON).drive(120).close(Mode.REST)
        val a = DayTotals.strips(spring.entries, LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29), spring.end, LONDON).single()
        assertEquals(h(2), a.driveMs)
        assertEquals(30, a.pieces.first().fromMin)
        assertEquals(3 * 60 + 30, a.pieces.first().toMin)
        // Clocks go back at 02:00 on 25 October 2026: the day has 25 hours, pieces never pass 1440.
        val autumn = Scenario(at("2026-10-25T00:00", LONDON), zone = LONDON).drive(25 * 60).close(Mode.REST)
        val b = DayTotals.strips(autumn.entries, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), autumn.end, LONDON).single()
        assertEquals(h(25), b.driveMs)
        assertTrue(b.pieces.all { it.toMin <= 1440 && it.fromMin <= it.toMin })
        assertEquals(1440, b.pieces.last().toMin)
        assertEquals(3 * 60, DayTotals.minuteOfDay(at("2026-10-25T03:00", LONDON), LONDON))
    }

    @Test
    fun `the working time period wraps the new year`() {
        val p = RtdPeriod.containing(at("2027-01-15T12:00"))
        assertEquals(LocalDate.of(2026, 12, 7), p.startDate)
        assertEquals(LocalDate.of(2027, 4, 4), p.lastDate)
        assertEquals(17, p.weeks)
        assertEquals(816 * 60, p.limitMin)
    }

    @Test
    fun `leave credit ignores bad dates and never passes 48 hours in a week`() {
        val p = RtdPeriod.containing(at("2026-10-14T12:00"))
        val credit = RuleEngine.leaveCredit(
            listOf(
                Absence("2026-10-12", AbsenceKind.ANNUAL_LEAVE, 24 * 60),
                Absence("2026-10-13", AbsenceKind.ANNUAL_LEAVE, 24 * 60),
                Absence("2026-10-14", AbsenceKind.SICK, 24 * 60),
                Absence("not a date", AbsenceKind.SICK),
            ),
            p,
        )
        assertEquals(h(48), credit)
    }

    @Test
    fun `damaged or foreign files are refused, a BOM is tolerated`() {
        val good = Backup.encode(BackupFile(exportedAt = 1_790_000_000_000, entries = listOf(BackupEntry(1_790_000_000_000, "DRIVE", "EU"))))
        assertEquals(1, Backup.decode("﻿" + good).entries.size)
        val bad = listOf(
            "null", "[]", "{", "{}", "\u0000\u0001\u0002", good.dropLast(5),
            """{"format":"dutyclock-backup","schema":1}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1e30}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"entries":[{"startUtc":0,"mode":"DRIVE","ruleSet":"EU"}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"entries":[{"startUtc":1790000000000,"mode":"DRIVE","ruleSet":"US"}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"entries":[{"startUtc":1790000000000,"mode":"DRIVE","ruleSet":"EU"},{"startUtc":1790000000000,"mode":"REST","ruleSet":"EU"}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"absences":[{"date":"2026-02-31","kind":"SICK"}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"absences":[{"date":"2026-02-03","kind":"SICK","creditedMin":-480}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"absences":[{"date":"2026-02-03","kind":"HOLIDAY"}]}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"drivingTodayMin":-5}}""",
        )
        for (text in bad) {
            try {
                Backup.decode(text); fail("accepted: $text")
            } catch (_: NotABackup) {
            }
        }
    }
}
