package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect

internal data class LyricContentKey(val translation: Boolean, val romanization: Boolean)
internal data class LyricContentWeight(val key: LyricContentKey, val opacity: Float)
internal data class LyricLayoutLayer(val key: LyricContentKey, val opacity: Float, val groups: List<GroupLayout>)

/** Keep the original baseline aligned and draw each auxiliary line once as neighbouring slots move. */
internal fun lyricRowContent(layers: List<LyricLayoutLayer>, group: Int, row: Int): LyricRowContent {
    if (layers.size == 1) return layers.single().groups[group].rows[row].content
    val rows = layers.map { it.groups[group].rows[row] to it.opacity }
    val baseline = rows.sumOf { ((it.first.rubyHeight + it.first.main.firstBaseline) * it.second).toDouble() }.toFloat()
    var top = rows.sumOf { ((it.first.rubyHeight + it.first.main.size.height + 6f) * it.second).toDouble() }.toFloat()
    val auxiliary = buildList {
        for (translation in listOf(true, false)) {
            val candidates = rows.mapNotNull { (layout, weight) ->
                layout.auxiliary.firstOrNull { it.translation == translation }?.let { it.layout to weight }
            }
            if (candidates.isEmpty()) continue
            val opacity = candidates.sumOf { it.second.toDouble() }.toFloat()
            val height = candidates.sumOf { ((it.first.size.height + 5f) * it.second).toDouble() }.toFloat()
            add(LyricAuxiliaryLayer(candidates.maxBy { it.second }.first, top, height, opacity))
            top += height
        }
    }
    return LyricRowContent(baseline, auxiliary)
}

internal fun DrawScope.withLyricRowTransitionClip(changing: Boolean, height: Float, padding: Float,
    content: DrawScope.() -> Unit) {
    if (changing) clipRect(-padding, -padding, size.width + padding, (height - 5f).coerceAtLeast(0f), block = content)
    else content()
}

/** Reversible content fades share their progress with layout heights; measurements stay cached. */
internal class LyricContentTransition(initial: RenderOptions) {
    private fun envelope(visible: Boolean) = ActivationEnvelope(if (visible) 1f else 0f,
        .28f, .28f, CubicBezier(.25f, .1f, .25f, 1f))
    private val translation = envelope(initial.translation)
    private val romanization = envelope(initial.romanization)
    private val footer = envelope(!initial.hideFooter)
    val footerVisibility: Float get() = footer.value

    fun update(options: RenderOptions, dt: Float): List<LyricContentWeight> {
        val t = translation.update(options.translation, dt, options.reducedMotion)
        val r = romanization.update(options.romanization, dt, options.reducedMotion)
        footer.update(!options.hideFooter, dt, options.reducedMotion)
        return buildList {
            for (showTranslation in listOf(false, true)) for (showRomanization in listOf(false, true)) {
                val opacity = (if (showTranslation) t else 1f - t) * (if (showRomanization) r else 1f - r)
                if (opacity > 0f) add(LyricContentWeight(LyricContentKey(showTranslation, showRomanization), opacity))
            }
        }
    }
}

/** All variants describe the same lyric rows, including duet and backing-vocal ordering. */
internal fun blendLyricLayouts(layers: List<LyricLayoutLayer>): List<GroupLayout> {
    if (layers.size == 1) return layers.single().groups
    val representative = layers.maxBy { it.opacity }.groups
    var top = 0f
    return representative.mapIndexed { index, group ->
        val heights = group.rows.indices.map { row ->
            layers.sumOf { (it.groups[index].rowHeights[row] * it.opacity).toDouble() }.toFloat()
        }
        val height = layers.sumOf { (it.groups[index].height * it.opacity).toDouble() }.toFloat()
        GroupLayout(group.rows, top, height, heights).also { top += height }
    }
}
