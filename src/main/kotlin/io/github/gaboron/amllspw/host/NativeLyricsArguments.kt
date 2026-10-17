package io.github.gaboron.amllspw.host

import androidx.compose.ui.Modifier

/** LyricsContainer takes onLineTime before onTime; the first callback is not a playback clock. */
internal data class NativeLyricsArguments(val modifier: Modifier, val onTime: (() -> Number)?) {
    companion object {
        const val CLOCK_INDEX = 2
        fun read(arguments: List<Any?>): NativeLyricsArguments {
            @Suppress("UNCHECKED_CAST")
            val clock = arguments.getOrNull(CLOCK_INDEX) as? (() -> Number)
            return NativeLyricsArguments(arguments.getOrNull(4) as? Modifier ?: Modifier, clock)
        }
    }
}
