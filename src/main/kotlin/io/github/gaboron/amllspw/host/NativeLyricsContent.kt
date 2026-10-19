package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.gaboron.amllspw.ui.NativeLyricsSurface
import io.github.gaboron.amllspw.ui.LyricsScene

/** Host adapter only: the drawing layer receives values and callbacks, never Workshop API objects. */
@Composable
internal fun NativeLyricsContent(runtime: AmllRuntime, modifier: Modifier, showSecondary: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val options by runtime.preferences.options.collectAsState()
    // This hook occupies only the lyrics column. The host owns the whole-page backdrop.
    LyricsScene(presentation, options, modifier, runtime::nextPoem) { scene ->
        // A departing track keeps its last playhead instead of jumping to zero during the fade.
        val lastPosition = remember(scene.trackKey) { longArrayOf(0L) }
        NativeLyricsSurface(scene, options.copy(background = false), Modifier.fillMaxSize(), {
            // The host's onTime callback freezes when its own lyric document is empty (e.g. raw TTML).
            if (scene.trackKey == presentation.trackKey) lastPosition[0] = runtime.clock.now(scene.trackKey)
            lastPosition[0]
        }, { runtime.seekTo(scene.trackKey, it) }, showSecondary,
            { if (scene.trackKey == presentation.trackKey) runtime.clock.playingState(scene.trackKey) else false },
            { runtime.clock.seekGeneration })
    }
}
