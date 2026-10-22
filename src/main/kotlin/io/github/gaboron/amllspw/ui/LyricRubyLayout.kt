package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import io.github.gaboron.amllspw.lyrics.*
import io.github.gaboron.amllspw.timeline.AnnotationMotion
import io.github.gaboron.amllspw.timeline.AnnotationTimingPart

internal data class RubyRun(val syllable: LyricSyllable, val text: TextLayoutResult, val offset: Offset,
    val motion: AnnotationMotion, val maskLeft: Float, val maskRight: Float)
internal data class RubyLineText(val main: TextLayoutResult, val offsets: IntArray, val runs: List<RubyRun>, val emPx: Float)

/** Measures ruby once with the lyric layout, reserving horizontal and per-row vertical space. */
internal fun layoutRubyLine(line: LyricLine, annotations: List<LyricAnnotation>, width: Int,
    style: TextStyle, measurer: TextMeasurer): RubyLineText {
    val probe = measurer.measure(AnnotatedString("Ag"), style, softWrap = false)
    val density = probe.layoutInput.density
    val emPx = with(density) { style.fontSize.toPx() }
    val rubyStyle = style.copy(fontSize = (style.fontSize.value * .5f).sp,
        lineHeight = (style.fontSize.value * .55f).sp, textAlign = TextAlign.Left, letterSpacing = 0.sp)
    data class Atom(val start: Int, val end: Int, val ruby: LyricAnnotation?, val padding: Float, val width: Float)
    val starts = annotations.associateBy { it.start }
    val rubyLayouts = annotations.associateWith { annotation -> annotation.syllables.map {
        measurer.measure(AnnotatedString(it.text), rubyStyle, softWrap = false)
    } }
    val atoms = mutableListOf<Atom>()
    var cursor = 0
    val plainAtom = Regex("[A-Za-z0-9]+|\\X")
    while (cursor < line.text.length) {
        val annotation = starts[cursor]
        val end = annotation?.end ?: minOf(plainAtom.find(line.text, cursor)!!.range.last + 1,
            annotations.firstOrNull { it.start > cursor }?.start ?: line.text.length)
        val value = line.text.substring(cursor, end)
        val baseWidth = measurer.measure(AnnotatedString(value), style, softWrap = false).multiParagraph.maxIntrinsicWidth
        val rubyWidth = rubyLayouts[annotation]?.sumOf { it.size.width.toDouble() }?.toFloat() ?: 0f
        val padding = if (annotation != null) (maxOf(baseWidth, rubyWidth.coerceAtMost(width.toFloat())) - baseWidth) / 2 + emPx * .06f else 0f
        atoms += Atom(cursor, end, annotation, padding.coerceAtLeast(0f), baseWidth + padding.coerceAtLeast(0f) * 2)
        cursor = end
    }
    val breaks = mutableSetOf<Int>()
    var paragraph = 0
    for (end in 0..atoms.size) {
        if (end == atoms.size || line.text.substring(atoms[end].start, atoms[end].end) == "\n") {
            val part = atoms.subList(paragraph, end)
            val tokens = part.map { BreakToken(line.text.substring(it.start, it.end), it.width.toDouble()) }
            val boundaries = part.mapTo(mutableSetOf()) { it.start - (part.firstOrNull()?.start ?: 0) }
            BalancedLineBreaks.calculate(tokens, width.toDouble(), boundaries).forEach { breaks += part[it].start }
            paragraph = end + 1
        }
    }
    val placeholders = mutableListOf<AnnotatedString.Range<Placeholder>>()
    val offsets = IntArray(line.text.length)
    val display = AnnotatedString.Builder()
    fun pad(pixels: Float) {
        if (pixels <= 0f) return
        val start = display.length
        display.append('\uFFFC')
        placeholders += AnnotatedString.Range(Placeholder(with(density) { pixels.toSp() }, style.fontSize,
            PlaceholderVerticalAlign.TextCenter), start, start + 1)
    }
    for (atom in atoms) {
        if (atom.start in breaks) display.append('\n')
        pad(atom.padding)
        for (index in atom.start until atom.end) { offsets[index] = display.length; display.append(line.text[index]) }
        pad(atom.padding)
    }
    val rubyHeight = rubyLayouts.values.flatten().maxOfOrNull { it.size.height }?.toFloat() ?: 0f
    val main = measurer.measure(display.toAnnotatedString(), style.copy(
        lineHeight = with(density) { (probe.size.height + rubyHeight + emPx * .12f).toSp() },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None)),
        placeholders = placeholders, constraints = Constraints(minWidth = width, maxWidth = width))
    val wordOffsets = line.words.runningFold(0) { offset, word -> offset + word.text.length }
    fun textWidth(start: Int, end: Int): Float = (start until end).map {
        main.getBoundingBox(offsets[it])
    }.filter { it.width > 0 }.distinct().sumOf { it.width.toDouble() }.toFloat()
    val runs = annotations.flatMap { annotation ->
        val first = offsets[annotation.start]
        val row = main.getLineForOffset(first)
        val boxes = (annotation.start until annotation.end).filter { main.getLineForOffset(offsets[it]) == row }
            .map { main.getBoundingBox(offsets[it]) }.filter { it.width > 0 }
        if (boxes.isEmpty()) return@flatMap emptyList()
        val left = boxes.minOf { it.left }
        val right = boxes.maxOf { it.right }
        val original = rubyLayouts.getValue(annotation)
        val total = original.sumOf { it.size.width.toDouble() }.toFloat()
        // A very long authored base may wrap naturally. Keep its annotation within this row.
        val available = if (main.getLineForOffset(offsets[annotation.end - 1]) != row) right - left else
            (right - left + atoms.first { it.start == annotation.start }.padding * 2).coerceAtMost(width.toFloat())
        val shrink = minOf(1f, available / total.coerceAtLeast(1f))
        val measured = if (shrink < 1f) annotation.syllables.map {
            measurer.measure(AnnotatedString(it.text), rubyStyle.copy(fontSize = (rubyStyle.fontSize.value * shrink).sp), softWrap = false)
        } else original
        val totalWidth = measured.sumOf { it.size.width.toDouble() }.toFloat()
        var x = ((left + right - totalWidth) / 2).coerceIn(0f, (width - totalWidth).coerceAtLeast(0f))
        val maskLeft = x
        val maskRight = x + totalWidth
        val y = main.getLineBaseline(row) - probe.firstBaseline - measured.maxOf { it.size.height } - emPx * .04f
        val parts = line.words.mapIndexedNotNull { index, word ->
            val start = maxOf(wordOffsets[index], annotation.start)
            val end = minOf(wordOffsets[index + 1], annotation.end)
            if (start >= end) return@mapIndexedNotNull null
            val wordWidth = textWidth(wordOffsets[index], wordOffsets[index + 1]).coerceAtLeast(1f)
            val from = textWidth(wordOffsets[index], start) / wordWidth
            val to = from + textWidth(start, end) / wordWidth
            AnnotationTimingPart(word, from, to, textWidth(start, end))
        }
        val weight = parts.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
        val motion = AnnotationMotion(parts.map { it.copy(weight = it.weight / weight) }, line.background)
        annotation.syllables.mapIndexed { index, syllable ->
            RubyRun(syllable, measured[index], Offset(x, y), motion, maskLeft, maskRight).also { x += measured[index].size.width }
        }
    }
    return RubyLineText(main, offsets, runs, emPx)
}
