package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs
import kotlin.math.sign

/** Compose's scrollable handles touch/pen but excludes Mouse, including AWT's emulated contacts. */
internal fun Modifier.lyricPointerScroll(
    state: ScrollableState,
    reverseDirection: Boolean = false,
): Modifier = pointerInput(state, reverseDirection) {
    val direction = if (reverseDirection) -1f else 1f
    while (true) {
        val start = awaitPointerEventScope {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse || currentEvent.buttons.isSecondaryPressed) {
                return@awaitPointerEventScope null
            }
            var result: PointerScrollStart? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.singleOrNull() ?: break
                if (change.id != down.id || !change.pressed || change.isConsumed ||
                    !event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed) break
                val distance = change.position.y - down.position.y
                if (abs(distance) > viewConfiguration.touchSlop) {
                    change.consume()
                    result = PointerScrollStart(change.id, distance - sign(distance) * viewConfiguration.touchSlop)
                    break
                }
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
            }
            result
        } ?: continue

        state.scroll(MutatePriority.UserInput) {
            scrollBy(start.delta * direction)
            awaitPointerEventScope {
                while (currentEvent.changes.any { it.id == start.id && it.pressed }) {
                    val event = awaitPointerEvent()
                    val change = event.changes.singleOrNull() ?: break
                    if (change.id != start.id || change.isConsumed || event.buttons.isSecondaryPressed) break
                    val delta = change.positionChange().y
                    change.consume()
                    scrollBy(delta * direction)
                }
            }
        }
    }
}

private data class PointerScrollStart(val id: PointerId, val delta: Float)
