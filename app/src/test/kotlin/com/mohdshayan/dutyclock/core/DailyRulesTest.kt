package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.DailyRestKind
import com.mohdshayan.dutyclock.core.engine.FindingKind
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Daily driving and daily rest. Source: GOV.UK "Drivers' hours and tachographs: goods vehicles"
 * (GV262), sections 1.4 and 1.5; Regulation 561/2006 Articles 6(1) and 8.
 */
class DailyRulesTest {
    /** Monday 12 October 2026 at 00:00 UTC; the week before is closed by a regular weekly rest. */
    private val monday = at("2026-10-12T00:00")

    private fun week() = Scenario(monday - h(50)).rest(50 * 60)

    /** One working day: 4h30, 45 break, then the rest of [driveMin], then a rest of [restMin]. */
    private fun Scenario.day(driveMin: Int, restMin: Int): Scenario {
        drive(minOf(270, driveMin)); rest(45)
        if (driveMin > 270) drive(driveMin - 270)
        return rest(restMin)
    }

    @Test
    fun `9 hours a day, and a 10-hour day is only a choice`() {
        val s = week().rest(6 * 60).drive(270).rest(45).drive(240).close(Mode.WORK)
        val e = s.eval()
        assertEquals(h(9), e.limits.dayLimit)
        assertEquals(h(0, 30), e.limits.dailyDrivingLeft)
        val chosen = s.eval(choices = DayChoices(tenHourDayFor = e.limits.dayStart))
        assertEquals(h(1, 30), chosen.limits.dailyDrivingLeft)
        assertEquals(1, chosen.limits.tenHourDaysLeft)
    }

    @Test
    fun `a third 10-hour day in a fixed week is refused and found`() {
        val s = week().rest(6 * 60).day(600, 11 * 60).day(600, 11 * 60)
        s.drive(270).rest(45).drive(240).close(Mode.WORK)
        val e = s.eval()
        assertEquals(0, e.limits.tenHourDaysLeft)
        val chosen = s.eval(choices = DayChoices(tenHourDayFor = e.limits.dayStart))
        assertEquals("the 10-hour choice is refused", h(9), chosen.limits.dayLimit)
        val daily = chosen.counters.single { it.kind == CounterKind.DAILY_DRIVING }
        assertNull(daily.offerLabel)

        s.drive(60).close(Mode.REST).rest(11 * 60).close(Mode.WORK)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.THIRD_TEN_HOUR_DAY })
    }

    @Test
    fun `daily rest must start by 24 hours minus 11, or minus 9 when reduced`() {
        val start = monday + h(6)
        val s = week().rest(6 * 60).drive(120).close(Mode.WORK)
        val e = s.eval()
        assertEquals(start, e.limits.dayStart)
        assertEquals(start + h(13), e.limits.dailyRestLatestStart)
        val reduced = s.eval(choices = DayChoices(reducedRestFor = start))
        assertEquals(start + h(15), reduced.limits.dailyRestLatestStart)
    }

    @Test
    fun `split rest 3 then 9 is regular, 9 then 3 is reduced`() {
        val split = week().rest(6 * 60).drive(240).rest(180).drive(240).work(60).rest(9 * 60).close(Mode.WORK)
        val a = split.eval().replay
        assertEquals(DailyRestKind.SPLIT, a.dailyRests.last().kind)
        assertEquals(0, a.reducedCount)

        val wrong = week().rest(6 * 60).drive(240).rest(9 * 60).drive(60).rest(180).close(Mode.WORK)
        val b = wrong.eval().replay
        assertEquals(DailyRestKind.REDUCED, b.dailyRests.first().kind)
        assertEquals(1, b.reducedCount)
    }

    @Test
    fun `a fourth reduced daily rest between weekly rests is found`() {
        val s = week().rest(6 * 60)
        repeat(4) { s.day(480, 9 * 60) }
        s.close(Mode.WORK)
        val e = s.eval()
        assertTrue(e.replay.findings.any { it.kind == FindingKind.FOURTH_REDUCED_REST })
        assertEquals(0, e.limits.reducedRestsLeft)
    }

    @Test
    fun `a rest started too late for the 24-hour period is found`() {
        val s = week().rest(6 * 60).drive(270).rest(45).drive(270).work(14 * 60 + 30).rest(11 * 60).close(Mode.WORK)
        assertTrue(s.eval().replay.findings.any { it.kind == FindingKind.DAILY_REST_LATE })
    }
}
