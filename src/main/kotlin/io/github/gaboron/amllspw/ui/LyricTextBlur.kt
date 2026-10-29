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
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorType
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

/** Native-resolution source masks with a small, prefiltered blur scale space. */
internal class LyricTextBlur : AutoCloseable {
    private class Mask(val key: Any?, val left: Int, val top: Int, val width: Int, val height: Int, val alpha: ByteArray) {
        var resident = false
        var warming = false
        var warmThrough = 0
        var lastAmount = Float.NaN
    }
    private data class Variant(val mask: Mask, val level: Int, val image: Image) {
        val bytes: Int get() = image.width * image.height * if (level == 0) 1 else 8
    }
    private data class PreparedKey(val mask: Mask, val level: Int)
    private val masks = ArrayDeque<Mask>()
    private val variants = ArrayDeque<Variant>()
    private var maskBytes = 0
    private var imageBytes = 0
    private val gaussian = LyricGaussianBlur()
    private val backgroundGaussian = LyricGaussianBlur()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "amll-lyric-blur").apply { isDaemon = true } }
    private val gate = Any()
    private val prepared = LinkedHashMap<PreparedKey, LyricGaussianBlur.Mask>(64, .75f, true)
    private val composites = LyricBlurComposite(worker,
        { owner -> synchronized(gate) { !closed && (owner as Mask).resident } }, ::buildComposite)
    private var preparedBytes = 0
    private val imagePaint = Paint()
    private val livePaint = Paint()
    private val shadows = mutableMapOf<Int, Paint>()
    private var tint: Int? = null
    private var tintFilter: ColorFilter? = null
    @Volatile private var closed = false

    fun DrawScope.paintLive(bounds: SkiaRect, color: Color, contrastEmPx: Float, content: DrawScope.() -> Unit) {
        val native = drawContext.canvas.skiaCanvas
        val radius = if (contrastEmPx > 0f) (contrastEmPx * .065f).roundToInt().coerceIn(1, 8) else 0
        val shadow = shadowPaint(radius)
        updateTint(color.toArgb())
        if (shadow != null) native.saveLayer(Canvas.SaveLayerRec(bounds, shadow, null, null,
            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
        // Keep the accepted grayscale-mask/tint order, but let the host allocate
        // the mask on its own canvas instead of reading it back through CPU memory.
        native.saveLayer(bounds, livePaint)
        try { content() }
        finally {
            native.restore()
            if (shadow != null) native.restore()
        }
    }

    fun DrawScope.paint(key: Any?, bounds: SkiaRect, radius: Float, color: Color, contrastEmPx: Float = 0f,
        opacity: Float = 1f, viewport: SkiaRect? = null, content: DrawScope.() -> Unit) {
        if (opacity <= 0f) return
        val native = drawContext.canvas.skiaCanvas
        val shadowRadius = if (contrastEmPx > 0f) (contrastEmPx * .065f).roundToInt().coerceIn(1, 8) else 0
        // Read enough source beyond the output clip for the complete convolution halo.
        // Very tall paragraphs need only the visible segment plus this halo.
        val clip = viewport ?: bounds
        val halo = MAX_LYRIC_BLUR_RADIUS * 3f + shadowRadius * 3f + 2f
        val fullLeft = floor(bounds.left).toInt()
        val fullTop = floor(bounds.top).toInt()
        val fullRight = ceil(bounds.right).toInt()
        val fullBottom = ceil(bounds.bottom).toInt()
        val fullMaskFits = key != null && (fullRight - fullLeft).toLong() * (fullBottom - fullTop) <= IMAGE_BUDGET / 32
        // Keep ordinary rows intact as they scroll across an edge. Tall paragraph
        // segments use a fixed grid so their cache key does not change every pixel.
        val left = if (fullMaskFits) fullLeft else maxOf(fullLeft, floor((clip.left - halo) / 128f).toInt() * 128)
        val top = if (fullMaskFits) fullTop else maxOf(fullTop, floor((clip.top - halo) / 128f).toInt() * 128)
        val cropRight = if (fullMaskFits) fullRight else minOf(fullRight, ceil((clip.right + halo) / 128f).toInt() * 128)
        val cropBottom = if (fullMaskFits) fullBottom else minOf(fullBottom, ceil((clip.bottom + halo) / 128f).toInt() * 128)
        // All pyramid images cover the same extent, including their zero-filled halo.
        val right = left + ((cropRight - left + 3) / 4) * 4
        val bottom = top + ((cropBottom - top + 3) / 4) * 4
        if (right <= left || bottom <= top) return
        val mask = masks.firstOrNull {
            key != null && it.key === key && it.left == left && it.top == top && it.width == right - left && it.height == bottom - top
        }?.also {
            masks.remove(it)
            masks.addLast(it)
        } ?: capture(key, left, top, right - left, bottom - top, content)
        updateTint(color.toArgb())
        val amount = radius.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS)
        val blend = LyricBlurLevels.blend(amount)
        val lower = blend.lower
        val fraction = blend.fraction
        // The centered contrast shadow is useful on sharp strokes. Fade it out
        // continuously with depth instead of filtering almost invisible blurred ink.
        val shadowAlpha = (38f * (1f - amount / 3.25f).coerceIn(0f, 1f)).roundToInt()
        val unchanged = mask.lastAmount == amount
        mask.lastAmount = amount
        if (unchanged && cacheable(mask)) {
            val request = LyricBlurComposite.Request(mask, mask.width, mask.height,
                radius.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS), color.toArgb(), shadowRadius, shadowAlpha)
            composites.getOrPrepare(request)?.let { image ->
                imagePaint.colorFilter = null
                imagePaint.blendMode = BlendMode.SRC_OVER
                imagePaint.setAlphaf(opacity.coerceIn(0f, 1f))
                try {
                    native.drawImageRect(image, SkiaRect.makeWH(mask.width.toFloat(), mask.height.toFloat()),
                        SkiaRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                        SamplingMode.LINEAR, imagePaint, true)
                } finally { imagePaint.colorFilter = tintFilter }
                return
            }
        }
        val low = variant(mask, lower)
        val high = if (fraction > 0f) variant(mask, lower + 1) else null
        warm(mask, blend.upper)
        val layer = shadowPaint(shadowRadius, shadowAlpha)
        native.saveLayer(Canvas.SaveLayerRec(bounds, layer, null, null,
            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
        try {
            val target = SkiaRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            imagePaint.blendMode = BlendMode.SRC_OVER
            imagePaint.setAlphaf(((1f - fraction) * opacity).coerceIn(0f, 1f))
            native.drawImageRect(low, lyricBlurImageBounds(low), target, SamplingMode.LINEAR, imagePaint, true)
            if (high != null) {
                // Add premultiplied weighted masks on a transparent layer: exact linear
                // interpolation, without alpha-over darkening halfway through a transition.
                imagePaint.blendMode = BlendMode.PLUS
                imagePaint.setAlphaf((fraction * opacity).coerceIn(0f, 1f))
                native.drawImageRect(high, lyricBlurImageBounds(high), target, SamplingMode.LINEAR, imagePaint, true)
            }
        } finally {
            native.restore()
            if (!cacheable(mask)) {
                low.close()
                high?.close()
            }
        }
    }

    private fun shadowPaint(radius: Int, alpha: Int = 38): Paint? = if (radius > 0 && alpha > 0) shadows.getOrPut(radius * 256 + alpha) { Paint().apply {
        ImageFilter.makeDropShadow(0f, 0f, radius.toFloat(), radius.toFloat(), alpha shl 24).use { imageFilter = it }
    } } else null

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
            if (cacheable(mask)) {
                synchronized(gate) { mask.resident = true }
                masks.addLast(mask)
                maskBytes += alpha.size
                while (masks.size > 24 || maskBytes > MASK_BUDGET) {
                    val old = masks.removeFirst()
                    maskBytes -= old.alpha.size
                    discardPrepared(old)
                    val obsolete = variants.filter { it.mask === old }
                    obsolete.forEach {
                        variants.remove(it)
                        imageBytes -= it.bytes
                        it.image.close()
                    }
                }
            }
        }
    }

    private fun cacheable(mask: Mask) = mask.key != null && mask.alpha.size <= IMAGE_BUDGET / 32 && mask.alpha.size <= MASK_BUDGET

    private fun variant(mask: Mask, level: Int): Image {
        variants.firstOrNull { it.mask === mask && it.level == level }?.let {
            variants.remove(it)
            variants.addLast(it)
            return it.image
        }
        val image = if (level == 0) Image.makeRaster(ImageInfo.makeA8(mask.width, mask.height), mask.alpha, mask.width)
        else {
            val key = PreparedKey(mask, level)
            val sampled = synchronized(gate) { prepared[key] } ?: gaussian.applyAlpha(mask.alpha, mask.width, mask.height, LyricBlurLevels.radius(level)).also {
                storePrepared(key, it)
                warm(mask, maxOf(level, mask.warmThrough), refresh = true)
            }
            sampledImage(sampled)
        }
        if (cacheable(mask)) {
            val variant = Variant(mask, level, image)
            variants.addLast(variant)
            imageBytes += variant.bytes
            while (variants.size > 64 || imageBytes > IMAGE_BUDGET) {
                val old = variants.removeFirst()
                imageBytes -= old.bytes
                old.image.close()
            }
        }
        return image
    }

    private fun warm(mask: Mask, through: Int, refresh: Boolean = false) {
        synchronized(gate) {
            if (closed || !mask.resident || through == 0) return
            if (!refresh && through <= mask.warmThrough) return
            mask.warmThrough = maxOf(mask.warmThrough, through)
            if (mask.warming) return
            mask.warming = true
        }
        worker.execute { prepare(mask) }
    }

    private fun prepare(mask: Mask) {
        var level = 1
        while (true) {
            val key = synchronized(gate) {
                if (closed || !mask.resident || level > mask.warmThrough) {
                    mask.warming = false
                    return
                }
                PreparedKey(mask, level++)
            }
            if (synchronized(gate) { prepared.containsKey(key) }) continue
            val sampled = backgroundGaussian.applyAlpha(mask.alpha, mask.width, mask.height, LyricBlurLevels.radius(key.level))
            storePrepared(key, sampled)
        }
    }

    private fun storePrepared(key: PreparedKey, sampled: LyricGaussianBlur.Mask) {
        synchronized(gate) {
            if (closed || !key.mask.resident || prepared.containsKey(key)) return
            prepared[key] = sampled
            preparedBytes += sampled.alpha.size
            val entries = prepared.entries.iterator()
            while (preparedBytes > PREPARED_BUDGET && entries.hasNext()) {
                preparedBytes -= entries.next().value.alpha.size
                entries.remove()
            }
        }
    }

    private fun discardPrepared(mask: Mask) {
        synchronized(gate) {
            mask.resident = false
            val entries = prepared.entries.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.key.mask === mask) {
                    preparedBytes -= entry.value.alpha.size
                    entries.remove()
                }
            }
        }
        composites.discard(mask)
    }

    private fun buildComposite(request: LyricBlurComposite.Request): Image {
        val mask = request.owner as Mask
        val blend = LyricBlurLevels.blend(request.radius)
        val level = blend.lower
        val fraction = blend.fraction
        fun image(index: Int): Image {
            if (index == 0) return Image.makeRaster(ImageInfo.makeA8(mask.width, mask.height), mask.alpha, mask.width)
            val key = PreparedKey(mask, index)
            val sampled = synchronized(gate) { prepared[key] } ?:
                backgroundGaussian.applyAlpha(mask.alpha, mask.width, mask.height, LyricBlurLevels.radius(index)).also { storePrepared(key, it) }
            return sampledImage(sampled)
        }
        return image(level).use { low ->
            val high = if (fraction > 0f) image(level + 1) else null
            try { composeLyricBlur(request, low, high, fraction) }
            finally { high?.close() }
        }
    }

    private fun sampledImage(sampled: LyricGaussianBlur.Mask): Image = Image.makeRaster(
        ImageInfo(sampled.width, sampled.height, ColorType.RGBA_F16, ColorAlphaType.PREMUL),
        LyricGaussianBlur.rgba(sampled.alpha), sampled.width * 8)

    private fun updateTint(argb: Int) {
        if (tint == argb) return
        imagePaint.colorFilter = null
        livePaint.colorFilter = null
        tintFilter?.close()
        tintFilter = ColorFilter.makeBlend(argb, BlendMode.SRC_IN)
        imagePaint.colorFilter = tintFilter
        livePaint.colorFilter = tintFilter
        tint = argb
    }

    fun clear() {
        composites.clear()
        synchronized(gate) {
            masks.forEach { it.resident = false }
            prepared.clear()
            preparedBytes = 0
        }
        variants.forEach { it.image.close() }
        variants.clear()
        masks.clear()
        maskBytes = 0
        imageBytes = 0
        gaussian.clear()
        if (!closed) worker.execute { backgroundGaussian.clear() }
    }

    override fun close() {
        if (closed) return
        closed = true
        composites.close()
        clear()
        worker.shutdownNow()
        imagePaint.close()
        livePaint.close()
        tintFilter?.close()
        shadows.values.forEach { it.close() }
    }

    private companion object {
        const val MASK_BUDGET = 16 * 1024 * 1024
        const val IMAGE_BUDGET = 24 * 1024 * 1024
        const val PREPARED_BUDGET = 32 * 1024 * 1024
    }
}

internal fun lyricBlurImageBounds(image: Image): SkiaRect = SkiaRect.makeWH(image.width.toFloat(), image.height.toFloat())
