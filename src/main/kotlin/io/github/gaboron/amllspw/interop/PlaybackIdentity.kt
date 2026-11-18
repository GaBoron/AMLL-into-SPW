package io.github.gaboron.amllspw.interop

import javax.management.ObjectName

/** The active playback identity used to scope footer contributions. */
internal sealed interface PlaybackIdentity {
    data class Host(val media: TrackIdentity) : PlaybackIdentity

    data class External(
        val endpoint: ObjectName,
        val provider: String,
        val sessionId: String,
        val trackRevision: Long,
        val uri: String,
    ) : PlaybackIdentity

    fun matches(other: PlaybackIdentity): Boolean = when {
        this is Host && other is Host -> media.matches(other.media)
        else -> this == other
    }
}
