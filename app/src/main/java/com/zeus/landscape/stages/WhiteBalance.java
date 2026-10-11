package com.zeus.landscape.stages;

import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 1: 自动白平衡 —— 3×3 分块中位数 shades-of-gray。
 *
 * 依据前序打样实证 (research/smart_filter):
 * - 经典灰世界 (p=1) 被大色块支配: 大面积湖面/岩石会把全图拉向其补色
 * - 高阶 p-norm 在 clip 边界迭代发散
 * - 分块独立估计 + 中位数聚合: 稀释大色块, 保留真实色偏方向, 最稳
 *
 * 增益限幅 ±10%/轮: 风景照的色偏通常是色温级 (±8%), 限幅防过冲。
 * 中性目标带 [0.96, 1.04]: v8.2 收紧 (1.10 暖容差被实测证伪——三层暖叠加系统性偏黄)。
 */
public final class WhiteBalance implements Stage {
    private static final int ITERS = 3;
    private static final float MAX_GAIN = 1.10f;
    private static final float MIN_GAIN = 0.92f;

    @Override
    public String apply(FloatImage img) {
        int w = img.width, h = img.height;
        float rb = 1f;
        for (int it = 0; it < ITERS; it++) {
            rb = blockRbMedian(img);
            if (rb >= 0.96f && rb <= 1.04f) break;   // v8.2: 中性带 (1.10 的暖容差是偏黄根因)
            float gR = (1f / (float) Math.sqrt(rb)) * 0.5f + 0.5f;
            float gB = ((float) Math.sqrt(rb)) * 0.5f + 0.5f;
            gR = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gR));
            gB = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gB));
            scale(img, 0, gR);
            scale(img, 2, gB);
        }
        return String.format("wb: 分块中位数 R/B=%.3f (增益限幅±10%%)", rb);
    }

    /** 3×3 分块, 每块 p=3 Minkowski 范数估计 R/B, 取中位数 */
    private float blockRbMedian(FloatImage img) {
        int w = img.width, h = img.height;
        List<Float> rbs = new ArrayList<>(9);
        for (int by = 0; by < 3; by++) {
            for (int bx = 0; bx < 3; bx++) {
                int x0 = bx * w / 3, x1 = (bx + 1) * w / 3;
                int y0 = by * h / 3, y1 = (by + 1) * h / 3;
                double sr = 0, sb = 0;
                long n = 0;
                for (int y = y0; y < y1; y++) {
                    int base = y * w;
                    for (int x = x0; x < x1; x++) {
                        float r = img.data[0][base + x];
                        float b = img.data[2][base + x];
                        sr += Math.pow(r, 3);
                        sb += Math.pow(b, 3);
                        n++;
                    }
                }
                double rp = Math.pow(sr / Math.max(1, n), 1.0 / 3);
                double bp = Math.pow(sb / Math.max(1, n), 1.0 / 3);
                rbs.add((float) ((rp + 1e-6) / (bp + 1e-6)));
            }
        }
        float[] arr = new float[rbs.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = rbs.get(i);
        return Stats.median(arr);
    }

    private void scale(FloatImage img, int c, float g) {
        float[] d = img.data[c];
        for (int i = 0; i < d.length; i++) {
            d[i] = Math.max(0f, Math.min(1.5f, d[i] * g));
        }
    }
}
