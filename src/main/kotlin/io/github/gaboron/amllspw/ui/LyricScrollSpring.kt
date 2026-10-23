package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.sqrt

/** Automatic scrolling keeps one spring and its velocity across consecutive lyric exits. */
internal class LyricScrollSpring {
    private val offset = SpringMotion()
    private var residuals = emptyList<SpringMotion>()
    private var previousTops = floatArrayOf()
    private var active = false

    fun update(tops: FloatArray, heights: FloatArray, positions: List<SpringMotion>, focus: Int,
        targetOffset: Float, dt: Float, enabled: Boolean, stiffness: Float,
        minimumY: FloatArray): FloatArray? {
        if (!enabled || (active && residuals.size != positions.size)) { active = false; return null }
        if (!active) {
            val origin = tops[focus] - positions[focus].value
            offset.snap(origin)
            // Preserve every current position on entry. Common critically damped springs
            // restore the layout without introducing per-row start delays or crossings.
            residuals = positions.mapIndexed { index, row -> SpringMotion(row.value - (tops[index] - origin), 480f, 2f * sqrt(480f)) }
            active = true
        } else {
            val shift = tops[focus] - previousTops[focus]
            offset.value += shift
            residuals.forEachIndexed { index, row -> row.value += shift - (tops[index] - previousTops[index]) }
        }
        previousTops = tops.copyOf()
        offset.configure(stiffness, 2f * sqrt(stiffness))
        offset.updateBounded(targetOffset, dt)
        val bounds = FloatArray(tops.size) { index ->
            if (positions[index].value >= minimumY[index] - .01f) minimumY[index] else Float.NEGATIVE_INFINITY
        }
        val result = FloatArray(tops.size) { index ->
            residuals[index].configure(stiffness, 2f * sqrt(stiffness))
            val base = tops[index] - offset.value
            val y = base + residuals[index].updateBounded(0f, dt)
            // Stop an older retained voice at its final visible position. Do not pull a
            // newly active, previously offscreen voice directly into the viewport.
            maxOf(y, bounds[index])
        }
        preserveLyricRowSpacing(result, heights, focus, bounds)
        result.forEachIndexed { index, y ->
            val residual = y - (tops[index] - offset.value)
            if (kotlin.math.abs(residual - residuals[index].value) > .001f) residuals[index].snap(residual)
        }
        return result
    }
}
