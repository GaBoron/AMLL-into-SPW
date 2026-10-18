package io.github.gaboron.amllspw.lyrics

import org.w3c.dom.Element

/** Reads the already secured DOM; translations and transliterations remain lyric sidecars. */
internal object TtmlMetadata {
    fun read(root: Element): List<LyricMetadata> {
        val values = linkedMapOf<String, MutableList<String>>()
        fun add(key: String, value: String) {
            val text = value.trim()
            if (key.isBlank() || text.isBlank()) return
            val entries = values.getOrPut(key) { mutableListOf() }
            if (text !in entries) entries += text
        }
        fun visit(element: Element, depth: Int) {
            if (depth >= 64) return
            val name = element.localName ?: element.tagName.substringAfter(':')
            if (name in setOf("translations", "transliterations", "translation", "transliteration")) return
            if (name == "meta" && element.hasAttribute("key")) {
                add(element.getAttribute("key"), if (element.hasAttribute("value")) element.getAttribute("value") else element.textContent)
                return
            }
            val children = element.elements()
            if (name == "agent") {
                val id = element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id")
                children.filter { (it.localName ?: it.tagName.substringAfter(':')) == "name" }.forEach {
                    add(if (id.isBlank()) "agent" else "agent:$id", it.textContent)
                }
                return
            }
            if (children.isEmpty()) {
                val key = if (element.namespaceURI == "http://www.w3.org/ns/ttml#metadata") name else element.tagName
                add(key, element.textContent.ifBlank { element.getAttribute("value") })
            } else children.forEach { visit(it, depth + 1) }
        }
        root.elements().filter { (it.localName ?: it.tagName) == "head" }.forEach { head ->
            head.elements().filter { (it.localName ?: it.tagName) == "metadata" }.forEach { visit(it, 0) }
        }
        return values.map { (key, entries) -> LyricMetadata(key, entries.toList()) }
    }

    private fun Element.elements() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
