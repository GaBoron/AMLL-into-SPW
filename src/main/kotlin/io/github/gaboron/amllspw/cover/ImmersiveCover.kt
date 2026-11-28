package io.github.gaboron.amllspw.cover

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage

internal class ImmersiveCover(
    val original: ImageBitmap,
    val crop: Rect,
    val colorField: ImageBitmap,
    val detailMask: ImageBitmap,
    val material: ImageBitmap,
    val surroundings: List<Int>,
    val profile: CoverBackdropProfile,
)

/** Match the player's square center crop for both the original and synthesized pixels. */
internal fun coverCrop(width: Int, height: Int): Rect {
    val edge = minOf(width, height).toFloat()
    return Rect.makeXYWH((width - edge) / 2f, (height - edge) / 2f, edge, edge)
}

internal fun prepareImmersiveCover(artwork: ImageBitmap, checkCancellation: () -> Unit): ImmersiveCover {
    val fullCrop = coverCrop(artwork.width, artwork.height)
    val fullSource = sampleCover(artwork, fullCrop)
    val crop = trimCoverMatte(fullCrop, fullSource)
    val source = if (crop == fullCrop) fullSource else sampleCover(artwork, crop)
    val initialField = CoverColorField(source)
    val profile = coverBackdropProfile(source)
    checkCancellation()
    val matte = coverEdgeMatte(source, initialField, profile)
    val field = CoverColorField(source, matte.background)
    checkCancellation()
    val edge = COVER_PLATE_SIZE
    val colors = coverBitmap(COVER_FIELD_SIZE, COVER_FIELD_SIZE, field.pixels())
    val material = coverBitmap(MATERIAL_COLUMNS * MATERIAL_TILE, MATERIAL_ROWS * MATERIAL_TILE, extractCoverMaterial(source))
    val far = COVER_SAMPLE_SIZE / 2
    val opposite = edge - far
    val surroundings = listOf(field.color(far, far), field.color(opposite, far),
        field.color(far, opposite), field.color(opposite, opposite)).map(profile::blendGround)
    return ImmersiveCover(artwork, crop, colors, coverBitmap(COVER_SAMPLE_SIZE, COVER_SAMPLE_SIZE, matte.mask),
        material, surroundings, profile)
}

private fun coverBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, width, height, pixels, 0, width)
    }.toComposeImageBitmap()

private fun sampleCover(artwork: ImageBitmap, crop: Rect): IntArray {
    val size = COVER_SAMPLE_SIZE
    return Surface.makeRasterN32Premul(size, size).use { surface ->
        surface.canvas.clear(0xff090909.toInt())
        Image.makeFromBitmap(artwork.asSkiaBitmap()).use { image ->
            surface.canvas.drawImageRect(image, crop,
                Rect.makeWH(size.toFloat(), size.toFloat()), SamplingMode.LINEAR, null, true)
        }
        Bitmap().use { bitmap ->
            check(bitmap.allocPixels(surface.imageInfo))
            check(surface.readPixels(bitmap, 0, 0))
            IntArray(size * size) { bitmap.getColor(it % size, it / size) }
        }
    }
}
