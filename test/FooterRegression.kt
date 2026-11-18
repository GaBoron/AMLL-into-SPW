package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.host.ExternalPlaybackClient
import io.github.gaboron.amllspw.host.FooterReceiver
import io.github.gaboron.amllspw.interop.FooterBridge
import io.github.gaboron.amllspw.interop.FooterInbox
import io.github.gaboron.amllspw.interop.PlaybackIdentity
import io.github.gaboron.amllspw.interop.TrackIdentity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.management.MBeanServerFactory
import javax.management.ObjectName
import javax.management.StandardMBean
import javax.management.openmbean.CompositeData
import javax.management.openmbean.CompositeDataSupport
import javax.management.openmbean.CompositeType
import javax.management.openmbean.SimpleType

internal fun verifyFooterProtocol() {
    val server = MBeanServerFactory.newMBeanServer()
    val client = ExternalPlaybackClient(server)
    val firstSource = ObjectName("dev.gaboron.spw:type=ExternalPlayback,version=1,provider=example.player")
    val secondSource = ObjectName("dev.gaboron.spw:type=ExternalPlayback,version=1,provider=another.player")
    val firstTrack = PlaybackIdentity.External(firstSource, "example.player", "session-1", 3, "urn:example:track:123")
    val source = PlaybackSource(firstTrack)
    val local = PlaybackIdentity.Host(TrackIdentity("local-id", "music/album/../song.flac"))
    var host: PlaybackIdentity.Host? = local
    val inbox = FooterInbox { client.snapshot()?.identity ?: host }
    var enabled = true
    var notifications = 0

    fun invoke(name: String, vararg args: Any?): Boolean {
        val signature = args.map { argument ->
            when (argument) {
                is Long -> "long"
                is Array<*> -> String::class.java.arrayType().name
                else -> "java.lang.String"
            }
        }.toTypedArray()
        return server.invoke(FooterBridge.NAME, name, args, signature) == true
    }

    fun submit(track: PlaybackIdentity.External, value: String, endpoint: String? = track.endpoint.canonicalName) =
        invoke("submitExternalFooter", "example.footer", "Example Footer", endpoint, track.provider,
            track.sessionId, track.trackRevision, track.uri, arrayOf("Source"), arrayOf(value))

    fun clear(track: PlaybackIdentity.External) =
        invoke("clearExternalFooter", "example.footer", track.endpoint.canonicalName, track.provider,
            track.sessionId, track.trackRevision, track.uri)

    fun current(track: PlaybackIdentity) = inbox.current(track).singleOrNull()?.fields?.singleOrNull()?.second

    inbox.use {
        FooterBridge(FooterReceiver(inbox, { enabled }) { notifications++ }, server).use {
            check(server.getAttribute(FooterBridge.NAME, "ProtocolVersion") == 1)
            check(invoke("submitFooter", "example.footer", "Local", "local-id", "music/song.flac",
                arrayOf("Source"), arrayOf("File")))
            check(current(local) == "File")
            check(!invoke("clearFooter", "example.footer", "wrong-id", "music/song.flac"))
            check(invoke("clearFooter", "example.footer", "local-id", "music/song.flac"))

            server.registerMBean(StandardMBean(source, RegressionPlaybackSourceMBean::class.java), firstSource)
            check(client.snapshot()?.identity == firstTrack) { "The paused source should be eligible for playback" }
            check(submit(firstTrack, "Original"))
            check(submit(firstTrack, "Updated", endpoint = firstSource.toString()))
            check(current(firstTrack) == "Updated")
            check(notifications == 4)

            val differentIdentities = listOf(
                firstTrack.copy(endpoint = secondSource),
                firstTrack.copy(provider = "other.provider"),
                firstTrack.copy(sessionId = "old-session"),
                firstTrack.copy(trackRevision = 2),
                firstTrack.copy(uri = "urn:example:track:different"),
            )
            for (different in differentIdentities) {
                check(!submit(different, "Stale")) { "Accepted a mismatched identity: $different" }
                check(!clear(different)) { "Cleared a mismatched identity: $different" }
            }
            check(!submit(firstTrack, "Invalid", endpoint = "bad object name"))
            check(!submit(firstTrack, "Invalid", endpoint = null))
            check(!invoke("clearFooter", "example.footer", firstTrack.uri, ""))
            check(!invoke("submitFooter", "example.footer", "Local", "local-id", "music/song.flac",
                arrayOf("Source"), arrayOf("File")))
            enabled = false
            check(!submit(firstTrack, "Disabled"))
            check(!clear(firstTrack))
            enabled = true
            check(notifications == 4 && current(firstTrack) == "Updated")

            // Replaying the same URI creates a new selection. A late clear must spare the new footer.
            val replay = firstTrack.copy(trackRevision = 5)
            source.track = replay
            check(!submit(firstTrack, "Late"))
            check(inbox.current(replay).isEmpty())
            check(submit(replay, "Replay"))
            check(!clear(firstTrack))
            check(current(replay) == "Replay")
            check(clear(replay) && inbox.current(replay).isEmpty())

            check(submit(replay, "Before switching source"))
            val competingTrack = replay.copy(endpoint = secondSource, provider = "another.player")
            val competingSource = PlaybackSource(competingTrack, activatedAtNanos = 2)
            server.registerMBean(StandardMBean(competingSource, RegressionPlaybackSourceMBean::class.java), secondSource)
            check(!submit(replay, "Unselected source"))
            check(submit(competingTrack, "Selected source"))
            check(inbox.current(replay).isEmpty())
            check(current(competingTrack) == "Selected source")
            competingSource.active = false
            check(!clear(competingTrack))
            check(inbox.current(replay).isEmpty())

            check(submit(replay, "Before expiry"))
            source.updatedAtNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(16)
            check(!submit(replay, "Expired"))
            check(inbox.current(replay).isEmpty())
            server.unregisterMBean(firstSource)
            val restarted = replay.copy(sessionId = "session-2")
            server.registerMBean(StandardMBean(PlaybackSource(restarted), RegressionPlaybackSourceMBean::class.java), firstSource)
            check(!submit(replay, "Prior session"))
            check(submit(restarted, "New session"))
            server.unregisterMBean(firstSource)
            check(inbox.current(restarted).isEmpty())
            check(!clear(restarted))

            val stream = PlaybackIdentity.Host(TrackIdentity("stream-id", "https://example.invalid/live"))
            host = stream
            check(invoke("submitFooter", "example.footer", "Host stream", stream.media.id, stream.media.path,
                arrayOf("Source"), arrayOf("Host URI")))
            check(current(stream) == "Host URI")
            host = null
            check(inbox.current(stream).isEmpty())
            host = stream
            check(inbox.current(stream).isEmpty())
        }
        check(!server.isRegistered(FooterBridge.NAME))
    }
    println("Footer JMX: host compatibility, external submit/replace/clear, source changes, expiry and stale identity rejection passed")
}

internal fun verifyConcurrentFooterUpdate() {
    val first = PlaybackIdentity.External(ObjectName("test:type=Playback"), "player", "session", 1, "urn:track:1")
    val next = first.copy(trackRevision = 2)
    val live = AtomicReference(first)
    val readingFirst = CountDownLatch(1)
    val resume = CountDownLatch(1)
    FooterInbox {
        val current = live.get()
        if (current == first) {
            readingFirst.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        current
    }.use { inbox ->
        val pending = CompletableFuture.supplyAsync {
            inbox.submit("example.footer", "Example", first, arrayOf("Source"), arrayOf("Old"))
        }
        try {
            check(readingFirst.await(5, TimeUnit.SECONDS))
            live.set(next)
            check(inbox.submit("example.footer", "Example", next, arrayOf("Source"), arrayOf("New")))
        } finally {
            resume.countDown()
        }
        check(!pending.get(5, TimeUnit.SECONDS))
        check(inbox.current(next).single().fields.single().second == "New")
    }
    println("Footer concurrency: a delayed snapshot cannot overwrite a newer selection")
}

internal interface RegressionPlaybackSourceMBean {
    fun getProtocolVersion(): Int
    fun getSnapshot(): CompositeData
}

private class PlaybackSource(
    var track: PlaybackIdentity.External,
    private val activatedAtNanos: Long = 1,
) : RegressionPlaybackSourceMBean {
    var active = true
    var updatedAtNanos = System.nanoTime()

    override fun getProtocolVersion() = 1

    override fun getSnapshot(): CompositeData {
        val values = linkedMapOf(
            "active" to active,
            "playing" to false,
            "activatedAtNanos" to activatedAtNanos,
            "updatedAtNanos" to updatedAtNanos,
            "provider" to track.provider,
            "sessionId" to track.sessionId,
            "trackRevision" to track.trackRevision,
            "uri" to track.uri,
        )
        val names = values.keys.toTypedArray()
        val types = values.values.map {
            when (it) {
                is Boolean -> SimpleType.BOOLEAN
                is Long -> SimpleType.LONG
                else -> SimpleType.STRING
            }
        }.toTypedArray()
        return CompositeDataSupport(CompositeType("Snapshot", "Playback snapshot", names, names, types), values)
    }
}
