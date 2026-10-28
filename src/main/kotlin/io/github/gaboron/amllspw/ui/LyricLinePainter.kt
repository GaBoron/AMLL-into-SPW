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
        highlight: Float, color: Color, blurRadius: Float, viewport: SkiaRect? = null) {
        with(textBlur) {
            paint(layout.takeIf { highlight == 0f }, layout.layerBounds, blurRadius, color,
                if (options.coloredLyrics) layout.emPx else 0f, viewport = viewport) {
                paintContent(layout, positionMs, options, highlight)
            }
        }
    }

    private fun DrawScope.paintContent(layout: LineLayout, positionMs: Long, options: RenderOptions, highlight: Float) {
        layout.upperRomanization?.let { with(ruby) { paintUpper(it, layout.emPx, highlight, Color.White) } }
        translate(top = layout.rubyHeight) {
            if (!layout.line.hasWordTiming || highlight <= .001f) {
                with(words) { paintBloom(layout, highlight, options, Color.White) }
                drawText(layout.main, color = Color.White, alpha = .2f + .8f * highlight)
            } else {
                // Atlas sampling and direct text have slightly different antialiasing.
                // Blend their last frames instead of changing rasterization at one threshold.
                val wordOpacity = (highlight / .15f).coerceIn(0f, 1f)
                if (wordOpacity < 1f)
                    drawText(layout.main, color = Color.White, alpha = (.2f + .8f * highlight) * (1f - wordOpacity))
                for (region in layout.words) {
                    with(words) { paint(layout, region, positionMs, highlight, options, Color.White, wordOpacity) }
                }
            }
            if (options.romanization) with(ruby) { paintRuby(layout, positionMs, highlight, options, Color.White) }
            var auxiliaryY = layout.main.size.height + 6f
            for (text in layout.auxiliary) {
                drawText(text, color = Color.White.copy(alpha = .3f + .35f * highlight), topLeft = Offset(0f, auxiliaryY))
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
