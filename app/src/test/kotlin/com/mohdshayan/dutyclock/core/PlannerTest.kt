package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.plan.Blocker
import com.mohdshayan.dutyclock.core.plan.HomePlanner
import com.mohdshayan.dutyclock.core.plan.PlanResult
import com.mohdshayan.dutyclock.core.plan.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blueprint flow 3: Andrei at Dover at 17:20 on Tuesday 13 October 2026, London time. */
class PlannerTest {
    private val z = LONDON

    /** Weekly rest to Monday 06:00; a 10-hour Monday; Tuesday from 10:10 as the blueprint tells it. */
    private fun andrei(): Scenario {
        val s = Scenario(at("2026-10-10T06:00", z), zone = z)
        s.rest(48 * 60)
        s.drive(270).rest(45).drive(270).rest(45).drive(60) // Monday: 10 hours of driving
        s.rest(16 * 60 + 40)                                // to Tuesday 10:10
        s.work(15).drive(105).rest(45).drive(170)          // 4 h 35 today, 2 h 50 since the break
        s.avail(95)                                        // waiting at the port until 17:20
        assertEquals(at("2026-10-13T17:20", z), s.end)
        return s
    }

    @Test
    fun `the drive home fits with one break`() {
        val s = andrei()
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(3, 40))
        r as PlanResult.Fits
        assertEquals(listOf(StepKind.DRIVE, StepKind.BREAK, StepKind.DRIVE), r.steps.map { it.kind })
        assertEquals(listOf(h(1, 40), m(45), h(2)), r.steps.map { it.length })
        assertEquals(at("2026-10-13T21:45", z), r.arrival)
    }

    @Test
    fun `a longer drive runs out of daily driving and offers the 10-hour day`() {
        val s = andrei()
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(4, 50))
        r as PlanResult.Blocked
        assertEquals(Blocker.DAILY_DRIVING, r.blocker)
        assertEquals(m(25), r.shortByMs)
        assertEquals("You run out of daily driving 25 minutes short.", r.message())
        assertEquals("A 10-hour day would cover it: 1 of 2 left.", r.fix)
    }

    @Test
    fun `with the 10-hour day chosen it fits before the rest deadline`() {
        val s = andrei()
        val ev = s.eval()
        val r = HomePlanner.plan(s.input(choices = DayChoices(tenHourDayFor = ev.limits.dayStart)), s.end, s.end, h(4, 50))
        assertTrue(r is PlanResult.Fits)
        assertTrue((r as PlanResult.Fits).arrival <= ev.limits.dailyRestLatestStart!!)
    }

    @Test
    fun `leaving later counts the wait as rest`() {
        val s = andrei()
        val r = HomePlanner.plan(s.input(), s.end, at("2026-10-13T18:05", z), h(3, 40))
        r as PlanResult.Fits
        assertEquals(StepKind.DRIVE, r.steps.first().kind)
        assertEquals("a 45-minute wait is the break", h(3, 40), r.steps.first().length)
    }

    /** 2 h 40 driven since the last full break, the split's 15 minutes taken, then resting from 02:16. */
    private fun splitUnderWay(restMin: Int): Scenario {
        val s = Scenario(at("2026-10-16T23:21"))
        s.rest(48 * 60)
        s.drive(150).rest(15).drive(10)
        assertEquals(at("2026-10-19T02:16"), s.end)
        return s.rest(restMin)
    }

    @Test
    fun `a split nearly complete is finished first when that gets home sooner`() {
        // The S22+ case: at 02:42 the second part is 26 of 30. Driving now meant 1:50, 0:30, 0:50 (arrive 05:52).
        val s = splitUnderWay(26)
        assertEquals(at("2026-10-19T02:42"), s.end)
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(2, 40))
        r as PlanResult.Fits
        assertEquals(listOf(StepKind.FINISH_BREAK to m(4), StepKind.DRIVE to h(2, 40)), r.steps.map { it.kind to it.length })
        assertEquals("Finish the break", r.steps.first().kind.label)
        assertEquals(s.end, r.steps.first().start)
        assertEquals(at("2026-10-19T05:26"), r.arrival)
    }

    @Test
    fun `finishing first is timed in whole minutes when now has seconds`() {
        val s = splitUnderWay(26)
        val now = s.end + 40_000
        val r = HomePlanner.plan(s.input(), now, now, h(2, 40))
        r as PlanResult.Fits
        assertTrue(r.steps.all { it.length % m(1) == 0L })
        assertEquals(StepKind.FINISH_BREAK to m(4), r.steps.first().kind to r.steps.first().length)
        assertEquals(now + m(4) + h(2, 40), r.arrival)
    }

    @Test
    fun `a break barely started is not finished first when the drive fits without one`() {
        val s = Scenario(at("2026-10-16T23:21")).rest(48 * 60).drive(160).rest(2)
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(1, 30))
        r as PlanResult.Fits
        assertEquals(listOf(StepKind.DRIVE to h(1, 30)), r.steps.map { it.kind to it.length })
        assertEquals(s.end + h(1, 30), r.arrival)
    }

    @Test
    fun `a daily rest nearly complete is finished first instead of running out of daily driving`() {
        // 9 hours driven today, then 8 h 50 of rest: driving now continues the same day, with nothing left.
        val s = Scenario(at("2026-10-12T06:00")).rest(48 * 60)
        s.drive(270).rest(45).drive(270)
        s.rest(8 * 60 + 50)
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(2))
        r as PlanResult.Fits
        assertEquals(listOf(StepKind.FINISH_DAILY_REST to m(10), StepKind.DRIVE to h(2)), r.steps.map { it.kind to it.length })
        assertEquals("Finish the daily rest", r.steps.first().kind.label)
        assertEquals(s.end + m(10) + h(2), r.arrival)
    }

    @Test
    fun `a rest far from complete is not finished first, the planner plans no daily rest`() {
        val s = Scenario(at("2026-10-12T06:00")).rest(48 * 60)
        s.drive(270).rest(45).drive(270)
        s.rest(5 * 60)
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(2))
        r as PlanResult.Blocked
        assertEquals(Blocker.DAILY_DRIVING, r.blocker)
    }

    @Test
    fun `no log means nothing to plan from`() {
        val empty = EngineInput(emptyList(), null, emptyList(), DayChoices(), RuleSet.EU, z)
        assertEquals(PlanResult.NeedsLog, HomePlanner.plan(empty, 0L, 0L, h(1)))
    }
}
