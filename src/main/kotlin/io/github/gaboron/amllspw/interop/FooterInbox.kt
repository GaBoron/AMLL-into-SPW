package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.footer.FooterContribution
import java.text.Collator
import java.util.Locale

/** Current-song contributions, independently replaceable by each provider. No disk cache. */
internal class FooterInbox(private val currentTrack: () -> PlaybackIdentity?) : AutoCloseable {
    private val entries = mutableMapOf<String, FooterContribution>()
    private val names = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    private var track: PlaybackIdentity? = null
    private var trackVersion = 0L
    private var closed = false

    fun submit(providerId: String?, providerName: String?, requested: PlaybackIdentity,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean {
        val contribution = validate(providerId, providerName, keys, values) ?: return false
        return withCurrentTrack(false) { playing ->
            if (playing?.matches(requested) != true) return@withCurrentTrack false
            if (contribution.providerId !in entries && entries.size >= MAX_PROVIDERS) return@withCurrentTrack false
            entries[contribution.providerId] = contribution
            true
        }
    }

    fun clear(providerId: String?, requested: PlaybackIdentity): Boolean = withCurrentTrack(false) { playing ->
        playing?.matches(requested) == true && entries.remove(providerId) != null
    }

    fun current(requested: PlaybackIdentity?): List<FooterContribution> =
        withCurrentTrack(emptyList()) { playing ->
            if (requested == null || playing?.matches(requested) != true) return@withCurrentTrack emptyList()
            entries.values.sortedWith { a, b ->
                names.compare(a.providerName, b.providerName).takeIf { it != 0 }
                    ?: a.providerId.compareTo(b.providerId)
            }
        }

    private fun <T> withCurrentTrack(ifClosed: T, action: (PlaybackIdentity?) -> T): T {
        while (true) {
            val observed = synchronized(this) {
                if (closed) return ifClosed
                trackVersion
            }
            // Source snapshot getters can acquire plugin locks. Retry if another call changed the track meanwhile.
            val playing = currentTrack()
            synchronized(this) {
                if (closed) return ifClosed
                if (trackVersion == observed) {
                    if (track != playing && (playing == null || track?.matches(playing) != true)) {
                        entries.clear()
                        track = playing
                        trackVersion++
                    }
                    return action(playing)
                }
            }
        }
    }

    private fun validate(providerId: String?, providerName: String?, keys: Array<out String?>?,
        values: Array<out String?>?): FooterContribution? {
        if (providerId == null || !PROVIDER_ID.matches(providerId) ||
            providerName == null || providerName.length > 128 || providerName.isBlank() ||
            providerName.any { it == '\n' || it == '\r' || it == '\t' } ||
            keys == null || values == null || keys.size !in 1..MAX_FIELDS || keys.size != values.size) return null
        var length = 0
        val fields = keys.indices.map { index ->
            val key = keys[index] ?: return null
            val value = values[index] ?: return null
            if (key.length > 128 || key.isBlank() || key.any { it == '\n' || it == '\r' || it == '\t' } ||
                value.length > 4_096 || value.isBlank()) return null
            length += key.length + value.length
            if (length > 32_768) return null
            key.trim() to value.trim()
        }
        return FooterContribution(providerId, providerName.trim(), fields)
    }

    @Synchronized override fun close() { closed = true; entries.clear(); track = null }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val MAX_PROVIDERS = 16
        const val MAX_FIELDS = 64
    }
}
