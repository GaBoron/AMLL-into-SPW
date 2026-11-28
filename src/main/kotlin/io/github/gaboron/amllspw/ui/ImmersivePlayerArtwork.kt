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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.cover.ImmersiveCover
import io.github.gaboron.amllspw.cover.ImmersiveBackdropSamples
import io.github.gaboron.amllspw.cover.ImmersiveCoverRenderer
import io.github.gaboron.amllspw.cover.ImmersiveInk
import io.github.gaboron.amllspw.cover.immersiveInk
import io.github.gaboron.amllspw.cover.immersiveInkFamily
import io.github.gaboron.amllspw.cover.prepareImmersiveCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect

internal data class ImmersiveArtwork(val effect: RuntimeEffect, val cover: ImmersiveCover)
internal data class ImmersivePlayerInk(val lyrics: ImmersiveInk, val controls: ImmersiveInk,
    val header: ImmersiveInk, val corner: ImmersiveInk)

internal val LocalImmersiveControlInk = staticCompositionLocalOf<ImmersiveInk?> { null }

@Composable
internal fun ImmersiveControlInk(ink: ImmersiveInk?, content: @Composable () -> Unit) {
    val primary = ink?.let { Color(it.primary) } ?: LocalPlayerColor.current
    CompositionLocalProvider(LocalPlayerColor provides primary, LocalImmersiveControlInk provides ink, content = content)
}

@Composable
internal fun rememberImmersivePlayerInk(artwork: ImmersiveArtwork, style: AmllPlayerStyle, targetCover: Rect,
    lyricTop: Float, hiddenLyrics: Boolean, accent: Int, colored: Boolean): ImmersivePlayerInk {
    val density = LocalDensity.current.density
    val samples = remember(artwork, style.width, style.height, targetCover, density) {
        val pageAnchor = Rect(targetCover.left * density, targetCover.top * density,
            targetCover.right * density, targetCover.bottom * density)
        if (style.width > 0f && style.height > 0f) runCatching {
            ImmersiveBackdropSamples.render(artwork.effect, artwork.cover, style.width * density,
                style.height * density, pageAnchor)
        }.getOrNull() else null
    }
    return remember(samples, artwork, style, targetCover, lyricTop, hiddenLyrics, accent, colored) {
        val fallback = artwork.cover.surroundings.toIntArray()
        fun colors(left: Float, top: Float, right: Float, bottom: Float) =
            samples?.colors(Rect(left * density, top * density, right * density, bottom * density)) ?: fallback
        val lyrics = colors(style.lyricContentLeft, lyricTop, style.lyricContentRight,
            style.height - if (style.vertical) 0f else 48f)
        val controlsLeft = if (style.vertical) style.edge else targetCover.left
        val controlsRight = if (style.vertical) style.width - style.edge else targetCover.right
        val controlsTop = if (style.vertical) targetCover.bottom + style.em else style.controlsTop
        val controls = colors(controlsLeft, controlsTop, controlsRight,
            if (style.vertical) style.height - 24f else controlsTop + style.controlsHeight)
        val header = if (style.vertical && !hiddenLyrics)
            colors(style.edge + style.smallCover + style.em, style.smallHeaderTop,
                style.width - style.edge, style.smallHeaderTop + style.smallCover) else controls
        val cornerRight = style.width - style.em * 2
        val corner = colors(cornerRight - 40, style.height - 56, cornerRight, style.height - 16)
        val family = if (colored) immersiveInkFamily(accent, fallback) else null
        ImmersivePlayerInk(immersiveInk(lyrics, family, colored), immersiveInk(controls, family, colored),
            immersiveInk(header, family, colored), immersiveInk(corner, family, colored))
    }
}

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
    ink: ImmersivePlayerInk,
) {
    val dark = listOf(ink.lyrics.dark, ink.controls.dark, ink.header.dark, ink.corner.dark)
    AnimatedContent(artwork.cover to dark, Modifier.fillMaxSize(), contentKey = { it.first }, transitionSpec = {
        // Hold the outgoing image opaque while the incoming extension and core fade in together.
        val duration = if (reducedMotion || initialState.second != targetState.second) 0 else 480
        fadeIn(tween(duration)) togetherWith ExitTransition.KeepUntilTransitionsFinished
    }, label = "immersive cover") { (cover, _) ->
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
