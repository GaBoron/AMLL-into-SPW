package io.github.gaboron.amllspw.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.interop.PlayerExtensionClient
import io.github.gaboron.amllspw.interop.PlayerExtensionClient.Control
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.roundToInt

private const val TransitionMs = 180

@Composable
internal fun PlayerExtensionButtons(client: PlayerExtensionClient, side: Float,
    context: PlayerExtensionClient.Context, reducedMotion: Boolean) {
    val host = LocalPlayerGlassPopups.current ?: return
    var panels by remember(client) { mutableStateOf(client.panels()) }
    LaunchedEffect(client) {
        while (true) {
            panels = client.panels()
            delay(400)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (panel in panels) key(panel.endpoint) {
            PlayerExtensionButton(client, panel, host, side, context, reducedMotion)
        }
    }
}

@Composable
private fun PlayerExtensionButton(client: PlayerExtensionClient, panel: PlayerExtensionClient.Panel,
    host: PlayerGlassPopups, side: Float, context: PlayerExtensionClient.Context, reducedMotion: Boolean) {
    val entry = remember(panel, host) {
        lateinit var popup: PlayerGlassPopupEntry
        popup = PlayerGlassPopupEntry(panel.label) { popup.expanded = false }
        popup
    }
    val currentContext by rememberUpdatedState(context)
    val motionReduced by rememberUpdatedState(reducedMotion)
    val iconSize = side * .60f
    SideEffect {
        entry.width = panel.width.dp
        entry.keepOnTrackChange = panel.keepOnTrackChange
        entry.source = { ExtensionIcon(panel.svg, null, Modifier.size(iconSize.dp)) }
    }
    DisposableEffect(entry) { onDispose { entry.dismiss() } }

    Box(Modifier.onGloballyPositioned { entry.anchor = it.boundsInWindow().roundToIntRect() }
        .graphicsLayer { alpha = if (entry.sourceHidden) 0f else 1f }) {
        PlayerMediaButtonContent(panel.label, side, iconSize, true, reducedMotion, icon = { modifier, alpha ->
            ExtensionIcon(panel.svg, panel.label, modifier, alpha)
        }) {
            val session = UUID.randomUUID().toString()
            entry.content = { ExtensionPanel(client, panel, session, currentContext, motionReduced, entry.dismiss) }
            entry.expanded = true
            host.show(entry)
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun ExtensionIcon(svg: String, label: String?, modifier: Modifier, alpha: Float = 1f) {
    val density = LocalDensity.current
    val painter = remember(svg, density) {
        svg.takeIf { it.isNotBlank() && it.length <= 65_536 }?.let { source ->
            runCatching {
                source.replace("currentColor", "#ffffff").byteInputStream().use { loadSvgPainter(it, density) }
            }.getOrNull()
        }
    }
    if (painter == null) PlayerIcon("icon_more", label, modifier, alpha)
    else Image(painter, label, modifier, colorFilter = ColorFilter.tint(LocalPlayerColor.current), alpha = alpha)
}

@Composable
private fun ExtensionPanel(client: PlayerExtensionClient, panel: PlayerExtensionClient.Panel, session: String,
    context: PlayerExtensionClient.Context, reducedMotion: Boolean, dismiss: () -> Unit) {
    var controls by remember(client, panel, session, context) {
        mutableStateOf(client.render(panel, session, context))
    }
    val refresh = {
        val updated = client.render(panel, session, context)
        if (updated == null) dismiss() else controls = updated
    }
    LaunchedEffect(client, panel, session, context) {
        while (true) {
            refresh()
            delay(160)
        }
    }
    DisposableEffect(client, panel, session) {
        onDispose { client.close(panel, session) }
    }

    val color = if (LocalPlayerGlassDark.current) Color.White else Color(0xff202020)
    CompositionLocalProvider(LocalPlayerColor provides color) {
        PlayerGlassMenu {
            key(context) {
                ExtensionControls(controls.orEmpty(), color, reducedMotion) { action, value ->
                    client.action(panel, session, context, action, value)
                    refresh()
                }
            }
        }
    }
}

@Composable
private fun ExtensionControls(controls: List<Control>, color: Color, reducedMotion: Boolean,
    onAction: (String, Int) -> Unit) {
    for (control in controls) key(control.id) {
        ExtensionControl(control, color, reducedMotion, Modifier, onAction)
    }
}

@Composable
private fun ExtensionControl(control: Control, color: Color, reducedMotion: Boolean, modifier: Modifier,
    onAction: (String, Int) -> Unit) {
    val label = control.text("text")
    val action = control.text("action")
    val enabled = control.flag("enabled", true) && action.isNotBlank()
    val sizing = control.number("width").takeIf { it > 0 }?.let { modifier.width(it.dp) } ?: modifier
    val padding = control.number("padding").coerceIn(0, 32).dp
    val gap = control.number("gap", 5).coerceIn(0, 24).dp
    when (control.type) {
        "column" -> Column(sizing.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(gap)) {
            ExtensionControls(control.children, color, reducedMotion, onAction)
        }
        "row" -> Row(sizing.fillMaxWidth().padding(horizontal = padding), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (child in control.children) key(child.id) {
                val weight = if (child.flag("fill")) Modifier.weight(1f) else Modifier
                ExtensionControl(child, color, reducedMotion, weight, onAction)
            }
        }
        "wrap" -> FlowRow(sizing.padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            ExtensionControls(control.children, color, reducedMotion, onAction)
        }
        "text" -> ExtensionText(label, color, control.number("size", 13).coerceIn(10, 24),
            sizing.padding(horizontal = padding), control.flag("emphasis"), control.flag("muted"),
            control.number("lines", 1).coerceIn(1, 5))
        "divider" -> PlayerGlassMenuDivider()
        "button" -> {
            val selected = control.flag("selected")
            val click = { onAction(action, 0) }
            if (control.text("style") == "chip") {
                ExtensionChip(label, selected, enabled, color, reducedMotion, click)
            } else {
                PlayerGlassMenuItem(enabled = enabled, onClick = click) { PlayerGlassMenuText(label, enabled) }
            }
        }
        "slider" -> ExtensionSlider(control, sizing, color, enabled, reducedMotion, onAction)
        "progress" -> {
            val value = ((control.properties["value"] as? Number)?.toFloat() ?: 0f)
                .takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            val displayed by animateFloatAsState(value, if (reducedMotion) snap() else tween(TransitionMs))
            Box(sizing.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = .13f))) {
                Box(Modifier.fillMaxWidth(displayed).height(3.dp).background(color.copy(alpha = .75f)))
            }
        }
    }
}

@Composable
private fun ExtensionChip(label: String, active: Boolean, enabled: Boolean, color: Color, reducedMotion: Boolean,
    onClick: () -> Unit) {
    val tint by animateFloatAsState(if (active) 1f else 0f, if (reducedMotion) snap() else tween(TransitionMs))
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .06f + .12f * tint))
        .selectable(active, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 5.dp)) {
        ExtensionText(label, color.copy(alpha = if (enabled) .72f + .28f * tint else .42f), 12)
    }
}

@Composable
private fun ExtensionText(text: String, color: Color, size: Int, modifier: Modifier = Modifier, emphasis: Boolean = false,
    muted: Boolean = false, lines: Int = 1) {
    BasicText(text, modifier, style = TextStyle(color = if (muted) color.copy(alpha = .68f) else color, fontSize = size.sp,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ExtensionSlider(control: Control, modifier: Modifier, color: Color, enabled: Boolean, reducedMotion: Boolean,
    onAction: (String, Int) -> Unit) {
    val min = control.number("min")
    val max = control.number("max", 100)
    if (max <= min) return
    val value = control.number("value").coerceIn(min, max)
    val span = max.toDouble() - min
    val level = ((value.toDouble() - min) / span).toFloat()
    val unit = control.text("unit")
    var preview by remember { mutableStateOf<Float?>(null) }
    val animated by animateFloatAsState(level, if (reducedMotion || preview != null) snap() else tween(TransitionMs))
    val displayed = preview ?: if (reducedMotion) level else animated
    val format = { fraction: Float -> (min + span * fraction).roundToInt().toString() + unit }
    val send = { fraction: Float ->
        val next = (min + span * fraction).roundToInt().coerceIn(min, max)
        if (next != value) onAction(control.text("action"), next)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        PlayerSlider(level, enabled, control.text("text"), reducedMotion, Modifier.weight(1f), formatValue = format,
            displayedValue = displayed, onPreview = { draft ->
                preview = draft
                if (draft != null && control.flag("continuous")) send(draft)
            }, onCommit = send)
        val width = control.number("valueWidth")
        if (width > 0) ExtensionText(format(displayed), color, 12, Modifier.width(width.dp), muted = true)
    }
}
