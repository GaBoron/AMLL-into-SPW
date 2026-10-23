package io.github.gaboron.amllspw.ui

internal data class LyricVisibleSpan(val top: Float, val bottom: Float, val firstGroup: Int, val lastGroup: Int)

internal fun lyricViewportTopMargin(height: Float) = minOf(45f, height * .08f)
internal fun lyricViewportBottomMargin(height: Float) = minOf(70f, height * .12f)

/** Include the lead bound to an active backing voice, even after the lead's own timing ends. */
internal fun lyricVisibleSpan(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long): LyricVisibleSpan? {
    var first = Float.POSITIVE_INFINITY
    var last = Float.NEGATIVE_INFINITY
    var firstGroup = groups.size
    var lastGroup = -1
    groups.forEachIndexed { index, group ->
        var top = geometry.tops[index]
        val backgroundSlide = geometry.backgroundOffsets[index]
        val backingActive = group.rows.any { it.line.background && it.line.active(timeMs) }
        group.rows.forEach { row ->
            if (row.line.active(timeMs) || (!row.line.background && backingActive)) {
                val y = top + if (row.line.background) backgroundSlide else 0f
                first = minOf(first, y)
                last = maxOf(last, y + row.height)
                firstGroup = minOf(firstGroup, index)
                lastGroup = maxOf(lastGroup, index)
            }
            top += row.height
        }
    }
    return if (lastGroup < 0) null else LyricVisibleSpan(first, last, firstGroup, lastGroup)
}

/** Follow the lead while retaining its complete vocal context whenever it fits. */
internal fun lyricScrollAnchor(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long,
    viewportHeight: Float, preferredOffset: Float): Float {
    val span = lyricVisibleSpan(groups, geometry, timeMs) ?: return preferredOffset
    val minimum = span.bottom - viewportHeight + lyricViewportBottomMargin(viewportHeight)
    val maximum = span.top - lyricViewportTopMargin(viewportHeight)
    return if (minimum <= maximum) preferredOffset.coerceIn(minimum, maximum) else preferredOffset
}
