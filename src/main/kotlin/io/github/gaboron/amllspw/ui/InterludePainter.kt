package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.gaboron.amllspw.timeline.InterludeSnapshot

internal fun interludeSlotHeight(fontPx: Float, viewportHeight: Float = 0f) =
    (viewportHeight * .01f).coerceIn(fontPx * .5f, fontPx * 3) + fontPx * 1.6f
internal fun interludeWidth(fontPx: Float) = fontPx * 1.26f

/** Coordinates come from the reserved layout slot, including scroll and spring movement. */
internal fun DrawScope.paintInterlude(snapshot: InterludeSnapshot, y: Float, x: Float,
    fontPx: Float, occupied: List<Rect>, reducedMotion: Boolean) {
    if (!snapshot.active || snapshot.opacity <= 0f) return
    val radius = fontPx * .15f
    val spacing = radius * 3.2f
    val width = interludeWidth(fontPx)
    val scale = if (reducedMotion) 1f else snapshot.scale
    val pivot = Offset(x + width / 2, y)
    val bounds = Rect(pivot.x - width * scale / 2, y - radius * scale, pivot.x + width * scale / 2, y + radius * scale)
    // During a staggered transition an outgoing line can still cross the new slot.
    if (occupied.any { it.overlaps(bounds) }) return
    withTransform({ scale(scale, scale, pivot) }) {
        for (i in 0..2) drawCircle(Color.White.copy(alpha = (snapshot.dots[i] * snapshot.opacity).coerceIn(0f, 1f)),
            radius, Offset(x + radius + i * spacing, y))
    }
}
