package io.github.gaboron.amllspw.cover

import androidx.compose.ui.geometry.Rect
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect as SkiaRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A bounded raster from the same shader that paints the visible artwork. Regions are in page pixels. */
internal class ImmersiveBackdropSamples private constructor(
    private val pixels: IntArray,
    private val columns: Int,
    private val rows: Int,
    private val width: Float,
    private val height: Float,
) {
    fun colors(region: Rect): IntArray {
        val left = floor(region.left / width * columns).toInt().coerceIn(0, columns - 1)
        val top = floor(region.top / height * rows).toInt().coerceIn(0, rows - 1)
        val right = ceil(region.right / width * columns).toInt().coerceIn(left + 1, columns)
        val bottom = ceil(region.bottom / height * rows).toInt().coerceIn(top + 1, rows)
        val stride = right - left
        return IntArray(stride * (bottom - top)) { index ->
            pixels[(top + index / stride) * columns + left + index % stride]
        }
    }

    companion object {
        fun render(effect: RuntimeEffect, cover: ImmersiveCover, width: Float, height: Float, anchor: Rect):
            ImmersiveBackdropSamples {
            val extent = maxOf(width, height)
            val columns = (72 * width / extent).roundToInt().coerceAtLeast(24)
            val rows = (72 * height / extent).roundToInt().coerceAtLeast(24)
            return Surface.makeRasterN32Premul(columns, rows).use { surface ->
                surface.canvas.scale(columns / width, rows / height)
                ImmersiveCoverRenderer(effect, cover).use {
                    it.draw(surface.canvas, width, height, SkiaRect.makeLTRB(anchor.left, anchor.top, anchor.right, anchor.bottom))
                }
                Bitmap().use { bitmap ->
                    check(bitmap.allocPixels(surface.imageInfo) && surface.readPixels(bitmap, 0, 0))
                    val pixels = IntArray(columns * rows) { bitmap.getColor(it % columns, it / columns) }
                    ImmersiveBackdropSamples(pixels, columns, rows, width, height)
                }
            }
        }
    }
}
