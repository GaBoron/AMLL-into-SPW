package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.TtmlWriter
import io.github.gaboron.amllspw.lyrics.InstrumentalLyrics
import io.github.gaboron.amllspw.poetry.Poem
import io.github.gaboron.amllspw.footer.FooterContribution
import androidx.compose.ui.graphics.ImageBitmap

internal data class LyricsPresentation(
    val trackKey: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: ImageBitmap? = null,
    val backgroundArtwork: ImageBitmap? = null,
    val artworkLoading: Boolean = false,
    val externalPlayback: Boolean = false,
    val transportAvailable: Boolean = true,
    val document: LyricDocument = LyricDocument.EMPTY,
    val offsetMs: Long = 0,
    val palette: List<Int> = listOf(0xff233149.toInt(), 0xff494060.toInt(), 0xff513b47.toInt(), 0xff344a50.toInt()),
    val lyricColor: Int = 0xffffffff.toInt(),
    val lyricGlowColor: Int = lyricColor,
    val loading: Boolean = false,
    val poem: Poem? = null,
    val footer: List<FooterContribution> = emptyList(),
) {
    val ttml: String = TtmlWriter.write(document)
    val isInstrumental: Boolean get() = trackKey.isNotEmpty() && !loading && InstrumentalLyrics.matches(document)
}
