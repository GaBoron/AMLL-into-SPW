package io.github.gaboron.amllspw.ui

/** Background slots follow their reveal animation; interludes reserve a real slot. */
internal class LyricGeometry(groups: List<GroupLayout>, backgroundExpansion: List<Float>, interludeBefore: Int?, slotHeight: Float,
    footerHeight: Float = 0f) {
    val tops = FloatArray(groups.size)
    val heights = FloatArray(groups.size)
    val mainHeights = FloatArray(groups.size)
    var interludeTop: Float? = null; private set
    val footerTop: Float
    val totalHeight: Float
    init {
        var top = 0f
        groups.forEachIndexed { index, group ->
            if (index == interludeBefore) { interludeTop = top; top += slotHeight }
            tops[index] = top
            val background = group.rows.filter { it.line.background }.sumOf { it.height.toDouble() }.toFloat()
            mainHeights[index] = group.height - background
            heights[index] = mainHeights[index] + background * backgroundExpansion[index].coerceIn(0f, 1f)
            top += heights[index]
        }
        footerTop = top
        totalHeight = top + footerHeight
    }
}
