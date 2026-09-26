package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.FindingKind
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.time.FixedWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weekly rest, compensation, 56 and 90. Source: GOV.UK "Drivers' hours and tachographs: goods
 * vehicles" (GV262), sections 1.4 and 1.6; Regulation 561/2006 Articles 4(i), 6(2), 6(3), 8(6).
 */
class WeeklyRulesTest {
    private val monday = at("2026-09-28T00:00")

    @Test
    fun `weekly rest must start within six 24-hour periods of the last one`() {
        val s = Scenario(monday - h(48)).rest(48 * 60).drive(240).close(Mode.WORK)
        assertEquals(monday + 6 * 24 * h(1), s.eval().limits.weeklyRestLatestStart)
    }

    /** GOV.UK 1.6 worked example: a 33-hour weekly rest in week 1 owes 12 hours by the end of week 4. */
    @Test
    fun `a reduced weekly rest owes the difference by the end of the third following week`() {
        val s = Scenario(monday - h(48)).rest(48 * 60).work(8 * 60).rest(33 * 60).drive(60).close(Mode.WORK)
        val debt = s.eval().replay.debts.single()
        assertEquals(h(12), debt.owedMs)
        assertEquals(FixedWeek.start(monday) + 4 * WEEK_MS, debt.dueUtc)
    }

    /** Blueprint persona Marek: a 24-hour weekly rest in the week of 28 September. */
    @Test
    fun `owed 21 hours shows its Sunday deadline`() {
        val s = Scenario(monday - h(48)).rest(48 * 60).work(8 * 60).rest(24 * 60).drive(60).close(Mode.WORK)
        val row = s.eval().counters.single { it.kind == CounterKind.COMPENSATION }
        assertEquals("Owed 21 h, attach to a rest by the end of Sunday 25 October.", row.detail)
    }

    @Test
    fun `compensation is paid only by a rest that holds it on top of a full rest`() {
        val base = { Scenario(monday - h(48)).rest(48 * 60).work(8 * 60).rest(33 * 60).drive(240) }
        val short = base().rest(20 * 60).drive(60).close(Mode.WORK)
        assertEquals("8 hours plus 12 does not pay it", 1, short.eval().replay.debts.size)
        val full = base().rest(23 * 60).drive(60).close(Mode.WORK)
        assertTrue("11 hours plus 12 pays it", full.eval().replay.debts.isEmpty())
    }

    @Test
    fun `two reduced weekly rests in a row are found`() {
        val s = Scenario(monday - h(48)).rest(48 * 60).work(8 * 60).rest(24 * 60).work(8 * 60).rest(30 * 60).close(Mode.WORK)
        val e = s.eval()
        assertTrue(e.replay.findings.any { it.kind == FindingKind.TWO_REDUCED_WEEKLY })
        assertEquals(h(45), e.limits.weeklyRestRequired)
    }

    @Test
    fun `a weekly rest spanning two fixed weeks is counted once`() {
        val s = Scenario(monday - h(20)).rest(46 * 60).drive(60).close(Mode.WORK)
        assertEquals(1, s.eval().replay.weeklyRests.size)
    }

    /** Week boundaries are Monday 00:00 UTC. In British Summer Time that is Monday 01:00 in London. */
    @Test
    fun `56 and 90 split at the UTC week boundary across a DST change`() {
        val sunday = at("2026-10-04T23:30", LONDON) // 22:30 UTC, still the week of 28 September
        val s = Scenario(sunday, zone = LONDON).drive(120).close(Mode.REST)
        val e = s.eval()
        val wk = FixedWeek.start(at("2026-10-05T12:00"))
        assertEquals(h(1, 30), e.replay.weekDriving[wk - WEEK_MS])
        assertEquals(h(0, 30), e.replay.weekDriving[wk])
        assertEquals(h(56) - h(0, 30), e.limits.weekLeft)
        assertEquals(h(90) - h(2), e.limits.fortnightLeft)

        // After the clocks go back on 25 October, UTC and London agree again.
        assertEquals(at("2026-10-26T00:00", LONDON), FixedWeek.start(at("2026-10-26T09:00", LONDON)))
    }

    @Test
    fun `going over 56 in a week and 90 in two weeks is found`() {
        val s = Scenario(monday - h(48)).rest(48 * 60)
        repeat(7) { s.drive(270).rest(45).drive(270).rest(12 * 60 + 15) }
        s.close(Mode.WORK)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.WEEK_56_OVER })
    }

    /** Mixed weeks: GB domestic driving is counted toward the EU 56 and 90, which can only overcount. */
    @Test
    fun `GB domestic driving counts toward the EU weekly totals`() {
        val s = Scenario(monday - h(48)).rest(48 * 60)
        s.then(Mode.DRIVE, 300, RuleSet.GB_GOODS).rest(12 * 60).drive(120).close(Mode.WORK)
        assertEquals(h(56) - h(7), s.eval().limits.weekLeft)
    }
}
