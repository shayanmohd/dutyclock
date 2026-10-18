package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.CounterKind
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.Offer
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.plan.Blocker
import com.mohdshayan.dutyclock.core.plan.HomePlanner
import com.mohdshayan.dutyclock.core.plan.PlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 1(e): after "Not now", what the log cannot know says Unknown instead of a made-up number (plan tests 16 to 21). */
class UnknownTest {
    private val unknownKinds = setOf(CounterKind.WEEKLY_REST, CounterKind.WEEK_56, CounterKind.FORTNIGHT_90, CounterKind.RTD_WEEK_60, CounterKind.RTD_AVERAGE)

    /** Test 1's seed: Rest since 11:00 on Thursday, read at 11:20. */
    private val seed = Seed(
        seededAtUtc = at("2026-10-15T11:00"), readAtUtc = at("2026-10-15T11:20"), dayStartUtc = at("2026-10-15T06:00"),
        drivingSinceBreakMin = 240, drivingTodayMin = 240, thisWeekDrivingMin = 1800, lastWeekDrivingMin = 2400,
        lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
    )

    @Test
    fun `after Not now the weekly rest deadline is Unknown, not a week out`() {
        val e = Scenario(at("2026-10-15T11:14")).work(30).drive(120).eval()
        for (k in unknownKinds) {
            val c = e.counters.first { it.kind == k }
            assertTrue("$k", c.unknown)
            assertEquals("Unknown", c.value)
            assertEquals("Unknown, bring your week in", c.detail)
            assertNull(c.limitAt)
            assertFalse(c.over)
            assertEquals(Offer.BRING_WEEK_IN, c.offer)
            assertEquals("Bring your week in", c.offerLabel)
        }
        assertEquals(
            "No weekly rest logged or brought in yet, so the deadline is unknown",
            e.counters.first { it.kind == CounterKind.WEEKLY_REST }.working.first(),
        )
        assertEquals(
            "Your log starts Thu 11:14, after this week began on Monday 00:00 UTC",
            e.counters.first { it.kind == CounterKind.WEEK_56 }.working.first(),
        )
        assertEquals(
            "Your log starts Thu 11:14, after last week began on Monday 00:00 UTC",
            e.counters.first { it.kind == CounterKind.FORTNIGHT_90 }.working.first(),
        )
        assertEquals(
            "Your log starts Thu 11:14, after this reference period began on 3 Aug",
            e.counters.first { it.kind == CounterKind.RTD_AVERAGE }.working.first(),
        )
        // The sheet never lists the made-up anchor or a logged-only total as if it were known.
        val made = mapOf(
            CounterKind.WEEKLY_REST to listOf("Counted from your first logged duty", "Last weekly rest ended", "Latest start"),
            CounterKind.WEEK_56 to listOf("Driving since Monday"),
            CounterKind.FORTNIGHT_90 to listOf("This week:", "Last week:"),
            CounterKind.RTD_WEEK_60 to listOf("Driving and other work since Monday"),
            CounterKind.RTD_AVERAGE to listOf("Worked plus leave credit", "Leave credited"),
        )
        for ((k, bad) in made) {
            val lines = e.counters.first { it.kind == k }.working
            assertTrue("$k: $lines", lines.none { l -> bad.any { l.startsWith(it) } })
        }
        assertEquals(
            "Weekly rest must start within six 24-hour periods of the end of the last one",
            e.counters.first { it.kind == CounterKind.WEEKLY_REST }.working[1],
        )
        val keys = AlertPlan.plan(e, 15, true, max = 10).map { it.key }
        assertTrue(keys.none { k -> listOf("WEEKLY_REST", "WEEK_56", "FORTNIGHT_90", "RTD_WEEK_60").any { k.startsWith(it) } })
        assertEquals(CounterKind.BREAK, e.counters.first { !it.unknown }.kind)
        assertEquals(unknownKinds, e.counters.takeLast(5).map { it.kind }.toSet())
    }

    @Test
    fun `a log from before Monday knows this week, from the Monday before knows the fortnight`() {
        val one = Scenario(at("2026-10-11T20:00")).rest(14 * 60).work(30).drive(60).eval()
        assertFalse(one.counters.first { it.kind == CounterKind.WEEK_56 }.unknown)
        assertTrue(one.counters.first { it.kind == CounterKind.FORTNIGHT_90 }.unknown)
        val two = Scenario(at("2026-10-04T20:00")).rest(7 * 24 * 60 + 14 * 60).work(30).drive(60)
        assertEquals(at("2026-10-12T11:30"), two.end)
        val e = two.eval()
        assertFalse(e.counters.first { it.kind == CounterKind.WEEK_56 }.unknown)
        assertFalse(e.counters.first { it.kind == CounterKind.FORTNIGHT_90 }.unknown)
    }

    @Test
    fun `a logged weekly rest makes the weekly rest deadline known`() {
        val monday = at("2026-10-12T00:00")
        val e = Scenario(monday - h(48)).rest(48 * 60).drive(60).close().eval()
        assertFalse(e.counters.first { it.kind == CounterKind.WEEKLY_REST }.unknown)
        assertEquals(at("2026-10-18T00:00"), e.limits.weeklyRestLatestStart)
    }

    @Test
    fun `with a setup nothing is Unknown`() {
        val e = RuleEngine.evaluate(EngineInput(listOf(Entry(1, at("2026-10-15T11:00"), Mode.REST, RuleSet.EU)), seed, emptyList(), DayChoices(), RuleSet.EU, UTC), at("2026-10-15T11:20"))
        assertTrue(e.counters.none { it.unknown })
        assertEquals(unknownKinds, e.counters.map { it.kind }.toSet().intersect(unknownKinds))
    }

    @Test
    fun `a setup from the last reference period still knows the new one`() {
        val e = RuleEngine.evaluate(EngineInput(emptyList(), seed, emptyList(), DayChoices(), RuleSet.EU, UTC), at("2026-12-07T06:00"))
        assertFalse(e.counters.first { it.kind == CounterKind.RTD_AVERAGE }.unknown)
        assertFalse(e.counters.first { it.kind == CounterKind.WEEK_56 }.unknown)
    }

    @Test
    fun `the planner leaves unknown limits out and says which`() {
        val s = Scenario(at("2026-10-15T11:14")).work(30).drive(120)
        val r = HomePlanner.plan(s.input(), s.end, s.end, h(1))
        assertTrue(r is PlanResult.Fits)
        assertEquals(setOf(Blocker.WEEKLY_REST, Blocker.WEEK_56, Blocker.FORTNIGHT_90, Blocker.RTD_WEEK), (r as PlanResult.Fits).unknown)
        val known = HomePlanner.plan(s.input(seed = seed.copy(seededAtUtc = s.end, readAtUtc = s.end)), s.end, s.end, h(1))
        assertEquals(emptySet<Blocker>(), (known as PlanResult.Fits).unknown)
    }

    @Test
    fun `a log already over 56 hours shows Over even when the week is not covered`() {
        val s = Scenario(at("2026-10-13T06:00"))
        repeat(5) { s.drive(270).rest(45).drive(300).rest(24 * 60 - 615) }
        s.drive(270).rest(45).drive(300).close(Mode.REST)
        val c = s.eval().counters.first { it.kind == CounterKind.WEEK_56 }
        assertTrue(c.over)
        assertFalse(c.unknown)
        assertEquals(-h(1), c.remainingMs)
    }
}
