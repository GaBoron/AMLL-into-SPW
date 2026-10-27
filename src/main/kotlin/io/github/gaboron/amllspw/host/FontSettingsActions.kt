@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.ui.CustomLyricFontLoader
import io.github.gaboron.amllspw.ui.LyricFontWeight
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Public static callbacks used by the host's declarative settings buttons, including while disabled. */
object FontSettingsActions {
    private val busy = AtomicBoolean()

    @JvmStatic
    fun chooseFont() {
        if (!busy.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            val selected = try {
                chooseFile()
            } catch (failure: Exception) {
                reportFailure(failure)
                busy.set(false)
                return@invokeLater
            }
            if (selected == null) {
                busy.set(false)
                return@invokeLater
            }
            runInBackground {
                val config = WorkshopApi.manager.createConfigManager().getConfig(AmllPreferences.FILE)
                config.reload()
                val weight = LyricFontWeight.fromPreference(config.get("font_weight", LyricFontWeight.DEFAULT.value))
                // Validate with the same renderer before changing the saved selection.
                val name = CustomLyricFontLoader().use { it.fontName(selected, weight) }
                saveSelection(selected.toAbsolutePath().normalize().toString())
                WorkshopApi.ui.toast("已选择歌词字体：$name", WorkshopApi.Ui.ToastType.Success)
            }
        }
    }

    @JvmStatic
    fun restoreDefault() {
        if (!busy.compareAndSet(false, true)) return
        runInBackground {
            saveSelection("")
            WorkshopApi.ui.toast("已恢复内置思源黑体 SC", WorkshopApi.Ui.ToastType.Success)
        }
    }

    private fun runInBackground(action: () -> Unit) {
        thread(name = "amll-font-settings", isDaemon = true) {
            try { action() }
            catch (failure: Exception) { reportFailure(failure) }
            finally { busy.set(false) }
        }
    }

    private fun saveSelection(path: String) {
        val config = WorkshopApi.manager.createConfigManager().getConfig(AmllPreferences.FILE)
        config.reload()
        val previous = config.get(AmllPreferences.FONT_FILE, "")
        config.set(AmllPreferences.FONT_FILE, path)
        try {
            check(config.save()) { "字体设置保存失败" }
        } catch (failure: Exception) {
            config.set(AmllPreferences.FONT_FILE, previous)
            throw failure
        }
        AmllPlugin.current?.preferences?.reload()
    }

    private fun chooseFile(): Path? {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val title = "选择歌词字体（TTF / OTF / TTC / OTC）"
        val dialog = when (owner) {
            is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
            is Frame -> FileDialog(owner, title, FileDialog.LOAD)
            else -> FileDialog(null as Frame?, title, FileDialog.LOAD)
        }
        return try {
            dialog.isMultipleMode = false
            dialog.directory = initialDirectory()?.toString()
            dialog.file = "*.ttf;*.otf;*.ttc;*.otc"
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in CustomLyricFontLoader.EXTENSIONS }
            dialog.isVisible = true
            dialog.files.firstOrNull()?.toPath()
        } finally { dialog.dispose() }
    }

    internal fun initialDirectory(): Path? {
        val home = Path.of(System.getProperty("user.home"))
        val candidates = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOfNotNull(System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Microsoft", "Windows", "Fonts") },
                System.getenv("WINDIR")?.let { Path.of(it, "Fonts") })
        } else listOf(home.resolve(".local/share/fonts"), home.resolve(".fonts"), Path.of("/usr/share/fonts"))
        return (candidates + home).firstOrNull(Files::isDirectory)
    }

    private fun reportFailure(failure: Exception) {
        val message = "歌词字体选择失败：${failure.message ?: failure.javaClass.simpleName}"
        System.err.println("AMLL: $message")
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Error) }
    }
}
