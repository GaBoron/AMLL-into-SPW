package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.abs

/** Large moves share one spring translation, so staggered rows cannot overtake outgoing text. */
internal class LyricScrollSpring {
    private val offset = SpringMotion()
    private var residuals = emptyList<SpringMotion>()
    private var previousTops = floatArrayOf()
    private var active = false

    fun update(tops: FloatArray, positions: List<SpringMotion>, focus: Int, focusJump: Int,
        targetOffset: Float, previousTargetOffset: Float, viewportHeight: Float, dt: Float,
        enabled: Boolean, stiffness: Float, damping: Float): FloatArray? {
        if (!enabled || (active && residuals.size != positions.size)) { active = false; return null }
        val largeMove = focusJump > 1 || abs(targetOffset - previousTargetOffset) > maxOf(1f, viewportHeight * .45f)
        if (!active && !largeMove) return null
        if (!active) {
            val origin = tops[focus] - positions[focus].value
            offset.snap(origin)
            // Preserve every current position on entry. Common overdamped residual springs
            // restore the layout without introducing per-row start delays or crossings.
            residuals = positions.mapIndexed { index, row -> SpringMotion(row.value - (tops[index] - origin), 170f, 28f) }
            active = true
        } else {
            val shift = tops[focus] - previousTops[focus]
            offset.value += shift
            residuals.forEachIndexed { index, row -> row.value += shift - (tops[index] - previousTops[index]) }
        }
        previousTops = tops.copyOf()
        offset.configure(stiffness, damping)
        offset.update(targetOffset, dt)
        val result = FloatArray(tops.size) { index -> tops[index] - offset.value + residuals[index].update(0f, dt) }
        if (abs(offset.value - targetOffset) < .5f && abs(offset.velocity) < 2f &&
            residuals.all { abs(it.value) < .5f && abs(it.velocity) < 2f }) active = false
        return result
    }
}
