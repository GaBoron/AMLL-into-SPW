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
        val backgroundSlide: SpringMotion = SpringMotion(-80f), var expanded: Boolean = false, var overflowOpacity: Float = 1f)
    data class Hit(val bounds: Rect, val positionMs: Long)
    var hits: List<Hit> = emptyList()
    var height = 0f
    var interludeY: Float? = null; private set
    val footer = LyricFooterMotion()
    private var rows = emptyList<RowMotion>()
    private var exitCohort = LyricExitCohort(emptyList())
    private var focus = -1
    private var focusStamp = 0L
    private var manualUntil = 0L
    private var manualOffset = 0f
    private var currentOffset = 0f
    private var lastNow = System.nanoTime()
    private var stiffness = 170f
    private var seekUntil = 0L
    private val seekFollow = LyricSeekFollow()
    private val overflow = LyricOverflowCollapse()
    private val scrollSpring = LyricScrollSpring()
    private val staggerSpacing = LyricStaggerSpacing()

    fun scroll(delta: Float, now: Long = lastNow) {
        seekFollow.cancel()
        if (now >= manualUntil) manualOffset = currentOffset
        manualUntil = now + 3_000_000_000
        manualOffset -= delta
    }
    fun follow() { manualUntil = 0; seekFollow.cancel() }
    fun followAfterSeek(positionMs: Long) {
        manualOffset = currentOffset
        manualUntil = 0
        seekFollow.request(positionMs, lastNow)
    }
    fun isManual(now: Long) = now < manualUntil || seekFollow.isWaiting(now)

    fun update(groups: List<GroupLayout>, activeGroup: Int, timeMs: Long, dt: Float, now: Long, seek: Boolean,
        interludeBefore: Int? = null, slotHeight: Float = 0f, playing: Boolean? = null, hidePassed: Boolean = false, immediate: Boolean = false,
        playbackRate: Float = 1f,
        footerHeight: Float = 0f, endOfLyrics: Boolean = false, footerBlurEnabled: Boolean = true): List<RowMotion> {
        lastNow = now
        seekFollow.update(timeMs, now)
        val manual = isManual(now)
        if (groups.isEmpty()) {
            interludeY = null
            val offset = if (manual) manualOffset.coerceIn(-height * .3f, maxOf(0f, footerHeight - height * .38f)) else 0f
            currentOffset = offset
            footer.update(height * .52f - offset, footerHeight > 0, true, 0f, height, dt, immediate, 170f, 28f)
            return emptyList()
        }
        val first = rows.size != groups.size
        if (first) {
            rows = groups.map { RowMotion(SpringMotion(it.top), it.rows.map { VoiceMotion() }) }
            exitCohort = LyricExitCohort(groups)
        }
        val active = groups.map { group -> timeMs >= group.rows.minOf { it.line.startMs } && timeMs < group.rows.maxOf { it.line.endMs } }
        rows.forEachIndexed { index, row ->
            row.expanded = active[index] || manual || playing == false
            val slide = if (row.expanded) 0f else -80f
            row.backgroundSlide.configure(225f, 30f)
            if (first || seek || immediate) row.backgroundSlide.snap(slide) else row.backgroundSlide.updateBounded(slide, dt)
        }
        val backgroundExpansion = rows.map { 1f + it.backgroundSlide.value / 80f }
        val timelineFocus = activeGroup.coerceIn(groups.indices)
        overflow.prepare(groups.size, seek)
        val previousGeometry = LyricGeometry(groups, backgroundExpansion, interludeBefore, slotHeight, footerHeight, overflow.expansion)
        overflow.update(groups, previousGeometry, timeMs, height, dt,
            !manual && playing != false && interludeBefore == null, immediate,
            focus = timelineFocus.takeIf { interludeBefore == null })
        val geometry = LyricGeometry(groups, backgroundExpansion, interludeBefore, slotHeight, footerHeight, overflow.expansion)
        val followGeometry = LyricGeometry(groups, backgroundExpansion, interludeBefore, slotHeight, footerHeight, overflow.plannedExpansion)
        val focusIndex = (if (overflow.latestOnly) groups.indexOfLast { group -> group.rows.any { it.line.startMs <= timeMs } }
            else timelineFocus).coerceIn(groups.indices)
        // Like upstream, manual browsing freezes the previous footer focus. The bottom is a
        // separate target, never a synthetic timed lyric or a focus inferred from the last row.
        val footerFocused = footerHeight > 0 && (if (manual) footer.focused else endOfLyrics)
        val motionFocus = if (footerFocused) groups.size else focusIndex
        if (seek) seekUntil = now + 650_000_000
        if (motionFocus != focus) {
            val interval = if (focus in groups.indices) abs(groups[focusIndex].rows.first().line.startMs - groups[focus].rows.first().line.startMs) else 800L
            stiffness = 170f + 50f * (1f - ((interval - 100f) / 700f).coerceIn(0f, 1f)).pow(.2f)
            focus = motionFocus; focusStamp = now
        }
        val focal = if (footerFocused) followGeometry.footerTop + footerHeight * .5f else
            followGeometry.interludeTop?.let { it + slotHeight / 2 } ?: (followGeometry.tops[focusIndex] + followGeometry.mainHeights[focusIndex] * .35f)
        val aligned = focal - height * .35f
        val maxOffset = if (footerHeight > 0) maxOf(0f, geometry.totalHeight - height * .35f) else maxOf(0f, geometry.tops.last())
        val followOffset = if (overflow.latestOnly) aligned else lyricScrollAnchor(groups, followGeometry, timeMs, height, aligned,
            focus = focusIndex.takeIf { !footerFocused && interludeBefore == null })
        // Keep the focused row at its planned screen position while completed slots close.
        // Their changing prefix height is a layout shift, not a second scroll animation.
        val layoutShift = if (footerFocused) geometry.footerTop - followGeometry.footerTop
            else geometry.tops[focusIndex] - followGeometry.tops[focusIndex]
        val offset = if (manual) manualOffset.coerceIn(-height * .65f, maxOffset) else followOffset + layoutShift
        val seeking = now < seekUntil
        val minimumY = FloatArray(groups.size) { index ->
            if (active[index] && index < focusIndex && !overflow.latestOnly && !seeking && !manual)
                (followGeometry.tops[index] - followOffset).takeIf { it >= 0f } ?: Float.NEGATIVE_INFINITY
            else Float.NEGATIVE_INFINITY
        }
        val targets = FloatArray(groups.size) { geometry.tops[it] - offset }
        val free = BooleanArray(groups.size) { seeking || active[it] || manual }
        val scrollStiffness = stiffness * exitCohort.stiffnessScale(focusIndex, timeMs, rows.map { it.y }, targets,
            height, !first && !seek && !immediate && !manual && !footerFocused && playing != false && interludeBefore == null)
        rows.forEach { it.y.configure(if (seeking) 90f else scrollStiffness, if (seeking) 15f else sqrt(scrollStiffness) * 2.2f) }
        val extents = groups.mapIndexed { index, group -> lyricRowExtent(group, rows[index].backgroundSlide.value) { voice ->
            val state = rows[index].voices[voice]
            state.opacity.value * state.visibility.value * overflow.rows[index].opacity > .01f
        } }
        val elapsed = (now - focusStamp) / 1e9f
        staggerSpacing.update(rows.map { it.y }, targets, extents, motionFocus, elapsed, dt, height, free,
            !first && !immediate && !manual && !footerFocused)
        val collapsing = overflow.rows.any { it.collapsing }
        val coupled = staggerSpacing.requiresCoupling || collapsing
        val bulkPositions = scrollSpring.update(geometry.tops, geometry.heights, rows.map { it.y }, focusIndex,
            offset, dt, !first && !immediate && !manual && !footerFocused, coupled, scrollStiffness, minimumY, collapsing)
        currentOffset = offset
        rows.forEachIndexed { index, row ->
            row.overflowOpacity = overflow.rows[index].opacity
            val target = staggerSpacing.target(index, targets[index], elapsed, free[index] || bulkPositions != null)
            if (bulkPositions != null) row.y.transfer(bulkPositions[index],
                if (dt > 0f) (bulkPositions[index] - row.y.value) / dt else row.y.velocity)
            else if (first || immediate) row.y.snap(target)
            else row.y.update(target, dt)
            val scale = if (active[index]) 1f else .97f
            if (first || seek || immediate) row.scale.snap(scale)
            else row.scale.update(scale, dt)
            row.voices.forEachIndexed { voiceIndex, voice ->
                val line = groups[index].rows[voiceIndex].line
                val voiceScale = if (line.background && !active[index]) .75f else 1f
                val opacity = if (!line.background) 1f else if (row.expanded) .4f else 0f
                voice.highlight.update(active[index], dt, first || seek || immediate)
                voice.visibility.update(!hidePassed || timeMs < groups[index].rows.maxOf { it.line.endMs }, dt, first || seek || immediate)
                if (first || seek || immediate) { voice.scale.snap(voiceScale); voice.opacity.snap(opacity) }
                else { voice.scale.update(voiceScale, dt); voice.opacity.update(opacity, dt) }
            }
        }
        val footerStagger = (abs(groups.size - motionFocus) * 45L).coerceAtMost(270) * 1_000_000
        // Seeks retarget the current footer animation; only disabling motion skips transitions.
        footer.update(geometry.footerTop - offset, footerHeight > 0, footerFocused,
            if (!footerBlurEnabled || footerFocused || manual) 0f else (1 + groups.size - focusIndex).coerceAtMost(5).toFloat(),
            height, dt, immediate, if (seeking) 90f else stiffness, if (seeking) 15f else sqrt(stiffness) * 2.2f,
            first || seeking || manual || now - focusStamp >= footerStagger)
        // The slot moves with the following row, including manual scroll and follow return.
        // A separate spring can arrive first and paint the dots over lyrics still in transit.
        interludeY = interludeBefore?.takeIf { it in rows.indices }?.let { index ->
            rows[index].y.value - slotHeight / 2
        }
        return rows
    }
}
