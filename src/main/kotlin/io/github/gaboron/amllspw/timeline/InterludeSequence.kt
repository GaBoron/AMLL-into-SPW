package io.github.gaboron.amllspw.timeline

import kotlin.math.*

internal class InterludeSnapshot {
    var active = false
    var scale = 1f
    var opacity = 0f
    val dots = FloatArray(3)
    fun hide() { active = false; scale = 1f; opacity = 0f; dots.fill(0f) }
}

/** Native port of AMLL InterludeDotsBase choreography, revision ca0f41d67882 (AGPL-3.0-only). */
internal class InterludeSequence(private val remainingMs: Double, private val holdMs: Double) {
    private val bodyMs = remainingMs - holdMs - 1_000
    val supported = bodyMs >= 910
    private val bodyEnd = remainingMs - 1_000
    private val fallback = bodyMs < 3_000
    private val period = bodyMs / max(1.0, floor(bodyMs / 4_000))
    private val segment = ((bodyMs + 750) / 3).roundToInt().toDouble()
    private val thirdDuration = bodyMs - segment * 2
    private val thirdTarget = if (fallback) 1.0 else thirdDuration / segment

    fun sample(elapsedMs: Double, out: InterludeSnapshot) {
        if (!supported || elapsedMs >= remainingMs) { out.hide(); return }
        out.active = true
        if (elapsedMs < holdMs) { out.scale = 1f; out.opacity = 0f; out.dots.fill(0f); return }
        val time = max(0.0, elapsedMs - holdMs)
        val enter = ease(ENTER, time / 180)
        if (elapsedMs >= bodyEnd) {
            val exit = elapsedMs - bodyEnd
            out.opacity = (enter * (1 - ease(EXIT_FADE, (exit - 750) / 250))).toFloat()
            out.scale = (if (exit < 750) 1 + ease(EXIT_GROW, exit / 750) * .25
                else 1.25 - ease(EXIT_SHRINK, (exit - 750) / 250) * .85).toFloat()
            writeDots(out, time, 1.0, 1.0, thirdTarget + (1 - thirdTarget) * (exit / 750).coerceIn(0.0, 1.0))
        } else {
            out.opacity = enter.toFloat()
            if (fallback) { out.scale = 1f; writeDots(out, time, 1.0, 1.0, 1.0); return }
            val t = time % period / period
            val angle = 4 * PI * t
            val s = sin(angle); val c = cos(angle)
            val breath = t - .084 * s + .008 * (1 - c) + .0046 * s * (c - s)
            out.scale = (if (breath <= .5) 1 + breath * .5 else 1.25 - (breath - .5) * .5).toFloat()
            writeDots(out, time, fraction(time, 0.0, segment, 1.0), fraction(time, segment, segment, 1.0),
                fraction(time, segment * 2, thirdDuration, thirdTarget))
        }
    }

    private fun writeDots(out: InterludeSnapshot, time: Double, a: Double, b: Double, c: Double) {
        for (index in 0..2) {
            val enter = ((time - index * 80) / 750).coerceIn(0.0, 1.0)
            val fraction = when (index) { 0 -> a; 1 -> b; else -> c }
            out.dots[index] = ((.2 + .7 * fraction.coerceIn(0.0, 1.0)) * enter * enter).toFloat()
        }
    }
    private fun fraction(time: Double, delay: Double, duration: Double, target: Double) =
        if (time <= delay) 0.0 else ease(LIGHT, (time - delay) / duration) * target

    companion object {
        private val LIGHT = CubicBezier(.56f, .01f, .45f, 1f)
        private val ENTER = CubicBezier(.59f, .02f, .07f, 1f)
        private val EXIT_GROW = CubicBezier(.14f, .06f, .25f, 1f)
        private val EXIT_SHRINK = CubicBezier(.29f, .03f, 1f, .38f)
        private val EXIT_FADE = CubicBezier(.43f, .08f, .83f, .31f)
        private fun ease(curve: CubicBezier, time: Double) = curve.transform(time.toFloat()).toDouble()
        fun dismissalOpacity(initial: Float, elapsedMs: Double) = initial * (1 - ease(EXIT_FADE, elapsedMs / 150)).toFloat()
    }
}
