package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.FontVariationAxis
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** A loaded family owns its native typeface for as long as cached text layouts use it. */
internal class CustomLyricFont(val name: String, val family: FontFamily, val weight: FontWeight, val style: FontStyle)

/** File access and collection inspection belong on the preference loader's serial worker. */
internal class CustomLyricFontLoader {
    private data class Stamp(val path: Path, val size: Long, val modified: Long, val fileKey: Any?)
    private data class Face(val index: Int, val name: String, val weight: Int, val italic: Boolean,
        val weightAxis: FontVariationAxis?) {
        fun weightFor(requested: Int): Int = weightAxis?.let {
            requested.toFloat().coerceIn(it.minValue, it.maxValue).toInt()
        } ?: weight
    }
    private var stamp: Stamp? = null
    private var faces = emptyList<Face>()
    private var requestedWeight: LyricFontWeight? = null
    private var loaded: CustomLyricFont? = null

    fun load(file: Path, requested: LyricFontWeight): CustomLyricFont {
        val path = file.toAbsolutePath().normalize()
        require(path.fileName.toString().substringAfterLast('.', "").lowercase() in EXTENSIONS) {
            "请选择 TTF、OTF、TTC 或 OTC 字体文件"
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(attributes.isRegularFile && attributes.size() in 12..MAX_BYTES) { "字体文件为空、过大或不是普通文件（最大 128 MiB）" }
        val nextStamp = Stamp(path, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey())
        if (nextStamp == stamp && requestedWeight == requested) loaded?.let { return it }
        val candidates = if (nextStamp == stamp) faces else inspect(path)
        val target = requested.value.toInt()
        val chosen = candidates.minWith(compareBy<Face> { abs(it.weightFor(target) - target) }
            .thenBy { it.italic }.thenBy { it.index })
        val base = checkNotNull(FontMgr.default.makeFromFile(path.toString(), chosen.index)) { "无法读取所选字体" }
        val actualWeight = chosen.weightFor(target).coerceIn(1, 1000)
        val native = try {
            if (chosen.weightAxis == null) base else {
                base.makeClone(arrayOf(FontVariation("wght", actualWeight.toFloat())), chosen.index)
                    .also { base.close() }
            }
        } catch (failure: Throwable) {
            base.close()
            throw failure
        }
        val result = try {
            // A unique alias prevents two files with the same family name from replacing
            // one another in the shared host renderer. Skia manages the loaded file data.
            val alias = "io.github.gaboron.amllspw.custom-font-${identities.incrementAndGet()}"
            CustomLyricFont(chosen.name, FontFamily(Typeface(native, alias)), FontWeight(actualWeight),
                if (chosen.italic) FontStyle.Italic else FontStyle.Normal)
        } catch (failure: Throwable) {
            native.close()
            throw failure
        }
        stamp = nextStamp
        faces = candidates
        requestedWeight = requested
        loaded = result
        return result
    }

    private fun inspect(path: Path): List<Face> {
        val header = Files.newInputStream(path).use { it.readNBytes(12) }
        require(header.size == 12) { "字体文件不完整" }
        val buffer = ByteBuffer.wrap(header)
        val count = if (buffer.getInt(0) == 0x74746366) buffer.getInt(8) else 1
        require(count in 1..128) { "字体集合包含无效的字体数量" }
        return (0 until count).map { index ->
            checkNotNull(FontMgr.default.makeFromFile(path.toString(), index)) { "字体文件损坏或格式不受支持" }.use { face ->
                require(face.glyphsCount > 0 && face.familyName.isNotBlank()) { "字体文件没有可用字形" }
                Face(index, face.familyName, face.fontStyle.weight, face.fontStyle.slant != FontSlant.UPRIGHT,
                    face.variationAxes?.firstOrNull { it.tag == "wght" })
            }
        }
    }

    companion object {
        val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
        private const val MAX_BYTES = 128L * 1024 * 1024
        private val identities = AtomicLong()
    }
}
