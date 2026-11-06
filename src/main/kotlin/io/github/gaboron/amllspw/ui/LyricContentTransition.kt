package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import io.github.gaboron.amllspw.timeline.SpringMotion
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.sqrt

internal const val LYRIC_CONTENT_SPRING_STIFFNESS = 160f
internal const val LYRIC_CONTENT_SPRING_DAMPING_RATIO = .72f

internal data class LyricContentKey(val translation: Boolean, val romanization: Boolean)
/** Layout weights extrapolate during overshoot; opacity weights remain a bounded crossfade. */
internal data class LyricContentWeight(val key: LyricContentKey, val opacity: Float, val layoutWeight: Float = opacity)
internal data class LyricLayoutLayer(val key: LyricContentKey, val opacity: Float, val groups: List<GroupLayout>,
    val layoutWeight: Float = opacity)

/** Keep the original baseline aligned and draw each auxiliary line once as neighbouring slots move. */
internal fun lyricRowContent(layers: List<LyricLayoutLayer>, group: Int, row: Int): LyricRowContent {
    if (layers.size == 1) return layers.single().groups[group].rows[row].content
    val baseline = layers.sumOf {
        val layout = it.groups[group].rows[row]
        ((layout.rubyHeight + layout.main.firstBaseline) * it.layoutWeight).toDouble()
    }.toFloat()
    var top = layers.sumOf {
        val layout = it.groups[group].rows[row]
        ((layout.rubyHeight + layout.main.size.height + 6f) * it.layoutWeight).toDouble()
    }.toFloat()
    val auxiliary = buildList {
        for (translation in listOf(true, false)) {
            val candidates = layers.mapNotNull { layer ->
                layer.groups[group].rows[row].auxiliary.firstOrNull { it.translation == translation }
                    ?.let { it.layout to layer }
            }
            if (candidates.isEmpty()) continue
            val opacity = candidates.sumOf { it.second.opacity.toDouble() }.toFloat().coerceIn(0f, 1f)
            val height = candidates.sumOf { ((it.first.size.height + 5f) * it.second.layoutWeight).toDouble() }
                .toFloat().coerceAtLeast(0f)
            add(LyricAuxiliaryLayer(candidates.maxBy { it.second.opacity }.first, top, height, opacity))
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

/** Alpha remains bounded while dimensions use a spring that preserves momentum on reversal. */
internal class LyricContentTransition(initial: RenderOptions) {
    private val translation = ContentVisibility(initial.translation)
    private val romanization = ContentVisibility(initial.romanization)
    private val footer = ContentVisibility(!initial.hideFooter)
    val footerVisibility: Float get() = footer.opacity
    val footerExpansion: Float get() = footer.expansion

    fun update(options: RenderOptions, dt: Float): List<LyricContentWeight> {
        translation.update(options.translation, dt, options)
        romanization.update(options.romanization, dt, options)
        footer.update(!options.hideFooter, dt, options)
        return buildList {
            for (showTranslation in listOf(false, true)) for (showRomanization in listOf(false, true)) {
                val opacity = (if (showTranslation) translation.opacity else 1f - translation.opacity) *
                    (if (showRomanization) romanization.opacity else 1f - romanization.opacity)
                val layoutWeight = (if (showTranslation) translation.expansion else 1f - translation.expansion) *
                    (if (showRomanization) romanization.expansion else 1f - romanization.expansion)
                // A zero-alpha variant can still provide geometry for the spring's overshoot.
                if (opacity > 0f || layoutWeight != 0f)
                    add(LyricContentWeight(LyricContentKey(showTranslation, showRomanization), opacity, layoutWeight))
            }
        }
    }
}

private class ContentVisibility(visible: Boolean) {
    private val fade = ActivationEnvelope(if (visible) 1f else 0f,
        .28f, .28f, CubicBezier(.25f, .1f, .25f, 1f))
    private val spring = SpringMotion(if (visible) 1f else 0f, LYRIC_CONTENT_SPRING_STIFFNESS,
        2f * sqrt(LYRIC_CONTENT_SPRING_STIFFNESS) * LYRIC_CONTENT_SPRING_DAMPING_RATIO)
    val opacity: Float get() = fade.value
    val expansion: Float get() = spring.value.coerceIn(0f, 1.08f)

    fun update(visible: Boolean, dt: Float, options: RenderOptions) {
        val target = if (visible) 1f else 0f
        fade.update(visible, dt, options.reducedMotion)
        if (options.reducedMotion || !options.spring) {
            spring.snap(fade.value)
            return
        }
        spring.update(target, dt)
        // Collapsed slots cannot have negative height or rebound into an empty gap.
        if ((!visible && spring.value <= 0f) ||
            (abs(spring.value - target) < .0005f && abs(spring.velocity) < .005f)) spring.snap(target)
    }
}

/** All variants describe the same lyric rows, including duet and backing-vocal ordering. */
internal fun blendLyricLayouts(layers: List<LyricLayoutLayer>): List<GroupLayout> {
    if (layers.size == 1) return layers.single().groups
    val representative = layers.maxBy { it.opacity }.groups
    var top = 0f
    return representative.mapIndexed { index, group ->
        val heights = group.rows.indices.map { row ->
            layers.sumOf { (it.groups[index].rowHeights[row] * it.layoutWeight).toDouble() }.toFloat().coerceAtLeast(0f)
        }
        val gap = (group.height - group.rowHeights.sum()).coerceAtLeast(0f)
        val height = heights.sum() + gap
        GroupLayout(group.rows, top, height, heights).also { top += height }
    }
}
