package io.github.gaboron.amllspw.ui

internal data class LyricRowExtent(val top: Float, val bottom: Float)

/** Measure the text being painted, rather than treating all reserved spacing as occupied. */
internal fun lyricRowExtent(group: GroupLayout, backgroundSlide: Float, visible: (Int) -> Boolean): LyricRowExtent? {
    val background = group.rows.filter { it.line.background }.sumOf { it.height.toDouble() }.toFloat()
    var rowTop = 0f
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    group.rows.forEachIndexed { index, row ->
        if (visible(index)) {
            val y = rowTop + if (row.line.background) backgroundSlide.coerceIn(-80f, 0f) / 100f * background else 0f
            top = minOf(top, y)
            bottom = maxOf(bottom, y + row.height - 10f)
        }
        rowTop += row.height
    }
    return if (top.isFinite()) LyricRowExtent(top, bottom) else null
}

/** Constrain relative motion at painted text bounds, leaving paragraph whitespace free. */
internal class LyricRowConstraints(extents: List<LyricRowExtent?>, focus: Int) {
    private val indices = extents.indices.filter { extents[it] != null }
    private val separation = DoubleArray(indices.size)
    // The current lead has more inertia when a genuinely crowded pair shares an impulse.
    private val weights = DoubleArray(indices.size) { if (indices[it] == focus) 8.0 else 1.0 }

    init {
        for (i in 1 until indices.size) {
            val upper = extents[indices[i - 1]]!!
            val lower = extents[indices[i]]!!
            // Leave a small ink gap while allowing the larger paragraph spacing to breathe.
            separation[i] = separation[i - 1] + maxOf(0f, upper.bottom - lower.top + 4f)
        }
    }

    /** A guard for changed text/layout; ordinary spring steps are protected by velocity. */
    fun constrainPositions(positions: FloatArray, minimumY: FloatArray? = null) {
        val coordinates = DoubleArray(indices.size) { positions[indices[it]].toDouble() - separation[it] }
        val minimum = DoubleArray(indices.size) { i ->
            (minimumY?.get(indices[i])?.toDouble() ?: Double.NEGATIVE_INFINITY) - separation[i]
        }
        val ordered = orderedProjection(coordinates, minimum)
        indices.forEachIndexed { i, index -> positions[index] += (ordered[i] - coordinates[i]).toFloat() }
    }

    /**
     * For a critically damped relative spring, gap velocity >= -frequency * gap
     * guarantees that a nonnegative gap cannot cross zero while approaching a legal layout.
     * A uniform scroll velocity cancels out of every pair, so its rebound is preserved.
     */
    fun constrainVelocities(positions: FloatArray, velocities: FloatArray, frequency: Float,
        minimumY: FloatArray? = null) {
        val coordinates = DoubleArray(indices.size) { i ->
            velocities[indices[i]] + frequency * (positions[indices[i]].toDouble() - separation[i])
        }
        val minimum = DoubleArray(indices.size) { i ->
            frequency * ((minimumY?.get(indices[i])?.toDouble() ?: Double.NEGATIVE_INFINITY) - separation[i])
        }
        val ordered = orderedProjection(coordinates, minimum)
        indices.forEachIndexed { i, index -> velocities[index] += (ordered[i] - coordinates[i]).toFloat() }
    }

    /** Weighted isotonic regression resolves all neighbouring contacts in linear time. */
    private fun orderedProjection(values: DoubleArray, minimum: DoubleArray): DoubleArray {
        val sums = DoubleArray(values.size)
        val mass = DoubleArray(values.size)
        val bounds = DoubleArray(values.size)
        val starts = IntArray(values.size)
        val ends = IntArray(values.size)
        var blocks = 0
        fun mean(block: Int) = maxOf(sums[block] / mass[block], bounds[block])
        values.indices.forEach { i ->
            sums[blocks] = values[i] * weights[i]
            mass[blocks] = weights[i]
            bounds[blocks] = minimum[i]
            starts[blocks] = i
            ends[blocks] = i
            blocks++
            while (blocks > 1 && mean(blocks - 2) > mean(blocks - 1)) {
                val lower = blocks - 1
                val upper = lower - 1
                sums[upper] += sums[lower]
                mass[upper] += mass[lower]
                bounds[upper] = maxOf(bounds[upper], bounds[lower])
                ends[upper] = ends[lower]
                blocks--
            }
        }
        val result = DoubleArray(values.size)
        for (block in 0 until blocks) {
            val value = mean(block)
            for (i in starts[block]..ends[block]) result[i] = value
        }
        return result
    }
}
