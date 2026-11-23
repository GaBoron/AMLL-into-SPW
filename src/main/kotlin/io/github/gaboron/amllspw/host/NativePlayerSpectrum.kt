package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.interop.TrackMixClient
import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import java.io.File
import java.lang.reflect.Modifier

/** Reads the current mix upstream of host volume, or analyzes the original with a decode-only stream. */
internal class NativePlayerSpectrum(loader: ClassLoader = WorkshopApi::class.java.classLoader,
    private val trackMix: TrackMixClient = TrackMixClient()) : AutoCloseable {
    private val controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
    private val serviceType = Class.forName("com.xuncorp.voxzen.service.PlaybackService", false, loader)
    private val playerType = Class.forName("com.xuncorp.pisces.PiscesPlayer", false, loader)
    private val service = controller.declaredFields.single { it.type == serviceType && Modifier.isStatic(it.modifiers) }
        .also { check(it.trySetAccessible()) }
    private val player = serviceType.getMethod("getPlayer").also { require(it.returnType == playerType) }
    private val itemType = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader)
    private val item = playerType.methods.single { it.parameterCount == 0 && it.returnType == itemType }
    // This is the path getter used by the host's BassImpl stream factory.
    private val path = itemType.getMethod("\u052c").also { require(it.returnType == String::class.java) }
    private val decoder = lazy {
        runCatching { NativeSpectrumDecoder(loader) }.onFailure {
            System.err.println("AMLL: 原曲频谱解码不可用：" + (it.cause ?: it))
        }.getOrNull()
    }
    private var closed = false
    private var viewers = 0

    @Synchronized
    fun activate() { if (!closed) viewers++ }

    @Synchronized
    fun read(positionMs: Long): SpectrumFrame? {
        if (closed || viewers == 0) return null
        val current = player.invoke(service.get(null)) ?: return pause().let { null }
        val media = item.invoke(current) ?: return pause().let { null }
        val source = path.invoke(media) as String
        val position = positionMs.coerceAtLeast(0)
        trackMix.spectrum(source, position)?.let {
            pause()
            return it
        }
        return decoder.value?.read(source, position)
    }

    @Synchronized
    fun pause() {
        if (decoder.isInitialized()) decoder.value?.release()
    }

    @Synchronized
    fun idle() {
        viewers = (viewers - 1).coerceAtLeast(0)
        if (viewers == 0) pause()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        viewers = 0
        if (decoder.isInitialized()) decoder.value?.close()
    }
}

/** Reuses the host's loaded BASS codecs without altering its playback channel, DSP or settings. */
private class NativeSpectrumDecoder(loader: ClassLoader) : AutoCloseable {
    private val bassType = Class.forName("com.moriafly.bass.BASS", false, loader)
    private val libraryType = Class.forName("com.moriafly.bass.BASSLibrary", false, loader)
    private val companion = libraryType.getField("Companion").get(null)
    private val library = companion.javaClass.methods.single { it.parameterCount == 0 && it.returnType == libraryType }
        .invoke(companion)
    private val create = bassType.methods.single { it.parameterTypes.contentEquals(arrayOf(
        String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)) }
    private val free = libraryType.getMethod("BASS_ChannelFree", Int::class.javaPrimitiveType)
    private val secondsToBytes = libraryType.getMethod("BASS_ChannelSeconds2Bytes", Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
    private val seek = libraryType.getMethod("BASS_ChannelSetPosition", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val pointerType = Class.forName("com.sun.jna.Pointer", false, loader)
    private val data = libraryType.getMethod("BASS_ChannelGetData", Int::class.javaPrimitiveType, pointerType, Int::class.javaPrimitiveType)
    private val infoType = Class.forName("${bassType.name}\$BASS_CHANNELINFO", false, loader)
    private val info = libraryType.getMethod("BASS_ChannelGetInfo", Int::class.javaPrimitiveType, infoType)
    private val frequency = infoType.getField("freq")
    private val memoryType = Class.forName("com.sun.jna.Memory", false, loader)
    private val storage = lazy { memoryType.getConstructor(Long::class.javaPrimitiveType).newInstance(16384L * 4) }
    private val floats = memoryType.getMethod("getFloatArray", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val unicode = if (System.getProperty("os.name").startsWith("Windows")) Int.MIN_VALUE else 0
    private var channel = 0
    private var sampleRate = 0
    private var source = ""
    private var retryAt = 0L

    fun read(path: String, positionMs: Long): SpectrumFrame? {
        if (path != source) {
            release()
            source = path
        }
        if (channel == 0) {
            if (System.nanoTime() < retryAt) return null
            retryAt = System.nanoTime() + 2_000_000_000L
            if (!File(path).isFile) return null
            channel = create.invoke(null, path, 0L, 0L, unicode or 0x200100) as Int // DECODE | FLOAT
            if (channel == 0) return null
            val format = infoType.getConstructor().newInstance()
            if (info.invoke(library, channel, format) != true) {
                releaseChannel()
                return null
            }
            sampleRate = frequency.getInt(format)
            if (sampleRate < 8000) {
                releaseChannel()
                return null
            }
        }
        val shift = if (sampleRate > 96000) 2 else if (sampleRate > 48000) 1 else 0
        fun window(flag: Int, points: Int): FloatArray? {
            val count = points shl shift
            // Center both windows on the playback clock instead of letting their
            // different lengths place bass and percussion at different moments.
            val start = (positionMs / 1000.0 - count.toDouble() / sampleRate).coerceAtLeast(0.0)
            val offset = secondsToBytes.invoke(library, channel, start) as Long
            if (seek.invoke(library, channel, offset, 0) != true) return null
            val read = data.invoke(library, channel, storage.value, Int.MIN_VALUE or (flag + shift) or 0x40) as Int
            return when {
                read < 0 -> null
                read == 0 -> FloatArray(count)
                else -> floats.invoke(storage.value, 0L, count) as FloatArray
            }
        }
        val low = window(5, 4096) ?: return null
        val high = window(3, 1024) ?: return null
        return SpectrumFrame(low, sampleRate, high)
    }

    private fun releaseChannel() {
        if (channel != 0) {
            val owned = channel
            channel = 0
            free.invoke(library, owned)
        }
    }

    fun release() {
        releaseChannel()
        source = ""
        sampleRate = 0
        retryAt = 0L
    }

    override fun close() {
        release()
        if (storage.isInitialized()) memoryType.getMethod("close").invoke(storage.value)
    }
}
