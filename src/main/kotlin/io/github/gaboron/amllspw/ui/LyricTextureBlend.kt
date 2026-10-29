package io.github.gaboron.amllspw.ui

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Rect as SkiaRect
import kotlin.math.roundToInt

/** Interpolate final premultiplied row textures without allocating a row-sized mixing surface. */
internal class LyricTextureBlend : AutoCloseable {
    private val paint = Paint()

    fun draw(canvas: Canvas, low: Image, high: Image?, bounds: SkiaRect, fraction: Float, opacity: Float,
        additive: Boolean = false) {
        paint.blendMode = if (additive) BlendMode.PLUS else BlendMode.SRC_OVER
        paint.setAlphaf(opacity.coerceIn(0f, 1f))
        if (high == null || fraction <= 0f) {
            canvas.drawImageRect(low, lyricBlurImageBounds(low), bounds, SamplingMode.LINEAR, paint, true)
            return
        }
        val resources = ArrayList<AutoCloseable>(7)
        fun <T : AutoCloseable> own(resource: T): T = resource.also { resources.add(it) }
        fun weighted(image: Image, weight: Int): Shader {
            val matrix = Matrix33(bounds.width / image.width, 0f, bounds.left,
                0f, bounds.height / image.height, bounds.top, 0f, 0f, 1f)
            val source = own(image.makeShader(FilterTileMode.DECAL, FilterTileMode.DECAL, SamplingMode.LINEAR, matrix))
            // Modulating by premultiplied white scales all four channels together.
            // Complementary weights sum to 255, preserving constant opacity.
            val filter = own(ColorFilter.makeBlend((weight shl 24) or 0x00ffffff, BlendMode.MODULATE))
            return own(source.makeWithColorFilter(filter))
        }
        try {
            val upper = (fraction.coerceIn(0f, 1f) * 255).roundToInt()
            val shader = own(Shader.makeBlend(BlendMode.PLUS, weighted(low, 255 - upper), weighted(high, upper)))
            paint.shader = shader
            canvas.drawRect(bounds, paint)
        } finally {
            paint.shader = null
            resources.asReversed().forEach { it.close() }
        }
    }

    fun clear() { paint.shader = null }
    override fun close() { clear(); paint.close() }
}