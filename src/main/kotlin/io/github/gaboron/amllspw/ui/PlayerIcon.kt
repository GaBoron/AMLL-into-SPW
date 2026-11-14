package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter

/** Original react-full SVG assets; currentColor is supplied by Compose instead of the CSS cascade. */
@Composable
@Suppress("DEPRECATION")
internal fun PlayerIcon(name: String, description: String?, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val density = LocalDensity.current
    val painter = remember(name, density) {
        val svg = checkNotNull(AmllPlayerStyle::class.java.getResourceAsStream("/player/$name.svg"))
            .bufferedReader().use { it.readText() }.replace("currentColor", "#ffffff")
        // Plugin JAR assets use this classloader; the host supplies the existing Compose runtime.
        svg.byteInputStream().use { loadSvgPainter(it, density) }
    }
    Image(painter, description, modifier, colorFilter = ColorFilter.tint(Color.White), alpha = alpha)
}
