package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.NativeLyricsSurface

/** Host adapter only: the drawing layer receives values and callbacks, never Workshop API objects. */
@Composable
internal fun NativeLyricsContent(runtime: AmllRuntime, modifier: Modifier, onTime: (() -> Number)?, showSecondary: Boolean) {
    val presentation by runtime.presentation.collectAsState()
    val options by runtime.preferences.options.collectAsState()
    // This hook occupies only the lyrics column. The host owns the whole-page backdrop.
    NativeLyricsSurface(presentation, options.copy(background = false), modifier, {
        runCatching { onTime?.invoke()?.toLong() }.getOrNull()?.coerceAtLeast(0) ?: runtime.clock.now()
    }, { WorkshopApi.playback.seekTo(it) }, showSecondary, { runtime.clock.playingState() }, { runtime.clock.seekGeneration })
}
