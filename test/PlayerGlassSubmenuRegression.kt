package io.github.gaboron.amllspw.verify

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.boundingRect
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
    check(GlassSubmenuMotion.stackExtent(240, 400, .5f) == 320)
    println("Glass submenu: opener and card bounds, staged reveal, parent recession, and viewport growth passed")
}
