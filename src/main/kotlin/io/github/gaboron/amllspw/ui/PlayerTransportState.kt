package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.PlaybackClock

/** Transport intent survives the unknown/buffering samples between two tracks. Lyrics keep their own clock state. */
internal class PlayerTransportState {
    private var track: String? = null
    private var playing: Boolean? = null
    private var recovering = false
    private var pausedSince: Long? = null
    private var requested: Pair<Boolean, Long>? = null

    fun request(command: String, now: Long = System.nanoTime()) {
        val value = when (command) { "play" -> true; "pause" -> false; else -> return }
        requested = value to (now + 2_500_000_000L)
        playing = value
        recovering = false
        pausedSince = null
    }

    fun sample(key: String, source: PlaybackClock.Sample, now: Long = System.nanoTime()): PlaybackClock.Sample {
        if (key.isBlank()) {
            track = key
            playing = false
            requested = null
            recovering = false
            pausedSince = null
            return source.copy(playing = false)
        }
        if (track != key) {
            if (track != null) requested = null
            track = key
            recovering = playing == true
            pausedSince = null
        }
        val pending = requested
        if (pending != null) {
            if (source.playing == pending.first || now >= pending.second) requested = null
            else return source.copy(playing = pending.first)
        }
        when (source.playing) {
            null -> {
                recovering = playing == true
                pausedSince = null
            }
            true -> {
                playing = true
                recovering = false
                pausedSince = null
            }
            false -> {
                if (recovering && playing == true) {
                    // Ready and isPlaying callbacks may settle in adjacent updates. Confirm
                    // a stable pause after a transition; explicit user commands bypass this wait.
                    val start = pausedSince ?: now.also { pausedSince = it }
                    if (now - start >= 250_000_000L) {
                        playing = false
                        recovering = false
                    }
                } else playing = false
            }
        }
        return source.copy(playing = playing)
    }
}
