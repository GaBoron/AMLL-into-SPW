package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** A loaded family owns its native typeface for as long as cached text layouts use it. */
internal class CustomLyricFont(val name: String, val family: FontFamily, val weight: FontWeight, val style: FontStyle)

/** File access and collection inspection belong on the preference loader's serial worker. */
internal class CustomLyricFontLoader : AutoCloseable {
    private data class Stamp(val path: Path, val size: Long, val modified: Long, val fileKey: Any?)
    private var stamp: Stamp? = null
    private var data: Data? = null
    private var faces = emptyList<OpenTypeFontFace>()
    private var requestedWeight: LyricFontWeight? = null
    private var loaded: CustomLyricFont? = null

    fun fontName(file: Path, requested: LyricFontWeight): String {
        prepare(file)
        return nativeFace(choose(requested)).use { it.familyName }
    }

    fun load(file: Path, requested: LyricFontWeight): CustomLyricFont {
        prepare(file)
        if (requestedWeight == requested) loaded?.let { return it }
        val target = requested.value.toInt()
        val chosen = choose(requested)
        val base = nativeFace(chosen)
        val actualWeight = chosen.weightFor(target).coerceIn(1, 1000)
        val native = try {
            if (chosen.weightRange == null) base else {
                base.makeClone(arrayOf(FontVariation("wght", actualWeight.toFloat())), chosen.index)
                    .also { base.close() }
            }
        } catch (failure: Throwable) {
            base.close()
            throw failure
        }
        val result = try {
            // A unique alias prevents two files with the same family name from replacing
            // one another in the shared host renderer. The typeface retains its shared data.
            val alias = "io.github.gaboron.amllspw.custom-font-${identities.incrementAndGet()}"
            CustomLyricFont(native.familyName, FontFamily(Typeface(native, alias)), FontWeight(actualWeight),
                if (chosen.italic) FontStyle.Italic else FontStyle.Normal)
        } catch (failure: Throwable) {
            native.close()
            throw failure
        }
        requestedWeight = requested
        loaded = result
        return result
    }

    private fun prepare(file: Path) {
        val path = file.toAbsolutePath().normalize()
        require(path.fileName.toString().substringAfterLast('.', "").lowercase() in EXTENSIONS) {
            "请选择 TTF、OTF、TTC 或 OTC 字体文件"
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(attributes.isRegularFile && attributes.size() in 12..MAX_BYTES) { "字体文件为空、过大或不是普通文件（最大 128 MiB）" }
        val nextStamp = Stamp(path, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey())
        if (nextStamp == stamp) return
        // Read once so every TTC/OTC face shares one buffer. Font use must not leave the
        // source file mapped or locked, and large collections must not reread it per face.
        val bytes = ByteArray(attributes.size().toInt())
        DataInputStream(Files.newInputStream(path)).use { input ->
            input.readFully(bytes)
            require(input.read() == -1) { "字体文件在读取时发生变化，请重新选择" }
        }
        // Inspect OS/2 and fvar records without constructing every native typeface in a
        // large collection. Only the selected face needs the platform font loader.
        val candidates = OpenTypeFontInfo.read(bytes)
        val opened = Data.makeFromBytes(bytes)
        clear()
        stamp = nextStamp
        data = opened
        faces = candidates
    }

    private fun choose(requested: LyricFontWeight): OpenTypeFontFace = faces.minWith(
        compareBy<OpenTypeFontFace> { abs(it.weightFor(requested.value.toInt()) - requested.value.toInt()) }
            .thenBy { it.italic }.thenBy { it.index })

    private fun nativeFace(face: OpenTypeFontFace): org.jetbrains.skia.Typeface {
        val native = checkNotNull(FontMgr.default.makeFromData(checkNotNull(data), face.index)) { "字体文件损坏或格式不受支持" }
        try {
            require(native.glyphsCount > 0 && native.familyName.isNotBlank()) { "字体文件没有可用字形" }
        } catch (failure: Throwable) {
            native.close()
            throw failure
        }
        return native
    }

    fun clear() {
        data?.close()
        data = null
        stamp = null
        faces = emptyList()
        requestedWeight = null
        loaded = null
    }

    override fun close() = clear()

    companion object {
        val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
        private const val MAX_BYTES = 128L * 1024 * 1024
        private val identities = AtomicLong()
    }
}
