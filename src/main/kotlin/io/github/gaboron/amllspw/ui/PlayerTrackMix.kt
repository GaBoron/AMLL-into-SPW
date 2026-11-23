package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.gaboron.amllspw.interop.TrackIdentity
import io.github.gaboron.amllspw.interop.TrackMixClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TrackLabels = listOf("人声", "鼓组", "贝斯", "吉他", "钢琴", "其他")
private val PresetLabels = listOf("原曲", "纯伴奏", "仅人声", "自定义")
private const val MixTransitionMs = 180

@Composable
internal fun PlayerTrackMixButton(client: TrackMixClient, side: Float, trackKey: String?, reducedMotion: Boolean) {
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
    val localTrack by rememberUpdatedState(trackKey)
    val motionReduced by rememberUpdatedState(reducedMotion)
    val iconSize = side * .60f
    SideEffect {
        entry.width = 340.dp
        entry.keepOnTrackChange = true
        entry.source = { PlayerIcon("icon_stems", null, Modifier.size(iconSize.dp)) }
    }
    DisposableEffect(entry) { onDispose { entry.dismiss() } }
    Box(Modifier.onGloballyPositioned { entry.anchor = it.boundsInWindow().roundToIntRect() }
        .graphicsLayer { alpha = if (entry.sourceHidden) 0f else 1f }) {
        PlayerMediaButton("六轨混音", "icon_stems", side, iconSize, reducedMotion = reducedMotion) {
            entry.content = { TrackMixPanel(client, localTrack, motionReduced, entry.dismiss) }
            entry.expanded = true
            host.show(entry)
        }
    }
}

@Composable
private fun TrackMixPanel(client: TrackMixClient, trackKey: String?, reducedMotion: Boolean, dismiss: () -> Unit) {
    var state by remember(client) { mutableStateOf(client.snapshot()) }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(client, trackKey) {
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
    val displayTrack = trackKey?.takeIf { key ->
        current.identity?.matches(TrackIdentity(key.substringBeforeLast('|'), key.substringAfterLast('|'))) != false
    }
    val color = if (LocalPlayerGlassDark.current) Color.White else Color(0xff202020)
    val edit = { action: String, lane: Int, value: Int ->
        client.command(action, lane, value)
        state = client.snapshot()
    }
    PlayerGlassMenu {
        val settings = current.settings
        if (showSettings && settings != null) {
            TrackMixSettings(settings, current.jobs.isEmpty(), displayTrack, color, reducedMotion,
                back = { showSettings = false },
                save = { limit, quality, cpu, fp16 ->
                    client.configure(limit, quality, cpu, fp16)
                    state = client.snapshot()
                    showSettings = false
                },
                manage = { command -> edit(command, 0, 0) })
            SeparationQueue(current.jobs, current.jobNotice, color, reducedMotion) { edit("cancel", 0, it) }
            return@PlayerGlassMenu
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MixText("六轨混音", color, 17, FontWeight.SemiBold)
            if (displayTrack != null && current.title.isNotBlank()) {
                MixText(current.title, color.copy(alpha = .62f), 13, maxLines = 1)
            }
            val description = when {
                trackKey == null -> "请播放本地立体声歌曲"
                displayTrack == null -> "正在切换曲目"
                current.notice.isNotBlank() -> current.notice
                current.quickCached && current.ready -> if (current.original) "快速分离已缓存 · 原曲直通" else "快速分离 · 实时混音"
                current.ready && current.original -> "原曲直通"
                current.ready -> "实时混音"
                current.cached -> "重新打开歌曲以接入混音"
                current.currentQueued -> "已加入分离队列"
                current.title.isBlank() -> "请播放本地立体声歌曲"
                else -> "这首歌尚未分离"
            }
            MixText(description, color.copy(alpha = .70f), 13, maxLines = 2)
        }

        if (displayTrack != null && current.canSeparate) {
            PlayerGlassMenuItem(onClick = { edit("separate", 0, 0) }) { PlayerGlassMenuText("标准分离") }
        }
        if (displayTrack != null && current.canQuick) {
            PlayerGlassMenuItem(onClick = { edit("quick", 0, 0) }) { PlayerGlassMenuText("快速分离") }
        }
        if (settings != null) {
            PlayerGlassMenuItem(onClick = { showSettings = true }) { PlayerGlassMenuText("模型和缓存") }
        }
        SeparationQueue(current.jobs, current.jobNotice, color, reducedMotion) { edit("cancel", 0, it) }
        if (displayTrack != null && current.ready) key(trackKey) {
            PlayerGlassMenuDivider()
            FlowRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PresetLabels.forEachIndexed { index, preset ->
                    MixChip(preset, current.preset == index, color, reducedMotion) { edit("preset", 0, index) }
                }
                MixChip("重置", false, color, reducedMotion) { edit("reset", 0, 0) }
            }
            PlayerGlassMenuDivider()
            CompositionLocalProvider(LocalPlayerColor provides color) {
                TrackLabels.forEachIndexed { lane, name ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MixText(name, color, 13, modifier = Modifier.width(30.dp))
                        val level = current.levels[lane] / 150f
                        var preview by remember { mutableStateOf<Float?>(null) }
                        val animatedLevel by animateFloatAsState(level,
                            if (reducedMotion || preview != null) snap() else tween(MixTransitionMs))
                        val displayed = preview ?: if (reducedMotion) level else animatedLevel
                        val setLevel = { value: Float ->
                            val percent = (150 * value).roundToInt()
                            if (percent != current.levels[lane]) edit("level", lane, percent)
                        }
                        val format = { value: Float -> "${(150 * value).roundToInt()}%" }
                        PlayerSlider(level, true, "$name 音量", reducedMotion, Modifier.weight(1f),
                            formatValue = format, displayedValue = displayed, onPreview = { value ->
                                preview = value
                                value?.let(setLevel)
                            }, onCommit = setLevel)
                        MixText(format(displayed), color.copy(alpha = .65f), 12, modifier = Modifier.width(39.dp))
                        val bit = 1 shl lane
                        MixChip("静音", (current.muted and bit) != 0, color, reducedMotion) { edit("mute", lane, 0) }
                        MixChip("独奏", (current.solos and bit) != 0, color, reducedMotion) { edit("solo", lane, 0) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MixChip(label: String, active: Boolean, color: Color, reducedMotion: Boolean,
    enabled: Boolean = true, click: () -> Unit) {
    val selectedTint by animateFloatAsState(if (active) 1f else 0f,
        if (reducedMotion) snap() else tween(MixTransitionMs))
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .06f + .12f * selectedTint))
        .selectable(active, enabled = enabled, role = Role.Button, onClick = click).padding(horizontal = 7.dp, vertical = 5.dp)) {
        MixText(label, color.copy(alpha = if (enabled) .72f + .28f * selectedTint else .42f), 12)
    }
}

@Composable
internal fun MixText(text: String, color: Color, size: Int, weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SeparationQueue(jobs: List<TrackMixClient.Job>, notice: String, color: Color, reducedMotion: Boolean,
    cancel: (Int) -> Unit) {
    if (jobs.isNotEmpty()) {
        PlayerGlassMenuDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MixText("后台任务", color, 14, FontWeight.SemiBold)
            jobs.forEachIndexed { index, job -> key(job.id) {
                val kind = when (job.kind) {
                    "QUICK" -> "快速分离"
                    "MAINTENANCE" -> "模型和缓存"
                    else -> "标准分离"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        MixText(job.title, color, 13)
                        val progress = when {
                            job.cancelling -> "正在取消"
                            !job.running -> "排队 · 第 $index 位"
                            else -> jobDescription(job)
                        }
                        MixText("$kind · $progress", color.copy(alpha = .68f), 12)
                    }
                    MixChip("取消", false, color, reducedMotion, enabled = !job.cancelling) { cancel(job.id) }
                }
                val progress = (if (job.running) job.progress else 0.0).toFloat().coerceIn(0f, 1f)
                val displayed by animateFloatAsState(progress, if (reducedMotion) snap() else tween(MixTransitionMs))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = .13f))) {
                    Box(Modifier.fillMaxWidth(displayed).height(3.dp).background(color.copy(alpha = .75f)))
                }
            } }
        }
    }
    if (notice.isNotBlank()) {
        MixText(notice, color.copy(alpha = .68f), 12, maxLines = 3,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

private fun jobDescription(job: TrackMixClient.Job): String {
    val stage = when (job.stage) {
        "" -> "准备开始"
        "runtime" -> "准备推理环境"
        "download" -> "下载模型"
        "verify" -> "校验模型"
        "load" -> "载入模型"
        "decode" -> "解码原曲"
        "gpu16" -> "CUDA FP16"
        "gpu32" -> "CUDA FP32"
        "cpu" -> "CPU 分离"
        "fallback" -> "切换推理设备或精度"
        "cache", "finish", "done" -> "写入音轨缓存"
        else -> "正在处理"
    }
    return if (job.progress in 0.001..0.999) "$stage ${(100 * job.progress).roundToInt()}%" else stage
}
