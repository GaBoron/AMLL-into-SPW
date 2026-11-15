package io.github.gaboron.amllspw.spectrum

import kotlin.math.*

/** One-sample lookahead averages fine chatter while retaining the height of distinct musical peaks. */
internal class SpectrumTargetBuffer(bands: Int) {
    val levels = FloatArray(bands)
    private val samples = Array(3) { FloatArray(bands) }
    private var cursor = 0

    fun accept(input: FloatArray) {
        val newest = samples[cursor]
        val center = samples[(cursor + 2) % 3]
        val oldest = samples[(cursor + 1) % 3]
        input.copyInto(newest)
        for (band in levels.indices) {
            val a = oldest[band]
            val b = center[band]
            val c = newest[band]
            val range = maxOf(a, b, c) - minOf(a, b, c)
            val change = ((range - .04f) / .12f).coerceIn(0f, 1f)
            val smoothing = 1f - change * change * (3f - 2f * change)
            val average = (a + 2f * b + c) * .25f
            levels[band] = b + (average - b) * smoothing
        }
        cursor = (cursor + 1) % 3
    }

    fun clear() {
        samples.forEach { it.fill(0f) }
        levels.fill(0f)
        cursor = 0
    }
}
