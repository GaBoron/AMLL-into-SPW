package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.TtmlWriter
import io.github.gaboron.amllspw.lyrics.InstrumentalLyrics
import io.github.gaboron.amllspw.poetry.Poem
import io.github.gaboron.amllspw.footer.FooterContribution

internal data class LyricsPresentation(
    val trackKey: String = "",
    val document: LyricDocument = LyricDocument.EMPTY,
    val offsetMs: Long = 0,
    val palette: List<Int> = listOf(0xff233149.toInt(), 0xff494060.toInt(), 0xff513b47.toInt(), 0xff344a50.toInt()),
    val lyricColor: Int = 0xffffffff.toInt(),
    val loading: Boolean = false,
    val poem: Poem? = null,
    val footer: List<FooterContribution> = emptyList(),
) {
    val ttml: String = TtmlWriter.write(document)
    val isInstrumental: Boolean get() = trackKey.isNotEmpty() && !loading && InstrumentalLyrics.matches(document)
}
