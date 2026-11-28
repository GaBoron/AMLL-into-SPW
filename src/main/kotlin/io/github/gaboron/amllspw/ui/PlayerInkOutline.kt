package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ImageFilter

/** Adds contrast to the foreground alpha only, leaving the artwork and spaces between glyphs intact. */
internal fun playerInkOutline(radius: Float, darkInk: Boolean = false): ImageFilter =
    ImageFilter.makeDilate(radius, radius, null, null).use { mask ->
        val color = if (darkInk) 0xc4ffffff.toInt() else 0xc4000000.toInt()
        ColorFilter.makeBlend(color, BlendMode.SRC_IN).use { tint ->
            ImageFilter.makeColorFilter(tint, mask, null).use { outline ->
                ImageFilter.makeBlend(BlendMode.SRC_OVER, outline, null, null)
            }
        }
    }

@Composable
internal fun rememberPlayerInkOutline(darkInk: Boolean): RenderEffect? {
    val radius = with(LocalDensity.current) { .85.dp.roundToPx().coerceAtLeast(1).toFloat() }
    val filter = remember(radius, darkInk) { runCatching { playerInkOutline(radius, darkInk) }.getOrNull() }
    DisposableEffect(filter) { onDispose { filter?.close() } }
    return remember(filter) { filter?.asComposeRenderEffect() }
}
