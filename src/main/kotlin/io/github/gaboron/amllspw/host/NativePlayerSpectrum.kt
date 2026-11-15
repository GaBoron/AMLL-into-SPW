package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Modifier

/** Reads the same native FFT getter used by PlaybackService.updateFftData's default argument. */
internal class NativePlayerSpectrum(loader: ClassLoader = WorkshopApi::class.java.classLoader) {
    private val controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
    private val serviceType = Class.forName("com.xuncorp.voxzen.service.PlaybackService", false, loader)
    private val playerType = Class.forName("com.xuncorp.pisces.PiscesPlayer", false, loader)
    private val service = controller.declaredFields.single { it.type == serviceType && Modifier.isStatic(it.modifiers) }
        .also { check(it.trySetAccessible()) }
    private val player = serviceType.getMethod("getPlayer").also { require(it.returnType == playerType) }
    private val fft = playerType.methods.single { it.parameterCount == 0 && it.returnType == FloatArray::class.java }

    fun read(): FloatArray? {
        val current = player.invoke(service.get(null)) ?: return null
        return (fft.invoke(current) as? FloatArray)?.takeIf { it.size >= 16 }
    }
}
