package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.NotABackup
import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.plan.HomePlanner
import com.mohdshayan.dutyclock.core.plan.PlanResult
import com.mohdshayan.dutyclock.core.time.FixedWeek
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cases found in review: each one failed before its fix. */
class ReviewFindingsTest {
    private val monday = at("2026-10-12T00:00")

    /** Weekly rest to Monday 00:00, then five 24-hour days of 9 hours' duty and 15 hours' rest. */
    private fun fiveDays(): Scenario {
        val s = Scenario(monday - h(50)).rest(50 * 60)
        repeat(5) { s.work(30).drive(240).rest(45).drive(180).work(45).rest(15 * 60) }
        return s
    }

    @Test
    fun `a rest already under way does not sound a weekly rest must start alarm`() {
        // Saturday: 5 hours' duty, then resting from 05:00. The weekly rest must start by Sunday 00:00.
        val s = fiveDays().work(30).drive(240).work(30).rest(18 * 60)
        val e = s.eval()
        assertEquals(Mode.REST, e.mode)
        assertEquals(monday + 6 * 24 * h(1), e.limits.weeklyRestLatestStart)
        val alerts = AlertPlan.plan(e, leadMin = 15, breakComplete = true)
        assertTrue(alerts.toString(), alerts.none { it.key.startsWith("WEEKLY_REST") || it.key.startsWith("DAILY_REST") })
    }

    @Test
    fun `the weekly rest alarm still sounds while on duty`() {
        val s = fiveDays().work(30).drive(240).work(30)
        val alerts = AlertPlan.plan(s.eval(), leadMin = 15, breakComplete = true, max = 10)
        assertTrue(alerts.any { it.key == "WEEKLY_REST-limit" })
    }

    @Test
    fun `a catch-up counts today's driving as working time too`() {
        val seededAt = monday + h(9)
        val seed = Seed(
            seededAtUtc = seededAt, drivingSinceBreakMin = 100, dayStartUtc = monday + h(5), drivingTodayMin = 200,
            thisWeekDrivingMin = 1000, lastWeeklyRestEndUtc = monday - h(10),
        )
        val e = RuleEngine.evaluate(EngineInput(emptyList(), seed, emptyList(), DayChoices(), RuleSet.EU, UTC), seededAt)
        // Driving is work: the week holds at least its 1,000 minutes of driving.
        assertEquals(m(1000), e.limits.rtdWeekWork)
        // A qualifying break was taken (100 minutes since it, 200 today): 6 hours in a row count from it.
        assertEquals(h(6) - m(100), e.limits.rtdBreakLeft)
        assertEquals(m(15), e.limits.rtdBreakNeeded)
        // With no break yet, 6 hours of work in the shift need 30 minutes, not 15.
        val noBreak = seed.copy(drivingSinceBreakMin = 200)
        val f = RuleEngine.evaluate(EngineInput(emptyList(), noBreak, emptyList(), DayChoices(), RuleSet.EU, UTC), seededAt)
        assertEquals(h(6) - m(200), f.limits.rtdBreakLeft)
        assertEquals(m(30), f.limits.rtdBreakNeeded)
    }

    @Test
    fun `plan steps are whole minutes that add up to the drive, even when now has seconds`() {
        val s = Scenario(monday - h(50)).rest(50 * 60).work(10).drive(133)
        val now = s.end + 40_000
        val r = HomePlanner.plan(s.input(), now, now, h(5, 30))
        r as PlanResult.Fits
        assertTrue(r.steps.all { it.length % m(1) == 0L })
        assertEquals(h(5, 30), r.steps.filter { it.kind == com.mohdshayan.dutyclock.core.plan.StepKind.DRIVE }.sumOf { it.length })
        assertEquals(now + h(5, 30) + m(45), r.arrival)
    }

    @Test
    fun `a blocked plan is short by exactly what the steps leave out`() {
        val s = Scenario(monday - h(50)).rest(50 * 60).work(10).drive(133)
        val now = s.end + 40_000
        val r = HomePlanner.plan(s.input(), now, now, h(9))
        r as PlanResult.Blocked
        val driven = r.steps.filter { it.kind == com.mohdshayan.dutyclock.core.plan.StepKind.DRIVE }.sumOf { it.length }
        assertEquals(h(9) - driven, r.shortByMs)
        assertEquals("You run out of daily driving 2 h 14 min short.", r.message())
    }

    @Test
    fun `weeks and working time periods stay on UTC in a far-east zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // Monday 12 October 08:30 in Tokyo is Sunday 23:30 UTC: the week before.
        val t = at("2026-10-12T08:30", tokyo)
        assertEquals(at("2026-10-05T00:00"), FixedWeek.start(t))
        // 7 December 2026 is the Monday after 1 December: 08:59 in Tokyo on that day is still the August period.
        assertEquals(LocalDate.of(2026, 8, 3), RtdPeriod.containing(at("2026-12-07T08:59", tokyo)).startDate)
        assertEquals(LocalDate.of(2026, 12, 7), RtdPeriod.containing(at("2026-12-07T09:00", tokyo)).startDate)
    }

    @Test
    fun `the daily rest deadline is real hours across the spring clock change`() {
        // Day starts 00:30 GMT on 29 March 2026; clocks go forward at 01:00. 13 real hours later is 14:30 BST.
        val s = Scenario(at("2026-03-28T23:30", LONDON) - h(12), zone = LONDON).rest(12 * 60 + 60).work(30).drive(60)
        val e = s.eval()
        assertEquals("14:30", Fmt.clock(e.limits.dailyRestLatestStart!!, LONDON))
    }

    @Test
    fun `a lead longer than the time left arms only the limit alert`() {
        val s = Scenario(monday).work(10).drive(260)
        val alerts = AlertPlan.plan(s.eval(), leadMin = 30, breakComplete = true, max = 10)
        assertTrue(alerts.none { it.key == "BREAK-lead" })
        assertTrue(alerts.any { it.key == "BREAK-limit" })
    }

    @Test
    fun `entries before the catch-up are not counted twice`() {
        val seededAt = monday + h(9)
        val seed = Seed(seededAtUtc = seededAt, dayStartUtc = monday + h(5), drivingTodayMin = 200, drivingSinceBreakMin = 200, thisWeekDrivingMin = 200)
        val before = listOf(Entry(1, monday + h(5), Mode.DRIVE, RuleSet.EU), Entry(2, monday + h(8), Mode.REST, RuleSet.EU))
        val e = RuleEngine.evaluate(EngineInput(before, seed, emptyList(), DayChoices(), RuleSet.EU, UTC), seededAt + h(1))
        assertEquals(m(200), e.limits.drivingToday)
        assertEquals(m(200), e.limits.weekDriving)
    }

    @Test
    fun `impossible catch-up counts in a backup are refused`() {
        val bad = listOf(
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"tenHourDaysUsed":3}}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"reducedDailyRestsUsed":4}}""",
            """{"format":"dutyclock-backup","schema":2,"exportedAt":1}""",
            "",
        )
        for (text in bad) {
            try {
                Backup.decode(text); fail("accepted: $text")
            } catch (_: NotABackup) {
            }
        }
    }

    @Test
    fun `counters survive an empty log with only leave`() {
        val input = EngineInput(emptyList(), null, listOf(com.mohdshayan.dutyclock.core.model.Absence("2026-10-12", com.mohdshayan.dutyclock.core.model.AbsenceKind.SICK)), DayChoices(), RuleSet.EU, UTC)
        val e = RuleEngine.evaluate(input, monday + h(12))
        assertTrue(e.empty)
        assertTrue(e.counters.none { it.kind == CounterKind.RTD_AVERAGE })
    }
}
