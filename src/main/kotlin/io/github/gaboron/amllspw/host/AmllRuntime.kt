package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PluginPermission
import io.github.gaboron.amllspw.lyrics.*
import io.github.gaboron.amllspw.timeline.PlaybackClock
import io.github.gaboron.amllspw.interop.TrackIdentity
import io.github.gaboron.amllspw.interop.TtmlInbox
import io.github.gaboron.amllspw.ui.LyricsPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serial ownership of track selection and lyric IO; UI reads immutable presentations. */
internal class AmllRuntime : AutoCloseable {
    val preferences = AmllPreferences()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "amll-lyrics-io").apply { isDaemon = true } }
    private val mutablePresentation = MutableStateFlow(LyricsPresentation())
    val presentation = mutablePresentation.asStateFlow()
    val clock = PlaybackClock()
    private val local = LocalLyricsReader()
    private val inbox = TtmlInbox()
    private var localDocument: LyricDocument? = null
    @Volatile private var closed = false
    val isActive: Boolean get() = !closed && runCatching {
        WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM) &&
            WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
    }.getOrDefault(false)
    private var key = ""
    private var initialized = false

    fun start() {
        // Some lyric-provider plugins short-circuit the before-load extension chain.
        // Poll track identity in the background so raw TTML still takes precedence in that case.
        worker.scheduleWithFixedDelay({
            if (!closed) runCatching {
                val media = WorkshopApi.playback.getCurrentMediaItem()
                if (media == null) clear() else select(media)
            }.onFailure { System.err.println("AMLL: 当前歌曲读取失败: ${it.message}") }
        }, 0, 1_000, TimeUnit.MILLISECONDS)
    }

    fun trackChanged(media: PlaybackExtensionPoint.MediaItem) = enqueue {
        val current = WorkshopApi.playback.getCurrentMediaItem() ?: return@enqueue clear()
        select(current, reload = current.id == media.id && current.path == media.path)
    }
    fun lyricsChanged() = enqueue {
        // Resolve current identity before associating an unkeyed host lyric callback with a song.
        val media = WorkshopApi.playback.getCurrentMediaItem() ?: return@enqueue clear()
        select(media)
        refreshDocument(media, hostUpdated = true)
    }

    fun positionChanged(position: Long, seek: Boolean = false) {
        currentIdentity()?.let { clock.sync("${it.id}|${it.path}", position, seek) }
    }
    fun playingChanged(playing: Boolean) {
        currentIdentity()?.let { clock.playing("${it.id}|${it.path}", playing) }
    }
    fun seekTo(trackKey: String, position: Long) {
        val current = currentIdentity() ?: return
        if (trackKey.isEmpty() || trackKey != "${current.id}|${current.path}" || presentation.value.trackKey != trackKey) return
        WorkshopApi.playback.seekTo(position.coerceAtLeast(0))
    }

    fun receiveTtml(track: TrackIdentity, xml: String, offsetMs: Long): Boolean {
        if (!isActive) return false
        val accepted = inbox.submit(track, xml, offsetMs, ::currentIdentity)
        if (accepted) lyricsChanged()
        return accepted && !closed
    }
    fun clearTtml(track: TrackIdentity): Boolean {
        if (!isActive || currentIdentity()?.matches(track) != true) return false
        return inbox.clear(track).also { if (it) lyricsChanged() }
    }
    private fun currentIdentity(): TrackIdentity? = runCatching {
        if (closed) null else WorkshopApi.playback.getCurrentMediaItem()?.let { TrackIdentity(it.id, it.path) }
    }.getOrNull()

    private fun select(media: PlaybackExtensionPoint.MediaItem, reload: Boolean = false) {
        val next = "${media.id}|${media.path}"
        if (initialized && next == key && !reload) return
        initialized = true
        key = next
        val previous = mutablePresentation.value
        mutablePresentation.value = LyricsPresentation(trackKey = next, palette = previous.palette, loading = true)
        localDocument = runCatching { local.readTtml(Path.of(media.path)) }.getOrNull()
        if (closed) return
        refreshDocument(media)
        CoverPaletteReader.read(media.id)?.let { colors ->
            if (!closed && currentIdentity()?.matches(TrackIdentity(media.id, media.path)) == true)
                mutablePresentation.value = mutablePresentation.value.copy(palette = colors.palette, lyricColor = colors.lyric)
        }
    }

    private fun refreshDocument(media: PlaybackExtensionPoint.MediaItem, hostUpdated: Boolean = false) {
        if (currentIdentity()?.matches(TrackIdentity(media.id, media.path)) != true) {
            clear(); return
        }
        val received = inbox.current(TrackIdentity(media.id, media.path))
        val document = localDocument ?: received?.document ?: HostLyricsAdapter.convert(WorkshopApi.playback.getLyricsLines())
        val offset = if (localDocument != null) 0 else received?.offsetMs ?: 0
        if (!closed) mutablePresentation.value = mutablePresentation.value.copy(document = document, offsetMs = offset,
            loading = document.lines.isEmpty() && mutablePresentation.value.loading && !hostUpdated)
    }
    private fun clear() {
        key = ""; initialized = false; localDocument = null; inbox.close()
        if (currentIdentity() == null) clock.clear()
        if (!closed) mutablePresentation.value = LyricsPresentation()
    }
    private fun enqueue(action: () -> Unit) {
        if (closed) return
        runCatching { worker.execute { if (!closed) runCatching(action).onFailure { System.err.println("AMLL: ${it.message}") } } }
    }
    override fun close() { closed = true; inbox.close(); worker.shutdownNow(); preferences.close(); mutablePresentation.value = LyricsPresentation() }
}
