package io.github.gaboron.amllspw.cover

internal const val COVER_SAMPLE_SIZE = 128
internal const val COVER_PLATE_SIZE = COVER_SAMPLE_SIZE * 3
private const val CELL = 8
internal const val COVER_FIELD_SIZE = COVER_PLATE_SIZE / CELL
private const val GRID = COVER_FIELD_SIZE
private const val FIRST = COVER_SAMPLE_SIZE / CELL
private const val LAST = FIRST * 2

/** Harmonic continuation carries broad color in two dimensions beyond local contour support. */
internal class CoverColorField(source: IntArray, edgeBackground: BooleanArray? = null) {
    private val red = FloatArray(GRID * GRID)
    private val green = FloatArray(GRID * GRID)
    private val blue = FloatArray(GRID * GRID)

    init {
        val r = IntArray(CELL * CELL)
        val g = IntArray(CELL * CELL)
        val b = IntArray(CELL * CELL)
        val backdropR = IntArray(CELL * CELL)
        val backdropG = IntArray(CELL * CELL)
        val backdropB = IntArray(CELL * CELL)
        for (y in FIRST until LAST) for (x in FIRST until LAST) {
            val index = y * GRID + x
            val outerCell = minOf(x - FIRST, y - FIRST, LAST - 1 - x, LAST - 1 - y) == 0
            var backdropCount = 0
            for (dy in 0 until CELL) for (dx in 0 until CELL) {
                val sourceIndex = ((y - FIRST) * CELL + dy) * COVER_SAMPLE_SIZE + (x - FIRST) * CELL + dx
                val pixel = source[sourceIndex]
                val sample = dy * CELL + dx
                r[sample] = component(pixel, 16)
                g[sample] = component(pixel, 8)
                b[sample] = component(pixel, 0)
                if (outerCell && edgeBackground?.get(sourceIndex) == true) {
                    backdropR[backdropCount] = r[sample]
                    backdropG[backdropCount] = g[sample]
                    backdropB[backdropCount] = b[sample]
                    backdropCount++
                }
            }
            // Where a tile contains enough connected backdrop, use it to keep nearby
            // lettering, highlights or a clipped subject from bleeding into the color field.
            val weight = ((backdropCount - 9) / 17f).coerceIn(0f, 1f)
            fun tone(all: IntArray, backdrop: IntArray): Float {
                val typical = representativeColor(all, all.size)
                if (weight <= 0f) return typical
                return typical + (representativeColor(backdrop, backdropCount) - typical) * weight
            }
            red[index] = tone(r, backdropR)
            green[index] = tone(g, backdropG)
            blue[index] = tone(b, backdropB)
        }
        solve(red)
        solve(green)
        solve(blue)
    }

    fun pixels(): IntArray = IntArray(GRID * GRID) { index ->
        pack(red[index].toInt(), green[index].toInt(), blue[index].toInt())
    }

    private fun representativeColor(samples: IntArray, count: Int): Float {
        samples.sort(0, count)
        val trim = count / 4
        var total = 0
        for (i in trim until count - trim) total += samples[i]
        return total.toFloat() / (count - trim * 2)
    }

    private fun solve(colors: FloatArray) {
        for (y in 0 until GRID) for (x in 0 until GRID) {
            if (x in FIRST until LAST && y in FIRST until LAST) continue
            colors[y * GRID + x] = colors[y.coerceIn(FIRST, LAST - 1) * GRID + x.coerceIn(FIRST, LAST - 1)]
        }
        // Gauss–Seidel relaxation on a small grid with no color flux across the viewport edges.
        repeat(160) {
            for (y in 0 until GRID) for (x in 0 until GRID) {
                if (x in FIRST until LAST && y in FIRST until LAST) continue
                val index = y * GRID + x
                val left = if (x > 0) index - 1 else index
                val right = if (x < GRID - 1) index + 1 else index
                val up = if (y > 0) index - GRID else index
                val down = if (y < GRID - 1) index + GRID else index
                colors[index] += ((colors[left] + colors[right] + colors[up] + colors[down]) * .25f - colors[index]) * 1.65f
            }
        }
    }

    fun color(x: Int, y: Int): Int {
        val fx = ((x + .5f) / CELL - .5f).coerceIn(0f, (GRID - 1).toFloat())
        val fy = ((y + .5f) / CELL - .5f).coerceIn(0f, (GRID - 1).toFloat())
        val ix = fx.toInt()
        val iy = fy.toInt()
        val topLeft = iy * GRID + ix
        val bottomLeft = minOf(iy + 1, GRID - 1) * GRID + ix
        val right = if (ix < GRID - 1) 1 else 0
        fun sample(channel: FloatArray): Int {
            val top = channel[topLeft] + (channel[topLeft + right] - channel[topLeft]) * (fx - ix)
            val bottom = channel[bottomLeft] + (channel[bottomLeft + right] - channel[bottomLeft]) * (fx - ix)
            return (top + (bottom - top) * (fy - iy) + .5f).toInt().coerceIn(0, 255)
        }
        return pack(sample(red), sample(green), sample(blue))
    }

    private fun pack(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun component(color: Int, shift: Int): Int = (color ushr shift) and 255
