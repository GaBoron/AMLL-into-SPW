package io.github.gaboron.amllspw.cover

import kotlin.math.pow
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
    val dark: Boolean,
    val needsOutline: Boolean,
)

/** Contrasts against both ends of the visible background range, with isolated texture peaks trimmed. */
internal fun immersiveInk(background: IntArray, family: OklabColor?, colored: Boolean, preferred: Int? = null): ImmersiveInk {
    val levels = DoubleArray(background.size) { inkLuminance(background[it]) }.apply { sort() }
    val trim = (levels.lastIndex * .025).toInt()
    val darkest = levels[trim]
    val lightest = levels[levels.lastIndex - trim]
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
    val maximum = contrast(neutral)
    val minimum = minOf(READABLE, maximum)
    val shared = preferred?.takeIf { contrast(it) >= READABLE }
    val selected = if (shared == null && colored && family != null && maximum >= READABLE) {
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
    val primary = shared ?: selected ?: neutral
    val dark = inkLuminance(primary) <= darkest
    val average = averageInkColor(background)
    val secondary = (7 downTo 1).asSequence().map { mixInk(primary, average, it * .04) }
        .firstOrNull { contrast(it) >= minimum } ?: primary
    val bubble = if (dark) {
        family.takeIf { colored }?.toRgb(.24) ?: 0xff252525.toInt()
    } else {
        family.takeIf { colored }?.toRgb(.94) ?: 0xffededed.toInt()
    }
    val bubbleText = if (inkContrast(inkLuminance(BLACK), inkLuminance(bubble)) >=
        inkContrast(inkLuminance(WHITE), inkLuminance(bubble))) BLACK else WHITE
    return ImmersiveInk(primary, secondary, mixInk(primary, average, .81), bubble, bubbleText,
        dark, contrast(primary) < READABLE)
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
