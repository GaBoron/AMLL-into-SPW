package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.cover.CoverLyricColor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

/** Deduplicated artwork requests and a bounded color cache, independent of lyric IO. */
internal class CoverPaletteReader(
    private val cover: (String) -> CompletionStage<ByteArray?> = { WorkshopApi.library.getCoverById(it) },
) : AutoCloseable {
    data class Colors(val palette: List<Int>, val lyric: Int, val glow: Int = lyric, val artwork: ImageBitmap? = null)

    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "amll-cover-colors").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<String, Colors?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Colors?>?) = size > 32
    }
    private val pending = mutableMapOf<String, CompletableFuture<Colors?>>()
    private var closed = false

    @Synchronized
    fun load(trackId: String): CompletableFuture<Colors?> {
        if (closed || trackId.isBlank()) return CompletableFuture.completedFuture(null)
        if (cache.containsKey(trackId)) return CompletableFuture.completedFuture(cache[trackId])
        pending[trackId]?.let { return it }
        val result = CompletableFuture<Colors?>()
        pending[trackId] = result
        runCatching {
            // Apply the timeout to our dependent stage, never to the host-owned future.
            cover(trackId).thenApplyAsync({ bytes -> bytes?.let(::decode) }, decoder)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS)
        }.onSuccess { request ->
            request.whenComplete { colors, error -> complete(trackId, result, colors, error) }
        }.onFailure { error -> complete(trackId, result, null, error) }
        return result
    }

    private fun complete(trackId: String, result: CompletableFuture<Colors?>, colors: Colors?, error: Throwable?) {
        synchronized(this) {
            if (pending[trackId] !== result) return
            pending.remove(trackId)
            // Missing artwork is cacheable; failed/timed-out requests may be retried later.
            if (!closed && error == null) cache[trackId] = colors
        }
        result.complete(colors)
    }

    private fun decode(bytes: ByteArray): Colors? {
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        val palette = listOf(.2 to .2, .75 to .25, .25 to .75, .7 to .7).map { (x, y) ->
            val pixel = image.getRGB((image.width * x).toInt().coerceIn(0, image.width - 1), (image.height * y).toInt().coerceIn(0, image.height - 1))
            fun component(shift: Int) = (((pixel ushr shift) and 255) * .5f + 12).toInt().coerceIn(0, 255)
            (255 shl 24) or (component(16) shl 16) or (component(8) shl 8) or component(0)
        }
        val accent = CoverLyricColor.accent(CoverLyricColor.dominant(image))
        // Keep the artwork bounded in the same cache as its colors; never decode on the UI thread.
        val edge = minOf(512, maxOf(image.width, image.height))
        val ratio = edge.toDouble() / maxOf(image.width, image.height)
        val thumbnail = BufferedImage((image.width * ratio).toInt().coerceAtLeast(1),
            (image.height * ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = thumbnail.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, 0, 0, thumbnail.width, thumbnail.height, null)
        } finally { graphics.dispose() }
        return Colors(palette, accent.foreground, accent.glow, thumbnail.toComposeImageBitmap())
    }

    override fun close() {
        val requests = synchronized(this) {
            closed = true
            pending.values.toList().also { pending.clear(); cache.clear() }
        }
        requests.forEach { it.cancel(false) }
        decoder.shutdownNow()
    }
}
