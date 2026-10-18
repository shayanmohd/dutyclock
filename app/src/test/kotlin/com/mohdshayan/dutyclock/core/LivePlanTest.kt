package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.plan.LivePlan
import com.mohdshayan.dutyclock.core.plan.PlanRequest
import com.mohdshayan.dutyclock.core.plan.PlanResult
import com.mohdshayan.dutyclock.core.plan.ShownPlan
import com.mohdshayan.dutyclock.core.plan.StepKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A plan on screen follows the log and the clock: it is never built on an older log. */
class LivePlanTest {
    /** 2 h 40 driven since the last full break, then the split's second part 29 minutes under way. */
    private fun midSplit(): Scenario {
        val s = Scenario(at("2026-10-12T06:00"))
        s.rest(48 * 60)
        s.drive(150).rest(15).drive(10) // 2:40 since the break, the 15-minute first part taken
        s.rest(29)                      // the 30-minute second part, 29 minutes in
        return s
    }

    private fun List<ShownPlan>.lastSteps() = (last().result as PlanResult.Fits).steps.map { it.kind to it.length }

    @Test
    fun `a new entry re-plans the shown plan from the same request`() = runTest(UnconfinedTestDispatcher()) {
        val s = midSplit()
        val now = s.end
        val input = MutableStateFlow(s.input())
        val clock = MutableStateFlow(now)
        val seen = mutableListOf<ShownPlan>()
        val job = launch { LivePlan.shown(PlanRequest(h(2, 40), null), input, clock).collect { seen += it } }

        // A minute from complete, the split is finished first: sooner home than 1:50, 0:30, 0:50.
        assertEquals(listOf(StepKind.FINISH_BREAK to m(1), StepKind.DRIVE to h(2, 40)), seen.lastSteps())

        // The split completes, the driver taps Driving a minute later and comes back to Plan.
        s.rest(1).drive(1)
        input.value = s.input()
        clock.value = s.end
        assertEquals(listOf(StepKind.DRIVE to h(2, 40)), seen.lastSteps())
        assertEquals(LivePlan.compute(PlanRequest(h(2, 40), null), s.input(), s.end), seen.last())
        job.cancel()
    }

    @Test
    fun `leaving now moves the plan on with the clock`() = runTest(UnconfinedTestDispatcher()) {
        val s = midSplit()
        val input = MutableStateFlow<EngineInput>(s.input())
        val clock = MutableStateFlow(s.end)
        val seen = mutableListOf<ShownPlan>()
        val job = launch { LivePlan.shown(PlanRequest(h(1), null), input, clock).collect { seen += it } }
        clock.value = s.end + m(5)
        assertEquals(s.end + m(5), seen.last().start)
        assertEquals(LivePlan.compute(PlanRequest(h(1), null), s.input(), s.end + m(5)), seen.last())
        job.cancel()
    }
}
