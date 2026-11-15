package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.amllspw.spectrum.SpectrumFrame
import org.objectweb.asm.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reads multi-resolution output FFT when the native binding is verified, retaining the host getter as fallback. */
internal class NativePlayerSpectrum(loader: ClassLoader = WorkshopApi::class.java.classLoader) : AutoCloseable {
    private val controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
    private val serviceType = Class.forName("com.xuncorp.voxzen.service.PlaybackService", false, loader)
    private val playerType = Class.forName("com.xuncorp.pisces.PiscesPlayer", false, loader)
    private val service = controller.declaredFields.single { it.type == serviceType && Modifier.isStatic(it.modifiers) }
        .also { check(it.trySetAccessible()) }
    private val player = serviceType.getMethod("getPlayer").also { require(it.returnType == playerType) }
    private val fft = playerType.methods.single { it.parameterCount == 0 && it.returnType == FloatArray::class.java }
    private val native = runCatching { NativeBassSpectrum(loader, playerType, fft) }.getOrNull()
    private var closed = false

    @Synchronized
    fun read(): SpectrumFrame? {
        if (closed) return null
        val current = player.invoke(service.get(null)) ?: return null
        runCatching { native?.read(current) }.getOrNull()?.let { return it }
        val values = (fft.invoke(current) as? FloatArray)?.takeIf { it.size >= 16 } ?: return null
        return SpectrumFrame(values, runCatching { native?.sampleRate(current) }.getOrNull() ?: 0)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        native?.close()
    }
}

/** Resolve the host's own BASS binding and output handle from its verified FFT call site. */
private class NativeBassSpectrum(loader: ClassLoader, playerType: Class<*>, fft: Method) : AutoCloseable {
    private data class Member(val owner: String, val name: String, val descriptor: String) {
        fun method(loader: ClassLoader): Method = Class.forName(owner.replace('/', '.'), false, loader)
            .getMethod(name, *Type.getArgumentTypes(descriptor).map {
                if (it.sort == Type.INT) Int::class.javaPrimitiveType!! else Class.forName(it.className, false, loader)
            }.toTypedArray())
    }

    private val concrete = Class.forName("com.xuncorp.pisces.\u0528", false, loader)
    private val engineType = Class.forName("com.xuncorp.pisces.\u052c", false, loader)
    private val engine = concrete.declaredFields.single { it.type.name == "com.xuncorp.pisces.\u0529" }
        .also { check(it.trySetAccessible()) }
    private val mode = playerType.methods.single { it.parameterCount == 0 && it.returnType.name == "${playerType.name}\$Mode" }
    private val calls = mutableListOf<Member>()
    private val fields = mutableListOf<Member>()

    init {
        require(Type.getMethodDescriptor(fft) == "()[F")
        val path = "/${engineType.name.replace('.', '/')}.class"
        val bytes = requireNotNull(engineType.getResourceAsStream(path)).use { it.readBytes() }
        var readers = 0
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (descriptor != "()[F" || access and Opcodes.ACC_PUBLIC == 0) return null
                readers++
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        calls += Member(owner, name, descriptor)
                    }
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (opcode == Opcodes.GETFIELD) fields += Member(owner, name, descriptor)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        require(readers == 1) { "Native FFT entry is ambiguous" }
    }

    private val data = calls.single { it.descriptor == "(ILcom/sun/jna/Pointer;I)I" }.method(loader)
    private val api = calls.single { it.owner == concrete.name.replace('.', '/') &&
        it.descriptor == "()L${data.declaringClass.name.replace('.', '/')};" }.method(loader)
    private val channel = calls.single { it.owner == concrete.name.replace('.', '/') &&
        it.descriptor.startsWith("()L") && it.name != api.name }.method(loader)
    private val handle = calls.single { it.descriptor == "()I" &&
        Class.forName(it.owner.replace('/', '.'), false, loader).isAssignableFrom(channel.returnType) }.method(loader)
    private val lock = fields.distinct().single { it.owner == engineType.name.replace('.', '/') &&
        it.descriptor == "Ljava/lang/Object;" }.let { engineType.getDeclaredField(it.name) }
        .also { check(it.trySetAccessible()) }
    private val bassType = Class.forName("com.moriafly.bass.BASS", false, loader)
    private val infoType = Class.forName("${bassType.name}\$BASS_CHANNELINFO", false, loader)
    private val info = bassType.methods.single { it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, infoType)) }
    private val bassApi = bassType.fields.single { it.type == bassType && Modifier.isStatic(it.modifiers) }.get(null)
    private val frequency = infoType.getField("freq")
    private val flags = infoType.getField("flags")
    private val memoryType = Class.forName("com.sun.jna.Memory", false, loader)
    private val storage = lazy { memoryType.getConstructor(Long::class.javaPrimitiveType).newInstance(16384L * 4) }
    private val floats = memoryType.getMethod("getFloatArray", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private var lastHandle: Int? = null
    private var rate = 0
    private var decoding = false

    fun sampleRate(player: Any): Int {
        if (!concrete.isInstance(player)) return 0
        val output = channel.invoke(player) ?: return 0
        val current = handle.invoke(output) as Int
        if (current != lastHandle || rate == 0) {
            val format = infoType.getConstructor().newInstance()
            val valid = info.invoke(bassApi, current, format) == true
            rate = if (valid) frequency.getInt(format) else 0
            decoding = !valid || flags.getInt(format) and 0x200000 != 0
            lastHandle = current
        }
        return rate
    }

    fun read(player: Any): SpectrumFrame? {
        // Only the non-decoding DirectX output may be read this way. WASAPI/ASIO
        // consume decoding streams in their callbacks; retain their own FFT getter.
        if (!concrete.isInstance(player) || (mode.invoke(player) as? Enum<*>)?.name != "DirectX") return null
        val delegate = engine.get(player) ?: return null
        return synchronized(lock.get(delegate)) {
            val sampleRate = sampleRate(player)
            if (sampleRate < 8000 || decoding) return@synchronized null
            val output = channel.invoke(player) ?: return@synchronized null
            val current = handle.invoke(output) as Int
            val binding = api.invoke(player)
            // Approximately 170 ms for bass resolution, 43 ms for percussion detail,
            // scaled with sample rate rather than changing the represented frequencies.
            val shift = if (sampleRate > 96000) 2 else if (sampleRate > 48000) 1 else 0
            fun readWindow(flag: Int, points: Int): FloatArray? {
                val result = data.invoke(binding, current, storage.value, (Int.MIN_VALUE or (flag + shift) or 0x40)) as Int
                return if (result == -1) null else floats.invoke(storage.value, 0L, points shl shift) as FloatArray
            }
            val low = readWindow(5, 4096) ?: return@synchronized null
            val high = readWindow(3, 1024) ?: return@synchronized null
            SpectrumFrame(low, sampleRate, high)
        }
    }

    override fun close() {
        // Lazy native storage is owned by this bridge, never by the host engine.
        if (storage.isInitialized()) memoryType.getMethod("close").invoke(storage.value)
    }
}
