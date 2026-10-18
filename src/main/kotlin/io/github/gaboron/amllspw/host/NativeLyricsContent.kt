package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.gaboron.amllspw.ui.NativeLyricsSurface

/** Host adapter only: the drawing layer receives values and callbacks, never Workshop API objects. */
@Composable
internal fun NativeLyricsContent(runtime: AmllRuntime, modifier: Modifier, showSecondary: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val options by runtime.preferences.options.collectAsState()
    // This hook occupies only the lyrics column. The host owns the whole-page backdrop.
    NativeLyricsSurface(presentation, options.copy(background = false), modifier, {
        // The host's onTime callback freezes when its own lyric document is empty (e.g. raw TTML).
        runtime.clock.now(presentation.trackKey)
    }, { runtime.seekTo(presentation.trackKey, it) }, showSecondary,
        { runtime.clock.playingState(presentation.trackKey) }, { runtime.clock.seekGeneration })
}
