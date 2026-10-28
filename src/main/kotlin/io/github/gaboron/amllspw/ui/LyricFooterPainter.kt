package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.translate
import org.jetbrains.skia.Rect as SkiaRect

/** Ordinary informational text: no lyric hit boxes, scaling, word masks or seek action. */
internal class LyricFooterPainter : AutoCloseable {
    private var closed = false
    private val textBlur = LyricTextBlur()

    fun DrawScope.paintFooter(layout: LyricFooterLayout?, motion: LyricFooterMotion, left: Float, color: Color) {
        if (layout == null || motion.y + layout.height < -60 || motion.y > size.height + 60) return
        for (row in layout.rows) {
            val y = motion.y + layout.padding + row.top
            if (y + row.text.size.height < -60 || y > size.height + 60) continue
            translate(left, y) {
                val padding = MAX_LYRIC_BLUR_RADIUS * 3f
                val bounds = SkiaRect.makeLTRB(-padding, -padding,
                    row.text.size.width + padding, row.text.size.height + padding)
                with(textBlur) {
                    val viewport = SkiaRect.makeLTRB(-left, -y, size.width - left, size.height - y)
                    paint(row, bounds, motion.blur, color, opacity = motion.opacity, viewport = viewport) {
                        drawText(row.text, color = Color.White, alpha = row.opacity, topLeft = Offset.Zero)
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        textBlur.close()
    }
}
