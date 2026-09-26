package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.FindingKind
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Breaks from driving. Source: GOV.UK "Drivers' hours and tachographs: goods vehicles" (GV262),
 * section 1.4, and Regulation 561/2006 Article 7: a 45-minute break after no more than 4.5 hours
 * of driving, or 15 minutes then 30 minutes; the second part must be at least 30; breaks under
 * 15 minutes count for nothing.
 */
class BreakRulesTest {
    private val t0 = at("2026-10-13T06:00")

    @Test
    fun `4h30 then 45 minutes wipes the slate clean`() {
        val s = Scenario(t0).work(15).drive(270).rest(45).close(Mode.WORK)
        val e = s.eval()
        assertEquals(h(4, 30), e.limits.breakLeft)
        assertFalse(e.replay.findings.any { it.kind == FindingKind.BREAK_LATE })
    }

    @Test
    fun `15 then 30 resets the 4h30 clock`() {
        val s = Scenario(t0).drive(120).rest(15).drive(120).rest(30).close(Mode.WORK)
        assertEquals(h(4, 30), s.eval().limits.breakLeft)
    }

    @Test
    fun `30 then 15 does not reset, and neither does other work`() {
        val wrongOrder = Scenario(t0).drive(120).rest(30).drive(120).rest(15).close(Mode.WORK)
        assertEquals(m(30), wrongOrder.eval().limits.breakLeft)

        val work = Scenario(t0).drive(120).work(45).drive(60).close(Mode.WORK)
        assertEquals(m(90), work.eval().limits.breakLeft)
    }

    @Test
    fun `a break under 15 minutes counts for nothing`() {
        val s = Scenario(t0).drive(60).rest(14).drive(60).rest(30).close(Mode.WORK)
        assertEquals(h(2, 30), s.eval().limits.breakLeft)
    }

    @Test
    fun `driving past 4h30 without a break is found at the minute it happened`() {
        val s = Scenario(t0).drive(275).close(Mode.REST)
        val f = s.eval().replay.findings.single { it.kind == FindingKind.BREAK_LATE }
        assertEquals(t0 + h(4, 30), f.at)
    }

    /** Blueprint flow 2, Gemma's split break, and the edit that moves it. */
    @Test
    fun `split break flow with an edited break start`() {
        val z = LONDON
        val entries = mutableListOf(
            Entry(1, at("2026-10-13T06:30", z), Mode.DRIVE, RuleSet.EU),
            Entry(2, at("2026-10-13T08:40", z), Mode.REST, RuleSet.EU),
        )
        val s = Scenario(at("2026-10-13T06:30", z), zone = z)
        s.entries += entries
        val at0855 = s.eval(now = at("2026-10-13T08:55", z))
        val breakRow = at0855.counters.single { it.kind == CounterKind.BREAK }
        assertTrue(breakRow.detail, breakRow.detail.startsWith("15 of 45 taken"))

        s.entries += Entry(3, at("2026-10-13T08:56", z), Mode.DRIVE, RuleSet.EU)
        s.entries += Entry(4, at("2026-10-13T11:05", z), Mode.REST, RuleSet.EU)
        val at1135 = s.eval(now = at("2026-10-13T11:35", z))
        assertEquals(h(4, 30), at1135.limits.breakLeft)
        assertEquals("Split break complete.", at1135.counters.single { it.kind == CounterKind.BREAK }.detail)

        // Move the 08:40 tap to 08:35: 2 h 05 before the break, 2 h 09 after, still reset at 11:35.
        s.entries[1] = Entry(2, at("2026-10-13T08:35", z), Mode.REST, RuleSet.EU, edited = true)
        val moved = s.eval(now = at("2026-10-13T11:35", z))
        assertEquals(h(4, 30), moved.limits.breakLeft)
        assertEquals(h(4, 14), moved.limits.drivingToday)
    }
}
