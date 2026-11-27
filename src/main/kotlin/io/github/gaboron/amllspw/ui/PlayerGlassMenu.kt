// SPDX-License-Identifier: AGPL-3.0-only
// Menu metrics adapted from Prism UI popup.dart, revision 63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185.
package io.github.gaboron.amllspw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlayerGlassMenu(active: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = active), content = content)
}

@Composable
internal fun PlayerGlassMenuItem(enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier,
    onClickLabel: String? = null, content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focus = LocalFocusManager.current
    val hoverColor = if (LocalPlayerGlassDark.current) Color.White else Color.Black
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (hovered && enabled) hoverColor.copy(alpha = .13f) else Color.Transparent)
        .hoverable(interactions, enabled)
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
                else -> false
            }
        }.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
        .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
internal fun PlayerGlassMenuText(text: String, enabled: Boolean = true, checked: Boolean? = null) {
    val color = if (LocalPlayerGlassDark.current) Color.White else Color(0xff202020)
    Row(Modifier.semantics { if (checked != null) toggleableState =
        if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
        verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) BasicText(if (checked) "✓" else "", Modifier.width(28.dp),
            style = TextStyle(color = color, fontSize = 18.sp))
        BasicText(text, style = TextStyle(color = color.copy(alpha = if (enabled) 1f else .4f), fontSize = 16.sp))
    }
}

@Composable
internal fun PlayerGlassMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp)
        .background(if (LocalPlayerGlassDark.current) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .1f)))
}
