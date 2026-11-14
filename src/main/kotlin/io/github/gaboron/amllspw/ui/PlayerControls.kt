// SPDX-License-Identifier: AGPL-3.0-only
// Controls and responsive sizes ported from react-full/PrebuiltLyricPlayer and MediaButton,
// revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3 (SteveXMH and AMLL contributors).
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.timeline.PlaybackClock
import kotlinx.coroutines.delay

@Composable
internal fun PlayerControls(presentation: LyricsPresentation, style: AmllPlayerStyle, options: RenderOptions,
    modifier: Modifier, onPlayback: () -> PlaybackClock.Sample, onSeek: (Long) -> Unit,
    onTransport: (String) -> Unit, readVolume: () -> Float?, onVolume: (Float) -> Unit,
    readMode: () -> String?, onMode: (String) -> Unit, bottomControls: @Composable () -> Unit = {},
    info: @Composable () -> Unit) {
    val readPlayback by rememberUpdatedState(onPlayback)
    val volumeReader by rememberUpdatedState(readVolume)
    val modeReader by rememberUpdatedState(readMode)
    var playback by remember(presentation.trackKey) { mutableStateOf(readPlayback()) }
    var volume by remember(presentation.trackKey) { mutableStateOf(volumeReader()) }
    var mode by remember(presentation.trackKey) { mutableStateOf(modeReader()) }
    var dragging by remember(presentation.trackKey) { mutableStateOf<Float?>(null) }
    var volumeDrag by remember(presentation.trackKey) { mutableStateOf<Float?>(null) }
    var remaining by remember { mutableStateOf(true) }
    LaunchedEffect(presentation.trackKey) {
        while (true) {
            playback = readPlayback()
            volume = volumeReader()
            mode = modeReader()
            delay(100)
        }
    }
    val duration = presentation.durationMs.coerceAtLeast(0)
    val progress = dragging ?: if (duration > 0) (playback.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val enabled = presentation.trackKey.isNotBlank()
    val transportEnabled = enabled && presentation.transportAvailable
    BoxWithConstraints(modifier) {
        val buttonSize = if (style.vertical) (style.height * .1f).dp else maxWidth * .18f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            info()
            Column {
                key(presentation.trackKey) {
                    PlayerSlider(progress, enabled && duration > 0, "播放进度", options.reducedMotion,
                        gestureKey = presentation.trackKey,
                        onPreview = { dragging = it }, onCommit = { onSeek((it.toDouble() * duration).toLong().coerceIn(0, duration)) })
                }
                Row(Modifier.fillMaxWidth().padding(top = if (style.height > 768) 4.dp else 0.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel(playerTime(if (dragging != null) (progress.toDouble() * duration).toLong() else playback.positionMs), style)
                    TimeLabel(if (duration <= 0) "--:--" else if (remaining)
                        "−${playerTime((duration - progress.toDouble() * duration).toLong())}" else playerTime(duration), style,
                        Modifier.clickable { remaining = !remaining })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (!style.vertical) PlayerMediaButton("随机播放", if (mode == "Random") "shuffle-active" else "shuffle",
                    buttonSize.value, 1.3f * style.em, mode != null, options.reducedMotion) {
                    onMode(if (mode == "Random") "CircleEnd" else "Random")
                }
                PlayerMediaButton("上一首", "icon_rewind", buttonSize.value,
                    if (style.vertical) 32f * (if (style.width <= 480) 2f else 2.5f) else 32f * style.mediaScale,
                    transportEnabled, options.reducedMotion) { onTransport("previous") }
                val playing = playback.playing == true
                PlayerMediaButton(if (playing) "暂停" else "播放", if (playing) "icon_pause" else "icon_play", buttonSize.value,
                    if (style.vertical) 38f * (if (style.width <= 480) 1.25f else 1.75f) else 38f * style.playScale,
                    transportEnabled, options.reducedMotion) { onTransport(if (playing) "pause" else "play") }
                PlayerMediaButton("下一首", "icon_forward", buttonSize.value,
                    if (style.vertical) 32f * (if (style.width <= 480) 2f else 2.5f) else 32f * style.mediaScale,
                    transportEnabled, options.reducedMotion) { onTransport("next") }
                if (!style.vertical) PlayerMediaButton("循环播放", when(mode) {
                    "Circle" -> "repeat-active"; "RepeatOne" -> "repeat-one-active"; else -> "repeat"
                }, buttonSize.value, 1.3f * style.em, mode != null, options.reducedMotion) {
                    onMode(when(mode) { "CircleEnd" -> "Circle"; "Circle" -> "RepeatOne"; else -> "CircleEnd" })
                }
            }
            if (style.vertical) bottomControls()
            if (volume != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon("icon_speaker", null, Modifier.size(24.dp), .5f)
                key(presentation.trackKey) {
                    PlayerSlider(volumeDrag ?: volume!!, enabled, "音量", options.reducedMotion,
                        Modifier.weight(1f), gestureKey = presentation.trackKey,
                        onPreview = { volumeDrag = it }, onCommit = onVolume)
                }
                PlayerIcon("icon_speaker_3", null, Modifier.size(24.dp), .5f)
            }
        }
    }
}

@Composable
private fun TimeLabel(value: String, style: AmllPlayerStyle, modifier: Modifier = Modifier) = BasicText(value, modifier,
    style = playerTimeStyle(style.timeSize))

internal fun playerTimeStyle(size: Float) = TextStyle(
    color = Color.White.copy(alpha = .5f), fontSize = size.sp, lineHeight = (size * 1.25f).sp,
)

internal fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun PlayerMediaButton(label: String, icon: String, side: Float, iconSize: Float,
    enabled: Boolean = true, reducedMotion: Boolean = false, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .85f else 1f, if (reducedMotion) snap() else spring(.45f, 300f))
    Box(Modifier.size(side.dp).clip(CircleShape)
        .background(if (hovered || pressed) Color.White.copy(alpha = .133f) else Color.Transparent)
        .hoverable(interactions, enabled).clickable(interactionSource = interactions, indication = null,
            enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        PlayerIcon(icon, label, Modifier.size(iconSize.dp).graphicsLayer { scaleX = scale; scaleY = scale }, if (enabled) 1f else .25f)
    }
}
