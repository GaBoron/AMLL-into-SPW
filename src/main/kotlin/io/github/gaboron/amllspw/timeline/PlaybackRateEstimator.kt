package io.github.gaboron.amllspw.timeline

import kotlin.math.abs

/** Estimate media speed from progressing host samples, independently of display phase error. */
internal class PlaybackRateEstimator {
    var rate = 1.0; private set
    fun useRate(value: Double) {
        if (value.isFinite() && value in .1..8.0) { rate = value; candidate = null }
    }
    private var position = 0L
    private var stamp = -1L
    private var candidate: Double? = null

    fun reset(positionMs: Long, nanos: Long, clearRate: Boolean = false) {
        position = positionMs
        stamp = nanos
        candidate = null
        if (clearRate) rate = 1.0
    }

    fun observe(positionMs: Long, nanos: Long): Boolean {
        if (stamp < 0 || positionMs < position) { reset(positionMs, nanos); return false }
        val elapsed = (nanos - stamp) / 1_000_000.0
        if (elapsed < 100 || positionMs == position) return false
        val observed = (positionMs - position) / elapsed
        position = positionMs
        stamp = nanos
        if (elapsed > 2_500 || observed !in .1..8.0) {
            candidate = null
            return false
        }
        // A short seek or a late callback can look like a valid high playback rate.
        // Require a second consistent interval before accepting a substantial rate change.
        if (abs(observed - rate) <= maxOf(.05, rate * .2) ||
            candidate?.let { abs(observed - it) <= maxOf(.05, it * .15) } == true) {
            rate = observed
            candidate = null
        } else candidate = observed
        return true
    }
}
