package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal enum class LyricFontWeight(val value: String, weight: Int = value.toInt()) {
    // Preserve the existing preference key; the variable font's real lower limit is 250.
    EXTRA_LIGHT("200", 250),
    LIGHT("300"),
    REGULAR("400"),
    MEDIUM("500"),
    BOLD("700"),
    HEAVY("900");

    val fontWeight = FontWeight(weight)
    val family: FontFamily by lazy { SourceHanSansFont.family(fontWeight) }

    companion object {
        val DEFAULT = MEDIUM
        fun fromPreference(value: String) = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/** Typography shared by main lyrics, background vocals, translations, romanization and ruby. */
internal data class LyricTypography(
    val weight: LyricFontWeight = LyricFontWeight.DEFAULT,
    val fontSizeSp: Int? = null,
    val customFont: CustomLyricFont? = null,
) {
    fun fontSize(viewportWidthDp: Float): Float =
        fontSizeSp?.toFloat() ?: (viewportWidthDp / 13.5f).coerceIn(24f, 42f)

    fun textStyle(sizeSp: Float) = TextStyle(
        fontFamily = customFont?.family ?: weight.family,
        fontWeight = customFont?.weight ?: weight.fontWeight,
        fontStyle = customFont?.style ?: FontStyle.Normal,
        fontSynthesis = FontSynthesis.None,
        fontSize = sizeSp.sp,
    )

    companion object {
        private val sizes = setOf(18, 20, 22, 24, 26, 28, 30, 32, 36, 40, 44, 48, 56, 64, 72)

        fun fromPreferences(weight: String, size: String) = LyricTypography(
            weight = LyricFontWeight.fromPreference(weight),
            fontSizeSp = size.toIntOrNull()?.takeIf { it in sizes },
        )
    }
}
