package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.timeline.ActivationEnvelope
import io.github.gaboron.amllspw.timeline.CubicBezier
import io.github.gaboron.amllspw.timeline.SpringMotion

/** AMLL BottomLine's independent entrance, position spring, focus fade and blur transition. */
internal class LyricFooterMotion {
    private val position = SpringMotion()
    private val brightness = ActivationEnvelope(0f, .4f, .4f, CubicBezier(.25f, .1f, .25f, 1f))
    private val blurTransition = SpringMotion()
    private var hasContent = false
    var focused = false; private set
    val y: Float get() = position.value
    val opacity: Float get() = .2f + .65f * brightness.value
    val blur: Float get() = blurTransition.value.coerceIn(0f, 5f)

    fun update(target: Float, hasContent: Boolean, focused: Boolean, blur: Float, viewportHeight: Float,
        dt: Float, snap: Boolean, stiffness: Float, damping: Float, canMove: Boolean = true) {
        if (!hasContent) {
            this.hasContent = false; this.focused = false
            brightness.update(false, 0f, true); blurTransition.snap(0f)
            return
        }
        if (!this.hasContent) position.snap(viewportHeight * 2)
        this.hasContent = true
        this.focused = focused
        position.configure(stiffness, damping)
        if (snap) position.snap(target) else if (canMove) position.update(target, dt)
        brightness.update(focused, dt, snap)
        if (snap) blurTransition.snap(blur) else blurTransition.follow(blur, dt, .1f)
    }
}
