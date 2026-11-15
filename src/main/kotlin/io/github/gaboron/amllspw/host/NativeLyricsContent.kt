package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.gaboron.amllspw.ui.NativeLyricsSurface
import io.github.gaboron.amllspw.ui.LyricsScene
import io.github.gaboron.amllspw.ui.LocalPlayerLyricFraming
import io.github.gaboron.amllspw.timeline.PlaybackClock

/** Host adapter only: the drawing layer receives values and callbacks, never Workshop API objects. */
@Composable
internal fun NativeLyricsContent(runtime: AmllRuntime, modifier: Modifier, showSecondary: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val options by runtime.preferences.options.collectAsState()
    val framing = LocalPlayerLyricFraming.current
    val sceneOptions = if (options.typography.fontSizeSp == null && framing.fontSize != null)
        options.copy(typography = options.typography.copy(fontSizeSp = framing.fontSize)) else options
    // The surrounding page owns the backdrop; this surface only draws the lyrics column.
    LyricsContextMenu(runtime, presentation, options, modifier) {
        LyricsScene(presentation, sceneOptions, Modifier.fillMaxSize(), runtime::nextPoem, framing.color, framing.horizontalPaddingDp) { scene ->
            // A departing track keeps its last playhead instead of jumping to zero during the fade.
            val lastPlayback = remember(scene.trackKey) { arrayOf(PlaybackClock.Sample(0L, null, 0L, null)) }
            val readPlayback = {
                // The host's onTime callback freezes when its own lyric document is empty (e.g. raw TTML).
                if (scene.trackKey == presentation.trackKey) lastPlayback[0] = runtime.clock.sample(scene.trackKey)
                else lastPlayback[0] = lastPlayback[0].copy(playing = false)
                lastPlayback[0]
            }
            NativeLyricsSurface(scene, sceneOptions.copy(background = false), Modifier.fillMaxSize(),
                { readPlayback().positionMs }, { runtime.seekTo(scene.trackKey, it) }, showSecondary,
                onPlaybackSample = readPlayback, alignPosition = framing.position, alignAnchor = framing.anchor,
                foregroundColor = framing.color, horizontalPaddingDp = framing.horizontalPaddingDp)
        }
    }
}
