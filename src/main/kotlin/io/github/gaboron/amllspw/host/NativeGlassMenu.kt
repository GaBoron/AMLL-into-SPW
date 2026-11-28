@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.amllspw.host

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
            fun destinations(title: String, drawLabel: @Composable (String) -> Unit,
                choices: List<Pair<String, () -> Unit>>): Entry? = when (choices.size) {
                0 -> null
                1 -> Entry(label = { drawLabel(title) }, action = choices.single().second)
                else -> Entry(label = { drawLabel(title) }, children = {
                    choices.map { (name, open) -> Entry(label = { drawLabel(name) }, action = open) }
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

    private data class Step(val index: Int, val trigger: GlassMenuTrigger?)
    private data class Page(val entries: List<Entry>, val owner: Entry? = null, val path: List<Step> = emptyList())

    @Composable
    fun Content(builder: Any, navigation: List<Entry>, dismiss: () -> Unit) {
        var path by remember { mutableStateOf(emptyList<Step>()) }
        val native = read(builder)
        val root = if (navigation.isEmpty() || native.isEmpty()) navigation + native
            else navigation + Entry() + native.dropWhile { it.label == null }
        val pages = mutableListOf(GlassMenuPage(emptyList<Int>(), Page(root)))
        var entries = root
        for ((depth, step) in path.withIndex()) {
            val owner = entries.getOrNull(step.index) ?: break
            entries = owner.children?.invoke() ?: break
            val levelPath = path.take(depth + 1)
            pages += GlassMenuPage(levelPath.map(Step::index), Page(entries, owner, levelPath), step.trigger)
        }
        PlayerGlassSubmenus(pages) { (items, owner, levelPath), level, state ->
            val scroll = rememberScrollState()
            PlayerGlassMenu(state.active, scroll) {
                if (owner != null) {
                    MenuRow(owner, state.acceptsBack, state.placeHeader { scroll.value },
                        rotation = { 90f * state.unfoldProgress().coerceIn(0f, 1f) },
                        onClickLabel = if (state.active) "返回上一级" else "展开子菜单", onClick = {
                            path = if (state.active) levelPath.dropLast(1) else levelPath
                        })
                }
                Column(Modifier.graphicsLayer { alpha = GlassSubmenuMotion.items(state.unfoldProgress()) }) {
                    if (owner != null) PlayerGlassMenuDivider()
                    items.forEachIndexed { index, item ->
                        if (item.label == null) PlayerGlassMenuDivider()
                        else {
                            val row = remember { RowCoordinates() }
                            MenuRow(item, state.active, Modifier.onGloballyPositioned { row.value = it }
                                .graphicsLayer { alpha = if (state.covers(index)) 0f else 1f }, onClick = {
                                if (item.children != null) path = levelPath + Step(index, level.trigger(row.value, index))
                                else {
                                    dismiss()
                                    item.action?.invoke()
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    private class RowCoordinates(var value: LayoutCoordinates? = null)

    @Composable
    private fun MenuRow(entry: Entry, active: Boolean, modifier: Modifier = Modifier,
        rotation: () -> Float = { 0f }, onClickLabel: String? = null, onClick: () -> Unit) {
        PlayerGlassMenuItem(active && entry.enabled, onClick, modifier, onClickLabel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { entry.label?.invoke() }
                if (entry.children != null) Box(Modifier.graphicsLayer { rotationZ = rotation() }) {
                    PlayerGlassMenuText("›", entry.enabled)
                }
            }
        }
    }
}
