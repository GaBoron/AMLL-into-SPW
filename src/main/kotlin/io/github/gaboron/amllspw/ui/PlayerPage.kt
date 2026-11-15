// SPDX-License-Identifier: AGPL-3.0-only
// Layout and MusicInfo styles ported from react-full, revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.gaboron.amllspw.timeline.PlaybackClock
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.delay

internal data class PlayerLyricFraming(val position: Float = .35f, val anchor: Float = .35f,
    val fontSize: Int? = null, val color: Color? = null, val horizontalPaddingDp: Float? = null)
internal val LocalPlayerLyricFraming = staticCompositionLocalOf { PlayerLyricFraming() }

@Composable
internal fun PlayerPage(presentation: LyricsPresentation, options: RenderOptions, modifier: Modifier,
    onPlayback: () -> PlaybackClock.Sample, onSeek: (Long) -> Unit, onTransport: (String) -> Unit, onMore: () -> Unit = {},
    queue: @Composable (Float, Float) -> Unit = { _, _ -> }, readVolume: () -> Float? = { null }, onVolume: (Float) -> Unit = {},
    readMode: () -> String? = { null }, onMode: (String) -> Unit = {},
    pageMenu: (@Composable (Float) -> Unit)? = null, pageExpanded: Boolean = true,
    readSpectrum: () -> FloatArray? = { null }, lyrics: @Composable () -> Unit) {
    val spectrum = rememberPlayerSpectrum(readSpectrum)
    val glassPopups = remember { PlayerGlassPopups() }
    val backdrop = rememberLayerBackdrop()
    LaunchedEffect(pageExpanded, presentation.trackKey) { glassPopups.dismiss() }
    DisposableEffect(glassPopups) { onDispose { glassPopups.dismiss() } }
    var hiddenLyrics by remember { mutableStateOf(false) }
    val transportState = remember { PlayerTransportState() }
    val readPlayback = { transportState.sample(presentation.trackKey, onPlayback()) }
    var coverDirection by remember { mutableIntStateOf(1) }
    LaunchedEffect(presentation.trackKey) { delay(800); coverDirection = 1 }
    val lyricVisibility by animateFloatAsState(if (hiddenLyrics) 0f else 1f,
        if (options.reducedMotion) snap() else tween(560, easing = FastOutSlowInEasing))
    val animatedColor by animateColorAsState(if (options.coloredLyrics) Color(presentation.lyricColor) else Color.White,
        tween(if (options.reducedMotion || !options.coloredLyrics) 0 else 650))
    val color = if (options.coloredLyrics) animatedColor else Color.White
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalPlayerColor provides color, LocalPlayerGlassPopups provides glassPopups) {
        Box(modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().then(
                if (glassPopups.active) Modifier.layerBackdrop(backdrop) else Modifier)
                .clipToBounds().background(Color.Black)) {
                val preferred = AmllPlayerStyle(maxWidth.value, maxHeight.value)
                val title = playerTrackTitle(presentation)
                val artist = playerTrackArtist(presentation)
                val minimumText = remember(measurer, title, artist, preferred.infoSize, preferred.timeSize, density) {
                    val info = measurer.measure(title, playerInfoStyle(preferred.infoSize, Color.White, .9f, FontWeight.Medium),
                        maxLines = 1, softWrap = false).size.height +
                        measurer.measure(artist, playerInfoStyle(preferred.infoSize, Color.White, .45f, FontWeight.Normal),
                            maxLines = 1, softWrap = false).size.height
                    val time = measurer.measure("−0:00", playerTimeStyle(preferred.timeSize), maxLines = 1).size.height
                    info / density.density to time / density.density
                }
                val style = preferred.copy(infoTextHeight = minimumText.first, timeTextHeight = minimumText.second)
                PlayerBackground(presentation.palette, options.reducedMotion)
                val shown = style.cover(false)
                val hidden = style.cover(true)
                val movement = 1f - lyricVisibility
                fun blend(a: Float, b: Float) = a + (b - a) * movement
                val cover = Rect(blend(shown.left, hidden.left), blend(shown.top, hidden.top),
                    blend(shown.right, hidden.right), blend(shown.bottom, hidden.bottom))
                val menu: @Composable () -> Unit = {
                    if (pageMenu != null) pageMenu(style.menuSize)
                    else PlayerMediaButton("更多", "icon_more", style.menuSize, style.menuSize * .72f, onClick = onMore)
                }
                PlayerArtwork(presentation, Modifier.offset(cover.left.dp, cover.top.dp).size(cover.width.dp).zIndex(1f),
                    viewportHeight = style.height, coverSize = cover.width, onPlayback = readPlayback,
                    reducedMotion = options.reducedMotion, direction = coverDirection,
                    toggleLabel = if (hiddenLyrics) "显示歌词" else "隐藏歌词", onToggleLyrics = { hiddenLyrics = !hiddenLyrics })
                if (!style.vertical || movement > .001f) {
                    val controlsTop = if (style.vertical) cover.bottom + style.em else style.controlsTop
                    val controlsWidth = if (style.vertical) style.width - style.edge * 2f else style.coverSize
                    val controlsHeight = if (style.vertical) (style.height - controlsTop - 24f).coerceAtLeast(style.verticalControlsHeight)
                        else style.controlsHeight
                    PlayerControls(presentation, style, options,
                        Modifier.offset((if (style.vertical) style.edge else cover.left).dp, controlsTop.dp)
                            .size(controlsWidth.dp, controlsHeight.dp)
                            .zIndex(1f)
                            .graphicsLayer { alpha = if (style.vertical) movement else 1f },
                        readPlayback, onSeek, { command ->
                            if (command == "previous" || command == "next") coverDirection = if (command == "previous") -1 else 1
                            transportState.request(command)
                            onTransport(command)
                        }, readVolume, onVolume, readMode, onMode, queue = queue, spectrum = spectrum,
                        info = { PlayerTrackInfo(presentation, style.infoSize, options.reducedMotion, menu) })
                }
                if (style.vertical && lyricVisibility > .001f) {
                    Row(Modifier.offset(style.edge.dp, style.smallHeaderTop.dp).width((style.width - style.edge * 2).dp)
                        .height(style.smallCover.dp).zIndex(1f).graphicsLayer { alpha = lyricVisibility }, verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width((style.smallCover + style.em).dp))
                        Box(Modifier.weight(1f)) { PlayerTrackInfo(presentation, style.smallInfoSize, options.reducedMotion, menu) }
                    }
                }
                if (lyricVisibility > .001f) {
                    val top = if (style.vertical) style.lyricTop else maxOf(30f, style.coverTop - style.height * .04f)
                    val height = style.height - top - if (style.vertical) 0f else 48f
                    val position = if (style.vertical) .1f else ((style.coverCenter - top) / height).coerceIn(0f, 1f)
                    val fontSize = maxOf(style.height * .05f, style.width * .025f, 14f).toInt()
                    CompositionLocalProvider(LocalPlayerLyricFraming provides PlayerLyricFraming(position,
                        if (style.vertical) 0f else .5f, fontSize, color, style.lyricDrawingPadding)) {
                        Box(Modifier.offset(style.lyricCanvasLeft.dp, top.dp).size(style.lyricCanvasWidth.dp, height.dp)
                            .graphicsLayer {
                                alpha = lyricVisibility
                                translationX = (1f - lyricVisibility) * 40.dp.toPx()
                                scaleX = .97f + .03f * lyricVisibility
                                scaleY = scaleX
                            }) { lyrics() }
                    }
                }
            }
            PlayerGlassPopupHost(glassPopups, backdrop, options.reducedMotion)
        }
    }
}

@Composable
private fun PlayerTrackInfo(presentation: LyricsPresentation, size: Float, reducedMotion: Boolean, menu: @Composable () -> Unit) {
    val color = LocalPlayerColor.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clipToBounds()) {
            @Composable fun Line(text: String, alpha: Float, weight: FontWeight) {
                BasicText(text, if (reducedMotion) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = playerInfoStyle(size, color, alpha, weight), maxLines = 1, softWrap = false)
            }
            Line(playerTrackTitle(presentation), .9f, FontWeight.Medium)
            Line(playerTrackArtist(presentation), .45f, FontWeight.Normal)
        }
        Spacer(Modifier.width(16.dp))
        menu()
    }
}

private fun playerTrackTitle(presentation: LyricsPresentation) = presentation.title.ifBlank {
    if (presentation.trackKey.isBlank()) "尚未播放" else "未知曲目"
}

private fun playerTrackArtist(presentation: LyricsPresentation) = presentation.artist.ifBlank {
    if (presentation.trackKey.isBlank()) "从曲库选择歌曲" else "未知艺术家"
}

private fun playerInfoStyle(size: Float, color: Color, alpha: Float, weight: FontWeight) = TextStyle(
    color = color.copy(alpha = alpha), fontSize = size.sp,
    lineHeight = (size * 1.25f).sp, fontWeight = weight, letterSpacing = .4.sp,
)
