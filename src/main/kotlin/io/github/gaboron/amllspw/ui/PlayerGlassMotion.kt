// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Prism UI popup.dart (chen08209, GPL-3.0), revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.sqrt

internal data class PlayerGlassFrame(val rect: Rect, val radius: Float, val opacity: Float, val content: Float,
    val droplet: Rect? = null, val blend: Float = 0f, val glyph: Rect? = null, val glyphOpacity: Float = 0f,
    val optics: Float = 1f, val frost: Float = 12f, val contentBlur: Float = 0f, val initial: Boolean = false) {
    val outline by lazy(LazyThreadSafetyMode.NONE) { PlayerGlassOutline(this) }
}

/** Prism's button morph and unanchored context-menu branches share the same material and geometry clock. */
internal object PlayerGlassMotion {
    private val opening = FloatSpringSpec(.754f, 400f)
    private val corners = FloatSpringSpec(.75f, 324f)
    private val easeOut = CubicBezierEasing(0f, 0f, .58f, 1f)
    private val easeIn = CubicBezierEasing(.42f, 0f, 1f, 1f)
    private val easeInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
    private val glyphCurve = CubicBezierEasing(.25f, .1f, .25f, 1f)
    private val returning = FloatSpringSpec(.8f, 165f)

    fun source(anchor: Rect): PlayerGlassFrame {
        val side = maxOf(1f, min(anchor.width, anchor.height) * .41f)
        return PlayerGlassFrame(Rect(anchor.center - Offset(side / 2, side / 2),
            anchor.center + Offset(side / 2, side / 2)), side / 2, 0f, 0f, glyph = anchor, glyphOpacity = 1f,
            optics = 0f, contentBlur = 4f, initial = true)
    }

    fun open(from: PlayerGlassFrame, target: Rect, phase: Float, density: Float, control: Rect? = null): PlayerGlassFrame {
        if (phase >= 1f) return PlayerGlassFrame(target, 24f * density, 1f, 1f)
        if (control != null && from.initial) return openFromButton(control, target, phase, density)
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
        val droplet = from.droplet?.let {
            centered(it.center + (target.center - it.center) * progress,
                mix(it.width, target.width * .25f, progress), mix(it.height, target.height * .25f, progress))
        }
        return PlayerGlassFrame(Rect(center - Offset(width / 2, height / 2), center + Offset(width / 2, height / 2)),
            radius, mix(from.opacity, 1f, (phase * 4.5f).coerceIn(0f, 1f)),
            mix(from.content, 1f, progress.coerceIn(0f, 1f)), droplet, from.blend * (1f - progress.coerceIn(0f, 1f)),
            glyph = from.glyph,
            glyphOpacity = from.glyphOpacity * (1f - (progress / .25f).coerceIn(0f, 1f)),
            optics = mix(from.optics, 1f, progress.coerceIn(0f, 1f)),
            frost = mix(from.frost, 12f, progress.coerceIn(0f, 1f)),
            contentBlur = from.contentBlur * (1f - progress.coerceIn(0f, 1f)))
    }

    /** Prism's source-image branch keeps a droplet at the button while the platter departs. */
    private fun openFromButton(control: Rect, card: Rect, phase: Float, density: Float): PlayerGlassFrame {
        val time = phase * .45f
        val nanos = (time * 1_000_000_000L).toLong()
        val anchor = dropAnchor(control, density)
        val progress = opening.getValueFromNanos(nanos, 0f, 1f, 0f)
        val scale = mix(.41f * min(anchor.width, anchor.height) / card.width, 1f, progress)
        val height = mix(card.width, card.height, progress)
        val distance = card.center.y - anchor.center.y
        val away = if (distance < 0f) -1f else 1f
        val dip = away * (min(1f, abs(distance) / (30f * density)) * 23f * density + .124f * abs(distance)) *
            pulse(time, .098f, 3.3f)
        val beyond = Offset(max(0f, anchor.width - anchor.height), max(0f, anchor.height - anchor.width)) *
            (1f - progress).coerceIn(0f, 1f)
        val platter = centered(anchor.center + (card.center - anchor.center) * progress + Offset(0f, dip),
            max(.001f, card.width * scale + beyond.x), max(.001f, height * scale + beyond.y))
        val corners = 24f * density + card.width * .48f * this.corners.getValueFromNanos(
            (nanos - 70_000_000L).coerceAtLeast(0), 1f, 0f, 0f)
        val radius = max(0f, min(height / 2f, corners) * scale)
        val inset = max(anchor.width, anchor.height) * .25f / 2f + 36f * density
        val inner = card.deflate(inset)
        val sunk = anchor.center + (card.center - anchor.center) * .25f
        val target = if (inner.isEmpty) card.center else Offset(sunk.x.coerceIn(inner.left, inner.right),
            sunk.y.coerceIn(inner.top, inner.bottom))
        val droplet = centered(anchor.center + (target - anchor.center) * progress,
            anchor.width * mix(1f, .25f, progress), anchor.height * mix(1f, .25f, progress))
        val settled = progress.coerceIn(0f, 1f)
        val glyph = centered(droplet.center, droplet.width * control.width / anchor.width,
            droplet.height * control.height / anchor.height)
        val basicBlur = 4f * (1f - settled)
        val bump = 1.9f * pulse(time, .069f, 2.1f)
        return PlayerGlassFrame(platter, radius, (time / .1f).coerceIn(0f, 1f), settled,
            droplet, 28f * density, glyph, 1f - (settled / .25f).coerceIn(0f, 1f),
            optics = settled, contentBlur = sqrt(basicBlur * basicBlur + bump * bump))
    }

    /** A context menu has no glass button to merge into: Prism shrinks it to 80% over 200 ms. */
    fun close(from: PlayerGlassFrame, anchor: Rect, phase: Float, returnToAnchor: Boolean = false,
        density: Float = 1f): PlayerGlassFrame {
        if (returnToAnchor) return returnToButton(from, anchor, phase, density)
        val progress = easeOut.transform(phase.coerceIn(0f, 1f))
        val scale = mix(1f, .8f, progress)
        val origin = Offset(anchor.center.x.coerceIn(from.rect.left, from.rect.right),
            anchor.center.y.coerceIn(from.rect.top, from.rect.bottom))
        return PlayerGlassFrame(Rect(origin + (from.rect.topLeft - origin) * scale,
            origin + (from.rect.bottomRight - origin) * scale), from.radius * scale,
            from.opacity * (1f - progress), from.content * (1f - progress))
    }

    /** Prism's 500 ms return branch: dip, absorb the platter, lift/re-form the source glyph. */
    private fun returnToButton(from: PlayerGlassFrame, control: Rect, phase: Float, density: Float): PlayerGlassFrame {
        val time = phase.coerceIn(0f, 1f) * .5f
        val anchor = dropAnchor(control, density)
        val ringing = 1f - easeIn.transform(((time - .4f) / .1f).coerceIn(0f, 1f))
        val progress = 1f + (returning.getValueFromNanos((time * 1_000_000_000L).toLong(), 0f, 1f, 0f) - 1f) * ringing
        val distance = from.rect.center.y - anchor.center.y
        val away = if (distance < 0) -1f else 1f
        val dip = min(.21f * abs(distance), 22f * density) * pulse(time, .072f, 2.4f)
        val lift = min(.25f * abs(distance), 19f * density) * pulse(time, .176f, 4.9f)
        val scale = mix(1f, .41f * min(anchor.width, anchor.height) / max(from.rect.width, from.rect.height), progress)
        val beyond = Offset(max(0f, anchor.width - anchor.height), max(0f, anchor.height - anchor.width)) * progress.coerceIn(0f, 1f)
        val center = from.rect.center + (anchor.center - from.rect.center) * progress + Offset(0f, away * dip * ringing)
        val platter = centered(center, max(.001f, from.rect.width * scale + beyond.x),
            max(.001f, mix(from.rect.height, from.rect.width, progress) * scale + beyond.y))
        val radius = max(0f, mix(from.radius, from.rect.width / 2f, progress) * scale)
        val initialDrop = from.droplet ?: centered(anchor.center + (from.rect.center - anchor.center) * .25f,
            anchor.width * .25f, anchor.height * .25f)
        val droplet = centered(initialDrop.center + (anchor.center - initialDrop.center) * progress - Offset(0f, away * lift * ringing),
            mix(initialDrop.width, anchor.width, progress), mix(initialDrop.height, anchor.height, progress))
        val absorbed = easeInOut.transform(((phase - .6f) / .3f).coerceIn(0f, 1f))
        val glyph = centered(droplet.center, droplet.width * control.width / anchor.width,
            droplet.height * control.height / anchor.height)
        return PlayerGlassFrame(platter, radius, from.opacity * (1f - absorbed),
            from.content * (1f - easeOut.transform((time / .1f).coerceIn(0f, 1f))),
            droplet, 28f * density * min(1f, time / .05f) * (1f - absorbed), glyph,
            mix(.2f, 1f, glyphCurve.transform((time / .47f).coerceIn(0f, 1f))),
            optics = (1f - progress).coerceIn(0f, 1f), frost = mix(12f, 2.5f, absorbed),
            contentBlur = 8f * easeOut.transform((time / .1f).coerceIn(0f, 1f)))
    }

    private fun dropAnchor(control: Rect, density: Float) = Rect(control.left - 12f * density, control.top - 6f * density,
        control.right + 12f * density, control.bottom + 6f * density)

    private fun centered(center: Offset, width: Float, height: Float) =
        Rect(center - Offset(width / 2, height / 2), center + Offset(width / 2, height / 2))

    private fun pulse(time: Float, peak: Float, sharpness: Float): Float {
        if (time <= 0f) return 0f
        val x = time / peak
        return x.pow(sharpness) * exp(sharpness * (1f - x))
    }

    private fun mix(a: Float, b: Float, fraction: Float) = a + (b - a) * fraction
}
