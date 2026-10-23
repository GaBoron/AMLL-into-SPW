package io.github.gaboron.amllspw.timeline

/** Estimate media speed from progressing host samples, independently of display phase error. */
internal class PlaybackRateEstimator {
    var rate = 1.0; private set
    private var position = 0L
    private var stamp = -1L

    fun reset(positionMs: Long, nanos: Long, clearRate: Boolean = false) {
        position = positionMs
        stamp = nanos
        if (clearRate) rate = 1.0
    }

    fun observe(positionMs: Long, nanos: Long): Boolean {
        if (stamp < 0 || positionMs < position) { reset(positionMs, nanos); return false }
        val elapsed = (nanos - stamp) / 1_000_000.0
        if (elapsed < 100 || positionMs == position) return false
        val observed = (positionMs - position) / elapsed
        reset(positionMs, nanos)
        if (elapsed > 2_500 || observed !in .1..8.0) return false
        rate = observed
        return true
    }
}
