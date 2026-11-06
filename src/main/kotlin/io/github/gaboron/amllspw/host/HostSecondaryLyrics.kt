package io.github.gaboron.amllspw.host

import io.github.gaboron.amllspw.lyrics.LyricLine
import io.github.gaboron.amllspw.romanization.HostRomanization
import io.github.gaboron.amllspw.romanization.JapaneseReadings
import io.github.gaboron.amllspw.romanization.CantoneseReadings
import io.github.gaboron.amllspw.romanization.JyutpingAlignment
import io.github.gaboron.amllspw.romanization.KoreanReadings
import io.github.gaboron.amllspw.romanization.KoreanRomanizationAlignment
import java.text.Normalizer
import java.util.Locale

/** Separates supplied SPL/LRC readings from the host's merged secondary text at load time. */
internal class HostSecondaryLyrics(
    private val readings: JapaneseReadings = JapaneseReadings(),
    private val cantoneseReadings: CantoneseReadings = CantoneseReadings(),
) {
    private var readingFailureReported = false

    fun split(lines: List<LyricLine>, cantonese: Boolean = false): List<LyricLine> {
        // Track-wide kana evidence also covers Kanji-only rows, without loading a Japanese
        // dictionary for Chinese tracks that happen to contain an English translation.
        val japanese = lines.any { hasKana(Normalizer.normalize(it.text, Normalizer.Form.NFKC)) }
        return lines.map { split(it, japanese, cantonese) }
    }

    fun split(line: LyricLine, japanese: Boolean = false, cantonese: Boolean = false): LyricLine {
        if (line.romanization.isNotBlank() || line.translation.isBlank() ||
            line.text.length !in 1..512 || line.translation.length > 16_384) return line
        val text = Normalizer.normalize(line.text, Normalizer.Form.NFKC)
        val hasKana = hasKana(text)
        val hasHangul = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
        val hanCount = text.codePoints().filter(Character::isIdeographic).count().toInt()
        if (!hasKana && !hasHangul && hanCount == 0) return line

        // Tokenize only when a Latin-script candidate needs checking. Share the dictionary
        // with optional Japanese annotation generation, which remains a separate setting.
        val japaneseReading by lazy {
            runCatching { readings.analyze(line.text) }.getOrElse {
                if (!readingFailureReported) {
                    System.err.println("AMLL: 宿主音译识别失败: ${it.message}")
                    readingFailureReported = true
                }
                emptyList()
            }
        }
        val koreanReading by lazy { KoreanReadings.analyze(line.text) }
        val cantoneseReading by lazy { runCatching { cantoneseReadings.analyze(line.text) }.getOrDefault(emptyList()) }
        val (romanization, translation) = line.translation.lines().partition { value ->
            latinReading(value) && when {
                hasHangul -> !hasKana && hanCount == 0 &&
                    KoreanRomanizationAlignment.match(line.text, koreanReading, value) != null
                cantonese && !hasKana -> JyutpingAlignment.match(line.text, cantoneseReading, value) != null
                hasKana || japanese -> HostRomanization.matches(line.text, japaneseReading, value)
                else -> markedPinyin(text, value, hanCount)
            }
        }
        if (romanization.isEmpty()) return line
        return line.copy(translation = translation.joinToString("\n"), romanization = romanization.joinToString("\n"))
    }

    private fun hasKana(text: String): Boolean = text.any { it in '\u3041'..'\u3096' || it in '\u30a1'..'\u30fa' }

    private fun latinReading(value: String): Boolean = value.codePoints().anyMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
    } && value.codePoints().allMatch { !Character.isLetter(it) || Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }

    private fun markedPinyin(text: String, value: String, hanCount: Int): Boolean {
        if (text.codePoints().anyMatch { Character.isLetter(it) && !Character.isIdeographic(it) }) return false
        val normalized = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        if (normalized.none { it in "\u0300\u0301\u0304\u030c" } && !toneNumber.containsMatchIn(value)) return false
        val syllables = normalized.replace(combiningMarks, "").split(syllableBoundary).filter(String::isNotBlank)
        // Tone evidence and one syllable per Han character avoid treating arbitrary Latin
        // translations as pinyin. Unmarked or ambiguous text keeps its original category.
        return syllables.size == hanCount && syllables.all { pinyinSyllable.matches(it) }
    }

    companion object {
        private val combiningMarks = Regex("\\p{M}")
        private val toneNumber = Regex("[A-Za-züÜ][1-5](?:\\b|$)")
        private val syllableBoundary = Regex("[^a-z0-9]+")
        private val pinyinSyllable = Regex(
            "(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcswy])?" +
                "(?:a|o|e|ai|ei|ao|ou|an|en|ang|eng|ong|i|ia|ie|iao|iu|ian|in|iang|ing|iong|u|ua|uo|uai|ui|uan|un|uang|ueng|ue|er|v|ve)[1-5]?"
        )
    }
}
