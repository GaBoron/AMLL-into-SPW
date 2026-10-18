package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import io.github.gaboron.amllspw.lyrics.LyricLine

/** Display-only breaks retain a UTF-16 mapping back to the original timed words. */
internal data class WrappedLyricText(val text: String, val offsets: IntArray)

internal fun wrapLyricLine(line: LyricLine, width: Int, style: TextStyle, measurer: TextMeasurer): WrappedLyricText {
    val text = line.text
    val breaks = mutableSetOf<Int>()
    var start = 0
    // Explicit TTML line breaks remain hard boundaries.
    while (start < text.length) {
        val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
        val paragraph = text.substring(start, end)
        if (paragraph.isNotBlank()) {
            val unwrapped = measurer.measure(AnnotatedString(paragraph), style, softWrap = false)
            if (unwrapped.multiParagraph.maxIntrinsicWidth > width) {
                val segments = wordSegments(paragraph, unwrapped)
                val parts = if (line.hasWordTiming) dynamicParts(line, start, end) else segments
                val tokens = parts.map { range ->
                    val value = paragraph.substring(range.first, range.last + 1)
                    BreakToken(value, measurer.measure(AnnotatedString(value), style, softWrap = false)
                        .multiParagraph.maxIntrinsicWidth.toDouble())
                }
                // Match the upstream calibration of non-dynamic text to its shaped full width.
                val total = tokens.sumOf { it.width }
                val calibrated = if (!line.hasWordTiming && total > 0) tokens.map {
                    it.copy(width = it.width * unwrapped.multiParagraph.maxIntrinsicWidth / total)
                } else tokens
                val boundaries = segments.filter { range ->
                    paragraph.substring(range).codePoints().anyMatch { isCjk(it) && Character.isLetterOrDigit(it) }
                }.mapTo(mutableSetOf()) { it.first }
                val prefix = IntArray(tokens.size + 1)
                tokens.forEachIndexed { i, token -> prefix[i + 1] = prefix[i] + token.text.length }
                BalancedLineBreaks.calculate(calibrated, width.toDouble(), boundaries).forEach {
                    // Retain separators on the preceding display line without removing source
                    // characters or changing their word/timing mapping.
                    var boundary = prefix[it]
                    while (boundary < paragraph.length && paragraph[boundary].isWhitespace()) boundary++
                    if (boundary < paragraph.length) breaks += start + boundary
                }
            }
        }
        start = end + 1
    }
    val offsets = IntArray(text.length)
    val display = buildString {
        text.forEachIndexed { index, char ->
            if (index in breaks) append('\n')
            offsets[index] = length
            append(char)
        }
    }
    return WrappedLyricText(display, offsets)
}

/** Skia's ICU word boundaries provide the native equivalent of Intl.Segmenter. */
private fun wordSegments(text: String, layout: TextLayoutResult): List<IntRange> = buildList {
    var offset = 0
    while (offset < text.length) {
        val end = layout.getWordBoundary(offset).end.coerceIn(offset + 1, text.length)
        add(offset until end)
        offset = end
    }
}

/** Keep Latin syllables together, split CJK into characters, and keep Ruby bases indivisible. */
private fun dynamicParts(line: LyricLine, start: Int, end: Int): List<IntRange> {
    val parts = mutableListOf<IntRange>()
    var groupStart = -1
    var groupEnd = 0
    fun flush() {
        if (groupStart >= 0) parts += (groupStart - start) until (groupEnd - start)
        groupStart = -1
    }
    var offset = 0
    line.words.forEach { word ->
        val from = maxOf(offset, start)
        val to = minOf(offset + word.text.length, end)
        if (from < to) {
            val value = word.text.substring(from - offset, to - offset)
            val atoms = if (word.ruby.isNotEmpty()) Regex("^\\s+|\\S(?:[\\s\\S]*\\S)?|\\s+$") else Regex("\\s+|\\S+")
            atoms.findAll(value).forEach { match ->
                val atomStart = from + match.range.first
                val atomEnd = from + match.range.last + 1
                when {
                    match.value.isBlank() -> { flush(); parts += (atomStart - start) until (atomEnd - start) }
                    word.ruby.isNotEmpty() -> { flush(); parts += (atomStart - start) until (atomEnd - start) }
                    match.value.codePoints().allMatch(::isCjk) -> {
                        flush()
                        Regex("\\X").findAll(match.value).forEach { char ->
                            parts += (atomStart + char.range.first - start)..(atomStart + char.range.last - start)
                        }
                    }
                    else -> { if (groupStart < 0) groupStart = atomStart; groupEnd = atomEnd }
                }
            }
        }
        offset += word.text.length
    }
    flush()
    return parts
}

// Upstream includes this broad BMP range in addition to Unified_Ideograph.
private fun isCjk(codePoint: Int): Boolean = codePoint in 0x0800..0x9ffc ||
    Character.isIdeographic(codePoint)
