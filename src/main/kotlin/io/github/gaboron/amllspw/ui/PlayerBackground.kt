package io.github.gaboron.amllspw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb

/** Keep the outgoing cover while artwork loads; all texture layers share one continuous clock. */
@Composable
internal fun PlayerBackground(presentation: LyricsPresentation, reducedMotion: Boolean) {
    var artwork by remember { mutableStateOf(presentation.backgroundArtwork) }
    LaunchedEffect(presentation.trackKey, presentation.backgroundArtwork, presentation.artworkLoading) {
        if (!presentation.artworkLoading) artwork = presentation.backgroundArtwork
    }
    val flow = remember { runCatching { ArtworkFlowRenderer() }.getOrNull() }
    val fallbackColors = if (flow == null) List(4) { index ->
        animateColorAsState(Color(presentation.palette.getOrElse(index) { 0xff233149.toInt() }),
            tween(if (reducedMotion) 0 else 1600)).value
    } else emptyList()
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
    DisposableEffect(flow) { onDispose { flow?.close() } }
    Canvas(Modifier.fillMaxSize()) {
        if (flow == null) drawFluidBackground(fallbackColors.map { it.toArgb() }, seconds)
        else drawIntoCanvas {
            flow.draw(it.skiaCanvas, size.width, size.height, seconds, artwork, presentation.palette, reducedMotion)
        }
    }
}
