package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine as HostLine
import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.LyricLine
import io.github.gaboron.amllspw.lyrics.LyricWord

/** Uses the host's SPL/LRC decoder; only information actually present is carried into TTML. */
internal object HostLyricsAdapter {
    fun convert(source: List<HostLine>): LyricDocument = LyricDocument(source.mapIndexedNotNull { index, line ->
        if (line.pureMainText.isBlank()) return@mapIndexedNotNull null
        val start = line.startTime.coerceAtLeast(0)
        val end = line.endTime.takeIf { it > start }
            ?: source.drop(index + 1).firstOrNull { it.startTime > start }?.startTime ?: (start + 5_000)
        val cells = line.lyricsCells.filter { it.text.isNotEmpty() && it.startTime >= start && it.endTime > it.startTime }
        val words = if (cells.isNotEmpty() && cells.joinToString("") { it.text } == line.pureMainText)
            cells.map { LyricWord(it.text, it.startTime, it.endTime) }
        else listOf(LyricWord(line.pureMainText, start, end, timed = false))
        LyricLine(start, end, words, translation = line.pureSubText.orEmpty(), group = index)
    }.sortedBy { it.startMs }, LyricDocument.Origin.HOST)
}
