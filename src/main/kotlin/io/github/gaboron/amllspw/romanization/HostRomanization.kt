package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.LyricLine

/** The host flattens LRC translation and romanization tracks into the same secondary text. */
internal object HostRomanization {
    fun extract(line: LyricLine, readings: List<JapaneseReading>): LyricLine {
        if (line.romanization.isNotBlank()) return line
        val secondary = line.translation.lines()
        val supplied = secondary.filter { matches(line.text, readings, it) }.distinct()
        if (supplied.size != 1) return line
        return line.copy(romanization = supplied.single(),
            translation = secondary.filterNot { it == supplied.single() }.joinToString("\n"))
    }

    private fun matches(text: String, readings: List<JapaneseReading>, value: String): Boolean {
        if (value.none { it in 'a'..'z' || it in 'A'..'Z' } || value.any {
                when (Character.UnicodeScript.of(it.code)) {
                    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
                    else -> false
                }
            }) return false
        if (RomanizationAlignment.match(readings, value) != null) return true
        val partial = PartialRomanizationAlignment.align(text, readings, value)
        val matched = RomanizationAlignment.normalize(partial.filterNot { it.distribute }.joinToString("") { it.text }).text.length
        var remaining = RomanizationAlignment.normalize(value).text
        val length = remaining.length
        // Literal names such as Daily / AiSS do not receive ruby and are not mismatched readings.
        readings.filterNot { it.annotate }.forEach { reading ->
            val literal = RomanizationAlignment.normalize(reading.roman).text
            val at = remaining.indexOf(literal)
            if (literal.isNotEmpty() && at >= 0) remaining = remaining.removeRange(at, at + literal.length)
        }
        val literal = length - remaining.length
        // Require substantial phonetic agreement; ordinary Latin-script translations stay below.
        return matched >= 4 && (matched >= remaining.length * .65f || matched + literal >= length * .7f)
    }
}
