package com.zeus.landscape;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.Stats;

/**
 * 质量指标: 增强前后的客观量化 (写入处理报告)。
 *
 * - hazeIndex  : 暗通道均值 — 雾感/通透度核心指标 (↓越好)
 * - contrast   : 亮度 p95-p5
 * - entropy    : 信息熵 (细节丰富度)
 * - sharpness  : Laplacian 能量 (归一化清晰度)
 * - satMean    : 平均饱和度
 */
public final class QualityMetrics {
    public final float hazeIndex, contrast, entropy, sharpness, satMean;

    public QualityMetrics(FloatImage img) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);

        // 暗通道均值 (5x5 简化窗口采样, 指标用途无需 15x15)
        float[] min3 = new float[n];
        for (int i = 0; i < n; i++) {
            min3[i] = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
        }
        // 降采样求 min-filter (速度)
        int w = img.width, h = img.height;
        double dcSum = 0;
        int cnt = 0;
        for (int y = 2; y < h - 2; y += 4) {
            for (int x = 2; x < w - 2; x += 4) {
                float m = 1f;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        m = Math.min(m, min3[(y + dy) * w + x + dx]);
                dcSum += m;
                cnt++;
            }
        }
        hazeIndex = cnt > 0 ? (float) (dcSum / cnt) : 0f;

        contrast = Stats.percentile(L, 0.95f) - Stats.percentile(L, 0.05f);
        entropy = Stats.entropy(L);
        satMean = meanSaturation(img);
        sharpness = laplacianEnergy(L, w, h);
    }

    private float meanSaturation(FloatImage img) {
        float s = 0;
        float[] hsv = new float[3];
        int n = img.size();
        for (int i = 0; i < n; i++) {
            ColorOps.rgbToHsv(img.data[0][i], img.data[1][i], img.data[2][i], hsv);
            s += hsv[1];
        }
        return s / n;
    }

    /** Laplacian 能量 (归一化) — 清晰度代理指标 */
    private float laplacianEnergy(float[] L, int w, int h) {
        double e = 0;
        long c = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                int i = y * w + x;
                float lap = 4 * L[i] - L[i - 1] - L[i + 1] - L[i - w] - L[i + w];
                e += lap * lap;
                c++;
            }
        }
        return c > 0 ? (float) Math.min(1.0, e / c) : 0f;
    }

    public String diff(QualityMetrics before) {
        return String.format(
                "雾感 %.3f→%.3f (%.0f%%) | 对比 %.3f→%.3f | 熵 %.3f→%.3f | 清晰 %.4f→%.4f | 饱和 %.3f→%.3f",
                before.hazeIndex, hazeIndex, pct(before.hazeIndex, hazeIndex),
                before.contrast, contrast,
                before.entropy, entropy,
                before.sharpness, sharpness,
                before.satMean, satMean);
    }

    private static float pct(float a, float b) {
        return Math.abs(a) < 1e-6f ? 0 : (b - a) / a * 100f;
    }

    public String toJson() {
        return String.format(
                "{\"hazeIndex\":%.4f,\"contrast\":%.4f,\"entropy\":%.4f,\"sharpness\":%.5f,\"satMean\":%.4f}",
                hazeIndex, contrast, entropy, sharpness, satMean);
    }
}
