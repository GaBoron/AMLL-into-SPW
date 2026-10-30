package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.*
import java.util.Locale

/** Metadata-gated enrichment on the lyric IO worker; source text and timings are untouched. */
internal class CantoneseAnnotations(private val readings: CantoneseReadings = CantoneseReadings()) {
    private val cache = object : LinkedHashMap<LyricLine, List<LyricAnnotation>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricLine, List<LyricAnnotation>>): Boolean = size > 128
    }
    private var failureReported = false

    fun apply(document: LyricDocument, enabled: Boolean, audioLanguages: List<String> = emptyList()): LyricDocument {
        if (!enabled || !isCantonese(document, audioLanguages)) return document
        var remaining = 32_768
        val lines = document.lines.map { line ->
            if (line.text.length !in 1..512 || line.text.length > remaining ||
                line.romanizationSyllables.isNotEmpty() || !line.text.codePoints().anyMatch(Character::isIdeographic) ||
                line.text.codePoints().anyMatch {
                    when (Character.UnicodeScript.of(it)) {
                        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL -> true
                        else -> false
                    }
                }) return@map line
            remaining -= line.text.length
            val generated = cache.getOrPut(line) {
                runCatching { annotate(line) }.getOrElse {
                    if (!failureReported) {
                        System.err.println("AMLL: 粤语注音分析失败: ${it.message}")
                        failureReported = true
                    }
                    emptyList()
                }
            }
            if (generated.isEmpty()) line else line.copy(annotations = line.annotations + generated)
        }
        return if (lines == document.lines) document else document.copy(lines = lines)
    }

    private fun annotate(line: LyricLine): List<LyricAnnotation> {
        val spans = readings.analyze(line.text)
        val generated = if (line.romanization.isNotBlank())
            JyutpingAlignment.match(line.text, spans, line.romanization) ?: return emptyList()
        else spans.filter { it.roman.isNotBlank() }.map {
            LyricAnnotation(it.start, it.end, listOf(LyricSyllable(it.roman)))
        }
        val existing = line.rubyAnnotations()
        return generated.filter { span -> existing.none { it.start < span.end && span.start < it.end } }
    }

    companion object {
        /** Audio tags edited in SPW Lyrics take precedence; TTML language is the fallback. */
        fun isCantonese(document: LyricDocument, audioLanguages: List<String>): Boolean = isCantonese(
            audioLanguages.ifEmpty { document.metadata.filter { it.key == "language" }.flatMap { it.values } }
        )

        fun isCantonese(values: List<String>): Boolean = values.asSequence()
            .flatMap { value -> value.lowercase(Locale.ROOT).split(languageSeparator).asSequence().map(String::trim) }
            .any { it in names || it == "yue" || it.startsWith("yue-") || it.startsWith("yue_") ||
                it == "zh-yue" || it.startsWith("zh-yue-") || it == "zh_yue" || it.startsWith("zh_yue_") }

        private val languageSeparator = Regex("[,，;；/|、\\r\\n()（）]+")
        private val names = setOf("粤语", "粵語", "广东话", "廣東話", "广州话", "廣州話", "广府话", "廣府話", "cantonese")
    }
}
