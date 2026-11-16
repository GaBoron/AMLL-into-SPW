package io.github.gaboron.amllspw.cover

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** Blur a circular cover into transparent margins so overlapping layers retain their own contours. */
internal fun blurredCoverArtwork(artwork: ImageBitmap): ImageBitmap {
    val edge = minOf(artwork.width, artwork.height).toFloat()
    val left = (artwork.width - edge) / 2f
    val top = (artwork.height - edge) / 2f
    val crop = Rect.makeXYWH(left, top, edge, edge)
    val bounds = Rect.makeLTRB(40f, 40f, 216f, 216f)
    return Surface.makeRasterN32Premul(256, 256).use { source ->
        source.canvas.clear(0)
        source.canvas.clipRRect(RRect.makeLTRB(40f, 40f, 216f, 216f, 88f), true)
        Image.makeFromBitmap(artwork.asSkiaBitmap()).use { image ->
            source.canvas.drawImageRect(image, crop, bounds, SamplingMode.LINEAR, null, true)
        }
        Surface.makeRasterN32Premul(256, 256).use { blurred ->
            blurred.canvas.clear(0)
            // Padding contains the Gaussian tail; the blur must not be clipped to the cover circle.
            ImageFilter.makeBlur(14f, 14f, FilterTileMode.DECAL).use { blur ->
                Paint().use { paint ->
                    paint.imageFilter = blur
                    source.makeImageSnapshot().use { blurred.canvas.drawImage(it, 0f, 0f, paint) }
                }
            }
            blurred.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}
