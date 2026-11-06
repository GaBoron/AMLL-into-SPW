@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.LyricsPresentation
import io.github.gaboron.amllspw.ui.RenderOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import kotlin.math.roundToInt

/** Owns the menu outside the scene crossfade so a departing track cannot retain an active menu. */
@Composable
internal fun LyricsContextMenu(runtime: AmllRuntime, menu: NativeLyricsMenu?, presentation: LyricsPresentation,
    options: RenderOptions, modifier: Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val jobs = rememberCoroutineScope()
    var anchor by remember(presentation.trackKey, presentation.document) { mutableStateOf<IntOffset?>(null) }
    val open by rememberUpdatedState<(IntOffset) -> Unit>({
        if (runtime.isActive) {
            if (menu != null) anchor = it
            else reportMenuFailure("当前 SPW 版本的菜单组件不可用")
        }
    })
    Box(modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            var secondary = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    secondary = true
                    event.changes.firstOrNull()?.position?.let {
                        open(IntOffset(it.x.roundToInt(), it.y.roundToInt()))
                    }
                }
                if (secondary) {
                    // Consume before the child tap detectors, including the release: right-click
                    // must never seek, resume following, or change the instrumental poem.
                    event.changes.forEach { it.consume() }
                    if (!event.buttons.isSecondaryPressed) secondary = false
                }
            }
        }
    }) {
        content()
        val point = anchor
        if (point != null && menu != null && runtime.isActive) {
            val position = remember(point) { lyricMenuPosition(point) }
            val canCopy = !presentation.loading && !presentation.isInstrumental &&
                presentation.document.lines.any { it.text.isNotBlank() }
            fun copyLyrics(translation: Boolean) {
                jobs.launch {
                    try {
                        clipboard.setClipEntry(ClipEntry(StringSelection(presentation.document.plainText(translation))))
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
            menu.Popup(position, { anchor = null }, listOf(
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
            ))
        }
    }
}

/** Anchor in window pixels and flip at the edges, including when the window is smaller than the menu. */
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
