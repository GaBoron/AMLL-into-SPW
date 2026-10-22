package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextLayoutResult
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.ceil

/** Bounded white text masks: shape/rasterize a line once, then move sampled pixels each frame. */
internal class GlyphAtlas(private val capacity: Int = 8) : AutoCloseable {
    data class Entry(val text: TextLayoutResult, val image: Image, val padding: Float)
    private val entries = ArrayDeque<Entry>()

    fun get(layout: LineLayout, scope: DrawScope): Entry = get(layout.main, layout.emPx, scope)

    fun get(text: TextLayoutResult, emPx: Float, scope: DrawScope): Entry {
        entries.firstOrNull { it.text === text }?.let { entries.remove(it); entries.addLast(it); return it }
        val padding = ceil(emPx * .25f)
        val width = ceil((text.size.width + padding * 2) * RESOLUTION).toInt().coerceAtLeast(1)
        val height = ceil((text.size.height + padding * 2) * RESOLUTION).toInt().coerceAtLeast(1)
        val image = Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.scale(RESOLUTION, RESOLUTION)
            surface.canvas.translate(padding, padding)
            CanvasDrawScope().draw(scope, text.layoutInput.layoutDirection, surface.canvas.asComposeCanvas(),
                Size(text.size.width.toFloat(), text.size.height.toFloat())) {
                drawText(text, color = Color.White)
            }
            surface.makeImageSnapshot()
        }
        return Entry(text, image, padding).also {
            entries.addLast(it)
            while (entries.size > capacity) entries.removeFirst().image.close()
        }
    }
    fun clear() { entries.forEach { it.image.close() }; entries.clear() }
    override fun close() = clear()
    companion object { const val RESOLUTION = 2f }
}
