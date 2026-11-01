package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.gaboron.amllspw.ui.NativeLyricsSurface
import io.github.gaboron.amllspw.ui.LyricsScene
import io.github.gaboron.amllspw.timeline.PlaybackClock

/** Host adapter only: the drawing layer receives values and callbacks, never Workshop API objects. */
@Composable
internal fun NativeLyricsContent(runtime: AmllRuntime, modifier: Modifier, showSecondary: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val options by runtime.preferences.options.collectAsState()
    // This hook occupies only the lyrics column. The host owns the whole-page backdrop.
    LyricsScene(presentation, options, modifier, runtime::nextPoem) { scene ->
        // A departing track keeps its last playhead instead of jumping to zero during the fade.
        val lastPlayback = remember(scene.trackKey) { arrayOf(PlaybackClock.Sample(0L, null, 0L, null)) }
        val readPlayback = {
            // The host's onTime callback freezes when its own lyric document is empty (e.g. raw TTML).
            if (scene.trackKey == presentation.trackKey) lastPlayback[0] = runtime.clock.sample(scene.trackKey)
            else lastPlayback[0] = lastPlayback[0].copy(playing = false)
            lastPlayback[0]
        }
        NativeLyricsSurface(scene, options.copy(background = false), Modifier.fillMaxSize(),
            { readPlayback().positionMs }, { runtime.seekTo(scene.trackKey, it) }, showSecondary,
            onPlaybackSample = readPlayback)
    }
}
