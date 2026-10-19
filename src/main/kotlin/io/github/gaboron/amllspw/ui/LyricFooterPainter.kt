package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.text.drawText
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt

/** Ordinary informational text: no lyric hit boxes, scaling, word masks or seek action. */
internal class LyricFooterPainter : AutoCloseable {
    private val blurPaints = (0..10).map { level -> Paint().apply {
        if (level > 0) ImageFilter.makeBlur(level * .5f, level * .5f, FilterTileMode.DECAL).use { imageFilter = it }
    } }

    fun DrawScope.paintFooter(layout: LyricFooterLayout?, motion: LyricFooterMotion, left: Float, color: Color) {
        if (layout == null || motion.y + layout.height < -60 || motion.y > size.height + 60) return
        val native = drawContext.canvas.skiaCanvas
        native.saveLayer(null, blurPaints[(motion.blur * 2).roundToInt().coerceIn(0, 10)])
        try {
            drawText(layout.text, color = color, alpha = motion.opacity, topLeft = Offset(left, motion.y + layout.padding))
            // Mask this layer only, so long footers can scroll through the edges without hiding
            // their entire text block or affecting the lyric layer beneath it.
            drawRect(Brush.verticalGradient(
                0f to Color.Transparent, (45f / size.height).coerceIn(0f, .4f) to Color.White,
                (1f - 70f / size.height).coerceIn(.6f, 1f) to Color.White, 1f to Color.Transparent,
            ), blendMode = BlendMode.DstIn)
        } finally { native.restore() }
    }

    override fun close() { blurPaints.forEach { it.close() } }
}
