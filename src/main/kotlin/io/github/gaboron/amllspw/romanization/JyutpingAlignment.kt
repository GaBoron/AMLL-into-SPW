package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.LyricAnnotation
import io.github.gaboron.amllspw.lyrics.LyricSyllable
import java.util.Locale

/** Accept supplied Jyutping only when the complete line agrees with dictionary readings. */
internal object JyutpingAlignment {
    fun match(text: String, readings: List<CantoneseReading>, value: String): List<LyricAnnotation>? {
        if (readings.isEmpty() || value.length !in 1..4096 || '\n' in value || '\r' in value) return null
        val offsets = value.indices.filter { value[it].isLetterOrDigit() }
        if (offsets.any { value[it] !in 'a'..'z' && value[it] !in 'A'..'Z' && value[it] !in '1'..'6' }) return null
        val source = offsets.joinToString("") { value[it].lowercaseChar().toString() }
        if (source.isBlank()) return null
        val numbered = source.any(Char::isDigit)
        val byStart = readings.associateBy { it.start }
        var states = mapOf(0 to emptyList<LyricAnnotation>())
        var index = 0
        while (index < text.length) {
            val reading = byStart[index]
            val point = text.codePointAt(index)
            if (reading == null && !Character.isLetterOrDigit(point)) {
                index += Character.charCount(point)
                continue
            }
            // Literal Latin words may accompany Chinese lyrics, but are never given ruby.
            val end = reading?.end ?: (index until text.length).firstOrNull {
                text[it] !in 'a'..'z' && text[it] !in 'A'..'Z'
            } ?: text.length
            if (end == index) return null
            val variants = if (reading != null) reading.candidates.map { candidate ->
                candidate.filter { it != ' ' && (numbered || !it.isDigit()) }
            }.distinct() else listOf(text.substring(index, end).lowercase(Locale.ROOT))
            val next = linkedMapOf<Int, List<LyricAnnotation>>()
            for ((cursor, annotations) in states) for (variant in variants) {
                if (variant.isEmpty() || !source.startsWith(variant, cursor)) continue
                val to = cursor + variant.length
                if (to in next) continue
                next[to] = if (reading == null) annotations else annotations + LyricAnnotation(index, end,
                    listOf(LyricSyllable(value.substring(offsets[cursor], offsets[to - 1] + 1))))
            }
            if (next.isEmpty()) return null
            states = next
            index = end
        }
        return states[source.length]
    }
}
