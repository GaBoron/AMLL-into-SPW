package io.github.gaboron.amllspw.lyrics

import java.nio.file.Files
import java.nio.file.Path
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT

/** TTML candidates are exhausted before considering any converted/native lyrics. */
class LocalLyricsReader(
    private val ttml: TtmlReader = TtmlReader(),
    private val embedded: (Path) -> List<String> = ::embeddedLyrics,
    private val sidecar: (Path, String) -> String? = ::readSidecar,
) {
    fun readTtml(audio: Path): LyricDocument? {
        sidecar(audio, "ttml")?.let { ttml.read(it)?.let { document -> return document.copy(source = LyricSource.TTML_FILE) } }
        // Inspect every lyric tag; an ordinary LRC/USLT tag must not hide a later TTML tag.
        runCatching { embedded(audio) }.getOrDefault(emptyList()).forEach {
            ttml.read(it)?.let { document -> return document.copy(source = LyricSource.EMBEDDED_TTML) }
        }
        // Some libraries store raw TTML in a same-name .lrc file.
        sidecar(audio, "lrc")?.let { return ttml.read(it)?.copy(source = LyricSource.TTML_IN_LRC) }
        return null
    }

}

private fun readSidecar(audio: Path, extension: String): String? = runCatching {
        val stem = audio.fileName.toString().substringBeforeLast('.', audio.fileName.toString())
        val path = audio.resolveSibling("$stem.$extension")
        if (!Files.isRegularFile(path) || Files.size(path) > TtmlReader.MAX_CHARS) null else Files.readString(path)
}.getOrNull()

private fun embeddedLyrics(audio: Path): List<String> = runCatching {
    val tag = AudioFileIO.read(audio.toFile()).tag ?: return emptyList()
    buildList {
        addAll(runCatching { tag.getAll(FieldKey.LYRICS) }.getOrDefault(emptyList()))
        val fields = tag.fields
        while (fields.hasNext()) {
            val field = fields.next()
            val body = (field as? AbstractID3v2Frame)?.body
            val text = when {
                body is FrameBodyUSLT -> body.lyric
                body is FrameBodyTXXX && body.description.uppercase() in LYRIC_TAGS -> body.text
                field is TagTextField && field.id.uppercase() in LYRIC_TAGS -> field.content
                else -> null
            }
            if (!text.isNullOrBlank() && text.length <= TtmlReader.MAX_CHARS) add(text)
        }
    }.filter { it.length <= TtmlReader.MAX_CHARS }.distinct()
}.getOrDefault(emptyList())

private val LYRIC_TAGS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "TTML", "TTMLLYRIC", "TTML_LYRICS")
