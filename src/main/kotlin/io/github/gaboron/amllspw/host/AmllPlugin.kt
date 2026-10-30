package io.github.gaboron.amllspw.host

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.HookHandle
import io.github.gaboron.amllspw.interop.TtmlBridge
import io.github.gaboron.amllspw.interop.FooterBridge

@OptIn(UnstableSpwWorkshopApi::class)
class AmllPlugin(context: PluginContext) : SpwPlugin(context) {
    private var handle: HookHandle? = null
    private var active: AmllRuntime? = null
    private var bridge: TtmlBridge? = null
    private var footerBridge: FooterBridge? = null
    private var cleanupFailure: Throwable? = null

    @OptIn(UnstableSpwWorkshopApi::class)
    @Synchronized
    override fun start() {
        if (active != null) return
        check(cleanupFailure == null) { "上次停用未能完整清理，请重启 SPW 后再启用 AMLL into SPW" }
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
            footerBridge = FooterBridge(FooterReceiver(created))
            created.start()
        } catch (e: Throwable) {
            release(created, e)
            throw e
        }
    }

    @Synchronized
    override fun stop() {
        val old = active ?: return
        release(old)
    }

    private fun release(runtime: AmllRuntime, failure: Throwable? = null) {
        if (current === runtime) current = null
        active = null
        val resources = arrayOf(runtime, footerBridge, bridge, handle)
        footerBridge = null
        bridge = null
        handle = null
        val previousFailures = failure?.suppressed?.size ?: 0
        try {
            closePluginResources(*resources, cause = failure)
        } catch (error: Throwable) {
            cleanupFailure = error
            throw error
        } finally {
            // An uncertain unhook/unregister must not be followed by a second live session.
            if (failure != null && failure.suppressed.size > previousFailures)
                cleanupFailure = failure.suppressed.last()
        }
    }
    override fun update() = stop()
    override fun delete() = stop()

    companion object { @Volatile internal var current: AmllRuntime? = null; private set }
}

/** Detach ownership first and attempt every release, preserving the original startup failure. */
internal fun closePluginResources(vararg resources: AutoCloseable?, cause: Throwable? = null) {
    var failure = cause
    for (resource in resources) {
        try {
            resource?.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure.addSuppressed(error)
        }
    }
    if (cause == null) failure?.let { throw it }
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
