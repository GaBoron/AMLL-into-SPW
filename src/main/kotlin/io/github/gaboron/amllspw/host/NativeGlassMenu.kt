@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import io.github.gaboron.amllspw.ui.*
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType

/** Read the host's menu DSL, including additions from other plugins, without instantiating its acrylic renderer. */
internal class NativeGlassMenu(body: Method) {
    private val scope = (((body.genericParameterTypes[2] as ParameterizedType)
        .actualTypeArguments[0] as WildcardType).lowerBounds.single() as Class<*>).also { type ->
        require(type.isInterface)
        val parameters = type.declaredMethods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.parameterTypes.map(Class<*>::getName) }.toSet()
        require(parameters == setOf(emptyList<String>(),
            listOf("kotlin.jvm.functions.Function0", "boolean", "kotlin.jvm.functions.Function2"),
            listOf("kotlin.jvm.functions.Function1", "boolean", "kotlin.jvm.functions.Function2"))) {
            "宿主菜单内容接口已改变"
        }
    }
    private val invokeBuilder = Function1::class.java.getMethod("invoke", Any::class.java)
    private val invokeLabel = Function2::class.java.getMethod("invoke", Any::class.java, Any::class.java)
    private val invokeAction = Function0::class.java.getMethod("invoke")
    internal class Entry(
        val label: (@Composable () -> Unit)? = null,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
        val children: (() -> List<Entry>)? = null,
    ) {
        companion object {
            fun destinations(title: String, choices: List<Pair<String, () -> Unit>>): Entry? = when (choices.size) {
                0 -> null
                1 -> Entry(label = { PlayerGlassMenuText(title) }, action = choices.single().second)
                else -> Entry(label = { PlayerGlassMenuText(title) }, children = {
                    choices.map { (name, open) -> Entry(label = { PlayerGlassMenuText(name) }, action = open) }
                })
            }
        }
    }

    private fun read(builder: Any): List<Entry> {
        val entries = mutableListOf<Entry>()
        val receiver = Proxy.newProxyInstance(scope.classLoader, arrayOf(scope)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "toString" -> "GlassMenuScope"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> null
                }
                method.parameterCount == 0 -> {
                    entries += Entry()
                    null
                }
                else -> {
                    val (callback, enabled, label) = requireNotNull(args)
                    val draw: @Composable () -> Unit = { invokeLabel.invoke(label, currentComposer, 0) }
                    entries += if (method.parameterTypes[0] == Function0::class.java)
                        Entry(draw, enabled as Boolean, action = { invokeAction.invoke(callback) })
                    else Entry(draw, enabled as Boolean, children = { read(requireNotNull(callback)) })
                    null
                }
            }
        }
        invokeBuilder.invoke(builder, receiver)
        return entries
    }

    @Composable
    fun Content(builder: Any, navigation: List<Entry>, dismiss: () -> Unit) {
        var path by remember { mutableStateOf(emptyList<Int>()) }
        var entries = read(builder)
        if (navigation.isNotEmpty()) {
            if (entries.isNotEmpty() && entries.last().label != null) entries = entries + Entry()
            entries = entries + navigation
        }
        var depth = 0
        for (index in path) {
            val nested = entries.getOrNull(index)?.children ?: break
            entries = nested()
            depth++
        }
        val activePath = path.take(depth)
        key(activePath) {
            PlayerGlassMenu {
                if (activePath.isNotEmpty()) PlayerGlassMenuItem(onClick = { path = activePath.dropLast(1) }) {
                    PlayerGlassMenuText("‹  返回")
                }
                entries.forEachIndexed { index, entry ->
                    if (entry.label == null) PlayerGlassMenuDivider()
                    else PlayerGlassMenuItem(entry.enabled, {
                        if (entry.children != null) path = activePath + index
                        else {
                            dismiss()
                            entry.action?.invoke()
                        }
                    }) {
                        Row {
                            Box(Modifier.weight(1f)) { entry.label.invoke() }
                            if (entry.children != null) PlayerGlassMenuText("›")
                        }
                    }
                }
            }
        }
    }
}
