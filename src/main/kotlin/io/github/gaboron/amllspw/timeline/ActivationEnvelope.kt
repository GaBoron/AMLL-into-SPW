package io.github.gaboron.amllspw.timeline

/** AMLL's activation fades: 300 ms in, 450 ms out, reversible from the current brightness. */
internal class ActivationEnvelope(initial: Float = 0f, private val enterSeconds: Float = .3f, private val exitSeconds: Float = .45f,
    private val easing: CubicBezier? = null) {
    var value = initial; private set
    private var target = initial
    private var origin = initial
    private var elapsed = 0f

    fun update(active: Boolean, dt: Float, snap: Boolean = false): Float {
        val next = if (active) 1f else 0f
        if (snap) { value = next; target = next; origin = next; elapsed = 0f; return value }
        if (next != target) { origin = value; target = next; elapsed = 0f }
        elapsed += dt.coerceAtLeast(0f)
        val linear = (elapsed / if (active) enterSeconds else exitSeconds).coerceIn(0f, 1f)
        val progress = easing?.transform(linear) ?: linear
        value = origin + (target - origin) * progress
        return value
    }
}
