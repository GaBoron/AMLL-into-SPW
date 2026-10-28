package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt

internal class LyricLinePainter(private val measurer: TextMeasurer) : AutoCloseable {
    private var closed = false
    private data class EffectKey(val blur: Int, val contrast: Int)
    // Quantized radius keys bound the cache and combine contrast/depth into one native layer.
    private val effectPaints = mutableMapOf<EffectKey, Paint>()
    private val words = WordHighlightPainter()
    private val ruby = LyricRubyPainter()

    fun DrawScope.paintLine(layout: LineLayout, positionMs: Long, active: Boolean, distance: Int, manual: Boolean, options: RenderOptions,
        highlight: Float = if (active) 1f else 0f, color: Color = Color.White,
        blurRadius: Float = lyricBlurRadius(distance) * (1f - highlight)) {
        val native = drawContext.canvas.skiaCanvas
        val blur = if (manual || !options.blur || options.reducedMotion) 0f else blurRadius
        val effect = effectPaint(blur, layout.emPx, options.coloredLyrics)
        if (effect != null) native.saveLayer(layout.layerBounds, effect)
        try {
            layout.upperRomanization?.let { with(ruby) { paintUpper(it, layout.emPx, highlight, color) } }
            translate(top = layout.rubyHeight) {
                if (!layout.line.hasWordTiming || highlight <= .001f) {
                    with(words) { paintBloom(layout, highlight, options, color) }
                    drawText(layout.main, color = color, alpha = .2f + .8f * highlight)
                } else {
                    // Atlas sampling and direct text have slightly different antialiasing.
                    // Blend their last frames instead of changing rasterization at one threshold.
                    val wordOpacity = (highlight / .15f).coerceIn(0f, 1f)
                    if (wordOpacity < 1f)
                        drawText(layout.main, color = color, alpha = (.2f + .8f * highlight) * (1f - wordOpacity))
                    for (region in layout.words) {
                        with(words) { paint(layout, region, positionMs, highlight, options, color, wordOpacity) }
                    }
                }
                if (options.romanization) with(ruby) { paintRuby(layout, positionMs, highlight, options, color) }
                var auxiliaryY = layout.main.size.height + 6f
                for (text in layout.auxiliary) {
                    drawText(text, color = color.copy(alpha = .3f + .35f * highlight), topLeft = Offset(0f, auxiliaryY))
                    auxiliaryY += text.size.height + 5
                }
            }
        } finally {
            if (effect != null) native.restore()
        }
    }

    private fun effectPaint(blur: Float, emPx: Float, colored: Boolean): Paint? {
        val level = (blur.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS) * 4).roundToInt()
        val radius = if (colored) (emPx * .065f).roundToInt().coerceIn(1, 8) else 0
        if (level == 0 && radius == 0) return null
        return effectPaints.getOrPut(EffectKey(level, radius)) {
            // The same faint, centered shadow follows every colored row's own opacity.
            // It never switches at an activation threshold or creates a raised edge.
            val shadow = if (radius > 0) ImageFilter.makeDropShadow(0f, 0f,
                radius.toFloat(), radius.toFloat(), 0x26000000) else null
            try {
                if (level == 0) Paint().apply { imageFilter = shadow }
                else ImageFilter.makeBlur(level * .25f, level * .25f, FilterTileMode.DECAL, shadow).use {
                    Paint().apply { imageFilter = it }
                }
            } finally {
                shadow?.close()
            }
        }
    }

    fun clearTextCache() { words.clearTextCache(); ruby.clear() }
    override fun close() {
        if (closed) return
        closed = true
        words.close()
        ruby.close()
        effectPaints.values.forEach { it.close() }
    }
}
