// SPDX-License-Identifier: AGPL-3.0-only
// Submenu motion adapted from Prism UI popup.dart, revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.roundToInt

internal class PlayerGlassSubmenuHost(val backdrop: Backdrop, val expanded: () -> Boolean, val settled: () -> Boolean) {
    var topBounds by mutableStateOf<Rect?>(null)
    var topOutline: (() -> RoundRect)? = null
    val drawCardStack: Boolean get() = topBounds != null && expanded() && settled()
}

internal val LocalPlayerGlassSubmenuHost = staticCompositionLocalOf<PlayerGlassSubmenuHost?> { null }
internal val LocalPlayerGlassReducedMotion = staticCompositionLocalOf { false }

internal data class GlassMenuTrigger(val bounds: Rect, val id: Any? = null)
internal data class GlassMenuPage<T>(val key: Any, val data: T, val trigger: GlassMenuTrigger? = null)
internal data class GlassMenuLayerState(val active: Boolean, val acceptsBack: Boolean, val covered: Any?,
    val unfoldProgress: () -> Float)

internal class GlassMenuLevel {
    var coordinates: LayoutCoordinates? = null
    var left by mutableIntStateOf(0)
    var top by mutableIntStateOf(0)

    fun trigger(row: LayoutCoordinates?, id: Any? = null): GlassMenuTrigger? {
        val card = coordinates ?: return null
        if (!card.isAttached || row?.isAttached != true) return null
        return GlassMenuTrigger(card.localBoundingBoxOf(row, clipBounds = false), id)
    }
}

internal object GlassSubmenuMotion {
    // Prism specifies duration and bounce. Compose's spring uses stiffness and damping ratio.
    private fun stiffness(seconds: Float) = (2 * PI / seconds).let { (it * it).toFloat() }
    val unfold = spring(.85f, stiffness(.42f), .001f)
    val fold = spring(1f, stiffness(.34f), .001f)

    fun items(progress: Float) = ((progress - .05f) / .35f).coerceIn(0f, 1f)
    fun parentScale(depth: Int, progress: Float) = (1f - .05f * distance(depth, progress)).coerceAtLeast(.65f)
    fun parentDim(depth: Int, progress: Float) = (.2f * distance(depth, progress)).coerceAtMost(.4f)

    fun parentOutline(card: Rect, openerTop: Float, scale: Float, density: Float): RoundRect {
        val pivot = Offset(card.right, openerTop.coerceIn(card.top, card.bottom))
        return RoundRect(Rect(pivot + (card.topLeft - pivot) * scale, pivot + (card.bottomRight - pivot) * scale),
            CornerRadius(24f * density * scale))
    }

    private fun distance(depth: Int, progress: Float) = (depth - 1 + progress.coerceIn(0f, 1f)).coerceAtLeast(0f)

    fun outline(origin: Rect, card: Rect, progress: Float, density: Float): RoundRect {
        fun mix(start: Float, end: Float, amount: Float) = start + (end - start) * amount
        val width = (progress / .3f).coerceIn(0f, 1f)
        val top = mix(origin.top, card.top, progress)
        val rect = Rect(mix(origin.left, card.left, width), top,
            mix(origin.right, card.right, width), mix(origin.bottom, card.bottom, progress).coerceAtLeast(top + 1f))
        return RoundRect(rect, CornerRadius(mix(12f, 24f, progress).coerceAtLeast(0f) * density))
    }

    fun stackExtent(parent: Int, full: Int, progress: Float): Int =
        parent + ((full - parent) * progress.coerceIn(0f, 1f)).roundToInt()
}

/** Keeps the parent laid out while the top card unfolds from its opener or folds back to it. */
@Composable
internal fun <T> PlayerGlassSubmenus(pages: List<GlassMenuPage<T>>,
    content: @Composable (data: T, level: GlassMenuLevel, state: GlassMenuLayerState) -> Unit) {
    val host = LocalPlayerGlassSubmenuHost.current
    val reducedMotion = LocalPlayerGlassReducedMotion.current
    val density = LocalDensity.current.density
    val inset = with(LocalDensity.current) { 8.dp.roundToPx() }
    val maxGrowth = with(LocalDensity.current) { 40.dp.roundToPx() }
    val progress = remember { Animatable(1f) }
    val keys = pages.map { it.key }
    val latest = remember { mutableMapOf<Any, GlassMenuPage<T>>() }
    var mounted by remember { mutableStateOf(keys) }
    var speed by remember { mutableFloatStateOf(0f) }
    val desired = pages.associateBy { it.key }
    SideEffect {
        latest.putAll(desired)
        latest.keys.retainAll(mounted.toSet() + keys)
    }
    DisposableEffect(host) { onDispose {
        host?.topBounds = null
        host?.topOutline = null
    } }
    LaunchedEffect(keys, reducedMotion) {
        if (reducedMotion || mounted.firstOrNull() != keys.firstOrNull()) {
            progress.snapTo(1f)
            mounted = keys
            speed = 0f
            return@LaunchedEffect
        }
        val common = mounted.zip(keys).takeWhile { (old, next) -> old == next }.size
        while (mounted.size > common) {
            progress.animateTo(0f, GlassSubmenuMotion.fold, speed) { speed = velocity }
            mounted = mounted.dropLast(1)
            progress.snapTo(1f)
            speed = 0f
        }
        if (mounted.size < keys.size) {
            progress.snapTo(0f)
            mounted = keys
        }
        progress.animateTo(1f, GlassSubmenuMotion.unfold, speed) { speed = velocity }
        speed = 0f
    }

    val visible = mounted.mapNotNull { desired[it] ?: latest[it] }
    val geometry = remember(mounted) { mounted.associateWith { GlassMenuLevel() } }
    val dark = LocalPlayerGlassDark.current
    Layout(content = {
        visible.forEachIndexed { index, page -> key(page.key) {
            val level = geometry.getValue(page.key)
            val trigger = page.trigger
            val parent = visible.getOrNull(index - 1)?.let { geometry.getValue(it.key) }
            val child = visible.getOrNull(index + 1)
            val top = child == null
            val openerTop = child?.trigger?.bounds?.top ?: 0f
            val depth = visible.lastIndex - index
            val interactive = top && host?.expanded?.invoke() != false
            val state = GlassMenuLayerState(interactive && page.key == keys.lastOrNull(), interactive,
                child?.trigger?.id) { if (top && parent != null) progress.value else 1f }
            var size by remember { mutableStateOf(IntSize.Zero) }
            val frame: (Rect) -> RoundRect = { bounds ->
                if (parent != null && top && host?.expanded?.invoke() != false) {
                    val source = trigger?.bounds ?: Rect(inset.toFloat(), inset.toFloat(),
                        (bounds.right - inset).coerceAtLeast(inset.toFloat()), inset + 44f * density)
                    GlassSubmenuMotion.outline(source.translate(Offset((parent.left - level.left).toFloat(),
                        (parent.top - level.top).toFloat())),
                        bounds, progress.value, density)
                } else RoundRect(bounds, CornerRadius(24f * density))
            }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { level.coordinates = it }
                .onSizeChanged { size = it }
                .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}), propagateMinConstraints = true) {
                if (host?.drawCardStack == true) {
                    // Move the glass edges in window coordinates so the sampled background stays at full scale.
                    val glassFrame: (Rect) -> RoundRect = if (top) frame else { bounds ->
                        GlassSubmenuMotion.parentOutline(bounds, openerTop,
                            GlassSubmenuMotion.parentScale(depth, progress.value), density)
                    }
                    GlassSubmenuPane(host.backdrop, dark, size, glassFrame,
                        optics = { if (top && parent != null) (progress.value / .35f).coerceIn(0f, 1f) else 1f },
                        shadow = { if (top && parent != null) progress.value.coerceIn(0f, 1f) else .65f })
                }
                Box(Modifier.graphicsLayer {
                    if (!top) {
                        val scale = GlassSubmenuMotion.parentScale(depth, progress.value)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(1f, (openerTop / this.size.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
                    }
                    if (host?.drawCardStack == true) {
                        val outline = frame(Rect(Offset.Zero, this.size))
                        shape = GenericShape { _, _ -> addRoundRect(outline) }
                        clip = true
                    }
                }.drawWithContent {
                    drawContent()
                    if (!top && host?.drawCardStack == true) drawRect(if (dark) Color.Black else Color.White,
                        alpha = GlassSubmenuMotion.parentDim(depth, progress.value))
                }.padding(8.dp), propagateMinConstraints = true) {
                    content(page.data, level, state)
                }
            }
        } }
    }) { measurables, constraints ->
        var cardWidth = constraints.minWidth
        val cards = measurables.mapIndexed { index, measurable ->
            if (index > 0) cardWidth = minOf((cardWidth * 1.12f).roundToInt(), cardWidth + maxGrowth, constraints.maxWidth)
            measurable.measure(constraints.copy(minWidth = cardWidth, maxWidth = cardWidth, minHeight = 0))
        }
        var height = 0
        val offsets = cards.mapIndexed { index, card ->
            val previous = if (index == 0) 0 else geometry.getValue(visible[index - 1].key).top
            val opener = visible[index].trigger?.bounds?.top?.roundToInt() ?: inset
            val y = (previous + opener - inset).coerceIn(0, (constraints.maxHeight - card.height).coerceAtLeast(0))
            geometry.getValue(visible[index].key).top = y
            height = maxOf(height, y + card.height)
            y
        }
        val width = if (cards.size < 2) cardWidth else
            GlassSubmenuMotion.stackExtent(cards[cards.lastIndex - 1].width, cardWidth, progress.value / .3f)
        cards.forEachIndexed { index, card -> geometry.getValue(visible[index].key).left = width - card.width }
        if (host != null) {
            val card = cards.lastOrNull()?.takeIf { cards.size > 1 }
            host.topBounds = card?.let { Rect((width - it.width).toFloat(), offsets.last().toFloat(),
                width.toFloat(), (offsets.last() + it.height).toFloat()) }
            val topBounds = host.topBounds
            val anchor = visible.lastOrNull()?.trigger?.bounds
            host.topOutline = if (topBounds == null || anchor == null) null else {
                val index = offsets.lastIndex - 1
                val origin = anchor.translate(Offset((width - cards[index].width).toFloat(), offsets[index].toFloat()))
                val outline: () -> RoundRect = { GlassSubmenuMotion.outline(origin, topBounds, progress.value, density) }
                outline
            }
        }
        val displayedHeight = if (cards.size < 2) height else {
            val parentHeight = (0 until cards.lastIndex).maxOf { offsets[it] + cards[it].height }
            GlassSubmenuMotion.stackExtent(parentHeight, height, progress.value)
        }
        layout(width, displayedHeight.coerceAtLeast(constraints.minHeight)) {
            cards.forEachIndexed { index, card ->
                card.placeWithLayer(width - card.width, offsets[index]) {
                    alpha = if (index < cards.lastIndex && host?.expanded?.invoke() == false) 0f else 1f
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GlassSubmenuPane(backdrop: Backdrop, dark: Boolean, size: IntSize, frame: (Rect) -> RoundRect,
    optics: () -> Float, shadow: () -> Float) {
    if (size.width <= 0 || size.height <= 0) return
    val bounds = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
    val outline = frame(bounds)
    val rect = outline.boundingRect
    val density = LocalDensity.current
    val translation = rect.center - bounds.center
    val radius = with(density) { outline.topLeftCornerRadius.x.toDp() }
    Box(Modifier.matchParentSize().requiredSize(with(density) { rect.width.coerceAtLeast(1f).toDp() },
        with(density) { rect.height.coerceAtLeast(1f).toDp() })
        .playerGlassSurface(backdrop, dark, shape = { RoundedCornerShape(radius) }, optics = optics,
            frost = { 12f }, opacity = { 1f }, shadowAlpha = shadow, layer = {
                translationX = translation.x
                translationY = translation.y
            }))
}
