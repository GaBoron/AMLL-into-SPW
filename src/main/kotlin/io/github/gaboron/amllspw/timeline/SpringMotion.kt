package io.github.gaboron.amllspw.timeline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.sin
import kotlin.math.sqrt

internal class SpringMotion(var value: Float = 0f, private var stiffness: Float = 125f, private var damping: Float = 19f) {
    var velocity = 0f; private set
    fun configure(stiffness: Float, damping: Float) { this.stiffness = stiffness; this.damping = damping }
    fun snap(target: Float) { value = target; velocity = 0f }
    /** Transfer an in-flight spring sample without discarding its momentum. */
    fun transfer(position: Float, speed: Float) { value = position; velocity = speed }
    /** Forecast the current spring without changing its position or momentum. */
    fun preview(target: Float, seconds: Float, previousTarget: Float = target, delaySeconds: Float = 0f): Float {
        val position = value
        val speed = velocity
        val duration = seconds.coerceAtLeast(0f)
        val delay = delaySeconds.coerceIn(0f, duration)
        advance(previousTarget, delay.toDouble())
        advance(target, (duration - delay).toDouble())
        val projected = value
        transfer(position, speed)
        return projected
    }
    /** Keep spring acceleration while braking a retargeted or overshooting scroll. */
    fun updateBounded(target: Float, deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f) return value
        val distance = target - value
        if (distance * velocity < 0f) velocity = 0f
        update(target, deltaSeconds)
        if (distance * (target - value) <= 0f) snap(target)
        return value
    }
    /** A monotonic, frame-rate-independent follower for rapid changes; never jumps to its target. */
    fun follow(target: Float, deltaSeconds: Float, responseSeconds: Float): Float {
        val dt = deltaSeconds.coerceIn(0f, .064f)
        if (dt == 0f) return value
        val response = responseSeconds.coerceAtLeast(.001f)
        value += (target - value) * -expm1(-dt / response)
        velocity = (target - value) / response
        return value
    }
    fun update(target: Float, deltaSeconds: Float): Float {
        advance(target, deltaSeconds.coerceIn(0f, .064f).toDouble())
        return value
    }

    /** Exact damped-oscillator motion keeps position and velocity continuous on retargets. */
    private fun advance(target: Float, seconds: Double) {
        if (seconds <= 0.0) return
        val displacement = (value - target).toDouble()
        val speed = velocity.toDouble()
        val decay = damping * .5
        val frequencySquared = stiffness - decay * decay
        val nextPosition: Double
        val nextVelocity: Double
        when {
            abs(frequencySquared) < stiffness * 1e-6 -> {
                val envelope = exp(-decay * seconds)
                val slope = speed + decay * displacement
                nextPosition = envelope * (displacement + slope * seconds)
                nextVelocity = envelope * (speed - decay * slope * seconds)
            }
            frequencySquared > 0.0 -> {
                val frequency = sqrt(frequencySquared)
                val envelope = exp(-decay * seconds)
                val cosine = cos(frequency * seconds)
                val sine = sin(frequency * seconds) / frequency
                nextPosition = envelope * (displacement * cosine + (speed + decay * displacement) * sine)
                nextVelocity = envelope * (speed * cosine - (decay * speed + stiffness * displacement) * sine)
            }
            else -> {
                val frequency = sqrt(-frequencySquared)
                // This form avoids cancellation in the slow root and overflow in cosh/sinh.
                val fast = -decay - frequency
                val slow = -stiffness / (decay + frequency)
                val a = (speed - fast * displacement) / (slow - fast)
                val b = displacement - a
                val slowMotion = a * exp(slow * seconds)
                val fastMotion = b * exp(fast * seconds)
                nextPosition = slowMotion + fastMotion
                nextVelocity = slow * slowMotion + fast * fastMotion
            }
        }
        value = (target + nextPosition).toFloat()
        velocity = nextVelocity.toFloat()
    }
}
