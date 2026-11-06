// SPDX-License-Identifier: MIT
// Oklab conversion matrices: Copyright (c) 2020 Björn Ottosson.
package io.github.gaboron.amllspw.cover

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** D65 Oklab/sRGB conversion; gamut mapping preserves perceptual lightness and hue. */
internal data class OklabColor(val lightness: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)
    val hue: Double get() = atan2(b, a)

    fun toRgb(lightness: Double = this.lightness, chroma: Double = this.chroma): Int {
        val level = lightness.coerceIn(0.0, 1.0)
        val amount = chroma.coerceAtLeast(0.0)
        val original = this.chroma
        val directionA = if (original > 1e-7) a / original else 0.0
        val directionB = if (original > 1e-7) b / original else 0.0
        fun rgb(value: Double) = linearRgb(level, directionA * value, directionB * value)
        var result = rgb(amount)
        if (!result.inGamut) {
            // Reduce only chroma; clipping channels independently would change the hue.
            var low = 0.0
            var high = amount
            repeat(18) {
                val middle = (low + high) / 2
                if (rgb(middle).inGamut) low = middle else high = middle
            }
            result = rgb(low)
        }
        fun channel(value: Double): Int {
            val linear = value.coerceIn(0.0, 1.0)
            val encoded = if (linear <= .0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - .055
            return (encoded * 255).roundToInt().coerceIn(0, 255)
        }
        return (255 shl 24) or (channel(result.red) shl 16) or (channel(result.green) shl 8) or channel(result.blue)
    }

    private data class LinearRgb(val red: Double, val green: Double, val blue: Double) {
        val inGamut: Boolean get() = red in -1e-7..1.0000001 && green in -1e-7..1.0000001 && blue in -1e-7..1.0000001
    }

    companion object {
        // https://bottosson.github.io/posts/oklab/ (2021-01-25 matrices).
        fun fromRgb(rgb: Int): OklabColor {
            fun channel(shift: Int): Double {
                val encoded = ((rgb ushr shift) and 255) / 255.0
                return if (encoded <= .04045) encoded / 12.92 else ((encoded + .055) / 1.055).pow(2.4)
            }
            val red = channel(16)
            val green = channel(8)
            val blue = channel(0)
            val l = cbrt(.4122214708 * red + .5363325363 * green + .0514459929 * blue)
            val m = cbrt(.2119034982 * red + .6806995451 * green + .1073969566 * blue)
            val s = cbrt(.0883024619 * red + .2817188376 * green + .6299787005 * blue)
            return OklabColor(
                .2104542553 * l + .7936177850 * m - .0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
                .0259040371 * l + .7827717662 * m - .8086757660 * s,
            )
        }

        private fun linearRgb(lightness: Double, a: Double, b: Double): LinearRgb {
            val l = (lightness + .3963377774 * a + .2158037573 * b).pow(3)
            val m = (lightness - .1055613458 * a - .0638541728 * b).pow(3)
            val s = (lightness - .0894841775 * a - 1.2914855480 * b).pow(3)
            return LinearRgb(
                4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
                -.0041960863 * l - .7034186147 * m + 1.7076147010 * s,
            )
        }
    }
}
