package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import org.jetbrains.skia.Rect as SkiaRect

internal class LyricLinePainter(private val measurer: TextMeasurer) : AutoCloseable {
    private var closed = false
    private val textBlur = LyricTextBlur()
    private val words = WordHighlightPainter()
    private val ruby = LyricRubyPainter()

    fun DrawScope.paintLine(layout: LineLayout, positionMs: Long, options: RenderOptions,
        highlight: Float, color: Color, blurRadius: Float, viewport: SkiaRect? = null, opacity: Float = 1f) {
        if (opacity <= 0f) return
        with(textBlur) {
            val contrast = if (options.coloredLyrics) layout.emPx else 0f
            if (highlight > 0f) {
                // Moving words use one native effect layer; their changing shapes
                // never trigger a CPU mask capture or convolution on the UI thread.
                paintLive(layout.layerBounds, blurRadius, color, contrast, opacity) {
                    paintContent(layout, positionMs, options, highlight, Color.White)
                }
                return
            }
            paint(layout, layout.layerBounds, blurRadius, color,
                contrast, opacity = opacity, viewport = viewport) {
                paintContent(layout, positionMs, options, highlight, Color.White)
            }
        }
    }

    private fun DrawScope.paintContent(layout: LineLayout, positionMs: Long, options: RenderOptions, highlight: Float, color: Color) {
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
    }

    fun clearTextCache() { words.clearTextCache(); ruby.clear(); textBlur.clear() }
    override fun close() {
        if (closed) return
        closed = true
        words.close()
        ruby.close()
        textBlur.close()
    }
}
