package io.github.gaboron.amllspw.ui

import java.nio.ByteBuffer

internal data class OpenTypeFontFace(val index: Int, val weight: Int, val italic: Boolean,
    val weightRange: ClosedFloatingPointRange<Float>?) {
    fun weightFor(requested: Int): Int = (weightRange?.let {
        requested.toFloat().coerceIn(it.start, it.endInclusive).toInt()
    } ?: weight).coerceIn(1, 1000)
}

/** Reads only selection metadata; Skia validates and loads the chosen face's glyph tables. */
internal object OpenTypeFontInfo {
    fun read(bytes: ByteArray): List<OpenTypeFontFace> {
        val reader = Reader(bytes)
        val header = reader.block(0, 12)
        val offsets = if (header.getInt(0) == 0x74746366) { // ttcf
            val count = header.getInt(8)
            require(count in 1..128) { "字体集合包含无效的字体数量" }
            val entries = reader.block(12, count * 4)
            List(count) { reader.offset(entries.getInt(it * 4)) }
        } else listOf(0)
        return offsets.mapIndexed { index, offset -> readFace(reader, index, offset) }
    }

    private fun readFace(reader: Reader, index: Int, offset: Int): OpenTypeFontFace {
        val header = reader.block(offset, 12)
        require(header.getInt(0) in setOf(0x00010000, 0x4f54544f, 0x74727565, 0x74797031)) {
            "字体文件损坏或格式不受支持"
        }
        val count = header.unsignedShort(4)
        require(count in 1..4095) { "字体包含无效的表数量" }
        val directory = reader.block(offset + 12, count * 16)
        val tables = mutableMapOf<Int, ByteBuffer>()
        repeat(count) { entry ->
            val record = entry * 16
            val tag = directory.getInt(record)
            val data = reader.block(reader.offset(directory.getInt(record + 8)), reader.offset(directory.getInt(record + 12)))
            if (tag == OS2 || tag == HEAD || tag == FVAR) {
                require(tables.put(tag, data) == null) { "字体包含重复的样式表" }
            }
        }
        val head = tables[HEAD]
        require(head == null || head.capacity() >= 46) { "字体 head 表不完整" }
        val macStyle = head?.unsignedShort(44) ?: 0
        val os2 = tables[OS2]
        require(os2 == null || os2.capacity() >= 64) { "字体 OS/2 表不完整" }
        val weight = os2?.unsignedShort(4) ?: if (macStyle and 1 != 0) 700 else 400
        require(weight in 1..1000) { "字体的字重信息无效" }
        val selection = os2?.unsignedShort(62) ?: 0
        val italic = macStyle and 2 != 0 || selection and 0x201 != 0
        return OpenTypeFontFace(index, weight, italic, tables[FVAR]?.let(::weightRange))
    }

    private fun weightRange(table: ByteBuffer): ClosedFloatingPointRange<Float>? {
        require(table.capacity() >= 16) { "字体 fvar 表不完整" }
        if (table.unsignedShort(0) != 1) return null
        val start = table.unsignedShort(4)
        val count = table.unsignedShort(8)
        if (count == 0) return null
        val size = table.unsignedShort(10)
        require(size >= 20 && start.toLong() + count.toLong() * size <= table.capacity()) { "字体可变轴数据不完整" }
        repeat(count) { index ->
            val offset = start + index * size
            if (table.getInt(offset) == 0x77676874) { // wght
                val min = table.getInt(offset + 4) / 65536f
                val max = table.getInt(offset + 12) / 65536f
                require(min <= max) { "字体字重轴范围无效" }
                return min..max
            }
        }
        return null
    }

    private fun ByteBuffer.unsignedShort(offset: Int): Int = getShort(offset).toInt() and 0xffff

    private class Reader(private val bytes: ByteArray) {
        fun offset(value: Int): Int {
            val unsigned = value.toLong() and 0xffffffffL
            require(unsigned <= bytes.size) { "字体表偏移或长度超出文件范围" }
            return unsigned.toInt()
        }

        fun block(offset: Int, length: Int): ByteBuffer {
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) { "字体表数据不完整" }
            return ByteBuffer.wrap(bytes, offset, length).slice()
        }
    }

    private const val OS2 = 0x4f532f32
    private const val HEAD = 0x68656164
    private const val FVAR = 0x66766172
}
