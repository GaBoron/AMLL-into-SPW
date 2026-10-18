package io.github.gaboron.amllspw.ui

/** Dense passages must not queue more motion than can finish before the next focus change. */
internal class LyricMotionFallback(starts: LongArray) {
    private val intervals: LongArray

    init {
        // Simultaneous voices are one change, rather than a burst of zero-length intervals.
        val distinct = starts.distinct().sorted()
        val gaps = distinct.mapIndexed { index, start ->
            start to minOf(
                if (index > 0) start - distinct[index - 1] else Long.MAX_VALUE,
                if (index < distinct.lastIndex) distinct[index + 1] - start else Long.MAX_VALUE,
            )
        }.toMap()
        intervals = LongArray(starts.size) { gaps.getValue(starts[it]) }
    }

    fun shouldSnap(focus: Int, playbackRate: Float, lagPx: Float, viewportHeight: Float): Boolean {
        val interval = intervals.getOrNull(focus) ?: return false
        val rapid = interval.toDouble() / playbackRate.coerceIn(.1f, 8f) <= 600.0
        val lagging = viewportHeight > 0f && lagPx > viewportHeight * .5f
        return rapid || lagging
    }
}
