package io.github.gaboron.amllspw.timeline

/** Track-bound playback samples remain available even when the host has no decoded lyrics. */
internal class PlaybackClock(private val nanoTime: () -> Long = System::nanoTime) {
    private var trackKey = ""
    private var position = 0L
    private var stamp = nanoTime()
    private var playing = false
    private var playStateKnown = false
    private var sampled = false
    private val speed = PlaybackRateEstimator()
    @Volatile var seekGeneration = 0L; private set

    @Synchronized fun sync(track: String, ms: Long, seek: Boolean = false) {
        select(track)
        val now = nanoTime()
        val raw = ms.coerceAtLeast(0)
        val elapsed = ((now - stamp) / 1_000_000.0).coerceAtLeast(0.0)
        val discontinuity = seek || (sampled && (raw < position - 80 || raw - position > elapsed * 8 + 1_000))
        if (!sampled || discontinuity || (playStateKnown && !playing)) speed.reset(raw, now)
        else if (speed.observe(raw, now) && !playStateKnown) { playing = true; playStateKnown = true }
        position = raw; stamp = now; sampled = true
        if (discontinuity) seekGeneration++
    }
    @Synchronized fun playing(track: String, value: Boolean) {
        select(track)
        position = current(); stamp = nanoTime(); playing = value; playStateKnown = true
        speed.reset(position, stamp)
    }
    @Synchronized fun playingState(track: String): Boolean? = playing.takeIf { track == trackKey && playStateKnown }
    @Synchronized fun playbackRate(track: String): Float? = speed.rate.toFloat().takeIf { track == trackKey }
    @Synchronized fun now(track: String): Long = if (track == trackKey && track.isNotEmpty()) current() else 0
    @Synchronized fun clear() { select("") }

    private fun current(): Long = position + if (playing && sampled)
        (((nanoTime() - stamp) / 1_000_000.0).coerceIn(0.0, 1_500.0) * speed.rate).toLong() else 0

    private fun select(track: String) {
        if (track == trackKey) return
        trackKey = track; position = 0; stamp = nanoTime()
        playing = false; playStateKnown = false; sampled = false
        speed.reset(0, stamp, clearRate = true); seekGeneration++
    }
}
