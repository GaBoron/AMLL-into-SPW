package io.github.gaboron.amllspw.cover

import kotlin.math.abs
import kotlin.math.roundToInt

private const val EDGE_BAND = 14

internal data class CoverEdgeMatte(val mask: IntArray, val background: BooleanArray)

/**
 * Refines the seam with a thin trimap. Grow matching backdrop from the perimeter and
 * distinctive foreground from the interior, then relax uncertain pixels along color edges.
 */
internal fun coverEdgeMatte(source: IntArray, field: CoverColorField, profile: CoverBackdropProfile): CoverEdgeMatte {
    val size = COVER_SAMPLE_SIZE
    val prior = coverDetailMask(source, field, profile)
    val depth = IntArray(source.size) { index ->
        val x = index % size
        val y = index / size
        minOf(x, y, size - 1 - x, size - 1 - y)
    }
    val band = depth.indices.filter { depth[it] <= EDGE_BAND }
    val residual = IntArray(source.size)
    for (index in band) {
        val base = field.color(index % size + size, index / size + size)
        residual[index] = edgeColorDistance(source[index], profile.blendGround(base))
    }
    val background = growEdgeRegion(source, band.filter { depth[it] <= 1 && residual[it] <= 21 }) {
        depth[it] < EDGE_BAND && residual[it] <= 28
    }
    val foreground = growEdgeRegion(source, band.filter { depth[it] >= EDGE_BAND - 1 && residual[it] >= 31 }) {
        depth[it] in 1..EDGE_BAND && residual[it] >= 25
    }

    val target = FloatArray(source.size) { component(prior[it], 0) / 255f }
    val certainty = FloatArray(source.size)
    val cropLimit = FloatArray(source.size)
    val strength = FloatArray(source.size)
    val links = Array(4) { FloatArray(band.size) }
    val neighbors = Array(4) { IntArray(band.size) }
    for ((position, index) in band.withIndex()) {
        val distance = depth[index].toFloat()
        if (distance == 0f || distance >= EDGE_BAND) continue
        val localSupport = if (profile.groundWeight < 1f) edgeBackdropSupport(source, background, foreground, index) else 1f
        val support = maxOf(profile.groundWeight, localSupport)
        strength[index] = .27f + .73f * support
        cropLimit[index] = edgeRamp(0f, 5.4f - 2.6f * support, distance)
        when {
            background[index] && !foreground[index] -> {
                target[index] = minOf(target[index], edgeRamp(4f, 13f, distance))
                certainty[index] = 2.6f
            }
            foreground[index] && !background[index] -> {
                // A shaded landscape can also have locally consistent edges. Only
                // promote strongly when there is evidence of a separate background.
                val protection = .24f + .76f * support
                target[index] += (cropLimit[index] - target[index]).coerceAtLeast(0f) * protection
                certainty[index] = 3.4f
            }
            else -> certainty[index] = .8f
        }
        visitEdgeNeighbors(index, size) { direction, other ->
            val difference = edgeColorDistance(source[index], source[other])
            neighbors[direction][position] = other
            links[direction][position] = 1f / (1f + difference * difference / 144f)
        }
    }

    var alpha = target.copyOf()
    var pending = alpha.copyOf()
    repeat(6) {
        for ((position, index) in band.withIndex()) {
            val keep = certainty[index]
            if (keep == 0f) continue
            var sum = target[index] * keep
            var weight = keep
            for (direction in 0..3) {
                val link = links[direction][position]
                sum += alpha[neighbors[direction][position]] * link
                weight += link
            }
            // A subject crossing the original crop still needs a sub-band fade before its pixels end.
            pending[index] = minOf(sum / weight, cropLimit[index])
        }
        val swap = alpha
        alpha = pending
        pending = swap
    }
    val mask = IntArray(source.size) { index ->
        val feather = component(prior[index], 0) / 255f
        // Let the wide feather anchor the visible blend. The trimap only reshapes
        // its partial-opacity pixels; it cannot put a hard silhouette at the crop edge.
        val range = .52f * feather * (1f - feather)
        val correction = ((alpha[index] - feather) * strength[index]).coerceIn(-range, range)
        val opacity = ((feather + correction) * 255f).roundToInt()
        val material = if (depth[index] <= EDGE_BAND) localMaterial(source, background, index) else 255
        (0xff shl 24) or (opacity shl 16) or (material shl 8)
    }
    return CoverEdgeMatte(mask, background)
}

private fun edgeBackdropSupport(source: IntArray, background: BooleanArray, foreground: BooleanArray, index: Int): Float {
    val size = COVER_SAMPLE_SIZE
    val x = index % size
    val y = index / size
    var hits = 0
    var samples = 0
    var boundary = 0
    for (row in maxOf(0, y - 7)..minOf(size - 1, y + 7) step 2) {
        for (column in maxOf(0, x - 7)..minOf(size - 1, x + 7) step 2) {
            val here = row * size + column
            if (background[here]) hits++
            if (background[here] || foreground[here]) {
                visitEdgeNeighbors(here, size) { _, other ->
                    if (background[here] && foreground[other] || foreground[here] && background[other])
                        boundary = maxOf(boundary, edgeColorDistance(source[here], source[other]))
                }
            }
            samples++
        }
    }
    return edgeRamp(.12f, .48f, hits.toFloat() / samples) * edgeRamp(30f, 58f, boundary.toFloat())
}

private fun growEdgeRegion(source: IntArray, seeds: List<Int>, eligible: (Int) -> Boolean): BooleanArray {
    val marked = BooleanArray(source.size)
    val queue = IntArray(source.size)
    var read = 0
    var write = 0
    for (seed in seeds) {
        marked[seed] = true
        queue[write++] = seed
    }
    while (read < write) {
        val index = queue[read++]
        visitEdgeNeighbors(index, COVER_SAMPLE_SIZE) { _, other ->
            if (!marked[other] && eligible(other) && edgeColorDistance(source[index], source[other]) <= 24) {
                marked[other] = true
                queue[write++] = other
            }
        }
    }
    return marked
}

private inline fun visitEdgeNeighbors(index: Int, size: Int, visit: (Int, Int) -> Unit) {
    if (index % size > 0) visit(0, index - 1)
    if (index % size < size - 1) visit(1, index + 1)
    if (index >= size) visit(2, index - size)
    if (index < size * (size - 1)) visit(3, index + size)
}

private fun localMaterial(source: IntArray, background: BooleanArray, index: Int): Int {
    val size = COVER_SAMPLE_SIZE
    val x = index % size
    val y = index / size
    var roughness = 0
    var count = 0
    for (row in maxOf(0, y - 3)..minOf(size - 2, y + 3)) {
        for (column in maxOf(0, x - 3)..minOf(size - 2, x + 3)) {
            val here = row * size + column
            if (!background[here] || !background[here + 1] || !background[here + size]) continue
            roughness += minOf(edgeColorDistance(source[here], source[here + 1]),
                edgeColorDistance(source[here], source[here + size]))
            count++
        }
    }
    if (count < 6) return 255
    return (255f * edgeRamp(1.5f, 9f, roughness.toFloat() / count)).roundToInt()
}

private fun edgeColorDistance(a: Int, b: Int): Int = maxOf(
    abs(component(a, 16) - component(b, 16)),
    abs(component(a, 8) - component(b, 8)),
    abs(component(a, 0) - component(b, 0)),
)

private fun edgeRamp(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
