package com.mineways;

import android.os.Build;

/**
 * 液态玻璃的 AGSL 着色器源码。
 *
 * <p>这三个着色器是「液态玻璃」效果的本体：折射（含 7 段色散变体）与边缘高光，
 * 源码取自 Apache-2.0 的 AndroidLiquidGlass / backdrop（Kyant0），
 * 与「玄戒工具箱 3.8.1」apk 里实际跑的那份是同一套算法（已从它的 dex 中逐字核对）。</p>
 *
 * <p>{@code uniform shader content} 的输入由调用方用
 * {@code RuntimeShader.setInputShader("content", bitmapShader)} 注入（合成后的背景图）。</p>
 *
 * <p>RuntimeShader 需要 API 33+；低版本没有着色器，和原库一样自动降级。
 * 所有对 {@link android.graphics.RuntimeShader} 的触碰都关在 {@link #supported()} 分支里。</p>
 */
final class GlassShaders {

    private GlassShaders() {
    }

    /** 圆角矩形的有符号距离场（SDF），被下面两个着色器共用。 */
    static final String SDF =
            "float radiusAt(float2 coord, float4 radii) {\n"
                    + "    if (coord.x >= 0.0) {\n"
                    + "        if (coord.y <= 0.0) return radii.y;\n"
                    + "        else return radii.z;\n"
                    + "    } else {\n"
                    + "        if (coord.y <= 0.0) return radii.x;\n"
                    + "        else return radii.w;\n"
                    + "    }\n"
                    + "}\n"
                    + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
                    + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
                    + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
                    + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
                    + "    return outside + inside;\n"
                    + "}\n"
                    + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
                    + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
                    + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
                    + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
                    + "    } else {\n"
                    + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
                    + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
                    + "    }\n"
                    + "}\n";

    /** 折射：靠近边缘 refractionHeight 的一圈内，按 circleMap 把采样点往外推 refractionAmount。 */
    static final String REFRACTION =
            "uniform shader content;\n"
                    + "uniform float2 size;\n"
                    + "uniform float2 offset;\n"
                    + "uniform float4 cornerRadii;\n"
                    + "uniform float refractionHeight;\n"
                    + "uniform float refractionAmount;\n"
                    + "uniform float depthEffect;\n"
                    + SDF
                    + "float circleMap(float x) {\n"
                    + "    return 1.0 - sqrt(1.0 - x * x);\n"
                    + "}\n"
                    + "half4 main(float2 coord) {\n"
                    + "    float2 halfSize = size * 0.5;\n"
                    + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
                    + "    float radius = radiusAt(coord, cornerRadii);\n"
                    + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
                    + "    if (-sd >= refractionHeight) {\n"
                    + "        return content.eval(coord);\n"
                    + "    }\n"
                    + "    sd = min(sd, 0.0);\n"
                    + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
                    + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
                    + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
                    + " + depthEffect * normalize(centeredCoord));\n"
                    + "    float2 refractedCoord = coord + d * grad;\n"
                    + "    return content.eval(refractedCoord);\n"
                    + "}\n";

    /** 折射 + 色散：在折射点周围做红→紫 7 段采样，这是"液态"的关键。 */
    static final String REFRACTION_DISPERSION =
            "uniform shader content;\n"
                    + "uniform float2 size;\n"
                    + "uniform float2 offset;\n"
                    + "uniform float4 cornerRadii;\n"
                    + "uniform float refractionHeight;\n"
                    + "uniform float refractionAmount;\n"
                    + "uniform float depthEffect;\n"
                    + "uniform float chromaticAberration;\n"
                    + SDF
                    + "float circleMap(float x) {\n"
                    + "    return 1.0 - sqrt(1.0 - x * x);\n"
                    + "}\n"
                    + "half4 main(float2 coord) {\n"
                    + "    float2 halfSize = size * 0.5;\n"
                    + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
                    + "    float radius = radiusAt(coord, cornerRadii);\n"
                    + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
                    + "    if (-sd >= refractionHeight) {\n"
                    + "        return content.eval(coord);\n"
                    + "    }\n"
                    + "    sd = min(sd, 0.0);\n"
                    + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
                    + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
                    + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
                    + " + depthEffect * normalize(centeredCoord));\n"
                    + "    float2 refractedCoord = coord + d * grad;\n"
                    + "    float dispersionIntensity = chromaticAberration *"
                    + " ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
                    + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
                    + "    half4 color = half4(0.0);\n"
                    + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
                    + "    color.r += red.r / 3.5;\n"
                    + "    color.a += red.a / 7.0;\n"
                    + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
                    + "    color.r += orange.r / 3.5;\n"
                    + "    color.g += orange.g / 7.0;\n"
                    + "    color.a += orange.a / 7.0;\n"
                    + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
                    + "    color.r += yellow.r / 3.5;\n"
                    + "    color.g += yellow.g / 3.5;\n"
                    + "    color.a += yellow.a / 7.0;\n"
                    + "    half4 green = content.eval(refractedCoord);\n"
                    + "    color.g += green.g / 3.5;\n"
                    + "    color.a += green.a / 7.0;\n"
                    + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
                    + "    color.g += cyan.g / 3.5;\n"
                    + "    color.b += cyan.b / 3.0;\n"
                    + "    color.a += cyan.a / 7.0;\n"
                    + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
                    + "    color.b += blue.b / 3.0;\n"
                    + "    color.a += blue.a / 7.0;\n"
                    + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
                    + "    color.r += purple.r / 7.0;\n"
                    + "    color.b += purple.b / 3.0;\n"
                    + "    color.a += purple.a / 7.0;\n"
                    + "    return color;\n"
                    + "}\n";

    /** 边缘高光：按 angle 方向的梯度取 pow(abs(dot), falloff)，越靠边越亮。 */
    static final String HIGHLIGHT =
            "uniform float2 size;\n"
                    + "uniform float4 cornerRadii;\n"
                    + "layout(color) uniform half4 color;\n"
                    + "uniform float angle;\n"
                    + "uniform float falloff;\n"
                    + SDF
                    + "half4 main(float2 coord) {\n"
                    + "    float2 halfSize = size * 0.5;\n"
                    + "    float2 centeredCoord = coord - halfSize;\n"
                    + "    float radius = radiusAt(coord, cornerRadii);\n"
                    + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
                    + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
                    + "    float2 normal = float2(cos(angle), sin(angle));\n"
                    + "    float d = dot(grad, normal);\n"
                    + "    float intensity = pow(abs(d), falloff);\n"
                    + "    return color * intensity;\n"
                    + "}\n";

    /** 是否支持 RuntimeShader（AGSL）：API 33 起。 */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }
}
