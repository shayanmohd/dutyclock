package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.DailyRestKind
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.Evaluation
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 5: the regular and the longer legal limit together (plan tests 28 to 37b). Worked case from
 * the GOV.UK drivers' hours guide, section 1.5 (daily rest within 24 hours of the end of the last
 * rest): work starts Thursday 06:00, so a regular 11-hour rest must start by 19:00 and a reduced
 * 9-hour rest by 21:00. Section 1.4: 9 hours of driving a day, 10 hours twice a fixed week.
 */
class DailyDeadlinesTest {
    private fun t(hm: String, day: String = "2026-10-15") = at("${day}T$hm")
    private fun eval(entries: List<Entry>, seed: Seed, now: Long): Evaluation =
        RuleEngine.evaluate(EngineInput(entries, seed, emptyList(), DayChoices(), RuleSet.EU, UTC), now)

    private val restSeed = Seed(
        seededAtUtc = t("06:00"), dayStartUtc = t("06:00"), lastWeeklyRestEndUtc = at("2026-10-12T00:00"), lastWeeklyRestMin = 2700,
        thisWeekDrivingMin = 1200, lastWeekDrivingMin = 2400,
    )
    private val work = listOf(Entry(1, t("06:00"), Mode.WORK, RuleSet.EU))
    private fun rest(e: Evaluation) = e.counters.first { it.kind == CounterKind.DAILY_REST }

    @Test
    fun `daily rest shows 19 00 and 21 00 together`() {
        val c = rest(eval(work, restSeed, t("18:00")))
        assertEquals("19:00", c.value)
        assertEquals(h(1), c.remainingMs)
        assertFalse(c.over)
        assertEquals(t("21:00"), c.fallbackAt)
        assertEquals("Regular 11 hours by 19:00. Reduced 9 hours by 21:00: 3 of 3 left.", c.detail)
        assertTrue(c.working.contains("Reduced rest latest start: Thu 21:00"))
    }

    @Test
    fun `at 19 15 a legal reduced rest remains, so it is not Over`() {
        val c = rest(eval(work, restSeed, t("19:15")))
        assertEquals("21:00", c.value)
        assertEquals(h(1, 45), c.remainingMs)
        assertFalse(c.over)
        assertEquals("Too late to start 11 hours. A reduced 9-hour rest must start by 21:00: 3 of 3 left.", c.detail)
        assertEquals("Take a reduced rest: 3 of 3 left", c.offerLabel)
    }

    @Test
    fun `at 21 01 it is Over`() {
        val c = rest(eval(work, restSeed, t("21:01")))
        assertTrue(c.over)
        assertEquals(-m(1), c.remainingMs)
    }

    @Test
    fun `with no reduced rests left, 19 15 is Over`() {
        val c = rest(eval(work, restSeed.copy(reducedDailyRestsUsed = 3), t("19:15")))
        assertTrue(c.over)
        assertEquals(-m(15), c.remainingMs)
        assertEquals("11 hours within 24 of 06:00. No reduced rests left.", c.detail)
    }

    @Test
    fun `a rest started at 19 30 is a reduced rest under way`() {
        val entries = work + Entry(2, t("19:30"), Mode.REST, RuleSet.EU)
        val c = rest(eval(entries, restSeed, t("20:00")))
        assertTrue(c.met)
        assertFalse(c.over)
        assertEquals(h(8, 30), c.remainingMs)
        assertEquals("Rest under way. 9 h complete at Fri 04:30.", c.detail)
        val late = rest(eval(entries, restSeed, t("04:40", "2026-10-16")))
        assertEquals("9:10", late.value)
        assertTrue(late.met)
        assertFalse(late.over)
        assertEquals(h(1, 50), late.remainingMs)
        assertEquals("Reduced rest reached: 9:10. It began after 19:00, so it counts as reduced.", late.detail)
        val next = eval(entries + Entry(3, t("05:00", "2026-10-16"), Mode.WORK, RuleSet.EU), restSeed, t("05:01", "2026-10-16"))
        assertEquals(DailyRestKind.REDUCED, next.replay.dailyRests.last().kind)
        assertEquals(2, next.limits.reducedRestsLeft)
    }

    @Test
    fun `a rest started before 19 00 keeps the 1_0_0 text`() {
        val entries = work + Entry(2, t("18:30"), Mode.REST, RuleSet.EU)
        val c = rest(eval(entries, restSeed, t("03:40", "2026-10-16")))
        assertEquals("Reduced rest reached. Regular at 05:30.", c.detail)
    }

    @Test
    fun `the 19 00 alert says a reduced rest can start until 21 00`() {
        val alerts = AlertPlan.plan(eval(work, restSeed, t("18:00")), 15, true, max = 10).associateBy { it.key }
        assertEquals(t("18:45"), alerts["DAILY_REST-lead"]!!.at)
        assertEquals("A regular 11-hour rest must start in 15 minutes. A reduced 9-hour rest can start until 21:00.", alerts["DAILY_REST-lead"]!!.text)
        assertEquals(t("19:00"), alerts["DAILY_REST-limit"]!!.at)
        assertEquals("Start now for a regular 11-hour rest. A reduced 9-hour rest can start until 21:00.", alerts["DAILY_REST-limit"]!!.text)
        assertEquals(t("20:45"), alerts["DAILY_REST-lead2"]!!.at)
        assertEquals("Daily rest must start in 15 minutes.", alerts["DAILY_REST-lead2"]!!.text)
        assertEquals(t("21:00"), alerts["DAILY_REST-limit2"]!!.at)
        assertEquals("Daily rest must start now.", alerts["DAILY_REST-limit2"]!!.text)
    }

    /** 8 hours driven since 05:00, a break just taken, driving again from 14:00. */
    private val driveSeed = Seed(
        seededAtUtc = t("14:00"), dayStartUtc = t("05:00"), drivingTodayMin = 480, drivingSinceBreakMin = 0,
        thisWeekDrivingMin = 1200, lastWeekDrivingMin = 2400, lastWeeklyRestEndUtc = at("2026-10-12T00:00"), lastWeeklyRestMin = 2700,
    )
    private val drive = listOf(Entry(1, t("14:00"), Mode.DRIVE, RuleSet.EU))
    private fun daily(e: Evaluation) = e.counters.first { it.kind == CounterKind.DAILY_DRIVING }

    @Test
    fun `daily driving shows 9 00 and the 10-hour day together`() {
        val c = daily(eval(drive, driveSeed, t("14:00")))
        assertEquals(h(1), c.remainingMs)
        assertEquals(t("16:00"), c.fallbackAt)
        assertFalse(c.over)
        assertEquals("8:00 of 9:00 driven since your last daily rest. A 10-hour day adds 1:00: 2 of 2 left.", c.detail)
    }

    @Test
    fun `past 9 hours a 10-hour day left is used, never Over`() {
        val e = eval(drive, driveSeed, t("15:20"))
        val c = daily(e)
        assertFalse(c.over)
        assertEquals(m(40), c.remainingMs)
        assertEquals(h(10), e.limits.dayLimit)
        assertNull(c.fallbackAt)
        assertEquals("9:20 of 10:00 driven since your last daily rest. 10-hour day in use: 1 of 2 left after today.", c.detail)
    }

    @Test
    fun `with both 10-hour days used, 9 01 is Over`() {
        val c = daily(eval(drive, driveSeed.copy(tenHourDaysUsed = 2), t("15:01")))
        assertTrue(c.over)
        assertEquals(-m(1), c.remainingMs)
    }

    @Test
    fun `the 9-hour alert says a 10-hour day is left`() {
        val alerts = AlertPlan.plan(eval(drive, driveSeed, t("14:00")), 15, true, max = 10).associateBy { it.key }
        assertEquals(t("14:45"), alerts["DAILY_DRIVING-lead"]!!.at)
        assertEquals("9 hours of driving in 15 minutes. A 10-hour day allows 1 more hour: 2 of 2 left.", alerts["DAILY_DRIVING-lead"]!!.text)
        assertEquals(t("15:00"), alerts["DAILY_DRIVING-limit"]!!.at)
        assertEquals("9 hours of driving reached. A 10-hour day allows 1 more hour: 2 of 2 left.", alerts["DAILY_DRIVING-limit"]!!.text)
        assertEquals(t("15:45"), alerts["DAILY_DRIVING-lead2"]!!.at)
        assertEquals("Daily driving ends in 15 minutes.", alerts["DAILY_DRIVING-lead2"]!!.text)
        assertEquals(t("16:00"), alerts["DAILY_DRIVING-limit2"]!!.at)
        assertEquals("Daily driving limit reached.", alerts["DAILY_DRIVING-limit2"]!!.text)
        val hour = AlertPlan.plan(eval(drive, driveSeed, t("14:00")), 60, true, max = 10).map { it.key }
        assertFalse(hour.contains("DAILY_DRIVING-lead2"))
        assertTrue(hour.contains("DAILY_DRIVING-limit2"))
    }

    @Test
    fun `exactly at 9 00 the 10-hour alerts are still planned`() {
        val e = eval(drive, driveSeed, t("15:00"))
        assertNull(daily(e).limitAt)
        val alerts = AlertPlan.plan(e, 15, true, max = 10).associateBy { it.key }
        assertEquals(t("15:45"), alerts["DAILY_DRIVING-lead2"]!!.at)
        assertEquals(t("16:00"), alerts["DAILY_DRIVING-limit2"]!!.at)
    }
}
