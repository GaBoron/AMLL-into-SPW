package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

internal data class LyricFooterRow(val text: TextLayoutResult, val top: Float, val opacity: Float)
internal data class LyricFooterLayout(val rows: List<LyricFooterRow>, val height: Float, val padding: Float)

internal fun layoutLyricFooter(content: LyricFooterContent, measurer: TextMeasurer, width: Int,
    fontSize: Float, typography: LyricTypography): LyricFooterLayout? {
    if (content.text.isBlank()) return null
    val size = lyricFooterFontSize(fontSize)
    var top = 0f
    var emPx = 0f
    val rows = content.sections.mapIndexed { index, section ->
        val sectionSize = if (section.attribution) lyricFooterAttributionFontSize(size) else size
        val lineHeight = lyricFooterLineHeight(section.attribution)
        val text = measurer.measure(AnnotatedString(section.text),
            typography.textStyle(sectionSize).copy(lineHeight = (sectionSize * lineHeight).sp),
            constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
        if (index == 0) emPx = (text.getLineBottom(0) - text.getLineTop(0)) / lineHeight
        top += emPx * lyricFooterGapEm(index, section.attribution)
        LyricFooterRow(text, top, if (section.attribution) FOOTER_ATTRIBUTION_OPACITY else 1f)
            .also { top += text.size.height }
    }
    return LyricFooterLayout(rows, top + emPx * .8f, emPx * .4f)
}
