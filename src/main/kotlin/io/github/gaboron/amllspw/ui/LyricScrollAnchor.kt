package io.github.gaboron.amllspw.ui

/** Follow the lead while retaining every singing voice when their span fits the viewport. */
internal fun lyricScrollAnchor(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long,
    viewportHeight: Float, preferredOffset: Float): Float {
    var first = Float.POSITIVE_INFINITY
    var last = Float.NEGATIVE_INFINITY
    groups.forEachIndexed { index, group ->
        var top = geometry.tops[index]
        val backgroundSlide = -.8f * (group.height - geometry.heights[index])
        group.rows.forEach { row ->
            if (row.line.active(timeMs)) {
                val y = top + if (row.line.background) backgroundSlide else 0f
                first = minOf(first, y)
                last = maxOf(last, y + row.height)
            }
            top += row.height
        }
    }
    val minimum = last - viewportHeight + minOf(70f, viewportHeight * .12f)
    val maximum = first - minOf(45f, viewportHeight * .08f)
    return if (minimum <= maximum) preferredOffset.coerceIn(minimum, maximum) else preferredOffset
}
