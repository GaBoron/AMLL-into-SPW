package io.github.gaboron.amllspw.ui

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
