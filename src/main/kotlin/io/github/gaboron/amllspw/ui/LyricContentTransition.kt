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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val LYRIC_CONTENT_SPRING_STIFFNESS = 180f
internal const val LYRIC_CONTENT_SPRING_DAMPING_RATIO = .42f

/** Round both rest positions with a C2 connector, keeping reflected closing velocity continuous. */
private fun softenContentEndpoint(value: Float): Float {
    val endpoint = if (value < .5f) 0f else 1f
    val distance = value - endpoint
    val radius = .3f
    val magnitude = abs(distance)
    if (magnitude >= radius) return value
    val t = magnitude / radius
    // Matches value, slope and curvature at the linear boundary; slope and curvature are zero at rest.
    val rounded = radius * t * t * t * (6f + t * (-8f + 3f * t))
    return endpoint + if (distance < 0f) -rounded else rounded
}

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
    private var wasVisible = visible
    private var returning = false
    private var springEnabled = true
    private val criticalDamping = 2f * sqrt(LYRIC_CONTENT_SPRING_STIFFNESS)
    private val fade = ActivationEnvelope(if (visible) 1f else 0f,
        .28f, .28f, CubicBezier(.25f, .1f, .25f, 1f))
    private val spring = SpringMotion(if (visible) 1f else 0f, LYRIC_CONTENT_SPRING_STIFFNESS,
        criticalDamping * LYRIC_CONTENT_SPRING_DAMPING_RATIO)
    val opacity: Float get() = fade.value
    // The rounded zero crossing removes the impact from folding the closing rebound into positive height.
    val expansion: Float get() = if (springEnabled) abs(softenContentEndpoint(spring.value)).coerceAtMost(1.32f)
        else fade.value

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
        springEnabled = options.spring && !options.reducedMotion
        if (!springEnabled) {
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

/** Compose footer content uses the same reflected closing spring as the canvas lyrics. */
@Composable
internal fun LyricContentVisibility(visible: Boolean, options: RenderOptions, content: @Composable () -> Unit) {
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
    if (!visible && expansion == 0f && opacity == 0f) return
    Box(Modifier.clipToBounds().graphicsLayer { alpha = opacity }
        .layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            val height = constraints.constrainHeight((child.height * expansion).roundToInt())
            layout(child.width, height) { child.placeRelative(0, 0) }
        }.then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) {
        content()
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
