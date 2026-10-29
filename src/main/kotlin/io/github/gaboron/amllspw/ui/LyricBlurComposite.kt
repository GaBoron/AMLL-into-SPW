package io.github.gaboron.amllspw.ui

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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService

/** Background-composed idle rows need one texture draw, without per-frame shadow/blend layers. */
internal class LyricBlurComposite(
    private val worker: ExecutorService,
    private val alive: (Request) -> Boolean,
    private val build: (Request) -> Image,
) : AutoCloseable {
    data class Request(val owner: Any, val width: Int, val height: Int, val radius: Float, val color: Int, val shadow: Int, val shadowAlpha: Int) {
        val scale: Int get() = LyricGaussianBlur.sampleScale(radius)
        val sampledWidth: Int get() = (width + scale - 1) / scale
        val sampledHeight: Int get() = (height + scale - 1) / scale
        val bytes: Int get() = sampledWidth * sampledHeight * 8
    }
    private data class Ready(val request: Request, val image: Image)
    private val gate = Any()
    private val pending = mutableSetOf<Request>()
    private val ready = ConcurrentLinkedQueue<Ready>()
    private val cache = LinkedHashMap<Request, Image>(16, .75f, true)
    private var cachedBytes = 0
    private var queuedBytes = 0
    private var generation = 0
    private var closed = false

    fun getOrPrepare(request: Request): Image? {
        drain()
        cache[request]?.let { return it }
        if (request.bytes > BUDGET / 4) return null
        val stamp = synchronized(gate) {
            if (closed || !pending.add(request)) return null
            generation
        }
        worker.execute {
            val image = runCatching { if (alive(request)) build(request) else null }.getOrNull()
            synchronized(gate) {
                pending.remove(request)
                if (image != null) {
                    if (!closed && generation == stamp && alive(request) && queuedBytes + request.bytes <= BUDGET) {
                        queuedBytes += request.bytes
                        ready.add(Ready(request, image))
                    } else image.close()
                }
            }
        }
        return null
    }

    private fun drain() {
        while (true) {
            val item = ready.poll() ?: break
            synchronized(gate) { queuedBytes -= item.request.bytes }
            if (!alive(item.request) || cache.containsKey(item.request)) { item.image.close(); continue }
            cache[item.request] = item.image
            cachedBytes += item.request.bytes
            val entries = cache.entries.iterator()
            while (cachedBytes > BUDGET && entries.hasNext()) {
                val old = entries.next()
                cachedBytes -= old.key.bytes
                old.value.close()
                entries.remove()
            }
        }
    }

    fun discard(owner: Any) {
        val entries = cache.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.owner === owner) {
                cachedBytes -= entry.key.bytes
                entry.value.close()
                entries.remove()
            }
        }
    }

    fun clear() {
        synchronized(gate) {
            generation++
            while (true) { (ready.poll() ?: break).image.close() }
            queuedBytes = 0
            pending.clear()
        }
        cache.values.forEach { it.close() }
        cache.clear()
        cachedBytes = 0
    }

    override fun close() {
        synchronized(gate) { closed = true }
        clear()
    }

    private companion object { const val BUDGET = 32 * 1024 * 1024 }
}

internal fun composeLyricBlur(request: LyricBlurComposite.Request, mask: Image): Image {
    val info = ImageInfo(request.sampledWidth, request.sampledHeight, ColorType.RGBA_F16, ColorAlphaType.PREMUL)
    return Surface.makeRaster(info).use { surface ->
        val bounds = SkiaRect.makeWH(request.sampledWidth.toFloat(), request.sampledHeight.toFloat())
        Paint().use { shadow ->
            if (request.shadow > 0 && request.shadowAlpha > 0) ImageFilter.makeDropShadow(0f, 0f,
                request.shadow.toFloat() / request.scale, request.shadow.toFloat() / request.scale,
                request.shadowAlpha shl 24).use { shadow.imageFilter = it }
            val canvas = surface.canvas
            canvas.saveLayer(Canvas.SaveLayerRec(bounds, shadow, null, null,
                Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType)))
            try {
                ColorFilter.makeBlend(request.color, BlendMode.SRC_IN).use { tint ->
                    Paint().use { paint ->
                        paint.colorFilter = tint
                        canvas.drawImageRect(mask, lyricBlurImageBounds(mask), bounds, SamplingMode.LINEAR, paint, true)
                    }
                }
            } finally { canvas.restore() }
        }
        surface.makeImageSnapshot()
    }
}
