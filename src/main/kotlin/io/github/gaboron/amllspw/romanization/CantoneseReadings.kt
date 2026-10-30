package io.github.gaboron.amllspw.romanization

internal data class CantoneseReading(val start: Int, val end: Int, val roman: String, val candidates: List<String>)

/** Offline longest-word matching; every result still refers to one original Unicode character. */
internal class CantoneseReadings {
    private val dictionary by lazy {
        runCatching {
            requireNotNull(javaClass.getResourceAsStream("/romanization/jyutping.tsv")).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.associate { line ->
                    val fields = line.split('\t', limit = 2)
                    fields[0] to fields[1].split('|')
                }
            }
        }
    }

    fun analyze(text: String): List<CantoneseReading> {
        val entries = dictionary.getOrThrow()
        val points = text.codePoints().toArray()
        val offsets = IntArray(points.size + 1)
        points.forEachIndexed { index, point -> offsets[index + 1] = offsets[index] + Character.charCount(point) }
        return buildList {
            var index = 0
            while (index < points.size) {
                if (Character.UnicodeScript.of(points[index]) != Character.UnicodeScript.HAN) {
                    index++
                    continue
                }
                var limit = index
                // The bundled dictionary contains words of at most six code points.
                while (limit < minOf(points.size, index + 6) &&
                    Character.UnicodeScript.of(points[limit]) == Character.UnicodeScript.HAN) limit++
                val end = (limit downTo index + 1).firstOrNull {
                    entries.containsKey(text.substring(offsets[index], offsets[it]))
                } ?: (index + 1)
                val chosen = entries[text.substring(offsets[index], offsets[end])]?.firstOrNull().orEmpty()
                val syllables = if (end == index + 1) listOf(chosen) else chosen.split(' ')
                for (at in index until end) {
                    val roman = syllables[at - index]
                    val alternatives = entries[text.substring(offsets[at], offsets[at + 1])].orEmpty()
                    add(CantoneseReading(offsets[at], offsets[at + 1], roman,
                        (listOf(roman) + alternatives).filter(String::isNotBlank).distinct()))
                }
                index = end
            }
        }
    }
}
