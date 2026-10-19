package io.github.gaboron.amllspw.ui

import kotlin.math.sqrt

/** Shared typography and spacing for the scrolling lyric footer and the poetry footer. */
internal const val FOOTER_ATTRIBUTION_OPACITY = .55f

/** At lyric size 48, match the former footer at lyric size 28 (19.6 sp). */
internal fun lyricFooterFontSize(lyricSizeSp: Float): Float =
    maxOf(10f, 19.6f * sqrt(lyricSizeSp.coerceAtLeast(0f) / 48f))

internal fun lyricFooterAttributionFontSize(bodySize: Float): Float = maxOf(8f, bodySize * .75f)

internal fun lyricFooterLineHeight(attribution: Boolean): Float = if (attribution) 1.35f else 1.45f

/** Keep attribution attached to its body; leave a larger gap before the next provider. */
internal fun lyricFooterGapEm(index: Int, attribution: Boolean): Float = when {
    index == 0 -> 0f
    attribution -> .12f
    else -> 1.2f
}
