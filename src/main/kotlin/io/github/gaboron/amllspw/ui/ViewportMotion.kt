package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Rect
import io.github.gaboron.amllspw.timeline.SpringMotion
import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

internal class ViewportMotion {
    data class VoiceMotion(val scale: SpringMotion = SpringMotion(1f), val opacity: SpringMotion = SpringMotion(1f),
        val highlight: ActivationEnvelope = ActivationEnvelope(),
        val visibility: ActivationEnvelope = ActivationEnvelope(1f, .4f, .4f, CubicBezier(.25f, .1f, .25f, 1f)),
        val hover: ActivationEnvelope = ActivationEnvelope(0f, .25f, .25f, CubicBezier(.25f, .1f, .25f, 1f)))
    data class RowMotion(val y: SpringMotion, val voices: List<VoiceMotion>, val scale: SpringMotion = SpringMotion(.97f, 90f, 18f),
        val backgroundSlide: SpringMotion = SpringMotion(-80f), var expanded: Boolean = false)
    data class Hit(val bounds: Rect, val positionMs: Long)
    var hits: List<Hit> = emptyList()
    var height = 0f
    var interludeY: Float? = null; private set
    private var rows = emptyList<RowMotion>()
    private var fallback = LyricMotionFallback(longArrayOf())
    private var focus = -1
    private var focusStamp = 0L
    private var manualUntil = 0L
    private var manualOffset = 0f
    private var currentOffset = 0f
    private var lastNow = System.nanoTime()
    private var stiffness = 170f
    private var seekUntil = 0L
    private val dotsY = SpringMotion(0f, 90f, 15f)
    private var dotsAnchor: Int? = null

    fun scroll(delta: Float, now: Long = lastNow) {
        if (now >= manualUntil) manualOffset = currentOffset
        manualUntil = now + 3_000_000_000
        manualOffset -= delta
    }
    fun follow() { manualUntil = 0 }
    fun isManual(now: Long) = now < manualUntil

    fun update(groups: List<GroupLayout>, activeGroup: Int, timeMs: Long, dt: Float, now: Long, seek: Boolean,
        interludeBefore: Int? = null, slotHeight: Float = 0f, playing: Boolean? = null, hidePassed: Boolean = false, immediate: Boolean = false,
        resetInterlude: Boolean = false, playbackRate: Float = 1f): List<RowMotion> {
        lastNow = now
        if (groups.isEmpty()) return emptyList()
        val first = rows.size != groups.size
        if (first) {
            rows = groups.map { RowMotion(SpringMotion(it.top), it.rows.map { VoiceMotion() }) }
            fallback = LyricMotionFallback(groups.map { group -> group.rows.minOf { it.line.startMs } }.toLongArray())
        }
        val active = groups.map { group -> timeMs >= group.rows.minOf { it.line.startMs } && timeMs < group.rows.maxOf { it.line.endMs } }
        rows.forEachIndexed { index, row -> row.expanded = active[index] || isManual(now) || playing == false }
        val geometry = LyricGeometry(groups, rows.map { it.expanded }, interludeBefore, slotHeight)
        val focusIndex = activeGroup.coerceIn(groups.indices)
        if (seek) seekUntil = now + 650_000_000
        if (focusIndex != focus) {
            val interval = if (focus in groups.indices) abs(groups[focusIndex].rows.first().line.startMs - groups[focus].rows.first().line.startMs) else 800L
            stiffness = 170f + 50f * (1f - ((interval - 100f) / 700f).coerceIn(0f, 1f)).pow(.2f)
            focus = focusIndex; focusStamp = now
        }
        val focal = geometry.interludeTop?.let { it + slotHeight / 2 } ?: (geometry.tops[focusIndex] + geometry.mainHeights[focusIndex] * .35f)
        val aligned = focal - height * .35f
        val offset = if (now < manualUntil) manualOffset.coerceIn(-height * .65f, maxOf(0f, geometry.tops.last())) else aligned
        currentOffset = offset
        val lag = if (first || now < seekUntil) 0f else abs(rows[focusIndex].y.value - (geometry.tops[focusIndex] - offset))
        val direct = immediate || (!isManual(now) && playing != false && interludeBefore == null &&
            fallback.shouldSnap(focusIndex, playbackRate, lag, height))
        rows.forEachIndexed { index, row ->
            val target = geometry.tops[index] - offset
            val stagger = (abs(index - focusIndex) * 45L).coerceAtMost(270) * 1_000_000
            val seeking = now < seekUntil
            row.y.configure(if (seeking) 90f else stiffness, if (seeking) 15f else sqrt(stiffness) * 2.2f)
            if (first || direct) row.y.snap(target)
            else if (seeking || now - focusStamp >= stagger || now < manualUntil) row.y.update(target, dt)
            val scale = if (active[index]) 1f else .97f
            if (first || seek || direct) row.scale.snap(scale) else row.scale.update(scale, dt)
            val slide = if (row.expanded) 0f else -80f
            if (first || seek || direct) row.backgroundSlide.snap(slide) else row.backgroundSlide.update(slide, dt)
            row.voices.forEachIndexed { voiceIndex, voice ->
                val line = groups[index].rows[voiceIndex].line
                val voiceScale = if (line.background && !active[index]) .75f else 1f
                val opacity = if (!line.background) 1f else if (row.expanded) .4f else 0f
                voice.highlight.update(active[index], dt, first || seek || direct)
                voice.visibility.update(!hidePassed || timeMs < groups[index].rows.maxOf { it.line.endMs }, dt, first || seek || direct)
                if (first || seek || direct) { voice.scale.snap(voiceScale); voice.opacity.snap(opacity) }
                else { voice.scale.update(voiceScale, dt); voice.opacity.update(opacity, dt) }
            }
        }
        interludeY = geometry.interludeTop?.let { top ->
            val target = top + slotHeight / 2 - offset
            if (first || seek || direct || resetInterlude || dotsAnchor != interludeBefore) dotsY.snap(target) else dotsY.update(target, dt)
            dotsY.value
        }
        dotsAnchor = interludeBefore
        return rows
    }
}
