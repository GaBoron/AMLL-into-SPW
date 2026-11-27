// SPDX-License-Identifier: AGPL-3.0-only
// Menu material and motion adapted from Prism UI (chen08209), revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

internal class PlayerGlassPopupEntry(val label: String, val dismiss: () -> Unit) {
    var anchor by mutableStateOf(IntRect.Zero)
    var expanded by mutableStateOf(false)
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var source: (@Composable () -> Unit)? = null
    var sourceHidden by mutableStateOf(false)
    var atPointer: Boolean = false
    var keepOnTrackChange: Boolean = false
    var width: Dp = 280.dp
    var hasSubmenus: Boolean = false
}

/** The popup is a sibling of the recorded backdrop, so it can never sample itself. */
@Stable
internal class PlayerGlassPopups {
    val entries = mutableStateListOf<PlayerGlassPopupEntry>()
    val active: Boolean get() = entries.isNotEmpty()
    var current by mutableStateOf<PlayerGlassPopupEntry?>(null)
        private set

    fun show(entry: PlayerGlassPopupEntry) {
        if (current !== entry) current?.dismiss?.invoke()
        if (entry !in entries) entries += entry
        current = entry
    }

    fun dismiss() { entries.toList().forEach { it.dismiss() } }

    fun dismissForTrackChange() {
        entries.filterNot { it.keepOnTrackChange }.forEach { it.dismiss() }
    }

    fun finished(entry: PlayerGlassPopupEntry) {
        entry.sourceHidden = false
        entry.content = null
        entries.remove(entry)
        if (current === entry) current = null
    }
}

internal val LocalPlayerGlassPopups = staticCompositionLocalOf<PlayerGlassPopups?> { null }
internal val LocalPlayerGlassDark = staticCompositionLocalOf { true }

@Composable
internal fun PlayerGlassPopupHost(host: PlayerGlassPopups, backdrop: Backdrop, reducedMotion: Boolean) {
    for (entry in host.entries) key(entry) { PlayerGlassPopup(entry, host, backdrop, reducedMotion) }
}

@Composable
private fun PlayerGlassPopup(entry: PlayerGlassPopupEntry, host: PlayerGlassPopups, backdrop: Backdrop, reducedMotion: Boolean) {
    DisposableEffect(entry) { onDispose { host.finished(entry) } }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val maxHeight = if (window.height > 0) with(density) { window.height.toDp() - 32.dp } else 480.dp
    val maxWidth = if (window.width > 0) with(density) { window.width.toDp() - 32.dp } else 420.dp
    val position = remember(entry, density) { GlassPopupPosition(entry, with(density) { 16.dp.roundToPx() },
        with(density) { 40.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }
    val phase = remember { Animatable(0f) }
    var opening by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(PlayerGlassMotion.source(entry.anchor.toRect())) }
    val target = position.surface.toRect()
    val submenuHost = remember(entry, backdrop) {
        PlayerGlassSubmenuHost(backdrop, { entry.expanded }, { opening && phase.value >= 1f })
    }
    val expanded = entry.expanded && entry.content != null
    val stackOwnsSurface = submenuHost.drawCardStack
    val contentTarget = if (expanded && opening) target else
        submenuHost.topBounds?.translate(target.topLeft) ?: target
    val measured = target.width > 0 && target.height > 0
    val returning = entry.source != null && !entry.atPointer
    val frame = when {
        !measured -> from
        !opening -> PlayerGlassMotion.close(from, entry.anchor.toRect(), phase.value, returning, density.density)
        !expanded && phase.value >= 1f && submenuHost.topBounds != null -> {
            val visible = submenuHost.topOutline?.invoke()
            PlayerGlassFrame(visible?.boundingRect?.translate(target.topLeft) ?: contentTarget,
                visible?.topLeftCornerRadius?.x ?: 24f * density.density, 1f, 1f)
        }
        else -> PlayerGlassMotion.open(from, target, phase.value, density.density,
            if (returning) entry.anchor.toRect() else null)
    }
    val current by rememberUpdatedState(frame)
    // Only the popup lifecycle starts an opening or closing morph.
    LaunchedEffect(expanded, measured, reducedMotion) {
        if (measured) {
            from = current
            opening = expanded
            entry.sourceHidden = returning
            phase.snapTo(0f)
            phase.animateTo(1f, tween(if (reducedMotion) 0 else if (expanded) 450 else if (returning) 500 else 200,
                easing = LinearEasing))
            if (!expanded) host.finished(entry)
        }
    }
    val dark = LocalPlayerGlassDark.current
    Popup(position, onDismissRequest = entry.dismiss, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        // Shadow/overshoot space is outside the card, and excluded from anchor alignment.
        Box(Modifier.padding(40.dp)) {
            val widthLimit = maxWidth.coerceAtLeast(1.dp)
            val baseWidth = entry.width.coerceAtMost(widthLimit)
            val widthModifier = if (entry.hasSubmenus) Modifier.widthIn(min = baseWidth, max = widthLimit)
                else Modifier.width(baseWidth)
            Box(widthModifier
                .heightIn(max = maxHeight.coerceAtLeast(1.dp))
                .semantics { contentDescription = "${entry.label} 液态玻璃弹层" }, propagateMinConstraints = true) {
                if (!stackOwnsSurface) {
                    val bounds = frame.outline.bounds
                    Box(Modifier.matchParentSize().requiredSize(with(density) { bounds.width.coerceAtLeast(1f).toDp() },
                        with(density) { bounds.height.coerceAtLeast(1f).toDp() }).playerGlassSurface(backdrop, dark,
                        shape = {
                            if (current.outline.merged) RoundedCornerShape(with(density) { current.radius.toDp() })
                            else GenericShape { _, _ -> addPath(current.outline.path, -current.outline.bounds.topLeft) }
                        }, optics = { current.optics }, frost = { current.frost }, opacity = { current.opacity },
                        merged = { current.outline.merged }, layer = {
                            translationX = current.outline.bounds.center.x - target.center.x
                            translationY = current.outline.bounds.center.y - target.center.y
                            alpha = current.opacity
                        }))
                }
                // The platter changes aspect ratio, while labels/icons stay uniformly scaled and clipped to it.
                val morph = if (stackOwnsSurface) Modifier else Modifier.graphicsLayer {
                    val pivot = contentTarget.center - target.topLeft
                    transformOrigin = TransformOrigin(pivot.x / size.width.coerceAtLeast(1f),
                        pivot.y / size.height.coerceAtLeast(1f))
                    scaleX = current.rect.width / contentTarget.width.coerceAtLeast(1f)
                    scaleY = scaleX
                    translationX = current.rect.center.x - contentTarget.center.x
                    translationY = current.rect.center.y - contentTarget.center.y
                    alpha = current.content
                    val sigma = current.contentBlur.dp.toPx()
                    renderEffect = if (sigma > .1f) BlurEffect(sigma, sigma) else null
                }.drawWithContent {
                    val scale = (current.rect.width / contentTarget.width.coerceAtLeast(1f)).coerceAtLeast(.001f)
                    val center = contentTarget.center - target.topLeft
                    val extent = Offset(current.rect.width, current.rect.height) / (2 * scale)
                    val radius = current.radius / scale
                    val clip = Path().apply { addRoundRect(RoundRect(Rect(center - extent, center + extent), CornerRadius(radius))) }
                    clipPath(clip) { this@drawWithContent.drawContent() }
                }
                Box(morph.then(if (entry.hasSubmenus) Modifier else Modifier.padding(8.dp)),
                    propagateMinConstraints = true) {
                    CompositionLocalProvider(LocalPlayerGlassSubmenuHost provides submenuHost,
                        LocalPlayerGlassReducedMotion provides reducedMotion) {
                        entry.content?.invoke()
                    }
                }
                if (returning && target.width > 0f && target.height > 0f && frame.glyph != null && frame.glyphOpacity > 0f) {
                    Box(Modifier.matchParentSize().requiredSize(with(density) { entry.anchor.width.toDp() },
                        with(density) { entry.anchor.height.toDp() }).graphicsLayer {
                        val glyph = current.glyph ?: return@graphicsLayer
                        scaleX = glyph.width / entry.anchor.width.coerceAtLeast(1)
                        scaleY = glyph.height / entry.anchor.height.coerceAtLeast(1)
                        translationX = glyph.center.x - target.center.x
                        translationY = glyph.center.y - target.center.y
                        alpha = current.glyphOpacity
                    }, contentAlignment = Alignment.Center) { entry.source?.invoke() }
                }
            }
        }
    }
}

internal fun Modifier.playerGlassSurface(backdrop: Backdrop, dark: Boolean, shape: () -> Shape,
    optics: () -> Float, frost: () -> Float, opacity: () -> Float, merged: () -> Boolean = { true },
    shadowAlpha: () -> Float = { 1f }, layer: GraphicsLayerScope.() -> Unit = {}): Modifier =
    drawBackdrop(backdrop = backdrop, shape = shape, effects = {
        val strength = optics()
        val brightness = 1f + (if (dark) .8f else .1f) * strength
        colorControls(brightness = (brightness - 1f) / 2f, contrast = brightness,
            saturation = if (dark) 2.84f + (1.75f - 2.84f) * strength else 2.08f + (2f - 2.08f) * strength)
        blur(frost().dp.toPx())
        if (merged()) lens(20.dp.toPx(), 60.dp.toPx())
    }, highlight = { Highlight(1.3.dp, alpha = opacity(),
        style = if (merged()) HighlightStyle.Default(color = Color.White.copy(alpha = .27f))
            else HighlightStyle.Plain(color = Color.White.copy(alpha = .27f))) },
        shadow = { Shadow(29.dp, DpOffset(0.dp, 8.dp),
            Color.Black.copy(alpha = (if (dark) .177f else .118f) * shadowAlpha())) },
        layerBlock = layer, onDrawSurface = {
            drawRect(if (dark) Color(0xff444444).copy(alpha = .63f) else Color(0xfff9f9f9).copy(alpha = .55f))
        })

internal class GlassPopupPosition(private val entry: PlayerGlassPopupEntry, private val margin: Int,
    private val gutter: Int, private val overlap: Int) : PopupPositionProvider {
    var surface by mutableStateOf(IntRect.Zero)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val anchor = entry.anchor
        val width = (popupContentSize.width - gutter * 2).coerceAtLeast(1)
        val height = (popupContentSize.height - gutter * 2).coerceAtLeast(1)
        val x = (if (entry.atPointer) {
            if (anchor.left + width <= windowSize.width - margin) anchor.left else anchor.left - width
        } else anchor.right - width).coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin))
        val y = (if (entry.atPointer) {
            if (anchor.top + height <= windowSize.height - margin) anchor.top else anchor.top - height
        } else anchor.top - overlap).coerceIn(margin, (windowSize.height - height - margin).coerceAtLeast(margin))
        surface = IntRect(x, y, x + width, y + height)
        return IntOffset(x - gutter, y - gutter)
    }
}
