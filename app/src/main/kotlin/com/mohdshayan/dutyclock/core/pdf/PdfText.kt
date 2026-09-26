package com.mohdshayan.dutyclock.core.pdf

import java.text.Normalizer

/**
 * PdfWriter draws with Helvetica in WinAnsiEncoding, which covers Latin-1 plus the cp1252 extras
 * (so ó, ä, š and ž print as typed) and prints anything else as '?'. Driver names in this app are
 * often Polish, Romanian or Lithuanian, whose ł, ą, ę, ș, ț, ă, ė, į, ų and ū are outside it.
 * This folds only the letters the font cannot draw: it decomposes them (NFD), drops the combining
 * marks, and maps the few letters that do not decompose (ł, đ, ħ, ı, ŀ). Letters WinAnsi can draw
 * are kept exactly, so "Wójcik" keeps its ó and "Žukauskas" its Ž.
 */
object PdfText {
    private val noDecomposition = mapOf(
        'ł' to "l", 'Ł' to "L", 'đ' to "d", 'Đ' to "D", 'ħ' to "h", 'Ħ' to "H",
        'ı' to "i", 'ŀ' to "l", 'Ŀ' to "L", 'ŧ' to "t", 'Ŧ' to "T",
        '\u2013' to "-", '\u2014' to "-", '\u2212' to "-",
    )

    private val cp1252Extras = setOf(
        '€', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š',
        '‹', 'Œ', 'Ž', '‘', '’', '“', '”', '•', '˜', '™',
        'š', '›', 'œ', 'ž', 'Ÿ',
    )

    fun printable(c: Char): Boolean = c.code in 32..126 || c.code in 160..255 || c in cp1252Extras

    fun fold(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            when {
                printable(c) -> out.append(c)
                noDecomposition.containsKey(c) -> out.append(noDecomposition.getValue(c))
                else -> {
                    val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                    out.append(if (base.isNotEmpty() && base.all { printable(it) }) base else "?")
                }
            }
        }
        return out.toString()
    }
}
