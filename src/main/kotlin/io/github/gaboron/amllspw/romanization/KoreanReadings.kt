package io.github.gaboron.amllspw.romanization

import java.text.Normalizer

internal data class KoreanReading(val start: Int, val end: Int, val roman: String, val annotate: Boolean = true)

/** Offline modern Hangul readings. All ranges refer to the original UTF-16 lyric text. */
internal object KoreanReadings {
    private data class Syllable(val start: Int, val end: Int, var initial: Char, val vowel: Int, var final: Char)

    private const val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val finals = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val vowels = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val consonants = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    // Retain the host recognizer's earlier spellings when supplied lyrics use them.
    private val spelledFinals = listOf("", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lp", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "h")
    private val linkedFinals = listOf("", "g", "kk", "ks", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "")
    private val clusters = Regex("\\X")
    private val doubleFinals = mapOf(
        'ㄳ' to ('ㄱ' to 'ㅅ'), 'ㄵ' to ('ㄴ' to 'ㅈ'), 'ㄺ' to ('ㄹ' to 'ㄱ'),
        'ㄻ' to ('ㄹ' to 'ㅁ'), 'ㄼ' to ('ㄹ' to 'ㅂ'), 'ㄽ' to ('ㄹ' to 'ㅅ'),
        'ㄾ' to ('ㄹ' to 'ㅌ'), 'ㄿ' to ('ㄹ' to 'ㅍ'), 'ㅄ' to ('ㅂ' to 'ㅅ'),
    )

    /**
     * Uses Revised Romanization vowels and consonants with local sound changes. Morpheme
     * boundaries, inserted ㄴ and lexical exceptions require a dictionary and are not inferred.
     * Derived tensing is omitted, as in RR; consonants written tense remain tense.
     */
    fun analyze(text: String, contextual: Boolean = true): List<KoreanReading> {
        val syllables = clusters.findAll(text).mapNotNull { cluster ->
            // NFC is local to a grapheme, so decomposed Hangul keeps its original range.
            val char = Normalizer.normalize(cluster.value, Normalizer.Form.NFC).singleOrNull()
                ?.takeIf { it in '\uac00'..'\ud7a3' } ?: return@mapNotNull null
            val value = char.code - 0xac00
            Syllable(cluster.range.first, cluster.range.last + 1, initials[value / 588], value / 28 % 21, finals[value % 28])
        }.toList()
        if (contextual) for (index in 0 until syllables.lastIndex) {
            val left = syllables[index]
            val right = syllables[index + 1]
            // Spaces, punctuation and other scripts end pronunciation context.
            if (left.end == right.start) connect(left, right)
        }
        val readings = syllables.mapIndexed { index, syllable ->
            val previous = syllables.getOrNull(index - 1)?.takeIf { it.end == syllable.start }
            val initial = if (contextual && syllable.initial == 'ㄹ' && previous?.final == 'ㄹ') "l"
                else consonants[initials.indexOf(syllable.initial)]
            val next = syllables.getOrNull(index + 1)?.takeIf { it.start == syllable.end }
            val final = if (contextual) finalSpelling(syllable.final) else {
                val finalIndex = finals.indexOf(syllable.final)
                if (next?.initial == 'ㅇ') {
                    if (next.vowel == 20 && syllable.final in "ㄷㅌ")
                        if (syllable.final == 'ㄷ') "j" else "ch"
                    else linkedFinals[finalIndex]
                } else spelledFinals[finalIndex]
            }
            KoreanReading(syllable.start, syllable.end, initial + vowels[syllable.vowel] + final)
        }
        // Literal letters/numbers anchor supplied readings in mixed Korean/English lines.
        return buildList {
            var cursor = 0
            for (reading in readings) {
                addLiterals(text, cursor, reading.start)
                add(reading)
                cursor = reading.end
            }
            addLiterals(text, cursor, text.length)
        }
    }

    private fun MutableList<KoreanReading>.addLiterals(text: String, start: Int, end: Int) {
        if (start < end && text.substring(start, end).codePoints().anyMatch(Character::isLetterOrDigit))
            add(KoreanReading(start, end, text.substring(start, end), annotate = false))
    }

    private fun connect(left: Syllable, right: Syllable) {
        if (left.final == ' ') return
        // ㅎ is lost before a vowel, or combines with a neighbouring stop to aspirate it.
        if (left.final in "ㅎㄶㅀ") {
            val residual = when (left.final) { 'ㄶ' -> 'ㄴ'; 'ㅀ' -> 'ㄹ'; else -> ' ' }
            if (right.initial == 'ㅇ') left.final = residual
            else aspirated(right.initial)?.let {
                left.final = residual
                right.initial = it
            }
        }
        if (right.initial == 'ㅇ') {
            if (left.final == 'ㅇ') return // Final ㅇ stays ng, unlike silent initial ㅇ.
            val (residual, moved) = doubleFinals[left.final] ?: (' ' to left.final)
            if (moved == ' ') return
            left.final = residual
            right.initial = palatalized(moved, right.vowel)
            return
        }
        if (right.initial == 'ㅎ') {
            val (residual, moved) = doubleFinals[left.final] ?: (' ' to left.final)
            aspirated(moved)?.let {
                left.final = residual
                right.initial = palatalized(it, right.vowel)
            }
        }
        left.final = reducedFinal(left.final)
        if (right.initial == 'ㄹ' && left.final in "ㄱㄷㅂㅁㅇ") right.initial = 'ㄴ'
        if (right.initial in "ㄴㅁ") left.final = when (left.final) {
            'ㄱ' -> 'ㅇ'
            'ㄷ' -> 'ㄴ'
            'ㅂ' -> 'ㅁ'
            else -> left.final
        }
        if ((left.final == 'ㄴ' && right.initial == 'ㄹ') ||
            (left.final == 'ㄹ' && right.initial == 'ㄴ')) {
            left.final = 'ㄹ'
            right.initial = 'ㄹ'
        }
    }

    private fun palatalized(consonant: Char, vowel: Int): Char = if (vowel == 20) when (consonant) {
        'ㄷ' -> 'ㅈ'
        'ㅌ' -> 'ㅊ'
        else -> consonant
    } else consonant

    private fun aspirated(consonant: Char): Char? = when (consonant) {
        'ㄱ', 'ㅋ' -> 'ㅋ'
        'ㄷ', 'ㅌ' -> 'ㅌ'
        'ㅂ', 'ㅍ' -> 'ㅍ'
        'ㅈ', 'ㅊ' -> 'ㅊ'
        else -> null
    }

    private fun reducedFinal(consonant: Char): Char = when (consonant) {
        'ㄲ', 'ㄳ', 'ㄺ', 'ㅋ' -> 'ㄱ'
        'ㄵ', 'ㄶ' -> 'ㄴ'
        'ㄼ', 'ㄽ', 'ㄾ', 'ㅀ' -> 'ㄹ'
        'ㄻ' -> 'ㅁ'
        'ㅄ', 'ㄿ', 'ㅍ' -> 'ㅂ'
        'ㅅ', 'ㅆ', 'ㅈ', 'ㅊ', 'ㅌ', 'ㅎ' -> 'ㄷ'
        else -> consonant
    }

    private fun finalSpelling(consonant: Char): String = when (reducedFinal(consonant)) {
        'ㄱ' -> "k"; 'ㄴ' -> "n"; 'ㄷ' -> "t"; 'ㄹ' -> "l"
        'ㅁ' -> "m"; 'ㅂ' -> "p"; 'ㅇ' -> "ng"; else -> ""
    }
}
