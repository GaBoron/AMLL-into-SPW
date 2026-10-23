package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope

/** Free space between retained voices one completed paragraph at a time. */
internal class LyricOverflowCollapse {
    class Row {
        private val fade = ActivationEnvelope(1f, .2f, .2f)
        private val space = ActivationEnvelope(1f, .25f, .25f)
        var hidden = false
        val opacity: Float get() = fade.value
        val expansion: Float get() = space.value
        val collapsing: Boolean get() = hidden && expansion > .001f

        fun update(dt: Float, immediate: Boolean) {
            fade.update(!hidden, dt, immediate)
            // Finish fading before closing the slot, so moving rows do not cross visible text.
            space.update(!hidden || opacity > .001f, dt, immediate)
        }
    }

    var rows = emptyList<Row>(); private set
    var latestOnly = false; private set
    private var cooldown = 0f
    val expansion: List<Float> get() = rows.map { it.expansion }

    fun prepare(count: Int, seek: Boolean) {
        if (rows.size != count || seek) {
            rows = List(count) { Row() }
            cooldown = 0f
            latestOnly = false
        }
    }

    fun update(groups: List<GroupLayout>, geometry: LyricGeometry, timeMs: Long, viewportHeight: Float,
        dt: Float, enabled: Boolean, immediate: Boolean) {
        val step = dt.coerceIn(0f, .064f)
        cooldown = (cooldown - step).coerceAtLeast(0f)
        rows.forEachIndexed { index, row ->
            if (!enabled || groups[index].rows.any { it.line.endMs > timeMs }) row.hidden = false
        }
        val span = lyricVisibleSpan(groups, geometry, timeMs)
        val available = viewportHeight - lyricViewportTopMargin(viewportHeight) - lyricViewportBottomMargin(viewportHeight)
        val overflow = enabled && viewportHeight > 0f && span != null && span.bottom - span.top > available
        val candidate = if (overflow) (span.firstGroup + 1 until span.lastGroup).firstOrNull { index ->
            !rows[index].hidden && groups[index].rows.all { it.line.endMs <= timeMs }
        } else null
        if (candidate != null && cooldown == 0f && rows.none { it.collapsing }) {
            rows[candidate].hidden = true
            // Keep the sequence paced even when the user disables animation.
            cooldown = .45f
        }
        rows.forEach { it.update(step, immediate) }
        latestOnly = overflow && candidate == null && rows.none { it.collapsing }
    }
}
