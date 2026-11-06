package io.github.gaboron.amllspw.lyrics

import java.text.Normalizer
import java.util.Locale

/** Optional display-time interpretation of speaker headings and parenthesized backing vocals. */
internal object AutomaticVocals {
    private data class Voice(val agent: String = "", val duet: Boolean = false, val background: Boolean = false)
    private data class Marker(val name: String, val end: Int)
    private data class Part(val start: Int, val end: Int)
    private const val namePunctuation = ".·・_-&/、+＋()（）'’"
    private val marker = Regex("^[\\s\\uFEFF]*([^:：\\r\\n]{1,48})[:：][\\s\\u3000]*")
    private val featuring = Regex("(?i)^(?:feat\\.?|ft\\.?|featuring)(?:\\s|\\.|$)")
    private val chorus = setOf("合", "合唱", "合声", "全体", "齐唱", "众人", "all", "together", "chorus", "ensemble")
    private val backing = setOf("和声", "伴唱", "背景", "背景人声", "backing", "background", "bg")
    private val credits = setOf(
        "词", "曲", "作词", "作曲", "作詞", "作編曲", "作词作曲", "作詞作曲", "编曲", "編曲", "词曲", "詞曲",
        "演唱", "原唱", "歌手", "制作", "製作", "制作人", "製作人", "混音", "录音", "錄音", "母带", "母帶",
        "翻译", "翻譯", "音译", "音譯", "歌词", "歌詞", "歌名", "专辑", "專輯", "发行", "發行", "出品", "监制", "監製",
        "lyrics", "lyricist", "composer", "arranger", "producer", "artist", "title", "album", "vocal", "vocals",
    )

    fun apply(document: LyricDocument, enabled: Boolean): LyricDocument {
        if (!enabled) return document
        val speakers = linkedMapOf<String, Voice>()
        val usedAgents = document.lines.map { it.agent }.filter(String::isNotBlank).toMutableSet()
        var current = Voice()
        fun voice(name: String): Voice {
            val key = key(name)
            val joint = key.split('/', '&', '+', '、')
            val knownJoint = joint.size > 1 && joint.all { it in speakers || it in setOf("男", "女") }
            return when {
                key in chorus || knownJoint -> Voice()
                key in backing -> current.copy(background = true)
                else -> speakers.getOrPut(key) {
                    var number = speakers.size + 1
                    while ("auto-voice-$number" in usedAgents) number++
                    val agent = "auto-voice-$number"
                    usedAgents += agent
                    Voice(agent, speakers.size % 2 == 1)
                }
            }
        }
        val lines = buildList {
            for (original in document.lines) {
                if (original.text.length > 16_384) {
                    add(original)
                    continue
                }
                val heading = findMarker(original.text)
                if (heading != null) current = voice(heading.name)
                // Authored TTML voices retain their source identity, side and background role.
                if (original.agent.isNotBlank() || original.duet || original.background) {
                    add(original)
                    continue
                }
                if (heading != null && heading.end == original.text.length) continue
                val trimmed = if (heading == null) original else
                    cut(original, listOf(Part(heading.end, original.text.length))) ?: original
                val line = trimmed.copy(agent = current.agent, duet = current.duet, background = current.background)
                val parentheses = parenthesized(line.text)
                // Parenthesized featured-artist credits in titles are not sung backing parts.
                val brackets = if (parentheses.any { featuring.containsMatchIn(line.text.substring(it.start + 1, it.end - 1).trim()) })
                    emptyList() else parentheses.filter { line.text.substring(it.start + 1, it.end - 1).isNotBlank() }
                val mainParts = outside(line.text.length, brackets)
                val onlyBacking = brackets.size == 1 && mainParts.all { line.text.substring(it.start, it.end).isBlank() }
                val canSplit = line.annotations.isEmpty() && line.romanizationSyllables.isEmpty() &&
                    line.words.none { it.ruby.isNotBlank() || it.rubySyllables.isNotEmpty() }
                if (brackets.isEmpty() || brackets.size > 32 || !canSplit) {
                    add(line)
                    continue
                }
                if (onlyBacking) {
                    val words = BackgroundVocalText.unwrap(line.words)
                    if (words.any { it.text.isNotBlank() }) add(line.copy(words = words, background = true,
                        translation = unwrap(line.translation), romanization = unwrap(line.romanization),
                        translationVariants = line.translationVariants.map { it.copy(text = unwrap(it.text)) }))
                    else add(line)
                    continue
                }
                val main = cut(line, mainParts)
                if (main == null || main.text.isBlank()) {
                    add(line)
                    continue
                }
                add(main.copy(translation = secondary(line.translation, brackets.size),
                    romanization = secondary(line.romanization, brackets.size),
                    translationVariants = line.translationVariants.map { it.copy(text = secondary(it.text, brackets.size)) }))
                brackets.forEachIndexed { index, part ->
                    val inner = cut(line, listOf(Part(part.start + 1, part.end - 1))) ?: return@forEachIndexed
                    val label = findMarker(inner.text)
                    val ownVoice = label?.let { voice(it.name) } ?: current
                    val content = if (label == null) inner else
                        cut(inner, listOf(Part(label.end, inner.text.length))) ?: inner
                    val words = BackgroundVocalText.unwrap(content.words)
                    if (words.none { it.text.isNotBlank() }) return@forEachIndexed
                    val timed = words.filter { it.timed && it.text.isNotBlank() }
                    val start = timed.minOfOrNull { it.startMs } ?: line.startMs
                    val end = timed.maxOfOrNull { it.endMs }?.coerceAtLeast(start + 1) ?: line.endMs
                    add(content.copy(words = words, background = true, agent = ownVoice.agent, duet = ownVoice.duet,
                        startMs = start, endMs = end,
                        translation = secondary(line.translation, brackets.size, index),
                        romanization = secondary(line.romanization, brackets.size, index),
                        translationVariants = line.translationVariants.map { it.copy(text = secondary(it.text, brackets.size, index)) }))
                }
            }
        }
        // Standalone backing rows join a preceding overlapping lead, so they expand with it.
        val authoredBacking = document.lines.filter { it.background }.toSet()
        var lead: LyricLine? = null
        val grouped = lines.map { line ->
            if (!line.background) {
                lead = line
                line
            } else if (line in authoredBacking) line
            else lead?.takeIf { it.startMs < line.endMs && line.startMs < it.endMs }
                ?.let { line.copy(group = it.group) } ?: line
        }
        if (grouped == document.lines) return document
        // Interchange keeps the authored headings/brackets and source voice metadata.
        return document.copy(lines = grouped, originalTtml = document.originalTtml ?: TtmlWriter.write(document))
    }

    private fun findMarker(text: String): Marker? {
        val match = marker.find(text) ?: return null
        val name = match.groupValues[1].trim().removeSurrounding("[", "]").removeSurrounding("【", "】")
        if (name.codePoints().noneMatch(Character::isLetter)) return null
        if (name.count { it == '(' || it == '（' } != name.count { it == ')' || it == '）' }) return null
        if (name.codePoints().anyMatch { !Character.isLetterOrDigit(it) && !Character.isWhitespace(it) &&
                (it > Char.MAX_VALUE.code || it.toChar() !in namePunctuation) }) return null
        val value = key(name)
        if (value in credits || value.split('/', '&', '+', '、').any { it in credits }) return null
        return Marker(name, match.range.last + 1)
    }

    private fun key(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
        return when (normalized) { "男声", "男聲" -> "男"; "女声", "女聲" -> "女"; else -> normalized }
    }

    /** Only balanced outer pairs are structural; incomplete or mismatched brackets stay literal. */
    private fun parenthesized(text: String): List<Part> {
        val stack = ArrayDeque<Char>()
        val result = mutableListOf<Part>()
        var start = 0
        text.forEachIndexed { index, char ->
            when (char) {
                '(', '（' -> {
                    if (stack.isEmpty()) start = index
                    stack.addLast(char)
                }
                ')', '）' -> {
                    if (stack.isEmpty() || stack.removeLast() != if (char == ')') '(' else '（') return emptyList()
                    if (stack.isEmpty()) result += Part(start, index + 1)
                }
            }
        }
        return result.takeIf { stack.isEmpty() }.orEmpty()
    }

    private fun outside(length: Int, parts: List<Part>): List<Part> = buildList {
        var start = 0
        for (part in parts) {
            if (start < part.start) add(Part(start, part.start))
            start = part.end
        }
        if (start < length) add(Part(start, length))
    }

    /** Keep the source clocks; cutting display characters never estimates new word timings. */
    private fun cut(line: LyricLine, parts: List<Part>): LyricLine? {
        var offset = 0
        val words = buildList {
            for (word in line.words) {
                val from = offset
                offset += word.text.length
                val text = parts.joinToString("") { part ->
                    val start = (part.start - from).coerceIn(0, word.text.length)
                    val end = (part.end - from).coerceIn(0, word.text.length)
                    if (start < end) word.text.substring(start, end) else ""
                }
                if (text.isEmpty()) continue
                if (text != word.text && (word.ruby.isNotBlank() || word.rubySyllables.isNotEmpty())) return null
                add(word.copy(text = text))
            }
        }
        val annotations = line.annotations.mapNotNull { annotation ->
            var output = 0
            for (part in parts) {
                if (annotation.start >= part.start && annotation.end <= part.end)
                    return@mapNotNull annotation.copy(start = annotation.start - part.start + output, end = annotation.end - part.start + output)
                if (annotation.start < part.end && part.start < annotation.end) return null
                output += part.end - part.start
            }
            null
        }
        return line.copy(words = words, annotations = annotations)
    }

    private fun secondary(text: String, count: Int, background: Int? = null): String {
        val parts = parenthesized(text)
        if (parts.size != count) return if (background == null) text else ""
        return if (background == null) outside(text.length, parts).joinToString("") { text.substring(it.start, it.end) }
            else unwrap(text.substring(parts[background].start + 1, parts[background].end - 1))
    }

    private fun unwrap(text: String): String = BackgroundVocalText.unwrap(listOf(LyricWord(text, 0, 0, timed = false))).joinToString("") { it.text }
}
