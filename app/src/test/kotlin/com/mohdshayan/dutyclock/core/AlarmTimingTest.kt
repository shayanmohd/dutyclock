package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.AlarmKind
import com.mohdshayan.dutyclock.core.engine.AlarmShot
import com.mohdshayan.dutyclock.core.engine.AlarmTiming
import com.mohdshayan.dutyclock.core.engine.AlertAccess
import com.mohdshayan.dutyclock.core.engine.AlertAccess.Step
import com.mohdshayan.dutyclock.core.engine.AlertPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 3: which alarm each alert gets, and when the app asks for access (plan tests 23 to 27). */
class AlarmTimingTest {
    private val now = at("2026-10-15T11:00")
    private val due: Long = now + h(2)

    @Test
    fun `with exact access the limit is an alarm clock and the lead is exact while idle`() {
        assertEquals(AlarmShot(AlarmKind.CLOCK, due, true), AlarmTiming.plan("BREAK-limit", due, now, true))
        assertEquals(AlarmShot(AlarmKind.CLOCK, due, true), AlarmTiming.plan("DAILY_DRIVING-limit2", due, now, true))
        assertEquals(AlarmShot(AlarmKind.CLOCK, due, true), AlarmTiming.plan("test", due, now, true))
        assertEquals(AlarmShot(AlarmKind.EXACT_IDLE, due, true), AlarmTiming.plan("BREAK-lead", due, now, true))
        assertEquals(AlarmShot(AlarmKind.EXACT_IDLE, due, true), AlarmTiming.plan("DAILY_REST-lead2", due, now, true))
        assertEquals(AlarmShot(AlarmKind.EXACT_IDLE, due, true), AlarmTiming.plan("break-complete", due, now, true))
    }

    @Test
    fun `without exact access a far alert hops halfway`() {
        assertEquals(AlarmShot(AlarmKind.INEXACT, h(1), false), AlarmTiming.plan("BREAK-limit", h(2), 0, false))
    }

    @Test
    fun `without exact access the final window ends at the limit`() {
        val six = AlarmTiming.plan("BREAK-limit", m(6), 0, false)
        assertEquals(AlarmShot(AlarmKind.INEXACT, 205_714, true), six)
        // Android's inexact window is three quarters of the delay.
        for (limit in listOf(m(6), m(1), 10_000L)) {
            val shot = AlarmTiming.plan("BREAK-limit", limit, 0, false)
            assertTrue(shot.final)
            assertTrue("$limit", shot.triggerAt + 0.75 * shot.triggerAt <= limit)
            assertTrue(shot.triggerAt > 0)
        }
    }

    @Test
    fun `the final alarm posts even when it lands early`() {
        assertTrue(AlarmTiming.shouldPost(true, due, due - 150_000))
        assertFalse(AlarmTiming.shouldPost(false, due, due - 180_000))
        assertTrue(AlarmTiming.shouldPost(false, due, due - 30_000))
    }

    @Test
    fun `an alert already posted is not armed again`() {
        val e = Scenario(at("2026-10-13T06:00")).work(10).drive(1).eval()
        val fresh = AlertPlan.plan(e, 15, true, max = 3)
        assertEquals("BREAK-lead", fresh.first().key)
        val lead = fresh.first()
        val after = AlertPlan.plan(e, 15, true, max = 3, posted = setOf("BREAK-lead@${lead.at}"))
        assertEquals(3, after.size)
        assertTrue(after.none { it.key == "BREAK-lead" })
        assertEquals("BREAK-limit", after.first().key)
    }

    @Test
    fun `first tap asks for notifications, then exact alerts`() {
        assertEquals(Step.ASK_NOTIFICATIONS, AlertAccess.first(36, false, false))
        assertEquals(Step.EXPLAIN_EXACT, AlertAccess.first(36, true, false))
        assertEquals(Step.NONE, AlertAccess.first(36, true, true))
        assertEquals(Step.EXPLAIN_EXACT, AlertAccess.first(32, false, false))
        assertEquals(Step.NONE, AlertAccess.first(30, true, true))
        assertEquals(Step.EXPLAIN_EXACT, AlertAccess.afterPrompt(36, true, false))
        assertEquals(Step.NONE, AlertAccess.afterPrompt(36, false, false))
        assertEquals(Step.NONE, AlertAccess.afterPrompt(36, true, true))
    }
}
