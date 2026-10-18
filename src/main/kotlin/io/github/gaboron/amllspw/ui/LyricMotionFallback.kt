package io.github.gaboron.amllspw.ui

/** Sustained dense passages use a shared response; near-simultaneous voices are one event. */
internal class LyricMotionFallback(starts: LongArray) {
    private val events: LongArray
    private val eventIndexes: IntArray
    private var catchingUp = false
    private var excessiveLagSeconds = 0f

    init {
        val clustered = mutableListOf<Long>()
        val indexes = mutableMapOf<Long, Int>()
        starts.distinct().sorted().forEach { start ->
            // Anchor each cluster at its first start, avoiding a chain of short offsets that
            // would otherwise merge an entire genuinely fast passage into a single event.
            if (clustered.isEmpty() || start - clustered.last() > 180) clustered += start
            indexes[start] = clustered.lastIndex
        }
        events = clustered.toLongArray()
        eventIndexes = IntArray(starts.size) { indexes.getValue(starts[it]) }
    }

    fun responseSeconds(focus: Int, playbackRate: Float, lagPx: Float, viewportHeight: Float,
        enabled: Boolean = true, deltaSeconds: Float = 0f): Float? {
        val event = eventIndexes.getOrNull(focus)
        if (!enabled || event == null) { catchingUp = false; excessiveLagSeconds = 0f; return null }
        val seconds = denseCadence(event, playbackRate.coerceIn(.1f, 8f))
        val rapid = seconds != null
        if (!catchingUp) {
            excessiveLagSeconds = if (viewportHeight > 0f && lagPx > viewportHeight)
                excessiveLagSeconds + deltaSeconds.coerceIn(0f, .064f) else 0f
            if (excessiveLagSeconds >= .35f) catchingUp = true
        }
        if (lagPx <= maxOf(2f, viewportHeight * .005f)) { catchingUp = false; excessiveLagSeconds = 0f }
        if (!rapid && !catchingUp) return null
        val cadence = seconds?.let { (it * .2f).coerceIn(.016f, .09f) } ?: .09f
        // Keep recovery active until settled, instead of switching modes around a lag threshold.
        return if (catchingUp && viewportHeight > 0f)
            (cadence * (viewportHeight * .25f / lagPx.coerceAtLeast(1f)).coerceIn(.25f, 1f))
                .coerceAtLeast(.008f) else cadence
    }

    private fun denseCadence(event: Int, rate: Float): Float? {
        // Require three consecutive gaps (four independent starts), each <= 500 ms of
        // playback. One short pair or a single anomalous timestamp cannot enable the mode.
        for (first in maxOf(0, event - 3)..minOf(event, events.size - 4)) {
            val gaps = (first until first + 3).map { (events[it + 1] - events[it]) / (rate * 1_000f) }
            if (gaps.all { it <= .5f }) return gaps.min()
        }
        return null
    }
}
