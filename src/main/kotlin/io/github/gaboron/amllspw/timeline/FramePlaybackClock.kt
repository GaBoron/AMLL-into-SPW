package io.github.gaboron.amllspw.timeline

import kotlin.math.abs

/** Phase-locks coarse host samples to the display clock without rewinding on each sample. */
internal class FramePlaybackClock {
    private var lastFrame = -1L
    private var lastSample = 0L
    private var sampleStamp = 0L
    private var windowStamp = 0L
    private var windowSample = 0L
    private var generation = 0L
    private var running = false
    private var value = 0.0
    private var rate = 1.0
    private var correction = 0.0
    var discontinuity = false; private set
    val playbackRate: Float get() = rate.toFloat()

    fun sample(rawMs: Long, frameNanos: Long, playing: Boolean? = null, seekGeneration: Long = 0): Long {
        val raw = rawMs.coerceAtLeast(0)
        if (frameNanos == 0L) return raw
        discontinuity = lastFrame >= 0 && (seekGeneration != generation || raw < lastSample - 80 || abs(raw - value) > 250)
        if (lastFrame < 0 || discontinuity || playing == false) {
            value = raw.toDouble(); sampleStamp = frameNanos; windowStamp = frameNanos; windowSample = raw
            rate = 1.0; correction = 0.0; running = playing == true
        } else {
            val dtMs = ((frameNanos - lastFrame) / 1e6).coerceIn(0.0, 100.0)
            if (raw > lastSample) running = true
            if (running && frameNanos - sampleStamp <= 100_000_000) value += dtMs * (rate + correction).coerceAtLeast(.1)
            if (raw != lastSample) {
                if (frameNanos - windowStamp >= 250_000_000) {
                    rate = ((raw - windowSample) / ((frameNanos - windowStamp) / 1e6)).coerceIn(.1, 8.0)
                    windowStamp = frameNanos; windowSample = raw
                }
                sampleStamp = frameNanos
                correction = ((raw - value) / 250.0).coerceIn(-rate * .1, rate * .1)
            }
        }
        lastFrame = frameNanos; lastSample = raw; generation = seekGeneration
        return value.toLong().coerceAtLeast(0)
    }
}
