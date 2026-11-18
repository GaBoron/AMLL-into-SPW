package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.interop.FooterInbox
import io.github.gaboron.amllspw.interop.FooterReceiverMBean
import io.github.gaboron.amllspw.interop.PlaybackIdentity
import io.github.gaboron.amllspw.interop.TrackIdentity
import javax.management.MalformedObjectNameException
import javax.management.ObjectName

internal class FooterReceiver(
    private val inbox: FooterInbox,
    private val active: () -> Boolean,
    private val onChange: () -> Unit,
) : FooterReceiverMBean {
    override fun getProtocolVersion() = 1
    override fun isActive() = active()

    override fun submitFooter(providerId: String?, providerName: String?, trackId: String?, audioPath: String?,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean {
        if (trackId == null || audioPath == null) return false
        return submit(providerId, providerName, PlaybackIdentity.Host(TrackIdentity(trackId, audioPath)), keys, values)
    }

    override fun clearFooter(providerId: String?, trackId: String?, audioPath: String?): Boolean {
        if (trackId == null || audioPath == null) return false
        return clear(providerId, PlaybackIdentity.Host(TrackIdentity(trackId, audioPath)))
    }

    override fun submitExternalFooter(providerId: String?, providerName: String?, endpoint: String?,
        playbackProvider: String?, sessionId: String?, trackRevision: Long, uri: String?, keys: Array<out String?>?,
        values: Array<out String?>?): Boolean {
        val track = externalIdentity(endpoint, playbackProvider, sessionId, trackRevision, uri) ?: return false
        return submit(providerId, providerName, track, keys, values)
    }

    override fun clearExternalFooter(providerId: String?, endpoint: String?, playbackProvider: String?, sessionId: String?,
        trackRevision: Long, uri: String?): Boolean {
        val track = externalIdentity(endpoint, playbackProvider, sessionId, trackRevision, uri) ?: return false
        return clear(providerId, track)
    }

    private fun submit(providerId: String?, providerName: String?, track: PlaybackIdentity,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean =
        (isActive() && inbox.submit(providerId, providerName, track, keys, values)).also { if (it) onChange() }

    private fun clear(providerId: String?, track: PlaybackIdentity): Boolean =
        (isActive() && inbox.clear(providerId, track)).also { if (it) onChange() }

    private fun externalIdentity(endpoint: String?, playbackProvider: String?, sessionId: String?, trackRevision: Long,
        uri: String?): PlaybackIdentity.External? {
        if (endpoint.isNullOrBlank() || playbackProvider.isNullOrBlank() || sessionId.isNullOrBlank() || uri.isNullOrBlank()) return null
        val name = try {
            ObjectName(endpoint)
        } catch (_: MalformedObjectNameException) {
            return null
        }
        if (name.isPattern) return null
        return PlaybackIdentity.External(name, playbackProvider, sessionId, trackRevision, uri)
    }
}
