package io.github.gaboron.amllspw.verify

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.boundingRect
import io.github.gaboron.amllspw.ui.GlassMenuLayerState
import io.github.gaboron.amllspw.ui.GlassSubmenuMotion

internal fun verifyGlassSubmenuMotion() {
    val opener = Rect(36f, 103f, 236f, 147f)
    val card = Rect(0f, 90f, 244f, 278f)
    check(GlassSubmenuMotion.outline(opener, card, 0f, 1.5f).boundingRect == opener)
    check(GlassSubmenuMotion.outline(opener, card, 1f, 1.5f).boundingRect == card)
    val widening = GlassSubmenuMotion.outline(opener, card, .15f, 1.5f)
    check(widening.width > opener.width && widening.width < card.width && widening.bottom > opener.bottom)
    val unfolded = GlassSubmenuMotion.outline(opener, card, .45f, 1.5f)
    check(unfolded.width == card.width && unfolded.bottom < card.bottom)
    check(GlassSubmenuMotion.items(.04f) == 0f && GlassSubmenuMotion.items(.2f) in 0f..1f &&
        GlassSubmenuMotion.items(.41f) == 1f)
    check(GlassSubmenuMotion.parentScale(1, 0f) == 1f && GlassSubmenuMotion.parentScale(1, 1f) < 1f)
    val parent = GlassSubmenuMotion.parentOutline(Rect(0f, 0f, 280f, 300f), 100f, .95f, 1f)
    check(parent.boundingRect == Rect(14f, 5f, 280f, 290f))
    check(GlassSubmenuMotion.stackExtent(240, 400, .5f) == 320)
    var phase = 1f
    val parentState = GlassMenuLayerState(false, false, { 1f }, "settings") { GlassSubmenuMotion.handoff(phase) }
    check(parentState.openerOpacity("settings") == 0f && parentState.openerOpacity("other") == 1f)
    phase = .001f
    check(GlassSubmenuMotion.handoff(phase) < .001f && parentState.openerOpacity("settings") > .999f)
    phase = 0f
    check(GlassSubmenuMotion.handoff(phase) == 0f && parentState.openerOpacity("settings") == 1f)
    check(GlassSubmenuMotion.parentShadow(1, phase) == 1f)
    println("Glass submenu: opener and card bounds, staged reveal, parent recession, and viewport growth passed")
}
