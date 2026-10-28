package io.github.gaboron.amllspw.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/** Three separable, fractional box passes approximate a Gaussian without downsampling the mask. */
internal class LyricGaussianBlur {
    private var first = FloatArray(0)
    private var second = FloatArray(0)

    fun clear() {
        first = FloatArray(0)
        second = FloatArray(0)
    }

    fun apply(alpha: ByteArray, width: Int, height: Int, sigma: Float): ByteArray {
        return rgba(applyAlpha(alpha, width, height, sigma))
    }

    fun applyAlpha(alpha: ByteArray, width: Int, height: Int, sigma: Float): ByteArray {
        require(alpha.size == width * height)
        if (first.size < alpha.size) {
            first = FloatArray(alpha.size)
            second = FloatArray(alpha.size)
        }
        for (i in alpha.indices) first[i] = (alpha[i].toInt() and 255).toFloat()
        // Each pass contributes one third of the variance. Fractional end taps keep
        // the kernel continuous between integer box widths, including subpixel radii.
        val deviation = sigma.coerceAtLeast(0f)
        val variance = deviation * deviation / 3f
        val radius = floor((sqrt(1f + 12f * variance) - 1f) / 2f).toInt()
        val widthInPixels = radius * 2 + 1
        val squareSum = radius.toFloat() * (radius + 1) * widthInPixels / 3f
        val tail = ((variance * widthInPixels - squareSum) /
            (2f * ((radius + 1f) * (radius + 1f) - variance))).coerceIn(0f, 1f)
        val normalization = 1f / (widthInPixels + tail * 2)
        if (sigma > 0f) repeat(3) {
            horizontal(first, second, width, height, radius, tail, normalization)
            vertical(second, first, width, height, radius, tail, normalization)
        }
        // Half-float masks preserve faint gradients; rounding to A8 here would turn
        // many neighboring pixels into the same opacity before interpolation.
        val output = ByteArray(alpha.size * 2)
        val half = ByteBuffer.wrap(output).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in alpha.indices) {
            val value = java.lang.Float.floatToFloat16((first[i] / 255f).coerceIn(0f, 1f))
            half.put(value)
        }
        return output
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
