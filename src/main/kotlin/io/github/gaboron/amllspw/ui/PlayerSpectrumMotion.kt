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

    fun accept(fft: FloatArray?) {
        var changed = false
        val count = fft?.size ?: 0
        val logarithm = ln(count.coerceAtLeast(2).toDouble())
        for (band in targets.indices) {
            val start = exp(logarithm * band / bands).toInt().coerceIn(1, count.coerceAtLeast(1))
            val end = exp(logarithm * (band + 1) / bands).toInt().coerceIn(start + 1, maxOf(count, start + 1))
            var energy = 0.0
            if (fft != null) for (bin in start until minOf(end, count)) {
                val value = fft[bin].takeIf { it.isFinite() && it > 0f } ?: 0f
                energy += value * value
            }
            val rms = sqrt(energy / maxOf(1, minOf(end, count) - start))
            // A fixed dB window preserves musical dynamics instead of normalizing every frame to full height.
            val level = ((20.0 * log10(rms.coerceAtLeast(1e-8)) + 72.0) / 60.0).toFloat().coerceIn(0f, 1f)
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
