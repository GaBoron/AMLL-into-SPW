package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.*

private const val HOVER_IDLE_TIMEOUT_NANOS = 3_000_000_000L

internal class LyricPointerState {
    var position by mutableStateOf<Offset?>(null)
        private set
    var pressed by mutableStateOf(false)
        private set

    private var lastPosition: Offset? = null
    private var lastActivityNanos = 0L

    fun onMouseInput(position: Offset, pressed: Boolean, scrolled: Boolean = false,
        nowNanos: Long = System.nanoTime()) {
        // Keep the physical position after expiry so repeated stationary events cannot
        // reactivate the highlight as lyrics move beneath the pointer.
        val active = position != lastPosition || pressed != this.pressed || scrolled
        lastPosition = position
        this.pressed = pressed
        if (active) {
            this.position = position
            lastActivityNanos = nowNanos
        }
    }

    fun expireHover(nowNanos: Long = System.nanoTime()) {
        if (position != null && !pressed && nowNanos - lastActivityNanos >= HOVER_IDLE_TIMEOUT_NANOS) {
            position = null
        }
    }

    fun reset() {
        position = null
        pressed = false
        lastPosition = null
    }
}

internal fun Modifier.lyricHover(state: LyricPointerState): Modifier = pointerInput(state) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Exit) {
                    state.reset()
                } else {
                    event.changes.firstOrNull { it.type == PointerType.Mouse }?.let {
                        state.onMouseInput(it.position, it.pressed, event.type == PointerEventType.Scroll)
                    }
                }
            }
        }
    } finally {
        state.reset()
    }
}

internal fun DrawScope.paintRowHover(bounds: Rect, alpha: Float, pressed: Boolean, emPx: Float) {
    if (alpha <= .001f) return
    drawRoundRect(Color.White.copy(alpha = alpha.coerceIn(0f, 1f) * if (pressed) .02f else 1f / 15),
        bounds.topLeft, bounds.size, CornerRadius(emPx * .25f))
}
