package io.github.gaboron.amllspw.ui

internal data class LyricRowExtent(val top: Float, val bottom: Float)

/** Measure the text being painted, rather than treating all reserved spacing as occupied. */
internal fun lyricRowExtent(group: GroupLayout, backgroundSlide: Float, visible: (Int) -> Boolean): LyricRowExtent? {
    val background = group.rows.filter { it.line.background }.sumOf { it.height.toDouble() }.toFloat()
    var rowTop = 0f
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    group.rows.forEachIndexed { index, row ->
        if (visible(index)) {
            val y = rowTop + if (row.line.background) backgroundSlide.coerceIn(-80f, 0f) / 100f * background else 0f
            top = minOf(top, y)
            bottom = maxOf(bottom, y + row.height - 10f)
        }
        rowTop += row.height
    }
    return if (top.isFinite()) LyricRowExtent(top, bottom) else null
}

/** Project spring positions onto the available row slots without delaying each row separately. */
internal fun preserveLyricRowSpacing(positions: FloatArray, heights: FloatArray, focus: Int, minimumY: FloatArray) {
    var minimum = Float.NEGATIVE_INFINITY
    for (index in 0..focus) {
        minimum = maxOf(minimum, minimumY[index])
        positions[index] = maxOf(positions[index], minimum)
        minimum += heights[index]
    }
    for (index in focus - 1 downTo 0)
        positions[index] = minOf(positions[index], positions[index + 1] - heights[index])
    for (index in focus + 1..positions.lastIndex)
        positions[index] = maxOf(positions[index], positions[index - 1] + heights[index - 1])
}
