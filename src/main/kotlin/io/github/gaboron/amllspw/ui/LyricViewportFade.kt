package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.RTreeFactory
import org.jetbrains.skia.Rect as SkiaRect

internal fun lyricViewportInRow(size: Size, origin: Offset, scale: Float, pivot: Offset): SkiaRect {
    val inverse = 1f / scale.coerceAtLeast(.001f)
    return SkiaRect.makeLTRB(
        (-origin.x - pivot.x) * inverse + pivot.x,
        (-origin.y - pivot.y) * inverse + pivot.y,
        (size.width - origin.x - pivot.x) * inverse + pivot.x,
        (size.height - origin.y - pivot.y) * inverse + pivot.y,
    )
}

/** Fade the completed, already-blurred pixels at the viewport edge, including wrapped rows. */
internal fun DrawScope.withLyricViewportFade(content: DrawScope.() -> Unit) {
    val native = drawContext.canvas.skiaCanvas
    val viewport = SkiaRect.makeWH(size.width, size.height)
    if (size.width <= 0f || size.height <= 0f) return
    // Record once so motion and hit testing run exactly once. The center is played
    // directly; only the narrow fading strips allocate temporary surfaces.
    PictureRecorder().use { recorder ->
        RTreeFactory().use { tree ->
            val recording = recorder.beginRecording(viewport, tree)
            CanvasDrawScope().draw(this, layoutDirection, recording.asComposeCanvas(), size) { content() }
        }
        recorder.finishRecordingAsPicture().use { picture ->
            val top = (48f / size.height).coerceIn(0f, .4f)
            val bottom = (80f / size.height).coerceIn(0f, .4f)
            val fade = Brush.verticalGradient(
                0f to Color.Transparent,
                top * .25f to Color.White.copy(alpha = .15625f),
                top * .75f to Color.White.copy(alpha = .84375f),
                top to Color.White,
                (1f - bottom) to Color.White,
                (1f - bottom * .75f) to Color.White.copy(alpha = .84375f),
                (1f - bottom * .25f) to Color.White.copy(alpha = .15625f),
                1f to Color.Transparent,
            )
            fun play(bounds: SkiaRect, faded: Boolean) {
                native.save()
                try {
                    native.clipRect(bounds)
                    if (faded) native.saveLayer(bounds, null)
                    try {
                        native.drawPicture(picture)
                        if (faded) drawRect(fade, blendMode = BlendMode.DstIn)
                    } finally { if (faded) native.restore() }
                } finally { native.restore() }
            }
            play(SkiaRect.makeLTRB(0f, top * size.height, size.width, (1f - bottom) * size.height), false)
            play(SkiaRect.makeLTRB(0f, 0f, size.width, top * size.height), true)
            play(SkiaRect.makeLTRB(0f, (1f - bottom) * size.height, size.width, size.height), true)
        }
    }
}
