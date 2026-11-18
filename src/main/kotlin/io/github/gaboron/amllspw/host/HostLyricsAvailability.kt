package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.lyrics.InstrumentalLyrics
import io.github.gaboron.amllspw.lyrics.LyricDocument
import java.util.concurrent.TimeUnit

/** Host reloads can expose temporary empty lyrics or a provider's instrumental placeholder. */
internal class HostLyricsAvailability {
    private var missingSinceNanos: Long? = null
    val awaitingConfirmation: Boolean get() = missingSinceNanos != null

    fun canPublish(document: LyricDocument, alreadyInstrumental: Boolean, nowNanos: Long = System.nanoTime()): Boolean {
        if (alreadyInstrumental || !InstrumentalLyrics.matches(document)) {
            reset()
            return true
        }
        val since = missingSinceNanos ?: nowNanos.also { missingSinceNanos = it }
        if (nowNanos - since < CONFIRMATION_NANOS) return false
        reset()
        return true
    }

    fun reset() {
        missingSinceNanos = null
    }

    private companion object {
        val CONFIRMATION_NANOS = TimeUnit.SECONDS.toNanos(1)
    }
}
