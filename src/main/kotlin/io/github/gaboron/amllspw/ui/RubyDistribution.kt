package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.LyricAnnotation
import io.github.gaboron.amllspw.lyrics.LyricSyllable

/** Spread oversized unmatched readings across base graphemes so they can wrap with the lyric. */
internal fun distributeWideRuby(text: String, annotations: List<LyricAnnotation>, width: Int,
    measure: (String) -> Float): List<LyricAnnotation> = annotations.flatMap { annotation ->
    val value = text.substring(annotation.start, annotation.end)
    if (!annotation.distribute || ('\n' !in value && measure(value) <= width)) return@flatMap listOf(annotation)
    val base = Regex("\\X").findAll(value).filter { it.value.any(Char::isLetterOrDigit) }.toList()
    val roman = Regex("\\X").findAll(annotation.text).map { it.value }.toList()
    if (base.isEmpty() || roman.isEmpty()) return@flatMap listOf(annotation)
    val weights = base.map { measure(it.value).coerceAtLeast(1f) }
    val total = weights.sum()
    var cursor = 0
    var edge = weights.first()
    val groups = linkedMapOf<Int, StringBuilder>()
    roman.forEachIndexed { index, character ->
        val target = (index + .5f) / roman.size * total
        while (cursor < base.lastIndex && target >= edge) { cursor++; edge += weights[cursor] }
        groups.getOrPut(cursor) { StringBuilder() }.append(character)
    }
    groups.map { (index, reading) ->
        val range = base[index].range
        LyricAnnotation(annotation.start + range.first, annotation.start + range.last + 1,
            listOf(LyricSyllable(reading.toString())))
    }
}
