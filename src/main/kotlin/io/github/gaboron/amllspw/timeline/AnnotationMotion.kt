package io.github.gaboron.amllspw.timeline

import io.github.gaboron.amllspw.lyrics.LyricWord

/** A measured slice of an existing word, without creating any annotation timestamps. */
internal data class AnnotationTimingPart(val word: LyricWord, val from: Float, val to: Float, val weight: Float)

/** Romanization shares the source word's mask and container float, as in upstream AMLL. */
internal class AnnotationMotion(private val parts: List<AnnotationTimingPart>, private val background: Boolean) {
    fun progress(timeMs: Long): Float = parts.sumOf { part ->
        val progress = WordAnimation.progress(part.word, timeMs)
        (((progress - part.from) / (part.to - part.from).coerceAtLeast(.0001f)).coerceIn(0f, 1f) * part.weight).toDouble()
    }.toFloat().coerceIn(0f, 1f)

    fun floatLift(timeMs: Long): Float = parts.sumOf { part ->
        (WordAnimation.floatLift(part.word, timeMs, background) * part.weight).toDouble()
    }.toFloat()
}
