package io.github.gaboron.amllspw.cover

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

/** Draws the cover and its extension in one coordinate system as the layout moves or resizes. */
internal class ImmersiveCoverRenderer(effect: RuntimeEffect, cover: ImmersiveCover) : AutoCloseable {
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = Paint()

    init {
        installTexture("original", cover.original)
        installTexture("colorField", cover.colorField)
        installTexture("detailMask", cover.detailMask)
        installTexture("material", cover.material)
        val crop = cover.crop
        builder.uniform("crop", crop.left, crop.top, crop.width, crop.height)
        builder.uniform("profile", cover.profile.groundWeight, cover.profile.textureGain)
        cover.surroundings.forEachIndexed { index, color ->
            builder.uniform("surrounding$index", component(color, 16) / 255f,
                component(color, 8) / 255f, component(color, 0) / 255f)
        }
    }

    private fun installTexture(name: String, bitmap: ImageBitmap) {
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR).use { shader ->
                builder.child(name, shader)
            }
        }
    }

    fun draw(canvas: Canvas, width: Float, height: Float, anchor: Rect) {
        // A narrow viewport can collapse the layout slot. Keep the background defined in that case.
        builder.uniform("anchor", (anchor.left + anchor.right) / 2f, (anchor.top + anchor.bottom) / 2f,
            anchor.width.coerceAtLeast(1f))
        builder.uniform("viewport", width, height)
        builder.makeShader().use { shader ->
            paint.shader = shader
            try {
                canvas.drawRect(Rect.makeWH(width, height), paint)
            } finally {
                paint.shader = null
            }
        }
    }

    override fun close() {
        paint.close()
        builder.close()
    }

    companion object {
        fun makeEffect(): RuntimeEffect = RuntimeEffect.makeForShader("""
            uniform shader original;
            uniform shader colorField;
            uniform shader detailMask;
            uniform shader material;
            uniform float4 crop;
            uniform float3 anchor;
            uniform float2 viewport;
            uniform float2 profile;
            uniform float3 surrounding0;
            uniform float3 surrounding1;
            uniform float3 surrounding2;
            uniform float3 surrounding3;

            float3 cellRandom(float2 cell) {
                float3 h = fract(float3(cell.x, cell.y, cell.x) * float3(0.1031, 0.1030, 0.0973));
                h += dot(h, h.yzx + 33.33);
                return fract((h.xxy + h.yzz) * h.zyx);
            }

            half3 swatch(float2 cell, float2 grid, float cellSize) {
                const float columns = ${MATERIAL_COLUMNS}.0;
                const float tile = ${MATERIAL_TILE}.0;
                const float swatchSize = ${MATERIAL_SWATCH}.0;
                float3 random = cellRandom(cell + 19.0);
                float2 direction = cell * cellSize - anchor.xy;
                float2 distance = abs(direction);
                float ratio = min(distance.x, distance.y) / max(1.0, max(distance.x, distance.y));
                float sector = distance.x >= distance.y ? ratio : 2.0 - ratio;
                if (direction.x < 0.0) sector = 4.0 - sector;
                if (direction.y < 0.0) sector = 8.0 - sector;
                float transition = smoothstep(0.0, 1.0, fract(sector));
                float column = mod(floor(sector) + columns + step(random.z, transition), columns);
                float row = floor(random.x * ${MATERIAL_ROWS}.0);
                float2 patchOrigin = float2(column, row) * tile;
                float2 uv = clamp((grid - cell) * 0.5 + 0.5, 0.0, 1.0);
                float2 offset = random.yz * (tile - swatchSize - 1.0);
                if (fract(random.x * 31.0) > 0.5) uv.x = 1.0 - uv.x;
                if (fract(random.y * 31.0) > 0.5) uv.y = 1.0 - uv.y;
                return material.eval(patchOrigin + 0.5 + offset + uv * swatchSize).rgb - half3(128.0 / 255.0);
            }

            half3 broadColor(float2 pixel, float2 p) {
                float radius = max(abs(p.x), abs(p.y));
                float2 uv = smoothstep(float2(0.0), float2(1.0), pixel / viewport);
                half3 tone = mix(mix(surrounding0, surrounding1, uv.x), mix(surrounding2, surrounding3, uv.x), uv.y);
                float fade = mix(smoothstep(0.56, 1.26, radius), 1.0, profile.x);
                if (fade >= 1.0) return tone;
                half3 field = colorField.eval((p / 3.0 + 0.5) * ${COVER_FIELD_SIZE}.0).rgb;
                return mix(field, tone, fade);
            }

            half3 surfaceDetail(float2 pixel) {
                // Each tile spans two cells. Overlap neighbors at a fixed aspect to hide the grid.
                float roughness = smoothstep(0.42, 0.90, profile.y);
                float cellSize = clamp(anchor.z * (${MATERIAL_SWATCH}.0 / (2.0 * ${COVER_SAMPLE_SIZE}.0)),
                    2.5, mix(4.5, 7.0, roughness));
                float2 grid = pixel / cellSize;
                float2 cell = floor(grid);
                float2 weight = smoothstep(float2(0.0), float2(1.0), fract(grid));
                half3 upper = mix(swatch(cell, grid, cellSize),
                    swatch(cell + float2(1, 0), grid, cellSize), weight.x);
                half3 lower = mix(swatch(cell + float2(0, 1), grid, cellSize),
                    swatch(cell + float2(1, 1), grid, cellSize), weight.x);
                return mix(upper, lower, weight.y);
            }

            half4 main(float2 pixel) {
                float2 cropRatio = crop.zw / max(crop.z, crop.w);
                float2 p = (pixel - anchor.xy) / (anchor.z * cropRatio);
                float radius = max(abs(p.x), abs(p.y));
                half weight = 0.0;
                half3 coverColor = half3(0.0);
                if (radius < 0.5) {
                    float2 uv = p + 0.5;
                    weight = detailMask.eval(uv * ${COVER_SAMPLE_SIZE}.0).r;
                    half4 cover = original.eval(crop.xy + uv * crop.zw);
                    coverColor = cover.rgb + half3(9.0 / 255.0) * (1.0 - cover.a);
                    if (weight >= 1.0) return half4(coverColor, 1.0);
                }
                // Smooth scenes use only a trace. Actual grain can stay visible across the page.
                float farTexture = mix(0.20, 0.78, smoothstep(0.42, 0.90, profile.y));
                float textureAmount = profile.y * mix(1.0, farTexture, smoothstep(0.52, 1.30, radius));
                half3 background = clamp(broadColor(pixel, p) + surfaceDetail(pixel) * textureAmount, 0.0, 1.0);
                return half4(mix(background, coverColor, weight), 1.0);
            }
        """.trimIndent())
    }
}
