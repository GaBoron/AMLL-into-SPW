package io.github.gaboron.amllspw.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import com.kyant.backdrop.effects.vibrancy

internal class PlayerGlassPopupEntry(val label: String, val dismiss: () -> Unit) {
    var anchor by mutableStateOf(IntRect.Zero)
    var expanded by mutableStateOf(false)
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** The popup is a sibling of the recorded backdrop, so it can never sample itself. */
@Stable
internal class PlayerGlassPopups {
    var current by mutableStateOf<PlayerGlassPopupEntry?>(null)
        private set

    fun show(entry: PlayerGlassPopupEntry) {
        if (current !== entry) current?.dismiss?.invoke()
        current = entry
    }

    fun dismiss() { current?.dismiss?.invoke() }

    fun finished(entry: PlayerGlassPopupEntry) {
        entry.content = null
        if (current === entry) current = null
    }
}

internal val LocalPlayerGlassPopups = staticCompositionLocalOf<PlayerGlassPopups?> { null }

@Composable
internal fun PlayerGlassPopupHost(host: PlayerGlassPopups, backdrop: Backdrop, reducedMotion: Boolean) {
    val entry = host.current ?: return
    key(entry) { PlayerGlassPopup(entry, host, backdrop, reducedMotion) }
}

@Composable
private fun PlayerGlassPopup(entry: PlayerGlassPopupEntry, host: PlayerGlassPopups, backdrop: Backdrop, reducedMotion: Boolean) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = entry.expanded && entry.content != null
    if (!visible.currentState && !visible.targetState) {
        if (!entry.expanded) SideEffect { host.finished(entry) }
        return
    }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val maxHeight = if (window.height > 0) with(density) { (window.height * .78f).toDp() } else 480.dp
    val position = remember(entry, density) { GlassPopupPosition(entry, with(density) { 14.dp.roundToPx() }) }
    Popup(position, onDismissRequest = entry.dismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(if (reducedMotion) 0 else 180)) + scaleIn(
                spring(dampingRatio = .8f, stiffness = 420f), initialScale = if (reducedMotion) 1f else .88f,
                transformOrigin = TransformOrigin(1f, if (position.above) 1f else 0f)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 180)) + scaleOut(
                tween(if (reducedMotion) 0 else 220), targetScale = if (reducedMotion) 1f else .94f,
                transformOrigin = TransformOrigin(1f, if (position.above) 1f else 0f))) {
            Box(Modifier.widthIn(min = 240.dp, max = 420.dp).heightIn(max = maxHeight)
                .drawBackdrop(backdrop = backdrop, shape = { RoundedCornerShape(24.dp) },
                    effects = {
                        vibrancy()
                        blur(3.dp.toPx())
                        lens(14.dp.toPx(), 24.dp.toPx())
                    }, onDrawSurface = { drawRect(Color(0xff162032).copy(alpha = .28f)) })
                .semantics { contentDescription = "${entry.label} 液态玻璃弹层" }
                .padding(10.dp)) { entry.content?.invoke() }
        }
    }
}

private class GlassPopupPosition(private val entry: PlayerGlassPopupEntry, private val margin: Int) : PopupPositionProvider {
    var above by mutableStateOf(false)

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val anchor = entry.anchor
        above = anchor.bottom + margin + popupContentSize.height > windowSize.height - margin
        val x = (anchor.right - popupContentSize.width).coerceIn(margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = (if (above) anchor.top - popupContentSize.height - margin else anchor.bottom + margin)
            .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}
