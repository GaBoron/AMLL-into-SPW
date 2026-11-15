package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.*
import io.github.gaboron.amllspw.timeline.SpringMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Logarithmic bands keep their springs across pauses, seeks and track changes. No synthetic signal is generated. */
@Stable
internal class PlayerSpectrumMotion(val bands: Int = 48) {
    private val springs = Array(bands) { SpringMotion(0f, 100f, 20f) }
    private val targets = FloatArray(bands)
    private val signal = MutableStateFlow(0L)
    private var revision by mutableIntStateOf(0)
    private var sourceBins = 0
    private val edges = IntArray(bands + 1)

    fun accept(fft: FloatArray?) {
        var changed = false
        val count = fft?.size ?: 0
        val available = count > bands
        if (available && sourceBins != count) {
            sourceBins = count
            edges[0] = 1 // BASS's first FFT point is DC, not a bass band.
            val logarithm = ln(count.toDouble())
            for (edge in 1..bands) {
                // Respect the native FFT resolution: every source bin belongs to one
                // band, and the first few bass points are never copied to many bars.
                edges[edge] = exp(logarithm * edge / bands).roundToInt()
                    .coerceIn(edges[edge - 1] + 1, count - (bands - edge))
            }
        }
        for (band in targets.indices) {
            var energy = 0.0
            if (available && fft != null) for (bin in edges[band] until edges[band + 1]) {
                val value = fft[bin].takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
                energy += value * value
            }
            // Integrate band power instead of averaging away the wider treble bands.
            // A soft knee retains bass dynamics without clipping at -24 dB. The
            // fixed floor keeps quiet harmonics visible without frame normalization.
            val level = if (energy > 0.0) tanh(((10.0 * log10(energy) + 105.0) / 80.0)
                .coerceAtLeast(0.0)).toFloat() else 0f
            if (abs(level - targets[band]) > .0001f) changed = true
            targets[band] = level
        }
        if (changed) signal.value++
    }

    fun advance(seconds: Float) {
        springs.indices.forEach { springs[it].update(targets[it], seconds) }
        revision++
    }

    fun level(band: Int): Float {
        revision // Register the draw scope's snapshot read without publishing a new array each frame.
        return springs[band].value.coerceIn(0f, 1f)
    }

    val moving: Boolean get() = springs.indices.any {
        abs(springs[it].value - targets[it]) > .0001f || abs(springs[it].velocity) > .001f
    }

    suspend fun animate() {
        var stamp = 0L
        while (true) {
            val seen = signal.value
            if (!moving) {
                signal.first { it != seen }
                stamp = 0L
            }
            withFrameNanos { now ->
                val dt = if (stamp == 0L) 1f / 60f else (now - stamp).coerceAtLeast(0L) / 1_000_000_000f
                stamp = now
                advance(dt)
            }
        }
    }
}

@Composable
internal fun rememberPlayerSpectrum(read: () -> FloatArray?): PlayerSpectrumMotion {
    val reader by rememberUpdatedState(read)
    val motion = remember { PlayerSpectrumMotion() }
    LaunchedEffect(motion) {
        launch { motion.animate() }
        while (true) {
            motion.accept(withContext(Dispatchers.Default) { runCatching { reader() }.getOrNull() })
            delay(33)
        }
    }
    return motion
}
