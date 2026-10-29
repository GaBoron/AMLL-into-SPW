package io.github.gaboron.amllspw.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/** Prefiltered, reduced-resolution Gaussian masks; sharp and near-sharp text stays at native resolution. */
internal class LyricGaussianBlur {
    data class Mask(val width: Int, val height: Int, val alpha: ByteArray)

    private var first = FloatArray(0)
    private var second = FloatArray(0)

    fun clear() {
        first = FloatArray(0)
        second = FloatArray(0)
    }

    fun applyAlpha(alpha: ByteArray, width: Int, height: Int, sigma: Float): Mask {
        require(alpha.size == width * height)
        val scale = when {
            sigma >= 6.75f -> 4
            sigma >= 3.25f -> 2
            else -> 1
        }
        // Reduced masks retain at least 1.6 texels of Gaussian softness, allowing
        // inexpensive bilinear reconstruction without visible sampling blocks.
        val sampledWidth = (width + scale - 1) / scale
        val sampledHeight = (height + scale - 1) / scale
        val pixels = sampledWidth * sampledHeight
        if (first.size < pixels) {
            first = FloatArray(pixels)
            second = FloatArray(pixels)
        }
        // Average the entire footprint before decimation. Point sampling thin strokes
        // would alias into blocks and make a scrolling row shimmer.
        if (scale == 1) {
            for (i in alpha.indices) first[i] = (alpha[i].toInt() and 255).toFloat()
        } else {
            for (y in 0 until sampledHeight) for (x in 0 until sampledWidth) {
                var sum = 0
                for (dy in 0 until minOf(scale, height - y * scale)) {
                    val row = (y * scale + dy) * width + x * scale
                    for (dx in 0 until minOf(scale, width - x * scale)) sum += alpha[row + dx].toInt() and 255
                }
                first[y * sampledWidth + x] = sum.toFloat() / (scale * scale)
            }
        }
        // Each pass contributes one third of the variance. Fractional end taps keep
        // the kernel continuous between integer box widths, including subpixel radii.
        // Account for the footprint average so reduced masks do not become too soft.
        val deviation = sigma.coerceAtLeast(0f)
        val variance = ((deviation * deviation - (scale * scale - 1) / 12f) / (scale * scale * 3f)).coerceAtLeast(0f)
        val radius = floor((sqrt(1f + 12f * variance) - 1f) / 2f).toInt()
        val widthInPixels = radius * 2 + 1
        val squareSum = radius.toFloat() * (radius + 1) * widthInPixels / 3f
        val tail = ((variance * widthInPixels - squareSum) /
            (2f * ((radius + 1f) * (radius + 1f) - variance))).coerceIn(0f, 1f)
        val normalization = 1f / (widthInPixels + tail * 2)
        if (sigma > 0f) repeat(3) {
            horizontal(first, second, sampledWidth, sampledHeight, radius, tail, normalization)
            vertical(second, first, sampledWidth, sampledHeight, radius, tail, normalization)
        }
        // Half-float masks preserve faint gradients; rounding to A8 here would turn
        // many neighboring pixels into the same opacity before interpolation.
        val output = ByteArray(pixels * 2)
        val half = ByteBuffer.wrap(output).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until pixels) {
            val value = java.lang.Float.floatToFloat16((first[i] / 255f).coerceIn(0f, 1f))
            half.put(value)
        }
        return Mask(sampledWidth, sampledHeight, output)
    }

    companion object {
        fun rgba(alpha: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(alpha).order(ByteOrder.nativeOrder()).asShortBuffer()
            val output = ByteArray(alpha.size * 4)
            val pixels = ByteBuffer.wrap(output).order(ByteOrder.nativeOrder()).asLongBuffer()
            while (input.hasRemaining()) {
                val channel = input.get().toLong() and 0xffffL
                pixels.put(channel or (channel shl 16) or (channel shl 32) or (channel shl 48))
            }
            return output
        }
    }

    private fun horizontal(source: FloatArray, target: FloatArray, width: Int, height: Int,
        radius: Int, tail: Float, normalization: Float) {
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in 0..minOf(radius, width - 1)) sum += source[row + x]
            for (x in 0 until width) {
                val left = x - radius - 1
                val right = x + radius + 1
                val edges = (if (left >= 0) source[row + left] else 0f) +
                    (if (right < width) source[row + right] else 0f)
                target[row + x] = (sum + tail * edges) * normalization
                if (right < width) sum += source[row + right]
                if (x - radius >= 0) sum -= source[row + x - radius]
            }
        }
    }

    private fun vertical(source: FloatArray, target: FloatArray, width: Int, height: Int,
        radius: Int, tail: Float, normalization: Float) {
        for (x in 0 until width) {
            var sum = 0f
            for (y in 0..minOf(radius, height - 1)) sum += source[y * width + x]
            for (y in 0 until height) {
                val top = y - radius - 1
                val bottom = y + radius + 1
                val edges = (if (top >= 0) source[top * width + x] else 0f) +
                    (if (bottom < height) source[bottom * width + x] else 0f)
                target[y * width + x] = (sum + tail * edges) * normalization
                if (bottom < height) sum += source[bottom * width + x]
                if (y - radius >= 0) sum -= source[(y - radius) * width + x]
            }
        }
    }
}

/** A small scale space is enough for a continuous focus transition; no per-frame kernels. */
internal object LyricBlurLevels {
    private val radii = floatArrayOf(0f, .5f, 1f, 1.5f, 2.25f, 3.25f, 4.75f, 6.75f, 9f, MAX_LYRIC_BLUR_RADIUS)

    data class Blend(val lower: Int, val fraction: Float) {
        val upper: Int get() = if (fraction > 0f) lower + 1 else lower
    }

    fun radius(level: Int): Float = radii[level]

    fun blend(radius: Float): Blend {
        val amount = radius.coerceIn(0f, radii.last())
        val upper = radii.indexOfFirst { it >= amount }
        if (upper == 0 || amount == radii[upper]) return Blend(upper, 0f)
        val lower = upper - 1
        return Blend(lower, (amount - radii[lower]) / (radii[upper] - radii[lower]))
    }
}
