package io.github.gaboron.amllspw.timeline

/** Owns re-anchoring and dismissal; the media clock already advances once per display frame. */
internal class InterludeAnimation {
    private enum class Phase { IDLE, PERFORMING, FADING }
    val snapshot = InterludeSnapshot()
    private var phase = Phase.IDLE
    private var current: LyricTimeline.Interlude? = null
    private var placement: LyricTimeline.Interlude? = null
    private var sequence: InterludeSequence? = null
    private var initialized = false
    private var anchor = 0L
    private var fadeElapsed = 0.0
    private var fadeInitial = 0f
    // Keep the slot while dismissing so its dots continue to move with the lyrics.
    val layoutInterval: LyricTimeline.Interlude? get() = placement.takeUnless { phase == Phase.IDLE }

    fun update(interval: LyricTimeline.Interlude?, positionMs: Long, dtMs: Double, playing: Boolean?, reset: Boolean) {
        val forceReset = reset || !initialized
        initialized = true
        if (interval != current || forceReset) {
            current = interval
            if (interval != null) {
                placement = interval
                anchor = if (forceReset) positionMs else interval.start
                val hold = if (forceReset || interval.beforeGroup > 0) 500.0 else 0.0
                sequence = InterludeSequence((interval.end - anchor).coerceAtLeast(0).toDouble(), hold)
                phase = if (sequence!!.supported) Phase.PERFORMING else Phase.IDLE
                if (phase == Phase.IDLE) snapshot.hide()
            } else if (phase == Phase.PERFORMING) {
                if (playing == false || snapshot.opacity <= 0) { phase = Phase.IDLE; snapshot.hide() }
                else { phase = Phase.FADING; fadeElapsed = 0.0; fadeInitial = snapshot.opacity }
            }
        }
        when (phase) {
            Phase.IDLE -> Unit
            Phase.PERFORMING -> {
                sequence!!.sample((positionMs - anchor).coerceAtLeast(0).toDouble(), snapshot)
                if (!snapshot.active) phase = Phase.IDLE
            }
            Phase.FADING -> {
                fadeElapsed += dtMs.coerceAtLeast(0.0)
                if (playing == false || fadeElapsed >= 150) { phase = Phase.IDLE; snapshot.hide() }
                else snapshot.opacity = InterludeSequence.dismissalOpacity(fadeInitial, fadeElapsed)
            }
        }
    }
}
