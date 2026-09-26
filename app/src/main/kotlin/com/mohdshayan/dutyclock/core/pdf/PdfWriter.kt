package com.mohdshayan.dutyclock.core.pdf

import java.io.ByteArrayOutputStream
import java.util.Locale

/** 72 points per inch, so 72 / 25.4 points per millimetre. */
const val PT_PER_MM = 72.0 / 25.4
const val PT_PER_INCH = 72.0

fun mmToPt(mm: Double) = mm * PT_PER_MM

enum class Paper(val label: String, val widthMm: Double, val heightMm: Double) {
    A4("A4", 297.0, 210.0),
    LETTER("Letter", 279.4, 215.9);

    /** Landscape page size in points. */
    val landscapeWidthPt get() = mmToPt(widthMm)
    val landscapeHeightPt get() = mmToPt(heightMm)
}

/**
 * One page of vector drawing in PDF points (origin bottom left). Streams are written uncompressed
 * so tests can read the geometry back out of the file.
 */
class PdfPage(val widthPt: Double, val heightPt: Double) {
    private val sb = StringBuilder()

    private fun n(v: Double) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    fun comment(tag: String) = apply { sb.append("% ").append(tag.replace('\n', ' ')).append('\n') }
    fun save() = apply { sb.append("q\n") }
    fun restore() = apply { sb.append("Q\n") }
    fun lineWidth(pt: Double) = apply { sb.append(n(pt)).append(" w\n") }
    fun gray(g: Double) = apply { sb.append(n(g)).append(" G ").append(n(g)).append(" g\n") }
    fun dash(on: Double, off: Double) = apply { sb.append("[").append(n(on)).append(' ').append(n(off)).append("] 0 d\n") }
    fun solid() = apply { sb.append("[] 0 d\n") }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double) = apply {
        sb.append(n(x1)).append(' ').append(n(y1)).append(" m ").append(n(x2)).append(' ').append(n(y2)).append(" l S\n")
    }

    fun polyline(points: List<Pair<Double, Double>>) = apply {
        if (points.size < 2) return@apply
        sb.append(n(points[0].first)).append(' ').append(n(points[0].second)).append(" m\n")
        for (i in 1 until points.size) sb.append(n(points[i].first)).append(' ').append(n(points[i].second)).append(" l\n")
        sb.append("S\n")
    }

    fun rect(x: Double, y: Double, w: Double, h: Double) = apply {
        sb.append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re S\n")
    }

    fun fillRect(x: Double, y: Double, w: Double, h: Double) = apply {
        sb.append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n")
    }

    /** Clips everything until the matching restore() to a rectangle. */
    fun clip(x: Double, y: Double, w: Double, h: Double) = apply {
        sb.append("q\n").append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re W n\n")
    }

    fun text(x: Double, y: Double, sizePt: Double, s: String, bold: Boolean = false) = apply {
        sb.append("BT /").append(if (bold) "F2" else "F1").append(' ').append(n(sizePt)).append(" Tf ")
            .append(n(x)).append(' ').append(n(y)).append(" Td (").append(escape(s)).append(") Tj ET\n")
    }

    internal fun content(): String = sb.toString()

    companion object {
        /** Helvetica advance widths are about half an em on average; used only to wrap text. */
        fun approxTextWidth(s: String, sizePt: Double) = s.length * sizePt * 0.52

        /**
         * WinAnsiEncoding is cp1252, which puts these characters in 128 to 159 where Latin-1 has control
         * codes. Everything from 160 to 255 already sits at its own Unicode code point.
         */
        private val WIN_ANSI_128_159 = mapOf(
            '\u20AC' to 128, '\u201A' to 130, '\u0192' to 131, '\u201E' to 132, '\u2026' to 133,
            '\u2020' to 134, '\u2021' to 135, '\u02C6' to 136, '\u2030' to 137, '\u0160' to 138,
            '\u2039' to 139, '\u0152' to 140, '\u017D' to 142, '\u2018' to 145, '\u2019' to 146,
            '\u201C' to 147, '\u201D' to 148, '\u2022' to 149, '\u2013' to 150, '\u2014' to 151,
            '\u02DC' to 152, '\u2122' to 153, '\u0161' to 154, '\u203A' to 155, '\u0153' to 156,
            '\u017E' to 158, '\u0178' to 159,
        )

        /**
         * Text into a PDF string, written as octal escapes so every byte emitted stays 7-bit. Helvetica with
         * WinAnsiEncoding covers Latin-1 and cp1252, so an accented job name prints as typed; a script the
         * font cannot draw at all, such as Cyrillic or CJK, is still the one case that falls back to '?'.
         */
        internal fun escape(s: String): String {
            val out = StringBuilder()
            fun octal(code: Int) = out.append('\\').append(String.format(Locale.ROOT, "%03o", code))
            for (c in s) {
                when {
                    c == '(' || c == ')' || c == '\\' -> out.append('\\').append(c)
                    c == '√' -> out.append("sqrt")
                    c.code in 32..126 -> out.append(c)
                    c.code in 160..255 -> octal(c.code)
                    WIN_ANSI_128_159.containsKey(c) -> octal(WIN_ANSI_128_159.getValue(c))
                    else -> out.append('?')
                }
            }
            return out.toString()
        }
    }
}

object PdfWriter {
    fun write(pages: List<PdfPage>, title: String): ByteArray {
        require(pages.isNotEmpty())
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun emit(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(id: Int, body: String) {
            while (offsets.size < id) offsets.add(0)
            offsets[id - 1] = out.size()
            emit("$id 0 obj\n$body\nendobj\n")
        }
        emit("%PDF-1.4\n%âãÏÓ\n")
        val firstPageId = 5
        val kids = pages.indices.joinToString(" ") { "${firstPageId + it * 2} 0 R" }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        pages.forEachIndexed { i, p ->
            val pageId = firstPageId + i * 2
            val contentId = pageId + 1
            val w = String.format(Locale.ROOT, "%.2f", p.widthPt)
            val h = String.format(Locale.ROOT, "%.2f", p.heightPt)
            obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $contentId 0 R >>")
            val bytes = p.content().toByteArray(Charsets.ISO_8859_1)
            obj(contentId, "<< /Length ${bytes.size} >>\nstream\n${p.content()}endstream")
        }
        val infoId = firstPageId + pages.size * 2
        obj(infoId, "<< /Title (${PdfPage.escape(title)}) /Producer (Dutyclock) >>")
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $infoId 0 R >>\nstartxref\n$xref\n%%EOF\n")
        emit(sb.toString())
        return out.toByteArray()
    }
}
