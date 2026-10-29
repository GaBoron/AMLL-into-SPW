package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect as SkiaRect
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Capture static ink once; convolution, tint and shadows are prepared only on the worker. */
internal class LyricTextBlur : AutoCloseable {
    private class Mask(val key: Any?, val left: Int, val top: Int, val width: Int, val height: Int, val alpha: ByteArray) {
        val image = Image.makeRaster(ImageInfo.makeA8(width, height), alpha, width)
        @Volatile var resident = true
        @Volatile var color = 0
        var warmLevel = 0
        var readySince = 0L
        var wasFallback = false
    }
    private data class EffectKey(val blur: Int, val shadow: Int, val alpha: Int, val color: Int)
    private val masks = ArrayDeque<Mask>()
    private var maskBytes = 0
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "amll-lyric-blur").apply { isDaemon = true } }
    private val gaussian = LyricGaussianBlur()
    private val composites = LyricBlurComposite(worker, ::isAlive, ::buildComposite)
    private val blend = LyricTextureBlend()
    private val imagePaint = Paint()
    private val effects = LinkedHashMap<EffectKey, Paint>(32, .75f, true)
    @Volatile private var closed = false

    private fun isAlive(request: LyricBlurComposite.Request): Boolean {
        val mask = request.owner as Mask
        return !closed && mask.resident && mask.color == request.color
    }

    /** Tint precedes shadow and blur inside a single effect, without a separate tint layer. */
    fun DrawScope.paintLive(bounds: SkiaRect, radius: Float, color: Color, contrastEmPx: Float, opacity: Float,
        content: DrawScope.() -> Unit) {
        val effect = effectPaint(radius, color.toArgb(), contrastEmPx, opacity)
        if (effect == null) { content(); return }
        val native = drawContext.canvas.skiaCanvas
        native.saveLayer(Canvas.SaveLayerRec(bounds, effect, null, null,
            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
        try { content() } finally { native.restore() }
    }

    fun DrawScope.paint(key: Any?, bounds: SkiaRect, radius: Float, color: Color, contrastEmPx: Float = 0f,
        opacity: Float = 1f, viewport: SkiaRect? = null, content: DrawScope.() -> Unit) {
        if (opacity <= 0f || closed) return
        val amount = radius.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS)
        val shadow = if (contrastEmPx > 0f) (contrastEmPx * .065f).roundToInt().coerceIn(1, 8) else 0
        val clip = viewport ?: bounds
        val halo = MAX_LYRIC_BLUR_RADIUS * 3f + shadow * 3f + 2f
        val fullLeft = floor(bounds.left).toInt()
        val fullTop = floor(bounds.top).toInt()
        val fullRight = ceil(bounds.right).toInt()
        val fullBottom = ceil(bounds.bottom).toInt()
        val fullFits = key != null && (fullRight - fullLeft).toLong() * (fullBottom - fullTop) <= MAX_MASK_PIXELS
        val left = if (fullFits) fullLeft else maxOf(fullLeft, floor((clip.left - halo) / 128f).toInt() * 128)
        val top = if (fullFits) fullTop else maxOf(fullTop, floor((clip.top - halo) / 128f).toInt() * 128)
        val cropRight = if (fullFits) fullRight else minOf(fullRight, ceil((clip.right + halo) / 128f).toInt() * 128)
        val cropBottom = if (fullFits) fullBottom else minOf(fullBottom, ceil((clip.bottom + halo) / 128f).toInt() * 128)
        val right = left + ((cropRight - left + 3) / 4) * 4
        val bottom = top + ((cropBottom - top + 3) / 4) * 4
        if (right <= left || bottom <= top) return
        val mask = masks.firstOrNull {
            key != null && it.key === key && it.left == left && it.top == top && it.width == right - left && it.height == bottom - top
        }?.also { masks.remove(it); masks.addLast(it) } ?: capture(key, left, top, right - left, bottom - top, content)
        // Source masks have a separate budget from the larger colored textures.
        // Oversized rows can keep their ink while using the bounded native fallback.
        val cached = key != null && mask.alpha.size <= MASK_BUDGET / 4
        val argb = color.toArgb()
        if (mask.color != argb) {
            mask.color = argb
            mask.warmLevel = 0
        }
        val levels = LyricBlurLevels.blend(amount)
        fun request(level: Int): LyricBlurComposite.Request {
            val sigma = LyricBlurLevels.radius(level)
            return LyricBlurComposite.Request(mask, mask.width, mask.height, sigma, argb, shadow,
                (38f * (1f - sigma / 3.25f).coerceIn(0f, 1f)).roundToInt())
        }
        // A miss never calls the CPU convolution from this thread. The short-lived
        // native fallback uses the requested radius until both prepared levels arrive.
        val low = if (cached) composites.getOrPrepare(request(levels.lower)) else null
        val high = if (cached && levels.upper != levels.lower) composites.getOrPrepare(request(levels.upper)) else null
        val ready = low != null && (levels.upper == levels.lower || high != null)
        val target = SkiaRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val native = drawContext.canvas.skiaCanvas
        try {
            if (!ready) {
                mask.wasFallback = true
                mask.readySince = 0L
                drawFallback(mask, target, amount, color, contrastEmPx, opacity)
            } else {
                val now = System.nanoTime()
                if (mask.wasFallback && mask.readySince == 0L) mask.readySince = now
                val mix = if (!mask.wasFallback) 1f else ((now - mask.readySince) / 80_000_000f).coerceIn(0f, 1f)
                if (mix < 1f) {
                    // Keep replacement of the temporary native blur continuous too.
                    native.saveLayer(Canvas.SaveLayerRec(target, null, null, null,
                        Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
                    try {
                        drawFallback(mask, target, amount, color, contrastEmPx, opacity * (1f - mix))
                        blend.draw(native, low, high, target, levels.fraction, opacity * mix, additive = true)
                    } finally { native.restore() }
                } else {
                    mask.wasFallback = false
                    blend.draw(native, low, high, target, levels.fraction, opacity)
                }
            }
            // Spread anticipatory work over frames instead of filling the queue with
            // every level of every row before the next visible row can be serviced.
            if (cached && mask.warmLevel < LyricBlurLevels.count) {
                composites.getOrPrepare(request(mask.warmLevel++))
            }
        } finally { if (!cached) { mask.resident = false; mask.image.close() } }
    }

    private fun DrawScope.drawFallback(mask: Mask, target: SkiaRect, radius: Float, color: Color, contrast: Float, opacity: Float) {
        imagePaint.setAlphaf(1f)
        with(this@LyricTextBlur) {
            paintLive(target, radius, color, contrast, opacity) {
                drawContext.canvas.skiaCanvas.drawImageRect(mask.image, lyricBlurImageBounds(mask.image), target,
                    SamplingMode.LINEAR, imagePaint, true)
            }
        }
    }

    private fun effectPaint(radius: Float, color: Int, contrast: Float, opacity: Float): Paint? {
        val amount = radius.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS)
        val shadow = if (contrast > 0f) (contrast * .065f).roundToInt().coerceIn(1, 8) else 0
        val alpha = (38f * (1f - amount / 3.25f).coerceIn(0f, 1f)).roundToInt()
        val key = EffectKey((amount * 8f).roundToInt(), shadow, alpha, color)
        if (key.blur == 0 && (shadow == 0 || alpha == 0) && color == -1 && opacity >= 1f) return null
        val paint = effects.getOrPut(key) {
            var filter: ImageFilter? = null
            try {
                if (color != -1) ColorFilter.makeBlend(color, BlendMode.SRC_IN).use {
                    filter = ImageFilter.makeColorFilter(it, null, null)
                }
                if (shadow > 0 && alpha > 0) {
                    val next = ImageFilter.makeDropShadow(0f, 0f, shadow.toFloat(), shadow.toFloat(), alpha shl 24, filter)
                    filter?.close()
                    filter = next
                }
                if (key.blur > 0) {
                    val next = ImageFilter.makeBlur(key.blur / 8f, key.blur / 8f, FilterTileMode.DECAL, filter)
                    filter?.close()
                    filter = next
                }
                Paint().apply { imageFilter = filter }
            } finally { filter?.close() }
        }
        while (effects.size > 192) {
            val entry = effects.entries.iterator()
            entry.next().value.close()
            entry.remove()
        }
        return paint.apply { setAlphaf(opacity.coerceIn(0f, 1f)) }
    }

    private fun DrawScope.capture(key: Any?, left: Int, top: Int, width: Int, height: Int, content: DrawScope.() -> Unit): Mask {
        val alpha = Surface.makeRasterN32Premul(width, height).use { surface ->
            CanvasDrawScope().draw(this, layoutDirection, surface.canvas.asComposeCanvas(), Size(width.toFloat(), height.toFloat())) {
                translate(-left.toFloat(), -top.toFloat()) { content() }
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(ImageInfo.makeA8(width, height)))
                check(surface.readPixels(bitmap, 0, 0))
                checkNotNull(bitmap.readPixels())
            }
        }
        return Mask(key, left, top, width, height, alpha).also { mask ->
            if (key != null && alpha.size <= MASK_BUDGET / 4) {
                masks.addLast(mask)
                maskBytes += alpha.size
                while (masks.size > 24 || maskBytes > MASK_BUDGET) {
                    val old = masks.removeFirst()
                    old.resident = false
                    maskBytes -= old.alpha.size
                    composites.discard(old)
                    old.image.close()
                }
            }
        }
    }

    private fun buildComposite(request: LyricBlurComposite.Request): Image {
        val mask = request.owner as Mask
        val image = if (request.radius == 0f) Image.makeRaster(ImageInfo.makeA8(mask.width, mask.height), mask.alpha, mask.width)
        else gaussian.applyAlpha(mask.alpha, mask.width, mask.height, request.radius).let { sampled ->
            Image.makeRaster(ImageInfo(sampled.width, sampled.height, org.jetbrains.skia.ColorType.RGBA_F16,
                org.jetbrains.skia.ColorAlphaType.PREMUL), LyricGaussianBlur.rgba(sampled.alpha), sampled.width * 8)
        }
        return image.use { composeLyricBlur(request, it) }
    }

    fun clear() {
        masks.forEach { it.resident = false; it.image.close() }
        masks.clear()
        maskBytes = 0
        composites.clear()
        blend.clear()
        if (!closed) worker.execute { gaussian.clear() }
    }

    override fun close() {
        if (closed) return
        closed = true
        clear()
        composites.close()
        worker.shutdownNow()
        blend.close()
        imagePaint.close()
        effects.values.forEach { it.close() }
    }

    private companion object {
        const val MASK_BUDGET = 16 * 1024 * 1024
        const val MAX_MASK_PIXELS = 24 * 1024 * 1024 / 32
    }
}

internal fun lyricBlurImageBounds(image: Image): SkiaRect = SkiaRect.makeWH(image.width.toFloat(), image.height.toFloat())
