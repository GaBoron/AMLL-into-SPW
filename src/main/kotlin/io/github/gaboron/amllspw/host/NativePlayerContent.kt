@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.PlayerPage
import io.github.gaboron.amllspw.ui.PlayerMediaButton
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import java.lang.invoke.MethodType
import java.lang.reflect.Method

/** Reflection stays at the host boundary, including a direct call to the unmodified native page. */
internal class NativePlayerContent(val target: NativePlayerTarget,
    private val loader: ClassLoader = WorkshopApi::class.java.classLoader) {
    private fun method(member: NativePlayerTarget.Member): Method {
        val type = MethodType.fromMethodDescriptorString(member.descriptor, loader)
        return Class.forName(member.owner.replace('/', '.'), false, loader)
            .getDeclaredMethod(member.name, *type.parameterArray()).also { check(it.trySetAccessible()) }
    }
    private val original = method(target.page)
    private val controls = target.controls.mapValues { method(it.value) }
    private val actions = runCatching { NativePlayerActions(loader) }.getOrNull()

    @Composable
    fun Content(runtime: AmllRuntime, arguments: List<Any?>, menu: NativeLyricsMenu?) {
        val options by runtime.preferences.options.collectAsState()
        if (!options.rebuildPlayerPage || !runtime.isActive) {
            // Normalize the optional modifier before calling with an explicit default mask of zero.
            original.invoke(null, arguments[0], arguments[1], arguments[2],
                arguments.getOrNull(3) as? Modifier ?: Modifier, currentComposer, 0, 0)
            return
        }
        val presentation by runtime.presentation.collectAsState()
        val restore = {
            runCatching { runtime.preferences.setRebuildPlayerPage(false) }.onFailure {
                WorkshopApi.ui.toast("播放页面设置保存失败：${it.message}", WorkshopApi.Ui.ToastType.Warning)
            }
            Unit
        }
        PlayerPage(presentation, options, arguments.getOrNull(3) as? Modifier ?: Modifier,
            onPlayback = { runtime.clock.sample(presentation.trackKey) },
            onSeek = { position ->
                runCatching { runtime.seekTo(presentation.trackKey, position) }.onFailure {
                    WorkshopApi.ui.toast("播放跳转失败：${it.message}", WorkshopApi.Ui.ToastType.Warning)
                }
            },
            onTransport = { runtime.transport(presentation.trackKey, it) },
            onRestore = restore,
            readVolume = {
                if (presentation.externalPlayback) runtime.externalVolume(presentation.trackKey)
                else runCatching { actions?.volume() }.getOrNull()
            },
            onVolume = { runtime.changeVolume(presentation.trackKey, it) { value -> actions?.volume(value) } },
            readMode = { if (presentation.externalPlayback) null else runCatching { actions?.mode() }.getOrNull() },
            onMode = { mode -> runtime.localPlayerAction(presentation.trackKey) { actions?.mode(mode) } },
            pageMenu = { hidden, toggle, size ->
                PlayerPageMenu(menu, hidden, toggle, restore, size)
            },
            nativeControls = {
                if (!presentation.externalPlayback) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controls["queue"]?.invoke(null, Color.White.value.toLong(), currentComposer, 0)
                }
            },
            lyrics = { NativeLyricsContent(runtime, Modifier.fillMaxSize(), true, menu) })
    }
}

@Composable
private fun PlayerPageMenu(menu: NativeLyricsMenu?, hidden: Boolean, toggle: () -> Unit, restore: () -> Unit, size: Float) {
    var expanded by remember { mutableStateOf(false) }
    val popupOffset = with(LocalDensity.current) { size.dp.roundToPx() }
    Box(Modifier.background(Color.White.copy(alpha = .082f), CircleShape)) {
        PlayerMediaButton("更多", "icon_more", size, size * .72f) { expanded = true }
        if (expanded) {
            if (menu != null) menu.Popup(lyricMenuPosition(IntOffset(0, popupOffset)), { expanded = false }, listOf(
                LyricMenuItem(if (hidden) "显示歌词" else "隐藏歌词") { toggle() },
                LyricMenuItem("恢复原版播放页") { restore() },
            )) else androidx.compose.material.DropdownMenu(true, { expanded = false }) {
                androidx.compose.material.DropdownMenuItem({ expanded = false; toggle() }) {
                    androidx.compose.material.Text(if (hidden) "显示歌词" else "隐藏歌词")
                }
                androidx.compose.material.DropdownMenuItem({ expanded = false; restore() }) {
                    androidx.compose.material.Text("恢复原版播放页")
                }
            }
        }
    }
}
