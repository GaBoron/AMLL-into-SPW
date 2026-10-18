package io.github.gaboron.amllspw.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

internal enum class LyricFontWeight(val value: String, private val fileName: String) {
    EXTRA_LIGHT("200", "PingFang-ExtraLight.ttf"),
    LIGHT("300", "PingFang-Light.ttf"),
    REGULAR("400", "PingFang-Regular.ttf"),
    MEDIUM("500", "PingFang-Medium.ttf"),
    BOLD("700", "PingFang-Bold.ttf"),
    HEAVY("900", "PingFang-Heavy.ttf");

    val fontWeight = FontWeight(value.toInt())
    // Each supplied face reports Regular in its metadata. Select the actual file and disable
    // synthesis instead of asking the system to infer a different weight from that metadata.
    // Keep Compose's platform font resolver: characters missing from this face use system fonts.
    val family: FontFamily by lazy {
        val bytes = checkNotNull(LyricFontWeight::class.java.getResourceAsStream("/fonts/$fileName")) {
            "Missing bundled lyric font: $fileName"
        }.use { it.readBytes() }
        FontFamily(Font(identity = "io.github.gaboron.amllspw/fonts/$fileName", data = bytes, weight = fontWeight))
    }

    companion object {
        fun fromPreference(value: String) = entries.firstOrNull { it.value == value } ?: BOLD
    }
}

/** Typography shared by main lyrics, background vocals, translations, romanization and ruby. */
internal data class LyricTypography(
    val weight: LyricFontWeight = LyricFontWeight.BOLD,
    val fontSizeSp: Int? = null,
) {
    fun fontSize(viewportWidthDp: Float): Float =
        fontSizeSp?.toFloat() ?: (viewportWidthDp / 13.5f).coerceIn(24f, 42f)

    fun textStyle(sizeSp: Float) = TextStyle(
        fontFamily = weight.family,
        fontWeight = weight.fontWeight,
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
