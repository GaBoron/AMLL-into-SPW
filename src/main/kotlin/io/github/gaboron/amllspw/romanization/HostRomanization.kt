package io.github.gaboron.amllspw.romanization

/** Checks supplied Japanese readings without enabling generated display annotations. */
internal object HostRomanization {
    fun matches(text: String, readings: List<JapaneseReading>, value: String): Boolean {
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
