package io.github.gaboron.amllspw.cover

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** Prepare a small, opaque texture on the cover decoder thread; animation never runs a blur. */
internal fun blurredCoverArtwork(artwork: ImageBitmap): ImageBitmap {
    val edge = minOf(artwork.width, artwork.height).toFloat()
    val left = (artwork.width - edge) / 2f
    val top = (artwork.height - edge) / 2f
    val crop = Rect.makeXYWH(left, top, edge, edge)
    val bounds = Rect.makeWH(192f, 192f)
    return Surface.makeRasterN32Premul(192, 192).use { surface ->
        surface.canvas.clear(0xff101014.toInt())
        Image.makeFromBitmap(artwork.asSkiaBitmap()).use { image ->
            ImageFilter.makeBlur(22f, 22f, FilterTileMode.CLAMP).use { blur ->
                Paint().use { paint ->
                    paint.imageFilter = blur
                    surface.canvas.drawImageRect(image, crop, bounds, SamplingMode.LINEAR, paint, true)
                }
            }
        }
        surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
    }
}
