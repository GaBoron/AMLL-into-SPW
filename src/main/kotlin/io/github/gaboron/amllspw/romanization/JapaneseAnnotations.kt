package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.*

/** Load-time enrichment only. Originals, source words and all base timestamps remain intact. */
internal class JapaneseAnnotations(private val readings: JapaneseReadings = JapaneseReadings()) {
    private val cache = object : LinkedHashMap<LyricLine, List<LyricAnnotation>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricLine, List<LyricAnnotation>>): Boolean = size > 128
    }
    private var failureReported = false

    fun apply(document: LyricDocument, enabled: Boolean): LyricDocument {
        if (!enabled) return document
        val japanese = document.metadata.any { it.key == "language" && it.values.any { value ->
            value.substringBefore('-').substringBefore('_').lowercase() in setOf("ja", "jpn")
        } } || document.lines.any { line -> line.text.any(::isKana) }
        var remaining = 32_768
        val lines = document.lines.map { line ->
            if (!japanese ||
                line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } ||
                (!line.text.any(::isKana) && !line.text.codePoints().anyMatch(Character::isIdeographic))) return@map line
            if (line.text.length !in 1..512 || line.text.length > remaining ||
                line.romanizationSyllables.isNotEmpty()) return@map if (line.romanization.isNotBlank())
                    line.copy(romanizationAbove = true) else line
            // Uncertain matches retain the supplied line above the lyric, rather than changing sides.
            val display = if (line.romanization.isNotBlank()) line.copy(romanizationAbove = true) else line
            val existing = line.rubyAnnotations()
            if (line.text.indices.filter { line.text[it].isLetterOrDigit() }.all { index ->
                existing.any { index >= it.start && index < it.end }
            }) return@map display
            remaining -= line.text.length
            val generated = cache.getOrPut(line) { runCatching { annotate(line) }.getOrElse {
                if (!failureReported) { System.err.println("AMLL: 日语注音分析失败: ${it.message}"); failureReported = true }
                emptyList()
            } }
            if (generated.isEmpty()) display else display.copy(annotations = line.annotations + generated)
        }
        return if (lines == document.lines) document else document.copy(lines = lines)
    }

    private fun isKana(char: Char): Boolean = char in '\u3041'..'\u3096' || char in '\u30a1'..'\u30fa' || char in '\uff66'..'\uff9d'

    private fun annotate(line: LyricLine): List<LyricAnnotation> {
        val spans = readings.analyze(line.text)
        val supplied = line.romanization.takeIf(String::isNotBlank)?.let { RomanizationAlignment.match(spans, it) }
        val existing = line.rubyAnnotations()
        if (line.romanization.isNotBlank() && supplied == null) return PartialRomanizationAlignment
            .align(line.text, spans, line.romanization).filter { span -> existing.none { it.start < span.end && span.start < it.end } }
        return (supplied ?: spans.map { RomanizationAlignment.Match(it, it.roman) }).mapNotNull { (span, roman) ->
            if (!span.annotate || span.roman.isBlank() || existing.any { it.start < span.end && span.start < it.end }) null
            else LyricAnnotation(span.start, span.end, listOf(LyricSyllable(roman)))
        }
    }
}
