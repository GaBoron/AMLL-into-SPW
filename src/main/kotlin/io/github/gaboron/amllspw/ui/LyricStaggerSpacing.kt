package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlin.math.abs
import kotlin.math.ceil

/** Keep the normal exit wave, advancing only a row whose delayed spring would hit its neighbour. */
internal class LyricStaggerSpacing {
    private var focus = -1
    private var delays = floatArrayOf()
    private var destinations = floatArrayOf()
    private var waiting = booleanArrayOf()
    private var lastElapsed = 0f
    var requiresCoupling = false; private set

    fun update(positions: List<SpringMotion>, targets: FloatArray, extents: List<LyricRowExtent?>,
        nextFocus: Int, elapsed: Float, dt: Float, height: Float, free: BooleanArray, enabled: Boolean): FloatArray {
        if (destinations.size != positions.size) {
            destinations = targets.copyOf()
            waiting = BooleanArray(positions.size)
        }
        if (focus != nextFocus || delays.size != positions.size) {
            val previous = delays
            focus = nextFocus
            delays = FloatArray(positions.size) { index ->
                val delay = (abs(index - focus) * .045f).coerceAtMost(.27f)
                // A fast sequence must not keep postponing a row's first retarget forever.
                if (waiting[index]) minOf(delay, (previous[index] - lastElapsed - dt).coerceAtLeast(0f)) else delay
            }
        }
        lastElapsed = elapsed
        requiresCoupling = false
        if (!enabled) { destinations = targets.copyOf(); waiting.fill(false) }
        if (!enabled || dt <= 0f || height <= 0f) return delays
        val step = dt.coerceIn(1f / 240f, .064f)
        val samples = floatArrayOf(0f, .05f, .1f, .16f, .24f, .34f, .48f)
        val candidates = positions.indices.filter { index ->
            val extent = extents[index] ?: return@filter false
            maxOf(positions[index].value, targets[index]) + extent.bottom >= -48f &&
                minOf(positions[index].value, targets[index]) + extent.top <= height + 48f
        }
        fun project(index: Int, ahead: Float, delay: Float = delays[index]): Float {
            val wait = if (free[index]) 0f else (delay - elapsed).coerceAtLeast(0f)
            val startsIn = ceil(wait / step) * step
            return positions[index].preview(targets[index], step + ahead, destinations[index], startsIn, step)
        }
        fun pairOverlaps(upper: Int, lower: Int, delay: Float): Boolean = samples.any { sample ->
            val ahead = ceil(sample / step) * step
            val a = extents[upper]!!
            val b = extents[lower]!!
            val ay = project(upper, ahead, delay)
            val by = project(lower, ahead)
            ay + a.bottom > 0f && ay + a.top < height && by + b.bottom > 0f && by + b.top < height &&
                by + b.top < ay + a.bottom
        }
        fun collision(): Pair<Int, Int>? {
            for (sample in samples) {
                val ahead = ceil(sample / step) * step
                var previous = -1
                var bottom = Float.NEGATIVE_INFINITY
                for (index in candidates) {
                    val extent = extents[index]!!
                    val y = project(index, ahead)
                    if (y + extent.bottom <= 0f || y + extent.top >= height) continue
                    if (previous >= 0 && y + extent.top < bottom) return previous to index
                    previous = index
                    bottom = y + extent.bottom
                }
            }
            return null
        }
        repeat(candidates.size) {
            val (upper, lower) = collision() ?: return delays
            if (upper >= focus || free[upper] || delays[upper] <= elapsed || pairOverlaps(upper, lower, 0f)) {
                requiresCoupling = true
                return delays
            }
            var low = 0f
            var high = delays[upper]
            repeat(8) {
                val middle = (low + high) * .5f
                if (pairOverlaps(upper, lower, middle)) high = middle else low = middle
            }
            // Do not increase the delay again mid-flight: that would pause an exiting row.
            delays[upper] = low
        }
        requiresCoupling = collision() != null
        return delays
    }

    /** Stagger the new destination, never stop a spring that is already moving. */
    fun target(index: Int, next: Float, elapsed: Float, free: Boolean): Float {
        waiting[index] = !free && elapsed < delays[index] && destinations[index] != next
        if (!waiting[index]) destinations[index] = next
        return destinations[index]
    }
}
