@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager

/** The picker owns this file; the host's settings form can save an older amll.json snapshot. */
internal class LyricFontSettings(private val manager: ConfigManager) {
    fun read(): String = synchronized(gate) {
        val config = manager.getConfig(FILE)
        check(config.reload()) { "字体设置读取失败" }
        if (config.get(INITIALIZED, false)) return@synchronized config.get(PATH, "")

        val legacy = manager.getConfig(AmllPreferences.FILE)
        check(legacy.reload()) { "原有字体设置读取失败" }
        val path = legacy.get(PATH, "")
        write(config, path)
        path
    }

    fun save(path: String) = synchronized(gate) {
        val config = manager.getConfig(FILE)
        check(config.reload()) { "字体设置读取失败" }
        write(config, path)
    }

    private fun write(config: ConfigHelper, path: String) {
        val previous = config.get(PATH, "")
        val initialized = config.get(INITIALIZED, false)
        config.set(PATH, path)
        // Persist an explicit default too, so a stale legacy path cannot undo a reset.
        config.set(INITIALIZED, true)
        try {
            check(config.save()) { "字体设置保存失败" }
        } catch (failure: Exception) {
            config.set(PATH, previous)
            config.set(INITIALIZED, initialized)
            throw failure
        }
    }

    private companion object {
        const val FILE = "lyric-font.json"
        const val PATH = "custom_font_path"
        const val INITIALIZED = "initialized"
        // Actions and live preferences use separate managers with separate mutable caches.
        val gate = Any()
    }
}
