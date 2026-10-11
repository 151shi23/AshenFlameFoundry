package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.Filters;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * 阶段 4: 阴影提升 + 高光压缩 (Shadow/Highlight Recovery)。
 *
 * 对标大疆/手机 HDR 直出的核心特征: 高光压得住 (云层层次), 暗部提得动 (岩石细节),
 * 同时保持中间调反差 (不发灰)。
 *
 * 方法: 亮度域双 mask 重映射 —
 *  - 暗部: L' = L + lift·(1-L)·smoothstep(0.30, 0.02, L)   (越暗提得越多)
 *  - 高光: L' = L - comp·L·smoothstep(0.72, 0.97, L)        (越亮压得越多)
 * mask 经大半径高斯模糊, 防止局部像素级过冲 (打样教训)。
 *
 * 强度自适应: 由直方图 p5 / p95 距目标带 (0.04 / 0.93) 的缺口决定, 有多少补多少。
 */
public final class ShadowHighlight implements Stage {
    private float liftAmount = -1f;   // -1 = auto
    private float compAmount = -1f;

    public ShadowHighlight auto() { return this; }
    public ShadowHighlight fixed(float lift, float comp) {
        this.liftAmount = lift;
        this.compAmount = comp;
        return this;
    }

    @Override
    public String apply(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p5 = Stats.percentile(L, 0.05f);
        float p95 = Stats.percentile(L, 0.95f);

        // 自适应: 缺口比例 (最大 0.32 lift / 0.30 comp)
        float lift = liftAmount >= 0 ? liftAmount
                : Math.max(0f, Math.min(0.32f, (0.06f - p5) * 2.2f));
        float comp = compAmount >= 0 ? compAmount
                : Math.max(0f, Math.min(0.30f, (p95 - 0.90f) * 2.5f));

        if (lift < 0.01f && comp < 0.01f) {
            return "shadow/highlight: 无需干预 (p5=" + f2(p5) + " p95=" + f2(p95) + ")";
        }

        // mask 模糊半径 ~ 图像 1/12
        int r = Math.max(20, Math.min(img.width, img.height) / 12);
        float[] shadowMask = new float[n];
        float[] hiMask = new float[n];
        for (int i = 0; i < n; i++) {
            // smoothstep 反向: 暗 → 1
            shadowMask[i] = 1f - Stats.smoothstep(0.02f, 0.30f, L[i]);
            hiMask[i] = Stats.smoothstep(0.72f, 0.97f, L[i]);
        }
        shadowMask = Filters.gaussianBlur(shadowMask, img.width, img.height, r * 0.5f);
        hiMask = Filters.gaussianBlur(hiMask, img.width, img.height, r * 0.5f);

        // 按 mask 重映射 L
        float[] newL = new float[n];
        for (int i = 0; i < n; i++) {
            float l = L[i];
            l = l + lift * (1f - l) * shadowMask[i];
            l = l - comp * l * hiMask[i];
            newL[i] = Math.max(0f, Math.min(1f, l));
        }

        // 亮度差按比例回 RGB (保护色相/饱和度; v 比例缩放法)
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                float base = Math.max(1e-4f, L[i]);
                float ratio = newL[i] / base;
                d[i] = Math.max(0f, Math.min(1f, d[i] * ratio));
            }
        }
        return String.format("shadow/lift=%.2f highlight/comp=%.2f (p5=%.2f→ p95=%.2f→)",
                lift, comp, p5, p95);
    }

    private static String f2(float v) { return String.format("%.2f", v); }
}
