package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * 阶段 3: 自动色阶 (Auto Levels)。
 * 亮度域 p1/p99 → [0.01, 0.97] 线性拉伸 (保留 1% 余量防 clipping),
 * per-channel 加权比缩放保护色相 (同 ShadowHighlight 的 v-ratio 法)。
 */
public final class AutoLevels implements Stage {
    private static final float LO_IN = 0.01f, HI_IN = 0.99f;
    private static final float LO_OUT = 0.005f, HI_OUT = 0.965f;

    @Override
    public String apply(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p1 = Stats.percentile(L, LO_IN);
        float p99 = Stats.percentile(L, HI_IN);
        float span = p99 - p1;
        if (span <= 0.5f) return "autolevels: 恒定图像, 跳过";

        float gain = (HI_OUT - LO_OUT) / Math.max(0.2f, span);
        // 收敛保护: 增益过大 (>3.2x) 说明原图接近单色, 限制防噪声爆炸
        gain = Math.min(gain, 3.2f);
        float offset = LO_OUT - p1 * gain;

        float[] newL = new float[n];
        for (int i = 0; i < n; i++) {
            newL[i] = Math.max(0f, Math.min(1f, L[i] * gain + offset));
        }
        if (Math.abs(gain - 1f) < 0.02f) return "autolevels: 已达标, 跳过";

        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                float base = Math.max(1e-4f, L[i]);
                d[i] = Math.max(0f, Math.min(1f, d[i] * (newL[i] / base)));
            }
        }
        return String.format("autolevels: gain=%.2f (p1=%.3f p99=%.3f)", gain, p1, p99);
    }
}
