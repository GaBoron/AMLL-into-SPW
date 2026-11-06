package io.github.gaboron.amllspw.romanization

import java.text.Normalizer
import java.util.Locale

/** Only complete phonetic or syllable-spelling matches may replace supplied line readings. */
internal object KoreanRomanizationAlignment {
    fun match(text: String, readings: List<KoreanReading>, value: String): List<KoreanReading>? {
        if (value.length > 16_384 || '\n' in value || '\r' in value || readings.none { it.annotate }) return null
        val letters = StringBuilder()
        val offsets = mutableListOf<Int>()
        value.forEachIndexed { index, char ->
            val normalized = normalize(char.toString())
            if (normalized.any { it.isLetterOrDigit() && it !in 'a'..'z' && it !in '0'..'9' }) return null
            normalized.filter { it in 'a'..'z' || it in '0'..'9' }.forEach {
                letters.append(it)
                offsets += index
            }
        }
        if (letters.isEmpty()) return null
        val matched = sequenceOf(readings, KoreanReadings.analyze(text, contextual = false)).firstOrNull { spans ->
            spans.all { span -> normalize(span.roman).all { !it.isLetterOrDigit() || it in 'a'..'z' || it in '0'..'9' } } &&
                spans.joinToString("") { normalize(it.roman).filter(Char::isLetterOrDigit) } == letters.toString()
        } ?: return null
        var cursor = 0
        return matched.map { reading ->
            val length = normalize(reading.roman).count(Char::isLetterOrDigit)
            val start = cursor
            cursor += length
            // A normalized ligature must not be split between two source syllables.
            if (cursor < offsets.size && offsets[cursor - 1] == offsets[cursor]) return null
            reading.copy(roman = value.substring(offsets[start], offsets[cursor - 1] + 1))
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
