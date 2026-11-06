package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.LyricSource
import io.github.gaboron.amllspw.lyrics.TtmlReader

/** One current-song slot, never a cross-song cache. Parsing happens outside its lock. */
internal class TtmlInbox(private val parse: (String) -> LyricDocument? = TtmlReader()::read) {
    data class Entry(val track: TrackIdentity, val document: LyricDocument?, val offsetMs: Long, val selected: Boolean = false)
    private var entry: Entry? = null
    private var generation = 0L

    fun submit(track: TrackIdentity, ttml: String, offsetMs: Long, current: () -> TrackIdentity?): Boolean =
        accept(track, ttml, offsetMs, current, selected = false)

    /** A selected empty payload explicitly follows the host, suppressing unrelated local TTML. */
    fun select(track: TrackIdentity, ttml: String, offsetMs: Long, current: () -> TrackIdentity?): Boolean =
        accept(track, ttml, offsetMs, current, selected = true)

    private fun accept(track: TrackIdentity, ttml: String, offsetMs: Long, current: () -> TrackIdentity?, selected: Boolean): Boolean {
        val observed = synchronized(this) { generation }
        if (offsetMs !in -86_400_000L..86_400_000L || ttml.length > TtmlReader.MAX_CHARS) return false
        if (current()?.matches(track) != true) return false
        val document = if (selected && ttml.isEmpty()) null else (parse(ttml) ?: return false)
        // A provider may complete while the user changes songs, including during XML parsing.
        if (current()?.matches(track) != true) return false
        synchronized(this) {
            if (generation != observed) return false
            entry = Entry(track, document?.copy(source = LyricSource.PLUGIN_TTML), if (document == null) 0 else offsetMs, selected)
        }
        return true
    }

    @Synchronized fun current(track: TrackIdentity): Entry? {
        if (entry != null && entry?.track?.matches(track) != true) { entry = null; generation++ }
        return entry
    }
    @Synchronized fun clear(track: TrackIdentity): Boolean {
        // Also invalidate a submission parsing outside the lock when no previous entry exists.
        generation++
        if (entry?.track?.matches(track) != true) return false
        entry = null
        return true
    }
    @Synchronized fun close() { entry = null; generation++ }
}
