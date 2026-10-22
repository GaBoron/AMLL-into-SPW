package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.lyrics.*
import io.github.gaboron.amllspw.timeline.WordAnimation
import kotlin.math.roundToInt

internal data class GlyphRegion(val bounds: Rect, val part: Int, val character: Int)
internal data class WordRegion(val word: LyricWord, val rects: List<Rect>, val rightToLeft: Boolean,
    val glyphs: List<GlyphRegion>, val characters: Int, val animation: WordAnimation.Prepared) {
    val partOffsets = FloatArray(rects.size)
    val totalWidth: Float
    init {
        var width = 0f
        rects.forEachIndexed { index, rect -> partOffsets[index] = width; width += rect.width }
        totalWidth = width
    }
}
internal data class LineLayout(
    val line: LyricLine,
    val main: TextLayoutResult,
    val words: List<WordRegion>,
    val auxiliary: List<TextLayoutResult>,
    val height: Float,
    val rubyHeight: Float,
    val emPx: Float,
    val inset: Float = 0f,
    val rubyRuns: List<RubyRun> = emptyList(),
    val upperRomanization: TextLayoutResult? = null,
) {
    // Include word float/glow and secondary text, while avoiding a viewport-sized layer per row.
    val layerBounds = org.jetbrains.skia.Rect.makeLTRB(-emPx * 1.5f, -emPx * 1.5f,
        main.size.width + emPx * 1.5f, height + emPx * 1.5f)
}
internal data class GroupLayout(val rows: List<LineLayout>, val top: Float, val height: Float)

internal fun layoutLyrics(document: LyricDocument, measurer: TextMeasurer, width: Int, fontSize: Float, showSecondary: Boolean, romanization: Boolean,
    typography: LyricTypography = LyricTypography()): List<GroupLayout> {
    var top = 0f
    val hasDuet = document.lines.any { it.duet }
    val rowWidth = (width * if (hasDuet) .85f else 1f).roundToInt().coerceAtLeast(1)
    return document.lines.groupBy { it.group }.values.sortedBy { rows -> rows.minOf { it.startMs } }.map { rows ->
        val layouts = rows.sortedBy { it.background }.map { line ->
            val size = fontSize * if (line.background) .7f else 1f
            val annotations = if (romanization) line.rubyAnnotations() else emptyList()
            val style = typography.textStyle(size).copy(lineHeight = (size * 1.24f).sp,
                textAlign = if (line.duet) TextAlign.Right else TextAlign.Left)
            val text = line.text
            val ruby = if (annotations.isEmpty()) null else layoutRubyLine(line, annotations, rowWidth, style, measurer)
            val wrapped = if (ruby == null) wrapLyricLine(line, rowWidth, style, measurer) else
                WrappedLyricText(ruby.main.layoutInput.text.text, ruby.offsets)
            val constraints = Constraints(minWidth = rowWidth, maxWidth = rowWidth)
            val main = ruby?.main ?: measurer.measure(AnnotatedString(wrapped.text), style, constraints = constraints)
            var offset = 0
            val regions = line.words.mapIndexed { wordIndex, word ->
                val byRow = linkedMapOf<Int, Rect>()
                for (sourceOffset in offset until (offset + word.text.length).coerceAtMost(text.length)) {
                    val i = wrapped.offsets[sourceOffset]
                    val rect = main.getBoundingBox(i)
                    if (rect.width > 0) {
                        val row = main.getLineForOffset(i)
                        byRow[row] = byRow[row]?.let { Rect(minOf(it.left, rect.left), minOf(it.top, rect.top), maxOf(it.right, rect.right), maxOf(it.bottom, rect.bottom)) } ?: rect
                    }
                }
                val rtl = offset < text.length && main.getBidiRunDirection(wrapped.offsets[offset]) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                var character = 0
                val glyphs = Regex("\\X").findAll(word.text).mapNotNull { cluster ->
                    if (cluster.value.isBlank()) return@mapNotNull null
                    val positions = cluster.range.map { main.getBoundingBox(wrapped.offsets[offset + it]) }.filter { it.width > 0 }
                    if (positions.isEmpty()) return@mapNotNull null
                    val box = positions.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                    val displayOffset = wrapped.offsets[offset + cluster.range.first]
                    val part = byRow.keys.indexOf(main.getLineForOffset(displayOffset))
                    // Full line boxes preserve descenders, but rounded neighbouring metrics can
                    // overlap by a pixel. Give that shared strip to the preceding row only.
                    val row = main.getLineForOffset(displayOffset)
                    val top = if (row == 0) main.getLineTop(row) else maxOf(main.getLineTop(row), main.getLineBottom(row - 1))
                    GlyphRegion(Rect(box.left, top, box.right, main.getLineBottom(row)), part, character++)
                }.distinctBy { it.bounds }.toList()
                offset += word.text.length
                WordRegion(word, byRow.values.toList(), rtl, glyphs, character,
                    WordAnimation.Prepared(word, line.background, wordIndex == line.words.lastIndex, character))
            }
            val romanLine = line.romanization.takeIf { romanization && (line.annotations.isEmpty() ||
                line.romanizationSyllables.any { syllable -> line.annotations.none { syllable in it.syllables } }) }.orEmpty()
            val auxiliaryStyle = style.copy(fontSize = (size * .48f).sp, lineHeight = (size * .68f).sp)
            val upper = if (line.romanizationAbove && romanLine.isNotBlank())
                measurer.measure(AnnotatedString(romanLine), auxiliaryStyle, constraints = constraints) else null
            val rubyHeight = upper?.let { it.size.height + 5f } ?: 0f
            val secondary = if (showSecondary) listOf(line.translation, romanLine.takeUnless { line.romanizationAbove }.orEmpty()).filter(String::isNotBlank).map {
                measurer.measure(AnnotatedString(it), auxiliaryStyle, constraints = constraints)
            } else emptyList()
            LineLayout(line, main, regions, secondary, main.size.height + rubyHeight + secondary.sumOf { it.size.height + 5 } + 10f, rubyHeight,
                ruby?.emPx ?: ((main.getLineBottom(0) - main.getLineTop(0)) / 1.24f),
                if (line.duet) (width - rowWidth).toFloat() else 0f, ruby?.runs.orEmpty(), upper)
        }
        val h = layouts.sumOf { it.height.toDouble() }.toFloat() + fontSize * .65f
        GroupLayout(layouts, top, h).also { top += h }
    }
}
