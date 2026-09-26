package com.mohdshayan.dutyclock.core.pdf

import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Finding
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.text
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.WEEK_MS
import com.mohdshayan.dutyclock.core.model.segmentsOf
import com.mohdshayan.dutyclock.core.time.FixedWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The 28-day personal record: A4 portrait, a strip and totals per day, weekly and fortnightly
 * driving sums, working time per week, and edited entries marked. Always headed as a personal
 * record and never as a tachograph printout.
 */
object RecordPdf {
    const val HEADLINE = "Personal record. Not a tachograph record."
    private const val W = 595.28
    private const val H = 841.89
    private const val DAYS_PER_PAGE = 14

    data class Input(
        val driverName: String,
        val vehicleReg: String,
        val entries: List<Entry>,
        val lastDay: LocalDate,
        val days: Int,
        val now: Long,
        val zone: ZoneId,
        val findings: List<Finding> = emptyList(),
    )

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val longFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun build(input: Input): ByteArray {
        val first = input.lastDay.minusDays((input.days - 1).toLong())
        val strips = DayTotals.strips(input.entries, first, input.lastDay, input.now, input.zone)
        val pages = mutableListOf<PdfPage>()
        val chunks = strips.chunked(DAYS_PER_PAGE)
        val totalPages = chunks.size + 1
        chunks.forEachIndexed { i, chunk ->
            val p = PdfPage(W, H)
            var y = header(p, input, first, i + 1, totalPages)
            legend(p, y); y -= 44
            for (s in chunk) {
                strip(p, s, y)
                y -= 46
            }
            pages += p
        }
        pages += summary(input, first, totalPages)
        return PdfWriter.write(pages, PdfText.fold("Dutyclock personal record ${input.driverName}".trim()))
    }

    private fun t(p: PdfPage, x: Double, y: Double, size: Double, s: String, bold: Boolean = false) {
        p.text(x, y, size, PdfText.fold(s), bold)
    }

    private fun header(p: PdfPage, input: Input, first: LocalDate, page: Int, pages: Int): Double {
        p.gray(0.0)
        t(p, 40.0, H - 56, 16.0, "Dutyclock", true)
        t(p, 40.0, H - 76, 12.0, HEADLINE, true)
        val who = listOfNotNull(
            input.driverName.takeIf { it.isNotBlank() }?.let { "Driver: $it" },
            input.vehicleReg.takeIf { it.isNotBlank() }?.let { "Vehicle: $it" },
        ).joinToString("    ")
        t(p, 40.0, H - 94, 9.0, (if (who.isNotEmpty()) "$who    " else "") + "${longFmt.format(first)} to ${longFmt.format(input.lastDay)}")
        t(p, 40.0, H - 107, 8.0, "Advisory, made from the driver's own entries. It does not replace tachograph or operator records.")
        t(p, W - 90, H - 56, 8.0, "Page $page of $pages")
        p.lineWidth(0.5).line(40.0, H - 116, W - 40, H - 116)
        return H - 136
    }

    private fun legend(p: PdfPage, y: Double) {
        var x = 128.0
        for ((mode, h) in listOf(Mode.DRIVE to 16.0, Mode.WORK to 10.0, Mode.AVAILABLE to 5.0)) {
            p.gray(shade(mode)).fillRect(x, y - 2, 14.0, h * 0.6)
            p.gray(0.0)
            t(p, x + 18, y, 8.0, mode.label)
            x += 92
        }
        t(p, x, y, 8.0, "Rest: no band.   * edited entries")
    }

    private fun shade(mode: Mode) = when (mode) {
        Mode.DRIVE -> 0.1
        Mode.WORK -> 0.4
        Mode.AVAILABLE -> 0.65
        Mode.REST -> 1.0
    }

    private fun band(mode: Mode) = when (mode) {
        Mode.DRIVE -> 16.0
        Mode.WORK -> 10.0
        Mode.AVAILABLE -> 5.0
        Mode.REST -> 0.0
    }

    private fun strip(p: PdfPage, s: com.mohdshayan.dutyclock.core.engine.DayStrip, y: Double) {
        p.comment("strip ${s.date}")
        val x0 = 128.0
        val w = 300.0
        val h = 18.0
        p.gray(0.0)
        t(p, 40.0, y + 6, 9.0, dayFmt.format(s.date) + if (s.edited) " *" else "", true)
        p.lineWidth(0.4).gray(0.55).rect(x0, y, w, h)
        for (hr in 0..24 step 3) {
            val x = x0 + w * hr / 24.0
            p.line(x, y + h, x, y + h + (if (hr % 6 == 0) 4.0 else 2.0))
            if (hr % 6 == 0 && hr < 24) {
                p.gray(0.3); t(p, x - 4, y + h + 6, 6.0, hr.toString().padStart(2, '0')); p.gray(0.55)
            }
        }
        for (piece in s.pieces) {
            val bh = band(piece.mode)
            if (bh == 0.0) continue
            val px = x0 + w * piece.fromMin / 1440.0
            val pw = (w * (piece.toMin - piece.fromMin) / 1440.0).coerceAtLeast(0.6)
            p.gray(shade(piece.mode)).fillRect(px, y, pw, bh)
        }
        p.gray(0.0)
        val tx = x0 + w + 14
        t(p, tx, y + 10, 8.0, "Driving ${Fmt.hm(s.driveMs)}   Work ${Fmt.hm(s.workMs)}")
        t(p, tx, y + 0.5, 8.0, "Availability ${Fmt.hm(s.availableMs)}   Rest ${Fmt.hm(s.restMs)}")
    }

    private fun summary(input: Input, first: LocalDate, pageNo: Int): PdfPage {
        val p = PdfPage(W, H)
        var y = header(p, input, first, pageNo, pageNo)
        val fromUtc = first.atStartOfDay(input.zone).toInstant().toEpochMilli()
        val toUtc = input.lastDay.plusDays(1).atStartOfDay(input.zone).toInstant().toEpochMilli()
        // Weekly sums over fixed UTC weeks, including the week before the first day for the fortnight.
        val drive = HashMap<Long, Long>()
        val work = HashMap<Long, Long>()
        val weeks = mutableListOf<Long>()
        var w = FixedWeek.start(fromUtc) - WEEK_MS
        while (w < toUtc) { weeks += w; w += WEEK_MS }
        for (seg in segmentsOf(input.entries, input.now)) {
            for (wk in weeks) {
                val a = maxOf(seg.start, wk); val b = minOf(seg.end, wk + WEEK_MS)
                if (b <= a) continue
                if (seg.mode == Mode.DRIVE) drive[wk] = (drive[wk] ?: 0L) + (b - a)
                if (seg.mode.isRtdWork) work[wk] = (work[wk] ?: 0L) + (b - a)
            }
        }
        t(p, 40.0, y, 12.0, "Weekly totals", true); y -= 18
        t(p, 40.0, y, 8.0, "Fixed weeks, Monday 00:00 to Sunday 24:00 UTC. Limits: 56 h driving a week, 90 h a fortnight, 60 h working time a week.")
        y -= 20
        val cols = doubleArrayOf(40.0, 190.0, 300.0, 420.0)
        listOf("Week starting", "Driving", "Two-week driving", "Working time").forEachIndexed { i, s -> t(p, cols[i], y, 9.0, s, true) }
        y -= 6; p.lineWidth(0.4).gray(0.55).line(40.0, y, W - 40, y); p.gray(0.0); y -= 14
        val utcDay = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        for (i in 1 until weeks.size) {
            val wk = weeks[i]
            p.comment("week $wk")
            val d = drive[wk] ?: 0L
            val f = d + (drive[weeks[i - 1]] ?: 0L)
            t(p, cols[0], y, 9.0, utcDay.format(Instant.ofEpochMilli(wk).atZone(ZoneOffset.UTC).toLocalDate()))
            t(p, cols[1], y, 9.0, Fmt.hm(d) + if (d > 56 * 3_600_000L) "  over" else "")
            t(p, cols[2], y, 9.0, Fmt.hm(f) + if (f > 90 * 3_600_000L) "  over" else "")
            t(p, cols[3], y, 9.0, Fmt.hm(work[wk] ?: 0L) + if ((work[wk] ?: 0L) > 60 * 3_600_000L) "  over" else "")
            y -= 16
        }
        y -= 16
        val edited = input.entries.filter { it.edited && it.startUtc in fromUtc until toUtc }.sortedBy { it.startUtc }
        t(p, 40.0, y, 12.0, "Edited entries", true); y -= 18
        if (edited.isEmpty()) {
            t(p, 40.0, y, 9.0, "None. Every entry was logged live."); y -= 16
        } else {
            for (e in edited.take(24)) {
                t(p, 40.0, y, 9.0, "* ${Fmt.shortDay(e.startUtc, input.zone)} ${Fmt.clock(e.startUtc, input.zone)}  ${e.mode.label}  (${e.ruleSet.label})")
                y -= 14
            }
            if (edited.size > 24) { t(p, 40.0, y, 9.0, "and ${edited.size - 24} more"); y -= 14 }
        }
        y -= 16
        val found = input.findings.filter { it.at in fromUtc until toUtc }.sortedBy { it.at }
        t(p, 40.0, y, 12.0, "Limits passed in this period", true); y -= 18
        if (found.isEmpty()) {
            t(p, 40.0, y, 9.0, "None found in the entries."); y -= 16
        } else {
            for (f in found.take(20)) {
                t(p, 40.0, y, 9.0, "${Fmt.shortDay(f.at, input.zone)} ${Fmt.clock(f.at, input.zone)}  ${f.text()}")
                y -= 14
            }
        }
        y -= 20
        t(p, 40.0, y, 8.0, "Made with Dutyclock from entries on the driver's phone. $HEADLINE")
        return p
    }
}
