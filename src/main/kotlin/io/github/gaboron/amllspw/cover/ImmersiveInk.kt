package io.github.gaboron.amllspw.cover

import kotlin.math.pow
import kotlin.math.cbrt
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BLACK = 0xff000000.toInt()
private const val WHITE = -1
private const val READABLE = 4.5
private const val COMFORTABLE = 5.5
private val linearSrgb = DoubleArray(256) { channel ->
    val value = channel / 255.0
    if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
}

internal data class ImmersiveInk(
    val primary: Int,
    val secondary: Int,
    val rail: Int,
    val bubble: Int,
    val bubbleText: Int,
)

internal data class ImmersiveBackdropTone(val darken: Boolean, val knee: Double, val compression: Double) {
    fun luminance(level: Double): Double {
        val value = if (darken) level else 1 - level
        val above = (value - knee).coerceAtLeast(0.0)
        val mapped = value - above + above / (1 + compression * above)
        return if (darken) mapped else 1 - mapped
    }

    fun color(rgb: Int): Int {
        val source = inkLuminance(rgb)
        val mapped = luminance(source)
        if (abs(mapped - source) < 1e-8) return rgb
        val factor = if (darken) mapped / source else (mapped - source) / (1 - source)
        fun channel(shift: Int): Int {
            val level = linearSrgb[component(rgb, shift)]
            val adjusted = if (darken) level * factor else level + (1 - level) * factor
            val encoded = if (adjusted <= .0031308) adjusted * 12.92 else 1.055 * adjusted.pow(1 / 2.4) - .055
            return (encoded * 255).roundToInt().coerceIn(0, 255) shl shift
        }
        return BLACK or channel(16) or channel(8) or channel(0)
    }
}

internal data class ImmersivePagePalette(val ink: ImmersiveInk, val backdropTone: ImmersiveBackdropTone?)

/** Keep one ink across all visible regions, even when the artwork spans both polarities. */
internal fun immersivePagePalette(regions: List<IntArray>, family: OklabColor?,
    adjustBackdrop: Boolean = true): ImmersivePagePalette {
    val regionalLevels = regions.map { colors -> DoubleArray(colors.size) { inkLuminance(colors[it]) }.apply { sort() } }
    // Trim isolated texture peaks per region so a small player component still counts.
    val darkest = regionalLevels.minOf { it[(it.lastIndex * .025).toInt()] }
    val lightest = regionalLevels.maxOf { it[it.lastIndex - (it.lastIndex * .025).toInt()] }
    fun contrast(ink: Int): Double {
        val level = inkLuminance(ink)
        return when {
            level <= darkest -> inkContrast(level, darkest)
            level >= lightest -> inkContrast(level, lightest)
            else -> 1.0
        }
    }
    val prefersDark = contrast(BLACK) >= contrast(WHITE)
    val neutral = if (prefersDark) BLACK else WHITE
    val selected = if (family != null && contrast(neutral) >= READABLE) {
        // Preserve the hue as tone is adjusted. The Oklab converter reduces chroma
        // when the selected tone falls outside the sRGB gamut.
        fun candidates(dark: Boolean, threshold: Double): Int? {
            if (contrast(if (dark) BLACK else WHITE) < threshold) return null
            return (0..26).asSequence().map { step ->
                family.toRgb(if (dark) .32 - step * .013 else .85 + step * .006)
            }.firstOrNull { contrast(it) >= threshold && OklabColor.fromRgb(it).chroma >= .014 }
        }
        candidates(prefersDark, COMFORTABLE) ?: candidates(!prefersDark, COMFORTABLE)
            ?: candidates(prefersDark, READABLE) ?: candidates(!prefersDark, READABLE)
    } else null
    val tone = if (adjustBackdrop && contrast(neutral) < READABLE) {
        fun correction(darken: Boolean): ImmersiveBackdropTone {
            // Leave headroom for rounding and the artwork beneath an antialiased pixel.
            val limit = if (darken) 1.05 / 4.58 - .05 else 1 - (4.58 * .05 - .05)
            val knee = limit - .045
            val peak = if (darken) lightest else 1 - darkest
            return ImmersiveBackdropTone(darken, knee, 1 / (limit - knee) - 1 / (peak - knee))
        }
        fun cost(candidate: ImmersiveBackdropTone) = regionalLevels.sumOf { levels ->
            levels.sumOf { abs(cbrt(candidate.luminance(it)) - cbrt(it)) } / levels.size
        }
        val shade = correction(true)
        val lift = correction(false)
        if (cost(shade) <= cost(lift)) shade else lift
    } else null
    val primary = if (tone != null) {
        if (tone.darken) WHITE else BLACK
    } else selected ?: neutral
    val dark = inkLuminance(primary) < .5
    val rawAverage = averageInkColor(regions.reduce { combined, region -> combined + region })
    val average = tone?.color(rawAverage) ?: rawAverage
    val secondary = (7 downTo 1).asSequence().map { mixInk(primary, average, it * .04) }
        .firstOrNull {
            val base = if (dark) darkest else lightest
            inkContrast(inkLuminance(it), tone?.luminance(base) ?: base) >= READABLE
        } ?: primary
    val bubble = if (dark) {
        family?.toRgb(.24) ?: 0xff252525.toInt()
    } else {
        family?.toRgb(.94) ?: 0xffededed.toInt()
    }
    val bubbleText = if (inkContrast(inkLuminance(BLACK), inkLuminance(bubble)) >=
        inkContrast(inkLuminance(WHITE), inkLuminance(bubble))) BLACK else WHITE
    return ImmersivePagePalette(ImmersiveInk(primary, secondary, mixInk(primary, average, .81), bubble, bubbleText), tone)
}

/** Share an analogous, restrained hue across the lyrics and controls. */
internal fun immersiveInkFamily(coverAccent: Int, background: IntArray): OklabColor? {
    val accent = OklabColor.fromRgb(coverAccent)
    val surface = OklabColor.fromRgb(averageInkColor(background))
    val base = when {
        surface.chroma < .022 -> accent
        accent.chroma < .022 -> surface
        else -> OklabColor(.5, .4 * accent.a + .6 * surface.a, .4 * accent.b + .6 * surface.b)
    }
    val chroma = base.chroma
    if (chroma < .018) return null
    val amount = chroma.coerceIn(.044, .105) / chroma
    return base.copy(a = base.a * amount, b = base.b * amount)
}

private fun averageInkColor(colors: IntArray): Int {
    fun channel(shift: Int) = (colors.sumOf { component(it, shift) } / colors.size) shl shift
    return BLACK or channel(16) or channel(8) or channel(0)
}

private fun mixInk(a: Int, b: Int, fraction: Double): Int {
    fun channel(shift: Int): Int {
        val from = component(a, shift)
        return (from + (component(b, shift) - from) * fraction).roundToInt() shl shift
    }
    return BLACK or channel(16) or channel(8) or channel(0)
}

/** Weighted SrcOver colors as seen by the opaque backdrop layers on the page. */
internal fun blendImmersiveColors(samples: List<IntArray>, weights: List<Float>): IntArray {
    if (samples.size == 1) return samples.single()
    return IntArray(samples.first().size) { index ->
        var red = 0f
        var green = 0f
        var blue = 0f
        for (layer in samples.indices) {
            val color = samples[layer][index]
            val weight = weights[layer]
            red += component(color, 16) * weight
            green += component(color, 8) * weight
            blue += component(color, 0) * weight
        }
        BLACK or (red.roundToInt().coerceIn(0, 255) shl 16) or
            (green.roundToInt().coerceIn(0, 255) shl 8) or blue.roundToInt().coerceIn(0, 255)
    }
}

internal fun inkLuminance(color: Int): Double =
    .2126 * linearSrgb[component(color, 16)] + .7152 * linearSrgb[component(color, 8)] +
        .0722 * linearSrgb[component(color, 0)]

internal fun inkContrast(a: Double, b: Double): Double = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
