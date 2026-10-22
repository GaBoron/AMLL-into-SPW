package io.github.gaboron.amllspw.romanization

import io.github.gaboron.amllspw.lyrics.LyricAnnotation
import io.github.gaboron.amllspw.lyrics.LyricSyllable

/** Anchors reliable reading runs, retaining unmatched original text between them. */
internal object PartialRomanizationAlignment {
    private data class Match(val reading: Int, val start: Int, val end: Int)

    fun align(text: String, readings: List<JapaneseReading>, romanization: String): List<LyricAnnotation> {
        val source = RomanizationAlignment.normalize(romanization)
        val n = readings.size
        val m = source.text.length
        if (n == 0 || m == 0 || (n + 1L) * (m + 1) > 300_000) return emptyList()
        val columns = m + 1
        val scores = IntArray((n + 1) * columns) { Int.MIN_VALUE / 2 }
        val steps = IntArray(scores.size)
        val variants = readings.map { RomanizationAlignment.variants(it).filter(String::isNotEmpty) }
        fun boundary(index: Int) = index == 0 || index == m || source.offsets[index - 1].last < source.offsets[index].first
        fun update(i: Int, j: Int, value: Int, step: Int) {
            val index = i * columns + j
            if (value > scores[index]) { scores[index] = value; steps[index] = step }
        }
        scores[0] = 0
        for (i in 0..n) for (j in 0..m) {
            val index = i * columns + j
            val score = scores[index]
            if (i < n) update(i + 1, j, score - 2, -1)
            if (j < m) update(i, j + 1, score - 1, -2)
            if (i == n || !boundary(j)) continue
            for (variant in variants[i]) {
                val end = j + variant.length
                if (end <= m && boundary(end) && source.text.startsWith(variant, j))
                    update(i + 1, end, score + variant.length * 4 + if (steps[index] > 0) 1 else 0, variant.length)
            }
        }
        var i = n
        var j = m
        val matches = mutableListOf<Match>()
        while (i > 0 || j > 0) {
            when (val step = steps[i * columns + j]) {
                -1 -> i--
                -2 -> j--
                else -> {
                    if (step <= 0) return emptyList()
                    matches += Match(i - 1, j - step, j); i--; j -= step
                }
            }
        }
        matches.reverse()
        // Short isolated vowel coincidences are not useful alignment anchors.
        val runs = mutableListOf<MutableList<Match>>()
        for (match in matches) {
            val last = runs.lastOrNull()?.lastOrNull()
            if (last != null && last.reading + 1 == match.reading && last.end == match.start) runs.last() += match
            else runs += mutableListOf(match)
        }
        val reliable = runs.filter { run -> run.sumOf { it.end - it.start } >= 3 }
        val anchors = reliable.flatten()
        val matched = anchors.sumOf { it.end - it.start }
        val wordAnchor = reliable.any { run ->
            val from = source.offsets[run.first().start].first
            val to = source.offsets[run.last().end - 1].last + 1
            (from == 0 || !romanization[from - 1].isLetterOrDigit()) &&
                (to == romanization.length || !romanization[to].isLetterOrDigit())
        }
        if (matched < 4 || (!wordAnchor && matched < m * .4f)) return emptyList()
        val result = mutableListOf<LyricAnnotation>()
        var baseCursor = 0
        var romanCursor = 0
        fun remainder(end: Int, romanEnd: Int): Boolean {
            val value = romanization.substring(romanCursor, romanEnd).trim()
            if (RomanizationAlignment.normalize(value).text.isEmpty()) return true
            var start = baseCursor
            var stop = end
            while (start < stop && !text[start].isLetterOrDigit()) start++
            while (stop > start && !text[stop - 1].isLetterOrDigit()) stop--
            if (start >= stop) return false
            val syllables = Regex("\\S+\\s*").findAll(value).map { LyricSyllable(it.value) }.toList()
            result += LyricAnnotation(start, stop, syllables, distribute = true)
            return true
        }
        for (anchor in anchors) {
            val reading = readings[anchor.reading]
            val from = source.offsets[anchor.start].first
            val to = source.offsets[anchor.end - 1].last + 1
            if (!remainder(reading.start, from)) return emptyList()
            if (reading.annotate) result += LyricAnnotation(reading.start, reading.end,
                listOf(LyricSyllable(romanization.substring(from, to))))
            baseCursor = reading.end; romanCursor = to
        }
        if (!remainder(text.length, romanization.length)) return emptyList()
        return result
    }
}
