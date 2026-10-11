package com.zeus.landscape;

/**
 * 色彩空间与色彩操作: 亮度 (Rec.709), RGB↔HSV, RGB↔线性RGB。
 * 风景管线在亮度/HSV 域操作以保护色相结构（8bit 串级 RGB 运算易产生色偏漂移）。
 */
public final class ColorOps {
    private ColorOps() {}

    /** Rec.709 亮度 (0..1) */
    public static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    public static float[] lumaPlane(FloatImage img) {
        float[] L = new float[img.size()];
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        for (int i = 0; i < L.length; i++) {
            L[i] = luma(R[i], G[i], B[i]);
        }
        return L;
    }

    /** sRGB → 线性 RGB (gamma 2.2 近似; 用于需要物理混合的运算) */
    public static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** 线性 RGB → sRGB */
    public static float linearToSrgb(float v) {
        return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
    }

    /** RGB→HSV。h ∈ [0,360), s/v ∈ [0,1]。返回复用缓冲或新数组。 */
    public static float[] rgbToHsv(float r, float g, float b, float[] out) {
        float mx = Math.max(r, Math.max(g, b));
        float mn = Math.min(r, Math.min(g, b));
        float d = mx - mn;
        float h;
        if (d < 1e-7f) {
            h = 0;
        } else if (mx == r) {
            h = 60f * (((g - b) / d) % 6f);
        } else if (mx == g) {
            h = 60f * ((b - r) / d + 2f);
        } else {
            h = 60f * ((r - g) / d + 4f);
        }
        if (h < 0) h += 360f;
        float s = mx < 1e-7f ? 0 : d / mx;
        if (out == null) out = new float[3];
        out[0] = h;
        out[1] = s;
        out[2] = mx;
        return out;
    }

    /** HSV→RGB。写入 out[0..2]。 */
    public static void hsvToRgb(float h, float s, float v, float[] out) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60f) % 2f - 1));
        float m = v - c;
        float r, g, b;
        if (h < 60)      { r = c; g = x; b = 0; }
        else if (h < 120){ r = x; g = c; b = 0; }
        else if (h < 180){ r = 0; g = c; b = x; }
        else if (h < 240){ r = 0; g = x; b = c; }
        else if (h < 300){ r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        out[0] = r + m;
        out[1] = g + m;
        out[2] = b + m;
    }

    /** 单像素色相 (0-360), 热路径免数组 */
    public static float hueOf(float r, float g, float b) {
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float d = mx - mn;
        if (d < 1e-6f) return 0f;
        float h;
        if (mx == r) h = ((g - b) / d) % 6f;
        else if (mx == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    /** 色相环距离: min(|a-b|, 360-|a-b|) */
    public static float hueDist(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    // ===== v2: 感知正确的色彩域 (自然度的根基) =====

    /** sRGB → linear (精确 EOTF)。所有亮度/对比/融合运算必须在 linear 域 ——
     *  sRGB 域直接运算会产生中间调失真与不自然的暗部 (v1 的教训)。 */
    public static float srgb2lin(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** linear → sRGB (精确 OETF) */
    public static float lin2srgb(float v) {
        v = v < 0f ? 0f : v;
        return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
    }

    /** sRGB LUT 化线性转换 (热路径, 1024 级) */
    public static float[] srgb2linLut() {
        float[] lut = new float[1025];
        for (int i = 0; i <= 1024; i++) lut[i] = srgb2lin(i / 1024f);
        return lut;
    }

    /**
     * OKLab: 感知均匀色彩空间 (Ottosson 2020, 公共贡献)。
     * L: 明度, a/b: 色度。饱和度调整在 C=√(a²+b²) 维度做 → hue 完全不漂移,
     * 比 HSV 饱和 (v1) 自然得多 —— HSV 调饱和会改变感知亮度产生荧光感。
     */
    public static void rgbToOkLab(float r, float g, float b, float[] out) {
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;
        float l_ = (float) Math.cbrt(l);
        float m_ = (float) Math.cbrt(m);
        float s_ = (float) Math.cbrt(s);
        out[0] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        out[1] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        out[2] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;
    }

    public static void okLabToRgb(float L, float a, float b_, float[] out) {
        float l_ = L + 0.3963377774f * a + 0.2158037573f * b_;
        float m_ = L - 0.1055613458f * a - 0.0638541728f * b_;
        float s_ = L - 0.0894841775f * a - 1.2914855480f * b_;
        float l = l_ * l_ * l_;
        float m = m_ * m_ * m_;
        float s = s_ * s_ * s_;
        out[0] = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
    }

    /**
     * Filmic tone curve (ACES 近似, Hill/Narkowicz): 电影工业级的自然对比。
     * 相比线性 S 曲线: 暗部 toe 平滑 (胶片感), 高光肩部软滚降 (无死白),
     * 中间调接近线性 (不破坏反差) —— 这是"直出自然感"的最后一块拼图。
     */
    public static float filmic(float x) {
        // ACES approx (Narkowicz 2015), 归一化到 [0,1]
        float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
        float y = (x * (a * x + b)) / (x * (c * x + d) + e);
        // 归一化: f(1.0)=0.80 → 缩放到 1.0 出
        return y * 1.2485f;
    }

    /** 数组版 linear→sRGB (统计用) */
    public static float[] lumaToSrgb(float[] lin) {
        float[] out = new float[lin.length];
        for (int i = 0; i < lin.length; i++) out[i] = lin2srgb(lin[i]);
        return out;
    }
}