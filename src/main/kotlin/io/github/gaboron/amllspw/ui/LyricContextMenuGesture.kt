package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

/** Observes presses before child tap handlers, taking ownership only when a menu is requested. */
@Composable
internal fun Modifier.lyricContextMenuGesture(contentKey: Any?, onOpen: (Offset) -> Unit): Modifier {
    val open by rememberUpdatedState(onOpen)
    return pointerInput(contentKey) {
        awaitEachGesture {
            var event: PointerEvent
            do {
                event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.type != PointerEventType.Press)

            if (event.changes.size != 1) return@awaitEachGesture
            val down = event.changes.single()
            val point = when {
                event.buttons.isSecondaryPressed -> down.position
                down.changedToDownIgnoreConsumed() &&
                    (down.type != PointerType.Mouse || event.buttons.isPrimaryPressed) -> awaitMenuHold(down)
                else -> null
            } ?: return@awaitEachGesture

            currentEvent.changes.forEach { it.consume() }
            open(point)
            // Claim the release too, so opening the menu cannot seek or change a poem.
            while (currentEvent.changes.any { it.pressed }) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
}

/** A desktop host may report touch and pen contact as Mouse, so primary holds work for all types. */
private suspend fun AwaitPointerEventScope.awaitMenuHold(down: PointerInputChange): Offset? {
    var secondaryAnchor: Offset? = null
    val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.singleOrNull() ?: break
            if (change.id != down.id || change.isConsumed || event.type == PointerEventType.Exit) break
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                secondaryAnchor = change.position
                break
            }
            if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.position.x !in 0f..size.width.toFloat() ||
                change.position.y !in 0f..size.height.toFloat()) break

            // Tap handlers consume the initial down, which is intentionally ignored. Subsequent
            // consumption belongs to scrolling or another gesture and cancels this hold.
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
        }
        true
    }
    return if (finished == null) down.position else secondaryAnchor
}
