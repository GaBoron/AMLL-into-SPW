package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint

internal class LyricLinePainter(private val measurer: TextMeasurer) : AutoCloseable {
    private var closed = false
    // Cache native resources instead of allocating blur layers/filters for every animation frame.
    private val blurPaints = (0..4).map { level -> Paint().apply {
        if (level > 0) ImageFilter.makeBlur(level * .6f, level * .6f, FilterTileMode.DECAL).use { imageFilter = it }
    } }
    private val words = WordHighlightPainter()

    fun DrawScope.paintLine(layout: LineLayout, positionMs: Long, active: Boolean, distance: Int, manual: Boolean, options: RenderOptions, highlight: Float = if (active) 1f else 0f, color: Color = Color.White) {
        val native = drawContext.canvas.skiaCanvas
        val blur = if (active || manual || !options.blur || options.reducedMotion) 0 else distance.coerceIn(0, 4)
        if (blur > 0) native.saveLayer(layout.layerBounds, blurPaints[blur])
        try {
            translate(top = layout.rubyHeight) {
                if (!layout.line.hasWordTiming || highlight <= .001f) {
                    drawText(layout.main, color = color, alpha = .2f + .8f * highlight)
                } else {
                    for (region in layout.words) {
                        with(words) { paint(layout, region, positionMs, highlight, options, color) }
                    }
                }
                if (options.romanization) paintRuby(layout, positionMs, highlight, options, color)
                var auxiliaryY = layout.main.size.height + 6f
                for (text in layout.auxiliary) {
                    drawText(text, color = color.copy(alpha = .3f + .35f * highlight), topLeft = Offset(0f, auxiliaryY))
                    auxiliaryY += text.size.height + 5
                }
            }
        } finally { if (blur > 0) native.restore() }
    }

    fun clearTextCache() { words.clearTextCache() }
    override fun close() {
        if (closed) return
        closed = true
        words.close()
        blurPaints.forEach { it.close() }
    }
}
