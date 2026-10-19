package io.github.gaboron.amllspw.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Keeps the outgoing document/poem alive while switching between the two native surfaces. */
@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun LyricsScene(presentation: LyricsPresentation, options: RenderOptions, modifier: Modifier,
    onNextPoem: (String) -> Unit, lyrics: @Composable (LyricsPresentation) -> Unit) {
    val transition = updateTransition(presentation, label = "lyrics scene")
    transition.Crossfade(modifier = modifier, animationSpec = tween(if (options.reducedMotion) 0 else 320),
        contentKey = {
            it.trackKey to when {
                it.loading -> "loading"
                it.isInstrumental && it.poem != null -> "instrumental"
                else -> "lyrics"
            }
        }) { scene ->
        val poem = scene.poem
        if (scene.isInstrumental && poem != null) {
            val footer = remember(scene.document, scene.footer) { LyricFooterContent.from(scene.document, scene.footer) }
            InstrumentalPoetrySurface(poem, options, scene.lyricColor, Modifier.fillMaxSize(), footer) { onNextPoem(scene.trackKey) }
        } else {
            lyrics(scene)
        }
    }
}
