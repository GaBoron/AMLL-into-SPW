package io.github.gaboron.amllspw.verify

import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import io.github.gaboron.amllspw.ui.PlayerSpectrumMotion
import kotlin.math.*

internal fun PlayerSpectrumMotion.accept(values: FloatArray?) = accept(values?.let { SpectrumFrame(it, 48000) })

internal fun spectrumTone(frequency: Double, amplitude: Float, rate: Int = 48000): SpectrumFrame {
    val scale = if (rate > 96000) 4 else if (rate > 48000) 2 else 1
    fun fft(points: Int): FloatArray {
        val step = rate.toDouble() / (points * 2)
        return FloatArray(points) { bin ->
            (amplitude * exp(-.5 * ((bin * step - frequency) / (step * .65)).pow(2))).toFloat()
        }
    }
    return SpectrumFrame(fft(4096 * scale), rate, fft(1024 * scale))
}
