package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.abs
import kotlin.math.sqrt

/** Couple risky exits while ordinary line changes retain their individual spring wave. */
internal class LyricScrollSpring {
    private val offset = SpringMotion()
    private var residuals = emptyList<SpringMotion>()
    private var previousTops = floatArrayOf()
    private var active = false
    private var finishingCollapse = false

    fun update(tops: FloatArray, heights: FloatArray, positions: List<SpringMotion>, focus: Int,
        targetOffset: Float, dt: Float, enabled: Boolean, coupled: Boolean, stiffness: Float,
        minimumY: FloatArray, collapsing: Boolean): FloatArray? {
        if (!enabled || (active && residuals.size != positions.size)) {
            active = false; finishingCollapse = false; return null
        }
        if (collapsing) finishingCollapse = true
        // Position and velocity were transferred back to each row on the previous frame.
        // Release collision-only coupling while the per-row springs are still moving.
        if (!coupled && !finishingCollapse) { active = false; return null }
        if (!active) {
            val origin = tops[focus] - positions[focus].value
            offset.transfer(origin, -positions[focus].velocity)
            // Preserve every current position on entry. Common critically damped springs
            // restore the layout without introducing per-row start delays or crossings.
            residuals = positions.mapIndexed { index, row -> SpringMotion().apply {
                transfer(row.value - (tops[index] - origin), row.velocity + offset.velocity)
            } }
            active = true
        } else {
            val shift = tops[focus] - previousTops[focus]
            offset.value += shift
            residuals.forEachIndexed { index, row -> row.value += shift - (tops[index] - previousTops[index]) }
        }
        previousTops = tops.copyOf()
        offset.configure(stiffness, 2f * sqrt(stiffness))
        offset.update(targetOffset, dt)
        val bounds = FloatArray(tops.size) { index ->
            if (positions[index].value >= minimumY[index] - .01f) minimumY[index] else Float.NEGATIVE_INFINITY
        }
        val result = FloatArray(tops.size) { index ->
            // Slot corrections must catch up to the shared spring instead of adding a
            // second, delayed scroll after completed text has freed its space.
            val correctionStiffness = stiffness * 4f
            residuals[index].configure(correctionStiffness, 2f * sqrt(correctionStiffness))
            val base = tops[index] - offset.value
            val y = base + residuals[index].update(0f, dt)
            // Stop an older retained voice at its final visible position. Do not pull a
            // newly active, previously offscreen voice directly into the viewport.
            maxOf(y, bounds[index])
        }
        preserveLyricRowSpacing(result, heights, focus, bounds)
        result.forEachIndexed { index, y ->
            val residual = y - (tops[index] - offset.value)
            if (abs(residual - residuals[index].value) > .001f) residuals[index].transfer(residual, residuals[index].velocity)
        }
        // Closing slots must finish following the planned layout before releasing rows.
        if (!coupled && abs(offset.value - targetOffset) < .3f && abs(offset.velocity) < 1f &&
            residuals.all { abs(it.value) < .3f && abs(it.velocity) < 1f }) {
            active = false; finishingCollapse = false
        }
        return result
    }
}
