package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

internal data class LyricFooterLayout(val text: TextLayoutResult, val padding: Float) {
    val height: Float get() = text.size.height + padding * 2
}

/** At lyric size 48, match the former footer at lyric size 28 (19.6 sp); grow gently either side. */
internal fun lyricFooterFontSize(lyricSizeSp: Float): Float =
    maxOf(10f, 19.6f * sqrt(lyricSizeSp.coerceAtLeast(0f) / 48f))

internal fun layoutLyricFooter(content: LyricFooterContent, measurer: TextMeasurer, width: Int,
    fontSize: Float, typography: LyricTypography): LyricFooterLayout? {
    if (content.text.isBlank()) return null
    val size = lyricFooterFontSize(fontSize)
    val text = measurer.measure(AnnotatedString(content.text), typography.textStyle(size).copy(lineHeight = (size * 1.8f).sp),
        constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
    // Derive padding from measured pixels so it respects both display density and font scaling.
    val emPx = (text.getLineBottom(0) - text.getLineTop(0)) / 1.8f
    return LyricFooterLayout(text, emPx * .4f)
}
