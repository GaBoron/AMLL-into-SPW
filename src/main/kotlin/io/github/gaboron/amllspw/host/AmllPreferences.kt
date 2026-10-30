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
    private val fontSettings = LyricFontSettings(manager)
    private val mutable = MutableStateFlow(decode(manager.getConfig(FILE)))
    val options = mutable.asStateFlow()
    private val fontLoader = CustomLyricFontLoader()
    private val fontWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "amll-font-loader").apply { isDaemon = true } }
    private val gate = Any()
    private var generation = 0L
    private var closed = false
    private var lastFontFailure: String? = null
    private var requestedFontPath = ""
    @Volatile var onAnnotationChange: (() -> Unit)? = null
    private val listener = Consumer<ConfigHelper> { if (it.reload()) refresh(it) }
    init {
        try {
            manager.addConfigChangeListener(FILE, listener)
            refresh(manager.getConfig(FILE))
        } catch (error: Throwable) {
            synchronized(gate) {
                closed = true
                generation++
            }
            closePluginResources(AutoCloseable { manager.removeConfigChangeListener(listener) },
                AutoCloseable(::closeFontLoader), cause = error)
            throw error
        }
    }

    fun reload() {
        val config = manager.getConfig(FILE)
        if (config.reload()) refresh(config)
    }

    private fun refresh(config: ConfigHelper) {
        val next = decode(config)
        val previous: RenderOptions
        synchronized(gate) {
            if (closed) return
            previous = mutable.value
            val path = try {
                fontSettings.read()
            } catch (failure: Exception) {
                System.err.println("AMLL: 字体设置读取失败，保留当前字体：${failure.message}")
                requestedFontPath
            }
            val hadFont = requestedFontPath.isNotBlank()
            requestedFontPath = path
            val request = ++generation
            mutable.value = next.copy(typography = next.typography.copy(
                customFont = previous.typography.customFont.takeIf { path.isNotBlank() }))
            if (path.isBlank()) {
                lastFontFailure = null
                if (hadFont) fontWorker.execute {
                    if (synchronized(gate) { !closed && generation == request }) fontLoader.clear()
                }
            }
            if (path.isNotBlank()) fontWorker.execute {
                if (synchronized(gate) { closed || generation != request }) return@execute
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
        if (previous.automaticJapaneseRuby != next.automaticJapaneseRuby ||
            previous.automaticCantoneseRuby != next.automaticCantoneseRuby || previous.romanization != next.romanization)
            onAnnotationChange?.invoke()
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            generation++
        }
        onAnnotationChange = null
        closePluginResources(AutoCloseable { manager.removeConfigChangeListener(listener) }, AutoCloseable(::closeFontLoader))
    }

    private fun closeFontLoader() {
        try {
            fontWorker.execute { fontLoader.close() }
        } finally {
            fontWorker.shutdown()
        }
    }
    private fun decode(c: ConfigHelper) = RenderOptions(
        blur = c.get("blur", true), scale = c.get("scale", true), glow = c.get("glow", true),
        spring = c.get("spring", true), hidePassed = c.get("hide_passed", false),
        hideFooter = c.get("hide_footer", false),
        hidePoetry = c.get("hide_poetry", false),
        translation = c.get("translation", true),
        romanization = c.get("romanization", true), reducedMotion = c.get("reduced_motion", false),
        automaticJapaneseRuby = c.get("automatic_japanese_ruby", false),
        automaticCantoneseRuby = c.get("automatic_cantonese_ruby", false),
        coloredLyrics = c.get("colored_lyrics", false),
        typography = LyricTypography.fromPreferences(c.get("font_weight", LyricFontWeight.DEFAULT.value), c.get("font_size", "auto")),
    )
    companion object {
        internal const val FILE = "amll.json"
    }
}
