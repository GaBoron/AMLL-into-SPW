package io.github.gaboron.amllspw.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText

/** Upstream floats and masks the whole word container; emphasis affects base characters only. */
internal fun DrawScope.paintRuby(layout: LineLayout, timeMs: Long, highlight: Float, options: RenderOptions, color: Color) {
    for (run in layout.rubyRuns) {
        val progress = if (layout.line.hasWordTiming) run.motion.progress(timeMs) else 1f
        val lift = if (options.scale && !options.reducedMotion)
            run.motion.floatLift(timeMs) * layout.emPx * highlight else 0f
        translate(top = -lift) {
            if (!layout.line.hasWordTiming || highlight <= .001f) {
                drawText(run.text, color = color, alpha = .2f + .8f * highlight, topLeft = run.offset)
            } else {
                drawText(run.text, color = color, alpha = .2f + .2f * highlight, topLeft = run.offset)
                if (progress > 0 && highlight > 0) {
                    if (progress >= .999f) drawText(run.text, color = color, alpha = highlight, topLeft = run.offset)
                    else {
                        val edge = run.maskLeft + (run.maskRight - run.maskLeft) * progress - run.offset.x
                        val fadeWidth = layout.emPx * .5f
                        drawText(run.text, brush = Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)),
                            edge - fadeWidth / 2, edge + fadeWidth / 2), alpha = highlight, topLeft = run.offset)
                    }
                }
            }
        }
    }
}
