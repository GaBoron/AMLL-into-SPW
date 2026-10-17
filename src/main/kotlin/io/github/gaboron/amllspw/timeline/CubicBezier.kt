package io.github.gaboron.amllspw.timeline

/** CSS cubic-bezier timing evaluated without a browser or per-frame allocations. */
internal class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    fun transform(progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        repeat(15) {
            val t = (low + high) * .5f
            if (axis(t, x1, x2) < x) low = t else high = t
        }
        return axis((low + high) * .5f, y1, y2)
    }
    private fun axis(t: Float, a: Float, b: Float): Float {
        val rest = 1f - t
        return 3f * rest * rest * t * a + 3f * rest * t * t * b + t * t * t
    }
}
