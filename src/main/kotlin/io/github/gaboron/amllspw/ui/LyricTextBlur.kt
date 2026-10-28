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

/** Full-resolution row masks, independent of the host clip and GPU blur downsampling. */
internal class LyricTextBlur : AutoCloseable {
    private class Mask(val key: Any?, val left: Int, val top: Int, val width: Int, val height: Int, val alpha: ByteArray) {
        var resident = false
        var warming = false
        var warmThrough = 0
    }
    private data class Variant(val mask: Mask, val level: Int, val image: Image)
    private data class PreparedKey(val mask: Mask, val level: Int)
    private val masks = ArrayDeque<Mask>()
    private val variants = ArrayDeque<Variant>()
    private var maskBytes = 0
    private var imageBytes = 0
    private val gaussian = LyricGaussianBlur()
    private val backgroundGaussian = LyricGaussianBlur()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "amll-lyric-blur").apply { isDaemon = true } }
    private val gate = Any()
    private val prepared = LinkedHashMap<PreparedKey, ByteArray>(64, .75f, true)
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
        val right = if (fullMaskFits) fullRight else minOf(fullRight, ceil((clip.right + halo) / 128f).toInt() * 128)
        val bottom = if (fullMaskFits) fullBottom else minOf(fullBottom, ceil((clip.bottom + halo) / 128f).toInt() * 128)
        if (right <= left || bottom <= top) return
        val mask = masks.firstOrNull {
            key != null && it.key === key && it.left == left && it.top == top && it.width == right - left && it.height == bottom - top
        }?.also {
            masks.remove(it)
            masks.addLast(it)
        } ?: capture(key, left, top, right - left, bottom - top, content)
        updateTint(color.toArgb())
        val amount = radius.coerceIn(0f, MAX_LYRIC_BLUR_RADIUS) * 2f
        val lower = floor(amount).toInt()
        val fraction = amount - lower
        val low = variant(mask, lower)
        val high = if (fraction > 0f) variant(mask, lower + 1) else null
        warm(mask, if (high != null) lower + 1 else lower)
        val layer = shadowPaint(shadowRadius)
        native.saveLayer(Canvas.SaveLayerRec(bounds, layer, null, null,
            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
        try {
            val source = SkiaRect.makeWH(mask.width.toFloat(), mask.height.toFloat())
            val target = SkiaRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            imagePaint.blendMode = BlendMode.SRC_OVER
            imagePaint.setAlphaf(((1f - fraction) * opacity).coerceIn(0f, 1f))
            native.drawImageRect(low, source, target, SamplingMode.LINEAR, imagePaint, true)
            if (high != null) {
                // Add premultiplied weighted masks on a transparent layer: exact linear
                // interpolation, without alpha-over darkening halfway through a transition.
                imagePaint.blendMode = BlendMode.PLUS
                imagePaint.setAlphaf((fraction * opacity).coerceIn(0f, 1f))
                native.drawImageRect(high, source, target, SamplingMode.LINEAR, imagePaint, true)
            }
        } finally {
            native.restore()
            if (!cacheable(mask)) {
                low.close()
                high?.close()
            }
        }
    }

    private fun shadowPaint(radius: Int): Paint? = if (radius > 0) shadows.getOrPut(radius) { Paint().apply {
        ImageFilter.makeDropShadow(0f, 0f, radius.toFloat(), radius.toFloat(), 0x26000000).use { imageFilter = it }
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
                        imageBytes -= old.alpha.size * 8
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
            val half = synchronized(gate) { prepared[key] } ?: gaussian.applyAlpha(mask.alpha, mask.width, mask.height, level * .5f).also {
                storePrepared(key, it)
                warm(mask, maxOf(level, mask.warmThrough), refresh = true)
            }
            val alpha = LyricGaussianBlur.rgba(half)
            val info = ImageInfo(mask.width, mask.height, ColorType.RGBA_F16, ColorAlphaType.PREMUL)
            Image.makeRaster(info, alpha, mask.width * 8)
        }
        if (cacheable(mask)) {
            variants.addLast(Variant(mask, level, image))
            imageBytes += mask.alpha.size * 8
            while (variants.size > 64 || imageBytes > IMAGE_BUDGET) {
                val old = variants.removeFirst()
                imageBytes -= old.mask.alpha.size * 8
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
            val half = backgroundGaussian.applyAlpha(mask.alpha, mask.width, mask.height, key.level * .5f)
            storePrepared(key, half)
        }
    }

    private fun storePrepared(key: PreparedKey, half: ByteArray) {
        synchronized(gate) {
            if (closed || !key.mask.resident || prepared.containsKey(key)) return
            prepared[key] = half
            preparedBytes += half.size
            val entries = prepared.entries.iterator()
            while (preparedBytes > PREPARED_BUDGET && entries.hasNext()) {
                preparedBytes -= entries.next().value.size
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
                    preparedBytes -= entry.value.size
                    entries.remove()
                }
            }
        }
    }

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
