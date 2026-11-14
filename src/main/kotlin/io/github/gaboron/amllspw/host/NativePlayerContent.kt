@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import io.github.gaboron.amllspw.ui.PlayerPage
import io.github.gaboron.amllspw.ui.PlayerMediaButton
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the host theme provider while selecting either its original content or the rebuilt page. */
@OptIn(UnstableSpwWorkshopApi::class)
internal class NativePlayerContent(val target: NativePlayerTarget,
    private val loader: ClassLoader = WorkshopApi::class.java.classLoader) : AutoCloseable {
    private fun method(member: NativePlayerTarget.Member): Method {
        val type = MethodType.fromMethodDescriptorString(member.descriptor, loader)
        return Class.forName(member.owner.replace('/', '.'), false, loader)
            .getDeclaredMethod(member.name, *type.parameterArray()).also { check(it.trySetAccessible()) }
    }
    private val original = method(target.page)
    private val popups = target.popups.mapNotNull { (role, popup) ->
        runCatching { role to NativePlayerPopup(popup, loader) }.onFailure {
            System.err.println("AMLL: 原生播放器 $role 菜单不可用：${it.message}")
        }.getOrNull()
    }.toMap()
    private val chrome = target.chrome.associateWith { method(it.member) }
    @Suppress("UNCHECKED_CAST")
    private val sheetLocal = method(target.sheetLocal).invoke(null) as CompositionLocal<Any>
    private val sheetExpanded = method(target.sheetExpanded)
    private val actions = runCatching { NativePlayerActions(loader) }.getOrNull()
    private val handles = mutableListOf<HookHandle>()
    private var ready by mutableStateOf(false)
    private var rebuildPage by mutableStateOf(false)
    private var sheetState: Any? by mutableStateOf(null)
    private var preferenceCollection: Job? = null

    /** Enable the page and its surrounding layers together, after every hook is installed. */
    fun install(runtime: AmllRuntime, menu: NativeLyricsMenu?) {
        check(handles.isEmpty() && preferenceCollection == null)
        // The material getter reads snapshot state without entering a composition.
        // One owned collector keeps its setting synchronized with the page and native controls.
        preferenceCollection = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.preferences.options.collect { rebuildPage = it.rebuildPlayerPage }
        }
        for ((layer, originalLayer) in chrome) {
            handles += WorkshopApi.hookRegistrar.replaceComposable(layer.member.owner.replace('/', '.'),
                layer.member.name, layer.caller.replace('/', '.'), layer.member.parameters) { call ->
                Chrome(runtime, originalLayer, call.arguments)
            }
        }
        // Workshop rejects ordinary method hooks on composables. Override the native
        // material-mode getter so the host keeps its layout and content composition intact.
        handles += WorkshopApi.hookRegistrar.hookMethod(target.glassEnabled.owner.replace('/', '.'),
            target.glassEnabled.name, emptyList()) {
            before { call -> if (hideGlass(runtime)) call.setResult(false) }
        }
        handles += WorkshopApi.hookRegistrar.replaceComposable(target.page.owner.replace('/', '.'),
            target.page.name, target.caller.replace('/', '.'), target.parameters) { call ->
            Content(runtime, call.arguments, menu)
        }
        ready = true
    }

    override fun close() {
        ready = false
        preferenceCollection?.cancel()
        preferenceCollection = null
        sheetState = null
        val old = handles.asReversed().toTypedArray()
        handles.clear()
        closePluginResources(*old)
    }

    private fun rebuilding(runtime: AmllRuntime): Boolean = ready && runtime.isActive && rebuildPage

    @Composable
    private fun hideNativeChrome(runtime: AmllRuntime): Boolean {
        if (!rebuilding(runtime)) return false
        val state = currentComposer.consume(sheetLocal)
        // Capture the state from this window's actual composition, then let the material
        // getter observe the same snapshot-backed expansion state on subsequent rendering.
        SideEffect { sheetState = state }
        return sheetExpanded.invoke(state) == true
    }

    private fun hideGlass(runtime: AmllRuntime): Boolean = rebuilding(runtime) &&
        sheetState?.let { sheetExpanded.invoke(it) == true } == true

    @Composable
    private fun Chrome(runtime: AmllRuntime, original: Method, arguments: List<Any?>) {
        // Read the native snapshot state in composition so collapsing the sheet restores
        // the cover and mini player even when the rebuilt page itself is no longer composed.
        if (hideNativeChrome(runtime)) return
        val flags = List(original.parameterCount - arguments.size - 1) { 0 }
        original.invoke(null, *(arguments + currentComposer + flags).toTypedArray())
    }

    @Composable
    fun Content(runtime: AmllRuntime, arguments: List<Any?>, menu: NativeLyricsMenu?) {
        if (!rebuilding(runtime)) {
            original.invoke(null, arguments[0], arguments[1], currentComposer, 0)
            return
        }
        val content: @Composable () -> Unit = { RebuiltPage(runtime, menu) }
        original.invoke(null, arguments[0], content, currentComposer, 0)
    }

    @Composable
    private fun RebuiltPage(runtime: AmllRuntime, menu: NativeLyricsMenu?) {
        val options by runtime.preferences.options.collectAsState()
        val presentation by runtime.presentation.collectAsState()
        PlayerPage(presentation, options, Modifier.fillMaxSize(),
            onPlayback = { runtime.clock.sample(presentation.trackKey) },
            onSeek = { position ->
                runCatching { runtime.seekTo(presentation.trackKey, position) }.onFailure {
                    WorkshopApi.ui.toast("播放跳转失败：${it.message}", WorkshopApi.Ui.ToastType.Warning)
                }
            },
            onTransport = { runtime.transport(presentation.trackKey, it) },
            readVolume = {
                if (presentation.externalPlayback) runtime.externalVolume(presentation.trackKey)
                else runCatching { actions?.volume() }.getOrNull()
            },
            onVolume = { runtime.changeVolume(presentation.trackKey, it) { value -> actions?.volume(value) } },
            readMode = { if (presentation.externalPlayback) null else runCatching { actions?.mode() }.getOrNull() },
            onMode = { mode -> runtime.localPlayerAction(presentation.trackKey) { actions?.mode(mode) } },
            pageMenu = { size ->
                val popup = popups["more"]
                if (popup != null) popup.Button("更多", "icon_more", size, size * .72f,
                    presentation.trackKey.isNotBlank(), options.reducedMotion)
                else PlayerMediaButton("更多", "icon_more", size, size * .72f, enabled = false) {}
            },
            queue = { side, iconSize ->
                val popup = popups["queue"]
                if (popup != null) popup.Button("播放列表", "queue", side, iconSize,
                    presentation.trackKey.isNotBlank() && !presentation.externalPlayback, options.reducedMotion)
                else PlayerMediaButton("播放列表", "queue", side, iconSize, enabled = false) {}
            },
            lyrics = { NativeLyricsContent(runtime, Modifier.fillMaxSize(), true, menu) })
    }
}
