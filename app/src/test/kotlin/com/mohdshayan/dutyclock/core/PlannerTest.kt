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

    @Test
    fun `no log means nothing to plan from`() {
        val empty = EngineInput(emptyList(), null, emptyList(), DayChoices(), RuleSet.EU, z)
        assertEquals(PlanResult.NeedsLog, HomePlanner.plan(empty, 0L, 0L, h(1)))
    }
}
