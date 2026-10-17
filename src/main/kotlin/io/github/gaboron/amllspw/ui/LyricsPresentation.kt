package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.TtmlWriter

internal data class LyricsPresentation(
    val trackKey: String = "",
    val document: LyricDocument = LyricDocument.EMPTY,
    val offsetMs: Long = 0,
    val palette: List<Int> = listOf(0xff233149.toInt(), 0xff494060.toInt(), 0xff513b47.toInt(), 0xff344a50.toInt()),
) {
    val ttml: String = TtmlWriter.write(document)
}
