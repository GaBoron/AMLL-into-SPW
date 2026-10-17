package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.TtmlReader

/** One current-song slot, never a cross-song cache. Parsing happens outside its lock. */
internal class TtmlInbox(private val parse: (String) -> LyricDocument? = TtmlReader()::read) {
    data class Entry(val track: TrackIdentity, val document: LyricDocument, val offsetMs: Long)
    private var entry: Entry? = null
    private var generation = 0L

    fun submit(track: TrackIdentity, ttml: String, offsetMs: Long, current: () -> TrackIdentity?): Boolean {
        val observed = synchronized(this) { generation }
        if (offsetMs !in -86_400_000L..86_400_000L || ttml.length > TtmlReader.MAX_CHARS) return false
        if (current()?.matches(track) != true) return false
        val document = parse(ttml) ?: return false
        // A provider may complete while the user changes songs, including during XML parsing.
        if (current()?.matches(track) != true) return false
        synchronized(this) {
            if (generation != observed) return false
            entry = Entry(track, document, offsetMs)
        }
        return true
    }

    @Synchronized fun current(track: TrackIdentity): Entry? {
        if (entry != null && entry?.track?.matches(track) != true) { entry = null; generation++ }
        return entry
    }
    @Synchronized fun clear(track: TrackIdentity): Boolean {
        if (entry?.track?.matches(track) != true) return false
        entry = null; generation++
        return true
    }
    @Synchronized fun close() { entry = null; generation++ }
}
