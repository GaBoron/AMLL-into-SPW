@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import io.github.gaboron.amllspw.ui.LocalPlayerGlassPopups
import io.github.gaboron.amllspw.ui.PlayerGlassPopupEntry
import io.github.gaboron.amllspw.ui.PlayerMediaButton
import io.github.gaboron.amllspw.ui.PlayerIcon
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy

private data class PlayerPopupContent(val entry: PlayerGlassPopupEntry, val navigation: List<NativeGlassMenu.Entry>)

private val LocalPlayerPopupContent = staticCompositionLocalOf<PlayerPopupContent?> { null }

/** Preserve native actions and plugin menu additions; route their content into the player's glass shell. */
@OptIn(UnstableSpwWorkshopApi::class)
internal class NativePlayerPopup(target: NativePlayerTarget.Popup, private val loader: ClassLoader) {
    private fun method(member: NativePlayerTarget.Member) = Class.forName(member.owner.replace('/', '.'), false, loader)
        .getDeclaredMethod(member.name, *MethodType.fromMethodDescriptorString(member.descriptor, loader).parameterArray())
        .also { check(it.trySetAccessible()) }
    private val constructor = Class.forName(target.stateOwner.replace('/', '.'), false, loader)
        .getConstructor(Boolean::class.javaPrimitiveType)
    private val open = method(target.open)
    private val dismiss = method(target.dismiss)
    private val isOpen = method(target.isOpen)
    private val content = method(target.content)
    private val shell = method(target.shell)
    private val shellCaller = target.shellCaller
    private val body = target.body?.let(::method)
    private val glassMenu = body?.let(::NativeGlassMenu)
    private val invokeQueue = Function3::class.java.getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
    private val scope = target.scopeOwner?.let { owner ->
        val type = Class.forName(owner.replace('/', '.'), false, loader)
        check(type.isInterface && type.declaredMethods.isEmpty())
        Proxy.newProxyInstance(loader, arrayOf(type)) { _, _, _ -> null }
    }
    private var ready by mutableStateOf(false)

    fun install(): HookHandle {
        val handle = WorkshopApi.hookRegistrar.replaceComposable(shell.declaringClass.name, shell.name,
            shellCaller.replace('/', '.'), shell.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }) { call ->
            val popup = LocalPlayerPopupContent.current
            val arguments = call.arguments.toList()
            if (popup == null) shell.invoke(null, *(arguments + currentComposer + listOf(0, 0)).toTypedArray())
            else {
                val draw: @Composable () -> Unit = { Body(arguments, popup.navigation) }
                SideEffect { popup.entry.content = draw }
            }
        }
        ready = true
        return object : HookHandle {
            override fun unhook() {
                ready = false
                handle.unhook()
            }
        }
    }

    @Composable
    private fun Body(arguments: List<Any?>, navigation: List<NativeGlassMenu.Entry>) {
        if (glassMenu != null) {
            glassMenu.Content(requireNotNull(arguments[2]), navigation) { (arguments[0] as Function0<*>).invoke() }
        } else {
            // ComposableLambda implements several FunctionN interfaces; Kotlin's arity cast rejects that shape.
            invokeQueue.invoke(arguments[4], requireNotNull(scope), currentComposer, 0)
        }
    }

    @Composable
    fun Button(label: String, icon: String, side: Float, iconSize: Float, enabled: Boolean, reducedMotion: Boolean,
        navigation: @Composable () -> List<NativeGlassMenu.Entry> = { emptyList() }) {
        val state = remember { constructor.newInstance(false) }
        val sourceIconSize by rememberUpdatedState(iconSize)
        val entry = remember { PlayerGlassPopupEntry(label) { dismiss.invoke(state) }.also {
            it.width = if (body == null) 400.dp else 280.dp
            it.hasSubmenus = glassMenu != null
            it.source = { PlayerIcon(icon, null, Modifier.size(sourceIconSize.dp)) }
        } }
        val host = LocalPlayerGlassPopups.current
        val expanded = ready && isOpen.invoke(state) == true
        Box(Modifier.onGloballyPositioned { entry.anchor = it.boundsInWindow().roundToIntRect() }
            .graphicsLayer { alpha = if (entry.sourceHidden) 0f else 1f }) {
            PlayerMediaButton(label, icon, side, iconSize, enabled && ready && host != null, reducedMotion) { open.invoke(state) }
            if (expanded) {
                val popup = PlayerPopupContent(entry, navigation())
                CompositionLocalProvider(LocalPlayerPopupContent provides popup) {
                    content.invoke(null, state, currentComposer, 0)
                }
            }
            SideEffect {
                entry.expanded = expanded
                if (expanded) host?.show(entry)
            }
        }
    }
}
