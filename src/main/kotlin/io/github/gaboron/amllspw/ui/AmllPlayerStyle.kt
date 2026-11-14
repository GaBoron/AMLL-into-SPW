// SPDX-License-Identifier: AGPL-3.0-only
// Layout and style rules ported from amll-dev/applemusic-like-lyrics react-full,
// revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3 (SteveXMH and AMLL contributors).
package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil

/** CSS viewport units, grid fractions and media queries shared by the native player components. */
internal data class AmllPlayerStyle(val width: Float, val height: Float,
    val infoTextHeight: Float? = null, val timeTextHeight: Float? = null) {
    val vertical = width < height
    val em = if (!vertical && height <= 768f) 12.8f else 16f
    val gap = if (height <= 768f) 2f else 8f
    val infoSize = maxOf(height * .02f, em)
    val smallInfoSize = maxOf(height * .02f, 16f).coerceAtMost(20f)
    val timeSize = maxOf(height * .012f, em * .8f)
    val edge = if (width <= 480f) 20f else 3f * em
    val smallCover = if (width <= 480f) 4.5f * em else 6f * em
    val menuSize = if (width <= 1000f) maxOf(2f * em, minOf(width * .05f, height * .04f)) else height * .035f
    private val thumbRow = height * .04f
    private val bottomRow = 48f
    private val controlsMargin = -8f + em * 1.75f
    private val horizontal = horizontalGrid()
    val coverSize = horizontal.cover
    val outerGutter = if (vertical) edge else maxOf(edge, (width * .45f - coverSize) / 2f)
    // Keep drawing room for lyric glow without adding a second inset to the visible content edges.
    val lyricDrawingPadding = minOf(32f, outerGutter * .5f)
    val lyricContentLeft = if (vertical) outerGutter else width * .45f + gap + outerGutter * .5f
    val lyricContentRight = width - outerGutter
    val lyricCanvasLeft = lyricContentLeft - lyricDrawingPadding
    val lyricCanvasWidth = lyricContentRight - lyricContentLeft + lyricDrawingPadding * 2f
    val coverTop = horizontal.drag + gap * 2f + thumbRow
    val coverCenter = coverTop + coverSize * .5f
    val controlsTop = coverTop + coverSize + gap + controlsMargin
    val controlsHeight = horizontal.controls
    val bottomTop = controlsTop + controlsHeight + gap
    val smallHeaderTop = 64f + em
    val lyricTop = smallHeaderTop + smallCover + 2f
    val verticalControlsHeight = maxOf(menuSize, infoTextHeight ?: infoSize * 2.5f) +
        24f + (timeTextHeight ?: timeSize * 1.25f) + (if (height > 768f) 4f else 0f) +
        ceil(height * .1f) + 24f
    val mediaScale = when {
        width <= 480f -> .5f
        height <= 512f -> 1f
        height <= 768f -> 1.5f
        height <= 1080f -> 2f
        else -> 3f
    }
    val playScale = when {
        width <= 480f -> .5f
        height <= 512f -> .5f
        height <= 768f -> .8f
        height <= 1080f -> 1.1f
        else -> 2f
    }

    private data class HorizontalGrid(val cover: Float, val drag: Float, val controls: Float)

    private fun minimumControls(cover: Float): Float =
        maxOf(menuSize, infoTextHeight ?: infoSize * 2.5f) + 24f + (timeTextHeight ?: timeSize * 1.25f) +
            (if (height > 768f) 4f else 0f) + ceil(cover * .18f) + 24f

    private fun horizontalGrid(): HorizontalGrid {
        val preferred = minOf(height * (if (height <= 1000f) .45f else .5f), width * .38f)
        val fixed = thumbRow + bottomRow + gap * 5f + controlsMargin
        // CSS fr tracks have intrinsic minima. Reserve every control's height before
        // sharing the remaining space; only an over-constrained viewport reduces the cover.
        var cover = preferred
        val deficit = 30f + cover + minimumControls(cover) + fixed - height
        if (deficit > 0f) cover = (cover - deficit / 1.18f).coerceAtLeast(0f)
        val weights = floatArrayOf(.45f, 3f, .3f)
        val minima = floatArrayOf(30f, minimumControls(cover), 0f)
        val tracks = FloatArray(3)
        val remaining = weights.indices.toMutableSet()
        var space = (height - fixed - cover).coerceAtLeast(0f)
        while (remaining.isNotEmpty()) {
            val fraction = space / remaining.sumOf { weights[it].toDouble() }.toFloat()
            val frozen = remaining.filter { weights[it] * fraction < minima[it] }
            if (frozen.isEmpty()) {
                remaining.forEach { tracks[it] = weights[it] * fraction }
                break
            }
            frozen.forEach {
                tracks[it] = minima[it]
                space -= minima[it]
                remaining.remove(it)
            }
        }
        return HorizontalGrid(cover, tracks[0], tracks[1])
    }
    fun cover(hiddenLyrics: Boolean): Rect {
        if (!vertical) {
            val left = if (hiddenLyrics) (width - coverSize) / 2f else outerGutter
            return Rect(left, coverTop, left + coverSize, coverTop + coverSize)
        }
        val size = if (hiddenLyrics) minOf(width - edge * 2,
            (height - smallHeaderTop - em - verticalControlsHeight - 24f).coerceAtLeast(0f)) else smallCover
        val x = if (hiddenLyrics) (width - size) / 2 else edge
        return Rect(x, smallHeaderTop, x + size, smallHeaderTop + size)
    }
}
