package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PluginPermission
import io.github.gaboron.amllspw.lyrics.*
import io.github.gaboron.amllspw.timeline.PlaybackClock
import io.github.gaboron.amllspw.interop.TrackIdentity
import io.github.gaboron.amllspw.interop.localMediaPath
import io.github.gaboron.amllspw.interop.TtmlInbox
import io.github.gaboron.amllspw.interop.FooterInbox
import io.github.gaboron.amllspw.interop.PlaybackIdentity
import io.github.gaboron.amllspw.ui.LyricsPresentation
import io.github.gaboron.amllspw.poetry.PoetryCatalog
import io.github.gaboron.amllspw.poetry.TrackPoems
import io.github.gaboron.amllspw.romanization.JapaneseAnnotations
import io.github.gaboron.amllspw.romanization.JapaneseReadings
import io.github.gaboron.amllspw.romanization.CantoneseAnnotations
import io.github.gaboron.amllspw.romanization.CantoneseReadings
import io.github.gaboron.amllspw.romanization.KoreanAnnotations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serial ownership of track selection and lyric IO; UI reads immutable presentations. */
internal class AmllRuntime : AutoCloseable {
    val preferences = AmllPreferences()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "amll-lyrics-io").apply { isDaemon = true } }
    private val mutablePresentation = MutableStateFlow(LyricsPresentation())
    val presentation = mutablePresentation.asStateFlow()
    val clock = PlaybackClock()
    private val external = ExternalPlaybackClient()
    @Volatile private var externalTrack: ExternalPlayback? = null
    private var externalLyricsRevision = -1L
    private val local = LocalLyricsReader()
    private val externalArtwork = ExternalArtworkLoader()
    private val covers = CoverPaletteReader { id ->
        val track = externalTrack?.takeIf { it.artworkKey == id }
        if (track != null) externalArtwork.load(track.artworkKey, track.coverUrl)
        else WorkshopApi.library.getCoverById(id)
    }
    private val readings = JapaneseReadings()
    private val cantoneseReadings = CantoneseReadings()
    private val hostLyrics = HostLyricsAdapter(HostSecondaryLyrics(readings, cantoneseReadings))
    private val annotations = JapaneseAnnotations(readings)
    private val cantoneseAnnotations = CantoneseAnnotations(cantoneseReadings)
    private val koreanAnnotations = KoreanAnnotations()
    private val languageReader = AudioLanguageReader()
    private var audioLanguages: List<String> = emptyList()
    private val inbox = TtmlInbox()
    private val footerInbox = FooterInbox(::currentFooterIdentity)
    val footerReceiver = FooterReceiver(footerInbox, { isActive }, { enqueue(::refreshFooter) })
    private val poems by lazy { TrackPoems(PoetryCatalog.load()) }
    private var localDocument: LyricDocument? = null
    @Volatile private var closed = false
    val isActive: Boolean get() = !closed && runCatching {
        WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM) &&
            WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
    }.getOrDefault(false)
    private var key = ""
    private var initialized = false
    private var selectedMedia: PlaybackExtensionPoint.MediaItem? = null

    fun start() {
        preferences.onAnnotationChange = ::lyricsChanged
        // Some lyric-provider plugins short-circuit the before-load extension chain.
        // Poll track identity in the background so raw TTML still takes precedence in that case.
        worker.scheduleWithFixedDelay({
            if (!closed) runCatching {
                val previous = externalTrack
                val next = external.snapshot()
                externalTrack = next
                if (next == null && previous != null) externalArtwork.cancel()
                val media = currentMediaItem()
                if (media == null) clear() else {
                    val changed = next?.identity != previous?.identity
                    val metadataChanged = next != null && previous != null &&
                        (next.title != previous.title || next.artist != previous.artist || next.album != previous.album ||
                            next.durationMs != previous.durationMs || next.coverUrl != previous.coverUrl)
                    select(media, reload = changed || metadataChanged)
                    if (next != null) {
                        val trackKey = mediaKey(media)
                        clock.sync(trackKey, next.currentPosition(), knownRate = next.playbackSpeed)
                        if (changed || previous?.playing != next.playing) clock.playing(trackKey, next.playing)
                        val lyricsRevision = external.lyrics(next)?.revision ?: -1L
                        if (changed || lyricsRevision != externalLyricsRevision) {
                            externalLyricsRevision = lyricsRevision
                            refreshDocument(media, hostUpdated = true)
                        }
                    }
                }
            }.onFailure { System.err.println("AMLL: 当前歌曲读取失败: ${it.message}") }
        }, 0, 1_000, TimeUnit.MILLISECONDS)
    }

    fun trackChanged(media: PlaybackExtensionPoint.MediaItem) {
        if (closed || external.snapshot() != null) return
        // Begin the artwork request at the before-load notification, ahead of queued lyric IO.
        covers.load(media.id)
        enqueue {
            val current = currentMediaItem() ?: return@enqueue clear()
            select(current, reload = current.id == media.id && current.path == media.path)
        }
    }
    fun lyricsChanged() = enqueue {
        // Resolve current identity before associating an unkeyed host lyric callback with a song.
        val media = currentMediaItem() ?: return@enqueue clear()
        select(media)
        refreshDocument(media, hostUpdated = true)
    }

    fun positionChanged(position: Long, seek: Boolean = false) = enqueue {
        if (externalTrack == null && external.snapshot() == null)
            currentIdentity()?.let { clock.sync("${it.id}|${it.path}", position, seek) }
    }
    fun playingChanged(playing: Boolean) = enqueue {
        if (externalTrack == null && external.snapshot() == null)
            currentIdentity()?.let { clock.playing("${it.id}|${it.path}", playing) }
    }
    fun seekTo(trackKey: String, position: Long) {
        val current = currentIdentity() ?: return
        if (trackKey.isEmpty() || trackKey != "${current.id}|${current.path}" || presentation.value.trackKey != trackKey) return
        val target = position.coerceAtLeast(0)
        clock.expectSeek(trackKey, target)
        try {
            val track = externalTrack
            if (track == null) WorkshopApi.playback.seekTo(target)
            else check(external.seek(track, target)) { "外部音源未接受跳转，请确认当前歌曲与跳转支持。" }
        } catch (error: Throwable) {
            clock.cancelExpectedSeek(trackKey, target)
            throw error
        }
    }

    fun nextPoem(trackKey: String) = enqueue {
        val current = mutablePresentation.value
        if (trackKey != current.trackKey || !current.isInstrumental ||
            currentIdentity()?.let { "${it.id}|${it.path}" } != trackKey) return@enqueue
        mutablePresentation.value = current.copy(poem = poems.next(trackKey))
    }

    fun transport(trackKey: String, command: String) = enqueue {
        if (!isActive || presentation.value.trackKey != trackKey ||
            currentIdentity()?.let { "${it.id}|${it.path}" } != trackKey) return@enqueue
        val remote = externalTrack
        if (remote == null && external.snapshot() != null) return@enqueue
        if (remote != null) {
            check(external.transport(remote, command)) { "外部音源未接受播放操作，请在音源插件中操作。" }
        } else when (command) {
            "play" -> WorkshopApi.playback.play()
            "pause" -> WorkshopApi.playback.pause()
            "previous" -> WorkshopApi.playback.previous()
            "next" -> WorkshopApi.playback.next()
        }
    }

    fun externalVolume(trackKey: String): Float? = externalTrack?.takeIf {
        presentation.value.trackKey == trackKey
    }?.let { it.volumePercent / 100f }

    fun changeVolume(trackKey: String, value: Float, local: (Float) -> Unit) = enqueue {
        if (!isActive || presentation.value.trackKey != trackKey ||
            currentIdentity()?.let { "${it.id}|${it.path}" } != trackKey) return@enqueue
        val remote = externalTrack
        if (remote != null) check(external.volume(remote, (value.coerceIn(0f, 1f) * 100).toInt())) {
            "外部音源未接受音量操作"
        } else if (external.snapshot() == null) local(value)
    }

    fun localPlayerAction(trackKey: String, action: () -> Unit) = enqueue {
        if (isActive && presentation.value.trackKey == trackKey && externalTrack == null && external.snapshot() == null &&
            currentIdentity()?.let { "${it.id}|${it.path}" } == trackKey) action()
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

    fun selectLyrics(track: TrackIdentity, xml: String, offsetMs: Long): Boolean {
        if (!isActive) return false
        val accepted = inbox.select(track, xml, offsetMs, ::currentIdentity)
        if (accepted) lyricsChanged()
        return accepted && !closed
    }

    private fun refreshFooter() {
        val media = selectedMedia ?: return
        val current = mutablePresentation.value
        if (current.trackKey != mediaKey(media)) return
        val footer = footerInbox.current(footerIdentityFor(media))
        if (!closed && current.footer != footer) mutablePresentation.compareAndSet(current, current.copy(footer = footer))
    }

    private fun currentFooterIdentity(): PlaybackIdentity? = runCatching {
        if (closed) return null
        external.snapshot()?.identity ?: hostMediaItem()?.let { PlaybackIdentity.Host(hostIdentityFor(it)) }
    }.getOrNull()

    private fun footerIdentityFor(media: PlaybackExtensionPoint.MediaItem): PlaybackIdentity =
        externalTrack?.identity ?: PlaybackIdentity.Host(hostIdentityFor(media))

    private fun currentIdentity(): TrackIdentity? = runCatching {
        if (closed) null else currentMediaItem()?.let(::identityFor)
    }.getOrNull()

    private fun select(media: PlaybackExtensionPoint.MediaItem, reload: Boolean = false) {
        val next = mediaKey(media)
        val options = preferences.options.value
        val audio = if (externalTrack == null) localMediaPath(media.path) else null
        val languages = if (options.automaticCantoneseRuby) audio?.let(languageReader::read).orEmpty() else emptyList()
        val metadataChanged = selectedMedia?.let {
            it.title != media.title || it.artist != media.artist || it.album != media.album || it.duration != media.duration
        } == true
        if (initialized && next == key && !reload && !metadataChanged) {
            if (languages != audioLanguages) {
                audioLanguages = languages
                refreshDocument(media)
            } else refreshFooter()
            return
        }
        audioLanguages = languages
        initialized = true
        key = next
        selectedMedia = media
        val artworkKey = externalTrack?.artworkKey ?: media.id
        val artwork = covers.load(artworkKey)
        val ready = artwork.isDone
        val colors = if (ready) artwork.getNow(null) else null
        val previous = mutablePresentation.value
        mutablePresentation.value = LyricsPresentation(trackKey = next,
            title = media.title, artist = media.artist, album = media.album, durationMs = media.duration.coerceAtLeast(0),
            artwork = colors?.artwork, backgroundArtwork = colors?.backgroundArtwork,
            artworkLoading = !ready, externalPlayback = externalTrack != null,
            transportAvailable = externalTrack?.let(external::supportsTransport) ?: true,
            palette = colors?.palette ?: previous.palette,
            lyricColor = colors?.lyric ?: if (ready) 0xffffffff.toInt() else previous.lyricColor, loading = true,
            lyricGlowColor = colors?.glow ?: if (ready) 0xffffffff.toInt() else previous.lyricGlowColor,
            footer = footerInbox.current(footerIdentityFor(media)))
        artwork.thenAccept { loaded -> enqueue { applyCover(media, next, artworkKey, loaded) } }
        localDocument = audio?.let { runCatching { local.readTtml(it) }.getOrNull() }
        if (closed) return
        // A fast cover decode can finish while local lyrics are read; publish both together.
        if (artwork.isDone) applyCover(media, next, artworkKey, artwork.getNow(null))
        refreshDocument(media)
    }

    private fun applyCover(media: PlaybackExtensionPoint.MediaItem, trackKey: String, artworkKey: String,
        colors: CoverPaletteReader.Colors?) {
        val current = mutablePresentation.value
        if (!closed && current.trackKey == trackKey && key == trackKey && mediaKey(media) == trackKey &&
            (externalTrack?.artworkKey ?: media.id) == artworkKey &&
            currentIdentity()?.matches(identityFor(media)) == true)
            mutablePresentation.value = current.copy(palette = colors?.palette ?: current.palette,
                artwork = colors?.artwork, backgroundArtwork = colors?.backgroundArtwork, artworkLoading = false,
                lyricColor = colors?.lyric ?: 0xffffffff.toInt(),
                lyricGlowColor = colors?.glow ?: 0xffffffff.toInt())
    }

    private fun refreshDocument(media: PlaybackExtensionPoint.MediaItem, hostUpdated: Boolean = false) {
        if (currentIdentity()?.matches(identityFor(media)) != true) {
            clear(); return
        }
        val options = preferences.options.value
        val received = inbox.current(identityFor(media))
        val remoteLyrics = externalTrack?.let(external::lyrics)
        val native = if (received?.selected == true) received.document else localDocument ?: received?.document
        val original = if (externalTrack != null) externalDocument(remoteLyrics, media.duration)
            else native ?: hostLyrics.convert(WorkshopApi.playback.getLyricsLines(),
                cantonese = options.automaticCantoneseRuby && CantoneseAnnotations.isCantonese(audioLanguages))
        val voiced = AutomaticVocals.apply(original, options.automaticVocalRoles)
        // Visibility belongs to rendering: keep annotations attached while their old layout fades out.
        val annotated = if (options.automaticCantoneseRuby && CantoneseAnnotations.isCantonese(voiced, audioLanguages))
            cantoneseAnnotations.apply(voiced, true, audioLanguages)
        else annotations.apply(voiced, options.automaticJapaneseRuby)
        val document = koreanAnnotations.apply(annotated, options.automaticKoreanRuby)
        val offset = remoteLyrics?.offsetMs ?: if (native != null && native === received?.document) received.offsetMs else 0
        if (!closed && currentIdentity()?.matches(identityFor(media)) == true) {
            val next = mutablePresentation.value.copy(document = document, offsetMs = offset,
                footer = footerInbox.current(footerIdentityFor(media)),
                loading = document.lines.isEmpty() && mutablePresentation.value.loading && !hostUpdated)
            mutablePresentation.value = next.copy(poem = if (next.isInstrumental) poems.forTrack(key) else null)
        }
    }
    private fun currentMediaItem(): PlaybackExtensionPoint.MediaItem? = externalTrack?.let {
        PlaybackExtensionPoint.MediaItem(it.title, it.artist, it.album, "", "", id = it.uri, duration = it.durationMs)
    } ?: hostMediaItem()

    private fun hostMediaItem(): PlaybackExtensionPoint.MediaItem? = WorkshopApi.playback.getCurrentMediaItem()?.takeIf {
        it.id.isNotBlank() || it.path.isNotBlank() || it.title.isNotBlank() || it.artist.isNotBlank() || it.album.isNotBlank()
    }

    private fun identityFor(media: PlaybackExtensionPoint.MediaItem): TrackIdentity = externalTrack
        ?.takeIf { it.uri == media.id }?.let { TrackIdentity(it.trackId, "") }
        ?: hostIdentityFor(media)

    private fun hostIdentityFor(media: PlaybackExtensionPoint.MediaItem): TrackIdentity =
        TrackIdentity(media.id.ifBlank {
            if (media.path.isNotBlank()) "" else "host:" +
                listOf(media.title, media.artist, media.album, media.duration.toString())
                    .joinToString("") { "${it.length}:$it" }
        }, media.path)

    private fun mediaKey(media: PlaybackExtensionPoint.MediaItem): String = identityFor(media).let { "${it.id}|${it.path}" }

    private fun externalDocument(lyrics: ExternalLyrics?, durationMs: Long): LyricDocument {
        if (lyrics == null || lyrics.format == "empty") return LyricDocument.EMPTY
        if (lyrics.format == "ttml") TtmlReader().read(lyrics.content)?.let { return it }
        val lines = ExternalSplReader.read(lyrics.spl, durationMs).mapIndexed { index, line ->
            val words = line.words.map { LyricWord(it.text, it.startMs, it.endMs) }.ifEmpty {
                listOf(LyricWord(line.text, line.startMs, line.endMs, timed = false))
            }
            LyricLine(line.startMs, line.endMs, words, translation = line.translation, group = index)
        }
        return LyricDocument(HostSecondaryLyrics(readings, cantoneseReadings).split(lines), LyricDocument.Origin.HOST)
    }
    private fun clear() {
        key = ""; initialized = false; localDocument = null; inbox.close()
        selectedMedia = null
        audioLanguages = emptyList()
        footerInbox.current(null)
        if (currentIdentity() == null) clock.clear()
        if (!closed) mutablePresentation.value = LyricsPresentation()
    }
    private fun enqueue(action: () -> Unit) {
        if (closed) return
        runCatching { worker.execute { if (!closed) runCatching(action).onFailure { System.err.println("AMLL: ${it.message}") } } }
    }
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        preferences.onAnnotationChange = null
        mutablePresentation.value = LyricsPresentation()
        closePluginResources(AutoCloseable { worker.shutdownNow() }, externalArtwork, covers,
            AutoCloseable { inbox.close() }, AutoCloseable { footerInbox.close() }, preferences)
    }
}
