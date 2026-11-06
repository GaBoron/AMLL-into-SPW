package io.github.gaboron.amllspw.ui

internal data class LyricVisibleSpan(val top: Float, val bottom: Float, val firstGroup: Int, val lastGroup: Int)

internal fun lyricViewportTopMargin(height: Float) = minOf(45f, height * .08f)
internal fun lyricViewportBottomMargin(height: Float) = minOf(70f, height * .12f)

/** Include the lead bound to an active backing voice, even after the lead's own timing ends. */
internal fun lyricVisibleSpan(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long, focus: Int? = null,
    includeInterlude: Boolean = true): LyricVisibleSpan? {
    var first = Float.POSITIVE_INFINITY
    var last = Float.NEGATIVE_INFINITY
    var firstGroup = groups.size
    var lastGroup = -1
    groups.forEachIndexed { index, group ->
        var top = if (includeInterlude) geometry.tops[index] else geometry.lyricTops[index]
        val backgroundSlide = geometry.backgroundOffsets[index]
        val backingActive = group.rows.any { it.line.background && it.line.active(timeMs) }
        group.rows.forEachIndexed { rowIndex, row ->
            if (row.line.active(timeMs) || (!row.line.background && (backingActive || index == focus))) {
                val y = top + if (row.line.background) backgroundSlide else 0f
                first = minOf(first, y)
                last = maxOf(last, y + group.rowHeights[rowIndex])
                firstGroup = minOf(firstGroup, index)
                lastGroup = maxOf(lastGroup, index)
            }
            top += group.rowHeights[rowIndex]
        }
    }
    return if (lastGroup < 0) null else LyricVisibleSpan(first, last, firstGroup, lastGroup)
}

/** Follow the lead while retaining its complete vocal context whenever it fits. */
internal fun lyricScrollAnchor(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long,
    viewportHeight: Float, preferredOffset: Float, focus: Int? = null, includeInterlude: Boolean = true): Float {
    val span = lyricVisibleSpan(groups, geometry, timeMs, focus, includeInterlude) ?: return preferredOffset
    val minimum = span.bottom - viewportHeight + lyricViewportBottomMargin(viewportHeight)
    val maximum = span.top - lyricViewportTopMargin(viewportHeight)
    // Keep the retained head readable while completed slots make room below it.
    // Once the span fits, the same boundary joins the ordinary follow range continuously.
    return if (minimum <= maximum) preferredOffset.coerceIn(minimum, maximum) else maximum
}
