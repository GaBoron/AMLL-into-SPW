package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.LyricDocument
import io.github.gaboron.amllspw.lyrics.LyricSource
import io.github.gaboron.amllspw.lyrics.InstrumentalLyrics
import io.github.gaboron.amllspw.footer.FooterContribution

internal data class LyricFooterSection(val text: String, val attribution: Boolean = false)

/** Native bottom-content slot, kept outside the timed lyric groups and their interactions. */
internal data class LyricFooterContent(val fields: List<Pair<String, String>> = emptyList(),
    val contributions: List<FooterContribution> = emptyList()) {
    val sections: List<LyricFooterSection> = buildList {
        if (fields.isNotEmpty()) {
            add(LyricFooterSection(format(fields)))
            add(LyricFooterSection("From: AMLL into SPW", attribution = true))
        }
        for (entry in contributions) {
            add(LyricFooterSection(format(entry.fields)))
            add(LyricFooterSection("From: ${entry.providerName}", attribution = true))
        }
    }
    val text: String = sections.joinToString("\n") { it.text }

    companion object {
        fun from(document: LyricDocument, contributions: List<FooterContribution> = emptyList()): LyricFooterContent =
            LyricFooterContent(documentFields(document), contributions)

        private fun documentFields(document: LyricDocument): List<Pair<String, String>> {
            if (InstrumentalLyrics.matches(document)) return emptyList()
            val fields = document.metadata.mapNotNull { entry ->
                val value = entry.values.filter(String::isNotBlank).joinToString(" / ")
                if (value.isBlank()) null else (labels[entry.key] ?: if (entry.key.startsWith("agent:"))
                    "演唱者 ${entry.key.substringAfter(':')}" else entry.key) to value
            }
            val source = when (document.source) {
                LyricSource.TTML_FILE -> "歌曲同名 TTML 文件"
                LyricSource.EMBEDDED_TTML -> "音频标签内嵌 TTML"
                LyricSource.TTML_IN_LRC -> "歌曲同名 LRC 文件中的 TTML"
                LyricSource.PLUGIN_TTML -> "跨插件 TTML（原始互通接口）"
                LyricSource.HOST -> "SPW 宿主已解析歌词（SPL / LRC）"
                LyricSource.UNKNOWN -> when (document.origin) {
                    LyricDocument.Origin.TTML -> "原始 TTML"
                    LyricDocument.Origin.LRC -> "LRC 歌词"
                    else -> "来源未提供"
                }
            }
            return fields + ("歌词来源" to source)
        }

        private fun format(fields: List<Pair<String, String>>): String = fields.joinToString("\n") { (key, value) ->
            if (key.isBlank()) value else "$key：$value"
        }

        private val labels = mapOf(
            "musicName" to "歌名", "title" to "标题", "artists" to "歌手", "artist" to "歌手",
            "album" to "专辑", "albumArtist" to "专辑歌手", "isrc" to "ISRC",
            "lyricist" to "作词", "composer" to "作曲", "arranger" to "编曲", "songwriter" to "词曲作者",
            "ncmMusicId" to "网易云音乐 ID", "qqMusicId" to "QQ 音乐 ID", "spotifyId" to "Spotify ID",
            "appleMusicId" to "Apple Music ID", "ttmlAuthorGithub" to "TTML 制作者 GitHub ID",
            "ttmlAuthorGithubLogin" to "TTML 制作者", "copyright" to "版权", "desc" to "说明",
            "description" to "说明", "language" to "语言", "agent" to "演唱者",
        )
    }
}
