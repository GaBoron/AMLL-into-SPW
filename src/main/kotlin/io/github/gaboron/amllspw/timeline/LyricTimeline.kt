package io.github.gaboron.amllspw.timeline

import io.github.gaboron.amllspw.lyrics.LyricDocument

/** A group can contain several independently active voices; focus never determines activation. */
internal class LyricTimeline(val document: LyricDocument) {
    data class Group(val id: Int, val lines: List<Int>, val start: Long, val end: Long)
    data class Frame(val active: Set<Int>, val focus: Int, val interlude: Interlude?)
    data class Interlude(val start: Long, val end: Long, val beforeGroup: Int)

    val groups = document.lines.indices.groupBy { document.lines[it].group }.map { (id, indices) ->
        Group(id, indices, indices.minOf { document.lines[it].startMs }, indices.maxOf { document.lines[it].endMs })
    }.sortedBy { it.start }

    fun frame(ms: Long): Frame {
        val active = document.lines.indices.filterTo(linkedSetOf()) { document.lines[it].active(ms) }
        val first = groups.indexOfFirst { group -> group.lines.any { it in active } }
        val past = groups.indexOfLast { it.start <= ms }
        val next = groups.indexOfFirst { it.start > ms }
        val previousEnd = groups.filter { it.start <= ms }.maxOfOrNull { it.end } ?: 0L
        val interlude = if (active.isEmpty() && next >= 0 && groups[next].start - previousEnd >= 7_000)
            Interlude(previousEnd, groups[next].start, next) else null
        return Frame(active, (if (first >= 0) first else if (interlude != null) next else past).coerceAtLeast(0), interlude)
    }
}
