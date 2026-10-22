package io.github.gaboron.amllspw.lyrics

/** Authored syllable timing is independent of the base lyric's highlight timing. */
data class LyricSyllable(val text: String, val startMs: Long = 0, val endMs: Long = 0, val timed: Boolean = false)

/** Display annotation over a half-open UTF-16 range; never splits or retimes source words. */
data class LyricAnnotation(val start: Int, val end: Int, val syllables: List<LyricSyllable>) {
    val text: String get() = syllables.joinToString("") { it.text }
}

internal fun LyricLine.rubyAnnotations(): List<LyricAnnotation> = buildList {
    var offset = 0
    for (word in words) {
        if (word.ruby.isNotBlank()) add(LyricAnnotation(offset, offset + word.text.length,
            word.rubySyllables.ifEmpty { listOf(LyricSyllable(word.ruby)) }))
        offset += word.text.length
    }
    addAll(annotations.filter { annotation -> none { it.start < annotation.end && annotation.start < it.end } })
}.filter { it.start >= 0 && it.end <= text.length && it.start < it.end && it.text.isNotBlank() }.sortedBy { it.start }

/** Match authored transliteration by time, without enabling automatic Japanese analysis. */
internal object TimedRomanization {
    fun attach(line: LyricLine): LyricLine {
        if (line.romanizationSyllables.isEmpty() || !line.hasWordTiming) return line
        val offsets = line.words.runningFold(0) { offset, word -> offset + word.text.length }
        val matches = linkedMapOf<Int, MutableList<LyricSyllable>>()
        for (syllable in line.romanizationSyllables) {
            val candidates = line.words.withIndex().filter { it.value.timed && it.value.text.isNotBlank() }.map { (i, word) ->
                val overlap = (minOf(word.endMs, syllable.endMs) - maxOf(word.startMs, syllable.startMs)).coerceAtLeast(0)
                val union = (maxOf(word.endMs, syllable.endMs) - minOf(word.startMs, syllable.startMs)).coerceAtLeast(1)
                val contained = overlap.toDouble() / (syllable.endMs - syllable.startMs).coerceAtLeast(1)
                val score = if (kotlin.math.abs(word.startMs - syllable.startMs) <= 2 &&
                    kotlin.math.abs(word.endMs - syllable.endMs) <= 2) 2.0 else overlap.toDouble() / union
                i to maxOf(score, if (contained >= .9) contained * .9 else 0.0)
            }.sortedByDescending { it.second }
            val best = candidates.firstOrNull() ?: continue
            if (best.second >= .5 && (candidates.size == 1 || best.second - candidates[1].second > .05))
                matches.getOrPut(best.first) { mutableListOf() }.add(syllable)
        }
        return line.copy(annotations = matches.map { (index, syllables) ->
            LyricAnnotation(offsets[index], offsets[index + 1], syllables)
        })
    }
}
