package io.github.gaboron.amllspw.romanization

import java.text.Normalizer

/** Small, deterministic Hepburn converter. Unknown readings are left unannotated. */
internal object KanaRomanizer {
    private val basic = ("あ:a い:i う:u え:e お:o か:ka き:ki く:ku け:ke こ:ko " +
        "さ:sa し:shi す:su せ:se そ:so た:ta ち:chi つ:tsu て:te と:to " +
        "な:na に:ni ぬ:nu ね:ne の:no は:ha ひ:hi ふ:fu へ:he ほ:ho " +
        "ま:ma み:mi む:mu め:me も:mo や:ya ゆ:yu よ:yo ら:ra り:ri る:ru れ:re ろ:ro " +
        "わ:wa ゐ:wi ゑ:we を:wo ん:n が:ga ぎ:gi ぐ:gu げ:ge ご:go " +
        "ざ:za じ:ji ず:zu ぜ:ze ぞ:zo だ:da ぢ:ji づ:zu で:de ど:do " +
        "ば:ba び:bi ぶ:bu べ:be ぼ:bo ぱ:pa ぴ:pi ぷ:pu ぺ:pe ぽ:po ゔ:vu " +
        "ぁ:a ぃ:i ぅ:u ぇ:e ぉ:o ゃ:ya ゅ:yu ょ:yo ゎ:wa ゕ:ka ゖ:ke")
        .split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }
    private val pairs = ("しぇ:she ちぇ:che じぇ:je てぃ:ti でぃ:di とぅ:tu どぅ:du " +
        "てゅ:tyu でゅ:dyu つぁ:tsa つぃ:tsi つぇ:tse つぉ:tso " +
        "ふぁ:fa ふぃ:fi ふぇ:fe ふぉ:fo ふゅ:fyu うぃ:wi うぇ:we うぉ:wo " +
        "ゔぁ:va ゔぃ:vi ゔぇ:ve ゔぉ:vo ゔゅ:vyu くぁ:kwa くぃ:kwi くぇ:kwe くぉ:kwo")
        .split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }

    fun hiragana(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).map {
        if (it in '\u30a1'..'\u30f6') (it.code - 0x60).toChar() else it
    }.joinToString("")

    fun convert(text: String): String? = units(text)?.joinToString("") { it.second }

    fun units(text: String): List<Pair<IntRange, String>>? {
        val kana = hiragana(text)
        val result = mutableListOf<Pair<IntRange, String>>()
        var i = 0
        while (i < kana.length) {
            val start = i
            var end = i + 1
            val roman = when (kana[i]) {
                'っ' -> {
                    val next = syllable(kana, i + 1)?.first ?: return null
                    if (next.first() in "aeioun") return null
                    if (next.startsWith("ch")) "t" else next.take(1)
                }
                'ー' -> result.lastOrNull()?.second?.lastOrNull { it in "aeiou" }?.toString() ?: return null
                'ん' -> if (syllable(kana, i + 1)?.first?.firstOrNull()?.let { it in "aeiouy" } == true) "n'" else "n"
                else -> {
                    val part = syllable(kana, i) ?: return null
                    end = i + part.second
                    part.first
                }
            }
            result += (start until end) to roman
            i = end
        }
        // Half-width kana can change UTF-16 length during normalization.
        return if (kana.length == text.length) result else listOf(text.indices to result.joinToString("") { it.second })
    }

    private fun syllable(kana: String, offset: Int): Pair<String, Int>? {
        if (offset >= kana.length) return null
        val pair = kana.substring(offset, minOf(offset + 2, kana.length))
        pairs[pair]?.let { return it to 2 }
        val base = basic[kana[offset].toString()] ?: return null
        if (pair.length == 2 && pair[1] in "ゃゅょ" && base.endsWith('i')) {
            val prefix = when (base) { "shi" -> "sh"; "chi" -> "ch"; "ji" -> "j"; else -> base.dropLast(1) + "y" }
            return prefix + when (pair[1]) { 'ゃ' -> "a"; 'ゅ' -> "u"; else -> "o" } to 2
        }
        return base to 1
    }
}
