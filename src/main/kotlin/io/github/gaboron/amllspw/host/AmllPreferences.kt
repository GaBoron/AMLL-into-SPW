@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import io.github.gaboron.amllspw.ui.LyricFontWeight
import io.github.gaboron.amllspw.ui.LyricTypography
import io.github.gaboron.amllspw.ui.RenderOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.function.Consumer

internal class AmllPreferences : AutoCloseable {
    private val manager = WorkshopApi.manager.createConfigManager()
    private val mutable = MutableStateFlow(decode(manager.getConfig(FILE)))
    val options = mutable.asStateFlow()
    private val listener = Consumer<ConfigHelper> { mutable.value = decode(it) }
    init { manager.addConfigChangeListener(FILE, listener) }
    override fun close() { manager.removeConfigChangeListener(listener) }
    private fun decode(c: ConfigHelper) = RenderOptions(
        blur = c.get("blur", true), scale = c.get("scale", true), glow = c.get("glow", true),
        spring = c.get("spring", true), hidePassed = c.get("hide_passed", false),
        romanization = c.get("romanization", true), reducedMotion = c.get("reduced_motion", false),
        coloredLyrics = c.get("colored_lyrics", false),
        typography = LyricTypography.fromPreferences(c.get("font_weight", LyricFontWeight.DEFAULT.value), c.get("font_size", "auto")),
    )
    companion object { private const val FILE = "amll.json" }
}
