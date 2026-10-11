package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * 阶段 8: 全局色调曲线 (对比 S 曲线 + 微量色彩分级)。
 *
 * S 曲线: 亮度域, 对称平滑函数 (sin² 形), 中点固定不动, 两端收敛 →
 * 加对比不 clipping。强度自适应 (p95-p5 对比度低 → 强)。
 *
 * 色彩分级 (克制的 Pocket 风): 高光微暖 (+R), 阴影微冷 (-R, +B),
 * 强度 = contrastGain 的一小部分, 防止"网红滤镜"味。
 */
public final class ToneCurve implements Stage {
    private float global = 1f;

    @Override
    public Stage strength(float g) { this.global = g; return this; }

    @Override
    public String apply(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p5 = Stats.percentile(L, 0.05f);
        float p95 = Stats.percentile(L, 0.95f);
        float contrast = p95 - p5;

        // 自适应: 对比 <0.55 → 强 S; >0.85 → 几乎不动
        float amount = contrast < 0.55f ? 0.85f
                : contrast < 0.70f ? 0.55f
                : contrast < 0.85f ? 0.30f
                : 0.10f;
        amount *= global;

        // 亮度 S 曲线 LUT (1024 级)
        float[] lut = new float[1025];
        for (int i = 0; i <= 1024; i++) {
            float x = i / 1024f;
            // S: 对 x<0.5 压, x>0.5 提。用 sin 形: y = x - a*sin(2πx)/(2π)
            float y = x - amount * 0.28f * (float) Math.sin(2 * Math.PI * x) / (2f * (float) Math.PI) * 2f;
            lut[i] = Math.max(0f, Math.min(1f, y));
        }

        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        for (int i = 0; i < n; i++) {
            float l = L[i];
            float nl = lut[(int) (Math.max(0f, Math.min(1f, l)) * 1024f)];
            float base = Math.max(1e-4f, l);
            float ratio = nl / base;
            float r = R[i] * ratio, g = G[i] * ratio, b = B[i] * ratio;

            // 微量分级: 高光暖 (+R -B), 阴影冷 (-R +B), 幅度 ~ amount*0.02
            float hi = Stats.smoothstep(0.6f, 1.0f, l);
            float sh = 1f - Stats.smoothstep(0.0f, 0.4f, l);
            float grade = amount * 0.03f;
            r += grade * hi - grade * 0.6f * sh;
            b -= grade * hi * 0.7f - grade * sh;

            R[i] = Math.max(0f, Math.min(1f, r));
            G[i] = Math.max(0f, Math.min(1f, g));
            B[i] = Math.max(0f, Math.min(1f, b));
        }
        return String.format("tone: S=%.2f (contrast=%.2f) + 微分级", amount, contrast);
    }
}
