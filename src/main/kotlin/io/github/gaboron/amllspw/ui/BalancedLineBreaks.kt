package io.github.gaboron.amllspw.ui

internal data class BreakToken(val text: String, val width: Double, val isSpace: Boolean = text.isBlank())

/** AMLL's lyric-line-break.ts cost model, independent of shaping and drawing. */
internal object BalancedLineBreaks {
    private val punctuation = Regex("[,.;:!?，。；：！？、）】》」』’”)\\[\\]}>~…]$")

    fun calculate(tokens: List<BreakToken>, width: Double, cjkBoundaries: Set<Int>): List<Int> {
        val n = tokens.size
        if (n == 0 || width <= 0) return emptyList()
        val offsets = IntArray(n + 1)
        val prefix = DoubleArray(n + 1)
        tokens.forEachIndexed { i, token ->
            offsets[i + 1] = offsets[i] + token.text.length
            prefix[i + 1] = prefix[i] + token.width
        }
        if (prefix[n] <= width) return emptyList()
        val costs = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val next = IntArray(n + 1) { -1 }
        costs[n] = 0.0
        for (i in n - 1 downTo 0) {
            for (j in i + 1..n) {
                val used = prefix[j] - prefix[i]
                val lineCost = if (used > width) {
                    if (j != i + 1) break
                    square(used - width) * 1_000
                } else square(width - used)
                val penalty = if (j == n) 0.0 else when {
                    punctuation.containsMatchIn(tokens[j - 1].text) -> -square(width * .6)
                    tokens[j - 1].isSpace -> -square(width * .4)
                    offsets[j] in cjkBoundaries -> square(width * .15)
                    else -> square(width * .5)
                }
                val cost = lineCost + penalty + costs[j]
                if (cost < costs[i]) { costs[i] = cost; next[i] = j }
            }
        }
        return buildList {
            var cursor = next[0]
            while (cursor in 1 until n) { add(cursor); cursor = next[cursor] }
        }
    }

    private fun square(value: Double) = value * value
}
