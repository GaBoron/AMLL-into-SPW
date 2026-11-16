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
    // Preserve the cover-to-padding and blur ratios of a 512px source at a bounded texture size.
    val scale = 256f / (512f + 180f)
    val inset = 90f * scale
    val content = 512f * scale
    val bounds = Rect.makeXYWH(inset, inset, content, content)
    return Surface.makeRasterN32Premul(256, 256).use { source ->
        source.canvas.clear(0)
        source.canvas.clipRRect(RRect.makeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom, content / 2f), true)
        Image.makeFromBitmap(artwork.asSkiaBitmap()).use { image ->
            source.canvas.drawImageRect(image, crop, bounds, SamplingMode.LINEAR, null, true)
        }
        Surface.makeRasterN32Premul(256, 256).use { blurred ->
            blurred.canvas.clear(0)
            // Padding contains the Gaussian tail; the blur must not be clipped to the cover circle.
            ImageFilter.makeBlur(40f * scale, 40f * scale, FilterTileMode.DECAL).use { blur ->
                Paint().use { paint ->
                    paint.imageFilter = blur
                    source.makeImageSnapshot().use { blurred.canvas.drawImage(it, 0f, 0f, paint) }
                }
            }
            blurred.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}
