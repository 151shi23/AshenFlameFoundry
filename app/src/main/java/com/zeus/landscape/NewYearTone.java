package com.zeus.landscape;

/**
 * 新年红金色调 (New Year Red & Gold) —— 2027 新年定制核心算法。
 *
 * Python 网格扫描定版 (scan7_newyear.jpg, medium 档):
 *  ① 红相增强: hue 335-25° (灯笼/红元素) 饱和 ×1.18 + 明度 ×1.04 —— 灯笼更艳
 *  ② 红金分离色调:
 *     高光 → 金 (1.0, 0.83, 0.50)      暖光更金
 *     中间调 → 暖 (1.0, 0.88, 0.74)    节庆暖调
 *     阴影 → 深红褐 (0.45, 0.24, 0.20)  暖夜 (不是冷黑)
 *  ③ 夜空蓝保护: 蓝相像素不染红金 (权重 ×0.15) —— 红金 vs 蓝夜的
 *     经典新年海报配色对比得以保留。
 */
public final class NewYearTone {

    private NewYearTone() {}

    public static void apply(FloatImage img, float strength) {
        if (strength <= 0.01f) return;
        int n = img.size();
        // ---- ① 红相增强 (HSV 域) ----
        for (int i = 0; i < n; i++) {
            float r = img.data[0][i], g = img.data[1][i], b = img.data[2][i];
            float hue = ColorOps.hueOf(r, g, b);
            boolean redBand = (hue >= 335f || hue <= 25f);
            if (!redBand) continue;
            float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            float sv = mx > 1e-5f ? (mx - mn) / mx : 0f;
            if (sv < 0.25f) continue;                      // 低饱和灰区不碰
            float satGain = 1f + 0.22f * strength;
            float valGain = 1f + 0.05f * strength;
            float anchor = mx;
            img.data[0][i] = Math.max(0f, Math.min(1f, anchor + (r - anchor) * satGain) * valGain);
            img.data[1][i] = Math.max(0f, Math.min(1f, anchor + (g - anchor) * satGain) * valGain);
            img.data[2][i] = Math.max(0f, Math.min(1f, anchor + (b - anchor) * satGain) * valGain);
        }
        // ---- ② 红金分离色调 (灰度锚定, 蓝夜保护) ----
        float[] L = ColorOps.lumaPlane(img);
        for (int i = 0; i < n; i++) {
            float l = L[i];
            float hue = ColorOps.hueOf(img.data[0][i], img.data[1][i], img.data[2][i]);
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
            float sv = mx > 1e-5f ? (mx - mn) / mx : 0f;
            float blueM = (hue >= 190f && hue <= 260f && sv > 0.25f) ? 1f : 0f;
            float hiW = Stats.smoothstep(0.62f, 0.95f, l) * (1f - 0.85f * blueM);
            float midW = Math.max(0f, 1f - Math.abs(l - 0.5f) / 0.35f) * (1f - 0.5f * blueM);
            float loW = (float) Math.pow(Stats.smoothstep(0.35f, 0.03f, l), 1.2) * (1f - 0.4f * blueM);
            for (int c = 0; c < 3; c++) {
                float x = img.data[c][i];
                x += strength * 0.35f * hiW * (GOLD[c] - x) * 0.5f;
                x += strength * 0.5f * midW * (WARM[c] - x) * 0.4f;
                x += strength * 0.30f * loW * (DEEP[c] - x) * 0.4f;
                img.data[c][i] = Math.max(0f, Math.min(1f, x));
            }
        }
    }

    private static final float[] GOLD = {1.0f, 0.83f, 0.50f};
    private static final float[] WARM = {1.0f, 0.88f, 0.74f};
    private static final float[] DEEP = {0.45f, 0.24f, 0.20f};
}
