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

/** Peak-led FFT bands retain their attack/hold/release envelopes across pauses, seeks and track changes. */
@Stable
internal class PlayerSpectrumMotion(val bands: Int = 48) {
    private val envelopes = Array(bands) { SpringMotion() }
    private val targets = FloatArray(bands)
    private val heldPeaks = FloatArray(bands)
    private val holdRemaining = FloatArray(bands)
    private val signal = MutableStateFlow(0L)
    private var revision by mutableIntStateOf(0)
    private var sourceBins = 0
    private val edges = IntArray(bands + 1)
    private val widthGains = DoubleArray(bands)
    private var hasData = false

    fun accept(fft: FloatArray?) {
        val count = fft?.size ?: 0
        val available = count > bands
        hasData = available
        if (available && sourceBins != count) {
            sourceBins = count
            edges[0] = 1 // BASS's first FFT point is DC, not a bass band.
            val logarithm = ln(count.toDouble())
            for (edge in 1..bands) {
                // Respect the native FFT resolution: every source bin belongs to one
                // band, and the first few bass points are never copied to many bars.
                edges[edge] = exp(logarithm * edge / bands).roundToInt()
                    .coerceIn(edges[edge - 1] + 1, count - (bands - edge))
                widthGains[edge - 1] = (edges[edge] - edges[edge - 1]).toDouble().pow(.12)
            }
        }
        for (band in targets.indices) {
            var energy = 0.0
            var peak = 0.0
            if (available && fft != null) for (bin in edges[band] until edges[band + 1]) {
                val value = fft[bin].takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
                energy += value * value
                peak = max(peak, value)
            }
            val width = if (available) edges[band + 1] - edges[band] else 1
            val rms = sqrt(energy / width)
            // Keep note/transient peaks instead of raising every wide band by its
            // total power. A small fixed width correction still reveals real treble.
            val magnitude = (.75 * peak + .25 * rms) * if (available) widthGains[band] else 1.0
            val normalized = if (magnitude > 0.0) ((20.0 * log10(magnitude) + 96.0) / 80.0)
                .coerceAtLeast(0.0) else 0.0
            // Preserve contrast below 80% height; only the top has a soft ceiling.
            val level = if (normalized <= .8) normalized.toFloat()
                else (.8 + .2 * -expm1(-(normalized - .8) / .2)).toFloat()
            targets[band] = level
            if (level > .0001f && level >= heldPeaks[band]) {
                heldPeaks[band] = level
                holdRemaining[band] = .05f
            }
        }
        // Compare with the displayed envelope too: small cumulative input changes
        // must wake an idle renderer even when adjacent FFT samples are very close.
        if (moving) signal.value++
    }

    fun advance(seconds: Float) {
        val dt = seconds.coerceIn(0f, .064f)
        if (dt <= 0f) return
        for (band in envelopes.indices) {
            val held = min(dt, holdRemaining[band])
            if (held > 0f) follow(band, max(heldPeaks[band], targets[band]), held)
            holdRemaining[band] = (holdRemaining[band] - dt).coerceAtLeast(0f)
            if (dt > held) follow(band, targets[band], dt - held)
            if (holdRemaining[band] == 0f) heldPeaks[band] = targets[band]
        }
        revision++
    }

    private fun follow(band: Int, target: Float, seconds: Float) {
        val envelope = envelopes[band]
        // One exponential envelope, rather than a second smoother on top. The
        // longer gap release lets pause/buffering decay without resetting height.
        val response = if (target > envelope.value) .025f else if (hasData) .15f else .32f
        envelope.follow(target, seconds, response)
    }

    fun level(band: Int): Float {
        revision // Register the draw scope's snapshot read without publishing a new array each frame.
        return envelopes[band].value.coerceIn(0f, 1f)
    }

    val moving: Boolean get() = envelopes.indices.any {
        val held = if (holdRemaining[it] > 0f) max(heldPeaks[it], targets[it]) else targets[it]
        abs(envelopes[it].value - held) > .0001f || abs(envelopes[it].value - targets[it]) > .0001f
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
