package io.github.gaboron.amllspw.ui

import androidx.compose.runtime.*
import kotlin.math.abs

/** Holds a submitted value while the asynchronous player still reports the previous sample. */
@Stable
internal class PlayerValueHandoff(private val tolerance: Float) {
    private data class Submission(val value: Float, val origin: Float, val deadline: Long)
    var preview by mutableStateOf<Float?>(null)
    private var submitted by mutableStateOf<Submission?>(null)

    fun value(source: Float): Float = preview ?: submitted?.value ?: source

    fun submit(value: Float, source: Float, now: Long = System.nanoTime()) {
        submitted = Submission(value.coerceIn(0f, 1f), source, now + 2_500_000_000L)
    }

    fun sample(value: Float, now: Long = System.nanoTime()) {
        val pending = submitted ?: return
        val distance = abs(pending.value - pending.origin)
        val moved = abs(value - pending.origin) > minOf(.00001f, distance * .5f)
        if (distance < .00001f || moved && abs(value - pending.value) <= tolerance || now >= pending.deadline) {
            submitted = null
        }
    }
}
