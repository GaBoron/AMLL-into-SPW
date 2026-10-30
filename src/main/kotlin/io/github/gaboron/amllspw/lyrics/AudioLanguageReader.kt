package io.github.gaboron.amllspw.lyrics

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Reads the same LANGUAGE tag as the SPW Lyrics metadata editor, without writing audio files. */
internal class AudioLanguageReader {
    private data class Revision(val path: Path, val modified: FileTime, val size: Long, val fileKey: Any?)
    private var revision: Revision? = null
    private var languages: List<String> = emptyList()

    fun read(audio: Path): List<String> {
        val path = audio.toAbsolutePath().normalize()
        val next = runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            Revision(path, attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
        }.getOrNull()
        if (next == null) {
            revision = null
            languages = emptyList()
        } else if (next != revision) {
            val loaded = runCatching {
                AudioFileIO.read(path.toFile()).tag?.getAll(FieldKey.LANGUAGE).orEmpty()
                    .map(String::trim).filter(String::isNotBlank)
            }
            if (loaded.isSuccess) {
                languages = loaded.getOrThrow()
                revision = next
            } else if (revision?.path != path) {
                languages = emptyList()
                revision = null
            }
            // Preserve this track's last value during a save lock; the old revision forces a retry.
        }
        return languages
    }
}
