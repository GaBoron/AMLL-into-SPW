package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.spectrum.*
import io.github.gaboron.amllspw.ui.PlayerSpectrumMotion
import kotlin.math.*

internal fun verifySpectrumEnvelope() {
    verifyTargetBuffer()
    val dt = 1f / 60f
    fun levels(m: PlayerSpectrumMotion) = FloatArray(m.bands) { m.level(it) }
    fun peak(m: PlayerSpectrumMotion) = levels(m).max()
    fun step(m: PlayerSpectrumMotion, frame: SpectrumFrame?, count: Int) {
        repeat(count) {
            m.accept(frame, dt)
            m.advance(dt)
        }
    }
    fun band(hz: Double) = (ln(hz / 40.0) / ln(400.0) * 47).roundToInt()
    for (rate in listOf(44100, 48000, 96000, 192000)) {
        var previous = -1
        for (frequency in listOf(60.0, 120.0, 250.0, 1000.0, 6000.0, 12000.0)) {
            val motion = PlayerSpectrumMotion()
            step(motion, spectrumTone(frequency, .04f, rate), 30)
            val response = levels(motion)
            val maximum = response.indices.maxBy { response[it] }
            check(abs(maximum - band(frequency)) <= 1 && maximum > previous) {
                "Tone frequency misplaced: $frequency Hz at $rate -> $maximum expected ${band(frequency)}"
            }
            check(response.max() > .05f)
            check(response.indices.filter { abs(it - maximum) > 4 }.all { response[it] < .001f })
            previous = maximum
        }
    }
    val bass = PlayerSpectrumMotion()
    step(bass, spectrumTone(60.0, .06f), 12)
    check(levels(bass).take(12).max() > .25f && levels(bass).drop(16).max() < .001f)
    val hat = PlayerSpectrumMotion()
    step(hat, spectrumTone(8000.0, .02f), 3)
    val attack = peak(hat)
    check(attack > .3f) { "Short treble attack was lost: $attack" }
    step(hat, SpectrumFrame(FloatArray(4096), 48000, FloatArray(1024)), 10)
    check(peak(hat) < attack * .22f) { "Successive hat hits have no valley: ${peak(hat)}/$attack" }
    val before = levels(bass)
    bass.accept(null)
    check(levels(bass).contentEquals(before))
    bass.advance(dt)
    check(before.indices.all { abs(bass.level(it) - before[it] * exp(-dt / .32f)) < .0001f })
    step(bass, null, 240)
    check(!bass.moving && peak(bass) < .0001f)
    val silent = PlayerSpectrumMotion()
    step(silent, SpectrumFrame(FloatArray(4096) { .000002f }, 48000), 600)
    check(!silent.moving && peak(silent) == 0f) { "Noise floor was normalized into music" }
    val dc = FloatArray(4096).also { it[0] = 1f }
    step(silent, SpectrumFrame(dc, 48000), 120)
    check(peak(silent) == 0f)
    val invalid = FloatArray(256) {
        when (it % 3) {
            0 -> Float.NaN
            1 -> Float.POSITIVE_INFINITY
            else -> -1f
        }
    }
    step(silent, SpectrumFrame(invalid, 48000), 20)
    check(levels(silent).all { it.isFinite() && it == 0f })
    // FFT window/sample-rate changes retarget existing envelopes without resetting positions.
    val motion = PlayerSpectrumMotion()
    step(motion, spectrumTone(120.0, .03f), 60)
    val old = levels(motion)
    motion.accept(spectrumTone(120.0, .03f, 96000), dt)
    check(levels(motion).contentEquals(old))
    motion.advance(dt)
    check(old.indices.maxOf { abs(old[it] - motion.level(it)) } < .05f)
    println("Spectrum: physical bass/mid/treble placement at 44.1/48/96/192 kHz, localized musical peaks, short hat attack/valley, pause decay, DC/noise rejection and format continuity passed")
}

private fun verifyTargetBuffer() {
    val buffer = SpectrumTargetBuffer(1)
    buffer.accept(floatArrayOf(.8f))
    check(buffer.levels[0] == 0f)
    buffer.accept(floatArrayOf(0f))
    check(buffer.levels[0] == .8f) { "An isolated real peak lost height" }
    buffer.accept(floatArrayOf(0f))
    check(buffer.levels[0] == 0f)
    repeat(3) { buffer.accept(floatArrayOf(.4f)) }
    val small = FloatArray(40) { frame ->
        buffer.accept(floatArrayOf(.4f + if (frame % 2 == 0) .01f else -.01f))
        buffer.levels[0]
    }
    check(small.drop(3).max() - small.drop(3).min() < .0001f) {
        "Alternating fine chatter was not averaged"
    }
    for (step in 0..120) {
        val input = .4f + step * .001f
        buffer.accept(floatArrayOf(input))
        if (step > 3) check(abs(buffer.levels[0] - (input - .001f)) < .00001f)
    }
    buffer.clear()
    check(buffer.levels.all { it == 0f })
    // Clearing must reset history as well as the visible output.
    buffer.accept(floatArrayOf(0f))
    check(buffer.levels.all { it == 0f })
    println("Short target buffer: isolated peaks, fine chatter, gradual motion and silence clear passed")
}

object SpectrumRegression {
    @JvmStatic fun main(args: Array<String>) {
        verifySpectrumEnvelope()
    }
}
