package io.github.gaboron.amllspw.cover

import org.jetbrains.skia.Rect
import kotlin.math.abs

/** Finds narrow neutral mattes with a matching edge on the opposite side of the artwork. */
internal fun trimCoverMatte(crop: Rect, pixels: IntArray): Rect {
    val size = COVER_SAMPLE_SIZE
    val edges = Array(4) { side -> matteEdge(pixels, side, size) }
    fun matched(side: Int, opposite: Int): Int {
        val a = edges[side] ?: return 0
        val b = edges[opposite] ?: return 0
        return if (colorDelta(a.color, b.color) <= 18) a.width else 0
    }
    val scale = crop.width / size
    return Rect.makeLTRB(crop.left + matched(0, 1) * scale, crop.top + matched(2, 3) * scale,
        crop.right - matched(1, 0) * scale, crop.bottom - matched(3, 2) * scale)
}

private data class MatteEdge(val color: Int, val width: Int)

private fun matteEdge(pixels: IntArray, side: Int, size: Int): MatteEdge? {
    fun sample(depth: Int, along: Int): Int = when (side) {
        0 -> pixels[along * size + depth]
        1 -> pixels[along * size + size - 1 - depth]
        2 -> pixels[depth * size + along]
        else -> pixels[(size - 1 - depth) * size + along]
    }
    val alongRange = 10 until size - 10
    val boundary = alongRange.map { sample(0, it) }
    fun median(shift: Int) = boundary.map { component(it, shift) }.sorted()[boundary.size / 2]
    val color = (median(16) shl 16) or (median(8) shl 8) or median(0)
    val red = component(color, 16)
    val green = component(color, 8)
    val blue = component(color, 0)
    if (maxOf(red, green, blue) - minOf(red, green, blue) > 18) return null
    // Ignore the corners where the adjacent matte or a rounded artwork edge can overlap.
    for (depth in 0..10) {
        val uniform = alongRange.count { colorDelta(color, sample(depth, it)) <= 19 }
        if (uniform >= boundary.size * .86) continue
        if (depth == 0) return null
        val inside = alongRange.map { sample(depth + 1, it) }
        val minimumJump = if (maxOf(red, green, blue) <= 12) 14 else 22
        if (inside.count { colorDelta(color, it) > minimumJump } < boundary.size * .72) return null
        // A line of colored ink on otherwise uniform paper can look like a matte.
        // Keep that margin unless it meets varied artwork or a neutral letterbox.
        val distinct = inside.groupingBy { it and 0x00e0e0e0 }.eachCount().count { it.value >= 3 }
        val neutralInterior = inside.count {
            val r = component(it, 16)
            val g = component(it, 8)
            val b = component(it, 0)
            maxOf(r, g, b) - minOf(r, g, b) <= 22
        }
        return if (distinct >= 5 || neutralInterior >= boundary.size * .85) MatteEdge(color, depth) else null
    }
    return null
}

private fun colorDelta(a: Int, b: Int): Int = maxOf(abs(component(a, 16) - component(b, 16)),
    abs(component(a, 8) - component(b, 8)), abs(component(a, 0) - component(b, 0)))
