package io.github.gaboron.amllspw.spectrum

/** Linear FFT magnitudes with an optional shorter window; rate 0 denotes missing legacy format metadata. */
internal data class SpectrumFrame(
    val magnitudes: FloatArray,
    val sampleRate: Int,
    val detailMagnitudes: FloatArray? = null,
)
