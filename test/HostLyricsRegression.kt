package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.host.HostLyricsAvailability
import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.LyricLine
import io.github.gaboron.amllspw.lyrics.LyricWord
import java.util.concurrent.TimeUnit

internal fun verifyHostLyricsAvailability() {
    fun hostDocument(text: String) = LyricDocument(
        listOf(LyricLine(0, 5_000, listOf(LyricWord(text, 0, 5_000, timed = false)))),
        LyricDocument.Origin.HOST,
    )
    fun at(ms: Long) = TimeUnit.MILLISECONDS.toNanos(ms)

    val availability = HostLyricsAvailability()
    val lyrics = hostDocument("A line with lyrics")
    val instrumental = hostDocument("纯音乐，请欣赏")
    val empty = LyricDocument.EMPTY

    check(availability.canPublish(lyrics, alreadyInstrumental = false, nowNanos = at(0)))
    check(!availability.canPublish(empty, alreadyInstrumental = false, nowNanos = at(100)))
    check(availability.awaitingConfirmation)
    check(!availability.canPublish(instrumental, alreadyInstrumental = false, nowNanos = at(500)))
    check(availability.canPublish(lyrics, alreadyInstrumental = false, nowNanos = at(700)))
    check(!availability.awaitingConfirmation)

    // A later gap needs its own confirmation window after lyrics have recovered.
    check(!availability.canPublish(instrumental, alreadyInstrumental = false, nowNanos = at(1_100)))
    check(!availability.canPublish(empty, alreadyInstrumental = false, nowNanos = at(2_099)))
    check(availability.canPublish(instrumental, alreadyInstrumental = false, nowNanos = at(2_100)))
    check(!availability.awaitingConfirmation)
    check(availability.canPublish(empty, alreadyInstrumental = true, nowNanos = at(2_200)))
    check(availability.canPublish(lyrics, alreadyInstrumental = true, nowNanos = at(2_300)))

    // Changing tracks (or choosing a non-host lyric source) discards unfinished confirmation.
    check(!availability.canPublish(empty, alreadyInstrumental = false, nowNanos = at(2_400)))
    availability.reset()
    check(!availability.canPublish(empty, alreadyInstrumental = false, nowNanos = at(3_400)))
    println("Host lyrics: transient gaps and instrumental notices, confirmed absence and track reset passed")
}
