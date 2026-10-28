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
    private var closed = false
    private val glowPaints = mutableMapOf<Int, Paint>()
    private val atlas = GlyphAtlas()
    private val imagePaint = Paint()
    private var tint = Color.White.toArgb()
    private var tintFilter: org.jetbrains.skia.ColorFilter? = null

    fun DrawScope.paint(layout: LineLayout, region: WordRegion, timeMs: Long, highlight: Float, options: RenderOptions, color: Color = Color.White) {
        updateTint(color.toArgb())
        val word = region.word
        val progress = WordAnimation.progress(word, timeMs)
        val texture = atlas.get(layout, this)
        // Even short emphasized notes must retain a stronger peak than their resting halo.
        val bloomOpacity = when {
            !options.coloredLyrics -> 0f
            region.animation.peakGlow > 0f -> minOf(BLOOM_OPACITY, region.animation.peakGlow * .2f)
            else -> BLOOM_OPACITY
        }
        for (glyph in region.glyphs) {
            val rect = glyph.bounds
            val animation = if (options.reducedMotion) WordAnimation.Transform() else
                region.animation.evaluate(timeMs, glyph.character)
            val lift = if (options.scale) animation.liftEm * layout.emPx * highlight else 0f
            val scale = if (options.scale) 1f + (animation.scale - 1f) * highlight else 1f
            val part = region.rects.getOrNull(glyph.part) ?: continue
            val edge = progress * region.totalWidth - region.partOffsets[glyph.part]
            val edgeX = if (region.rightToLeft) part.right - edge else part.left + edge
            val fadeWidth = layout.emPx * .5f
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
                if (options.glow && !options.reducedMotion && highlight > .001f) {
                    val lit = when {
                        progress <= 0f -> 0f
                        progress >= .999f -> 1f
                        else -> {
                            val litWidth = if (region.rightToLeft) rect.right - edgeX else edgeX - rect.left
                            (litWidth / rect.width).coerceIn(0f, 1f)
                        }
                    }
                    val bloom = bloomOpacity * lit
                    val strength = maxOf(animation.glow, bloom)
                    if (strength > .001f) {
                        // Blend into the existing emphasis instead of adding another glow on top.
                        val emphasis = if (bloom > 0f) (animation.glow / bloom).coerceIn(0f, 1f) else 1f
                        val radiusEm = BLOOM_RADIUS_EM + (animation.blurEm - BLOOM_RADIUS_EM) * emphasis
                        val radius = (radiusEm * layout.emPx).roundToInt().coerceIn(1, 24)
                        paintGlow(texture, source, target, strength * highlight, radius)
                    }
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
                            native.saveLayer(target, null)
                            try {
                                native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
                                // Only the glyph crossing the highlight edge needs a gradient.
                                val brush = Brush.horizontalGradient(
                                    if (region.rightToLeft) listOf(color.copy(alpha = 0f), color) else listOf(color, color.copy(alpha = 0f)),
                                    edgeX - fadeWidth / 2, edgeX + fadeWidth / 2)
                                drawRect(brush, Offset(target.left, target.top), androidx.compose.ui.geometry.Size(target.width, target.height), blendMode = BlendMode.SrcIn)
                            } finally { native.restore() }
                        }
                    }
                }
            }
        }
    }

    fun DrawScope.paintBloom(layout: LineLayout, highlight: Float, options: RenderOptions, color: Color) {
        if (!options.coloredLyrics || !options.glow || options.reducedMotion || highlight <= .001f) return
        updateTint(color.toArgb())
        val texture = atlas.get(layout, this)
        val padding = texture.padding
        val source = SkiaRect.makeWH(texture.image.width.toFloat(), texture.image.height.toFloat())
        val target = SkiaRect.makeLTRB(-padding, -padding,
            layout.main.size.width + padding, layout.main.size.height + padding)
        val radius = (BLOOM_RADIUS_EM * layout.emPx).roundToInt().coerceIn(1, 24)
        paintGlow(texture, source, target, BLOOM_OPACITY * highlight, radius)
    }

    private fun DrawScope.paintGlow(texture: GlyphAtlas.Entry, source: SkiaRect, target: SkiaRect, opacity: Float, radius: Int) {
        val native = drawContext.canvas.skiaCanvas
        val paint = glowPaints.getOrPut(radius) { Paint().apply {
            ImageFilter.makeBlur(radius.toFloat(), radius.toFloat(), FilterTileMode.DECAL).use { imageFilter = it }
        } }
        val padding = radius * 3f
        native.saveLayer(SkiaRect.makeLTRB(target.left - padding, target.top - padding,
            target.right + padding, target.bottom + padding), paint)
        try {
            imagePaint.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            native.drawImageRect(texture.image, source, target, SamplingMode.LINEAR, imagePaint, true)
        } finally { native.restore() }
    }

    fun clearTextCache() { atlas.clear() }
    private fun updateTint(argb: Int) {
        if (tint == argb) return
        tint = argb
        imagePaint.colorFilter = null
        tintFilter?.close()
        tintFilter = if (argb == Color.White.toArgb()) null else
            org.jetbrains.skia.ColorFilter.makeBlend(argb, org.jetbrains.skia.BlendMode.SRC_IN)
        imagePaint.colorFilter = tintFilter
    }
    override fun close() {
        if (closed) return
        closed = true
        atlas.close()
        imagePaint.close()
        tintFilter?.close()
        tintFilter = null
        glowPaints.values.forEach { it.close() }
        glowPaints.clear()
    }

    private companion object {
        const val BLOOM_OPACITY = .05f
        const val BLOOM_RADIUS_EM = .04f
    }
}
