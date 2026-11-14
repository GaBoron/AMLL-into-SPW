// SPDX-License-Identifier: AGPL-3.0-only
// Pause geometry originates from react-full/Cover at 832bad5f16ec2a7f96d06707ef927c9a4d3713d3.
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.timeline.PlaybackClock
import kotlinx.coroutines.delay

private data class ArtworkFrame(val track: String, val image: ImageBitmap?)

@Composable
internal fun PlayerArtwork(presentation: LyricsPresentation, modifier: Modifier, viewportHeight: Float, coverSize: Float,
    onPlayback: () -> PlaybackClock.Sample, reducedMotion: Boolean, direction: Int,
    toggleLabel: String, onToggleLyrics: () -> Unit) {
    val read by rememberUpdatedState(onPlayback)
    var paused by remember { mutableStateOf(read().playing == false) }
    LaunchedEffect(Unit) { while (true) { paused = read().playing == false; delay(100) } }
    val pause by animateFloatAsState(if (paused) 1f else 0f,
        if (reducedMotion) snap() else tween(650, easing = CubicBezierEasing(.22f, .8f, .2f, 1f)))
    var frame by remember { mutableStateOf(ArtworkFrame(presentation.trackKey, presentation.artwork)) }
    LaunchedEffect(presentation.trackKey, presentation.artwork, presentation.artworkLoading) {
        // A cold cover load keeps the outgoing sleeve until the incoming image is ready.
        if (!presentation.artworkLoading) frame = ArtworkFrame(presentation.trackKey, presentation.artwork)
    }
    val shape = RoundedCornerShape(maxOf(viewportHeight * .007f, coverSize * .02f).dp)
    val tint = LocalPlayerColor.current
    Box(modifier.graphicsLayer {
        scaleX = 1f - .18f * pause
        scaleY = scaleX
        transformOrigin = TransformOrigin(0f, .5f)
    }.clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggleLyrics)
        .semantics { contentDescription = toggleLabel }) {
        if (pause > .001f && coverSize > 100f) Canvas(Modifier.fillMaxSize().graphicsLayer {
            translationX = coverSize.dp.toPx() * .3f * pause
            alpha = pause
            rotationZ = -12f * (1f - pause)
        }) {
            val radius = size.minDimension * .46f
            drawCircle(Brush.radialGradient(listOf(Color(0xff232326), Color(0xff09090b), Color(0xff303034), Color(0xff0c0c0e)), radius = radius), radius)
            for (i in 1..38) drawCircle(Color.White.copy(alpha = if (i % 4 == 0) .12f else .045f),
                radius * (.31f + i * .0165f), style = Stroke(.65.dp.toPx()))
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .16f), Color.Transparent,
                Color.Transparent, Color.White.copy(alpha = .09f), Color.Transparent)), radius)
            drawCircle(tint.copy(alpha = .7f), radius * .26f)
            drawCircle(Color.Black.copy(alpha = .22f), radius * .19f, style = Stroke(radius * .025f))
            drawCircle(Color(0xff09090b), radius * .045f)
            drawCircle(Color.White.copy(alpha = .35f), radius * .05f, style = Stroke(.7.dp.toPx()))
        }
        AnimatedContent(frame, Modifier.fillMaxSize(), contentKey = { it.track }, transitionSpec = {
            val time = if (reducedMotion) 0 else 620
            (slideInHorizontally(tween(time, easing = FastOutSlowInEasing)) { it * direction / 3 } +
                scaleIn(tween(time, easing = FastOutSlowInEasing), initialScale = .78f) + fadeIn(tween(time / 2))) togetherWith
                (slideOutHorizontally(tween(time, easing = FastOutSlowInEasing)) { -it * direction / 3 } +
                    scaleOut(tween(time, easing = FastOutSlowInEasing), targetScale = .74f) + fadeOut(tween(time * 3 / 4))) using
                SizeTransform(clip = false)
        }, label = "album sleeves") { sleeve ->
            Box(Modifier.fillMaxSize().shadow(if (paused) 13.dp else 19.dp, shape,
                ambientColor = Color.Black.copy(alpha = .19f), spotColor = Color.Black.copy(alpha = .19f))
                .clip(shape).background(Color.Black)) {
                if (sleeve.image != null) Image(sleeve.image, "歌曲封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().semantics { contentDescription = "暂无歌曲封面" })
            }
        }
    }
}
