package io.github.gaboron.amllspw.interop

import io.github.gaboron.amllspw.footer.FooterContribution
import java.text.Collator
import java.util.Locale

/** Current-song contributions, independently replaceable by each provider. No disk cache. */
internal class FooterInbox(private val currentTrack: () -> TrackIdentity?) : AutoCloseable {
    private val entries = mutableMapOf<String, FooterContribution>()
    private val names = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    private var track: TrackIdentity? = null
    private var closed = false

    fun submit(providerId: String?, providerName: String?, requested: TrackIdentity,
        keys: Array<out String?>?, values: Array<out String?>?): Boolean {
        val contribution = validate(providerId, providerName, keys, values) ?: return false
        return synchronized(this) {
            if (closed || selectCurrent()?.matches(requested) != true) return false
            if (contribution.providerId !in entries && entries.size >= MAX_PROVIDERS) return false
            entries[contribution.providerId] = contribution
            true
        }
    }

    @Synchronized fun clear(providerId: String?, requested: TrackIdentity): Boolean {
        if (closed || selectCurrent()?.matches(requested) != true) return false
        return entries.remove(providerId) != null
    }

    @Synchronized fun current(requested: TrackIdentity?): List<FooterContribution> {
        if (closed) return emptyList()
        val playing = selectCurrent()
        if (requested == null || playing?.matches(requested) != true) return emptyList()
        return entries.values.sortedWith { a, b ->
            names.compare(a.providerName, b.providerName).takeIf { it != 0 }
                ?: a.providerId.compareTo(b.providerId)
        }
    }

    /** Read the live identity under the same lock as writes, including before the first IO poll. */
    private fun selectCurrent(): TrackIdentity? {
        val playing = currentTrack()
        if (playing == null || track?.matches(playing) != true) {
            entries.clear()
            track = playing
        }
        return playing
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
