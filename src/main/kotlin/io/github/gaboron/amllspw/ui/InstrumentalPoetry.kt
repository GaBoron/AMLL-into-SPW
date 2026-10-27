package io.github.gaboron.amllspw.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gaboron.amllspw.poetry.Poem

/** A stationary reading surface: its only playback-independent action is choosing another poem. */
@Composable
@OptIn(ExperimentalTextApi::class)
internal fun InstrumentalPoetrySurface(poem: Poem, options: RenderOptions, lyricColor: Int,
    modifier: Modifier, footer: LyricFooterContent = LyricFooterContent(), onNextPoem: () -> Unit) {
    val nextPoem by rememberUpdatedState(onNextPoem)
    val color = if (options.coloredLyrics) Color(lyricColor) else Color.White
    val footerSections = if (options.hideFooter) emptyList() else footer.sections
    val readingFamily = remember { FontFamily("SimSun") }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val headingSize = options.typography.fontSize(maxWidth.value)
        // Keep the reading scale independent of large lyric-font preferences.
        val readingSize = (maxWidth.value / 21f).coerceIn(18f, 26f)
        val margin = maxWidth * .075f
        Column(Modifier.fillMaxSize().padding(horizontal = margin).padding(top = maxHeight * .08f, bottom = maxHeight * .08f)) {
            // No pointer, focus, seek callback or click semantics on the heading.
            BasicText("纯音乐", style = options.typography.textStyle(headingSize).copy(color = color.copy(alpha = .94f)))
            Spacer(Modifier.height(8.dp))
            BasicText("INSTRUMENTAL", style = options.typography.textStyle((headingSize * .42f).coerceIn(12f, 18f)).copy(
                color = color.copy(alpha = .5f), letterSpacing = 1.6.sp))
            Spacer(Modifier.height(24.dp))
            Crossfade(poem, Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(if (options.reducedMotion) 0 else 280), label = "poem") { current ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val scroll = rememberScrollState()
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(scroll)
                        .pointerInput(current) { detectTapGestures(onDoubleTap = { nextPoem() }) }
                        .onKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                                nextPoem(); true
                            } else false
                        }.focusable().semantics(mergeDescendants = true) {
                            contentDescription = "诗词"
                            customActions = listOf(CustomAccessibilityAction("换一首诗词") { nextPoem(); true })
                        }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BalancedPoetryText(current.title, style = TextStyle(color = color.copy(alpha = .88f), fontFamily = readingFamily,
                            fontWeight = FontWeight.Medium, fontSize = (readingSize * 1.05f).sp,
                            lineHeight = (readingSize * 1.5f).sp, textAlign = TextAlign.Center))
                        Spacer(Modifier.height(10.dp))
                        BasicText(current.author, style = options.typography.textStyle(13f).copy(
                            color = color.copy(alpha = .52f), textAlign = TextAlign.Center))
                        Spacer(Modifier.height(28.dp))
                        BalancedPoetryText(current.text, style = TextStyle(color = color.copy(alpha = .78f), fontFamily = readingFamily,
                            fontSize = readingSize.sp, lineHeight = (readingSize * 1.85f).sp,
                            letterSpacing = .6.sp, textAlign = TextAlign.Center))
                        Spacer(Modifier.height(24.dp))
                        BasicText(current.collection, style = options.typography.textStyle(11f).copy(
                            color = color.copy(alpha = .38f), textAlign = TextAlign.Center))
                        val footerSize = lyricFooterFontSize(headingSize)
                        for ((index, section) in footerSections.withIndex()) {
                            val gap = if (index == 0) 16.dp else with(density) {
                                (footerSize * lyricFooterGapEm(index, section.attribution)).sp.toDp()
                            }
                            Spacer(Modifier.height(gap))
                            val sectionSize = if (section.attribution) lyricFooterAttributionFontSize(footerSize) else footerSize
                            BasicText(section.text, modifier = Modifier.fillMaxWidth(),
                                style = options.typography.textStyle(sectionSize).copy(
                                    color = color.copy(alpha = .5f * if (section.attribution) FOOTER_ATTRIBUTION_OPACITY else 1f),
                                    lineHeight = (sectionSize * lyricFooterLineHeight(section.attribution)).sp))
                        }
                    }
                }
            }
        }
    }
}

/** Measure the actual reading column, including window scaling, before choosing lyric-style breaks. */
@Composable
private fun BalancedPoetryText(text: String, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val wrapped = remember(text, width, style, measurer) { wrapBalancedText(text, width, style, measurer) }
        BasicText(wrapped, modifier = Modifier.fillMaxWidth(), style = style)
    }
}
