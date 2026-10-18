package io.github.gaboron.amllspw.lyrics

/** Metadata keeps file order, unknown keys and every distinct value. It is not timed lyric text. */
data class LyricMetadata(val key: String, val values: List<String>)

/** The actual delivery route, independent of any source/author claimed inside the lyric file. */
enum class LyricSource { UNKNOWN, TTML_FILE, EMBEDDED_TTML, TTML_IN_LRC, PLUGIN_TTML, HOST }
