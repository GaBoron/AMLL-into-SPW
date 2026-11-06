package io.github.gaboron.amllspw.lyrics

import java.util.Locale

/** Selects one authored alternative without changing the source or inventing a translation. */
internal object TranslationSelection {
    const val DEFAULT_LANGUAGE = "zh-CN"

    private data class Language(val tag: String, val primary: String, val script: String)

    fun select(variants: List<LyricTranslation>, preferred: String = DEFAULT_LANGUAGE): String {
        val available = variants.filter { it.text.isNotBlank() }
        val language = language(preferred.ifBlank { DEFAULT_LANGUAGE })
        val tagged = available.map { it to language(it.language) }
        tagged.firstOrNull { it.second.tag == language.tag }?.let { return it.first.text }
        val sameLanguage = tagged.filter { it.second.primary.isNotEmpty() && it.second.primary == language.primary }
        // Chinese region tags and explicit scripts are equivalent for display preference.
        // A generic zh alternative is preferable to a conflicting script; source order breaks ties.
        sameLanguage.maxByOrNull { (_, candidate) ->
            when {
                language.script.isNotEmpty() && candidate.script == language.script -> 2
                candidate.script.isEmpty() -> 1
                else -> 0
            }
        }?.let { return it.first.text }
        // Older TTML and host lyrics often omit a language. Keep that compatible fallback,
        // but never substitute a tagged translation in an unrelated language.
        return available.firstOrNull { it.language.isBlank() }?.text.orEmpty()
    }

    private fun language(value: String): Language {
        val parts = value.trim().replace('_', '-').lowercase(Locale.ROOT).split('-')
        val primary = when (parts.first()) {
            "zho", "chi" -> "zh"
            "eng" -> "en"
            "jpn" -> "ja"
            "kor" -> "ko"
            else -> parts.first()
        }
        val script = parts.drop(1).firstOrNull { it.length == 4 } ?: if (primary == "zh") when {
            parts.any { it in setOf("cn", "sg", "my") } -> "hans"
            parts.any { it in setOf("tw", "hk", "mo") } -> "hant"
            else -> ""
        } else ""
        return Language((listOf(primary) + parts.drop(1)).joinToString("-"), primary, script)
    }
}

internal fun LyricLine.displayTranslation(language: String = TranslationSelection.DEFAULT_LANGUAGE): String =
    if (translationVariants.isEmpty()) translation else TranslationSelection.select(translationVariants, language)
