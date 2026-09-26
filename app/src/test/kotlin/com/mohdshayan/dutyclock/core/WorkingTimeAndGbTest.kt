package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.FindingKind
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.AbsenceKind
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.time.RtdPeriod
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Road Transport (Working Time) Regulations 2005. Sources: GOV.UK "Drivers' hours and
 * tachographs: goods vehicles", Annex 2; DfT "Road Transport (Working Time) Guidance" (2007),
 * sections 3.4 to 3.6, whose worked examples are reproduced here.
 */
class WorkingTimeTest {
    private val monday = at("2026-10-12T00:00")
    private fun fresh() = Scenario(monday - h(48)).rest(48 * 60)

    @Test
    fun `no more than 6 hours of work without a break`() {
        val s = fresh().work(360)
        assertEquals(0L, s.eval().limits.rtdBreakLeft)
        s.work(1).close(Mode.REST)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.RTD_BREAK_LATE })
    }

    @Test
    fun `30 minutes covers up to 9 hours of work, over 9 needs 45`() {
        val s = fresh().work(300).rest(30).work(239)
        val e = s.eval()
        assertEquals(m(1), e.limits.rtdBreakLeft)
        assertEquals(m(15), e.limits.rtdBreakNeeded)
        assertFalse(e.replay.findings.any { it.kind == FindingKind.RTD_BREAK_LATE })
        s.work(2).close(Mode.REST)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.RTD_BREAK_LATE })
    }

    @Test
    fun `availability is neither work nor a break`() {
        val s = fresh().work(300).avail(120).work(30)
        val e = s.eval()
        assertEquals(h(5, 30), e.replay.shiftWork)
        assertEquals(m(30), e.limits.rtdBreakLeft)
    }

    @Test
    fun `no more than 60 hours of work in a fixed week`() {
        val s = fresh()
        repeat(6) { s.work(300).rest(45).work(300).rest(13 * 60 + 15) }
        s.work(60).close(Mode.REST)
        val e = s.eval()
        assertEquals(-h(1), e.limits.rtdWeekLeft)
        assertTrue(e.replay.findings.any { it.kind == FindingKind.RTD_60_OVER })
    }

    /** DfT guidance 3.6 option 1: the 2007/08 fixed periods, 18, 17 and 18 weeks. */
    @Test
    fun `fixed reference periods start on the Monday on or after 1 April, 1 August and 1 December`() {
        fun date(p: RtdPeriod) = p.startDate.toString() + ".." + p.lastDate + "/" + p.weeks
        assertEquals("2007-04-02..2007-08-05/18", date(RtdPeriod.containing(at("2007-05-15T12:00"))))
        assertEquals("2007-08-06..2007-12-02/17", date(RtdPeriod.containing(at("2007-08-06T00:00"))))
        assertEquals("2007-12-03..2008-04-06/18", date(RtdPeriod.containing(at("2008-01-10T12:00"))))
        assertEquals("2026-08-03..2026-12-06/18", date(RtdPeriod.containing(at("2026-10-12T00:00"))))
        assertEquals(48 * 60 * 17, RtdPeriod.containing(at("2007-09-01T00:00")).limitMin)
        assertEquals(48 * 60 * 18, RtdPeriod.containing(at("2026-10-12T00:00")).limitMin)
    }

    /** DfT guidance 3.4 example 1: 17 weeks of 40 hours plus 10 weeks of 12 hours overtime is 800. */
    @Test
    fun `the 48-hour average allows 816 hours in a 17-week period`() {
        val t = at("2007-11-26T08:00")
        val seed = Seed(seededAtUtc = t, thisWeekWorkMin = 40 * 60, rtdPeriodWorkMin = (800 - 40) * 60)
        val e = RuleEngine.evaluate(Scenario(t).input(seed = seed), t)
        assertEquals(h(800), e.limits.rtdPeriodTotal)
        assertEquals(h(16), e.limits.rtdPeriodLeft)
        assertTrue(e.counters.any { it.kind == CounterKind.RTD_AVERAGE && !it.over })
    }

    /** DfT guidance 3.5 example 2: two weeks of leave add 96 hours and five days add 40. */
    @Test
    fun `leave is credited at 8 hours a day and 48 hours a week`() {
        val p = RtdPeriod.containing(at("2026-10-12T00:00"))
        val twoWeeks = (0 until 14).map { Absence(LocalDate.of(2026, 10, 12).plusDays(it.toLong()).toString(), AbsenceKind.ANNUAL_LEAVE) }
        val fiveDays = (0 until 5).map { Absence(LocalDate.of(2026, 11, 2).plusDays(it.toLong()).toString(), AbsenceKind.SICK) }
        assertEquals(h(136), RuleEngine.leaveCredit(twoWeeks + fiveDays, p))
        val outside = Absence("2026-07-01", AbsenceKind.ANNUAL_LEAVE)
        assertEquals(0L, RuleEngine.leaveCredit(listOf(outside), p))
        assertEquals(ZoneOffset.UTC.id, "Z")
    }
}

/** GB domestic goods rules. Source: GOV.UK drivers' hours guide, section 2, and gov.uk/drivers-hours/gb-domestic-rules. */
class GbDomesticTest {
    private val t0 = at("2026-10-13T06:00")

    @Test
    fun `11 hours of duty on a day you drive`() {
        val s = Scenario(t0, RuleSet.GB_GOODS).work(240).drive(420).work(30).close(Mode.REST)
        val e = s.eval()
        assertEquals(-m(30), e.limits.gbDutyLeft)
        assertTrue(e.replay.findings.any { it.kind == FindingKind.GB_DUTY_OVER })
        assertEquals(listOf(CounterKind.GB_DUTY, CounterKind.GB_DRIVING), e.counters.map { it.kind }.take(2))
    }

    @Test
    fun `the duty limit does not apply on a day without driving`() {
        val s = Scenario(t0, RuleSet.GB_GOODS).work(12 * 60).close(Mode.REST)
        assertFalse(s.eval().replay.findings.any { it.kind == FindingKind.GB_DUTY_OVER })
    }

    @Test
    fun `10 hours of driving in the 24 hours from the start of duty`() {
        val s = Scenario(t0, RuleSet.GB_GOODS).drive(300).rest(10 * 60).drive(301).close(Mode.REST)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.GB_DRIVING_OVER })
        val nextDay = Scenario(t0, RuleSet.GB_GOODS).drive(300).rest(20 * 60).drive(301).close(Mode.REST)
        assertFalse(nextDay.eval().replay.findings.any { it.kind == FindingKind.GB_DRIVING_OVER })
    }
}
