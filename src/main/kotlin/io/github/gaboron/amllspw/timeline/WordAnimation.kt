package io.github.gaboron.amllspw.timeline

import io.github.gaboron.amllspw.lyrics.LyricWord
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Native translation of AMLL core's float and emphasize animation timing (AGPL-3.0-only). */
internal object WordAnimation {
    data class Transform(val liftEm: Float = 0f, val scale: Float = 1f, val shiftXEm: Float = 0f, val glow: Float = 0f, val blurEm: Float = 0f)
    private val easeOut = CubicBezier(0f, 0f, .58f, 1f)
    private val emphasizeIn = CubicBezier(.2f, .4f, .58f, 1f)
    private val emphasizeOut = CubicBezier(.3f, 0f, .58f, 1f)

    fun progress(word: LyricWord, timeMs: Long): Float = if (word.timed)
        ((timeMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) else 1f

    /** The upstream word container floats as a unit, including its romanization. */
    fun floatLift(word: LyricWord, timeMs: Long, background: Boolean): Float = if (!word.timed) 0f else
        .05f * (if (background) 2f else 1f) * easeOut.transform(
            (timeMs - word.startMs).toFloat() / maxOf(1_000f, (word.endMs - word.startMs).toFloat()))

    fun evaluate(word: LyricWord, timeMs: Long, background: Boolean, lastWord: Boolean, character: Int = 0, characters: Int = 1): Transform {
        return Prepared(word, background, lastWord, characters).evaluate(timeMs, character)
    }

    /** Unicode classification, ruby count and emphasis strength are layout data, not frame work. */
    class Prepared(private val word: LyricWord, background: Boolean, lastWord: Boolean, private val characters: Int) {
        private val duration = (word.endMs - word.startMs).coerceAtLeast(1).toFloat()
        private val multiplier = if (background) 2f else 1f
        private val cjk = word.text.codePoints().anyMatch { code ->
            Character.UnicodeScript.of(code) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        }
        private val emphasize = duration >= 1_000 && (cjk || word.text.trim().length in 2..7)
        private var du = maxOf(1_000f, duration)
        private var amount = du / 2_000
        private var blur = du / 3_000
        private val anchor = maxOf(1, if (word.ruby.isNotEmpty()) word.ruby.codePointCount(0, word.ruby.length) else characters)
        init {
            amount = (if (amount > 1) sqrt(amount) else amount.pow(3)) * .6f
            blur = (if (blur > 1) sqrt(blur) else blur.pow(3)) * .5f
            if (lastWord) { amount *= 1.6f; blur *= 1.5f; du *= 1.2f }
            amount = minOf(amount, 1.2f); blur = minOf(blur, .8f)
        }

        val peakGlow: Float get() = if (word.timed && emphasize) blur else 0f

        fun evaluate(timeMs: Long, character: Int): Transform {
            if (!word.timed) return Transform()
            val elapsed = (timeMs - word.startMs).toFloat()
            val baseLift = floatLift(word, timeMs, multiplier > 1f)
            if (!emphasize) return Transform(liftEm = baseLift)
            val delayed = elapsed - du / 2.5f / anchor * character
            val progress = (delayed / du).coerceIn(0f, 1f)
            val pulse = if (progress < .5f) emphasizeIn.transform(progress * 2) else 1f - emphasizeOut.transform((progress - .5f) * 2)
            val floating = sin(((delayed + 400) / (du * 1.4f)).coerceIn(0f, 1f) * PI).toFloat()
            return Transform(baseLift + floating * .05f * multiplier + pulse * .025f * amount,
                1f + pulse * .1f * amount, -pulse * .03f * amount * (characters / 2f - character), pulse * blur, minOf(.3f, blur * .3f))
        }
    }
}
