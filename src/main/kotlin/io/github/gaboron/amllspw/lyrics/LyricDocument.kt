package io.github.gaboron.amllspw.lyrics

/** One authored translation alternative, with an optional BCP 47 language tag. */
data class LyricTranslation(val language: String, val text: String)

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val timed: Boolean = true,
    val ruby: String = "",
    val rubySyllables: List<LyricSyllable> = emptyList(),
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord>,
    val translation: String = "",
    val romanization: String = "",
    val agent: String = "",
    val background: Boolean = false,
    val duet: Boolean = false,
    val group: Int = 0,
    val section: String = "",
    val romanizationSyllables: List<LyricSyllable> = emptyList(),
    val annotations: List<LyricAnnotation> = emptyList(),
    val romanizationAbove: Boolean = false,
    val translationVariants: List<LyricTranslation> = emptyList(),
) {
    val text: String get() = words.joinToString("") { it.text }
    val hasWordTiming: Boolean get() = words.any { it.timed }
    fun active(timeMs: Long): Boolean = timeMs >= startMs && timeMs < endMs
}

data class LyricDocument(
    val lines: List<LyricLine>,
    val origin: Origin,
    val originalTtml: String? = null,
    val metadata: List<LyricMetadata> = emptyList(),
    val source: LyricSource = if (origin == Origin.HOST) LyricSource.HOST else LyricSource.UNKNOWN,
) {
    enum class Origin { TTML, HOST, LRC, EMPTY }
    val durationMs = lines.maxOfOrNull { it.endMs } ?: 0L

    /** Plain song text in document order, with the selected translation after each original line. */
    fun plainText(includeTranslation: Boolean = false, translationLanguage: String = TranslationSelection.DEFAULT_LANGUAGE): String = buildList {
        for (line in lines) {
            if (line.text.isBlank()) continue
            add(line.text)
            val translation = line.displayTranslation(translationLanguage)
            if (includeTranslation && translation.isNotBlank()) add(translation)
        }
    }.joinToString("\n")

    companion object { val EMPTY = LyricDocument(emptyList(), Origin.EMPTY) }
}
