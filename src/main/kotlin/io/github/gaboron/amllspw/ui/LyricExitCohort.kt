package io.github.gaboron.amllspw.ui

/** Nearby end times share a scroll destination; activation still uses the original timeline. */
internal class LyricExitCohort(groups: List<GroupLayout>) {
    private val ends = groups.map { group -> group.rows.maxOf { it.line.endMs } }
    private val starts = groups.map { group -> group.rows.filterNot { it.line.background }.minOfOrNull { it.line.startMs } }
    private val orderedEnds = ends.distinct().sorted()

    fun focus(current: Int, timeMs: Long, playbackRate: Float): Int {
        val window = (300 * playbackRate.coerceIn(.1f, 8f)).toLong()
        val firstExit = ends.take(current).filter { it <= timeMs && timeMs - it <= window }.maxOrNull()
            ?: return current
        // Bound lookahead by one recent exit; never extend it by chaining future gaps.
        val lastExit = orderedEnds.lastOrNull { it > timeMs && it - firstExit <= window } ?: return current
        if (ends[current] !in timeMs + 1..lastExit) return current
        return (current + 1 until starts.size).lastOrNull { index ->
            starts[index]?.let { it in timeMs..lastExit } == true
        } ?: current
    }
}
