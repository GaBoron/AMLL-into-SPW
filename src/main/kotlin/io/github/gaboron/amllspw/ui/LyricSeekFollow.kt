package io.github.gaboron.amllspw.ui

import kotlin.math.abs

/** Keep the browsed viewport until the host clock confirms a lyric-click seek. */
internal class LyricSeekFollow {
    private var targetMs: Long? = null
    private var requestedAt = 0L

    fun request(positionMs: Long, now: Long) {
        targetMs = positionMs.coerceAtLeast(0)
        requestedAt = now
    }

    fun update(positionMs: Long, now: Long) {
        val target = targetMs ?: return
        // Allow coarse playback callbacks, but never wait forever if the host rejects a seek.
        if (abs(positionMs - target) <= 500 || !isWaiting(now)) cancel()
    }

    fun isWaiting(now: Long) = targetMs != null && now - requestedAt < 2_000_000_000L

    fun cancel() { targetMs = null }
}
