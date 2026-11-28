package io.github.gaboron.amllspw.cover

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class CoverBackdropProfile(val ground: Int, val groundWeight: Float, val textureGain: Float) {
    fun blendGround(color: Int): Int {
        fun blend(shift: Int): Int {
            val from = component(color, shift)
            return (from + (component(ground, shift) - from) * groundWeight).roundToInt() shl shift
        }
        return (0xff shl 24) or blend(16) or blend(8) or blend(0)
    }
}

/** Measures uniform ground and fine variation in the perimeter independently of the cover's subject. */
internal fun coverBackdropProfile(source: IntArray): CoverBackdropProfile {
    val size = COVER_SAMPLE_SIZE
    val candidates = ArrayList<Int>()
    val histogram = IntArray(4096)
    var microtexture = 0
    for (y in 2 until size - 2 step 3) for (x in 2 until size - 2 step 3) {
        if (minOf(x, y, size - 1 - x, size - 1 - y) > 16) continue
        val index = y * size + x
        val color = source[index]
        val bin = ((color ushr 12) and 0xf00) or ((color ushr 8) and 0xf0) or ((color ushr 4) and 0xf)
        histogram[bin]++
        candidates += color

        val dx = colorDistance(color, source[index + 1])
        val dy = colorDistance(color, source[index + size])
        // Small changes on both axes indicate material. Steps across isolated ink edges do not.
        if (dx in 7..62 && dy in 7..62) microtexture++
    }
    val bin = histogram.indices.maxBy { histogram[it] }
    val target = ((bin and 0xf00) shl 12) or ((bin and 0xf0) shl 8) or ((bin and 0xf) shl 4) or 0x080808
    var count = 0
    var red = 0
    var green = 0
    var blue = 0
    for (color in candidates) {
        if (colorDistance(color, target) > 19) continue
        count++
        red += component(color, 16)
        green += component(color, 8)
        blue += component(color, 0)
    }
    // Every color in the winning quantized bin lies within the matching distance.
    val r = red / count
    val g = green / count
    val b = blue / count
    val ground = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    val coverage = count.toFloat() / candidates.size
    // Paper and solid black sleeves tolerate less color leakage from printed subjects.
    val neutralGround = minOf(r, g, b) >= 215 || maxOf(r, g, b) <= 30
    val uniform = if (neutralGround) ease(.48f, .68f, coverage) else ease(.56f, .78f, coverage)
    val texture = ease(.11f, .44f, microtexture.toFloat() / candidates.size)
    return CoverBackdropProfile(ground, uniform, .85f + texture * .95f - uniform * .12f)
}

private fun colorDistance(a: Int, b: Int): Int = maxOf(
    abs(component(a, 16) - component(b, 16)),
    abs(component(a, 8) - component(b, 8)),
    abs(component(a, 0) - component(b, 0)),
)

private fun ease(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
