package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.interop.PlaybackIdentity
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.HttpURLConnection
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

/** JDK-only process-local contract, shared by independently loaded playback plugins. */
internal class ExternalPlaybackClient(private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()) {
    fun snapshot(): ExternalPlayback? = runCatching {
        server.queryNames(SOURCES, null).mapNotNull(::snapshot)
            .maxWithOrNull(compareBy<ExternalPlayback> { it.activatedAtNanos }.thenBy { it.sourceName.canonicalName })
    }.getOrNull()

    private fun snapshot(source: ObjectName): ExternalPlayback? = runCatching {
        if (server.getAttribute(source, "ProtocolVersion") != 1) return null
        val value = server.getAttribute(source, "Snapshot") as? CompositeData ?: return null
        if (value["active"] != true) return null
        val updated = value.number("updatedAtNanos")
        if (updated != 0L && System.nanoTime() - updated !in 0L..15_000_000_000L) return null
        ExternalPlayback(
            sourceName = source, activatedAtNanos = value.number("activatedAtNanos"),
            provider = value.text("provider"), sessionId = value.text("sessionId"),
            revision = value.number("revision"), trackRevision = value.number("trackRevision"),
            uri = value.text("uri"), title = value.text("title"), artist = value.text("artist"),
            album = value.text("album"), coverUrl = value.text("coverUrl"),
            durationMs = value.number("durationMs").coerceAtLeast(0),
            positionMs = value.number("positionMs").coerceAtLeast(0),
            sampledAtNanos = value.number("sampledAtNanos"), playing = value["playing"] == true,
            volumePercent = value.number("volumePercent").coerceIn(0, 100).toInt(),
            playbackSpeed = (if (value.containsKey("playbackSpeed")) value["playbackSpeed"] as? Number else null)
                ?.toDouble()?.takeIf { it.isFinite() && it in .1..8.0 } ?: 1.0,
        ).takeIf { it.provider.isNotBlank() && it.sessionId.isNotBlank() && it.uri.isNotBlank() }
    }.getOrNull()

    fun lyrics(track: ExternalPlayback): ExternalLyrics? = runCatching {
        if (snapshot()?.identity != track.identity) return null
        val value = server.getAttribute(track.sourceName, "Lyrics") as? CompositeData ?: return null
        if (snapshot()?.identity != track.identity) return null
        if (value.text("sessionId") != track.sessionId || value.number("trackRevision") != track.trackRevision ||
            value.text("uri") != track.uri) return null
        if (value.text("content").length > 2_000_000 || value.text("spl").length > 2_000_000) return null
        if (value.number("offsetMs") !in -600_000L..600_000L) return null
        ExternalLyrics(value.text("format"), value.text("content"), value.text("spl"),
            value.number("offsetMs"), value.number("revision"))
    }.getOrNull()

    fun seek(track: ExternalPlayback, positionMs: Long): Boolean = runCatching {
        val operations = server.getMBeanInfo(track.sourceName).operations
        fun supports(name: String, signature: Array<String>) = operations.any {
            it.name == name && it.signature.map { argument -> argument.type } == signature.toList()
        }
        if (snapshot()?.identity != track.identity) return false
        if (supports("control", CONTROL_SIGNATURE)) {
            server.invoke(track.sourceName, "control",
                arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, "seek", positionMs.coerceAtLeast(0)),
                CONTROL_SIGNATURE) == true
        } else if (supports("seekToIdentity", SEEK_IDENTITY_SIGNATURE)) {
            server.invoke(track.sourceName, "seekToIdentity",
                arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, positionMs.coerceAtLeast(0)),
                SEEK_IDENTITY_SIGNATURE) == true
        } else if (supports("seekTo", SEEK_SIGNATURE)) {
            server.invoke(track.sourceName, "seekTo", arrayOf<Any>(track.uri, positionMs.coerceAtLeast(0)), SEEK_SIGNATURE) == true
        } else false
    }.getOrDefault(false)

    fun supportsTransport(track: ExternalPlayback): Boolean = runCatching {
        val operations = server.getMBeanInfo(track.sourceName).operations
        operations.any {
            it.name == "control" && it.signature.map { argument -> argument.type } == CONTROL_SIGNATURE.toList()
        } || setOf("play", "pause", "previous", "next").all { command ->
            operations.any { it.name == command && it.signature.isEmpty() }
        }
    }.getOrDefault(false)

    fun volume(track: ExternalPlayback, percent: Int): Boolean = runCatching {
        if (snapshot()?.identity != track.identity) return false
        val operations = server.getMBeanInfo(track.sourceName).operations
        if (operations.any { it.name == "control" && it.signature.map { arg -> arg.type } == CONTROL_SIGNATURE.toList() }) {
            server.invoke(track.sourceName, "control",
                arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, "volume", percent.coerceIn(0, 100).toLong()), CONTROL_SIGNATURE) == true
        } else if (operations.any { it.name == "setVolume" && it.signature.map { arg -> arg.type } == listOf("int") }) {
            server.invoke(track.sourceName, "setVolume", arrayOf<Any>(percent.coerceIn(0, 100)), arrayOf("int")) == true
        } else false
    }.getOrDefault(false)

    fun transport(track: ExternalPlayback, command: String): Boolean = runCatching {
        if (command !in setOf("play", "pause", "previous", "next") || snapshot()?.identity != track.identity) return false
        val operations = server.getMBeanInfo(track.sourceName).operations
        if (operations.any { it.name == "control" && it.signature.map { argument -> argument.type } == CONTROL_SIGNATURE.toList() }) {
            server.invoke(track.sourceName, "control",
                arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, command, 0L), CONTROL_SIGNATURE) == true
        } else if (operations.any { it.name == command && it.signature.isEmpty() }) {
            // Older sources expose transport without identity arguments; recheck immediately before dispatch.
            if (snapshot()?.identity != track.identity) return false
            server.invoke(track.sourceName, command, emptyArray<Any>(), emptyArray<String>())
            true
        } else false
    }.getOrDefault(false)

    private companion object {
        val SOURCES = ObjectName("dev.gaboron.spw:type=ExternalPlayback,version=1,*")
        val CONTROL_SIGNATURE = arrayOf("java.lang.String", "long", "java.lang.String", "java.lang.String", "long")
        val SEEK_IDENTITY_SIGNATURE = arrayOf("java.lang.String", "long", "java.lang.String", "long")
        val SEEK_SIGNATURE = arrayOf("java.lang.String", "long")
    }
}

internal data class ExternalPlayback(
    val sourceName: ObjectName, val activatedAtNanos: Long,
    val provider: String, val sessionId: String, val revision: Long, val trackRevision: Long,
    val uri: String, val title: String, val artist: String, val album: String, val coverUrl: String,
    val durationMs: Long, val positionMs: Long, val sampledAtNanos: Long, val playing: Boolean,
    val volumePercent: Int, val playbackSpeed: Double,
) {
    val identity = PlaybackIdentity.External(sourceName, provider, sessionId, trackRevision, uri)
    val trackId = "external:" + listOf(sourceName.canonicalName, provider, sessionId, trackRevision.toString(), uri)
        .joinToString("") { "${it.length}:$it" }
    val artworkKey: String get() = "$trackId${coverUrl.length}:$coverUrl"

    fun currentPosition(): Long {
        val elapsed = if (playing) ((System.nanoTime() - sampledAtNanos) / 1_000_000L).coerceAtLeast(0) else 0
        val position = (positionMs.toDouble() + elapsed * playbackSpeed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

internal data class ExternalLyrics(val format: String, val content: String, val spl: String, val offsetMs: Long, val revision: Long)

private fun CompositeData.text(key: String): String = if (containsKey(key)) get(key) as? String ?: "" else ""
private fun CompositeData.number(key: String): Long = if (containsKey(key)) (get(key) as? Number)?.toLong() ?: 0 else 0

/** Artwork IO accepts bounded HTTP(S) responses without following redirects. */
internal fun readExternalArtwork(url: String): ByteArray? = runCatching {
    val uri = URI(url)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 3_000
        readTimeout = 4_000
        instanceFollowRedirects = false
    }
    try {
        if (connection.responseCode !in 200..299 || connection.contentLengthLong > 8_000_000) return null
        connection.inputStream.use { input -> input.readNBytes(8_000_001).takeIf { it.size <= 8_000_000 } }
    } finally { connection.disconnect() }
}.getOrNull()
