package io.github.gaboron.amllspw.timeline

import kotlin.math.abs

/** Track-bound playback samples remain available even when the host has no decoded lyrics. */
internal class PlaybackClock(private val nanoTime: () -> Long = System::nanoTime) {
    data class Sample(val positionMs: Long, val playing: Boolean?, val seekGeneration: Long, val playbackRate: Float?)

    private class Seek(val target: Long, var origin: Long, val requestedAt: Long, val superseded: List<Long>) {
        var confirmed = false
        var anchor = target
        var anchorStamp = requestedAt
        private var originStamp = requestedAt

        fun onOrigin(raw: Long, now: Long, rate: Double, playing: Boolean): Boolean {
            val elapsed = ((now - originStamp) / 1_000_000.0).coerceAtLeast(0.0)
            val expected = origin + if (playing) elapsed * rate else 0.0
            return abs(raw - expected) <= maxOf(80.0, 250.0 * rate)
        }

        fun accepts(raw: Long, now: Long, rate: Double, playing: Boolean): Boolean {
            fun projected(position: Long, stamp: Long) = position +
                if (playing) ((now - stamp) / 1_000_000.0).coerceAtLeast(0.0) * rate else 0.0
            val expected = projected(anchor, anchorStamp)
            val distance = if (confirmed) abs(raw - expected)
                else minOf(abs(raw - expected), abs(raw - target).toDouble())
            val tolerance = maxOf(80.0, 250.0 * rate)
            if (!confirmed) {
                if (distance > tolerance) return false
                // Merely playing past a nearby requested time is not confirmation.
                // The new trajectory must also separate from uninterrupted old playback.
                val old = abs(raw - projected(origin, originStamp))
                if (old <= minOf(80.0, abs(target - origin) * .5)) return false
            }
            if (distance <= 80.0) return true
            val oldDistance = abs(raw - projected(origin, originStamp))
            if (oldDistance <= tolerance && oldDistance < distance) return false
            return superseded.none {
                val old = minOf(abs(raw - it).toDouble(), abs(raw - projected(it, requestedAt)))
                old <= tolerance && old < distance
            }
        }

        fun confirm(raw: Long, now: Long) {
            confirmed = true
            anchor = raw
            anchorStamp = now
        }

        fun retime(now: Long, rate: Double, playing: Boolean) {
            if (playing) {
                origin += (((now - originStamp) / 1_000_000.0).coerceAtLeast(0.0) * rate).toLong()
                anchor += (((now - anchorStamp) / 1_000_000.0).coerceAtLeast(0.0) * rate).toLong()
            }
            originStamp = now
            anchorStamp = now
        }
    }

    private var trackKey = ""
    private var position = 0L
    private var stamp = nanoTime()
    private var playing = false
    private var playStateKnown = false
    private var sampled = false
    private val speed = PlaybackRateEstimator()
    private var pendingSeek: Seek? = null
    @Volatile var seekGeneration = 0L; private set

    @Synchronized fun sync(track: String, ms: Long, seek: Boolean = false) {
        select(track)
        val now = nanoTime()
        val raw = ms.coerceAtLeast(0)
        var request = pendingSeek
        if (request != null && now - request.requestedAt > 2_000_000_000L) {
            pendingSeek = null
            request = null
            speed.reset(raw, now)
        }
        var confirmedSeek = false
        if (seek) {
            if (request != null && abs(raw - request.target) > 80 &&
                request.superseded.any { abs(raw - it) <= 80 }) return
            if (request != null && abs(raw - request.target) <= 80 && request.confirmed) return
            if (request == null || abs(raw - request.target) > 80) {
                request = Seek(raw, current(), now, emptyList())
                pendingSeek = request
            }
            request.confirm(raw, now)
            confirmedSeek = true
        } else if (request != null) {
            if (!request.accepts(raw, now, speed.rate, playing)) {
                // Until the host confirms a seek, valid old-position callbacks still
                // keep normal playback alive if the request is delayed or rejected.
                if (request.confirmed || !request.onOrigin(raw, now, speed.rate, playing)) return
            } else if (!request.confirmed) {
                request.confirm(raw, now)
                confirmedSeek = true
            }
        }
        val elapsed = ((now - stamp) / 1_000_000.0).coerceAtLeast(0.0)
        val discontinuity = confirmedSeek || (sampled && (raw < position - 80 || raw - position > elapsed * 8 + 1_000))
        if (!sampled || discontinuity || (playStateKnown && !playing)) speed.reset(raw, now)
        else if (speed.observe(raw, now) && !playStateKnown) { playing = true; playStateKnown = true }
        position = raw; stamp = now; sampled = true
        if (request?.confirmed == true) request.confirm(raw, now)
        if (discontinuity) seekGeneration++
    }

    /** Register intent before calling the host; publish the new position only on confirmation. */
    @Synchronized fun expectSeek(track: String, targetMs: Long) {
        select(track)
        val superseded = pendingSeek?.let { (it.superseded + it.target).takeLast(8) }.orEmpty()
        pendingSeek = Seek(targetMs.coerceAtLeast(0), current(), nanoTime(), superseded)
    }

    @Synchronized fun cancelExpectedSeek(track: String, targetMs: Long) {
        if (track == trackKey && pendingSeek?.let { !it.confirmed && it.target == targetMs } == true) {
            pendingSeek = null
        }
    }
    @Synchronized fun playing(track: String, value: Boolean) {
        select(track)
        val now = nanoTime()
        pendingSeek?.retime(now, speed.rate, playing)
        position = current()
        stamp = now
        playing = value
        playStateKnown = true
        speed.reset(position, stamp)
    }
    @Synchronized fun playingState(track: String): Boolean? = playing.takeIf { track == trackKey && playStateKnown }
    @Synchronized fun playbackRate(track: String): Float? = speed.rate.toFloat().takeIf { track == trackKey }
    @Synchronized fun now(track: String): Long = if (track == trackKey && track.isNotEmpty()) current() else 0
    /** The display must not mix position from one callback with a later seek generation/rate. */
    @Synchronized fun sample(track: String): Sample = if (track == trackKey && track.isNotEmpty())
        Sample(current(), playing.takeIf { playStateKnown }, seekGeneration, speed.rate.toFloat())
    else Sample(0, null, seekGeneration, null)
    @Synchronized fun clear() { select("") }

    private fun current(): Long = position + if (playing && sampled)
        (((nanoTime() - stamp) / 1_000_000.0).coerceIn(0.0, 1_500.0) * speed.rate).toLong() else 0

    private fun select(track: String) {
        if (track == trackKey) return
        trackKey = track; position = 0; stamp = nanoTime()
        playing = false; playStateKnown = false; sampled = false
        pendingSeek = null
        speed.reset(0, stamp, clearRate = true); seekGeneration++
    }
}
