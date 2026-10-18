package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine as HostLine
import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.LyricLine
import io.github.gaboron.amllspw.lyrics.LyricWord

/** Uses the host's SPL/LRC decoder; only information actually present is carried into TTML. */
internal object HostLyricsAdapter {
    fun convert(source: List<HostLine>): LyricDocument {
        val sourceCells = source.map { line ->
            // Instant separators/punctuation are still source text. Dropping them would
            // discard every valid word timestamp in an otherwise timed line.
            line.lyricsCells.filter {
                it.text.isNotEmpty() && it.startTime >= line.startTime.coerceAtLeast(0) && it.endTime >= it.startTime
            }.takeIf { line.pureMainText.isNotBlank() && it.joinToString("") { cell -> cell.text } == line.pureMainText }
                .orEmpty()
        }
        // SPW also represents ordinary LRC as one cell per row. Use the whole song to
        // distinguish it from word-timed lyrics, where a row can contain one syllable.
        val hasWordTiming = sourceCells.any { cells -> cells.count { it.text.isNotBlank() } > 1 }
        return LyricDocument(source.mapIndexedNotNull { index, line ->
            if (line.pureMainText.isBlank()) return@mapIndexedNotNull null
            val start = line.startTime.coerceAtLeast(0)
            val end = line.endTime.takeIf { it > start }
                ?: source.drop(index + 1).firstOrNull { it.startTime > start }?.startTime ?: (start + 5_000)
            val cells = sourceCells[index]
            val words = if (hasWordTiming && cells.isNotEmpty())
                cells.map { LyricWord(it.text, it.startTime, it.endTime) }
            else listOf(LyricWord(line.pureMainText, start, end, timed = false))
            LyricLine(start, end, words, translation = line.pureSubText.orEmpty(), group = index)
        }.sortedBy { it.startMs }, LyricDocument.Origin.HOST)
    }
}
