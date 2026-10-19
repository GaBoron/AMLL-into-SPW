package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

internal data class LyricFooterRow(val text: TextLayoutResult, val top: Float, val opacity: Float)
internal data class LyricFooterLayout(val rows: List<LyricFooterRow>, val height: Float, val padding: Float)

internal const val FOOTER_ATTRIBUTION_OPACITY = .55f
internal fun lyricFooterAttributionFontSize(bodySize: Float): Float = maxOf(8f, bodySize - 2f)

/** At lyric size 48, match the former footer at lyric size 28 (19.6 sp); grow gently either side. */
internal fun lyricFooterFontSize(lyricSizeSp: Float): Float =
    maxOf(10f, 19.6f * sqrt(lyricSizeSp.coerceAtLeast(0f) / 48f))

internal fun layoutLyricFooter(content: LyricFooterContent, measurer: TextMeasurer, width: Int,
    fontSize: Float, typography: LyricTypography): LyricFooterLayout? {
    if (content.text.isBlank()) return null
    val size = lyricFooterFontSize(fontSize)
    var top = 0f
    var emPx = 0f
    val rows = content.sections.mapIndexed { index, section ->
        val sectionSize = if (section.attribution) lyricFooterAttributionFontSize(size) else size
        val text = measurer.measure(AnnotatedString(section.text),
            typography.textStyle(sectionSize).copy(lineHeight = (sectionSize * 1.8f).sp),
            constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
        if (index == 0) emPx = (text.getLineBottom(0) - text.getLineTop(0)) / 1.8f
        if (index > 0 && !section.attribution) top += emPx * .8f
        LyricFooterRow(text, top, if (section.attribution) FOOTER_ATTRIBUTION_OPACITY else 1f)
            .also { top += text.size.height }
    }
    return LyricFooterLayout(rows, top + emPx * .8f, emPx * .4f)
}
