package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.*

internal class LyricPointerState {
    var position by mutableStateOf<Offset?>(null)
    var pressed by mutableStateOf(false)
}

internal fun Modifier.lyricHover(state: LyricPointerState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Exit) { state.position = null; state.pressed = false }
            else event.changes.firstOrNull { it.type == PointerType.Mouse }?.let {
                state.position = it.position; state.pressed = it.pressed
            }
        }
    }
}

internal fun DrawScope.paintRowHover(bounds: Rect, alpha: Float, pressed: Boolean, emPx: Float) {
    if (alpha <= .001f) return
    drawRoundRect(Color.White.copy(alpha = alpha.coerceIn(0f, 1f) * if (pressed) .02f else 1f / 15),
        bounds.topLeft, bounds.size, CornerRadius(emPx * .25f))
}
