// SPDX-License-Identifier: AGPL-3.0-only
// Grain and vignette adapted from AMLL mesh-renderer/mesh.frag.glsl,
// revision 832bad5f16ec2a7f96d06707ef927c9a4d3713d3 (SteveXMH and AMLL contributors).
package io.github.gaboron.amllspw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** Retarget colors from their current blend while the continuous flow clock keeps running. */
@Composable
internal fun PlayerBackground(palette: List<Int>, reducedMotion: Boolean) {
    val colors = List(4) { index ->
        animateColorAsState(Color(palette.getOrElse(index) { 0xff233149.toInt() }),
            tween(if (reducedMotion) 0 else 1600)).value
    }
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) {
            var start = 0L
            val initial = seconds
            while (true) withFrameNanos { now ->
                if (start == 0L) start = now
                seconds = initial + (now - start) / 1_000_000_000f
            }
        }
    }
    val flow = remember { runCatching { PlayerFlowRenderer() }.getOrNull() }
    DisposableEffect(flow) { onDispose { flow?.close() } }
    Canvas(Modifier.fillMaxSize()) {
        if (flow == null) drawFluidBackground(colors.map { it.toArgb() }, seconds)
        else drawIntoCanvas { flow.draw(it.skiaCanvas, size.width, size.height, seconds, colors) }
    }
}

/** One native shader and builder per page; frame shaders are released immediately after drawing. */
private class PlayerFlowRenderer : AutoCloseable {
    private val effect = RuntimeEffect.makeForShader(SOURCE)
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = org.jetbrains.skia.Paint()

    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float, time: Float, colors: List<Color>) {
        builder.uniform("resolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        builder.uniform("time", time)
        colors.forEachIndexed { index, color -> builder.uniform("color$index", color.red, color.green, color.blue) }
        builder.makeShader().use { shader ->
            paint.shader = shader
            canvas.drawRect(org.jetbrains.skia.Rect.makeWH(width, height), paint)
            paint.shader = null
        }
    }

    override fun close() {
        paint.close()
        builder.close()
        effect.close()
    }

    companion object {
        // Domain warping bends the broad color fields instead of adding pale circles with Screen blending.
        private const val SOURCE = """
            uniform float2 resolution;
            uniform float time;
            uniform float3 color0;
            uniform float3 color1;
            uniform float3 color2;
            uniform float3 color3;
            half4 main(float2 pixel) {
                float2 uv = pixel / resolution;
                float2 p = (pixel - resolution * 0.5) / max(resolution.x, resolution.y);
                float t = time * 0.19;
                float angle = t * 0.7;
                p = float2(cos(angle) * p.x - sin(angle) * p.y, sin(angle) * p.x + cos(angle) * p.y);
                float2 wave = float2(sin(p.y * 4.8 + t) + cos(p.x * 3.1 - t * 0.8),
                    cos(p.x * 4.2 - t * 1.1) + sin(p.y * 3.7 + t * 0.7));
                float2 q = p + wave * 0.16;
                float x = smoothstep(-0.48, 0.48, q.x + sin(q.y * 4.0 - t) * 0.16);
                float y = smoothstep(-0.48, 0.48, q.y + cos(q.x * 4.5 + t) * 0.15);
                float3 color = mix(mix(color0, color1, x), mix(color2, color3, x), y);
                float ribbon = sin(q.x * 3.4 + q.y * 2.8 + t * 1.3);
                color *= 0.88 + 0.22 * smoothstep(-0.7, 0.9, ribbon);
                float noise = fract(52.9829189 * fract(dot(pixel, float2(0.06711056, 0.00583715))));
                color += (noise - 0.5) / 255.0;
                float vignette = 1.0 - smoothstep(0.3, 0.8, distance(uv, float2(0.5)));
                color *= 0.68 + vignette * 0.32;
                return half4(color, 1.0);
            }
        """
    }
}
