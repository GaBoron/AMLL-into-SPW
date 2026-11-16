// SPDX-License-Identifier: AGPL-3.0-only
// Grain and vignette adapted from AMLL mesh-renderer/mesh.frag.glsl,
// revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3 (SteveXMH and AMLL contributors).
package io.github.gaboron.amllspw.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/** Mix preblurred artwork at several scales; only texture sampling runs on each animation frame. */
internal class ArtworkFlowRenderer : AutoCloseable {
    private class Texture(val image: Image) : AutoCloseable {
        val shader: Shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
        override fun close() {
            shader.close()
            image.close()
        }
    }

    private val effect = RuntimeEffect.makeForShader(SOURCE)
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = Paint()
    private var incoming: Texture? = null
    private var outgoing: Texture? = null
    private var artwork: ImageBitmap? = null
    private var palette: List<Int> = emptyList()
    private var transitionStart = 0f

    fun draw(canvas: Canvas, width: Float, height: Float, time: Float,
        artwork: ImageBitmap?, palette: List<Int>, reducedMotion: Boolean) {
        val progress = if (reducedMotion) 1f else transition(time)
        if (incoming == null || this.artwork !== artwork || (artwork == null && this.palette != palette)) {
            retarget(artwork, palette, time, progress)
        }
        val blend = if (reducedMotion) 1f else transition(time)
        if (blend >= 1f) {
            outgoing?.close()
            outgoing = null
        }
        val next = checkNotNull(incoming)
        val previous = outgoing ?: next
        builder.child("previousArt", previous.shader)
        builder.child("nextArt", next.shader)
        builder.uniform("previousSize", previous.image.width.toFloat(), previous.image.height.toFloat())
        builder.uniform("nextSize", next.image.width.toFloat(), next.image.height.toFloat())
        builder.uniform("resolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        builder.uniform("time", time)
        builder.uniform("blend", blend)
        builder.makeShader().use { shader ->
            paint.shader = shader
            try {
                canvas.drawRect(Rect.makeWH(width, height), paint)
            } finally {
                paint.shader = null
            }
        }
    }

    private fun transition(time: Float): Float {
        val progress = ((time - transitionStart) / 1.8f).coerceIn(0f, 1f)
        return progress * progress * (3f - 2f * progress)
    }

    private fun retarget(artwork: ImageBitmap?, palette: List<Int>, time: Float, progress: Float) {
        val next = Texture(if (artwork != null) Image.makeFromBitmap(artwork.asSkiaBitmap()) else paletteImage(palette))
        // Flatten an interrupted texture transition so rapid skips start at the displayed colors.
        val current = incoming
        val previous = outgoing
        val visible = if (previous != null && current != null && progress < 1f) {
            Texture(blendImages(previous.image, current.image, progress))
        } else current
        if (previous !== visible) previous?.close()
        if (current !== visible) current?.close()
        outgoing = visible
        incoming = next
        this.artwork = artwork
        this.palette = palette
        transitionStart = if (visible == null) time - 1.8f else time
    }

    private fun blendImages(previous: Image, next: Image, progress: Float): Image =
        Surface.makeRasterN32Premul(192, 192).use { surface ->
            val bounds = Rect.makeWH(192f, 192f)
            Paint().use { alpha ->
                surface.canvas.drawImageRect(previous,
                    Rect.makeWH(previous.width.toFloat(), previous.height.toFloat()), bounds,
                    SamplingMode.LINEAR, alpha, true)
                alpha.setAlphaf(progress)
                surface.canvas.drawImageRect(next,
                    Rect.makeWH(next.width.toFloat(), next.height.toFloat()), bounds,
                    SamplingMode.LINEAR, alpha, true)
            }
            surface.makeImageSnapshot()
        }

    private fun paletteImage(palette: List<Int>): Image = Surface.makeRasterN32Premul(2, 2).use { surface ->
        Paint().use { fill ->
            repeat(4) { index ->
                fill.color = palette.getOrElse(index) { 0xff233149.toInt() }
                surface.canvas.drawRect(Rect.makeXYWH((index % 2).toFloat(), (index / 2).toFloat(), 1f, 1f), fill)
            }
        }
        surface.makeImageSnapshot()
    }

    override fun close() {
        paint.close()
        builder.close()
        incoming?.close()
        outgoing?.close()
        effect.close()
    }

    companion object {
        private const val SOURCE = """
            uniform shader previousArt;
            uniform shader nextArt;
            uniform float2 previousSize;
            uniform float2 nextSize;
            uniform float2 resolution;
            uniform float time;
            uniform float blend;

            float2 rotate(float2 p, float angle) {
                float s = sin(angle);
                float c = cos(angle);
                return float2(c * p.x - s * p.y, s * p.x + c * p.y);
            }

            half3 artwork(float2 uv) {
                uv = clamp(uv, 0.0, 1.0);
                if (blend >= 1.0) return nextArt.eval(uv * nextSize).rgb;
                if (blend <= 0.0) return previousArt.eval(uv * previousSize).rgb;
                return mix(previousArt.eval(uv * previousSize).rgb, nextArt.eval(uv * nextSize).rgb, blend);
            }

            half3 layer(half3 base, float2 p, float2 center, float radius, float angle, float opacity) {
                float2 q = (p - center) / radius;
                float mask = (1.0 - smoothstep(0.12, 1.0, length(q))) * opacity;
                return mix(base, artwork(rotate(q * 0.5, angle) + 0.5), mask);
            }

            half4 main(float2 pixel) {
                float2 uv = pixel / resolution;
                float2 p = (pixel - resolution * 0.5) / max(resolution.x, resolution.y);
                half3 color = artwork(rotate(p * 0.72, time * 0.022) + 0.5);
                color = layer(color, p, float2(-0.12, 0.05), 0.68, -time * 0.046 + 1.3, 0.66);
                color = layer(color, p, float2(-0.45, 0.30), 0.58, time * 0.058 + 3.7, 0.48);
                color = layer(color, p, float2(0.45, -0.30), 0.62, -time * 0.034 + 5.1, 0.44);
                // Compress broad highlights smoothly so pale covers stay behind the lyric text.
                float luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
                color *= 0.72 / (1.0 + luminance * 0.7);
                float vignette = 1.0 - smoothstep(0.3, 0.8, distance(uv, float2(0.5)));
                color *= 0.85 + vignette * 0.15;
                float noise = fract(52.9829189 * fract(dot(pixel, float2(0.06711056, 0.00583715))));
                color += (noise - 0.5) / 255.0;
                return half4(color, 1.0);
            }
        """
    }
}
