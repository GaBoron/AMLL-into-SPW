@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import io.github.gaboron.amllspw.ui.PlayerMediaButton
import java.lang.invoke.MethodType

/** Renders the host's real flyouts, retaining items appended by other plugins. */
internal class NativePlayerPopup(target: NativePlayerTarget.Popup, private val loader: ClassLoader) {
    private fun method(member: NativePlayerTarget.Member) = Class.forName(member.owner.replace('/', '.'), false, loader)
        .getDeclaredMethod(member.name, *MethodType.fromMethodDescriptorString(member.descriptor, loader).parameterArray())
        .also { check(it.trySetAccessible()) }
    private val constructor = Class.forName(target.stateOwner.replace('/', '.'), false, loader)
        .getConstructor(Boolean::class.javaPrimitiveType)
    private val open = method(target.open)
    private val isOpen = method(target.isOpen)
    private val content = method(target.content)

    @Composable
    fun Button(label: String, icon: String, side: Float, iconSize: Float, enabled: Boolean, reducedMotion: Boolean) {
        val state = remember { constructor.newInstance(false) }
        Box {
            PlayerMediaButton(label, icon, side, iconSize, enabled, reducedMotion) { open.invoke(state) }
            if (isOpen.invoke(state) == true) content.invoke(null, state, currentComposer, 0)
        }
    }
}
