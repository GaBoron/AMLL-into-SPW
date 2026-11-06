package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.*

/** Load-time Korean ruby enrichment; authored annotations and all source timings take priority. */
internal class KoreanAnnotations {
    fun apply(document: LyricDocument, enabled: Boolean): LyricDocument {
        if (!enabled) return document
        var remaining = 32_768
        val lines = document.lines.map { line ->
            if (line.text.length !in 1..512 || line.text.length > remaining ||
                line.romanizationSyllables.isNotEmpty()) return@map line
            remaining -= line.text.length
            val readings = KoreanReadings.analyze(line.text)
            if (readings.none { it.annotate }) return@map line
            val supplied = line.romanization.takeIf(String::isNotBlank)?.let {
                KoreanRomanizationAlignment.match(line.text, readings, it)
            }
            // As with Cantonese ruby, uncertain line-level spellings fall back to generated
            // readings. Rendering suppresses the supplied line once ruby is available.
            val spans = supplied ?: readings
            val existing = line.rubyAnnotations()
            val generated = spans.filter { span ->
                span.annotate && existing.none { it.start < span.end && span.start < it.end }
            }.map { LyricAnnotation(it.start, it.end, listOf(LyricSyllable(it.roman))) }
            if (generated.isEmpty()) line else line.copy(annotations = line.annotations + generated)
        }
        return if (lines == document.lines) document else document.copy(lines = lines)
    }
}
