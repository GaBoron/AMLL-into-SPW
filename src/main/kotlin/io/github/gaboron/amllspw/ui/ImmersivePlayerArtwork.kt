package io.github.gaboron.amllspw.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.cover.ImmersiveCover
import io.github.gaboron.amllspw.cover.ImmersiveCoverRenderer
import io.github.gaboron.amllspw.cover.prepareImmersiveCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect

internal data class ImmersiveArtwork(val effect: RuntimeEffect, val cover: ImmersiveCover)

@Composable
internal fun rememberImmersiveArtwork(presentation: LyricsPresentation): ImmersiveArtwork? {
    val effect = remember { runCatching { ImmersiveCoverRenderer.makeEffect() }.getOrNull() } ?: return null
    DisposableEffect(effect) { onDispose { effect.close() } }
    val cache = remember { LinkedHashMap<ImageBitmap, ImmersiveCover>(4, .75f, true) }
    var current by remember { mutableStateOf<ImmersiveCover?>(null) }
    LaunchedEffect(presentation.artwork, presentation.artworkLoading) {
        if (presentation.artworkLoading) return@LaunchedEffect
        val original = presentation.artwork
        if (original == null) {
            current = null
            return@LaunchedEffect
        }
        current = cache[original] ?: try {
            withContext(Dispatchers.Default) { prepareImmersiveCover(original) { ensureActive() } }.also {
                cache[original] = it
                if (cache.size > 3) cache.remove(cache.keys.first())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            System.err.println("AMLL: 沉浸式封面生成失败：${failure.message}")
            null
        }
    }
    return current?.let { ImmersiveArtwork(effect, it) }
}

@Composable
internal fun ImmersivePlayerBackground(
    artwork: ImmersiveArtwork,
    anchor: Rect,
    reducedMotion: Boolean,
) {
    AnimatedContent(artwork.cover, Modifier.fillMaxSize(), transitionSpec = {
        // Hold the outgoing image opaque while the incoming extension and core fade in together.
        fadeIn(tween(if (reducedMotion) 0 else 480)) togetherWith ExitTransition.KeepUntilTransitionsFinished
    }, label = "immersive cover") { cover ->
        val renderer = remember(artwork.effect, cover) { ImmersiveCoverRenderer(artwork.effect, cover) }
        DisposableEffect(renderer) { onDispose { renderer.close() } }
        Canvas(Modifier.fillMaxSize()) {
            val bounds = SkiaRect.makeLTRB(anchor.left.dp.toPx(), anchor.top.dp.toPx(),
                anchor.right.dp.toPx(), anchor.bottom.dp.toPx())
            drawIntoCanvas { renderer.draw(it.skiaCanvas, size.width, size.height, bounds) }
        }
    }
}

@Composable
internal fun ImmersiveCoverButton(modifier: Modifier, toggleLabel: String, onToggleLyrics: () -> Unit) {
    Box(modifier.clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggleLyrics)
        .semantics { contentDescription = "歌曲封面" })
}
