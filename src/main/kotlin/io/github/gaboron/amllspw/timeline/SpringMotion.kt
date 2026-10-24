package io.github.gaboron.amllspw.timeline

import kotlin.math.expm1

internal class SpringMotion(var value: Float = 0f, private var stiffness: Float = 125f, private var damping: Float = 19f) {
    var velocity = 0f; private set
    fun configure(stiffness: Float, damping: Float) { this.stiffness = stiffness; this.damping = damping }
    fun snap(target: Float) { value = target; velocity = 0f }
    /** Transfer an in-flight spring sample without discarding its momentum. */
    fun transfer(position: Float, speed: Float) { value = position; velocity = speed }
    /** Forecast the current spring without changing its position or momentum. */
    fun preview(target: Float, seconds: Float): Float {
        val position = value
        val speed = velocity
        var remaining = seconds.coerceAtLeast(0f)
        while (remaining > 0f) {
            val step = minOf(remaining, .064f)
            update(target, step)
            remaining -= step
        }
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
        var remaining = deltaSeconds.coerceIn(0f, .064f)
        while (remaining > 0) {
            val dt = minOf(remaining, 1f / 240f)
            velocity += ((target - value) * stiffness - velocity * damping) * dt
            value += velocity * dt
            remaining -= dt
        }
        return value
    }
}
