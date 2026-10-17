package io.github.gaboron.amllspw.timeline

/** Extrapolation is a fallback only; the native hook supplies the host's precise lyrics clock. */
internal class PlaybackClock(private val nanoTime: () -> Long = System::nanoTime) {
    private var position = 0L
    private var stamp = nanoTime()
    private var playing = false
    private var playStateKnown = false
    var seekGeneration = 0L; private set

    @Synchronized fun sync(ms: Long, seek: Boolean = false) {
        position = ms.coerceAtLeast(0); stamp = nanoTime()
        if (seek) seekGeneration++
    }
    @Synchronized fun playing(value: Boolean) {
        position = now(); stamp = nanoTime(); playing = value; playStateKnown = true
    }
    @Synchronized fun playingState(): Boolean? = playing.takeIf { playStateKnown }
    @Synchronized fun now(): Long = position + if (playing) ((nanoTime() - stamp) / 1_000_000).coerceIn(0, 1_500) else 0
}
