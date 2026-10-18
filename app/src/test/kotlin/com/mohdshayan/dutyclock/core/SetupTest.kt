package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.EditImpact
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.engine.Evaluation
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.engine.Setup
import com.mohdshayan.dutyclock.core.engine.SetupForm
import com.mohdshayan.dutyclock.core.engine.SetupResult
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 1 of 1.1.0: "Bring your week in" mid-shift (plan tests 1 to 15). Thursday 2026-10-15; every
 * time is UTC. The numbers in a 1.1.0 seed are the tachograph reading at readAtUtc, and the engine
 * takes off what the log holds between the start of counting and the reading.
 */
class SetupTest {
    private fun t(hm: String, day: String = "2026-10-15") = at("${day}T$hm")
    private fun e(id: Long, hm: String, mode: Mode, day: String = "2026-10-15") = Entry(id, t(hm, day), mode, RuleSet.EU)
    private fun eval(entries: List<Entry>, seed: Seed?, now: Long): Evaluation =
        RuleEngine.evaluate(EngineInput(entries, seed, emptyList(), DayChoices(), RuleSet.EU, UTC), now)

    /**
     * Form F: read at 11:20 on Thursday, started, work from 06:00, driving since break 4:00, since
     * the last daily rest 4:00, this week 30:00, last week 40:00, last weekly rest ended Monday
     * 05:00 after 45 hours, everything else zero. Each test names its mode and since.
     */
    private val now = t("11:20")
    private val f = SetupForm(
        mode = null, sinceUtc = null, started = true, dayStartUtc = t("06:00"), part15Taken = false,
        drivingSinceBreak = h(4), drivingToday = h(4), thisWeekDriving = h(30), lastWeekDriving = h(40),
        thisWeekWork = 0, rtdPeriodWork = 0, tenHourDaysUsed = 0, reducedDailyRestsUsed = 0,
        lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRest = h(45), compensationOwed = 0, compensationDueUtc = null,
    )

    private fun ok(form: SetupForm, at: Long = now): SetupResult.Ok {
        val r = Setup.build(form, at)
        assertTrue("refused: $r", r is SetupResult.Ok)
        return r as SetupResult.Ok
    }

    private fun refused(form: SetupForm, at: Long = now): String {
        val r = Setup.build(form, at)
        assertTrue("accepted: $r", r is SetupResult.Refused)
        return (r as SetupResult.Refused).message
    }

    // 1(a) Right now I am [mode] since [time].

    @Test
    fun `a break already under way counts from when it really started`() {
        val r = ok(f.copy(mode = Mode.REST, sinceUtc = t("11:00")))
        assertEquals(t("11:00"), r.seed.seededAtUtc)
        assertEquals(t("11:20"), r.seed.readAtUtc)
        assertEquals(240, r.seed.drivingSinceBreakMin)
        assertEquals(Mode.REST, r.entryMode)
        assertEquals(t("11:00"), r.entryStart)
        val entries = listOf(e(1, "11:00", Mode.REST))
        val at1130 = eval(entries, r.seed, t("11:30"))
        assertEquals(t("11:45"), at1130.breakCompleteAt)
        assertEquals(
            "30 of 45 taken. Rest 15 more, or take 30 later before 4:30 of driving.",
            at1130.counters.first { it.kind.name == "BREAK" }.detail,
        )
        val at1146 = eval(entries, r.seed, t("11:46"))
        assertEquals(h(4, 30), at1146.limits.breakLeft)
        assertNull(at1146.breakCompleteAt)
    }

    /** Test 2's seed: Driving since 11:00, read at 11:20, since break 2:20, since rest 3:20, this week 25:20. */
    private val drivingForm = f.copy(mode = Mode.DRIVE, sinceUtc = t("11:00"), drivingSinceBreak = h(2, 20), drivingToday = h(3, 20), thisWeekDriving = h(25, 20))
    private val drivingSeed get() = ok(drivingForm).seed

    @Test
    fun `driving up to setup is taken off at the start of counting`() {
        val seed = drivingSeed
        assertEquals(140, seed.drivingSinceBreakMin)
        assertEquals(200, seed.drivingTodayMin)
        assertEquals(1520, seed.thisWeekDrivingMin)
        val entries = listOf(e(1, "11:00", Mode.DRIVE))
        val st = Setup.start(seed, Setup.between(seed, entries))
        assertEquals(h(2), st.drivingSinceBreak)
        assertEquals(h(3), st.drivingToday)
        assertEquals(h(25), st.thisWeekDriving)
        assertNull(st.short)
        val at1120 = eval(entries, seed, t("11:20"))
        assertEquals(h(2, 10), at1120.limits.breakLeft)
        assertEquals(h(3, 20), at1120.limits.drivingToday)
        assertEquals(h(25, 20), at1120.limits.weekDriving)
        assertEquals(h(2), eval(entries, seed, t("11:30")).limits.breakLeft)
    }

    @Test
    fun `other work up to setup is taken off this week's work`() {
        val r = ok(f.copy(mode = Mode.WORK, sinceUtc = t("11:00"), thisWeekWork = h(32)))
        assertEquals(1920, r.seed.thisWeekWorkMin)
        val entries = listOf(e(1, "11:00", Mode.WORK))
        assertEquals(h(31, 40), Setup.start(r.seed, Setup.between(r.seed, entries)).thisWeekWork)
        assertEquals(h(32), eval(entries, r.seed, t("11:20")).limits.rtdWeekWork)
    }

    @Test
    fun `a reading below what the current mode used is refused`() {
        assertEquals(
            "You have been driving since 11:00, which is more than the driving you entered. Check the time and the totals.",
            refused(f.copy(mode = Mode.DRIVE, sinceUtc = t("11:00"), drivingSinceBreak = m(10))),
        )
    }

    @Test
    fun `a since time before Monday 00 00 UTC is split across the two weeks`() {
        val mon = at("2026-10-19T00:30")
        val form = f.copy(
            mode = Mode.WORK, sinceUtc = at("2026-10-18T23:30"), dayStartUtc = at("2026-10-18T22:00"),
            drivingSinceBreak = 0, drivingToday = 0, thisWeekDriving = 0, lastWeekDriving = 0,
            thisWeekWork = m(30), rtdPeriodWork = h(50), lastWeeklyRestEndUtc = at("2026-10-18T20:00"),
        )
        val r = ok(form, mon)
        assertEquals(30, r.seed.thisWeekWorkMin)
        assertEquals(3000, r.seed.rtdPeriodWorkMin)
        val entries = listOf(Entry(1, at("2026-10-18T23:30"), Mode.WORK, RuleSet.EU))
        val st = Setup.start(r.seed, Setup.between(r.seed, entries))
        assertEquals(0L, st.thisWeekWork)
        assertEquals(h(49, 30), st.periodWork)
        val ev = eval(entries, r.seed, mon)
        assertEquals(m(30), ev.limits.rtdWeekWork)
        assertEquals(h(50, 30), ev.limits.rtdPeriodTotal)
    }

    @Test
    fun `work before a new reference period comes off nothing`() {
        val mon = at("2026-12-07T00:30")
        val form = f.copy(
            mode = Mode.WORK, sinceUtc = at("2026-12-06T23:30"), dayStartUtc = at("2026-12-06T22:00"),
            drivingSinceBreak = 0, drivingToday = 0, thisWeekDriving = 0, lastWeekDriving = 0,
            thisWeekWork = m(30), rtdPeriodWork = 0, lastWeeklyRestEndUtc = at("2026-12-06T20:00"),
        )
        val r = ok(form, mon)
        val entries = listOf(Entry(1, at("2026-12-06T23:30"), Mode.WORK, RuleSet.EU))
        assertEquals(0L, Setup.start(r.seed, Setup.between(r.seed, entries)).periodWork)
        val ev = eval(entries, r.seed, mon)
        assertEquals(at("2026-12-07T00:00"), ev.limits.rtdPeriod.startUtc)
        assertEquals(m(30), ev.limits.rtdPeriodTotal)
        assertEquals(m(30), ev.limits.rtdWeekWork)
    }

    @Test
    fun `with a 1_0_0 seed the weeks still come from seededAtUtc`() {
        val seed = Seed(seededAtUtc = at("2026-10-19T00:10"), thisWeekDrivingMin = 0, lastWeekDrivingMin = 3000)
        assertTrue(Setup.between(seed, listOf(Entry(1, at("2026-10-19T00:10"), Mode.DRIVE, RuleSet.EU))).isEmpty())
        val ev = eval(emptyList(), seed, at("2026-10-19T00:20"))
        assertEquals(0L, ev.limits.weekDriving)
        assertEquals(h(50), ev.limits.lastWeekDriving)
    }

    // 1(b) First 15 minutes of a split break already taken.

    @Test
    fun `with the first 15 already taken, 30 more completes the split`() {
        val r = ok(f.copy(mode = Mode.REST, sinceUtc = t("11:00"), drivingSinceBreak = h(3), part15Taken = true))
        assertTrue(r.seed.part15Taken)
        val entries = listOf(e(1, "11:00", Mode.REST))
        val at1110 = eval(entries, r.seed, t("11:10"))
        assertEquals(t("11:30"), at1110.breakCompleteAt)
        assertTrue(at1110.breakCompleteIsSplit)
        assertEquals("15 of 45 taken. Second part: 20 of 30.", eval(entries, r.seed, t("11:20")).counters.first { it.kind.name == "BREAK" }.detail)
        assertEquals(h(4, 30), eval(entries, r.seed, t("11:31")).limits.breakLeft)
    }

    @Test
    fun `the box also counts 15 minutes of working time break`() {
        val form = f.copy(mode = Mode.WORK, sinceUtc = t("11:00"), drivingSinceBreak = h(3), drivingToday = h(3))
        val entries = listOf(e(1, "11:00", Mode.WORK))
        assertEquals(m(15), eval(entries, ok(form.copy(part15Taken = true)).seed, t("11:20")).replay.shiftBreaks)
        assertEquals(0L, eval(entries, ok(form).seed, t("11:20")).replay.shiftBreaks)
    }

    // 1(c) A work start on the previous day.

    @Test
    fun `a shift that began before midnight is accepted`() {
        val fri = at("2026-10-16T02:00")
        val r = ok(
            f.copy(mode = Mode.AVAILABLE, sinceUtc = at("2026-10-16T01:30"), dayStartUtc = at("2026-10-15T22:00"), drivingSinceBreak = h(3), drivingToday = h(3)),
            fri,
        )
        val ev = eval(listOf(Entry(1, at("2026-10-16T01:30"), Mode.AVAILABLE, RuleSet.EU)), r.seed, fri)
        assertEquals(at("2026-10-16T11:00"), ev.limits.dailyRestLatestStart)
        assertEquals(at("2026-10-16T13:00"), ev.limits.dailyRestReducedLatestStart)
    }

    @Test
    fun `setup refusals`() {
        val rest = f.copy(mode = Mode.REST, sinceUtc = t("11:00"))
        ok(rest)
        assertEquals("Pick what you are doing right now.", refused(rest.copy(mode = null)))
        assertEquals("Pick when you started it.", refused(rest.copy(sinceUtc = null)))
        assertEquals("That start is in the future. Pick a time up to now.", refused(rest.copy(sinceUtc = t("11:21"))))
        assertEquals("Pick a start in the last 7 days.", refused(rest.copy(sinceUtc = now - 7 * 24 * h(1) - m(1))))
        assertEquals(
            "You are on duty now, so your working day has started. Switch on I have started work since my last daily rest.",
            refused(rest.copy(mode = Mode.WORK, started = false, dayStartUtc = null)),
        )
        assertEquals("Work cannot start after your current mode began. Check both times.", refused(rest.copy(sinceUtc = t("05:30"))))
        assertEquals(
            "Work started more than 24 hours before your current mode began. Check both times.",
            refused(rest.copy(dayStartUtc = t("10:00", "2026-10-14"))),
        )
        assertEquals(
            "Driving since your last break is more than your driving since your last daily rest. Check both.",
            refused(rest.copy(drivingSinceBreak = h(5))),
        )
        assertEquals("Driving since your last daily rest cannot be more than 24 hours. Check it.", refused(rest.copy(drivingToday = h(30))))
        assertEquals(
            "Driving since your last daily rest is more than this week and last week together. Check them.",
            refused(rest.copy(thisWeekDriving = h(2), lastWeekDriving = h(1))),
        )
        assertEquals(
            "Your current mode cannot start before your last weekly rest ended. Check both times.",
            refused(rest.copy(sinceUtc = at("2026-10-12T04:00"), started = false, dayStartUtc = null)),
        )
        assertEquals(
            "You have been working since 11:00, which is more than the work you entered for this week. Check the time and the totals.",
            refused(rest.copy(mode = Mode.WORK, thisWeekDriving = m(10), lastWeekDriving = h(40), drivingToday = m(10), drivingSinceBreak = m(10))),
        )
    }

    // 1(d) Never silently drop or ignore what lands before the snapshot.

    /** A 1.0.0 seed saved at 11:20, before any 1.1.0 reading field existed. */
    private val oldSeed = Seed(
        seededAtUtc = at("2026-10-15T11:20"), drivingSinceBreakMin = 240, dayStartUtc = at("2026-10-15T06:00"), drivingTodayMin = 240,
        thisWeekDrivingMin = 1800, lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
    )

    @Test
    fun `moving the first counted break back to its real start counts it from there`() {
        val entries = listOf(e(1, "11:21", Mode.REST))
        assertEquals(EditImpact.Reanchor(t("11:00")), Setup.editImpact(oldSeed, entries, 1, t("11:00"), Mode.REST))
        val moved = Setup.reanchor(oldSeed, t("11:00"))
        assertEquals(t("11:00"), moved.seededAtUtc)
        assertEquals(t("11:20"), moved.readAtUtc)
        assertEquals(oldSeed.copy(seededAtUtc = t("11:00"), readAtUtc = t("11:20")), moved)
        val after = listOf(e(1, "11:00", Mode.REST))
        val ev = eval(after, moved, t("11:46"))
        assertEquals(h(4, 30), ev.limits.breakLeft)
        assertTrue(ev.replay.ongoingRest!!.length >= m(45))
        val strip = DayTotals.strips(after, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15), t("11:46"), UTC).first()
        assertEquals(660, strip.pieces.first { it.mode == Mode.REST }.fromMin)
    }

    @Test
    fun `moving driving back takes the extra driving off`() {
        val seed = oldSeed.copy(drivingSinceBreakMin = 100)
        assertEquals(EditImpact.Reanchor(t("11:10")), Setup.editImpact(seed, listOf(e(1, "11:21", Mode.DRIVE)), 1, t("11:10"), Mode.DRIVE))
        val moved = Setup.reanchor(seed, t("11:10"))
        assertEquals(listOf(100, 240, 1800), listOf(moved.drivingSinceBreakMin, moved.drivingTodayMin, moved.thisWeekDrivingMin))
        val after = listOf(e(1, "11:10", Mode.DRIVE))
        val st = Setup.start(moved, Setup.between(moved, after))
        assertEquals(listOf(h(1, 30), h(3, 50), h(29, 50)), listOf(st.drivingSinceBreak, st.drivingToday, st.thisWeekDriving))
        assertEquals(h(4, 30) - h(1, 50), eval(after, moved, t("11:30")).limits.breakLeft)
    }

    @Test
    fun `changing the mode of the setup entry keeps the tachograph reading`() {
        val seed = drivingSeed
        assertEquals(EditImpact.Counted, Setup.editImpact(seed, listOf(e(1, "11:00", Mode.DRIVE)), 1, t("11:00"), Mode.REST))
        val after = listOf(e(1, "11:00", Mode.REST))
        assertEquals(h(2, 10), eval(after, seed, t("11:20")).limits.breakLeft)
        assertEquals(h(4, 30), eval(after, seed, t("11:46")).limits.breakLeft)
    }

    @Test
    fun `moving the setup entry later keeps the tachograph reading`() {
        val seed = drivingSeed
        assertEquals(EditImpact.Counted, Setup.editImpact(seed, listOf(e(1, "11:00", Mode.DRIVE)), 1, t("11:10"), Mode.DRIVE))
        val ev = eval(listOf(e(1, "11:10", Mode.DRIVE)), seed, t("11:20"))
        assertEquals(h(2, 10), ev.limits.breakLeft)
        assertEquals(h(3, 20), ev.limits.drivingToday)
    }

    /** Test 13's seed: counting from 11:00, read at 11:20. */
    private val seed13 = Seed(
        seededAtUtc = at("2026-10-15T11:00"), readAtUtc = at("2026-10-15T11:20"), dayStartUtc = at("2026-10-15T06:00"),
        drivingSinceBreakMin = 240, drivingTodayMin = 240, thisWeekDrivingMin = 1800,
        lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
    )

    @Test
    fun `an edit before the start of counting is reported, not ignored`() {
        val entries = listOf(e(1, "09:00", Mode.WORK), e(2, "11:00", Mode.REST))
        assertEquals(EditImpact.BeforeStart(t("11:00")), Setup.editImpact(seed13, entries, 1, t("08:45"), Mode.WORK))
        assertEquals(EditImpact.BeforeStart(t("11:00")), Setup.editImpact(seed13, entries, null, t("10:00"), Mode.WORK))
        assertEquals(EditImpact.Counted, Setup.editImpact(seed13, entries, 2, t("12:00"), Mode.REST))
        assertEquals(EditImpact.Reanchor(t("10:50")), Setup.editImpact(seed13, entries, 2, t("10:50"), Mode.REST))
    }

    @Test
    fun `an entry added across the start of counting moves it back`() {
        assertEquals(EditImpact.Reanchor(t("11:00")), Setup.editImpact(oldSeed, emptyList(), null, t("11:00"), Mode.REST))
        val ev = eval(listOf(e(1, "11:00", Mode.REST)), Setup.reanchor(oldSeed, t("11:00")), t("11:30"))
        assertEquals(t("11:45"), ev.breakCompleteAt)
    }

    @Test
    fun `an entry tapped before a 1_0_0 setup counts from its real start once saved`() {
        val entries = listOf(e(1, "11:00", Mode.REST))
        assertEquals(t("12:05"), eval(entries, oldSeed, t("11:30")).breakCompleteAt)
        assertEquals(EditImpact.Reanchor(t("11:00")), Setup.editImpact(oldSeed, entries, 1, t("11:00"), Mode.REST))
        assertEquals(t("11:45"), eval(entries, Setup.reanchor(oldSeed, t("11:00")), t("11:30")).breakCompleteAt)
    }

    @Test
    fun `the start of counting cannot move before the start of work`() {
        assertEquals(EditImpact.CannotMove(t("11:00")), Setup.editImpact(seed13, listOf(e(2, "11:00", Mode.REST)), 2, t("05:50"), Mode.REST))
        assertFalse(Setup.canReanchor(seed13, t("05:50"), listOf(e(2, "05:50", Mode.REST))))
    }

    @Test
    fun `an edit that puts more driving before the reading than it holds is refused`() {
        val seed = ok(f.copy(mode = Mode.REST, sinceUtc = t("11:00"), drivingSinceBreak = m(10))).seed
        val entries = listOf(e(1, "11:00", Mode.REST))
        assertEquals(EditImpact.Exceeds(t("11:20")), Setup.editImpact(seed, entries, null, t("11:01"), Mode.DRIVE))
        assertEquals(EditImpact.Counted, Setup.editImpact(seed, entries, null, t("11:15"), Mode.DRIVE))
    }

    @Test
    fun `no seed means every edit is counted`() {
        assertEquals(EditImpact.Counted, Setup.editImpact(null, listOf(e(1, "11:00", Mode.REST)), 1, t("09:00"), Mode.REST))
    }

    // Orchestrator decision 1: other work since your last daily rest (plan tests 42 and 43).

    private val otherSeed = Seed(
        seededAtUtc = t("11:20"), dayStartUtc = t("06:00"), drivingSinceBreakMin = 180, drivingTodayMin = 180,
        otherWorkSinceRestMin = 120, thisWeekDrivingMin = 1800, lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
    )

    @Test
    fun `other work since the daily rest counts toward the working time break and GB duty`() {
        val l = eval(emptyList(), otherSeed, t("11:20")).limits
        assertEquals(h(1), l.rtdBreakLeft)
        assertEquals(m(30), l.rtdBreakNeeded)
        assertEquals(h(5), l.gbDuty)
    }

    @Test
    fun `without other work the numbers are 1_0_0's`() {
        val zero = eval(emptyList(), otherSeed.copy(otherWorkSinceRestMin = 0), t("11:20")).limits
        val unnamed = eval(
            emptyList(),
            Seed(
                seededAtUtc = t("11:20"), dayStartUtc = t("06:00"), drivingSinceBreakMin = 180, drivingTodayMin = 180,
                thisWeekDrivingMin = 1800, lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
            ),
            t("11:20"),
        ).limits
        assertEquals(h(3), zero.rtdBreakLeft)
        assertEquals(h(3), zero.gbDuty)
        assertEquals(unnamed, zero)
    }

    @Test
    fun `the sheet's other work reaches the working time break and GB duty`() {
        val form = f.copy(mode = Mode.WORK, sinceUtc = t("11:00"), drivingSinceBreak = h(3), drivingToday = h(3), otherWorkSinceRest = h(2))
        val r = ok(form)
        assertEquals(120, r.seed.otherWorkSinceRestMin)
        val l = eval(listOf(e(1, "11:00", Mode.WORK)), r.seed, t("11:20")).limits
        assertEquals(h(1), l.rtdBreakLeft)
        assertEquals(h(5), l.gbDuty)
        // Left empty while working since 11:00: not refused, and the replay still counts that work.
        val empty = ok(form.copy(otherWorkSinceRest = 0))
        assertEquals(0, empty.seed.otherWorkSinceRestMin)
        assertEquals(h(3, 20), eval(listOf(e(1, "11:00", Mode.WORK)), empty.seed, t("11:20")).limits.gbDuty)
    }

    @Test
    fun `other work is zero when work has not started since the daily rest`() {
        val r = ok(f.copy(mode = Mode.REST, sinceUtc = t("11:00"), started = false, otherWorkSinceRest = h(2)))
        assertEquals(0, r.seed.otherWorkSinceRestMin)
    }

    @Test
    fun `driving and other work over 24 hours is refused`() {
        val msg = refused(f.copy(mode = Mode.REST, sinceUtc = t("11:00"), drivingSinceBreak = h(4), drivingToday = h(20), otherWorkSinceRest = h(4, 1), thisWeekDriving = h(40)))
        assertEquals("Driving and other work since your last daily rest cannot be more than 24 hours. Check them.", msg)
    }

    // Orchestrator decision 2: the weekly rest default is never in the future (plan test 44).

    @Test
    fun `the weekly rest default is never in the future`() {
        val monEarly = at("2026-10-19T05:30", LONDON)
        val d = Setup.defaultWeeklyRestEnd(monEarly, LONDON)
        assertEquals(at("2026-10-12T06:00", LONDON), d)
        val form = f.copy(
            mode = Mode.REST, sinceUtc = at("2026-10-19T05:00", LONDON), started = false, dayStartUtc = null,
            drivingSinceBreak = 0, drivingToday = 0, thisWeekDriving = 0, lastWeekDriving = h(40), lastWeeklyRestEndUtc = d,
        )
        assertTrue(Setup.build(form, monEarly, LONDON) is SetupResult.Ok)
        assertEquals(at("2026-10-19T06:00", LONDON), Setup.defaultWeeklyRestEnd(at("2026-10-19T06:30", LONDON), LONDON))
        // The day after the clocks go back: last Monday 06:00 BST is 05:00 UTC.
        assertEquals(at("2026-10-19T05:00"), Setup.defaultWeeklyRestEnd(at("2026-10-26T05:30", LONDON), LONDON))
        assertEquals(at("2026-10-19T06:00", LONDON), Setup.defaultWeeklyRestEnd(at("2026-10-19T06:00", LONDON), LONDON))
    }

    // Review edge cases for the two extra setup fixes.

    @Test
    fun `other work comes off only after a daily rest inside the gap`() {
        // Counting moved back to Wed 08:00; the reading was taken Thu 20:00 with 1:30 of other work.
        val seed = Seed(
            seededAtUtc = t("08:00", "2026-10-14"), readAtUtc = t("20:00"), dayStartUtc = t("19:00"),
            otherWorkSinceRestMin = 90, thisWeekWorkMin = 600, thisWeekDrivingMin = 600,
            lastWeeklyRestEndUtc = at("2026-10-12T05:00"), lastWeeklyRestMin = 2700,
        )
        val between = listOf(
            com.mohdshayan.dutyclock.core.model.Segment(t("08:00", "2026-10-14"), t("09:00", "2026-10-14"), Mode.WORK, RuleSet.EU),
            com.mohdshayan.dutyclock.core.model.Segment(t("09:00", "2026-10-14"), t("19:00"), Mode.REST, RuleSet.EU),
            com.mohdshayan.dutyclock.core.model.Segment(t("19:00"), t("20:00"), Mode.WORK, RuleSet.EU),
        )
        val st = Setup.start(seed, between)
        // Only the hour after the daily rest is in the reading's "since your last daily rest".
        assertEquals(m(30), st.otherWorkSinceRest)
        assertNull(st.short)
    }

    @Test
    fun `other work below the current work is clamped, never refused`() {
        // Working since 11:00 (20 minutes at the reading) but only 10 minutes of other work entered.
        val r = ok(f.copy(mode = Mode.WORK, sinceUtc = t("11:00"), drivingSinceBreak = h(3), drivingToday = h(3), otherWorkSinceRest = m(10)))
        assertEquals(10, r.seed.otherWorkSinceRestMin)
        val l = eval(listOf(e(1, "11:00", Mode.WORK)), r.seed, t("11:20")).limits
        // Clamped at zero at 11:00, then the 20 logged minutes: overcounts by 10, the safe side.
        assertEquals(h(3, 20), l.gbDuty)
        assertEquals(h(2, 40), l.rtdBreakLeft)
    }

    @Test
    fun `a 1_0_0 seed never takes other work off`() {
        val seed = otherSeed.copy(otherWorkSinceRestMin = 60)
        val st = Setup.start(seed, Setup.between(seed, listOf(e(1, "11:00", Mode.WORK))))
        assertEquals(h(1), st.otherWorkSinceRest)
    }

    @Test
    fun `other work keeps counting after setup until a daily rest resets it`() {
        val r = ok(f.copy(mode = Mode.WORK, sinceUtc = t("11:00"), drivingSinceBreak = h(3), drivingToday = h(3), otherWorkSinceRest = h(2)))
        val entries = listOf(e(1, "11:00", Mode.WORK), e(2, "12:00", Mode.REST), e(3, "22:00", Mode.WORK, "2026-10-15"))
        // At 12:00, before the rest: 3:00 driving and 2:40 other work (2:00 read at 11:20, then 40 more minutes).
        assertEquals(h(5, 40), eval(entries.take(1), r.seed, t("12:00")).limits.gbDuty)
        // After a 10-hour rest the shift starts again from the new work only.
        assertEquals(m(30), eval(entries, r.seed, t("22:30")).replay.shiftWork)
    }

    @Test
    fun `the weekly rest default on other days and zones`() {
        // Sunday night: this week's Monday, not next.
        assertEquals(at("2026-10-12T06:00", LONDON), Setup.defaultWeeklyRestEnd(at("2026-10-18T23:00", LONDON), LONDON))
        // Tokyo, Monday 02:54 local (Sunday in UTC): the Monday a week back.
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        assertEquals(at("2026-10-12T06:00", tokyo), Setup.defaultWeeklyRestEnd(at("2026-10-19T02:54", tokyo), tokyo))
        // One second before 06:00 on Monday: still last week.
        assertEquals(at("2026-10-12T06:00", LONDON), Setup.defaultWeeklyRestEnd(at("2026-10-19T06:00", LONDON) - 1000, LONDON))
        // The day after the clocks go forward (Monday 30 March 2026, 05:30 BST): the Monday before, in GMT.
        assertEquals(at("2026-03-23T06:00"), Setup.defaultWeeklyRestEnd(at("2026-03-30T05:30", LONDON), LONDON))
    }
}
