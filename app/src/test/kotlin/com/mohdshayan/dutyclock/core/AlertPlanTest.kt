package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.Durations
import com.mohdshayan.dutyclock.core.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlanTest {
    private val t0 = at("2026-10-13T06:00")

    /** Blueprint flow 1: driving from 06:00, the countdown starts at 4:30 and warns at 4:15. */
    @Test
    fun `first alert is the break warning 15 minutes before 4h30 of driving`() {
        val s = Scenario(t0).work(10).drive(1)
        val alerts = AlertPlan.plan(s.eval(), leadMin = 15, breakComplete = true)
        assertEquals(3, alerts.size)
        val first = alerts.first()
        assertEquals("Break due in 15 minutes.", first.text)
        assertEquals(s.end - m(1) + h(4, 15), first.at)
        assertEquals("Break due now. Stop when it is safe to.", alerts[1].text)
        assertTrue(alerts.zipWithNext().all { (a, b) -> a.at <= b.at })
    }

    @Test
    fun `resting arms break complete, and nothing runs out while parked`() {
        val s = Scenario(t0).drive(200).rest(10)
        val alerts = AlertPlan.plan(s.eval(), leadMin = 15, breakComplete = true)
        val bc = alerts.first { it.key == "break-complete" }
        assertEquals(t0 + h(3, 20) + m(45), bc.at)
        assertTrue(alerts.none { it.key.startsWith("BREAK") })
        assertTrue(s.eval().mode == Mode.REST)
    }

    @Test
    fun `durations read the way drivers type them`() {
        assertEquals(h(3, 40), Durations.parse("3:40"))
        assertEquals(h(3, 40), Durations.parse(" 3.40 "))
        assertEquals(h(9), Durations.parse("9"))
        assertEquals(m(45), Durations.parse("0:45"))
        assertNull(Durations.parse("3:75"))
        assertNull(Durations.parse("three"))
        assertNull(Durations.parse(""))
    }
}
