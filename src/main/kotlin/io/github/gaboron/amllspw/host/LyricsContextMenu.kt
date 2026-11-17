@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Owns the menu outside the scene crossfade so a departing track cannot retain an active menu. */
@Composable
internal fun LyricsContextMenu(runtime: AmllRuntime, menu: NativeLyricsMenu?, presentation: LyricsPresentation,
    options: RenderOptions, modifier: Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val jobs = rememberCoroutineScope()
    // Only the rebuilt page provides this host; the original page keeps its native menu renderer.
    val glassHost = LocalPlayerGlassPopups.current
    var anchor by remember(presentation.trackKey, presentation.document, glassHost) { mutableStateOf<IntOffset?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dismiss by rememberUpdatedState { anchor = null }
    val open by rememberUpdatedState<(IntOffset) -> Unit>({
        if (runtime.isActive) {
            if (glassHost != null || menu != null) anchor = it
            else reportMenuFailure("当前 SPW 版本的菜单组件不可用")
        }
    })
    Box(modifier.fillMaxSize().onGloballyPositioned { coordinates = it }
        .lyricContextMenuGesture(presentation.trackKey to presentation.document) {
            open(IntOffset(it.x.roundToInt(), it.y.roundToInt()))
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
                LyricMenuItem("显示翻译", checked = options.translation, separatorBefore = true) {
                    save { runtime.preferences.setTranslation(!options.translation) }
                },
                LyricMenuItem("显示音译与注音", checked = options.romanization) {
                    save { runtime.preferences.setRomanization(!options.romanization) }
                },
                LyricMenuItem("显示底栏信息", checked = !options.hideFooter) {
                    save { runtime.preferences.setFooterVisible(options.hideFooter) }
                },
            )
            if (glassHost != null) {
                val localPoint = Offset(point.x.toFloat(), point.y.toFloat())
                val windowPoint = coordinates?.takeIf { it.isAttached }?.localToWindow(localPoint) ?: localPoint
                GlassLyricsMenu(glassHost, IntOffset(windowPoint.x.roundToInt(), windowPoint.y.roundToInt()),
                    dismiss, entries)
            } else if (menu != null) {
                val position = remember(point) { lyricMenuPosition(point) }
                menu.Popup(position, dismiss, entries)
            }
        }
    }
}

@Composable
private fun GlassLyricsMenu(host: PlayerGlassPopups, point: IntOffset, dismiss: () -> Unit, entries: List<LyricMenuItem>) {
    val onDismiss by rememberUpdatedState(dismiss)
    val entry = remember { PlayerGlassPopupEntry("歌词菜单") { onDismiss() }.also {
        it.atPointer = true
        it.width = 300.dp
    } }
    DisposableEffect(entry) { onDispose { entry.expanded = false } }
    val draw: @Composable () -> Unit = {
        PlayerGlassMenu {
            entries.forEach { item ->
                if (item.separatorBefore) PlayerGlassMenuDivider()
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
}

/** Keep native popup positioning relative to the lyrics surface and flip at the window edges. */
internal fun lyricMenuPosition(point: IntOffset): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + point.x
        val y = anchorBounds.top + point.y
        return IntOffset(
            (if (x + popupContentSize.width <= windowSize.width) x else x - popupContentSize.width)
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (if (y + popupContentSize.height <= windowSize.height) y else y - popupContentSize.height)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private fun reportMenuFailure(message: String) {
    System.err.println("AMLL: $message")
    runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Error) }
}
