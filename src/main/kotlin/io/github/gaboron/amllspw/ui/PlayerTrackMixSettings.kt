package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.interop.TrackMixClient
import kotlin.math.roundToInt

@Composable
internal fun TrackMixSettings(settings: TrackMixClient.Settings, idle: Boolean, trackKey: String?, color: Color,
    reducedMotion: Boolean, back: () -> Unit, save: (Int, Boolean, Boolean, Boolean) -> Unit, manage: (String) -> Unit) {
    var limit by remember(settings.cacheGiB) { mutableIntStateOf(settings.cacheGiB) }
    var quality by remember(settings.quality) { mutableStateOf(settings.quality) }
    var cpu by remember(settings.cpu) { mutableStateOf(settings.cpu) }
    var fp16 by remember(settings.fp16) { mutableStateOf(settings.fp16) }
    var preview by remember { mutableStateOf<Float?>(null) }
    var confirmation by remember(trackKey) { mutableStateOf<Pair<String, String>?>(null) }
    val cacheLevel = (limit - 1) / 99f
    val displayed = preview ?: cacheLevel
    val format = { value: Float -> "${(value * 99).roundToInt() + 1} GiB" }

    PlayerGlassMenuItem(onClick = back) { PlayerGlassMenuText("返回调音台") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MixText("模型和缓存", color, 17, FontWeight.SemiBold)
        MixText(if (settings.modelReady) "BS-RoFormer SW Fixed 已下载" else "首次分离时下载模型",
            color.copy(alpha = .68f), 13)
        MixText("缓存 %.2f GiB · 上限 %s".format(settings.cacheBytes / 1073741824.0, format(displayed)),
            color.copy(alpha = .68f), 13)
        PlayerSlider(cacheLevel, true, "缓存上限", reducedMotion, formatValue = format,
            displayedValue = displayed, onPreview = { preview = it }, onCommit = { limit = (it * 99).roundToInt() + 1 })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MixChip("高质量 TTA", quality, color, reducedMotion) { quality = !quality }
            MixChip("CUDA FP16", fp16 && !cpu, color, reducedMotion, enabled = !cpu) { fp16 = !fp16 }
            MixChip("仅 CPU", cpu, color, reducedMotion) { cpu = !cpu }
        }
    }
    PlayerGlassMenuItem(onClick = { save(limit, quality, cpu, fp16) }) { PlayerGlassMenuText("保存设置") }
    PlayerGlassMenuDivider()
    PlayerGlassMenuItem(enabled = idle, onClick = { manage("prepareModel") }) {
        PlayerGlassMenuText("准备模型", enabled = idle)
    }
    val hasTrack = idle && trackKey != null && settings.hasTrack
    PlayerGlassMenuItem(enabled = hasTrack, onClick = { confirmation = "clearCurrent" to "当前歌曲的音轨缓存" }) {
        PlayerGlassMenuText("清除当前歌曲缓存", enabled = hasTrack)
    }
    PlayerGlassMenuItem(enabled = idle, onClick = { confirmation = "clearAll" to "全部音轨缓存" }) {
        PlayerGlassMenuText("清除全部缓存", enabled = idle)
    }
    PlayerGlassMenuItem(enabled = idle, onClick = { confirmation = "removeModel" to "模型文件" }) {
        PlayerGlassMenuText("清除模型", enabled = idle)
    }
    confirmation?.let { (action, target) ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MixText("将${target}移入回收站？", color, 13, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MixChip("确认", false, color, reducedMotion, enabled = idle) {
                    confirmation = null
                    manage(action)
                }
                MixChip("返回", false, color, reducedMotion) { confirmation = null }
            }
        }
    }
}
