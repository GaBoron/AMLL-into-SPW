package io.github.gaboron.amllspw.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

internal data class JapaneseReading(val start: Int, val end: Int, val roman: String, val annotate: Boolean = true)

/** Owns the lazily loaded dictionary; offsets always refer to the untouched lyric text. */
internal class JapaneseReadings {
    private val tokenizer by lazy { Tokenizer() }

    fun analyze(text: String): List<JapaneseReading> = buildList {
        var consumed = 0
        for (token in tokenizer.tokenize(text)) {
            val surface = token.surface
            val start = token.position
            if (start < consumed) continue
            if (start < 0 || start + surface.length > text.length ||
                text.substring(start, start + surface.length) != surface) continue
            // IPADIC can split these irregular person counters into numeral + suffix.
            val person = when {
                text.startsWith("一人", start) -> "hitori"
                text.startsWith("二人", start) -> "futari"
                else -> null
            }
            if (person != null && surface.length <= 2 &&
                text.getOrNull(start + 2)?.let { Character.isIdeographic(it.code) } != true) {
                add(JapaneseReading(start, start + 2, person)); consumed = start + 2; continue
            }
            val kana = KanaRomanizer.hiragana(surface)
            val isKana = kana.all { it in '\u3041'..'\u3096' || it == 'ー' }
            if (isKana) {
                val particle = if (token.partOfSpeechLevel1 == "助詞") when (surface) {
                    "は" -> "wa"; "へ" -> "e"; "を" -> "o"; else -> null
                } else null
                if (particle != null) add(JapaneseReading(start, start + surface.length, particle))
                else KanaRomanizer.units(surface)?.forEach { (range, roman) ->
                    add(JapaneseReading(start + range.first, start + range.last + 1, roman))
                }
            } else if (surface.codePoints().anyMatch(Character::isIdeographic)) {
                val reading = token.reading.takeUnless { it == "*" }.orEmpty()
                addAll(kanjiSpans(start, surface, reading))
            } else if (surface.any { it.isLetterOrDigit() }) {
                add(JapaneseReading(start, start + surface.length, surface, annotate = false))
            }
        }
    }

    /** Kana anchors can separate okurigana; ambiguous kanji compounds stay whole. */
    private fun kanjiSpans(start: Int, surface: String, reading: String): List<JapaneseReading> {
        val whole = listOf(JapaneseReading(start, start + surface.length, KanaRomanizer.convert(reading).orEmpty()))
        val kana = KanaRomanizer.hiragana(reading)
        val parts = Regex("\\p{IsHan}+|[\\u3041-\\u3096\\u30a1-\\u30faー]+").findAll(surface).toList()
        if (parts.joinToString("") { it.value } != surface) return whole
        var cursor = 0
        val result = mutableListOf<JapaneseReading>()
        for ((index, part) in parts.withIndex()) {
            if (part.value.codePoints().allMatch(Character::isIdeographic)) {
                val next = parts.getOrNull(index + 1)?.value?.let(KanaRomanizer::hiragana)
                val end = if (next == null) kana.length else kana.indexOf(next, cursor).also {
                    if (it < 0 || kana.indexOf(next, it + 1) >= 0) return whole
                }
                val roman = KanaRomanizer.convert(kana.substring(cursor, end))?.takeIf(String::isNotEmpty) ?: return whole
                result += JapaneseReading(start + part.range.first, start + part.range.last + 1, roman)
                cursor = end
            } else {
                val normalized = KanaRomanizer.hiragana(part.value)
                if (!kana.startsWith(normalized, cursor)) return whole
                val units = KanaRomanizer.units(part.value) ?: return whole
                for ((range, roman) in units) result += JapaneseReading(start + part.range.first + range.first,
                    start + part.range.first + range.last + 1, roman)
                cursor += normalized.length
            }
        }
        return result.takeIf { cursor == kana.length } ?: whole
    }
}
