package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.ceil

/** Bounded white text masks: shape/rasterize a line once, then move sampled pixels each frame. */
internal class GlyphAtlas : AutoCloseable {
    data class Entry(val layout: LineLayout, val image: Image, val padding: Float)
    private val entries = ArrayDeque<Entry>()

    fun get(layout: LineLayout, scope: DrawScope): Entry {
        entries.firstOrNull { it.layout === layout }?.let { entries.remove(it); entries.addLast(it); return it }
        val padding = ceil(layout.emPx * .25f)
        val width = ceil((layout.main.size.width + padding * 2) * RESOLUTION).toInt().coerceAtLeast(1)
        val height = ceil((layout.main.size.height + padding * 2) * RESOLUTION).toInt().coerceAtLeast(1)
        val image = Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.scale(RESOLUTION, RESOLUTION)
            surface.canvas.translate(padding, padding)
            CanvasDrawScope().draw(scope, layout.main.layoutInput.layoutDirection, surface.canvas.asComposeCanvas(),
                Size(layout.main.size.width.toFloat(), layout.main.size.height.toFloat())) {
                drawText(layout.main, color = Color.White)
            }
            surface.makeImageSnapshot()
        }
        return Entry(layout, image, padding).also {
            entries.addLast(it)
            while (entries.size > 8) entries.removeFirst().image.close()
        }
    }
    override fun close() { entries.forEach { it.image.close() }; entries.clear() }
    companion object { const val RESOLUTION = 2f }
}
