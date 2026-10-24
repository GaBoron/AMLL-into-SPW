package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.abs

/** Give consecutive exits a little more response without anticipating future lyric boundaries. */
internal class LyricExitCohort(private val groups: List<GroupLayout>) {
    private val ends = groups.map { group -> group.rows.maxOf { it.line.endMs } }
    private var focus = -1
    private var lastExit = -1
    private var lastTarget = 0f
    private var response = 1f

    fun stiffnessScale(current: Int, timeMs: Long, positions: List<SpringMotion>, targets: FloatArray,
        height: Float, enabled: Boolean): Float {
        if (!enabled) { focus = current; lastExit = -1; response = 1f; return response }
        if (current != focus) {
            val pending = lastExit in positions.indices && positions[lastExit].value < height &&
                positions[lastExit].value + groups[lastExit].height > 0f &&
                abs(positions[lastExit].value - lastTarget) > 2f
            val exited = (focus.coerceAtLeast(0) until current).lastOrNull { ends[it] <= timeMs }
            response = if (focus >= 0 && current > focus && exited != null && pending) 1.2f else 1f
            lastExit = exited ?: -1
            focus = current
        }
        if (lastExit in targets.indices) lastTarget = targets[lastExit]
        return response
    }
}
