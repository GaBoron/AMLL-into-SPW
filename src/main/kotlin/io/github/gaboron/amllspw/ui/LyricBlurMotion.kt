package io.github.gaboron.amllspw.ui

internal const val MAX_LYRIC_BLUR_RADIUS = 12f

/** Finite, reversible blur transitions shared by focus, hover, browsing and settings changes. */
internal class LyricBlurMotion {
    var radius: Float = 0f; private set
    private var initialized = false
    private var target = 0f
    private var origin = 0f
    private var elapsed = 0f

    fun update(distance: Int, clear: Boolean, dt: Float) = updateRadius(if (clear) 0f else lyricBlurRadius(distance), dt)

    fun updateRadius(next: Float, dt: Float) {
        val desired = next.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS)
        if (!initialized) {
            initialized = true
            radius = desired
            origin = desired
            target = desired
            return
        }
        if (desired != target) {
            origin = radius
            target = desired
            elapsed = 0f
        }
        elapsed += dt.coerceAtLeast(0f)
        val progress = (elapsed / if (target < origin) .22f else .28f).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        radius = origin + (target - origin) * eased
    }
}

internal fun lyricBlurRadius(distance: Int): Float {
    val depth = distance.coerceIn(0, 6).toFloat()
    return (depth * 1.1f + depth * depth * .2f).coerceAtMost(MAX_LYRIC_BLUR_RADIUS)
}
