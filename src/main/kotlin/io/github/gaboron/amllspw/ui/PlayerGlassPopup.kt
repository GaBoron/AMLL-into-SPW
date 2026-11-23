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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    var destination by remember { mutableStateOf(Rect.Zero) }
    val target = position.surface.toRect()
    val returning = entry.source != null && !entry.atPointer
    val frame = if (destination.width <= 0 || destination.height <= 0) from else if (opening)
        PlayerGlassMotion.open(from, destination, phase.value, density.density,
            if (returning) entry.anchor.toRect() else null)
    else PlayerGlassMotion.close(from, entry.anchor.toRect(), phase.value, returning, density.density)
    val current by rememberUpdatedState(frame)
    val expanded = entry.expanded && entry.content != null
    LaunchedEffect(expanded, position.surface, reducedMotion) {
        if (target.width > 0 && target.height > 0) {
            from = current
            destination = target
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
            Box(Modifier.width(entry.width.coerceAtMost(maxWidth.coerceAtLeast(1.dp)))
                .heightIn(max = maxHeight.coerceAtLeast(1.dp))
                .semantics { contentDescription = "${entry.label} 液态玻璃弹层" }, propagateMinConstraints = true) {
                val bounds = frame.outline.bounds
                Box(Modifier.matchParentSize().requiredSize(with(density) { bounds.width.coerceAtLeast(1f).toDp() },
                    with(density) { bounds.height.coerceAtLeast(1f).toDp() }).drawBackdrop(backdrop = backdrop,
                    // Read snapshot state inside the library's retained shape callback so its clip follows the rim.
                    shape = {
                        if (current.outline.merged) RoundedCornerShape(with(density) { current.radius.toDp() })
                        else GenericShape { _, _ -> addPath(current.outline.path, -current.outline.bounds.topLeft) }
                    },
                    effects = {
                        val optics = current.optics
                        val brightness = 1f + (if (dark) .8f else .1f) * optics
                        colorControls(brightness = (brightness - 1f) / 2f,
                            contrast = brightness, saturation = if (dark) 2.84f + (1.75f - 2.84f) * optics
                                else 2.08f + (2f - 2.08f) * optics)
                        blur(current.frost.dp.toPx())
                        if (current.outline.merged) lens(20.dp.toPx(), 60.dp.toPx())
                    },
                    highlight = { Highlight(1.3.dp, alpha = current.opacity,
                        style = if (current.outline.merged) HighlightStyle.Default(color = Color.White.copy(alpha = .27f))
                            else HighlightStyle.Plain(color = Color.White.copy(alpha = .27f))) },
                    shadow = { Shadow(29.dp, DpOffset(0.dp, 8.dp), Color.Black.copy(alpha = if (dark) .177f else .118f)) },
                    // Only the pane's bounds change. The source remains in window coordinates at a fixed scale.
                    layerBlock = {
                        translationX = current.outline.bounds.center.x - target.center.x
                        translationY = current.outline.bounds.center.y - target.center.y
                        alpha = current.opacity
                    }, onDrawSurface = {
                        drawRect(if (dark) Color(0xff444444).copy(alpha = .63f) else Color(0xfff9f9f9).copy(alpha = .55f))
                    }))
                // The platter changes aspect ratio, while labels/icons stay uniformly scaled and clipped to it.
                Box(Modifier.graphicsLayer {
                    scaleX = current.rect.width / size.width.coerceAtLeast(1f)
                    scaleY = scaleX
                    translationX = current.rect.center.x - target.center.x
                    translationY = current.rect.center.y - target.center.y
                    alpha = current.content
                    val sigma = current.contentBlur.dp.toPx()
                    renderEffect = if (sigma > .1f) BlurEffect(sigma, sigma) else null
                }.drawWithContent {
                    val scale = (current.rect.width / size.width.coerceAtLeast(1f)).coerceAtLeast(.001f)
                    val halfHeight = current.rect.height / scale / 2
                    val radius = current.radius / scale
                    val clip = Path().apply { addRoundRect(RoundRect(Rect(0f, size.height / 2 - halfHeight,
                        size.width, size.height / 2 + halfHeight), CornerRadius(radius))) }
                    clipPath(clip) { this@drawWithContent.drawContent() }
                }.padding(8.dp), propagateMinConstraints = true) { entry.content?.invoke() }
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
