package com.mohdshayan.dutyclock.core.engine

enum class AlarmKind { CLOCK, EXACT_IDLE, INEXACT }

/** One AlarmManager call: which kind, when it triggers, and whether it posts the alert when it fires. */
data class AlarmShot(val kind: AlarmKind, val triggerAt: Long, val final: Boolean)

/**
 * How each planned alert is armed. With exact access an at-limit alert is an alarm clock
 * (setAlarmClock), which Doze and app standby never defer, and a lead or break-complete alert is
 * exact while idle. Without it, Android may deliver an inexact alarm up to three quarters of its
 * delay late (capped at an hour), so a far alert is reached by silent hops halfway there, and the
 * last one is armed early enough that its window ends at the limit.
 */
object AlarmTiming {
    /** Inexact alarms hop until the alert is this close. */
    const val HOP_MS = 6 * 60_000L

    fun isAtLimit(key: String): Boolean = key.endsWith("-limit") || key.endsWith("-limit2") || key == "test"

    fun plan(key: String, at: Long, now: Long, exact: Boolean): AlarmShot = when {
        exact && isAtLimit(key) -> AlarmShot(AlarmKind.CLOCK, at, true)
        exact -> AlarmShot(AlarmKind.EXACT_IDLE, at, true)
        at - now > HOP_MS -> AlarmShot(AlarmKind.INEXACT, now + (at - now) / 2, false)
        // 4/7 of the delay plus a window of 3/4 of that is exactly the delay: early, never late.
        else -> AlarmShot(AlarmKind.INEXACT, now + (at - now) * 4 / 7, true)
    }

    /** A final alarm always posts; a hop posts only when it lands within a minute of its alert. */
    fun shouldPost(final: Boolean, at: Long, now: Long): Boolean = final || at - now <= 60_000L
}
