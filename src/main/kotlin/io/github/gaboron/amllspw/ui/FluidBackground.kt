package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/** Continuous soft color fields from the song artwork, without a bitmap animation loop. */
internal fun DrawScope.drawFluidBackground(colors: List<Int>, elapsedSeconds: Float) {
    drawRect(Color(colors.first()).copy(alpha = .94f))
    colors.forEachIndexed { i, color ->
        val angle = elapsedSeconds * (.11f + i * .013f) + i * 1.7f
        val center = Offset(size.width * (.5f + .4f * sin(angle)), size.height * (.5f + .43f * cos(angle * .83f + i)))
        val radius = maxOf(size.width, size.height) * (.8f + .08f * sin(angle * .7f))
        drawCircle(Brush.radialGradient(listOf(Color(color).copy(alpha = .85f), Color(color).copy(alpha = 0f)), center, radius),
            radius, center, blendMode = BlendMode.Screen)
    }
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .28f))))
}
