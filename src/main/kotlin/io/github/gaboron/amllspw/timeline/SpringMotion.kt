package io.github.gaboron.amllspw.timeline

internal class SpringMotion(var value: Float = 0f, private var stiffness: Float = 125f, private var damping: Float = 19f) {
    var velocity = 0f; private set
    fun configure(stiffness: Float, damping: Float) { this.stiffness = stiffness; this.damping = damping }
    fun snap(target: Float) { value = target; velocity = 0f }
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
