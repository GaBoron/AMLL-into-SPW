package io.github.gaboron.amllspw.verify

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect as ComposeRect
import io.github.gaboron.amllspw.cover.blendImmersiveColors
import io.github.gaboron.amllspw.cover.COVER_SAMPLE_SIZE
import io.github.gaboron.amllspw.cover.CoverColorField
import io.github.gaboron.amllspw.cover.ImmersiveCoverRenderer
import io.github.gaboron.amllspw.cover.ImmersiveBackdropSamples
import io.github.gaboron.amllspw.cover.MATERIAL_COLUMNS
import io.github.gaboron.amllspw.cover.MATERIAL_ROWS
import io.github.gaboron.amllspw.cover.MATERIAL_TILE
import io.github.gaboron.amllspw.cover.coverBackdropProfile
import io.github.gaboron.amllspw.cover.coverDetailMask
import io.github.gaboron.amllspw.cover.coverEdgeMatte
import io.github.gaboron.amllspw.cover.extractCoverMaterial
import io.github.gaboron.amllspw.cover.immersiveInk
import io.github.gaboron.amllspw.cover.immersiveInkFamily
import io.github.gaboron.amllspw.cover.inkContrast
import io.github.gaboron.amllspw.cover.inkLuminance
import io.github.gaboron.amllspw.cover.OklabColor
import io.github.gaboron.amllspw.cover.prepareImmersiveCover
import io.github.gaboron.amllspw.cover.trimCoverMatte
import io.github.gaboron.amllspw.ui.playerInkOutline
import io.github.gaboron.amllspw.ui.AmllPlayerStyle
import io.github.gaboron.amllspw.ui.ImmersiveArtwork
import io.github.gaboron.amllspw.ui.ImmersivePlayerBackground
import io.github.gaboron.amllspw.ui.ImmersivePlayerInk
import io.github.gaboron.amllspw.ui.rememberImmersiveArtworkLayers
import io.github.gaboron.amllspw.ui.rememberImmersivePlayerInk
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import kotlin.math.abs

internal fun verifyImmersiveCover() {
    val edge = COVER_SAMPLE_SIZE
    val dark = 0xff1f2731.toInt()
    val flat = IntArray(edge * edge) { dark }
    val field = CoverColorField(flat)
    check(field.pixels().all { it == dark }) { "Flat artwork developed gaps or visible seams" }
    val uniform = coverBackdropProfile(flat)
    check(uniform.groundWeight == 1f && uniform.ground == dark)
    val mask = coverDetailMask(flat, field, uniform)
    for (i in 0 until edge) {
        check((mask[i] and 255) == 0 && (mask[(edge - 1) * edge + i] and 255) == 0)
        check((mask[i * edge] and 255) == 0 && (mask[i * edge + edge - 1] and 255) == 0)
    }
    check(mask.count { (it and 255) == 255 } > edge * edge * .80)
    val crop = Rect.makeWH(128f, 128f)
    val framed = IntArray(edge * edge) { index ->
        if (index / edge < 4 || index / edge >= edge - 4) -1 else dark
    }
    check(trimCoverMatte(crop, framed) == Rect.makeLTRB(0f, 4f, 128f, 124f))
    framed.fill(dark, edge * (edge - 4))
    check(trimCoverMatte(crop, framed) == crop) { "A single light edge was mistaken for a matte" }
    verifyCoverMaterial()
    verifyCoverEdgeMatte()
    verifyImmersiveRendering()
    verifyImmersiveInk()
    verifyImmersiveTransition()
    verifyForegroundOutline()
    println("Immersive cover: edge matting, adaptive texture, artwork crossfade, shared ink and outline fallback passed")
}

private fun verifyCoverEdgeMatte() {
    val size = COVER_SAMPLE_SIZE
    val background = 0xff9bbcd6.toInt()
    val subject = 0xff842e3c.toInt()
    val source = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        if ((x < 32 && y in 45..61) || (x in 2..4 && y in 75..78)) subject else background
    }
    val field = CoverColorField(source)
    val profile = coverBackdropProfile(source)
    val feather = coverDetailMask(source, field, profile)
    val matte = coverEdgeMatte(source, field, profile)
    fun pixel(x: Int, y: Int) = matte.mask[y * size + x]
    fun alpha(x: Int, y: Int) = (pixel(x, y) ushr 16) and 255
    val subjectFeather = feather[50 * size + 3] and 255
    check(alpha(0, 50) == 0 && alpha(3, 50) in (subjectFeather + 8)..(subjectFeather + 34)) {
        "A connected subject must gain opacity while keeping the broad feather"
    }
    val fleckFeather = feather[76 * size + 3] and 255
    check(alpha(3, 76) - fleckFeather < alpha(3, 50) - subjectFeather) {
        "An isolated edge fleck was treated as part of the subject"
    }
    check(alpha(3, 67) < (feather[67 * size + 3] and 255) && alpha(20, 67) == 255) {
        "The matte removed more than the perimeter backdrop"
    }
    check(matte.mask.indices.all { index ->
        val baseline = feather[index] and 255
        if (baseline == 0 || baseline == 255) (matte.mask[index] ushr 16 and 255) == baseline else true
    }) { "Matting replaced part of the stable artwork or revealed pixels outside the feather" }
    check(((pixel(1, 65) ushr 8) and 255) <= 8) { "A clean matte edge gained artificial material" }

    val strongGround = 0xffb1cfe0.toInt()
    val peppered = IntArray(size * size) { index ->
        if (index % size < 8 && index / size % 8 < 4) subject else strongGround
    }
    val groundMask = BooleanArray(peppered.size) { peppered[it] == strongGround }
    val before = CoverColorField(peppered).color(size - 1, size + 64)
    val after = CoverColorField(peppered, groundMask).color(size - 1, size + 64)
    check(abs((after and 255) - (strongGround and 255)) < abs((before and 255) - (strongGround and 255))) {
        "Cover artwork polluted the background boundary despite reliable backdrop samples"
    }
}

private fun verifyCoverMaterial() {
    val neutral = 0xff808080.toInt()
    val size = COVER_SAMPLE_SIZE
    val colors = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        if (x in 34..93 && y in 34..93 && (x / 6) % 2 == 0) -1 else neutral
    }
    check(extractCoverMaterial(colors).all { it == neutral }) {
        "The material tile contains lettering from the center of the artwork"
    }
    val background = IntArray(size * size) { index ->
        val grain = 113 + ((index % size * 73 xor index / size * 151) % 29)
        (0xff shl 24) or (grain shl 16) or (grain shl 8) or grain
    }
    val detail = extractCoverMaterial(background).map { it and 255 }
    check(detail.count { it <= 122 } > 200 && detail.count { it >= 134 } > 200) {
        "Background texture extraction flattened the source material"
    }
    val upperGrain = IntArray(size * size) { index -> if (index / size < 32) background[index] else neutral }
    val swatches = extractCoverMaterial(upperGrain)
    fun containsGrain(direction: Int) = (0 until MATERIAL_ROWS * MATERIAL_TILE).any { y ->
        (0 until MATERIAL_TILE).any { x ->
            swatches[y * MATERIAL_COLUMNS * MATERIAL_TILE + direction * MATERIAL_TILE + x] != neutral
        }
    }
    check(containsGrain(6) && containsGrain(5) && !containsGrain(2) && !containsGrain(1)) {
        "Background above the cover was copied to the opposite direction"
    }
    val scene = IntArray(size * size) { index ->
        (0xff shl 24) or (index % size * 2 shl 16) or (index / size * 2 shl 8) or 60
    }
    val material = IntArray(size * size) { index ->
        val delta = ((index % size * 13 xor index / size * 37) % 48)
        (0xff shl 24) or ((80 + delta * 2) shl 16) or ((90 + delta) shl 8) or (40 + delta)
    }
    val sceneProfile = coverBackdropProfile(scene)
    check(sceneProfile.groundWeight == 0f && sceneProfile.textureGain < .2f)
    check(coverBackdropProfile(material).textureGain > .75f)
}

private fun verifyImmersiveInk() {
    val white = -1
    val black = 0xff000000.toInt()
    val light = 0xffbdd7e6.toInt()
    val dark = 0xff4b302a.toInt()
    val accent = 0xffda8150.toInt()
    val lightColors = IntArray(80) { if (it == 0) white else light }
    val darkColors = IntArray(80) { dark }
    val family = immersiveInkFamily(accent, darkColors)
    fun contrast(a: Int, b: Int) = inkContrast(inkLuminance(a), inkLuminance(b))
    check(immersiveInk(lightColors, family, false).primary == black)
    check(immersiveInk(darkColors, family, false).primary == white)
    check(immersiveInk(intArrayOf(0xff777777.toInt()), null, false).primary == black)
    for ((colors, base) in listOf(lightColors to light, darkColors to dark)) {
        val ink = immersiveInk(colors, family, true)
        check(!ink.needsOutline && contrast(ink.primary, base) >= 4.5 && contrast(ink.secondary, base) >= 4.5)
        check(OklabColor.fromRgb(ink.primary).chroma > .02)
        check(contrast(ink.bubbleText, ink.bubble) >= 7.0)
        check(contrast(ink.primary, ink.rail) >= 3.0)
    }
    check(immersiveInk(intArrayOf(black, white), family, false).needsOutline)
    check(immersiveInkFamily(white, IntArray(20) { white }) == null)
    val middle = intArrayOf(0xff777777.toInt())
    val mediumInk = immersiveInk(middle, family, true)
    check(contrast(mediumInk.primary, middle.single()) >= 4.5 && !mediumInk.needsOutline)
    check(mediumInk.primary != black && mediumInk.primary != white) { "An eligible chromatic ink was ignored" }
    val shared = immersiveInk(lightColors, family, true).primary
    check(immersiveInk(intArrayOf(0xffd3e2ee.toInt()), family, true, shared).primary == shared)
    check(immersiveInk(darkColors, family, true, shared).primary != shared)
    check(blendImmersiveColors(listOf(intArrayOf(black), intArrayOf(white)), listOf(.75f, .25f)).single() ==
        0xff404040.toInt())

    val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
    for (y in 0..127) for (x in 0..127) image.setRGB(x, y, if (x < 64) black else white)
    val cover = prepareImmersiveCover(image.toComposeImageBitmap()) {}
    ImmersiveCoverRenderer.makeEffect().use { effect ->
        val sample = ImmersiveBackdropSamples.render(effect, cover, 960f, 560f, ComposeRect(220f, 56f, 650f, 486f))
        val left = sample.colors(ComposeRect(270f, 170f, 380f, 400f))
        val right = sample.colors(ComposeRect(485f, 170f, 590f, 400f))
        check(immersiveInk(left, null, false).primary == white)
        check(immersiveInk(right, null, false).primary == black)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun verifyImmersiveTransition() {
    fun flat(color: Int) = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, 128, 128, IntArray(128 * 128) { color }, 0, 128)
    }.toComposeImageBitmap()

    ImmersiveCoverRenderer.makeEffect().use { effect ->
        fun artwork(color: Int) = ImmersiveArtwork(effect, prepareImmersiveCover(flat(color)) {}, color)
        val black = artwork(0xff000000.toInt())
        val white = artwork(-1)
        val gray = artwork(0xff585858.toInt())
        val target = mutableStateOf(black)
        val style = AmllPlayerStyle(320f, 240f)
        val anchor = ComposeRect(20f, 30f, 100f, 110f)
        var inkNow: ImmersivePlayerInk? = null
        val scene = ImageComposeScene(width = 320, height = 240) {
            val layers = rememberImmersiveArtworkLayers(target.value, false)
            val ink = rememberImmersivePlayerInk(layers, style, anchor, 42f, false, false)
            SideEffect { inkNow = ink }
            ImmersivePlayerBackground(layers, anchor)
        }
        try {
            fun channel(ms: Long): Int = scene.render(1_000_000L * ms).use { image ->
                Bitmap.makeFromImage(image).use { it.getColor(300, 70) and 255 }
            }
            check(channel(0) == 0)
            target.value = white
            channel(16)
            channel(32)
            val intermediate = channel(260)
            check(intermediate in 15..240) { "Opposite-polarity artwork switched without a crossfade ($intermediate)" }
            val inBetween = checkNotNull(inkNow)
            check(inBetween.controls.primary == inBetween.lyrics.primary && !inBetween.lyrics.needsOutline)
            target.value = gray
            val retargeted = channel(276)
            check(abs(intermediate - retargeted) <= 32) { "Rapid track change discarded the displayed mix" }
            channel(300)
            channel(840)
            check(channel(900) == 88)
        } finally {
            scene.close()
        }
    }
}

private fun verifyForegroundOutline() {
    val source = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
    source.setRGB(0, 0, 128, 128, IntArray(128 * 128) { -1 }, 0, 128)
    val cover = prepareImmersiveCover(source.toComposeImageBitmap()) {}
    ImmersiveCoverRenderer.makeEffect().use { effect ->
        ImmersiveCoverRenderer(effect, cover).use { renderer ->
            Surface.makeRasterN32Premul(640, 320).use { surface ->
                renderer.draw(surface.canvas, 640f, 320f, Rect.makeXYWH(80f, 40f, 172f, 172f))
                val canvas = surface.canvas
                playerInkOutline(1f).use { outline ->
                    Paint().use { effectPaint ->
                        effectPaint.imageFilter = outline
                        canvas.saveLayer(Rect.makeXYWH(300f, 100f, 200f, 100f), effectPaint)
                        Paint().use { foreground ->
                            foreground.color = -1
                            canvas.drawRect(Rect.makeXYWH(350f, 120f, 4f, 60f), foreground)
                        }
                        canvas.restore()
                    }
                }
                Bitmap().use { result ->
                    check(result.allocPixels(surface.imageInfo))
                    check(surface.readPixels(result, 0, 0))
                    fun light(x: Int, y: Int) = result.getColor(x, y) and 255
                    check(light(349, 125) < 80 && light(350, 125) == 255)
                    check(light(348, 125) == 255 && light(165, 125) == 255 && light(550, 248) == 255) {
                        "Foreground contrast changed the artwork away from the glyph"
                    }
                }
            }
        }
    }
}

private fun verifyImmersiveRendering() {
    // Deliberately use a non-square source and features narrower than a synthesis sample.
    val source = BufferedImage(450, 384, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until source.height) for (x in 0 until source.width) {
        val color = when {
            x < 33 || x >= 417 -> 0xfff01010.toInt()
            x in 110..339 && y in 80..303 -> if (x % 2 == 0) 0xff101010.toInt() else 0xfff0f0f0.toInt()
            else -> 0xff8f8f8f.toInt()
        }
        source.setRGB(x, y, color)
    }
    val cover = prepareImmersiveCover(source.toComposeImageBitmap()) {}
    ImmersiveCoverRenderer.makeEffect().use { effect ->
        ImmersiveCoverRenderer(effect, cover).use { renderer ->
            for ((width, height) in listOf(960 to 560, 560 to 960)) {
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    val x = if (width > height) 40 else 60
                    val y = if (width > height) 67 else 100
                    renderer.draw(surface.canvas, width.toFloat(), height.toFloat(),
                        Rect.makeXYWH(x.toFloat(), y.toFloat(), 384f, 384f))
                    Bitmap().use { result ->
                        check(result.allocPixels(surface.imageInfo))
                        check(surface.readPixels(result, 0, 0))
                        val core = result.getColor(x + 191, y + 192)
                        val next = result.getColor(x + 192, y + 192)
                        check(core == 0xff101010.toInt() && next == 0xfff0f0f0.toInt()) {
                            "The original crop lost high-resolution detail"
                        }
                        val inside = result.getColor(x + 383, y + 192)
                        val outside = result.getColor(x + 384, y + 192)
                        check(abs((inside and 255) - (outside and 255)) <= 3)
                        val toneRow = y + 40
                        for (column in x + 370..x + 400) {
                            val a = result.getColor(column - 1, toneRow) and 255
                            val b = result.getColor(column, toneRow) and 255
                            check(abs(a - b) <= 3) { "Brightness jumps at the artwork boundary" }
                        }
                        val corner = result.getColor(width - 1, height - 1)
                        check(corner == 0xff8f8f8f.toInt()) { "The distant field changed uniform edge brightness" }
                    }
                }
            }
        }
    }
}
