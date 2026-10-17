package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.lyrics.*
import io.github.gaboron.amllspw.timeline.WordAnimation

internal data class GlyphRegion(val bounds: Rect, val part: Int, val character: Int)
internal data class WordRegion(val word: LyricWord, val rects: List<Rect>, val rightToLeft: Boolean,
    val glyphs: List<GlyphRegion>, val characters: Int, val animation: WordAnimation.Prepared)
internal data class LineLayout(
    val line: LyricLine,
    val main: TextLayoutResult,
    val words: List<WordRegion>,
    val auxiliary: List<TextLayoutResult>,
    val height: Float,
    val rubyHeight: Float,
    val emPx: Float,
)
internal data class GroupLayout(val rows: List<LineLayout>, val top: Float, val height: Float)

internal fun layoutLyrics(document: LyricDocument, measurer: TextMeasurer, width: Int, fontSize: Float, showSecondary: Boolean, romanization: Boolean): List<GroupLayout> {
    var top = 0f
    return document.lines.groupBy { it.group }.values.sortedBy { rows -> rows.minOf { it.startMs } }.map { rows ->
        val layouts = rows.sortedBy { it.background }.map { line ->
            val size = fontSize * if (line.background) .7f else 1f
            val hasRuby = romanization && line.words.any { it.ruby.isNotEmpty() }
            val style = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif, lineHeight = (size * 1.24f).sp,
                textAlign = if (line.duet) TextAlign.Right else TextAlign.Left)
            val main = measurer.measure(AnnotatedString(line.text), style, constraints = Constraints(minWidth = width, maxWidth = width))
            val rubyHeight = if (hasRuby) (main.getLineBottom(0) - main.getLineTop(0)) * .48f else 0f
            var offset = 0
            val regions = line.words.mapIndexed { wordIndex, word ->
                val byRow = linkedMapOf<Int, Rect>()
                for (i in offset until (offset + word.text.length).coerceAtMost(line.text.length)) {
                    val rect = main.getBoundingBox(i)
                    if (rect.width > 0) {
                        val row = main.getLineForOffset(i)
                        byRow[row] = byRow[row]?.let { Rect(minOf(it.left, rect.left), minOf(it.top, rect.top), maxOf(it.right, rect.right), maxOf(it.bottom, rect.bottom)) } ?: rect
                    }
                }
                val rtl = offset < line.text.length && main.getBidiRunDirection(offset) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                var character = 0
                val glyphs = Regex("\\X").findAll(word.text).mapNotNull { cluster ->
                    if (cluster.value.isBlank()) return@mapNotNull null
                    val positions = cluster.range.map { main.getBoundingBox(offset + it) }.filter { it.width > 0 }
                    if (positions.isEmpty()) return@mapNotNull null
                    val box = positions.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                    val part = byRow.keys.indexOf(main.getLineForOffset(offset + cluster.range.first))
                    // Full line boxes preserve descenders, but rounded neighbouring metrics can
                    // overlap by a pixel. Give that shared strip to the preceding row only.
                    val row = main.getLineForOffset(offset + cluster.range.first)
                    val top = if (row == 0) main.getLineTop(row) else maxOf(main.getLineTop(row), main.getLineBottom(row - 1))
                    GlyphRegion(Rect(box.left, top, box.right, main.getLineBottom(row)), part, character++)
                }.distinctBy { it.bounds }.toList()
                offset += word.text.length
                WordRegion(word, byRow.values.toList(), rtl, glyphs, character,
                    WordAnimation.Prepared(word, line.background, wordIndex == line.words.lastIndex, character))
            }
            val secondary = if (showSecondary) listOf(line.translation, line.romanization.takeIf { romanization }.orEmpty()).filter(String::isNotBlank).map {
                measurer.measure(AnnotatedString(it), style.copy(fontSize = (size * .48f).sp, lineHeight = (size * .68f).sp,
                    fontWeight = FontWeight.Medium), constraints = Constraints(minWidth = width, maxWidth = width))
            } else emptyList()
            LineLayout(line, main, regions, secondary, main.size.height + rubyHeight + secondary.sumOf { it.size.height + 5 } + 10f, rubyHeight,
                (main.getLineBottom(0) - main.getLineTop(0)) / 1.24f)
        }
        val h = layouts.sumOf { it.height.toDouble() }.toFloat() + fontSize * .65f
        GroupLayout(layouts, top, h).also { top += h }
    }
}
