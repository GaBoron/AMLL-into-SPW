package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion

internal const val MAX_LYRIC_BLUR_RADIUS = 12f

/** Progressive depth blur, with a monotonic transition when the focused row changes. */
internal class LyricBlurMotion {
    private val motion = SpringMotion()
    val radius: Float get() = motion.value

    fun update(distance: Int, clear: Boolean, dt: Float, snap: Boolean) {
        val target = if (clear) 0f else lyricBlurRadius(distance)
        if (snap) motion.snap(target) else motion.follow(target, dt, .12f)
    }
}

internal fun lyricBlurRadius(distance: Int): Float {
    val depth = distance.coerceIn(0, 6).toFloat()
    return (depth * 1.1f + depth * depth * .2f).coerceAtMost(MAX_LYRIC_BLUR_RADIUS)
}
