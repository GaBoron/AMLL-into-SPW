package io.github.gaboron.amllspw.romanization

import java.text.Normalizer
import java.util.Locale

/** Whole-line exact alignment with bounded spelling alternatives, never positional guessing. */
internal object RomanizationAlignment {
    private data class Normalized(val text: String, val offsets: List<IntRange>)
    private data class Path(val position: Int, val ranges: List<IntRange>)

    fun match(readings: List<JapaneseReading>, romanization: String): List<String>? {
        if (readings.isEmpty() || readings.any { it.roman.isBlank() } || '\n' in romanization) return null
        val source = normalize(romanization)
        var paths = listOf(Path(0, emptyList()))
        for (reading in readings) {
            val expected = normalize(reading.roman).text
            val variants = linkedSetOf(expected)
            // Common long-vowel spellings. Ambiguous assignments are rejected below.
            if (expected.contains("ou")) { variants += expected.replace("ou", "oo"); variants += expected.replace("ou", "o") }
            if (expected.contains("uu")) variants += expected.replace("uu", "u")
            if (expected.contains("oo")) variants += expected.replace("oo", "ou")
            if (reading.roman == "wa") variants += "ha"
            if (reading.roman == "e") variants += "he"
            val next = paths.flatMap { path -> variants.filter {
                it.isNotEmpty() && source.text.startsWith(it, path.position)
            }.map { Path(path.position + it.length, path.ranges + listOf(path.position until path.position + it.length)) } }
            // Keep at most two distinct histories for each cursor: enough to detect ambiguity.
            paths = next.groupBy { it.position }.values.flatMap { it.distinct().take(2) }
            if (paths.isEmpty() || paths.size > 64) return null
        }
        val complete = paths.filter { it.position == source.text.length }
        if (complete.size != 1) return null
        if (complete.single().ranges.zipWithNext().any { (left, right) ->
            source.offsets[left.last].last >= source.offsets[right.first].first
        }) return null
        return complete.single().ranges.map { range ->
            romanization.substring(source.offsets[range.first].first, source.offsets[range.last].last + 1)
        }
    }

    private fun normalize(text: String): Normalized {
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
