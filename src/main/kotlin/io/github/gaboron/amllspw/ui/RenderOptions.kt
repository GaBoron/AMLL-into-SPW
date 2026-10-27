package io.github.gaboron.amllspw.ui

internal data class RenderOptions(
    val blur: Boolean = true,
    val scale: Boolean = true,
    val glow: Boolean = true,
    val spring: Boolean = true,
    val background: Boolean = false,
    val hidePassed: Boolean = false,
    val hideFooter: Boolean = false,
    val hidePoetry: Boolean = false,
    val translation: Boolean = true,
    val romanization: Boolean = true,
    val automaticJapaneseRuby: Boolean = false,
    val reducedMotion: Boolean = false,
    val coloredLyrics: Boolean = false,
    val typography: LyricTypography = LyricTypography(),
)
