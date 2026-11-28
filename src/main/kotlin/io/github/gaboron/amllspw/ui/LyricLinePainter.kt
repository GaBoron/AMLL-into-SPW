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
        highlight: Float, color: Color, blurRadius: Float, viewport: SkiaRect? = null, opacity: Float = 1f,
        glowColor: Color = color, adaptiveInk: Boolean = false) {
        if (opacity <= 0f) return
        with(textBlur) {
            val contrast = if (options.coloredLyrics && !adaptiveInk) layout.emPx else 0f
            if (highlight > 0f) {
                // Moving words use one native effect layer; their changing shapes
                // never trigger a CPU mask capture or convolution on the UI thread.
                // Preserve the ink/glow colors drawn below; a final SRC_IN tint would
                // erase their chroma difference. White leaves this layer untinted.
                paintLive(layout.layerBounds, blurRadius, Color.White, contrast, opacity) {
                    paintContent(layout, positionMs, options, highlight, color, glowColor, adaptiveInk)
                }
                return
            }
            paint(layout, layout.layerBounds, blurRadius, color,
                contrast, opacity = opacity, viewport = viewport) {
                paintContent(layout, positionMs, options, highlight, Color.White, Color.White, adaptiveInk)
            }
        }
    }

    private fun DrawScope.paintContent(layout: LineLayout, positionMs: Long, options: RenderOptions, highlight: Float,
        color: Color, glowColor: Color, adaptiveInk: Boolean) {
        val baseline = if (adaptiveInk) .33f else .2f
        val lineOpacity = baseline + (1f - baseline) * highlight
        layout.upperRomanization?.let { with(ruby) { paintUpper(it, layout.emPx, highlight, color, adaptiveInk) } }
        translate(top = layout.rubyHeight) {
            if (!layout.line.hasWordTiming || highlight <= .001f) {
                with(words) { paintBloom(layout, highlight, options, glowColor) }
                drawText(layout.main, color = color, alpha = lineOpacity)
            } else {
                // Atlas sampling and direct text have slightly different antialiasing.
                // Blend their last frames instead of changing rasterization at one threshold.
                val wordOpacity = (highlight / .15f).coerceIn(0f, 1f)
                if (wordOpacity < 1f)
                    drawText(layout.main, color = color, alpha = lineOpacity * (1f - wordOpacity))
                for (region in layout.words) {
                    val unsung = if (adaptiveInk) baseline + .33f * highlight else .2f + .2f * highlight
                    with(words) { paint(layout, region, positionMs, highlight, options, color, wordOpacity, glowColor, unsung) }
                }
            }
            if (options.romanization) with(ruby) { paintRuby(layout, positionMs, highlight, options, color, adaptiveInk) }
        }
    }

    fun DrawScope.paintAuxiliary(text: TextLayoutResult, top: Float, height: Float, emPx: Float, highlight: Float,
        color: Color, blurRadius: Float, viewport: SkiaRect, opacity: Float, colored: Boolean, adaptiveInk: Boolean) {
        if (opacity <= 0f) return
        translate(top = top) {
            val padding = maxOf(emPx * 1.5f, MAX_LYRIC_BLUR_RADIUS * 3f)
            val bounds = SkiaRect.makeLTRB(-padding, -padding, text.size.width + padding, text.size.height + padding)
            val clip = SkiaRect.makeLTRB(viewport.left, viewport.top - top, viewport.right, viewport.bottom - top)
            val contrast = if (colored && !adaptiveInk) emPx else 0f
            val draw: DrawScope.() -> Unit = {
                val alpha = if (adaptiveInk) .48f + .52f * highlight else .3f + .35f * highlight
                drawText(text, color = Color.White, alpha = alpha, topLeft = Offset.Zero)
            }
            val paint: DrawScope.() -> Unit = {
                with(textBlur) {
                    if (highlight > 0f) paintLive(bounds, blurRadius, color, contrast, opacity, draw)
                    else paint(text, bounds, blurRadius, color, contrast, opacity, clip, draw)
                }
            }
            // Clip a collapsing slot so its remaining glyphs cannot overlap the next auxiliary line.
            if (height < text.size.height + 5f) clipRect(-padding, -padding, text.size.width + padding, height, block = paint)
            else paint()
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
