package io.github.gaboron.amllspw.lyrics

import kotlin.math.roundToLong

internal object TtmlTime {
    fun parse(value: String, frameRate: Double = 30.0, tickRate: Double = 1.0): Long? = runCatching {
        val s = value.trim()
        val clock = s.split(':')
        val ms = when {
            clock.size == 4 -> clock[0].toDouble() * 3_600_000 + clock[1].toDouble() * 60_000 +
                clock[2].toDouble() * 1_000 + clock[3].toDouble() * 1_000 / frameRate
            clock.size == 3 -> clock[0].toDouble() * 3_600_000 + clock[1].toDouble() * 60_000 + clock[2].toDouble() * 1_000
            clock.size == 2 -> clock[0].toDouble() * 60_000 + clock[1].toDouble() * 1_000
            Regex("[0-9]+(?:\\.[0-9]+)?").matches(s) -> s.toDouble() * 1_000
            else -> {
                val m = Regex("([+\\-]?[0-9]+(?:\\.[0-9]+)?)(ms|h|m|s|f|t)").matchEntire(s) ?: return null
                m.groupValues[1].toDouble() * when (m.groupValues[2]) {
                    "ms" -> 1.0; "h" -> 3_600_000.0; "m" -> 60_000.0
                    "s" -> 1_000.0; "f" -> 1_000.0 / frameRate; else -> 1_000.0 / tickRate
                }
            }
        }
        if (!ms.isFinite() || ms < 0 || ms > 604_800_000) null else ms.roundToLong()
    }.getOrNull()
}
