// SPDX-License-Identifier: AGPL-3.0-only
// Horizontal/vertical layout and MusicInfo styles ported from react-full,
// revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3 (SteveXMH and AMLL contributors).
package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.timeline.PlaybackClock

internal data class PlayerLyricFraming(val position: Float = .35f, val anchor: Float = .35f, val fontSize: Int? = null)
internal val LocalPlayerLyricFraming = staticCompositionLocalOf { PlayerLyricFraming() }

@Composable
internal fun PlayerPage(presentation: LyricsPresentation, options: RenderOptions, modifier: Modifier,
    onPlayback: () -> PlaybackClock.Sample, onSeek: (Long) -> Unit, onTransport: (String) -> Unit, onRestore: () -> Unit,
    nativeControls: @Composable () -> Unit = {}, readVolume: () -> Float? = { null }, onVolume: (Float) -> Unit = {},
    readMode: () -> String? = { null }, onMode: (String) -> Unit = {},
    pageMenu: (@Composable (Boolean, () -> Unit, Float) -> Unit)? = null, lyrics: @Composable () -> Unit) {
    var hiddenLyrics by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        val preferred = AmllPlayerStyle(maxWidth.value, maxHeight.value)
        val title = playerTrackTitle(presentation)
        val artist = playerTrackArtist(presentation)
        val minimumText = remember(measurer, title, artist, preferred.infoSize, preferred.timeSize, density) {
            val info = measurer.measure(title, playerInfoStyle(preferred.infoSize, .9f, FontWeight.Medium),
                maxLines = 1, softWrap = false).size.height +
                measurer.measure(artist, playerInfoStyle(preferred.infoSize, .45f, FontWeight.Normal),
                    maxLines = 1, softWrap = false).size.height
            val time = measurer.measure("−0:00", playerTimeStyle(preferred.timeSize), maxLines = 1).size.height
            info / density.density to time / density.density
        }
        val style = preferred.copy(infoTextHeight = minimumText.first, timeTextHeight = minimumText.second)
        PlayerBackground(presentation.palette, options.reducedMotion)
        val cover = style.cover(hiddenLyrics)
        val menu: @Composable () -> Unit = {
            if (pageMenu != null) pageMenu(hiddenLyrics, { hiddenLyrics = !hiddenLyrics }, style.menuSize)
            else PlayerMediaButton("更多", "icon_more", style.menuSize, style.menuSize * .72f, onClick = onRestore)
        }
        val bottom: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy((style.em * 2f).dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerMediaButton(if (hiddenLyrics) "显示歌词" else "隐藏歌词", if (hiddenLyrics) "lyrics_off" else "lyrics_on",
                    40f, 32f, reducedMotion = options.reducedMotion) { hiddenLyrics = !hiddenLyrics }
                nativeControls()
            }
        }
        PlayerArtwork(presentation.artwork, Modifier.offset(cover.left.dp, cover.top.dp).size(cover.width.dp),
            viewportHeight = style.height, coverSize = cover.width, onPlayback = onPlayback, reducedMotion = options.reducedMotion)
        if (!style.vertical || hiddenLyrics) {
            val controlsTop = if (style.vertical) cover.bottom + style.em else style.controlsTop
            val controlsWidth = if (style.vertical) style.width - style.edge * 2f else style.coverSize
            val controlsHeight = if (style.vertical) (style.height - controlsTop - 24f).coerceAtLeast(style.verticalControlsHeight)
                else style.controlsHeight
            PlayerControls(presentation, style, options,
                Modifier.offset((if (style.vertical) style.edge else cover.left).dp, controlsTop.dp)
                    .size(controlsWidth.dp, controlsHeight.dp),
                onPlayback, onSeek, onTransport, readVolume, onVolume, readMode, onMode,
                bottomControls = bottom, info = { PlayerTrackInfo(presentation, style.infoSize, options.reducedMotion, menu) })
        } else {
            Row(Modifier.offset(style.edge.dp, style.smallHeaderTop.dp).width((style.width - style.edge * 2).dp)
                .height(style.smallCover.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width((style.smallCover + style.em).dp))
                Box(Modifier.weight(1f)) { PlayerTrackInfo(presentation, style.smallInfoSize, options.reducedMotion, menu) }
            }
        }
        if (!hiddenLyrics) {
            val top = if (style.vertical) style.lyricTop else maxOf(30f, style.coverTop - style.height * .04f)
            val left = if (style.vertical) 0f else style.width * .45f + style.gap
            val rightPadding = if (style.vertical) 0f else (style.width * .55f) * (if (style.width <= 1600 || style.height <= 1000) .08f else .15f)
            val height = style.height - top - if (style.vertical) 0f else 48f
            val position = if (style.vertical) .1f else ((style.coverCenter - top) / height).coerceIn(0f, 1f)
            val fontSize = maxOf(style.height * .05f, style.width * .025f, 14f).toInt()
            CompositionLocalProvider(LocalPlayerLyricFraming provides PlayerLyricFraming(position, if (style.vertical) 0f else .5f, fontSize)) {
                Box(Modifier.offset(left.dp, top.dp).size((style.width - left - rightPadding).dp, height.dp)) { lyrics() }
            }
        }
        if (!style.vertical) Row(Modifier.offset(y = style.bottomTop.dp).fillMaxWidth().padding(end = (style.em * 2).dp),
            horizontalArrangement = Arrangement.End) { bottom() }
    }
}

@Composable
private fun PlayerTrackInfo(presentation: LyricsPresentation, size: Float, reducedMotion: Boolean, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clipToBounds()) {
            @Composable fun Line(text: String, alpha: Float, weight: FontWeight) {
                BasicText(text, if (reducedMotion) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = playerInfoStyle(size, alpha, weight), maxLines = 1, softWrap = false)
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

private fun playerInfoStyle(size: Float, alpha: Float, weight: FontWeight) = TextStyle(
    color = Color.White.copy(alpha = alpha), fontSize = size.sp,
    lineHeight = (size * 1.25f).sp, fontWeight = weight, letterSpacing = .4.sp,
)

@Composable
private fun PlayerBackground(palette: List<Int>, reducedMotion: Boolean) {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) {
            var start = 0L
            val initial = seconds
            while (true) withFrameNanos { now ->
                if (start == 0L) start = now
                seconds = initial + (now - start) / 1_000_000_000f
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) { drawFluidBackground(palette, seconds) }
}
