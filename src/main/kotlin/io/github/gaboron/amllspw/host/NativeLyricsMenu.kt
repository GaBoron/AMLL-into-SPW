@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.PopupPositionProvider
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.objectweb.asm.*
import java.io.File
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.util.jar.JarFile

internal data class LyricMenuItem(
    val text: String,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** Calls the host's popup and item renderers, preserving its theme, material and keyboard handling. */
internal class NativeLyricsMenu private constructor(
    private val popup: Method,
    private val label: Method,
    private val checkLabel: Method,
    private val item: Method,
    private val divider: Method,
) {
    @Composable
    fun Popup(position: PopupPositionProvider, dismiss: () -> Unit, entries: List<LyricMenuItem>) {
        val content: (Any) -> Unit = { scope ->
            entries.forEachIndexed { index, entry ->
                if (index == 2) divider.invoke(scope)
                val draw: @Composable () -> Unit = {
                    val checked = entry.checked
                    if (checked == null) {
                        val icon: @Composable () -> Unit = {}
                        label.invoke(null, entry.text, Modifier, icon, entry.enabled, currentComposer, 0, 0)
                    } else {
                        checkLabel.invoke(null, checked, entry.text, entry.enabled, currentComposer, 0, 0)
                    }
                }
                val click = {
                    dismiss()
                    entry.onClick()
                }
                item.invoke(scope, click, entry.enabled, draw)
            }
        }
        popup.invoke(null, position, dismiss, content, currentComposer, 0, 0)
    }

    companion object {
        private const val PREFIX = "com.xuncorp.voxzen.ui.component."
        private const val COMPOSER = "Landroidx/compose/runtime/Composer;"
        private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
        private const val FUNCTION2 = "Lkotlin/jvm/functions/Function2;"
        private val signatures = mapOf(
            "BlurPopupMenu" to "(Landroidx/compose/ui/window/PopupPositionProvider;$FUNCTION0$FUNCTION1${COMPOSER}II)V",
            "MenuFlyoutItemCompat" to "(Ljava/lang/String;Landroidx/compose/ui/Modifier;${FUNCTION2}Z${COMPOSER}II)V",
            "MenuFlyoutItemCheckCompat" to "(ZLjava/lang/String;Z${COMPOSER}II)V",
        )

        /** Source markers and exact JVM signatures must agree; obfuscated names are never assumed. */
        fun discover(
            host: File = NativeLyricsTarget.hostArchive(WorkshopApi::class.java),
            loader: ClassLoader = WorkshopApi::class.java.classLoader,
        ): NativeLyricsMenu {
            val found = signatures.keys.associateWith { mutableListOf<Method>() }
            JarFile(host).use { jar ->
                for (entry in jar.entries()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Host menu scan interrupted")
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    if (PREFIX + "BlurPopupMenu (" !in String(bytes, Charsets.ISO_8859_1)) continue
                    val reader = ClassReader(bytes)
                    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(access: Int, name: String, descriptor: String,
                            signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                            if (access and Opcodes.ACC_STATIC == 0) return null
                            val role = signatures.entries.singleOrNull { it.value == descriptor }?.key ?: return null
                            return object : MethodVisitor(Opcodes.ASM9) {
                                var marked = false
                                override fun visitLdcInsn(value: Any?) {
                                    if (value is String && value.startsWith("$PREFIX$role (")) marked = true
                                }
                                override fun visitEnd() {
                                    if (!marked) return
                                    val type = MethodType.fromMethodDescriptorString(descriptor, loader)
                                    val method = Class.forName(reader.className.replace('/', '.'), false, loader)
                                        .getDeclaredMethod(name, *type.parameterArray())
                                    check(method.returnType == Void.TYPE && method.trySetAccessible()) { "宿主菜单方法不可访问" }
                                    found.getValue(role) += method
                                }
                            }
                        }
                    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
            fun unique(role: String) = found.getValue(role).also {
                require(it.size == 1) { "无法唯一确认宿主菜单组件 $role（找到 ${it.size} 个）" }
            }.single()
            val popup = unique("BlurPopupMenu")
            // The native DSL is not composable: it accepts item callbacks and composable labels.
            val callback = popup.genericParameterTypes[2] as ParameterizedType
            val scope = (callback.actualTypeArguments[0] as WildcardType).lowerBounds.single() as Class<*>
            require(scope.isInterface) { "宿主菜单内容接口已改变" }
            fun scopeMethod(descriptor: String): Method = scope.declaredMethods.filter {
                !JvmModifier.isStatic(it.modifiers) &&
                    Type.getMethodDescriptor(it) == descriptor
            }.single().also { check(it.trySetAccessible()) { "宿主菜单内容方法不可访问" } }
            return NativeLyricsMenu(popup, unique("MenuFlyoutItemCompat"), unique("MenuFlyoutItemCheckCompat"),
                scopeMethod("(${FUNCTION0}Z$FUNCTION2)V"), scopeMethod("()V"))
        }
    }
}
