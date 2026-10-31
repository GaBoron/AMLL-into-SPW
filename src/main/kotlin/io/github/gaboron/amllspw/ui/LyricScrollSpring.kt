package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.abs
import kotlin.math.sqrt

/** Preserve a rebounding common scroll while contacts damp only relative row motion. */
internal class LyricScrollSpring {
    data class Frame(val positions: FloatArray, val velocities: FloatArray)

    private val offset = SpringMotion()
    private var residuals = emptyList<SpringMotion>()
    private var previousTops = floatArrayOf()
    private var active = false

    fun update(tops: FloatArray, extents: List<LyricRowExtent?>, positions: List<SpringMotion>, focus: Int,
        targetOffset: Float, dt: Float, enabled: Boolean, coupled: Boolean, stiffness: Float,
        damping: Float, minimumY: FloatArray, layoutShifts: FloatArray): Frame? {
        if (!enabled || (active && residuals.size != positions.size)) {
            active = false
            return null
        }
        if (!active && !coupled) return null
        if (!active) {
            val origin = tops[focus] - positions[focus].value
            offset.transfer(origin, -positions[focus].velocity)
            // Enter from the full position/velocity vector; switching to contact motion
            // must not restart the scroll or discard a row's existing momentum.
            residuals = positions.mapIndexed { index, row -> SpringMotion().apply {
                transfer(row.value - (tops[index] - origin), row.velocity + offset.velocity)
            } }
            active = true
        } else {
            val shift = tops[focus] - previousTops[focus]
            offset.value += shift
            residuals.forEachIndexed { index, row ->
                row.value += layoutShifts[index] + shift - (tops[index] - previousTops[index])
            }
        }
        previousTops = tops.copyOf()
        val constraints = LyricRowConstraints(extents, focus)
        val correctionStiffness = stiffness * 4f
        val frequency = sqrt(correctionStiffness)
        val bounds = FloatArray(tops.size) { index ->
            if (positions[index].value >= minimumY[index] - .01f) minimumY[index] else Float.NEGATIVE_INFINITY
        }
        val current = FloatArray(tops.size) { tops[it] - offset.value + residuals[it].value }
        val velocities = FloatArray(tops.size) { residuals[it].velocity - offset.velocity }
        constraints.constrainPositions(current, bounds)
        constraints.constrainVelocities(current, velocities, frequency, bounds)
        residuals.forEachIndexed { index, row ->
            row.transfer(current[index] - (tops[index] - offset.value), velocities[index] + offset.velocity)
            row.configure(correctionStiffness, 2f * frequency)
        }

        // Revealing a large backing voice can require more room than its reserved slot.
        // Restore toward a legal text layout rather than repeatedly pushing painted rows.
        val resting = tops.copyOf()
        constraints.constrainPositions(resting)
        val corrections = FloatArray(tops.size) { resting[it] - tops[it] }
        offset.configure(stiffness, damping)
        offset.update(targetOffset, dt)
        val result = FloatArray(tops.size) { index ->
            tops[index] - offset.value + residuals[index].update(corrections[index], dt)
        }
        residuals.indices.forEach { velocities[it] = residuals[it].velocity - offset.velocity }
        constraints.constrainPositions(result, bounds)
        constraints.constrainVelocities(result, velocities, frequency, bounds)
        result.forEachIndexed { index, y ->
            residuals[index].transfer(y - (tops[index] - offset.value), velocities[index] + offset.velocity)
        }
        // Keep one contact regime until its shape has settled. Both regimes use the same
        // scroll response, so the exact velocity can then return to independent springs.
        if (!coupled && residuals.indices.all {
            abs(residuals[it].value - corrections[it]) < .05f && abs(residuals[it].velocity) < .2f
        }) {
            active = false
        }
        return Frame(result, velocities)
    }
}
