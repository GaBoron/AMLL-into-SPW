// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.amllspw.cover

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.PI

/** Perceptual artwork hue families, with separate readable ink and more chromatic glow. */
internal object CoverLyricColor {
    data class Accent(val foreground: Int, val glow: Int)

    fun dominant(image: BufferedImage): Int? {
        val families = Array(24) { Accumulator() }
        val neutral = Accumulator()
        val all = Accumulator()
        // Bound this once-per-cover scan; large original artwork need not be visited pixel by pixel.
        val step = ((maxOf(image.width, image.height) + 95) / 96).coerceAtLeast(1)
        for (y in 0 until image.height step step) for (x in 0 until image.width step step) {
            val color = Color(image.getRGB(x, y), true)
            if (color.alpha < 128) continue
            val lab = OklabColor.fromRgb(color.rgb)
            all.add(lab)
            val usable = lab.lightness in .12.. .95
            if (usable) neutral.add(lab)
            if (usable && lab.chroma >= .035) {
                val hue = (lab.hue / (2 * PI) + 1) % 1
                // Within chromatic candidates, area remains dominant while modest chroma
                // weighting favours a distinct accent over nearby muted artwork.
                families[(hue * families.size + .5).toInt() % families.size]
                    .add(lab, .5 + lab.chroma.coerceAtMost(.25) * 2)
            }
        }
        return (families.maxByOrNull { it.count }?.takeIf { it.count > 0 }
            ?: neutral.takeIf { it.count > 0 } ?: all.takeIf { it.count > 0 })?.rgb()
    }

    fun accent(coverRgb: Int?): Accent {
        val cover = coverRgb?.let(OklabColor::fromRgb) ?: return Accent(Color.WHITE.rgb, Color.WHITE.rgb)
        // Neutral artwork must not acquire a hue from conversion/rounding noise.
        val chroma = cover.chroma.takeIf { it >= .025 } ?: return Accent(Color.WHITE.rgb, Color.WHITE.rgb)
        val foreground = cover.toRgb(.88, chroma.coerceAtMost(.12))
        val glow = cover.toRgb(.76, (chroma * 1.15).coerceIn(.08, .24))
        return Accent(foreground, glow)
    }

    private class Accumulator {
        var count = 0.0
        private var lightness = 0.0
        private var a = 0.0
        private var b = 0.0
        fun add(color: OklabColor, weight: Double = 1.0) {
            count += weight
            lightness += color.lightness * weight
            a += color.a * weight
            b += color.b * weight
        }
        fun rgb() = OklabColor(lightness / count, a / count, b / count).toRgb()
    }
}
