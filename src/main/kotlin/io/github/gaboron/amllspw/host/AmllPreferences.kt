@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import io.github.gaboron.amllspw.ui.LyricFontWeight
import io.github.gaboron.amllspw.ui.LyricTypography
import io.github.gaboron.amllspw.ui.RenderOptions
import io.github.gaboron.amllspw.ui.CustomLyricFontLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.function.Consumer

internal class AmllPreferences : AutoCloseable {
    private val manager = WorkshopApi.manager.createConfigManager()
    private val mutable = MutableStateFlow(decode(manager.getConfig(FILE)))
    val options = mutable.asStateFlow()
    private val fontLoader = CustomLyricFontLoader()
    private val fontWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "amll-font-loader").apply { isDaemon = true } }
    private val gate = Any()
    private var generation = 0L
    private var closed = false
    private var lastFontFailure: String? = null
    @Volatile var onAnnotationChange: (() -> Unit)? = null
    private val listener = Consumer<ConfigHelper>(::refresh)
    init {
        manager.addConfigChangeListener(FILE, listener)
        refresh(manager.getConfig(FILE))
    }

    fun reload() {
        val config = manager.getConfig(FILE)
        config.reload()
        refresh(config)
    }

    private fun refresh(config: ConfigHelper) {
        val next = decode(config)
        val path = config.get(FONT_FILE, "")
        val previous: RenderOptions
        synchronized(gate) {
            if (closed) return
            previous = mutable.value
            val request = ++generation
            mutable.value = next.copy(typography = next.typography.copy(
                customFont = previous.typography.customFont.takeIf { path.isNotBlank() }))
            if (path.isBlank()) lastFontFailure = null
            if (path.isNotBlank()) fontWorker.execute {
                val loaded = runCatching { fontLoader.load(Path.of(path), next.typography.weight) }
                var warning: String? = null
                synchronized(gate) {
                    if (!closed && generation == request) {
                        mutable.value = next.copy(typography = next.typography.copy(customFont = loaded.getOrNull()))
                        if (loaded.isSuccess) lastFontFailure = null
                        else if (lastFontFailure != path) {
                            lastFontFailure = path
                            warning = "自定义歌词字体无法加载，已使用内置字体：${loaded.exceptionOrNull()?.message}"
                        }
                    }
                }
                warning?.let { message ->
                    System.err.println("AMLL: $message")
                    runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
                }
            }
        }
        if (previous.automaticJapaneseRuby != next.automaticJapaneseRuby || previous.romanization != next.romanization)
            onAnnotationChange?.invoke()
    }

    override fun close() {
        synchronized(gate) { closed = true; generation++ }
        onAnnotationChange = null
        manager.removeConfigChangeListener(listener)
        fontWorker.shutdownNow()
    }
    private fun decode(c: ConfigHelper) = RenderOptions(
        blur = c.get("blur", true), scale = c.get("scale", true), glow = c.get("glow", true),
        spring = c.get("spring", true), hidePassed = c.get("hide_passed", false),
        hideFooter = c.get("hide_footer", false),
        hidePoetry = c.get("hide_poetry", false),
        translation = c.get("translation", true),
        romanization = c.get("romanization", true), reducedMotion = c.get("reduced_motion", false),
        automaticJapaneseRuby = c.get("automatic_japanese_ruby", false),
        coloredLyrics = c.get("colored_lyrics", false),
        typography = LyricTypography.fromPreferences(c.get("font_weight", LyricFontWeight.DEFAULT.value), c.get("font_size", "auto")),
    )
    companion object {
        internal const val FILE = "amll.json"
        internal const val FONT_FILE = "custom_font_path"
    }
}
