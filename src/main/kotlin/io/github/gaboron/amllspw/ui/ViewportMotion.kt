package io.github.gaboron.amllspw.ui

import androidx.compose.ui.geometry.Rect
import io.github.gaboron.amllspw.timeline.SpringMotion
import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import kotlin.math.abs
import kotlin.math.sqrt

internal class ViewportMotion {
    data class VoiceMotion(val scale: SpringMotion = SpringMotion(1f), val opacity: SpringMotion = SpringMotion(1f),
        val highlight: ActivationEnvelope = ActivationEnvelope(),
        val blur: LyricBlurMotion = LyricBlurMotion(),
        val visibility: ActivationEnvelope = ActivationEnvelope(1f, .4f, .4f, CubicBezier(.25f, .1f, .25f, 1f)),
        val hover: ActivationEnvelope = ActivationEnvelope(0f, .25f, .25f, CubicBezier(.25f, .1f, .25f, 1f)))
    data class RowMotion(val y: SpringMotion, val voices: List<VoiceMotion>, val scale: SpringMotion = SpringMotion(.97f, 90f, 18f),
        val backgroundSlide: SpringMotion = SpringMotion(-80f), var expanded: Boolean = false,
        var overflowOpacity: Float = 1f, var overflowScale: Float = 1f)
    data class Hit(val bounds: Rect, val positionMs: Long)
    var hits: List<Hit> = emptyList()
    var height = 0f
    var interludeY: Float? = null; private set
    val footer = LyricFooterMotion()
    private var rows = emptyList<RowMotion>()
    private var previousLayout: LyricGeometry? = null
    private var exitCohort = LyricExitCohort(emptyList())
    private var focus = -1
    private var focusStamp = 0L
    private var manualUntil = 0L
    private var manualOffset = 0f
    private var currentOffset = 0f
    private var lastNow = System.nanoTime()
    private var stiffness = 76f
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
        // Signed footer travel remains part of the framing until its compression has recovered.
        val footerPresent = footerHeight != 0f
        if (groups.isEmpty()) {
            interludeY = null
            val offset = if (manual) manualOffset.coerceIn(-height * .3f, maxOf(0f, footerHeight - height * .38f)) else 0f
            currentOffset = offset
            footer.update(height * .52f - offset, footerPresent, true, 0f, height, dt, immediate, 170f, 28f)
            return emptyList()
        }
        val first = rows.size != groups.size
        if (first) {
            rows = groups.map { RowMotion(SpringMotion(it.top), it.rows.map { VoiceMotion() }) }
            exitCohort = LyricExitCohort(groups)
        }
        // A seek changes the targets, not the current animation samples. Only the first
        // layout or explicitly disabled motion bypasses the reveal/fade/scale transitions.
        val snap = first || immediate
        val active = groups.map { group -> timeMs >= group.rows.minOf { it.line.startMs } && timeMs < group.rows.maxOf { it.line.endMs } }
        rows.forEachIndexed { index, row ->
            row.expanded = active[index] || manual || playing == false
            val slide = if (row.expanded) 0f else -80f
            row.backgroundSlide.configure(225f, 30f)
            if (snap) row.backgroundSlide.snap(slide) else row.backgroundSlide.updateBounded(slide, dt)
        }
        val backgroundExpansion = rows.map { 1f + it.backgroundSlide.value / 80f }
        val timelineFocus = activeGroup.coerceIn(groups.indices)
        overflow.prepare(groups.size, seek)
        val previousGeometry = LyricGeometry(groups, backgroundExpansion, interludeBefore, slotHeight, footerHeight, overflow.expansion)
        overflow.update(groups, previousGeometry, timeMs, height, dt,
            !manual && playing != false && interludeBefore == null, immediate,
            focus = timelineFocus.takeIf { interludeBefore == null })
        val geometry = LyricGeometry(groups, backgroundExpansion, interludeBefore, slotHeight, footerHeight, overflow.expansion)
        val focusIndex = (if (overflow.latestOnly) groups.indexOfLast { group -> group.rows.any { it.line.startMs <= timeMs } }
            else timelineFocus).coerceIn(groups.indices)
        // Like upstream, manual browsing freezes the previous footer focus. The bottom is a
        // separate target, never a synthetic timed lyric or a focus inferred from the last row.
        val footerFocused = footerPresent && (if (manual) footer.focused else endOfLyrics)
        val motionFocus = if (footerFocused) groups.size else focusIndex
        if (seek) seekUntil = now + 650_000_000
        if (motionFocus != focus) {
            val interval = if (focus in groups.indices) abs(groups[focusIndex].rows.first().line.startMs - groups[focus].rows.first().line.startMs) else 800L
            // Let ordinary lines unfold; raise the response gradually for short cadences
            // so rapid passages can keep up without making normal transitions rush.
            val urgency = (1f - (interval - 180f) / 620f).coerceIn(0f, 1f)
            stiffness = 76f + 74f * urgency
            focus = motionFocus; focusStamp = now
        }
        val focal = if (footerFocused) geometry.footerTop + footerHeight * .5f else
            geometry.interludeTop?.let { it + slotHeight / 2 } ?: (geometry.tops[focusIndex] + geometry.mainHeights[focusIndex] * .35f)
        val aligned = focal - height * .35f
        val maxOffset = if (footerPresent) maxOf(0f, geometry.totalHeight - height * .35f) else maxOf(0f, geometry.tops.last())
        val followOffset = if (overflow.latestOnly) aligned else lyricScrollAnchor(groups, geometry, timeMs, height, aligned,
            focus = focusIndex.takeIf { !footerFocused && interludeBefore == null })
        val offset = if (manual) manualOffset.coerceIn(-height * .65f, maxOffset) else followOffset
        // Lyric reveal/collapse already animates its height and carries its displacement.
        // Interlude insertion/removal is a new scroll target: keep screen positions and
        // momentum intact so the outgoing lead completes its exit animation.
        val previous = previousLayout
        val layoutShifts = FloatArray(groups.size)
        if (!first && !seek && !immediate && previous != null) {
            fun lyricOffset(layout: LyricGeometry): Float {
                val preferred = layout.lyricTops[focusIndex] + layout.mainHeights[focusIndex] * .35f - height * .35f
                return if (overflow.latestOnly) preferred else
                    lyricScrollAnchor(groups, layout, timeMs, height, preferred, focusIndex, includeInterlude = false)
            }
            val anchorShift = when {
                manual -> 0f
                footerFocused -> geometry.lyricHeight - previous.lyricHeight
                interludeBefore != null -> geometry.lyricTops[focusIndex] - previous.lyricTops[focusIndex]
                // Carry layout changes around the actual framing anchor, which can be a
                // retained upper voice rather than the newest lead during a crowded passage.
                else -> lyricOffset(geometry) - lyricOffset(previous)
            }
            rows.forEachIndexed { index, row ->
                layoutShifts[index] = geometry.lyricTops[index] - previous.lyricTops[index] - anchorShift
                row.y.value += layoutShifts[index]
            }
        }
        previousLayout = geometry
        val seeking = now < seekUntil
        // Retained voices may rebound through the viewport margin; only the actual edge
        // is a stop, rather than the voice's final resting position.
        val minimumY = FloatArray(groups.size) { index ->
            if (active[index] && index < focusIndex && !overflow.latestOnly && !seeking && !manual)
                0f.takeIf { geometry.tops[index] - followOffset >= 0f } ?: Float.NEGATIVE_INFINITY
            else Float.NEGATIVE_INFINITY
        }
        val targets = FloatArray(groups.size) { geometry.tops[it] - offset }
        val free = BooleanArray(groups.size) { seeking || active[it] || manual }
        val retaining = active.indices.any { it < focusIndex && active[it] }
        val exitResponse = exitCohort.stiffnessScale(focusIndex, timeMs, rows.map { it.y }, targets,
            height, !first && !seek && !immediate && !manual && !footerFocused && !retaining && playing != false && interludeBefore == null)
        // Reframing several simultaneous voices is a gentle pan, not a rapid line exit.
        val scrollStiffness = if (retaining) minOf(stiffness, 76f) else stiffness * exitResponse
        val motionStiffness = if (seeking) 90f else scrollStiffness
        // A damping ratio of .72 gives one restrained rebound (about 4%) per line change.
        val motionDamping = if (seeking) 15f else sqrt(motionStiffness) * 1.44f
        rows.forEach { it.y.configure(motionStiffness, motionDamping) }
        // Collision bounds must match the text painted on this frame, including voices
        // restored by a seek and the first visible frame of a backing-voice fade.
        rows.forEachIndexed { index, row ->
            row.overflowOpacity = overflow.rows[index].opacity
            row.overflowScale = overflow.rows[index].scale
            val scale = if (active[index]) 1f else .97f
            if (snap) row.scale.snap(scale)
            else row.scale.update(scale, dt)
            row.voices.forEachIndexed { voiceIndex, voice ->
                val line = groups[index].rows[voiceIndex].line
                val voiceScale = if (line.background && !active[index]) .75f else 1f
                val opacity = if (!line.background) 1f else if (row.expanded) .4f else 0f
                voice.highlight.update(active[index], dt, snap)
                voice.visibility.update(!hidePassed || timeMs < groups[index].rows.maxOf { it.line.endMs }, dt, snap)
                if (snap) { voice.scale.snap(voiceScale); voice.opacity.snap(opacity) }
                else { voice.scale.update(voiceScale, dt); voice.opacity.update(opacity, dt) }
            }
        }
        val extents = groups.mapIndexed { index, group -> lyricRowExtent(group, rows[index].backgroundSlide.value) { voice ->
            val state = rows[index].voices[voice]
            state.opacity.value * state.visibility.value * overflow.rows[index].opacity > .01f
        } }
        val elapsed = (now - focusStamp) / 1e9f
        staggerSpacing.update(rows.map { it.y }, targets, extents, motionFocus, elapsed, dt, height, free,
            !first && !immediate && !manual && !footerFocused, layoutShifts)
        val collapsing = overflow.rows.any { it.collapsing }
        val coupled = staggerSpacing.requiresCoupling || collapsing
        val bulkMotion = scrollSpring.update(geometry.tops, extents, rows.map { it.y }, focusIndex,
            offset, dt, !first && !immediate && !manual && !footerFocused, coupled, motionStiffness, motionDamping, minimumY,
            layoutShifts)
        currentOffset = offset
        rows.forEachIndexed { index, row ->
            val target = staggerSpacing.target(index, targets[index], elapsed, free[index] || bulkMotion != null)
            if (bulkMotion != null) row.y.transfer(bulkMotion.positions[index], bulkMotion.velocities[index])
            else if (first || immediate) row.y.snap(target)
            else row.y.update(target, dt)
        }
        val footerStagger = (abs(groups.size - motionFocus) * 45L).coerceAtMost(270) * 1_000_000
        // Seeks retarget the current footer animation; only disabling motion skips transitions.
        footer.update(geometry.footerTop - offset, footerPresent, footerFocused,
            if (!footerBlurEnabled || footerFocused || manual) 0f else (1 + groups.size - focusIndex).coerceAtMost(5).toFloat(),
            height, dt, immediate, motionStiffness, motionDamping,
            first || seeking || manual || now - focusStamp >= footerStagger)
        // The slot moves with the following row, including manual scroll and follow return.
        // A separate spring can arrive first and paint the dots over lyrics still in transit.
        interludeY = interludeBefore?.takeIf { it in rows.indices }?.let { index ->
            rows[index].y.value - slotHeight / 2
        }
        return rows
    }
}
