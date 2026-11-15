@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

/** Owns the menu outside the scene crossfade so a departing track cannot retain an active menu. */
@Composable
internal fun LyricsContextMenu(runtime: AmllRuntime, presentation: LyricsPresentation,
    options: RenderOptions, modifier: Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val jobs = rememberCoroutineScope()
    var anchor by remember(presentation.trackKey, presentation.document) { mutableStateOf<IntOffset?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dismiss by rememberUpdatedState { anchor = null }
    val entry = remember { PlayerGlassPopupEntry("歌词菜单") { dismiss() }.also {
        it.atPointer = true
        it.width = 300.dp
    } }
    DisposableEffect(entry) { onDispose { entry.expanded = false } }
    val open by rememberUpdatedState<(IntOffset) -> Unit>({
        if (runtime.isActive) {
            anchor = it
        }
    })
    PlayerGlassLayer(options.reducedMotion, modifier.fillMaxSize()) {
        val host = requireNotNull(LocalPlayerGlassPopups.current)
        Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates = it }
          .lyricContextMenuGesture(presentation.trackKey to presentation.document) {
            val point = coordinates?.takeIf { layout -> layout.isAttached }?.localToWindow(it) ?: it
            open(IntOffset(point.x.roundToInt(), point.y.roundToInt()))
          }) {
            content()
            val point = anchor
            if (point != null && runtime.isActive) {
                val canCopy = !presentation.loading && !presentation.isInstrumental &&
                    presentation.document.lines.any { it.text.isNotBlank() }
                fun copyLyrics(translation: Boolean) {
                    jobs.launch {
                        try {
                            LyricClipboard.copy(presentation.document.plainText(translation, options.translationLanguage), clipboard)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            reportMenuFailure("歌词复制失败：${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
                fun save(action: () -> Unit) {
                    try { action() }
                    catch (failure: Exception) {
                        reportMenuFailure("歌词设置保存失败：${failure.message ?: failure.javaClass.simpleName}")
                    }
                }
                val entries = listOf(
                    LyricMenuItem("复制歌词原文", canCopy) { copyLyrics(false) },
                    LyricMenuItem("复制歌词原文及翻译", canCopy) { copyLyrics(true) },
                    LyricMenuItem("显示翻译", checked = options.translation) {
                        save { runtime.preferences.setTranslation(!options.translation) }
                    },
                    LyricMenuItem("显示音译与注音", checked = options.romanization) {
                        save { runtime.preferences.setRomanization(!options.romanization) }
                    },
                    LyricMenuItem("显示底栏信息", checked = !options.hideFooter) {
                        save { runtime.preferences.setFooterVisible(options.hideFooter) }
                    },
                )
                val draw: @Composable () -> Unit = {
                    PlayerGlassMenu {
                        entries.forEachIndexed { index, item ->
                            if (index == 2) PlayerGlassMenuDivider()
                            PlayerGlassMenuItem(item.enabled, { dismiss(); item.onClick() }) {
                                PlayerGlassMenuText(item.text, item.enabled, item.checked)
                            }
                        }
                    }
                }
                SideEffect {
                    entry.anchor = IntRect(point, IntSize(1, 1))
                    entry.content = draw
                    entry.expanded = true
                    host.show(entry)
                }
            } else SideEffect { entry.expanded = false }
        }
    }
}

private data class LyricMenuItem(val text: String, val enabled: Boolean = true, val checked: Boolean? = null,
    val onClick: () -> Unit)

private fun reportMenuFailure(message: String) {
    System.err.println("AMLL: $message")
    runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Error) }
}
