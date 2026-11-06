@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.delay
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.charset.StandardCharsets.UTF_16LE

/** Windows receives explicit Unicode text, independent of the host JVM's default/ANSI code page. */
internal object LyricClipboard {
    suspend fun copy(text: String, clipboard: Clipboard) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            clipboard.setClipEntry(ClipEntry(StringSelection(text)))
            return
        }
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val owner = generateSequence(active) { it.owner }.firstNotNullOfOrNull {
            when (it) {
                is ComposeWindow -> it.windowHandle
                is ComposeDialog -> it.windowHandle
                else -> null
            }
        } ?: error("未找到播放器窗口，请返回播放界面后重试")
        check(owner != 0L) { "播放器窗口尚未准备好，请稍后重试" }
        val bytes = unicodeText(text)
        repeat(4) { attempt ->
            if (writeWindows(owner, bytes)) return
            if (attempt < 3) delay(25)
        }
        error("剪贴板正被其他程序占用，请重试")
    }

    /** CF_UNICODETEXT uses NUL-terminated UTF-16LE and Windows line endings. */
    internal fun unicodeText(text: String): ByteArray {
        val windowsText = text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\r\n")
        return (windowsText + '\u0000').toByteArray(UTF_16LE)
    }

    private fun writeWindows(owner: Long, bytes: ByteArray): Boolean {
        // SetClipboardData takes ownership of movable global memory only after success.
        val memory = Native.allocate.invokeWithArguments(0x42, bytes.size.toLong()) as MemorySegment
        check(memory.address() != 0L) { "无法分配剪贴板文本内存" }
        var transferred = false
        try {
            val pointer = Native.lock.invokeWithArguments(memory) as MemorySegment
            check(pointer.address() != 0L) { "无法访问剪贴板文本内存" }
            try {
                pointer.reinterpret(bytes.size.toLong()).copyFrom(MemorySegment.ofArray(bytes))
            } finally {
                Native.unlock.invokeWithArguments(memory)
            }
            if (Native.open.invokeWithArguments(MemorySegment.ofAddress(owner)) as Int == 0) return false
            try {
                check(Native.empty.invokeWithArguments() as Int != 0) { "无法准备剪贴板" }
                val result = Native.set.invokeWithArguments(13, memory) as MemorySegment // CF_UNICODETEXT
                check(result.address() != 0L) { "无法写入 Unicode 剪贴板文本" }
                transferred = true
                return true
            } finally {
                Native.close.invokeWithArguments()
            }
        } finally {
            if (!transferred) Native.free.invokeWithArguments(memory)
        }
    }

    private object Native {
        private val linker = Linker.nativeLinker()
        private val user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global())
        private val kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        val open = linker.downcallHandle(user32.find("OpenClipboard").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val empty = linker.downcallHandle(user32.find("EmptyClipboard").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
        val set = linker.downcallHandle(user32.find("SetClipboardData").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS))
        val close = linker.downcallHandle(user32.find("CloseClipboard").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
        val allocate = linker.downcallHandle(kernel32.find("GlobalAlloc").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG))
        val lock = linker.downcallHandle(kernel32.find("GlobalLock").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
        val unlock = linker.downcallHandle(kernel32.find("GlobalUnlock").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val free = linker.downcallHandle(kernel32.find("GlobalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    }
}
