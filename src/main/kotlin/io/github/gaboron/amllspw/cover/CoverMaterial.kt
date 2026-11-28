package io.github.gaboron.amllspw.cover

import kotlin.math.abs

internal const val MATERIAL_TILE = 24
internal const val MATERIAL_COLUMNS = 8
internal const val MATERIAL_ROWS = 3
internal const val MATERIAL_SWATCH = 9

/** Extracts repeatable surface detail from perimeter patches with weak large-scale structure. */
internal fun extractCoverMaterial(source: IntArray): IntArray {
    val size = COVER_SAMPLE_SIZE
    val pixels = IntArray(MATERIAL_TILE * MATERIAL_COLUMNS * MATERIAL_TILE * MATERIAL_ROWS)
    val stride = MATERIAL_TILE * MATERIAL_COLUMNS
    for (direction in 0 until MATERIAL_COLUMNS) {
        val candidates = materialCandidates(direction, size).sortedBy { materialCost(source, it) }
        val selected = mutableListOf<MaterialPatch>()
        for (patch in candidates) {
            if (selected.none { abs(it.x - patch.x) + abs(it.y - patch.y) < MATERIAL_TILE / 2 }) selected += patch
            if (selected.size == MATERIAL_ROWS) break
        }
        repeat(MATERIAL_ROWS) { row ->
            val patch = selected[row % selected.size]
            for (y in 0 until MATERIAL_TILE) for (x in 0 until MATERIAL_TILE) {
                pixels[(row * MATERIAL_TILE + y) * stride + direction * MATERIAL_TILE + x] =
                    materialPixel(source, patch.x + x, patch.y + y)
            }
        }
    }
    return pixels
}

private data class MaterialPatch(val x: Int, val y: Int)

/** Clockwise from the right, matching the renderer's screen-space angle. */
private fun materialCandidates(direction: Int, size: Int): List<MaterialPatch> = buildList {
    val edge = size - MATERIAL_TILE
    if (direction % 2 == 0) {
        for (inset in listOf(1, 7)) for (along in 26..edge - 26 step 7) {
            add(when (direction) {
                0 -> MaterialPatch(edge - inset, along)
                2 -> MaterialPatch(along, edge - inset)
                4 -> MaterialPatch(inset, along)
                else -> MaterialPatch(along, inset)
            })
        }
    } else {
        for (x in 1..19 step 6) for (y in 1..19 step 6) {
            if (minOf(x, y) > 7) continue
            add(when (direction) {
                1 -> MaterialPatch(edge - x, edge - y)
                3 -> MaterialPatch(x, edge - y)
                5 -> MaterialPatch(x, y)
                else -> MaterialPatch(edge - x, y)
            })
        }
    }
}

private fun materialCost(source: IntArray, patch: MaterialPatch): Float {
    var changes = 0
    var heavyEdges = 0
    var texture = 0
    for (y in 2 until MATERIAL_TILE - 2 step 3) for (x in 2 until MATERIAL_TILE - 2 step 3) {
        val cx = patch.x + x
        val cy = patch.y + y
        val center = source[cy * COVER_SAMPLE_SIZE + cx]
        val right = source[cy * COVER_SAMPLE_SIZE + cx + 2]
        val down = source[(cy + 2) * COVER_SAMPLE_SIZE + cx]
        for (shift in 0..16 step 8) {
            val value = component(center, shift)
            val variation = abs(value - component(right, shift)) + abs(value - component(down, shift))
            changes += variation
            if (variation > 70) heavyEdges++
            if (variation in 3..24) texture++
        }
    }
    // Prefer a regular field of finer detail to a patch containing a few hard letter or subject edges.
    return changes * .018f + heavyEdges * 1.7f - texture * .25f
}

private fun materialPixel(source: IntArray, x: Int, y: Int): Int {
    val size = COVER_SAMPLE_SIZE
    val pixel = source[y * size + x]
    val red = component(pixel, 16)
    val green = component(pixel, 8)
    val blue = component(pixel, 0)
    var redMean = 0
    var greenMean = 0
    var blueMean = 0
    var weight = 0
    for (dy in -3..3 step 3) for (dx in -3..3 step 3) {
        val sample = source[(y + dy).coerceIn(0, size - 1) * size + (x + dx).coerceIn(0, size - 1)]
        val r = component(sample, 16)
        val g = component(sample, 8)
        val b = component(sample, 0)
        // Hard changes of ink or subject color carry no reusable surface detail.
        val similarity = (42 - maxOf(abs(r - red), abs(g - green), abs(b - blue))).coerceAtLeast(0)
        weight += similarity
        redMean += similarity * r
        greenMean += similarity * g
        blueMean += similarity * b
    }
    fun band(value: Int, sum: Int, shift: Int): Int {
        val detail = value - (sum + weight / 2) / weight
        return (128 + detail * 24 / (24 + abs(detail))) shl shift
    }
    return (0xff shl 24) or band(red, redMean, 16) or band(green, greenMean, 8) or band(blue, blueMean, 0)
}
