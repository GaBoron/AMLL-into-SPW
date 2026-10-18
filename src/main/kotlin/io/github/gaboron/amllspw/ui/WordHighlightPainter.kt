package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import io.github.gaboron.amllspw.timeline.WordAnimation
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Rect as SkiaRect
import kotlin.math.roundToInt

/** Word masking, grapheme float and long-tone emphasis share the same shaped text layout. */
internal class WordHighlightPainter : AutoCloseable {
    private val glowPaints = mutableMapOf<Int, Paint>()
    private val atlas = GlyphAtlas()
    private val imagePaint = Paint()
    private var tint = Color.White.toArgb()
    private var tintFilter: org.jetbrains.skia.ColorFilter? = null

    fun DrawScope.paint(layout: LineLayout, region: WordRegion, timeMs: Long, highlight: Float, options: RenderOptions, color: Color = Color.White) {
        updateTint(color.toArgb())
        val word = region.word
        val progress = if (word.timed) ((timeMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) else 1f
        val totalWidth = region.rects.sumOf { it.width.toDouble() }.toFloat()
        val offsets = region.rects.runningFold(0f) { width, rect -> width + rect.width }
        val texture = atlas.get(layout, this)
        for (glyph in region.glyphs) {
            val rect = glyph.bounds
            val animation = if (options.reducedMotion) WordAnimation.Transform() else
                region.animation.evaluate(timeMs, glyph.character)
            val lift = if (options.scale) animation.liftEm * layout.emPx * highlight else 0f
            val scale = if (options.scale) 1f + (animation.scale - 1f) * highlight else 1f
            val part = region.rects.getOrNull(glyph.part) ?: continue
            val edge = progress * totalWidth - offsets[glyph.part]
            val edgeX = if (region.rightToLeft) part.right - edge else part.left + edge
            val fadeWidth = layout.emPx * .5f
            val brush = if (progress >= .999f) SolidColor(Color.White) else Brush.horizontalGradient(
                if (region.rightToLeft) listOf(color.copy(alpha = 0f), color) else listOf(color, color.copy(alpha = 0f)),
                edgeX - fadeWidth / 2, edgeX + fadeWidth / 2)
            // The measured line box includes descenders. Extending into the next line would
            // sample its neighbour's ink from the shared paragraph atlas and paint it twice.
            val target = SkiaRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom)
            val source = SkiaRect.makeLTRB((target.left + texture.padding) * GlyphAtlas.RESOLUTION,
                (target.top + texture.padding) * GlyphAtlas.RESOLUTION, (target.right + texture.padding) * GlyphAtlas.RESOLUTION,
                (target.bottom + texture.padding) * GlyphAtlas.RESOLUTION)
            withTransform({
                translate(if (options.scale) animation.shiftXEm * layout.emPx * highlight else 0f, -lift)
                scale(scale, scale, Offset(rect.center.x, rect.bottom))
            }) {
                val native = drawContext.canvas.skiaCanvas
                if (options.glow && animation.glow > .001f && highlight > .001f) {
                    val radius = (animation.blurEm * layout.emPx).roundToInt().coerceIn(1, 24)
                    val paint = glowPaints.getOrPut(radius) { Paint().apply {
                        ImageFilter.makeBlur(radius.toFloat(), radius.toFloat(), FilterTileMode.DECAL).use { imageFilter = it }
                    } }
                    native.saveLayer(null, paint)
                    try {
                        imagePaint.alpha = (animation.glow * highlight * 255).roundToInt().coerceIn(0, 255)
                        native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
                    } finally { native.restore() }
                }
                imagePaint.alpha = ((.2f + .2f * highlight) * 255).roundToInt()
                native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
                if (progress > 0 && highlight > 0) {
                    imagePaint.alpha = (highlight * 255).roundToInt()
                    val fullyLit = if (region.rightToLeft) edgeX + fadeWidth / 2 <= rect.left else edgeX - fadeWidth / 2 >= rect.right
                    val unlit = if (region.rightToLeft) edgeX - fadeWidth / 2 >= rect.right else edgeX + fadeWidth / 2 <= rect.left
                    if (!unlit) {
                        if (fullyLit || progress >= .999f) native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
                        else {
                            native.saveLayer(null, null)
                            try {
                                native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
                                drawRect(brush, Offset(target.left, target.top), androidx.compose.ui.geometry.Size(target.width, target.height), blendMode = BlendMode.SrcIn)
                            } finally { native.restore() }
                        }
                    }
                }
            }
        }
    }
    private fun updateTint(argb: Int) {
        if (tint == argb) return
        tint = argb
        imagePaint.colorFilter = null
        tintFilter?.close()
        tintFilter = if (argb == Color.White.toArgb()) null else
            org.jetbrains.skia.ColorFilter.makeBlend(argb, org.jetbrains.skia.BlendMode.SRC_IN)
        imagePaint.colorFilter = tintFilter
    }
    override fun close() { atlas.close(); imagePaint.close(); tintFilter?.close(); glowPaints.values.forEach { it.close() }; glowPaints.clear() }
}
