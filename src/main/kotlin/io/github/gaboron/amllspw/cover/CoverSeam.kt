package io.github.gaboron.amllspw.cover

import kotlin.math.abs
import kotlin.math.roundToInt

private const val FIRST_INSET = 2
private const val LAST_INSET = 10
private const val INSETS = LAST_INSET - FIRST_INSET + 1
private const val FEATHER = 4f

/** Finds four low-energy paths constrained to a narrow band inside the original crop. */
internal fun coverDetailMask(source: IntArray, field: CoverColorField, profile: CoverBackdropProfile): IntArray {
    val size = COVER_SAMPLE_SIZE
    val paths = Array(4) { seamPath(source, field, profile, it) }
    return IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        val left = x - paths[0][y]
        val right = size - 1 - x - paths[1][y]
        val top = y - paths[2][x]
        val bottom = size - 1 - y - paths[3][x]
        val weight = coverage(left) * coverage(right) * coverage(top) * coverage(bottom)
        val alpha = (255 * weight).roundToInt()
        (0xff shl 24) or (alpha shl 16) or (alpha shl 8) or alpha
    }
}

private fun seamPath(source: IntArray, field: CoverColorField, profile: CoverBackdropProfile, side: Int): FloatArray {
    val size = COVER_SAMPLE_SIZE
    val previous = IntArray(size * INSETS)
    var cost = FloatArray(INSETS)
    for (along in 0 until size) {
        val row = FloatArray(INSETS)
        for (track in 0 until INSETS) {
            val inset = FIRST_INSET + track
            val across = if (side % 2 == 0) inset else size - 1 - inset
            val x = if (side < 2) across else along
            val y = if (side < 2) along else across
            val patch = source[y * size + x]
            val base = profile.blendGround(field.color(x + size, y + size))
            val horizontal = energy(source[y * size + maxOf(0, x - 1)], source[y * size + minOf(size - 1, x + 1)])
            val vertical = energy(source[maxOf(0, y - 1) * size + x], source[minOf(size - 1, y + 1) * size + x])
            val importance = energy(patch, base) * .52f + maxOf(horizontal, vertical) * .36f
            var best = track
            var bestCost = cost[track]
            for (candidate in maxOf(0, track - 1)..minOf(INSETS - 1, track + 1)) {
                val step = cost[candidate] + 13f * abs(track - candidate)
                if (step < bestCost) {
                    best = candidate
                    bestCost = step
                }
            }
            // Preserve the largest area possible when several seams have comparable energy.
            row[track] = bestCost + importance + 3f * track
            previous[along * INSETS + track] = best
        }
        cost = row
    }
    val path = IntArray(size)
    var track = cost.indices.minBy { cost[it] }
    for (along in path.indices.reversed()) {
        path[along] = FIRST_INSET + track
        track = previous[along * INSETS + track]
    }
    return FloatArray(size) { along ->
        var sum = 0
        for (offset in -3..3) sum += path[(along + offset).coerceIn(0, size - 1)]
        sum / 7f
    }
}

private fun coverage(distance: Float): Float {
    val u = ((distance + 1f) / FEATHER).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

private fun energy(a: Int, b: Int): Int =
    (abs(component(a, 16) - component(b, 16)) +
        abs(component(a, 8) - component(b, 8)) * 2 +
        abs(component(a, 0) - component(b, 0))) / 4
