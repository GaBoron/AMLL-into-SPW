package io.github.gaboron.amllspw.ui

import io.github.gaboron.amllspw.lyrics.TranslationSelection

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
    val translationLanguage: String = TranslationSelection.DEFAULT_LANGUAGE,
    val romanization: Boolean = true,
    val automaticJapaneseRuby: Boolean = false,
    val automaticCantoneseRuby: Boolean = false,
    val automaticKoreanRuby: Boolean = false,
    val reducedMotion: Boolean = false,
    val coloredLyrics: Boolean = false,
    val typography: LyricTypography = LyricTypography(),
)
