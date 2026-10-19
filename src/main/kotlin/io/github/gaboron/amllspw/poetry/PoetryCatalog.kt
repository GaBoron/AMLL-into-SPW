package io.github.gaboron.amllspw.poetry

internal data class Poem(val title: String, val author: String, val collection: String, val paragraphs: List<String>) {
    val text: String get() = paragraphs.joinToString("\n")
}

/** Bundled complete short works from chinese-poetry; no network or extra runtime is required. */
internal object PoetryCatalog {
    fun load(): List<Poem> = checkNotNull(javaClass.getResourceAsStream("/poetry/poems.tsv")) {
        "Missing bundled poetry collection"
    }.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { row ->
            val fields = row.split('\t')
            require(fields.size == 4 && fields.all(String::isNotBlank)) { "Invalid bundled poem" }
            Poem(fields[0], fields[1], fields[2], fields[3].split('|'))
        }.toList().also { require(it.isNotEmpty()) { "Empty bundled poetry collection" } }
    }
}
