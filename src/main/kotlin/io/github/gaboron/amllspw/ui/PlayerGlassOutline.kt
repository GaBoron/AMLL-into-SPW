// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Prism UI goo.dart (chen08209, GPL-3.0), revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.*

/** Prism's adaptive ray fan only fills the neck; the two original rounded outlines stay intact. */
internal class PlayerGlassOutline(frame: PlayerGlassFrame) {
    val path: Path
    val bounds: Rect
    val merged: Boolean

    init {
        val a = frame.rect
        val b = frame.droplet
        val first = rounded(a, frame.radius)
        merged = b == null || depth(a, frame.radius, b) >= frame.blend
        path = when {
            b == null || merged -> first
            depth(b, min(b.width, b.height) / 2, a) >= frame.blend -> rounded(b, min(b.width, b.height) / 2)
            else -> {
                val both = Path.combine(PathOperation.Union, first, rounded(b, min(b.width, b.height) / 2))
                val neck = bridge(a, frame.radius, b, frame.blend)
                if (neck == null) both else Path.combine(PathOperation.Union, both, neck)
            }
        }
        bounds = path.getBounds()
    }

    private fun rounded(rect: Rect, radius: Float) = Path().apply {
        addRoundRect(RoundRect(rect, CornerRadius(radius.coerceIn(0f, min(rect.width, rect.height) / 2))))
    }

    private fun depth(a: Rect, radius: Float, b: Rect): Float =
        -listOf(b.topLeft, b.topRight, b.bottomLeft, b.bottomRight).maxOf { distance(a, radius, it) }

    private fun distance(rect: Rect, radius: Float, point: Offset): Float {
        val round = min(radius, min(rect.width, rect.height) / 2)
        val qx = abs(point.x - rect.center.x) - rect.width / 2 + round
        val qy = abs(point.y - rect.center.y) - rect.height / 2 + round
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - round
    }

    private fun bridge(a: Rect, radius: Float, b: Rect, blend: Float): Path? {
        if (blend <= 0 || b.isEmpty) return null
        val turns = 128
        val tolerance = .05f
        val center = b.center
        val limit = max(b.width, b.height) / 2 + max(a.width, a.height) + blend
        fun toA(point: Offset) = distance(a, radius, point)
        fun field(point: Offset): Float {
            val first = toA(point)
            val second = distance(b, min(b.width, b.height) / 2, point)
            val h = (.5f + .5f * (second - first) / blend).coerceIn(0f, 1f)
            return second + (first - second) * h - blend * h * (1f - h)
        }
        fun reach(direction: Offset): Offset {
            var inside = 0f
            var traveled = 0f
            while (traveled < limit) {
                val point = center + direction * traveled
                val platter = toA(point)
                if (platter < -blend) return point
                val depth = field(point)
                if (depth >= 0) {
                    var outside = traveled
                    repeat(4) {
                        val middle = (inside + outside) / 2
                        if (field(center + direction * middle) < 0) inside = middle else outside = middle
                    }
                    break
                }
                inside = traveled
                traveled += max(.5f, min(-depth, platter + blend))
            }
            return center + direction * inside
        }
        val ends = arrayOfNulls<Offset>(turns)
        val depths = FloatArray(turns)
        fun region(turn: Int): Int = depths[turn % turns].let { if (it < -blend) -1 else if (it < blend) 0 else 1 }
        fun cast(turn: Int) {
            val angle = turn.toDouble() / turns * 2 * PI
            val end = reach(Offset(cos(angle).toFloat(), sin(angle).toFloat()))
            ends[turn] = end
            depths[turn] = toA(end)
        }
        fun refine(from: Int, to: Int) {
            if (to - from < 2) return
            fun skip() {
                for (turn in from + 1 until to) if (ends[turn] == null) depths[turn] = depths[from]
            }
            val alike = region(from) == region(to)
            if (alike && region(from) != 0) {
                skip()
                return
            }
            val middle = (from + to) / 2
            cast(middle)
            if (alike && region(middle) == region(from) &&
                offChord(ends[from]!!, ends[to % turns]!!, ends[middle]!!) <= tolerance) {
                skip()
                return
            }
            refine(from, middle)
            refine(middle, to)
        }
        for (turn in 0 until turns step 4) cast(turn)
        for (turn in 0 until turns step 4) refine(turn, turn + 4)
        val flows = BooleanArray(turns) { depths[it] < blend }
        if (flows.none { it }) return null
        val first = flows.indexOf(false).coerceAtLeast(0)
        val path = Path()
        val fan = mutableListOf<Int>()
        fun close() {
            if (fan.size > 1) {
                val rim = fan.mapIndexedNotNull { index, turn ->
                    ends[turn]?.takeIf { index == 0 || index == fan.lastIndex || depths[fan[index - 1]] >= -blend ||
                        depths[turn] >= -blend || depths[fan[index + 1]] >= -blend }
                }
                path.moveTo(center.x, center.y)
                simplify(rim, tolerance).forEach { path.lineTo(it.x, it.y) }
                path.close()
            }
            fan.clear()
        }
        for (index in 0..turns) {
            val turn = (first + index) % turns
            if (flows[turn]) fan += turn else close()
        }
        close()
        return path
    }

    private fun offChord(from: Offset, to: Offset, point: Offset): Float {
        val chord = to - from
        val offset = point - from
        val length = chord.getDistance()
        return if (length == 0f) offset.getDistance() else abs(offset.x * chord.y - offset.y * chord.x) / length
    }

    private fun simplify(points: List<Offset>, tolerance: Float): List<Offset> {
        if (points.size < 3) return points
        val kept = BooleanArray(points.size)
        kept[0] = true
        kept[points.lastIndex] = true
        fun split(from: Int, to: Int) {
            var farthest = 0f
            var at = -1
            for (index in from + 1 until to) {
                val off = offChord(points[from], points[to], points[index])
                if (off > farthest) {
                    farthest = off
                    at = index
                }
            }
            if (at >= 0 && farthest > tolerance) {
                kept[at] = true
                split(from, at)
                split(at, to)
            }
        }
        split(0, points.lastIndex)
        return points.filterIndexed { index, _ -> kept[index] }
    }
}
