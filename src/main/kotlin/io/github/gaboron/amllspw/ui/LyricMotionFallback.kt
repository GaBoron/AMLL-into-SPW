package io.github.gaboron.amllspw.ui

/** Dense passages use a shared smooth response without accumulating stagger delays. */
internal class LyricMotionFallback(starts: LongArray) {
    private val intervals: LongArray
    private var catchingUp = false

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

    fun responseSeconds(focus: Int, playbackRate: Float, lagPx: Float, viewportHeight: Float,
        enabled: Boolean = true): Float? {
        val interval = intervals.getOrNull(focus)
        if (!enabled || interval == null) { catchingUp = false; return null }
        val seconds = (interval.toDouble() / playbackRate.coerceIn(.1f, 8f) / 1_000).toFloat()
        val rapid = seconds <= .6f
        if (viewportHeight > 0f && lagPx > viewportHeight * .5f) catchingUp = true
        if (lagPx <= maxOf(2f, viewportHeight * .005f)) catchingUp = false
        if (!rapid && !catchingUp) return null
        val cadence = if (rapid) (seconds * .2f).coerceIn(.016f, .09f) else .09f
        // Keep recovery active until settled, instead of switching modes around a lag threshold.
        return if (catchingUp && viewportHeight > 0f)
            (cadence * (viewportHeight * .25f / lagPx.coerceAtLeast(1f)).coerceIn(.25f, 1f))
                .coerceAtLeast(.008f) else cadence
    }
}
