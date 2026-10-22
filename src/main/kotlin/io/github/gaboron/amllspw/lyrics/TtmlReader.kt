package io.github.gaboron.amllspw.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Namespace-aware TTML reader. Original XML is retained; XML resources are never fetched. */
class TtmlReader {
    fun read(text: String): LyricDocument? = runCatching {
        if (text.length > MAX_CHARS || !text.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith('<')) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val parser = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }
        val root = parser.parse(InputSource(StringReader(text.trimStart('\uFEFF')))).documentElement
        if (root.local() != "tt" || root.namespaceURI !in setOf(null, "", TTML)) return null
        Parser(root).read(text)
    }.getOrNull()

    private class Parser(val root: Element) {
        val result = mutableListOf<LyricLine>()
        val primaryAgent = descendants(root).firstOrNull { it.local() == "agent" }?.attr("id").orEmpty()
        val frameRate = (root.attr("frameRate").toDoubleOrNull() ?: 30.0).let { rate ->
            val multiplier = root.attr("frameRateMultiplier").split(' ').mapNotNull(String::toDoubleOrNull)
            if (multiplier.size == 2 && multiplier[1] > 0) rate * multiplier[0] / multiplier[1] else rate
        }
        val tickRate = root.attr("tickRate").toDoubleOrNull() ?: 1.0
        val ranges = java.util.IdentityHashMap<Element, Range>()
        val appleTiming = root.attr("timing") in setOf("Word", "Line") ||
            root.getAttribute("xmlns:itunes").isNotEmpty() || root.getAttribute("xmlns:amll").isNotEmpty()
        val sidecarText = descendants(root).filter { it.local() == "text" && it.attr("for").isNotBlank() }
        var group = 0
        var hasTiming = false

        fun time(e: Element, name: String): Long? {
            val value = e.attr(name)
            if (value.isBlank()) return null
            return requireNotNull(TtmlTime.parse(value, frameRate, tickRate)) { "Invalid TTML time: $value" }
        }

        fun range(e: Element, parent: Range, sequenceStart: Long? = null): Range {
            if (e.hasAttribute("begin") || e.hasAttribute("end") || e.hasAttribute("dur")) hasTiming = true
            fun resolve(name: String): Long? = time(e, name)?.let {
                if (appleTiming || ':' in e.attr(name) || e.attr(name).toDoubleOrNull() != null) it else (sequenceStart ?: parent.start) + it
            }
            val start = resolve("begin") ?: (sequenceStart ?: parent.start)
            val end = resolve("end")
                ?: time(e, "dur")?.let { start + it } ?: parent.end
            // Apple/AMLL timestamps are absolute; background tails may outlive the parent p.
            return Range(start, if (appleTiming) end else minOf(end, parent.end)).also { ranges[e] = it }
        }

        fun read(original: String): LyricDocument? {
            visit(root, Range(0, MAX_TIME), 0)
            if (result.isEmpty() || !hasTiming) return null
            val lines = result.sortedWith(compareBy<LyricLine> { it.startMs }.thenBy { it.group }.thenBy { it.background })
            val wordTiming = when (root.attr("timing")) {
                "Word" -> true
                "Line" -> false
                else -> lines.any { line -> line.words.any { it.timed && it.text.isNotBlank() } }
            }
            return LyricDocument(lines.mapIndexed { index, line ->
                val following = lines.drop(index + 1).firstOrNull { it.startMs > line.startMs }?.startMs
                val bounded = if (line.endMs == MAX_TIME) line.copy(endMs = following ?: (line.startMs + 5_000)) else line
                // Word-timed TTML can put a single sung unit directly in p (or x-bg),
                // with its timing on that element instead of a nested span.
                val timed = if (wordTiming && !bounded.hasWordTiming) bounded.copy(words = bounded.words.map { word ->
                    word.copy(startMs = bounded.startMs, endMs = bounded.endMs, timed = word.text.isNotBlank())
                }) else bounded
                TimedRomanization.attach(timed)
            }, LyricDocument.Origin.TTML, original, metadata = TtmlMetadata.read(root))
        }

        fun visit(e: Element, parent: Range, depth: Int) {
            require(depth < 64 && result.size < 20_000) { "TTML exceeds rendering limits" }
            if (e.local() == "head") return
            val r = range(e, parent)
            if (e.local() == "p") { paragraph(e, r); return }
            var next = r.start
            for (child in e.children()) {
                val childParent = if (e.attr("timeContainer") == "seq") Range(next, r.end) else r
                visit(child, childParent, depth + 1)
                next = ranges[child]?.end?.takeUnless { it == MAX_TIME } ?: next
            }
        }

        fun paragraph(e: Element, r: Range) {
            val id = group++
            val agent = e.inherited("agent")
            fun secondaryOf(element: Element) = descendants(element).filter { node ->
                node.role() in SECONDARY && generateSequence(node.parentNode as? Element) { it.parentNode as? Element }
                    .takeWhile { it !== element }.none { it.role() == "x-bg" }
            }
            val secondary = secondaryOf(e)
            val attached = sidecarText.filter { it.attr("for") == e.attr("key") }
            val translation = (secondary.filter { it.role() in TRANSLATION } + attached.filter { (it.parentNode as? Element)?.local() == "translation" })
                .map { it.textContent }.distinct().joinToString("\n")
            val romanization = (secondary.filter { it.role() in ROMANIZATION } + attached.filter { (it.parentNode as? Element)?.local() == "transliteration" })
                .map { it.textContent }.distinct().joinToString("\n")
            val section = e.inherited("song-part").ifBlank { e.inherited("songPart") }
            val main = words(e, r)
            val bg = descendants(e).filter { it.role() == "x-bg" }
            fun line(element: Element, rr: Range, source: List<LyricWord>, background: Boolean) {
                val ww = if (background) BackgroundVocalText.unwrap(source) else source
                if (ww.none { it.text.isNotBlank() }) return
                val ownAgent = element.inherited("agent").ifBlank { agent }
                val start = if (element.hasAttribute("begin")) rr.start else ww.filter { it.timed }.minOfOrNull { it.startMs } ?: rr.start
                val wordEnd = ww.filter { it.timed }.maxOfOrNull { it.endMs }
                val end = if (appleTiming && wordEnd != null) maxOf(rr.end.takeUnless { it == MAX_TIME } ?: wordEnd, wordEnd)
                    else if (element.hasAttribute("end") || element.hasAttribute("dur")) rr.end else wordEnd ?: rr.end
                val ownSecondary = secondaryOf(element)
                val ownTranslation = if (element === e) translation else ownSecondary.filter { it.role() in TRANSLATION }.joinToString("\n") { it.textContent }
                val ownRomanization = if (element === e) romanization else ownSecondary.filter { it.role() in ROMANIZATION }.joinToString("\n") { it.textContent }
                val inlineRoman = ownSecondary.filter { it.role() in ROMANIZATION }
                val attachedRoman = if (element === e) attached.filter {
                    (it.parentNode as? Element)?.local() == "transliteration"
                } else attached
                        .filter { (it.parentNode as? Element)?.local() == "transliteration" }
                        .flatMap { descendants(it).filter { node -> node.role() == "x-bg" } }
                val romanNodes = inlineRoman.ifEmpty { attachedRoman.take(1) }
                val syllables = romanNodes.flatMap { roman ->
                    val ancestor = generateSequence(roman.parentNode as? Element) { it.parentNode as? Element }
                        .takeWhile { it !== e }.firstOrNull { it in ranges }
                    val romanRange = range(roman, ancestor?.let { ranges[it] } ?: rr)
                    val explicit = roman.hasAttribute("begin") || ancestor?.let {
                        it.local() == "span" && (it.hasAttribute("begin") || it.hasAttribute("end") || it.hasAttribute("dur"))
                    } == true
                    words(roman, romanRange, explicit).filter { it.timed && it.text.isNotBlank() }.map {
                        LyricSyllable(it.text, it.startMs, it.endMs, true)
                    }
                }
                val romanText = if (syllables.isNotEmpty()) syllables.joinToString(" ") { it.text } else
                    ownRomanization.ifBlank { romanNodes.joinToString("\n") { it.textContent } }
                result += LyricLine(start, maxOf(start + 1, end), ww, ownTranslation,
                    romanText, ownAgent, background,
                    ownAgent.isNotEmpty() && ownAgent != primaryAgent.ifBlank { "v1" }, id, section,
                    romanizationSyllables = syllables)
            }
            line(e, r, main, e.role() == "x-bg")
            for (element in bg) {
                val rr = ranges[element] ?: range(element, r)
                line(element, rr, words(element, rr), true)
            }
        }

        fun words(e: Element, parent: Range, timedRoot: Boolean = false): List<LyricWord> {
            val out = mutableListOf<LyricWord>()
            fun walk(node: Node, inherited: Range, timed: Boolean, ruby: String = "",
                rubySyllables: List<LyricSyllable> = emptyList(), depth: Int = 0) {
                require(depth < 64 && out.size < 50_000)
                when (node) {
                    is Element -> {
                        if (node !== e && (node.role() in SECONDARY || node.role() == "x-bg")) {
                            if (node.role() == "x-bg") range(node, inherited)
                            return
                        }
                        if (node.attr("ruby") in setOf("text", "textContainer", "delimiter")) return
                        var r = if (node === e) parent else range(node, inherited)
                        var explicit = timed || (node !== e && (node.hasAttribute("begin") || node.hasAttribute("end") || node.hasAttribute("dur")))
                        val rubyNodes = if (node.attr("ruby") == "container") descendants(node).filter { it.attr("ruby") == "text" } else emptyList()
                        val annotation = if (rubyNodes.isNotEmpty()) rubyNodes.joinToString("") { it.textContent } else ruby
                        val rubyTimes = rubyNodes.filter { it.hasAttribute("begin") }.map { range(it, r) }
                        val annotations = if (rubyNodes.isEmpty()) rubySyllables else rubyNodes.map { rubyNode ->
                            val timing = ranges[rubyNode] ?: range(rubyNode, r)
                            LyricSyllable(rubyNode.textContent, timing.start, timing.end,
                                rubyNode.hasAttribute("begin") && (rubyNode.hasAttribute("end") || rubyNode.hasAttribute("dur")))
                        }
                        if (!explicit && rubyTimes.isNotEmpty()) {
                            r = Range(rubyTimes.minOf { it.start }, rubyTimes.maxOf { it.end })
                            explicit = true
                        }
                        if (node.local() == "br") out += LyricWord("\n", r.start, r.end, false)
                        else {
                            var next = r.start
                            val children = node.childNodes
                            for (i in 0 until children.length) {
                                val childRange = if (node.attr("timeContainer") == "seq") Range(next, r.end) else r
                                val child = children.item(i)
                                walk(child, childRange, explicit, annotation, annotations, depth + 1)
                                if (child is Element) next = ranges[child]?.end?.takeUnless { it == MAX_TIME } ?: next
                            }
                        }
                    }
                    else -> if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                        val text = node.nodeValue.orEmpty()
                        if (text.isNotEmpty()) out += LyricWord(text, inherited.start, inherited.end, timed, ruby, rubySyllables)
                    }
                }
            }
            walk(e, parent, timedRoot)
            return out.map { if (it.endMs == MAX_TIME) it.copy(endMs = parent.end) else it }
        }
    }

    private data class Range(val start: Long, val end: Long)
    companion object {
        const val MAX_CHARS = 4_000_000
        private const val MAX_TIME = 604_800_000L
        private const val TTML = "http://www.w3.org/ns/ttml"
        private val TRANSLATION = setOf("x-translation", "translation")
        private val ROMANIZATION = setOf("x-roman", "x-romanization", "x-transliteration")
        private val SECONDARY = TRANSLATION + ROMANIZATION
    }
}

private fun Element.local(): String = localName ?: tagName.substringAfter(':')
private fun Element.attr(name: String): String {
    for (i in 0 until attributes.length) {
        val a = attributes.item(i)
        if ((a.localName ?: a.nodeName.substringAfter(':')) == name) return a.nodeValue
    }
    return ""
}
private fun Element.inherited(name: String): String = attr(name).ifEmpty { (parentNode as? Element)?.inherited(name).orEmpty() }
private fun Element.role(): String = attr("role")
private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
private fun descendants(e: Element): List<Element> {
    val nodes = e.getElementsByTagName("*")
    return (0 until nodes.length).map { nodes.item(it) as Element }
}
