package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * 阶段 7: 自然饱和度 (Vibrance)。
 *
 * 区别于全局饱和度 (Saturation): 低饱和像素增益大、高饱和像素增益小,
 * 单调收敛避免荧光化。风景特化:
 *  - 绿植保护: hue 70-160° ( foliage 区) 增益 ×0.55 — 自然绿过饱和立即假
 *  - 蓝天增强: hue 185-250° 增益 ×1.25 — 与 SkyEnhance 协同
 *  - 已过饱和区 (s>0.75) 增益趋 0
 *
 * 强度自适应: sat_mean<0.22 → 1.4x, >0.45 → 0.5x (已经鲜艳的图克制)。
 */
public final class Vibrance implements Stage {
    private float global = 1f;

    @Override
    public Stage strength(float g) { this.global = g; return this; }

    @Override
    public String apply(FloatImage img) {
        int n = img.size();
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        float satMean = 0;
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            ColorOps.rgbToHsv(R[i], G[i], B[i], hsv);
            satMean += hsv[1];
        }
        satMean /= n;

        float amount = satMean < 0.22f ? 0.85f
                : satMean < 0.32f ? 0.55f
                : satMean < 0.45f ? 0.35f
                : 0.15f;
        amount *= global;

        for (int i = 0; i < n; i++) {
            ColorOps.rgbToHsv(R[i], G[i], B[i], hsv);
            float hue = hsv[0], sat = hsv[1], val = hsv[2];
            // 低饱和大增益, 高饱和小增益 (线性权重 + 过饱和截断)
            float weight = (1f - Math.min(1f, sat / 0.85f));
            float gain = 1f + amount * weight;
            // 波段保护/增强
            if (hue >= 70f && hue <= 160f) gain = 1f + (gain - 1f) * 0.55f;   // 绿植
            else if (hue >= 185f && hue <= 250f) gain = 1f + (gain - 1f) * 1.25f; // 蓝天
            sat = Math.min(1f, sat * gain);
            ColorOps.hsvToRgb(hue, sat, val, hsv);
            R[i] = hsv[0]; G[i] = hsv[1]; B[i] = hsv[2];
        }
        return String.format("vibrance: amount=%.2f (satMean=%.2f)", amount, satMean);
    }
}
