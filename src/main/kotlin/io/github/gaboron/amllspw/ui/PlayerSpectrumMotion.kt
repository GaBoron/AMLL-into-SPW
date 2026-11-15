package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.*
import io.github.gaboron.amllspw.timeline.SpringMotion
import io.github.gaboron.amllspw.spectrum.SpectrumAnalyzer
import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Musical band envelopes preserve their motion across pauses, seeks and track changes. */
@Stable
internal class PlayerSpectrumMotion(val bands: Int = 48) {
    private val envelopes = Array(bands) { SpringMotion() }
    private val analyzer = SpectrumAnalyzer(bands)
    private val targets get() = analyzer.levels
    private val heldPeaks = FloatArray(bands)
    private val holdRemaining = FloatArray(bands)
    private val signal = MutableStateFlow(0L)
    private var revision by mutableIntStateOf(0)
    private var hasData = false

    fun accept(frame: SpectrumFrame?, seconds: Float = 1f / 60f) {
        hasData = frame != null && (frame.sampleRate == 0 || frame.sampleRate >= 8000) && frame.magnitudes.size >= 16
        analyzer.accept(frame, seconds)
        for (band in targets.indices) {
            val level = targets[band]
            if (level > .0001f && level >= heldPeaks[band]) {
                heldPeaks[band] = level
                holdRemaining[band] = .02f
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
        // Sub-pixel fluctuations follow slowly; larger musical changes retain a
        // quick attack and frequency-dependent release. Blend continuously rather
        // than freezing a dead zone, so gradual changes still reach their target.
        val response = if (!hasData) .32f else {
            val change = ((abs(target - envelope.value) - .015f) / .105f).coerceIn(0f, 1f)
            val urgency = change * change * (3f - 2f * change)
            val quick = if (target > envelope.value) .012f else .11f - .055f * band / (bands - 1)
            .14f + (quick - .14f) * urgency
        }
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
internal fun rememberPlayerSpectrum(read: () -> SpectrumFrame?): PlayerSpectrumMotion {
    val reader by rememberUpdatedState(read)
    val motion = remember { PlayerSpectrumMotion() }
    LaunchedEffect(motion) {
        launch { motion.animate() }
        var previous = System.nanoTime()
        while (true) {
            val frame = withContext(Dispatchers.Default) { runCatching { reader() }.getOrNull() }
            val now = System.nanoTime()
            motion.accept(frame, (now - previous) / 1_000_000_000f)
            previous = now
            delay(16)
        }
    }
    return motion
}
