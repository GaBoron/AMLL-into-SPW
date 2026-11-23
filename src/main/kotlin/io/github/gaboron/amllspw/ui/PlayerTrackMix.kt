package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.interop.TrackMixClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TrackLabels = listOf("人声", "鼓组", "贝斯", "吉他", "钢琴", "其他")
private val PresetLabels = listOf("原曲", "纯伴奏", "仅人声", "自定义")

@Composable
internal fun PlayerTrackMixButton(client: TrackMixClient, side: Float, enabled: Boolean, reducedMotion: Boolean) {
    val host = LocalPlayerGlassPopups.current ?: return
    var available by remember(client) { mutableStateOf(client.available()) }
    LaunchedEffect(client) {
        while (true) {
            available = client.available()
            delay(400)
        }
    }
    if (!available) return

    val entry = remember(host) {
        lateinit var popup: PlayerGlassPopupEntry
        popup = PlayerGlassPopupEntry("六轨混音") { popup.expanded = false }
        popup
    }
    val iconSize = side * .60f
    SideEffect {
        entry.width = 340.dp
        entry.source = { PlayerIcon("icon_stems", null, Modifier.size(iconSize.dp)) }
    }
    DisposableEffect(entry, enabled) {
        if (!enabled) entry.dismiss()
        onDispose { entry.dismiss() }
    }
    Box(Modifier.onGloballyPositioned { entry.anchor = it.boundsInWindow().roundToIntRect() }
        .graphicsLayer { alpha = if (entry.sourceHidden) 0f else 1f }) {
        PlayerMediaButton("六轨混音", "icon_stems", side, iconSize, enabled, reducedMotion) {
            entry.content = { TrackMixPanel(client, reducedMotion, entry.dismiss) }
            entry.expanded = true
            host.show(entry)
        }
    }
}

@Composable
private fun TrackMixPanel(client: TrackMixClient, reducedMotion: Boolean, dismiss: () -> Unit) {
    var state by remember(client) { mutableStateOf(client.snapshot()) }
    LaunchedEffect(client) {
        while (true) {
            val update = client.snapshot()
            if (update == null) {
                dismiss()
                return@LaunchedEffect
            }
            state = update
            delay(160)
        }
    }
    val current = state ?: return
    val color = if (LocalPlayerGlassDark.current) Color.White else Color(0xff202020)
    val edit = { action: String, lane: Int, value: Int ->
        client.command(action, lane, value)
        state = client.snapshot()
    }
    PlayerGlassMenu {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MixText("六轨混音", color, 17, FontWeight.SemiBold)
            if (current.title.isNotBlank()) MixText(current.title, color.copy(alpha = .62f), 13, maxLines = 1)
            val description = when {
                current.busy -> jobDescription(current)
                current.notice.isNotBlank() -> current.notice
                current.ready && current.original -> "原曲直通"
                current.ready -> "实时混音"
                current.cached -> "重新打开歌曲以接入混音"
                current.title.isBlank() -> "请播放本地立体声歌曲"
                else -> "这首歌尚未分离"
            }
            MixText(description, color.copy(alpha = .70f), 13, maxLines = 2)
            if (current.busy) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = .13f))) {
                    Box(Modifier.fillMaxWidth(current.progress.toFloat().coerceIn(0f, 1f)).height(3.dp)
                        .background(color.copy(alpha = .75f)))
                }
            }
        }

        if (current.busy || current.canSeparate) {
            PlayerGlassMenuItem(onClick = { edit(if (current.busy) "cancel" else "separate", 0, 0) }) {
                PlayerGlassMenuText(if (current.busy) "取消分离" else "分离六轨")
            }
        }
        if (current.ready) {
            PlayerGlassMenuDivider()
            FlowRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PresetLabels.forEachIndexed { index, preset ->
                    MixChip(preset, current.preset == index, color) { edit("preset", 0, index) }
                }
                MixChip("重置", false, color) { edit("reset", 0, 0) }
            }
            PlayerGlassMenuDivider()
            CompositionLocalProvider(LocalPlayerColor provides color) {
                TrackLabels.forEachIndexed { lane, name ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MixText(name, color, 13, modifier = Modifier.width(30.dp))
                        val set = { value: Float -> edit("level", lane, (150 * value).roundToInt()) }
                        PlayerSlider(current.levels[lane] / 150f, true, "$name 音量", reducedMotion, Modifier.weight(1f),
                            formatValue = { "${(150 * it).roundToInt()}%" }, onPreview = { it?.let(set) }, onCommit = set)
                        MixText("${current.levels[lane]}%", color.copy(alpha = .65f), 12, modifier = Modifier.width(39.dp))
                        val bit = 1 shl lane
                        MixChip("静音", (current.muted and bit) != 0, color) { edit("mute", lane, 0) }
                        MixChip("独奏", (current.solos and bit) != 0, color) { edit("solo", lane, 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixChip(label: String, active: Boolean, color: Color, click: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = if (active) .18f else .06f))
        .selectable(active, role = Role.Button, onClick = click).padding(horizontal = 7.dp, vertical = 5.dp)) {
        MixText(label, color.copy(alpha = if (active) 1f else .72f), 12)
    }
}

@Composable
private fun MixText(text: String, color: Color, size: Int, weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun jobDescription(state: TrackMixClient.Snapshot): String {
    val stage = when (state.stage) {
        "runtime" -> "准备推理环境"
        "download" -> "下载模型"
        "verify", "load" -> "载入模型"
        "decode" -> "解码原曲"
        "cache", "finish", "done" -> "写入音轨缓存"
        else -> "分离音轨"
    }
    return if (state.progress in 0.001..0.999) "$stage ${(100 * state.progress).roundToInt()}%" else stage
}
