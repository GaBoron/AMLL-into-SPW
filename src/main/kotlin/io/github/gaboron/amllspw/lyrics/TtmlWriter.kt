package io.github.gaboron.amllspw.lyrics

/** In-memory interchange format. Never writes back to songs or overwrites their sidecars. */
object TtmlWriter {
    fun write(document: LyricDocument): String {
        document.originalTtml?.let { return it }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
            append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\" ")
            append("itunes:timing=\"").append(if (document.lines.any { it.hasWordTiming }) "Word" else "Line").append("\">")
            append("<head><metadata>")
            document.lines.map { it.agent }.filter(String::isNotBlank).distinct().forEach { agent ->
                append("<ttm:agent type=\"person\" xml:id=\"").append(escape(agent)).append("\"/>")
            }
            append("</metadata></head><body><div>")
            for (line in document.lines) {
                append("<p begin=\"").append(stamp(line.startMs)).append("\" end=\"").append(stamp(line.endMs)).append('"')
                if (line.agent.isNotBlank()) append(" ttm:agent=\"").append(escape(line.agent)).append('"')
                if (line.background) append(" ttm:role=\"x-bg\"")
                if (line.section.isNotBlank()) append(" itunes:song-part=\"").append(escape(line.section)).append('"')
                append('>')
                for (word in line.words) {
                    append("<span")
                    if (word.timed) append(" begin=\"").append(stamp(word.startMs)).append("\" end=\"").append(stamp(word.endMs)).append('"')
                    if (word.ruby.isEmpty()) append('>').append(escape(word.text)).append("</span>")
                    else append(" tts:ruby=\"container\"><span tts:ruby=\"base\">").append(escape(word.text))
                        .append("</span><span tts:ruby=\"text\">").append(escape(word.ruby)).append("</span></span>")
                }
                if (line.translation.isNotEmpty()) append("<span ttm:role=\"x-translation\">").append(escape(line.translation)).append("</span>")
                if (line.romanization.isNotEmpty()) append("<span ttm:role=\"x-roman\">").append(escape(line.romanization)).append("</span>")
                append("</p>")
            }
            append("</div></body></tt>")
        }
    }

    private fun stamp(ms: Long): String = "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT,
        ms.coerceAtLeast(0) / 3_600_000, ms.coerceAtLeast(0) / 60_000 % 60, ms.coerceAtLeast(0) / 1_000 % 60, ms.coerceAtLeast(0) % 1_000)
    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
