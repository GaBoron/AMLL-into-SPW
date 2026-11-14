// SPDX-License-Identifier: AGPL-3.0-only
// BouncingSlider styling and gestures ported from react-full at 832bad5f16ec2a7f96d06707ef927c9a4d3713d3.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.tanh

/** Eight-pixel resting rail, twenty-pixel hover rail, no separate thumb, and a soft twelve-pixel edge bounce. */
@Composable
internal fun PlayerSlider(value: Float, enabled: Boolean, label: String, reducedMotion: Boolean,
    modifier: Modifier = Modifier, gestureKey: Any? = Unit,
    onPreview: (Float?) -> Unit = {}, onCommit: (Float) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var overshoot by remember { mutableFloatStateOf(0f) }
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val height by animateFloatAsState(if (dragging || hovered) 20f else 8f,
        if (reducedMotion) snap() else if (dragging || hovered) tween(280) else spring(.42f, 200f))
    val bounce by animateFloatAsState(overshoot, if (reducedMotion) snap() else spring(.35f, 300f))
    Canvas(modifier.fillMaxWidth().height(24.dp).graphicsLayer { translationX = bounce.dp.toPx() }
        .hoverable(interactions, enabled).pointerInput(enabled, gestureKey) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                dragging = true
                var position = (down.position.x / size.width).coerceIn(0f, 1f)
                preview(position)
                try {
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Compose cancels a removed pointer node with consumed up changes.
                        // Only an actual release may commit a seek or volume change.
                        if (change.isConsumed) break
                        if (event.changes.count { it.pressed } > 1) break
                        val raw = change.position.x / size.width
                        position = raw.coerceIn(0f, 1f)
                        overshoot = when { raw < 0 -> tanh(raw * 2) * 12; raw > 1 -> tanh((raw - 1) * 2) * 12; else -> 0f }
                        preview(position)
                        change.consume()
                        if (!change.pressed) { released = true; break }
                    }
                    if (released) commit(position)
                } finally {
                    dragging = false
                    overshoot = 0f
                    preview(null)
                }
            }
        }.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { commit((value - .01f).coerceAtLeast(0f)); true }
                Key.DirectionRight -> { commit((value + .01f).coerceAtMost(1f)); true }
                else -> false
            }
        }.focusable(enabled).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
            if (!enabled) disabled()
            setProgress { if (enabled) { commit(it.coerceIn(0f, 1f)); true } else false }
        }) {
        val railHeight = height.dp.toPx()
        val top = (size.height - railHeight) / 2
        val bounds = androidx.compose.ui.geometry.Rect(0f, top, size.width, top + railHeight)
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(bounds, CornerRadius(railHeight / 2))) }
        clipPath(path) {
            drawRect(Color.White.copy(alpha = if (enabled) .149f else .08f))
            drawRect(Color.White.copy(alpha = if (enabled) .4f else .1f), size = Size(size.width * value.coerceIn(0f, 1f), size.height))
        }
    }
}
