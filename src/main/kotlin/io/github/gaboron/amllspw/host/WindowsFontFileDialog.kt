package io.github.gaboron.amllspw.host

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.file.Path
import java.util.UUID

/** Windows Common Item Dialog. Its COM apartment and native allocations stay on the calling worker. */
internal object WindowsFontFileDialog {
    private const val CANCELLED = 0x800704C7.toInt()
    private const val FILE_SYSTEM_PATH = 0x80058000.toInt()
    private const val FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7"
    private const val FILE_OPEN_INTERFACE = "D57C7288-D4AD-4768-BE02-9D969532D960"
    private const val SHELL_ITEM_INTERFACE = "43826D1E-E718-42EE-BC55-A1E261C37BFE"

    fun choose(ownerHandle: Long, initialDirectory: Path?): Path? {
        // A fresh STA worker avoids changing the host's COM apartment or blocking the AWT event loop.
        Native.initialize.invokeWithArguments(MemorySegment.NULL, 0x2 or 0x4).let {
            checkResult(it as Int, "CoInitializeEx")
        }
        try {
            return Arena.ofConfined().use { arena ->
                createDialog(arena).use dialogUse@{ dialog ->
                    configure(dialog, arena, initialDirectory)
                    val result = dialog.call(Method.SHOW, MemorySegment.ofAddress(ownerHandle))
                    if (result == CANCELLED) return@dialogUse null
                    checkResult(result, "Show")
                    selectedPath(dialog, arena)
                }
            }
        } finally {
            Native.uninitialize.invokeWithArguments()
        }
    }

    private fun createDialog(arena: Arena): ComObject {
        val result = arena.allocate(ADDRESS)
        val status = Native.createInstance.invokeWithArguments(
            arena.guid(FILE_OPEN_DIALOG), MemorySegment.NULL, 1,
            arena.guid(FILE_OPEN_INTERFACE), result,
        ) as Int
        checkResult(status, "CoCreateInstance")
        return ComObject(result.get(ADDRESS, 0))
    }

    private fun configure(dialog: ComObject, arena: Arena, initialDirectory: Path?) {
        val options = arena.allocate(JAVA_INT)
        checkResult(dialog.call(Method.GET_OPTIONS, options), "GetOptions")
        // Preserve defaults; require an existing filesystem file and leave the process directory alone.
        val flags = options.get(JAVA_INT, 0) or 0x40 or 0x800 or 0x1000 or 0x8
        checkResult(dialog.call(Method.SET_OPTIONS, flags), "SetOptions")

        val filter = arena.allocate(ADDRESS.byteSize() * 2, ADDRESS.byteAlignment())
        filter.setAtIndex(ADDRESS, 0, arena.wideString("字体文件（TTF / OTF / TTC / OTC）"))
        filter.setAtIndex(ADDRESS, 1, arena.wideString("*.ttf;*.otf;*.ttc;*.otc"))
        checkResult(dialog.call(Method.SET_FILE_TYPES, 1, filter), "SetFileTypes")
        checkResult(dialog.call(Method.SET_FILE_TYPE_INDEX, 1), "SetFileTypeIndex")
        checkResult(dialog.call(Method.SET_TITLE, arena.wideString("选择歌词字体")), "SetTitle")

        if (initialDirectory != null) {
            val folder = arena.allocate(ADDRESS)
            val status = Native.createShellItem.invokeWithArguments(
                arena.wideString(initialDirectory.toAbsolutePath().normalize().toString()),
                MemorySegment.NULL, arena.guid(SHELL_ITEM_INTERFACE), folder,
            ) as Int
            checkResult(status, "SHCreateItemFromParsingName")
            ComObject(folder.get(ADDRESS, 0)).use {
                // Always start in the font directory requested by the user, even after browsing elsewhere.
                checkResult(dialog.call(Method.SET_FOLDER, it.pointer), "SetFolder")
            }
        }
    }

    private fun selectedPath(dialog: ComObject, arena: Arena): Path {
        val result = arena.allocate(ADDRESS)
        checkResult(dialog.call(Method.GET_RESULT, result), "GetResult")
        return ComObject(result.get(ADDRESS, 0)).use { item ->
            val name = arena.allocate(ADDRESS)
            checkResult(item.call(Method.GET_DISPLAY_NAME, FILE_SYSTEM_PATH, name), "GetDisplayName")
            val path = name.get(ADDRESS, 0)
            try {
                // Windows filesystem paths contain at most 32,767 UTF-16 code units plus a terminator.
                Path.of(path.reinterpret(65_536).getString(0, UTF_16LE))
            } finally {
                Native.free.invokeWithArguments(path)
            }
        }
    }

    private fun Arena.wideString(value: String): MemorySegment {
        val bytes = (value + '\u0000').toByteArray(UTF_16LE)
        return allocate(bytes.size.toLong(), 2).copyFrom(MemorySegment.ofArray(bytes))
    }

    private fun Arena.guid(value: String): MemorySegment {
        val uuid = UUID.fromString(value)
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((uuid.mostSignificantBits ushr 32).toInt())
            .putShort((uuid.mostSignificantBits ushr 16).toShort())
            .putShort(uuid.mostSignificantBits.toShort())
            .order(ByteOrder.BIG_ENDIAN).putLong(uuid.leastSignificantBits).array()
        return allocate(16, 4).copyFrom(MemorySegment.ofArray(bytes))
    }

    private fun checkResult(result: Int, operation: String) {
        check(result >= 0) { "$operation 失败（HRESULT 0x${result.toUInt().toString(16).uppercase()}）" }
    }

    /** IUnknown / IFileDialog / IShellItem slots from the Windows SDK, with their native signatures. */
    private enum class Method(val slot: Int, vararg arguments: MemoryLayout) {
        RELEASE(2),
        SHOW(3, ADDRESS),
        SET_FILE_TYPES(4, JAVA_INT, ADDRESS),
        SET_FILE_TYPE_INDEX(5, JAVA_INT),
        SET_OPTIONS(9, JAVA_INT),
        GET_OPTIONS(10, ADDRESS),
        SET_FOLDER(12, ADDRESS),
        SET_TITLE(17, ADDRESS),
        GET_RESULT(20, ADDRESS),
        GET_DISPLAY_NAME(5, JAVA_INT, ADDRESS);

        val descriptor: FunctionDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, *arguments)
    }

    private class ComObject(val pointer: MemorySegment) : AutoCloseable {
        fun call(method: Method, vararg arguments: Any): Int {
            val table = pointer.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
            val function = table.reinterpret((method.slot + 1) * ADDRESS.byteSize())
                .getAtIndex(ADDRESS, method.slot.toLong())
            val handle = Native.linker.downcallHandle(function, method.descriptor)
            return handle.invokeWithArguments(listOf(pointer) + arguments) as Int
        }

        override fun close() {
            call(Method.RELEASE)
        }
    }

    private object Native {
        val linker: Linker = Linker.nativeLinker()
        private val ole32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global())
        private val shell32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global())
        val initialize = linker.downcallHandle(
            ole32.find("CoInitializeEx").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
        )
        val uninitialize = linker.downcallHandle(
            ole32.find("CoUninitialize").orElseThrow(), FunctionDescriptor.ofVoid(),
        )
        val createInstance = linker.downcallHandle(
            ole32.find("CoCreateInstance").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
        )
        val createShellItem = linker.downcallHandle(
            shell32.find("SHCreateItemFromParsingName").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
        val free = linker.downcallHandle(
            ole32.find("CoTaskMemFree").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS),
        )
    }
}
