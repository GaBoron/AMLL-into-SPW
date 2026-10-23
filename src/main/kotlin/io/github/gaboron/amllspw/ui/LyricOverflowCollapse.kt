package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope

/** Free space between retained voices one completed paragraph at a time. */
internal class LyricOverflowCollapse {
    class Row {
        private val fade = ActivationEnvelope(1f, .12f, .12f)
        private val space = ActivationEnvelope(1f, .06f, .06f)
        var hidden = false
        val opacity: Float get() = fade.value
        val expansion: Float get() = space.value
        val collapsing: Boolean get() = hidden && expansion > .001f

        fun update(dt: Float, immediate: Boolean) {
            fade.update(!hidden, dt, immediate)
            // Keep its full slot until the completed text has faded, so the next row
            // cannot pass through it while opening space for retained voices.
            space.update(!hidden || fade.value > .001f, dt, immediate)
        }
    }

    var rows = emptyList<Row>(); private set
    var latestOnly = false; private set
    private var cooldown = 0f
    private var planned = booleanArrayOf()
    val expansion: List<Float> get() = rows.map { it.expansion }
    val plannedExpansion: List<Float> get() = planned.map { if (it) 0f else 1f }

    fun prepare(count: Int, seek: Boolean) {
        if (rows.size != count || seek) {
            rows = List(count) { Row() }
            planned = BooleanArray(count)
            cooldown = 0f
            latestOnly = false
        }
    }

    fun update(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long, viewportHeight: Float,
        dt: Float, enabled: Boolean, immediate: Boolean, focus: Int? = null) {
        val step = dt.coerceIn(0f, .064f)
        cooldown = (cooldown - step).coerceAtLeast(0f)
        rows.forEachIndexed { index, row ->
            if (!enabled || index == focus || groups[index].rows.any { it.line.endMs > timeMs }) row.hidden = false
        }
        val span = lyricVisibleSpan(groups, geometry, timeMs, focus)
        val available = viewportHeight - lyricViewportTopMargin(viewportHeight) - lyricViewportBottomMargin(viewportHeight)
        rows.forEachIndexed { index, row -> planned[index] = row.hidden }
        var required = span?.let { it.bottom - it.top } ?: 0f
        if (enabled && viewportHeight > 0f && span != null) {
            val middle = span.firstGroup + 1 until span.lastGroup
            required -= middle.filter { planned[it] }.sumOf { geometry.heights[it].toDouble() }.toFloat()
            // Plan enough space before changing the scroll target. Execution remains serial,
            // so a later row does not fade until the preceding row has finished closing.
            for (index in middle) {
                if (required <= available) break
                if (index != focus && !planned[index] && groups[index].rows.all { it.line.endMs <= timeMs }) {
                    planned[index] = true
                    required -= geometry.heights[index]
                }
            }
        }
        val candidate = rows.indices.firstOrNull { planned[it] && !rows[it].hidden }
        if (candidate != null && cooldown == 0f && rows.none { it.collapsing }) {
            rows[candidate].hidden = true
            // Keep the sequence paced even when the user disables animation.
            cooldown = .24f
        }
        rows.forEach { it.update(step, immediate) }
        latestOnly = enabled && viewportHeight > 0f && span != null && required > available
    }
}
