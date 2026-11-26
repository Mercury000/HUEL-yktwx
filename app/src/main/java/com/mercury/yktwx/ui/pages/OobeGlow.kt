package com.mercury.yktwx.ui.pages

import android.graphics.RuntimeShader
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.mercury.yktwx.ui.rememberForegroundState
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

@Composable
internal fun OobeGlow(admission: Boolean, centerY: () -> Float, modifier: Modifier = Modifier, animate: Boolean = true) {
    val shader = remember { runCatching { RuntimeShader(GlowSource) }.getOrNull() }
    val foreground by rememberForegroundState()
    var time by remember(admission) { mutableFloatStateOf(2f) }
    var elapsed by remember(admission) { mutableFloatStateOf(0f) }
    LaunchedEffect(admission, foreground.active, animate, shader) {
        if (!foreground.active || !animate || shader == null || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@LaunchedEffect
        val saved = elapsed
        var start = Long.MIN_VALUE
        while (true) withInfiniteAnimationFrameNanos { frame ->
            if (start == Long.MIN_VALUE) start = frame
            elapsed = saved + (frame - start) / 1_000_000_000f
            time = if (elapsed < 120f) elapsed else 120f - (elapsed - 120f).mod(236f).let { minOf(it, 236f - it) }
        }
    }
    Layout(modifier = modifier.background(Color(0xFF9D9FE7)), content = {
        if (shader != null) Spacer(Modifier.drawWithCache {
            shader.setFloatUniform("resolution", size.width, size.height)
            val brush = ShaderBrush(shader)
            onDrawBehind {
                shader.setFloatUniform("seconds", time)
                shader.setFloatUniform("centerY", centerY())
                shader.setFloatUniform("admission", if (admission) 1f else 0f)
                drawRect(brush)
            }
        })
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val glow = measurables.firstOrNull()?.measure(Constraints.fixed(
            ceil(width * .2).toInt().coerceAtLeast(1), ceil(height * .2).toInt().coerceAtLeast(1)))
        layout(width, height) {
            glow?.placeWithLayer(0, 0) {
                compositingStrategy = CompositingStrategy.Offscreen
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = width.toFloat() / glow.width
                scaleY = height.toFloat() / glow.height
            }
        }
    }
}

private const val GlowSource = """
uniform float2 resolution;
uniform float seconds;
uniform float centerY;
uniform float admission;

float3 gradientAt(float3 cell) {
    float3 seed = fract(cell * float3(.1031, .11369, .13787));
    seed += dot(seed, seed.yxz + 19.19);
    return 2.0 * fract((seed.xxy + seed.yzz) * seed.zyx) - 1.0;
}

float corner(float3 cell, float3 delta) {
    float weight = max(.6 - dot(delta, delta), 0.0);
    return weight * weight * weight * weight * dot(delta, gradientAt(cell));
}

float noiseField(float3 point) {
    float3 cell = floor(point + (point.x + point.y + point.z) / 3.0);
    float3 delta = point - cell + (cell.x + cell.y + cell.z) / 6.0;
    float3 ranked = step(float3(0.0), delta - delta.yzx);
    float3 near = ranked * (1.0 - ranked.zxy);
    float3 far = 1.0 - ranked.zxy * (1.0 - ranked);
    return 31.316 * (corner(cell, delta) + corner(cell + near, delta - near + 1.0/6.0)
        + corner(cell + far, delta - far + 1.0/3.0) + corner(cell + 1.0, delta - .5));
}

float3 palette(float3 from, float3 to, float fraction) {
    const float3x3 toCone = float3x3(.412165612, .211859107, .0883097947,
        .536275208, .6807189584, .2818474174, .0514575653, .107406579, .6302613616);
    const float3x3 fromCone = float3x3(4.0767245293, -1.2681437731, -.0041119885,
        -3.3072168827, 2.6093323231, -.7034763098, .2307590544, -.341134429, 1.7068625689);
    float3 cones = mix(pow(toCone * from, float3(1.0/3.0)), pow(toCone * to, float3(1.0/3.0)), fraction);
    cones *= 1.0 + .2 * fraction * (1.0 - fraction);
    return fromCone * (cones * cones * cones);
}

half4 main(float2 position) {
    float2 uv = position / resolution;
    float2 aspect = resolution / max(resolution.x, resolution.y);
    float2 plane = uv * aspect;
    float tint = mix(.3, .86, clamp((noiseField(float3(plane, seconds * .49) * .82) * .5 + .2) / .7, 0.0, 1.0));
    float light = clamp(noiseField(float3(plane, seconds * .4) * 1.3) * .5 + .5, .25, 1.0);
    const float3 red = float3(.961, .157, .157);
    const float3 lavender = float3(.604, .659, .961);
    const float3 violet = float3(.302, .29, .843);
    float3 dye = tint < .47 ? palette(red, lavender, tint / .47) : palette(lavender, violet, (tint - .47) / .53);
    float3 color = mix(dye, float3(1.0), light);
    if (admission > .5) {
        float distanceFromLogo = length((uv - float2(.5, centerY)) * aspect);
        float age = seconds * .9;
        if (age <= 1.0) {
            float radius = age / (1.4 - .4 * age);
            float edge = clamp((radius - distanceFromLogo) / .4, 0.0, 1.0);
            float intensity = 4.0 * edge * (1.0 - edge);
            float3 spectrum = .5 + .5 * cos(6.2831853 * (edge + float3(.25, .35, .45)));
            float3 halo = spectrum * (intensity * intensity * intensity);
            color = mix(color, float3(1.0), halo) + .04 * halo;
        }
        float reveal = min(seconds * .9 - .3, 1.0);
        reveal = reveal * (2.0 - reveal);
        color *= 1.0 - smoothstep(reveal - .3, reveal + .3, distanceFromLogo);
    }
    return half4(clamp(color, 0.0, 1.0), 1.0);
}
"""
