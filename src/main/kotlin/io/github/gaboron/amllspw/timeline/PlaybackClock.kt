package io.github.gaboron.amllspw.timeline

/** Track-bound playback samples remain available even when the host has no decoded lyrics. */
internal class PlaybackClock(private val nanoTime: () -> Long = System::nanoTime) {
    private var trackKey = ""
    private var position = 0L
    private var stamp = nanoTime()
    private var playing = false
    private var playStateKnown = false
    private var sampled = false
    private var windowPosition = 0L
    private var windowStamp = stamp
    private var rate = 1.0
    @Volatile var seekGeneration = 0L; private set

    @Synchronized fun sync(track: String, ms: Long, seek: Boolean = false) {
        select(track)
        val now = nanoTime()
        val raw = ms.coerceAtLeast(0)
        val elapsed = (now - windowStamp) / 1_000_000.0
        val delta = raw - windowPosition
        if (seek || raw < position) {
            windowPosition = raw; windowStamp = now
        } else if (sampled && elapsed >= 250 && delta > 0) {
            val measured = delta / elapsed
            if (measured in .1..8.0) {
                rate = measured
                if (!playStateKnown) { playing = true; playStateKnown = true }
            }
            windowPosition = raw; windowStamp = now
        } else if (!sampled || raw == position) {
            windowPosition = raw; windowStamp = now
        }
        position = raw; stamp = now; sampled = true
        if (seek) seekGeneration++
    }
    @Synchronized fun playing(track: String, value: Boolean) {
        select(track)
        position = current(); stamp = nanoTime(); playing = value; playStateKnown = true
        windowPosition = position; windowStamp = stamp
    }
    @Synchronized fun playingState(track: String): Boolean? = playing.takeIf { track == trackKey && playStateKnown }
    @Synchronized fun now(track: String): Long = if (track == trackKey && track.isNotEmpty()) current() else 0
    @Synchronized fun clear() { select("") }

    private fun current(): Long = position + if (playing && sampled)
        (((nanoTime() - stamp) / 1_000_000.0).coerceIn(0.0, 1_500.0) * rate).toLong() else 0

    private fun select(track: String) {
        if (track == trackKey) return
        trackKey = track; position = 0; stamp = nanoTime()
        playing = false; playStateKnown = false; sampled = false
        windowPosition = 0; windowStamp = stamp; rate = 1.0; seekGeneration++
    }
}
