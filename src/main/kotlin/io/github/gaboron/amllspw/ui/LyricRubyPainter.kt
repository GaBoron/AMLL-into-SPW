package io.github.gaboron.amllspw.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Rect as SkiaRect
import kotlin.math.roundToInt

/** Stable text masks move continuously with the source word instead of re-hinting each frame. */
internal class LyricRubyPainter : AutoCloseable {
    private val atlas = GlyphAtlas(256)
    private val paint = Paint()
    private var color = Color.White.toArgb()
    private var filter: ColorFilter? = null
    private var closed = false

    fun DrawScope.paintRuby(layout: LineLayout, timeMs: Long, highlight: Float, options: RenderOptions, tint: Color) {
        updateTint(tint)
        for (run in layout.rubyRuns) {
            val progress = if (layout.line.hasWordTiming) run.motion.progress(timeMs) else 1f
            val lift = if (options.scale && !options.reducedMotion)
                run.motion.floatLift(timeMs) * layout.emPx * highlight else 0f
            translate(top = -lift) {
                val texture = atlas.get(run.text, layout.emPx * .5f, this)
                val pad = texture.padding
                val target = SkiaRect.makeLTRB(run.offset.x - pad, run.offset.y - pad,
                    run.offset.x + run.text.size.width + pad, run.offset.y + run.text.size.height + pad)
                val source = SkiaRect.makeWH(texture.image.width.toFloat(), texture.image.height.toFloat())
                val native = drawContext.canvas.skiaCanvas
                fun draw(alpha: Float) {
                    paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                    native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, paint, true)
                }
                if (!layout.line.hasWordTiming || highlight <= .001f) {
                    draw(.2f + .8f * highlight)
                } else {
                    draw(.2f + .2f * highlight)
                    if (progress > 0 && highlight > 0) {
                        if (progress >= .999f) draw(highlight)
                        else {
                            val edge = run.maskLeft + (run.maskRight - run.maskLeft) * progress
                            val fadeWidth = layout.emPx * .5f
                            native.saveLayer(target, null)
                            try {
                                draw(highlight)
                                drawRect(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0f)),
                                    edge - fadeWidth / 2, edge + fadeWidth / 2), Offset(target.left, target.top),
                                    Size(target.width, target.height), blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
                            } finally { native.restore() }
                        }
                    }
                }
            }
        }
    }

    fun DrawScope.paintUpper(text: TextLayoutResult, emPx: Float, highlight: Float, tint: Color) {
        updateTint(tint)
        val texture = atlas.get(text, emPx * .5f, this)
        val pad = texture.padding
        val source = SkiaRect.makeWH(texture.image.width.toFloat(), texture.image.height.toFloat())
        val target = SkiaRect.makeLTRB(-pad, -pad, text.size.width + pad, text.size.height + pad)
        paint.alpha = ((.3f + .35f * highlight).coerceIn(0f, 1f) * 255).roundToInt()
        drawContext.canvas.skiaCanvas.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, paint, true)
    }

    fun clear() = atlas.clear()
    private fun updateTint(tint: Color) {
        if (color == tint.toArgb()) return
        color = tint.toArgb(); paint.colorFilter = null; filter?.close()
        filter = if (tint == Color.White) null else ColorFilter.makeBlend(color, org.jetbrains.skia.BlendMode.SRC_IN)
        paint.colorFilter = filter
    }
    override fun close() {
        if (closed) return
        closed = true; atlas.close(); paint.close(); filter?.close(); filter = null
    }
}
