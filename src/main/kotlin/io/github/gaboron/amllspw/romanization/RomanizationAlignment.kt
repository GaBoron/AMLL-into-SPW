package io.github.gaboron.amllspw.romanization

import java.text.Normalizer
import java.util.Locale

/** Whole-line exact alignment with bounded spelling alternatives, never positional guessing. */
internal object RomanizationAlignment {
    internal data class Normalized(val text: String, val offsets: List<IntRange>)
    data class Match(val reading: JapaneseReading, val text: String)
    private data class Part(val reading: JapaneseReading, val range: IntRange)
    private data class Path(val position: Int, val parts: List<Part>)
    private val longVowels = setOf("aa", "ii", "uu", "ee", "oo", "ou", "ei")

    fun match(readings: List<JapaneseReading>, romanization: String): List<Match>? {
        if (readings.isEmpty() || readings.any { it.roman.isBlank() } || '\n' in romanization) return null
        val source = normalize(romanization)
        val pending = Array(readings.size + 1) { mutableListOf<Path>() }
        pending[0] += Path(0, emptyList())
        fun boundary(index: Int) = index == 0 || index == source.text.length ||
            source.offsets[index - 1].last < source.offsets[index].first
        for ((index, reading) in readings.withIndex()) {
            // Keep at most two distinct histories for each cursor: enough to detect ambiguity.
            val paths = pending[index].groupBy { it.position }.values.flatMap { it.distinct().take(2) }
            if (paths.size > 64) return null
            val next = readings.getOrNull(index + 1)
            val combined = next?.takeIf { reading.annotate && it.annotate && reading.end == it.start &&
                reading.roman.takeLast(1) + it.roman in longVowels
            }?.let { reading.copy(end = it.end, roman = reading.roman + it.roman) }
            for (path in paths) for ((count, candidate) in listOfNotNull(1 to reading, combined?.let { 2 to it })) {
                for (variant in variants(candidate)) {
                    val end = path.position + variant.length
                    if (variant.isEmpty() || end > source.text.length || !boundary(end) ||
                        !source.text.startsWith(variant, path.position)) continue
                    val range = path.position until end
                    val raw = romanization.substring(source.offsets[range.first].first, source.offsets[range.last].last + 1)
                    // Only an actual macron joins two units. Repeated あ / い keep their own clocks.
                    if (count == 2 && raw.none { it.lowercaseChar() in "āīūēō" }) continue
                    pending[index + count] += Path(end, path.parts + Part(candidate, range))
                }
            }
        }
        val complete = pending.last().filter { it.position == source.text.length }.distinct()
        if (complete.size != 1) return null
        return complete.single().parts.map { (reading, range) ->
            Match(reading, romanization.substring(source.offsets[range.first].first, source.offsets[range.last].last + 1))
        }
    }

    internal fun variants(reading: JapaneseReading): Set<String> {
        val expected = normalize(reading.roman).text
        return linkedSetOf(expected).apply {
            if (expected.contains("ou")) { add(expected.replace("ou", "oo")); add(expected.replace("ou", "o")) }
            if (expected.contains("uu")) add(expected.replace("uu", "u"))
            if (expected.contains("oo")) add(expected.replace("oo", "ou"))
            if (expected.contains("ei")) add(expected.replace("ei", "ee"))
            if (expected.contains("ee")) add(expected.replace("ee", "ei"))
            if (reading.roman == "wa") add("ha")
            if (reading.roman == "e") add("he")
        }
    }

    internal fun normalize(text: String): Normalized {
        val chars = StringBuilder()
        val offsets = mutableListOf<IntRange>()
        text.forEachIndexed { index, char ->
            val value = when (char.lowercaseChar()) {
                'ā' -> "aa"; 'ī' -> "ii"; 'ū' -> "uu"; 'ē' -> "ee"; 'ō' -> "ou"
                else -> Normalizer.normalize(char.toString(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            }
            value.filter { it in 'a'..'z' || it.isDigit() }.forEach { chars.append(it); offsets += index..index }
        }
        // Normalize conventional Hepburn / Kunrei alternatives while retaining source indices.
        var value = chars.toString()
        val map = offsets.toMutableList()
        for ((from, to) in listOf("shi" to "si", "chi" to "ti", "tsu" to "tu", "fu" to "hu", "ji" to "zi", "wo" to "o")) {
            var start = value.indexOf(from)
            while (start >= 0) {
                value = value.replaceRange(start, start + from.length, to)
                val original = map[start].first..map[start + from.length - 1].last
                repeat(from.length) { map.removeAt(start) }
                repeat(to.length) { map.add(start, original) }
                start = value.indexOf(from, start + to.length)
            }
        }
        return Normalized(value, map)
    }
}
