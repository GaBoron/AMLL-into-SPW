package io.github.gaboron.amllspw.lyrics

import java.text.Normalizer
import java.util.Locale

/** Only a completed empty document or a whole-document notice is an instrumental page. */
internal object InstrumentalLyrics {
    private val separators = Regex("[\\s\\p{P}\\p{Z}\\uFEFF]+")
    private val chineseNotice = Regex(
        "(?:(?:此|本)(?:歌曲?|[乐樂]曲)[为為](?:[没沒]有填[词詞]的)?)?[纯純]音[乐樂](?:[请請]您?欣[赏賞])?",
    )
    private val englishNotices = setOf("instrumental", "instrumentalpleaseenjoy")

    fun matches(document: LyricDocument): Boolean {
        val texts = document.lines.map {
            separators.replace(Normalizer.normalize(it.text, Normalizer.Form.NFKC).lowercase(Locale.ROOT), "")
        }.filter(String::isNotBlank)
        return texts.isEmpty() || texts.all { chineseNotice.matches(it) || it in englishNotices }
    }
}
