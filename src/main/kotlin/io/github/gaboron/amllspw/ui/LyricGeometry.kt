package io.github.gaboron.amllspw.ui

/** Prefix sums use only expanded voices; an interlude is a real slot before the next group. */
internal class LyricGeometry(groups: List<GroupLayout>, expanded: List<Boolean>, interludeBefore: Int?, slotHeight: Float) {
    val tops = FloatArray(groups.size)
    val heights = FloatArray(groups.size)
    val mainHeights = FloatArray(groups.size)
    var interludeTop: Float? = null; private set
    init {
        var top = 0f
        groups.forEachIndexed { index, group ->
            if (index == interludeBefore) { interludeTop = top; top += slotHeight }
            tops[index] = top
            val background = group.rows.filter { it.line.background }.sumOf { it.height.toDouble() }.toFloat()
            mainHeights[index] = group.height - background
            heights[index] = mainHeights[index] + if (expanded[index]) background else 0f
            top += heights[index]
        }
    }
}
