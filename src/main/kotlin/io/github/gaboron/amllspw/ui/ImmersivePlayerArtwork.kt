package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gaboron.amllspw.cover.ImmersiveCover
import io.github.gaboron.amllspw.cover.ImmersiveBackdropSamples
import io.github.gaboron.amllspw.cover.ImmersiveBackdropTone
import io.github.gaboron.amllspw.cover.ImmersiveCoverRenderer
import io.github.gaboron.amllspw.cover.ImmersiveInk
import io.github.gaboron.amllspw.cover.blendImmersiveColors
import io.github.gaboron.amllspw.cover.immersiveInkFamily
import io.github.gaboron.amllspw.cover.immersivePagePalette
import io.github.gaboron.amllspw.cover.prepareImmersiveCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect

internal data class ImmersiveArtwork(val effect: RuntimeEffect, val cover: ImmersiveCover, val accent: Int)
internal data class ImmersiveArtworkLayer(val artwork: ImmersiveArtwork, val weight: Float)
internal data class ImmersivePlayerPalette(val ink: ImmersiveInk, val tones: List<ImmersiveBackdropTone?>)
private data class ImmersiveArtworkColors(
    val regions: List<IntArray>?, val surroundings: IntArray, val tone: ImmersiveBackdropTone?,
)

internal val LocalImmersiveControlInk = staticCompositionLocalOf<ImmersiveInk?> { null }

@Composable
internal fun ImmersiveControlInk(ink: ImmersiveInk?, content: @Composable () -> Unit) {
    val primary = ink?.let { Color(it.primary) } ?: LocalPlayerColor.current
    CompositionLocalProvider(LocalPlayerColor provides primary, LocalImmersiveControlInk provides ink, content = content)
}

@Composable
internal fun rememberImmersivePlayerPalette(layers: List<ImmersiveArtworkLayer>, style: AmllPlayerStyle, targetCover: Rect,
    lyricTop: Float, hiddenLyrics: Boolean, colored: Boolean, hasCornerControls: Boolean = false): ImmersivePlayerPalette {
    val density = LocalDensity.current.density
    val regions = remember(style, targetCover, lyricTop, hiddenLyrics, density, hasCornerControls) {
        fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
            Rect(left * density, top * density, right * density, bottom * density)
        val lyrics = bounds(style.lyricContentLeft, lyricTop, style.lyricContentRight,
            style.height - if (style.vertical) 0f else 48f)
        val controlsLeft = if (style.vertical) style.edge else targetCover.left
        val controlsRight = if (style.vertical) style.width - style.edge else targetCover.right
        val controlsTop = if (style.vertical) targetCover.bottom + style.em else style.controlsTop
        val controls = bounds(controlsLeft, controlsTop, controlsRight,
            if (style.vertical) style.height - 24f else controlsTop + style.controlsHeight)
        val header = if (style.vertical && !hiddenLyrics)
            bounds(style.edge + style.smallCover + style.em, style.smallHeaderTop,
                style.width - style.edge, style.smallHeaderTop + style.smallCover) else controls
        val cornerRight = style.width - style.em * 2
        buildList {
            if (!hiddenLyrics) add(lyrics)
            add(header)
            if (hasCornerControls) add(bounds(cornerRight - 40, style.height - 56, cornerRight, style.height - 16))
        }
    }
    val samples = layers.map { layer ->
        val artwork = layer.artwork
        key(artwork) {
            remember(artwork, regions, style.width, style.height, targetCover, density) {
                val pageAnchor = Rect(targetCover.left * density, targetCover.top * density,
                    targetCover.right * density, targetCover.bottom * density)
                val sampled = if (style.width > 0f && style.height > 0f) runCatching {
                    val samples = ImmersiveBackdropSamples.render(artwork.effect, artwork.cover, style.width * density,
                        style.height * density, pageAnchor)
                    regions.map(samples::colors)
                }.getOrNull() else null
                val surroundings = artwork.cover.surroundings.toIntArray()
                // Fade each finished backdrop as a unit. Recalculating its correction on every
                // frame could switch between lifting shadows and dimming highlights mid-transition.
                val tone = immersivePagePalette(sampled ?: listOf(surroundings), null).backdropTone
                fun corrected(colors: IntArray): IntArray {
                    return if (tone == null) colors else IntArray(colors.size) { tone.color(colors[it]) }
                }
                ImmersiveArtworkColors(sampled?.map(::corrected), corrected(surroundings), tone)
            }
        }
    }
    return remember(layers, samples, colored) {
        val weights = layers.map { it.weight }
        val surroundings = layers.map { it.artwork.cover.surroundings.toIntArray() }
        val blended = if (samples.all { it.regions != null }) {
            List(regions.size) { region -> blendImmersiveColors(samples.map { checkNotNull(it.regions)[region] }, weights) }
        } else listOf(blendImmersiveColors(samples.map { it.surroundings }, weights))
        val accent = blendImmersiveColors(layers.map { intArrayOf(it.artwork.accent) }, weights).single()
        val family = if (colored) immersiveInkFamily(accent, blendImmersiveColors(surroundings, weights)) else null
        val page = immersivePagePalette(blended, family, adjustBackdrop = false)
        ImmersivePlayerPalette(page.ink, samples.map { it.tone })
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
    val cover = current ?: return null
    val accent = remember(cover) { presentation.lyricGlowColor }
    return ImmersiveArtwork(effect, cover, accent)
}

@Composable
internal fun rememberImmersiveArtworkLayers(artwork: ImmersiveArtwork, reducedMotion: Boolean): List<ImmersiveArtworkLayer> {
    var visible by remember(artwork.effect) { mutableStateOf(listOf(ImmersiveArtworkLayer(artwork, 1f))) }
    LaunchedEffect(artwork, reducedMotion) {
        val incoming = ImmersiveArtworkLayer(artwork, 1f)
        if (!reducedMotion && visible != listOf(incoming)) {
            // Retarget from the visible mix when the user skips through several tracks.
            val outgoing = visible.sortedByDescending { it.weight }.take(3)
            val total = outgoing.sumOf { it.weight.toDouble() }.toFloat()
            animate(0f, 1f, animationSpec = tween(480)) { progress, _ ->
                val weights = outgoing.associate { it.artwork to it.weight / total * (1f - progress) }.toMutableMap()
                weights[artwork] = (weights[artwork] ?: 0f) + progress
                visible = weights.mapNotNull { (item, weight) ->
                    if (weight > 0f) ImmersiveArtworkLayer(item, weight) else null
                }
            }
        }
        visible = listOf(incoming)
    }
    return visible
}

@Composable
internal fun ImmersivePlayerBackground(layers: List<ImmersiveArtworkLayer>, anchor: Rect,
    tones: List<ImmersiveBackdropTone?>) {
    Box(Modifier.fillMaxSize()) {
        var covered = 0f
        for ((index, layer) in layers.withIndex()) {
            val (artwork, weight) = layer
            covered += weight
            // SrcOver opacity is conditional on the weight already below this layer.
            val opacity = (weight / covered).coerceIn(0f, 1f)
            key(artwork) {
                val renderer = remember(artwork.effect, artwork.cover) { ImmersiveCoverRenderer(artwork.effect, artwork.cover) }
                DisposableEffect(renderer) { onDispose { renderer.close() } }
                val modifier = if (opacity == 1f) Modifier else Modifier.graphicsLayer { alpha = opacity }
                Canvas(modifier.fillMaxSize()) {
                    val bounds = SkiaRect.makeLTRB(anchor.left.dp.toPx(), anchor.top.dp.toPx(),
                        anchor.right.dp.toPx(), anchor.bottom.dp.toPx())
                    drawIntoCanvas { renderer.draw(it.skiaCanvas, size.width, size.height, bounds, tones[index]) }
                }
            }
        }
    }
}

@Composable
internal fun ImmersiveCoverButton(modifier: Modifier, toggleLabel: String, onToggleLyrics: () -> Unit) {
    Box(modifier.clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggleLyrics)
        .semantics { contentDescription = "歌曲封面" })
}
