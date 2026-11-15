@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import io.github.gaboron.amllspw.ui.PlayerPage
import io.github.gaboron.amllspw.ui.PlayerMediaButton
import io.github.gaboron.amllspw.ui.LocalPlayerGlassDark
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.StackWalker
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
    private val sheetBottom = method(target.sheetBottom)
    private val sheetProgress = method(target.sheetProgress)
    private val actions = runCatching { NativePlayerActions(loader) }.getOrNull()
    private val spectrum = runCatching { NativePlayerSpectrum(loader) }.onFailure {
        System.err.println("AMLL: 播放频谱不可用：${it.message}")
    }.getOrNull()
    private val handles = mutableListOf<HookHandle>()
    private val callStack = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private var ready by mutableStateOf(false)
    private var rebuildPage by mutableStateOf(false)
    private var sheetState: Any? by mutableStateOf(null)
    private var preferenceCollection: Job? = null

    /** Enable the page and its surrounding layers together, after every hook is installed. */
    fun install(runtime: AmllRuntime) {
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
        for ((role, popup) in popups) {
            runCatching { handles += popup.install() }.onFailure {
                System.err.println("AMLL: 播放器 $role 菜单内容接入失败：${it.message}")
            }
        }
        // Workshop rejects ordinary method hooks on composables. Override the native
        // material-mode getter so the host keeps its layout and content composition intact.
        handles += WorkshopApi.hookRegistrar.hookMethod(target.glassEnabled.owner.replace('/', '.'),
            target.glassEnabled.name, emptyList()) {
            before { call -> if (hideGlass(runtime) && isGlassCallSite()) call.setResult(false) }
        }
        handles += WorkshopApi.hookRegistrar.replaceComposable(target.page.owner.replace('/', '.'),
            target.page.name, target.caller.replace('/', '.'), target.parameters) { call ->
            Content(runtime, call.arguments)
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
        return sheetVisible(state)
    }

    private fun hideGlass(runtime: AmllRuntime): Boolean = rebuilding(runtime) &&
        sheetState?.let(::sheetVisible) == true

    // The target switches to collapsed at the start of the slide. Keep one set of
    // page layers until the native sheet actually reaches its collapsed position.
    private fun sheetVisible(state: Any): Boolean = sheetExpanded.invoke(state) == true ||
        (sheetProgress.invoke(sheetBottom.invoke(state)) as Number).toFloat() > 0f

    private fun reveal(state: Any): Float = (sheetProgress.invoke(sheetBottom.invoke(state)) as Number)
        .toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: if (sheetExpanded.invoke(state) == true) 1f else 0f

    /** The same mode getter also styles navigation and the collapsed capsule. Leave those calls native. */
    private fun isGlassCallSite(): Boolean = callStack.walk { frames ->
        val stack = frames.iterator()
        fun matches(frame: StackWalker.StackFrame, member: NativePlayerTarget.Member) =
            frame.className == member.owner.replace('/', '.') && frame.methodName == member.name && frame.descriptor == member.descriptor
        while (stack.hasNext()) {
            if (matches(stack.next(), target.glassEnabled)) {
                return@walk stack.hasNext() && matches(stack.next(), target.glassCaller)
            }
        }
        false
    }

    @Composable
    private fun Chrome(runtime: AmllRuntime, original: Method, arguments: List<Any?>) {
        // Read the native snapshot state in composition so collapsing the sheet restores
        // the cover and mini player even when the rebuilt page itself is no longer composed.
        val enabled = rebuilding(runtime)
        val flags = List(original.parameterCount - arguments.size - 1) { 0 }
        if (!enabled) {
            original.invoke(null, *(arguments + currentComposer + flags).toTypedArray())
            return
        }
        val state = currentComposer.consume(sheetLocal)
        SideEffect { sheetState = state }
        val progress = reveal(state)
        // Emit the native nodes directly into their original parent. A wrapper Layout
        // swallows the mini player's BoxScope alignment and moves its capsule to the top.
        // Both native layers leave on the first opening frame and return during closing.
        // Reading the target as well as the position also covers a not-yet-moving sheet.
        if (sheetExpanded.invoke(state) != true && progress < 1f)
            original.invoke(null, *(arguments + currentComposer + flags).toTypedArray())
    }

    @Composable
    fun Content(runtime: AmllRuntime, arguments: List<Any?>) {
        if (!hideNativeChrome(runtime)) {
            original.invoke(null, arguments[0], arguments[1], currentComposer, 0)
            return
        }
        val state = currentComposer.consume(sheetLocal)
        val progress = reveal(state)
        val expanded = sheetExpanded.invoke(state) == true
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalPlayerGlassDark provides (arguments[0] as Boolean)) {
                RebuiltPage(runtime, progress, expanded)
            }
        }
        original.invoke(null, arguments[0], content, currentComposer, 0)
    }

    @Composable
    private fun RebuiltPage(runtime: AmllRuntime, progress: Float, expanded: Boolean) {
        val options by runtime.preferences.options.collectAsState()
        val presentation by runtime.presentation.collectAsState()
        PlayerPage(presentation, options, Modifier.fillMaxSize().graphicsLayer { alpha = progress },
            pageExpanded = expanded,
            readSpectrum = {
                if (!presentation.externalPlayback && runtime.clock.sample(presentation.trackKey).playing == true)
                    runCatching { spectrum?.read() }.getOrNull() else null
            },
            onPlayback = {
                val sample = runtime.clock.sample(presentation.trackKey)
                val buffering = !presentation.externalPlayback && runCatching { actions?.buffering() == true }.getOrDefault(false)
                if (buffering) sample.copy(playing = null) else sample
            },
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
            lyrics = { NativeLyricsContent(runtime, Modifier.fillMaxSize(), true) })
    }
}
