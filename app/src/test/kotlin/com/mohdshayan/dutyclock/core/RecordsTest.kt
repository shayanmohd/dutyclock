package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.BackupEntry
import com.mohdshayan.dutyclock.core.backup.BackupFile
import com.mohdshayan.dutyclock.core.backup.Csv
import com.mohdshayan.dutyclock.core.backup.NotABackup
import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.AbsenceKind
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.core.pdf.PdfPage
import com.mohdshayan.dutyclock.core.pdf.PdfText
import com.mohdshayan.dutyclock.core.pdf.RecordPdf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfTextTest {
    @Test
    fun `Polish, Romanian and Lithuanian names print without question marks`() {
        assertEquals("Lukasz Kowalski", PdfText.fold("Łukasz Kowalski"))
        assertEquals("Grzegorz Brzeczyszczykiewicz", PdfText.fold("Grzegorz Brzęczyszczykiewicz"))
        assertEquals("Zólkiewski Zdzislaw", PdfText.fold("Żółkiewski Zdzisław"))
        assertEquals("Stefan Tepes Ionita", PdfText.fold("Ștefan Țepeș Ioniță"))
        assertEquals("Serban Tutea", PdfText.fold("Şerban Ţuţea"))
        assertEquals("Žydrunas Ažuolas Eriksas", PdfText.fold("Žydrūnas Ąžuolas Ėriksas"))
        // Letters WinAnsi already draws are kept, not folded.
        assertEquals("Marek Wójcik", PdfText.fold("Marek Wójcik"))
        assertEquals("Šarunas", PdfText.fold("Šarūnas"))
        for (name in listOf("Łukasz", "Brzęczyszczykiewicz", "Ștefan Țepeș", "Ąžuolas Ėriksas", "Mădălina Căpraru", "Įvairūs Ųsai")) {
            assertFalse(name, PdfPage.escape(PdfText.fold(name)).contains('?'))
        }
    }
}

class RecordsTest {
    private val z = LONDON

    private fun month(): List<Entry> {
        val out = mutableListOf<Entry>()
        var id = 1L
        var day = LocalDate.of(2026, 9, 1)
        repeat(28) { i ->
            val t = day.atTime(6, 0).atZone(z).toInstant().toEpochMilli()
            out += Entry(id++, t, Mode.WORK, RuleSet.EU)
            out += Entry(id++, t + h(0, 20), Mode.DRIVE, RuleSet.EU)
            out += Entry(id++, t + h(4, 20), Mode.REST, RuleSet.EU, edited = i == 3)
            out += Entry(id++, t + h(5, 5), Mode.DRIVE, RuleSet.EU)
            out += Entry(id++, t + h(8, 45), Mode.AVAILABLE, RuleSet.EU)
            out += Entry(id++, t + h(9, 30), Mode.REST, RuleSet.EU)
            day = day.plusDays(1)
        }
        return out
    }

    @Test
    fun `the 28-day PDF is a personal record with 28 strips and folded names`() {
        val bytes = RecordPdf.build(
            RecordPdf.Input("Łukasz Kowalski", "WX71 KTE", month(), LocalDate.of(2026, 9, 28), 28, at("2026-09-29T08:00", z), z),
        )
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("(Personal record. Not a tachograph record.)"))
        assertTrue(text.contains("Lukasz Kowalski"))
        assertFalse(text.contains("?ukasz"))
        assertEquals(28, Regex("% strip ").findAll(text).count())
        assertTrue("edited day is asterisked", text.contains("Fri 4 Sep *"))
        assertTrue(text.contains("/Count 3"))
    }

    @Test
    fun `backup round trip keeps every entry, the seed, the leave and the settings`() {
        val file = BackupFile(
            exportedAt = 1_790_000_000_000,
            prefs = mapOf("driver_name" to "Gemma Riley", "rule_set_default" to "EU"),
            seed = Seed(seededAtUtc = 1_789_000_000_000, drivingTodayMin = 275, compensationOwedMin = 21 * 60),
            entries = month().map { BackupEntry(it.startUtc, it.mode.name, it.ruleSet.name, editedAt = if (it.edited) it.startUtc else null) },
            absences = listOf(Absence("2026-09-14", AbsenceKind.ANNUAL_LEAVE)),
        )
        val back = Backup.decode(Backup.encode(file))
        assertEquals(file, back)
        assertEquals(168, back.entries.size)
    }

    @Test
    fun `anything that is not a Dutyclock backup is refused`() {
        for (bad in listOf("", "hello", """{"format":"other","schema":1,"exportedAt":1}""", """{"format":"dutyclock-backup","schema":2,"exportedAt":1}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"entries":[{"startUtc":1,"mode":"FLY","ruleSet":"EU"}]}""")) {
            try {
                Backup.decode(bad); fail("accepted: $bad")
            } catch (_: NotABackup) {
            }
        }
    }

    @Test
    fun `CSV has the header and whole-minute durations`() {
        val csv = Csv.build(month().take(3), at("2026-09-01T12:00", z), z).lines()
        assertEquals(Csv.HEADER, csv[0])
        assertEquals("2026-09-01T06:00,2026-09-01T05:00:00Z,WORK,20,EU,no", csv[1])
        assertEquals("2026-09-01T10:20,2026-09-01T09:20:00Z,REST,100,EU,no", csv[3])
    }
}
