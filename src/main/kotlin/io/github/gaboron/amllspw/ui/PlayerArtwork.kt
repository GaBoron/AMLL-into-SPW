// SPDX-License-Identifier: AGPL-3.0-only
// Cover geometry and pause shrink ported from react-full/Cover at 832bad5f16ec2a7f96d06707ef927c9a4d3713d3.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.timeline.PlaybackClock
import kotlinx.coroutines.delay

@Composable
internal fun PlayerArtwork(artwork: ImageBitmap?, modifier: Modifier, viewportHeight: Float, coverSize: Float,
    onPlayback: () -> PlaybackClock.Sample, reducedMotion: Boolean) {
    val read by rememberUpdatedState(onPlayback)
    var paused by remember { mutableStateOf(read().playing == false) }
    LaunchedEffect(Unit) { while (true) { paused = read().playing == false; delay(100) } }
    val scale by animateFloatAsState(if (paused) .75f else 1f,
        if (reducedMotion) snap() else if (paused) tween(600, easing = CubicBezierEasing(.4f, .2f, .1f, 1f)) else tween(500, easing = CubicBezierEasing(.3f, .2f, .2f, 1.4f)))
    val shape = RoundedCornerShape(maxOf(viewportHeight * .007f, coverSize * .02f).dp)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(if (paused) 13.dp else 19.dp, shape, ambientColor = Color.Black.copy(alpha = .19f), spotColor = Color.Black.copy(alpha = .19f))
        .clip(shape).background(Color.Black)) {
        if (artwork != null) Image(artwork, "歌曲封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().semantics { contentDescription = "暂无歌曲封面" })
    }
}
