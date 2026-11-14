package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.flow.StateFlow

/** Verified public host contracts; accessed lazily only while the experimental page is visible. */
internal class NativePlayerActions(loader: ClassLoader = WorkshopApi::class.java.classLoader) {
    private val controllerType = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
    private val configType = Class.forName("com.xuncorp.voxzen.util.AppConfig", false, loader)
    private val modeType = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue\$Mode", false, loader)
    private val controller by lazy { controllerType.getField("INSTANCE").get(null) }
    private val config by lazy { configType.getField("INSTANCE").get(null) }
    private val getVolume = configType.getMethod("getVolume")
    private val updateVolume = configType.getMethod("updateVolume", Float::class.javaPrimitiveType)
    private val changeVolume = controllerType.getMethod("changeVolume", Float::class.javaPrimitiveType)
    private val getQueue = controllerType.getMethod("getPlaybackQueueState")
    private val changeMode = controllerType.getMethod("changePlaybackMode", modeType)
    private val playerState = runCatching { controllerType.getMethod("getPlayerState") }.getOrNull()
    private val modeGetter = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueueState", false, loader).methods
        .single { it.parameterCount == 0 && it.returnType == modeType }
    init {
        require(modeType.enumConstants.map { (it as Enum<*>).name }.toSet() == setOf("CircleEnd", "Circle", "RepeatOne", "Random")) {
            "宿主播放模式已改变"
        }
    }

    fun volume(): Float = (getVolume.invoke(config) as Float).coerceIn(0f, 1f)
    fun buffering(): Boolean = (playerState?.invoke(controller) as? Enum<*>)?.name == "Buffering"
    fun volume(value: Float) {
        val bounded = value.coerceIn(0f, 1f)
        updateVolume.invoke(config, bounded)
        changeVolume.invoke(controller, bounded)
    }

    fun mode(): String {
        val state = (getQueue.invoke(controller) as StateFlow<*>).value ?: return ""
        return (modeGetter.invoke(state) as Enum<*>).name
    }

    fun mode(value: String) {
        val mode = modeType.enumConstants.single { (it as Enum<*>).name == value }
        changeMode.invoke(controller, mode)
    }
}
