package com.mohdshayan.dutyclock.core.plan

import com.mohdshayan.dutyclock.core.engine.EngineInput
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What the driver asked for: the driving left, and the instant they leave, or null for now. */
data class PlanRequest(val drivingNeeded: Long, val leaveAt: Long?)

/** A plan as the Plan screen shows it: the result, and the instant its first step starts. */
data class ShownPlan(val result: PlanResult, val zone: ZoneId, val start: Long)

/**
 * The plan on screen is a function of the request, the log as it is now and the clock, so it
 * never disagrees with the log: every new entry, edit, setup or minute re-plans it.
 */
object LivePlan {
    fun compute(request: PlanRequest, input: EngineInput, now: Long): ShownPlan {
        val leave = request.leaveAt ?: now
        return ShownPlan(HomePlanner.plan(input, now, leave, request.drivingNeeded), input.zone, maxOf(now, leave))
    }

    fun shown(request: PlanRequest, input: Flow<EngineInput>, clock: Flow<Long>): Flow<ShownPlan> =
        combine(input, clock) { i, now -> compute(request, i, now) }
}
