package io.github.gaboron.amllspw.timeline

import kotlin.math.abs

/** Phase-locks coarse host samples to the display clock without rewinding on each sample. */
internal class FramePlaybackClock {
    private var lastFrame = -1L
    private var lastSample = 0L
    private var sampleStamp = 0L
    private val speed = PlaybackRateEstimator()
    private var generation = 0L
    private var running = false
    private var value = 0.0
    private var rate = 1.0
    private var correction = 0.0
    var discontinuity = false; private set
    val playbackRate: Float get() = rate.toFloat()

    fun sample(rawMs: Long, frameNanos: Long, playing: Boolean? = null, seekGeneration: Long = 0,
        sourceRate: Float? = null): Long {
        val raw = rawMs.coerceAtLeast(0)
        if (frameNanos == 0L) return raw
        val knownRate = sourceRate?.takeIf { it.isFinite() && it in .1f..8f }?.toDouble()
        discontinuity = lastFrame >= 0 && (seekGeneration != generation || (knownRate == null && raw < lastSample - 80))
        if (lastFrame < 0 || discontinuity || playing == false) {
            value = raw.toDouble(); sampleStamp = frameNanos; speed.reset(raw, frameNanos)
            rate = knownRate ?: speed.rate; correction = 0.0; running = playing == true
        } else {
            if (knownRate == null) speed.observe(raw, frameNanos)
            rate = knownRate ?: speed.rate
            val dtMs = ((frameNanos - lastFrame) / 1e6).coerceIn(0.0, 100.0)
            if (raw > lastSample) running = true
            if (running && frameNanos - sampleStamp <= 1_500_000_000) value += dtMs * (rate + correction).coerceAtLeast(0.0)
            if (raw != lastSample) {
                sampleStamp = frameNanos
                val error = raw - value
                if (abs(error) > maxOf(180.0, rate * 120.0)) {
                    // A delayed/discontinuous source sample must re-anchor once, rather
                    // than sprinting ahead and then waiting for the audio to catch up.
                    value = raw.toDouble()
                    correction = 0.0
                } else {
                    // Correct small phase error without stopping a running display clock
                    // or resetting every word, interlude and viewport spring.
                    correction = (error / 240.0).coerceIn(-rate * .2, rate * .2)
                }
            }
        }
        lastFrame = frameNanos; lastSample = raw; generation = seekGeneration
        return value.toLong().coerceAtLeast(0)
    }
}
