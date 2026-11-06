package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import io.github.gaboron.amllspw.timeline.SpringMotion
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val LYRIC_CONTENT_SPRING_STIFFNESS = 180f
internal const val LYRIC_CONTENT_SPRING_DAMPING_RATIO = .42f

/** Absorb negative collapse travel in whitespace, approaching zero spacing without a hard stop. */
private fun compressedContentSpacing(spacing: Float, displacement: Float): Float {
    return if (spacing > 0f && displacement < 0f) spacing * exp(displacement / spacing) else spacing
}

internal data class LyricContentKey(val translation: Boolean, val romanization: Boolean)
/** Layout weights retain signed travel; content weights keep text slots at nonnegative expansion. */
internal data class LyricContentWeight(val key: LyricContentKey, val opacity: Float,
    val layoutWeight: Float = opacity, val contentWeight: Float = layoutWeight)
internal data class LyricLayoutLayer(val key: LyricContentKey, val opacity: Float, val groups: List<GroupLayout>,
    val layoutWeight: Float = opacity, val contentWeight: Float = layoutWeight)

/** Keep the original baseline aligned and draw each auxiliary line once as neighbouring slots move. */
internal fun lyricRowContent(layers: List<LyricLayoutLayer>, group: Int, row: Int): LyricRowContent {
    if (layers.size == 1) return layers.single().groups[group].rows[row].content
    val baseline = layers.sumOf {
        val layout = it.groups[group].rows[row]
        ((layout.rubyHeight + layout.main.firstBaseline) * it.contentWeight).toDouble()
    }.toFloat()
    var top = layers.sumOf {
        val layout = it.groups[group].rows[row]
        ((layout.rubyHeight + layout.main.size.height + 6f) * it.contentWeight).toDouble()
    }.toFloat()
    val auxiliary = buildList {
        for (translation in listOf(true, false)) {
            val candidates = layers.mapNotNull { layer ->
                layer.groups[group].rows[row].auxiliary.firstOrNull { it.translation == translation }
                    ?.let { it.layout to layer }
            }
            if (candidates.isEmpty()) continue
            val opacity = candidates.sumOf { it.second.opacity.toDouble() }.toFloat().coerceIn(0f, 1f)
            val height = candidates.sumOf { ((it.first.size.height + 5f) * it.second.contentWeight).toDouble() }
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
        val translationSlot = translation.expansion.coerceAtLeast(0f)
        val romanizationSlot = romanization.expansion.coerceAtLeast(0f)
        return buildList {
            for (showTranslation in listOf(false, true)) for (showRomanization in listOf(false, true)) {
                val opacity = (if (showTranslation) translation.opacity else 1f - translation.opacity) *
                    (if (showRomanization) romanization.opacity else 1f - romanization.opacity)
                val layoutWeight = (if (showTranslation) translation.expansion else 1f - translation.expansion) *
                    (if (showRomanization) romanization.expansion else 1f - romanization.expansion)
                val contentWeight = (if (showTranslation) translationSlot else 1f - translationSlot) *
                    (if (showRomanization) romanizationSlot else 1f - romanizationSlot)
                // A zero-alpha variant can still provide geometry for the spring's overshoot.
                if (opacity > 0f || layoutWeight != 0f)
                    add(LyricContentWeight(LyricContentKey(showTranslation, showRomanization), opacity,
                        layoutWeight, contentWeight))
            }
        }
    }
}

private class ContentVisibility(visible: Boolean) {
    private var wasVisible = visible
    private var returning = false
    private val criticalDamping = 2f * sqrt(LYRIC_CONTENT_SPRING_STIFFNESS)
    private val fade = ActivationEnvelope(if (visible) 1f else 0f,
        .28f, .28f, CubicBezier(.25f, .1f, .25f, 1f))
    private val spring = SpringMotion(if (visible) 1f else 0f, LYRIC_CONTENT_SPRING_STIFFNESS,
        criticalDamping * LYRIC_CONTENT_SPRING_DAMPING_RATIO)
    val opacity: Float get() = fade.value
    // Keep the closing overshoot negative so spacing compresses past rest before recovering.
    val expansion: Float get() = spring.value

    fun settled(visible: Boolean): Boolean {
        val target = if (visible) 1f else 0f
        return spring.value == target && spring.velocity == 0f && fade.value == target
    }

    fun update(visible: Boolean, dt: Float, options: RenderOptions) {
        val target = if (visible) 1f else 0f
        if (wasVisible != visible) {
            wasVisible = visible
            returning = false
            spring.configure(LYRIC_CONTENT_SPRING_STIFFNESS, criticalDamping * LYRIC_CONTENT_SPRING_DAMPING_RATIO)
        }
        fade.update(visible, dt, options.reducedMotion)
        if (options.reducedMotion || !options.spring) {
            returning = false
            spring.configure(LYRIC_CONTENT_SPRING_STIFFNESS, criticalDamping * LYRIC_CONTENT_SPRING_DAMPING_RATIO)
            spring.snap(fade.value)
            return
        }
        spring.update(target, dt)
        val displacement = spring.value - target
        val overshot = if (visible) displacement > 0f else displacement < 0f
        // Both directions get the same first rebound, then a critically damped return.
        // Keep the sampled position and velocity so the change in damping introduces no jump.
        if (!returning && overshot && displacement * spring.velocity <= 0f) {
            returning = true
            spring.configure(LYRIC_CONTENT_SPRING_STIFFNESS, criticalDamping)
        }
        // Once closing reaches zero, keep the ink hidden while the layout returns to rest.
        if (!visible && spring.value <= 0f) fade.update(false, 0f, true)
        if (abs(spring.value - target) < .0005f && abs(spring.velocity) < .005f) spring.snap(target)
    }
}

/** Keep preceding text intact while a footer's signed overshoot compresses its leading gap. */
@Composable
internal fun LyricContentVisibility(visible: Boolean, options: RenderOptions, compressionSpace: Dp = 0.dp,
    leadingContent: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val motion = remember { ContentVisibility(visible) }
    val currentOptions by rememberUpdatedState(options)
    var expansion by remember { mutableFloatStateOf(motion.expansion) }
    var opacity by remember { mutableFloatStateOf(motion.opacity) }
    LaunchedEffect(visible, options.spring, options.reducedMotion) {
        motion.update(visible, 0f, currentOptions)
        expansion = motion.expansion
        opacity = motion.opacity
        var previous = 0L
        while (!motion.settled(visible)) {
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0f else ((now - previous) / 1e9f).coerceIn(0f, .064f)
                previous = now
                motion.update(visible, dt, currentOptions)
                expansion = motion.expansion
                opacity = motion.opacity
            }
        }
    }
    Layout(content = {
        Box { leadingContent() }
        Box(Modifier.graphicsLayer { alpha = opacity }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) {
            if (visible || expansion != 0f || opacity != 0f) content()
        }
    }, modifier = Modifier.clipToBounds()) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val leading = measurables[0].measure(childConstraints)
        val child = measurables[1].measure(childConstraints)
        val space = compressionSpace.toPx().coerceIn(0f, leading.height.toFloat())
        val shift = compressedContentSpacing(space, child.height * expansion.coerceAtMost(0f)) - space
        val height = constraints.constrainHeight(
            (leading.height + shift + child.height * expansion.coerceAtLeast(0f)).roundToInt())
        val width = constraints.constrainWidth(maxOf(leading.width, child.width))
        layout(width, height) {
            leading.placeRelative((width - leading.width) / 2, shift.roundToInt())
            child.placeRelative(0, (leading.height + shift).roundToInt())
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
            layers.sumOf { (it.groups[index].rowHeights[row] * it.contentWeight).toDouble() }.toFloat().coerceAtLeast(0f)
        }
        val gap = (group.height - group.rowHeights.sum()).coerceAtLeast(0f)
        val signedHeight = layers.sumOf { layer ->
            (layer.groups[index].rowHeights.sum() * layer.layoutWeight).toDouble()
        }.toFloat()
        // Text slots stop at zero; the remaining signed travel squeezes the paragraph gap instead.
        val height = heights.sum() + compressedContentSpacing(gap, signedHeight - heights.sum())
        GroupLayout(group.rows, top, height, heights).also { top += height }
    }
}
