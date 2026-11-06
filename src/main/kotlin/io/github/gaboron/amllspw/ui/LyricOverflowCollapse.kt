package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.SpringMotion

/** Fade and fold completed paragraphs in a continuous cascade between retained voices. */
internal class LyricOverflowCollapse {
    class Row {
        // Critical progress gives a roughly 400 ms fade followed by a softer height close.
        private val fold = SpringMotion(0f, 20f, 8.944272f)
        var hidden = false
        private val progress: Float get() = fold.value.coerceIn(0f, 1f)
        val opacity: Float get() = 1f - ease(progress / .55f)
        val scale: Float get() = .97f + .03f * opacity
        val expansion: Float get() = 1f - ease((progress - .48f) / .52f)
        val collapsing: Boolean get() = hidden && expansion > .001f

        fun update(dt: Float, immediate: Boolean) {
            // One reversible spring owns both phases, retaining momentum on pause/browse.
            // Fading leads the fold; on reversal, space opens before text returns.
            val target = if (hidden) 1f else 0f
            if (immediate) fold.snap(target) else fold.update(target, dt)
        }

        private fun ease(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            // Zero speed and acceleration at each phase boundary avoid a visible handoff.
            return (t * t * t * (t * (t * 6f - 15f) + 10f)).coerceIn(0f, 1f)
        }
    }

    var rows = emptyList<Row>(); private set
    var latestOnly = false; private set
    private var cooldown = 0f
    private var planned = booleanArrayOf()
    val expansion: List<Float> get() = rows.map { it.expansion }

    fun prepare(count: Int, seek: Boolean) {
        val changed = rows.size != count
        if (changed) {
            rows = List(count) { Row() }
            planned = BooleanArray(count)
        }
        if (changed || seek) {
            // Re-evaluate which rows fit after a jump, while unfolding from their current
            // spring progress instead of replacing a partly hidden row with a visible one.
            rows.forEach { it.hidden = false }
            planned.fill(false)
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
            // Plan sufficient room, but framing follows the actual animated layout.
            for (index in middle) {
                if (required <= available) break
                if (index != focus && !planned[index] && groups[index].rows.all { it.line.endMs <= timeMs }) {
                    planned[index] = true
                    required -= geometry.heights[index]
                }
            }
        }
        val candidate = rows.indices.firstOrNull { planned[it] && !rows[it].hidden }
        if (candidate != null && cooldown == 0f && rows.none { it.hidden && it.opacity > .15f }) {
            rows[candidate].hidden = true
            // Keep the sequence paced even when the user disables animation.
            cooldown = .18f
        }
        rows.forEach { it.update(step, immediate) }
        latestOnly = enabled && viewportHeight > 0f && span != null && required > available
    }
}
