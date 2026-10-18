package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.timeline.LyricTimeline
import io.github.gaboron.amllspw.timeline.FramePlaybackClock
import io.github.gaboron.amllspw.timeline.InterludeAnimation
import kotlin.math.*

/** Draws into the host composition. No Swing surface, browser, or additional window is created. */
@Composable
internal fun NativeLyricsSurface(presentation: LyricsPresentation, options: RenderOptions, modifier: Modifier,
    onTime: () -> Long, onSeek: (Long) -> Unit, showSecondary: Boolean,
    onPlaying: () -> Boolean? = { null }, onSeekGeneration: () -> Long = { 0 }) {
    val measurer = rememberTextMeasurer(cacheSize = 1_024)
    val painter = remember(measurer) { LyricLinePainter(measurer) }
    DisposableEffect(painter) { onDispose { painter.close() } }
    val viewport = remember(presentation.trackKey, presentation.document) { ViewportMotion() }
    val pointer = remember(viewport) { LyricPointerState() }
    val timeline = remember(presentation.document) { LyricTimeline(presentation.document) }
    val interlude = remember(presentation.trackKey, presentation.document, presentation.offsetMs) { InterludeAnimation() }
    val preciseTime by rememberUpdatedState(onTime)
    val seekTo by rememberUpdatedState(onSeek)
    val offsetMs by rememberUpdatedState(presentation.offsetMs)
    val playingState by rememberUpdatedState(onPlaying)
    val seekGeneration by rememberUpdatedState(onSeekGeneration)
    val clock = remember(presentation.trackKey, presentation.document, presentation.offsetMs) { FramePlaybackClock() }
    val density = LocalDensity.current
    var frameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameTime = it } }
    val cache = remember(presentation.document, density, showSecondary, options.romanization) { LayoutCache() }
    val lyricColor = if (options.coloredLyrics) Color(presentation.lyricColor) else Color.White
    val scroll = rememberScrollableState { delta -> viewport.scroll(delta); delta }
    val hovering = pointer.position?.let { point -> viewport.hits.any { it.bounds.contains(point) } } == true
    Canvas(modifier.fillMaxSize().clipToBounds().lyricHover(pointer)
        .pointerHoverIcon(if (hovering) PointerIcon.Hand else PointerIcon.Default)
        .scrollable(scroll, Orientation.Vertical)
        .pointerInput(viewport) {
            detectTapGestures(onTap = { point ->
                viewport.hits.firstOrNull { it.bounds.contains(point) }?.let {
                    seekTo((it.positionMs + offsetMs).coerceAtLeast(0)); viewport.follow()
                }
            }, onDoubleTap = { viewport.follow() })
        }
        .onKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionDown -> { viewport.scroll(-64f); true }
                Key.DirectionUp -> { viewport.scroll(64f); true }
                Key.PageDown -> { viewport.scroll(-viewport.height * .7f); true }
                Key.PageUp -> { viewport.scroll(viewport.height * .7f); true }
                Key.MoveHome, Key.Escape -> { viewport.follow(); true }
                else -> false
            }
        }.focusable().semantics {
            contentDescription = "动态歌词，点击歌词跳转，双击回到当前歌词"
            customActions = listOf(CustomAccessibilityAction("回到当前歌词") { viewport.follow(); true })
        }) {
        val nanos = frameTime
        val dt = if (cache.lastFrame == 0L) 0f else ((nanos - cache.lastFrame) / 1e9f).coerceIn(0f, .064f)
        cache.lastFrame = nanos
        val playing = playingState()
        val hostPosition = clock.sample(preciseTime(), nanos, playing, seekGeneration())
        val position = (hostPosition - presentation.offsetMs).coerceAtLeast(0)
        val seek = clock.discontinuity || (cache.lastPosition >= 0 && abs(position - cache.lastPosition) > 1_500)
        cache.lastPosition = position
        val fontSize = (size.width / density.density / 13.5f).coerceIn(24f, 42f)
        val margin = size.width * .075f
        val width = (size.width - margin * 2).roundToInt().coerceAtLeast(1)
        if (cache.width != width) {
            cache.width = width
            cache.groups = layoutLyrics(presentation.document, measurer, width, fontSize, showSecondary, options.romanization)
        }
        viewport.height = size.height
        val frame = timeline.frame(position)
        interlude.update(frame.interlude, position, dt * 1_000.0, playing, seek)
        val interval = interlude.layoutInterval
        val motions = viewport.update(cache.groups, frame.focus, position, dt, nanos, seek,
            interval?.beforeGroup, interludeSlotHeight(fontSize * density.density, size.height), playing,
            options.hidePassed && playing != false && !viewport.isManual(nanos) && !scroll.isScrollInProgress, !options.spring || options.reducedMotion,
            interlude.restarted, clock.playbackRate)
        val manual = viewport.isManual(nanos) || scroll.isScrollInProgress
        cache.backgroundTime += dt * if (position != cache.backgroundPosition) 1f else 0f
        cache.backgroundPosition = position
        if (options.background) drawFluidBackground(presentation.palette, if (options.reducedMotion) 0f else cache.backgroundTime)
        if (cache.groups.isEmpty() && !presentation.loading) {
            val empty = measurer.measure(AnnotatedString("暂无可用歌词"), TextStyle(fontSize = 18.sp))
            drawText(empty, color = Color.White.copy(alpha = .6f), topLeft = Offset((size.width - empty.size.width) / 2, size.height * .35f))
        }
        val hits = mutableListOf<ViewportMotion.Hit>()
        for ((index, group) in cache.groups.withIndex()) {
            val motion = motions[index]
            val y = motion.y.value
            if (y + group.height < -60 || y > size.height + 60) continue
            var rowY = y
            val backgroundHeight = group.rows.filter { it.line.background }.sumOf { it.height.toDouble() }.toFloat()
            val groupActive = position >= group.rows.minOf { it.line.startMs } && position < group.rows.maxOf { it.line.endMs }
            for ((voiceIndex, row) in group.rows.withIndex()) {
                val voice = motion.voices[voiceIndex]
                if (voice.opacity.value < .001f || voice.visibility.value < .001f) { rowY += row.height; continue }
                val paintY = rowY + if (row.line.background) motion.backgroundSlide.value / 100 * backgroundHeight else 0f
                val bounds = Rect(margin - 10, paintY, size.width - margin + 10, paintY + row.height)
                val hover = pointer.position?.let { bounds.contains(it) } == true
                voice.hover.update(hover, dt)
                paintRowHover(bounds, voice.hover.value * voice.visibility.value, pointer.pressed, row.emPx)
                val edgeAlpha = minOf((paintY / 45f).coerceIn(0f, 1f), ((size.height - paintY) / 70f).coerceIn(0f, 1f)) * voice.opacity.value.coerceIn(0f, 1f) * voice.visibility.value
                val scale = if (!options.scale || options.reducedMotion) 1f else motion.scale.value * voice.scale.value
                withTransform({
                    translate(margin + row.inset, paintY)
                    scale(scale, scale, Offset(if (row.line.duet) row.main.size.width.toFloat() else 0f, row.height / 2))
                }) {
                    // saveLayer is only needed at viewport edges; inner text draws directly.
                    val native = drawContext.canvas.skiaCanvas
                    val paint = if (edgeAlpha < .999f) org.jetbrains.skia.Paint().apply { alpha = (edgeAlpha * 255).toInt() } else null
                    if (paint != null) native.saveLayer(null, paint)
                    try { with(painter) { paintLine(row, position, groupActive, abs(index - frame.focus), manual, options, voice.highlight.value, lyricColor) } }
                    finally { if (paint != null) { native.restore(); paint.close() } }
                }
                hits += ViewportMotion.Hit(bounds, row.line.startMs)
                rowY += row.height
            }
        }
        viewport.hits = hits
        viewport.interludeY?.let { y ->
            val duet = interval?.beforeGroup?.let { cache.groups.getOrNull(it)?.rows?.firstOrNull()?.line?.duet } == true
            cache.dotsOrigin = Offset(if (duet) size.width - margin - interludeWidth(fontSize * density.density) else margin, y)
        }
        paintInterlude(interlude.snapshot, cache.dotsOrigin.y, cache.dotsOrigin.x, fontSize * density.density, hits.map { it.bounds }, options.reducedMotion, lyricColor)
    }
}

private class LayoutCache {
    var width = -1
    var groups = emptyList<GroupLayout>()
    var lastFrame = 0L
    var lastPosition = -1L
    var backgroundTime = 0f
    var backgroundPosition = 0L
    var dotsOrigin = Offset.Zero
}
