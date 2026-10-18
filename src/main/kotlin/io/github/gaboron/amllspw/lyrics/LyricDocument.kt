package io.github.gaboron.amllspw.lyrics

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val timed: Boolean = true,
    val ruby: String = "",
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
    companion object { val EMPTY = LyricDocument(emptyList(), Origin.EMPTY) }
}
