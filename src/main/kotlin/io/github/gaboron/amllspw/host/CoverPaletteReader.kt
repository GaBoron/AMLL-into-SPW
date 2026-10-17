package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal object CoverPaletteReader {
    fun read(trackId: String): List<Int>? = runCatching {
        if (trackId.isBlank()) return null
        val bytes = WorkshopApi.library.getCoverById(trackId).toCompletableFuture().get(3, TimeUnit.SECONDS) ?: return null
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        listOf(.2 to .2, .75 to .25, .25 to .75, .7 to .7).map { (x, y) ->
            val pixel = image.getRGB((image.width * x).toInt().coerceIn(0, image.width - 1), (image.height * y).toInt().coerceIn(0, image.height - 1))
            fun component(shift: Int) = (((pixel ushr shift) and 255) * .5f + 12).toInt().coerceIn(0, 255)
            (255 shl 24) or (component(16) shl 16) or (component(8) shl 8) or component(0)
        }
    }.getOrNull()
}
