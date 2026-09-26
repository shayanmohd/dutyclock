package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.Evaluation
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.MINUTE_MS
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import java.time.LocalDateTime
import java.time.ZoneId

val LONDON: ZoneId = ZoneId.of("Europe/London")
val UTC: ZoneId = ZoneId.of("UTC")

/** Local wall time in [zone] as epoch ms, from "2026-10-13T10:10". */
fun at(local: String, zone: ZoneId = UTC): Long =
    LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

fun h(hours: Int, minutes: Int = 0): Long = (hours * 60L + minutes) * MINUTE_MS
fun m(minutes: Int): Long = minutes * MINUTE_MS

/**
 * Builds a log the way a driver taps it: a start instant, then (mode, minutes) pairs, each a tap.
 * [end] is the instant just after the last part, which is where tests usually evaluate.
 */
class Scenario(start: Long, val ruleSet: RuleSet = RuleSet.EU, val zone: ZoneId = UTC) {
    val entries = mutableListOf<Entry>()
    var end: Long = start
        private set

    fun then(mode: Mode, minutes: Int, ruleSet: RuleSet = this.ruleSet): Scenario {
        entries += Entry(entries.size + 1L, end, mode, ruleSet)
        end += minutes * MINUTE_MS
        return this
    }

    fun drive(min: Int) = then(Mode.DRIVE, min)
    fun work(min: Int) = then(Mode.WORK, min)
    fun avail(min: Int) = then(Mode.AVAILABLE, min)
    fun rest(min: Int) = then(Mode.REST, min)

    /** Taps a closing mode and lets one minute of it run, so the part before is finished, not ongoing. */
    fun close(mode: Mode = Mode.WORK): Scenario = then(mode, 1)

    fun input(
        choices: DayChoices = DayChoices(),
        seed: Seed? = null,
        absences: List<Absence> = emptyList(),
        ruleSet: RuleSet = this.ruleSet,
    ) = EngineInput(entries.toList(), seed, absences, choices, ruleSet, zone)

    fun eval(now: Long = end, choices: DayChoices = DayChoices(), seed: Seed? = null, ruleSet: RuleSet = this.ruleSet): Evaluation =
        RuleEngine.evaluate(input(choices, seed, ruleSet = ruleSet), now)
}
