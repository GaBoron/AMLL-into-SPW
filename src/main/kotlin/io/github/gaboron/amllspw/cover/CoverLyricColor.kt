// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.amllspw.cover

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.pow

/** Dominant artwork hue families and a bright lyric accent for the playback background. */
internal object CoverLyricColor {
    private const val MAX_SATURATION = .26f
    private const val MIN_LUMINANCE = .82

    fun dominant(image: BufferedImage): Int? {
        val families = Array(12) { Accumulator() }
        val neutral = Accumulator()
        val all = Accumulator()
        val hsb = FloatArray(3)
        // Bound this once-per-cover scan; large original artwork need not be visited pixel by pixel.
        val step = ((maxOf(image.width, image.height) + 95) / 96).coerceAtLeast(1)
        for (y in 0 until image.height step step) for (x in 0 until image.width step step) {
            val color = Color(image.getRGB(x, y), true)
            if (color.alpha < 128) continue
            all.add(color)
            Color.RGBtoHSB(color.red, color.green, color.blue, hsb)
            val black = hsb[2] < .08f
            val white = hsb[2] > .92f && hsb[1] < .12f
            if (!black && !white) neutral.add(color)
            if (hsb[1] >= .12f && !black && !white)
                families[(hsb[0] * 12 + .5f).toInt() % 12].add(color)
        }
        return (families.maxByOrNull { it.count }?.takeIf { it.count > 0 }
            ?: neutral.takeIf { it.count > 0 } ?: all.takeIf { it.count > 0 })?.rgb()
    }

    fun foreground(coverRgb: Int?): Int {
        val cover = coverRgb?.let(::Color) ?: return Color.WHITE.rgb
        val hsb = Color.RGBtoHSB(cover.red, cover.green, cover.blue, null)
        val saturation = hsb[1].coerceAtMost(MAX_SATURATION)
        fun bright(value: Float) = Color.getHSBColor(hsb[0], value, 1f)
        val preferred = bright(saturation)
        // Full HSB brightness can still look dim in blue/purple. Keep the artwork hue,
        // but reduce saturation until the text has enough perceptual brightness.
        if (luminance(preferred) >= MIN_LUMINANCE) return preferred.rgb
        var low = 0f
        var high = saturation
        repeat(10) {
            val middle = (low + high) / 2f
            if (luminance(bright(middle)) >= MIN_LUMINANCE) low = middle else high = middle
        }
        return bright(low).rgb
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }

    private class Accumulator {
        var count = 0
        private var red = 0L
        private var green = 0L
        private var blue = 0L
        fun add(color: Color) { count++; red += color.red; green += color.green; blue += color.blue }
        fun rgb() = Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()).rgb
    }
}
