// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Prism UI popup.dart (chen08209, GPL-3.0), revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

internal data class PlayerGlassFrame(val rect: Rect, val radius: Float, val opacity: Float, val content: Float)

/** Prism's bare-control opening: a small round pane grows into a card; its contents keep their aspect ratio. */
internal object PlayerGlassMotion {
    private val opening = FloatSpringSpec(.754f, 400f)
    private val corners = FloatSpringSpec(.75f, 324f)
    private val easeOut = CubicBezierEasing(0f, 0f, .58f, 1f)

    fun source(anchor: Rect): PlayerGlassFrame {
        val side = maxOf(1f, min(anchor.width, anchor.height) * .41f)
        return PlayerGlassFrame(Rect(anchor.center - Offset(side / 2, side / 2),
            anchor.center + Offset(side / 2, side / 2)), side / 2, 0f, 0f)
    }

    fun open(from: PlayerGlassFrame, target: Rect, phase: Float, density: Float): PlayerGlassFrame {
        if (phase >= 1f) return PlayerGlassFrame(target, 24f * density, 1f, 1f)
        val time = (phase * 450_000_000L).toLong()
        val progress = opening.getValueFromNanos(time, 0f, 1f, 0f)
        val scale = mix(from.rect.width / target.width, 1f, progress).coerceAtLeast(.001f)
        val initialHeight = from.rect.height * target.width / from.rect.width
        val width = target.width * scale
        val height = mix(initialHeight, target.height, progress) * scale
        val center = from.rect.center + (target.center - from.rect.center) * progress
        val roundness = corners.getValueFromNanos((time - 70_000_000L).coerceAtLeast(0), 1f, 0f, 0f)
        val initialRadius = from.radius * target.width / from.rect.width
        val radius = (mix(24f * density, initialRadius, roundness) * scale)
            .coerceIn(0f, min(width, height) / 2)
        return PlayerGlassFrame(Rect(center - Offset(width / 2, height / 2), center + Offset(width / 2, height / 2)),
            radius, mix(from.opacity, 1f, (phase * 4.5f).coerceIn(0f, 1f)),
            mix(from.content, 1f, progress.coerceIn(0f, 1f)))
    }

    /** A context menu has no glass button to merge into: Prism shrinks it to 80% over 200 ms. */
    fun close(from: PlayerGlassFrame, anchor: Rect, phase: Float): PlayerGlassFrame {
        val progress = easeOut.transform(phase.coerceIn(0f, 1f))
        val scale = mix(1f, .8f, progress)
        val origin = Offset(anchor.center.x.coerceIn(from.rect.left, from.rect.right),
            anchor.center.y.coerceIn(from.rect.top, from.rect.bottom))
        return PlayerGlassFrame(Rect(origin + (from.rect.topLeft - origin) * scale,
            origin + (from.rect.bottomRight - origin) * scale), from.radius * scale,
            from.opacity * (1f - progress), from.content * (1f - progress))
    }

    private fun mix(a: Float, b: Float, fraction: Float) = a + (b - a) * fraction
}
