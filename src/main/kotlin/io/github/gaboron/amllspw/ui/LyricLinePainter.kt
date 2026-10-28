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
    // Cache native resources instead of allocating blur layers/filters for every animation frame.
    private val blurPaints = (0..4).map { level -> Paint().apply {
        if (level > 0) ImageFilter.makeBlur(level * .6f, level * .6f, FilterTileMode.DECAL).use { imageFilter = it }
    } }
    private val contrastPaints = mutableMapOf<Int, Paint>()
    private val words = WordHighlightPainter()
    private val ruby = LyricRubyPainter()

    fun DrawScope.paintLine(layout: LineLayout, positionMs: Long, active: Boolean, distance: Int, manual: Boolean, options: RenderOptions, highlight: Float = if (active) 1f else 0f, color: Color = Color.White) {
        val native = drawContext.canvas.skiaCanvas
        val blur = if (active || manual || !options.blur || options.reducedMotion) 0 else distance.coerceIn(0, 4)
        val contrast = if (options.coloredLyrics && highlight > .001f) contrastPaint(layout.emPx) else null
        if (blur > 0) native.saveLayer(layout.layerBounds, blurPaints[blur])
        if (contrast != null) native.saveLayer(layout.layerBounds, contrast)
        try {
            layout.upperRomanization?.let { with(ruby) { paintUpper(it, layout.emPx, highlight, color) } }
            translate(top = layout.rubyHeight) {
                if (!layout.line.hasWordTiming || highlight <= .001f) {
                    with(words) { paintBloom(layout, highlight, options, color) }
                    drawText(layout.main, color = color, alpha = .2f + .8f * highlight)
                } else {
                    for (region in layout.words) {
                        with(words) { paint(layout, region, positionMs, highlight, options, color) }
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
            if (contrast != null) native.restore()
            if (blur > 0) native.restore()
        }
    }

    private fun contrastPaint(emPx: Float): Paint {
        val radius = (emPx * .035f).roundToInt().coerceIn(1, 8)
        return contrastPaints.getOrPut(radius) { Paint().apply {
            // A narrow dark shadow separates the complete shaped row from the background
            // without changing its fill color, timing masks or glyph motion.
            ImageFilter.makeDropShadow(0f, radius * .5f, radius.toFloat(), radius.toFloat(), 0x66000000).use {
                imageFilter = it
            }
        } }
    }

    fun clearTextCache() { words.clearTextCache(); ruby.clear() }
    override fun close() {
        if (closed) return
        closed = true
        words.close()
        ruby.close()
        blurPaints.forEach { it.close() }
        contrastPaints.values.forEach { it.close() }
    }
}
