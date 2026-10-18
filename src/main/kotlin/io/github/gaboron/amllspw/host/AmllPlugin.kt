package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.HookHandle
import io.github.gaboron.amllspw.interop.TtmlBridge

@OptIn(UnstableSpwWorkshopApi::class)
class AmllPlugin(context: PluginContext) : SpwPlugin(context) {
    private var handle: HookHandle? = null
    private var active: AmllRuntime? = null
    private var bridge: TtmlBridge? = null

    @OptIn(UnstableSpwWorkshopApi::class)
    override fun start() {
        if (active != null) return
        require(WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM) &&
            WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) { "请授权修改宿主界面和读取曲库后启用 AMLL into SPW" }
        val target = NativeLyricsTarget.discover()
        val created = AmllRuntime()
        try {
            handle = WorkshopApi.hookRegistrar.replaceComposable(target.owner, target.method, target.caller, target.parameters) { call ->
                val arguments = NativeLyricsArguments.read(call.arguments)
                // Argument 7 is "activated", not "showSubText".
                NativeLyricsContent(created, arguments.modifier, true)
            }
            active = created
            current = created
            bridge = TtmlBridge(TtmlReceiver(created))
            created.start()
        } catch (e: Throwable) {
            bridge?.close(); bridge = null
            current = null; active = null
            handle?.close(); handle = null; created.close(); throw e
        }
    }

    override fun stop() {
        val old = active ?: return
        if (current === old) current = null
        bridge?.close(); bridge = null
        handle?.close(); handle = null
        active = null; old.close()
    }
    override fun update() = stop()
    override fun delete() = stop()

    companion object { @Volatile internal var current: AmllRuntime? = null; private set }
}

/** PF4J constructs extensions independently; this bridge is active only for the plugin lifetime. */
class AmllPlaybackExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        AmllPlugin.current?.trackChanged(mediaItem)
        return null
    }
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) { AmllPlugin.current?.lyricsChanged() }
    override fun onPositionUpdated(position: Long) { AmllPlugin.current?.positionChanged(position) }
    override fun onIsPlayingChanged(isPlaying: Boolean) { AmllPlugin.current?.playingChanged(isPlaying) }
    override fun onSeekTo(position: Long) { AmllPlugin.current?.positionChanged(position, seek = true) }
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (state in setOf(PlaybackExtensionPoint.State.Idle, PlaybackExtensionPoint.State.Ended, PlaybackExtensionPoint.State.Buffering))
            AmllPlugin.current?.playingChanged(false)
    }
}
