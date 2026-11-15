package io.github.gaboron.amllspw.spectrum

import kotlin.math.*

/** Constant-Q energy bands and a shared exposure preserve both musical shape and transient contrast. */
internal class SpectrumAnalyzer(val bands: Int) {
    val levels = FloatArray(bands)
    private val frequencies = DoubleArray(bands) { 40.0 * 400.0.pow(it.toDouble() / (bands - 1)) }
    private val gains = DoubleArray(bands) { (frequencies[it] / 220.0).pow(.32).coerceIn(.6, 4.0) }
    private val magnitudes = DoubleArray(bands)
    private val background = DoubleArray(bands)
    private val ranked = DoubleArray(bands)
    private var exposure = .06
    private var bass: FilterBank? = null
    private var detail: FilterBank? = null

    fun accept(frame: SpectrumFrame?, seconds: Float) {
        val dt = seconds.coerceIn(.001f, .1f).toDouble()
        if (frame == null || frame.sampleRate < 0 || frame.sampleRate in 1..7999 || frame.magnitudes.size < 16) {
            levels.fill(0f)
            for (band in background.indices) background[band] *= exp(-dt / .22)
            return
        }
        val full = frame.magnitudes
        val short = frame.detailMagnitudes?.takeIf { it.size >= 16 } ?: full
        // Legacy backends retain a nominal frequency axis when they expose only FFT.
        val rate = frame.sampleRate.takeIf { it >= 8000 } ?: 48000
        val low = bass?.takeIf { it.matches(full, rate) }
            ?: FilterBank(full.size, rate).also { bass = it }
        val high = if (short === full) low else detail?.takeIf { it.matches(short, rate) }
            ?: FilterBank(short.size, rate).also { detail = it }
        for (band in magnitudes.indices) {
            magnitudes[band] = (if (frequencies[band] < 250.0) low.energy(full, band)
                else high.energy(short, band)) * gains[band]
            ranked[band] = magnitudes[band]
        }
        ranked.sort()
        // One slow, bounded gain for the whole spectrum, never one normalization per
        // band or frame. Quiet noise cannot acquire full height and bass/treble keep
        // their relative strength. Leave headroom above sustained musical peaks.
        val desired = max(.018, ranked[(bands - 3).coerceAtLeast(0)] * 1.8)
        exposure += (desired - exposure) * -expm1(-dt / if (desired > exposure) .35 else 2.8)
        for (band in levels.indices) {
            val magnitude = magnitudes[band]
            val attack = max(0.0, magnitude - background[band] * 1.15)
            background[band] += (magnitude - background[band]) * -expm1(-dt / .22)
            // An amplitude curve suppresses the low-level bed instead of spreading
            // 96 dB across a few dozen pixels. Positive spectral change accents real
            // kick/snare/hat attacks; a sustained tone still has a steady response.
            val amplitude = max(0.0, magnitude + attack * .8 - .0004) / exposure
            val contrast = amplitude.pow(1.25)
            levels[band] = (if (contrast <= .8) contrast
                else .8 + .2 * -expm1(-(contrast - .8) / .2)).toFloat()
        }
    }

    /** Adjacent triangular bands share actual bin energy, forming connected musical peaks. */
    private inner class FilterBank(private val points: Int, private val rate: Int) {
        private val first = IntArray(bands)
        private val weights = Array(bands) { band ->
            val step = ln(400.0) / (bands - 1)
            val center = frequencies[band]
            first[band] = ceil(center * exp(-step) * points * 2 / rate).toInt().coerceIn(1, points)
            val last = floor(center * exp(step) * points * 2 / rate).toInt().coerceIn(0, points - 1)
            DoubleArray((last - first[band] + 1).coerceAtLeast(0)) { offset ->
                val frequency = (first[band] + offset).toDouble() * rate / (points * 2)
                (1.0 - abs(ln(frequency / center) / step)).coerceAtLeast(0.0)
            }
        }

        fun matches(values: FloatArray, sampleRate: Int) = values.size == points && sampleRate == rate

        fun energy(values: FloatArray, band: Int): Double {
            var power = 0.0
            val start = first[band]
            for (offset in weights[band].indices) {
                val magnitude = values[start + offset].toDouble()
                if (magnitude.isFinite() && magnitude > 0.0) power += magnitude * magnitude * weights[band][offset]
            }
            return sqrt(power)
        }
    }
}
