package io.github.gaboron.amllspw.lyrics

/** AMLL displays the background voice without its outer TTML parentheses. Timings stay intact. */
internal object BackgroundVocalText {
    fun unwrap(words: List<LyricWord>): List<LyricWord> {
        val text = words.joinToString("") { it.text }
        var start = 0
        var end = text.length
        fun trim() {
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
        }
        trim()
        while (start < end && text[start] in "(（" && text[end - 1] == if (text[start] == '(') ')' else '）') {
            var depth = 0
            var outerPair = true
            for (index in start until end) {
                if (text[index] in "(（") depth++ else if (text[index] in ")）") depth--
                if (depth == 0 && index != end - 1) { outerPair = false; break }
            }
            if (!outerPair || depth != 0) break
            start++; end--; trim()
        }
        var offset = 0
        return words.mapNotNull { word ->
            val from = (start - offset).coerceIn(0, word.text.length)
            val to = (end - offset).coerceIn(0, word.text.length)
            offset += word.text.length
            if (to <= from) null else word.copy(text = word.text.substring(from, to))
        }
    }
}
