package com.zeus.landscape;

import java.util.Arrays;

/** 统计工具: 直方图 / 百分位 / 均值。全部基于 float[0,1] 平面数据。 */
public final class Stats {
    private Stats() {}

    /** 256-bin 直方图 */
    public static int[] histogram(float[] v) {
        int[] h = new int[256];
        for (float x : v) {
            int b = (int) (Math.max(0f, Math.min(0.999f, x)) * 256f);
            h[b]++;
        }
        return h;
    }

    /** 线性插值百分位 (0..1) */
    public static float percentile(float[] v, float q) {
        int[] h = histogram(v);
        long total = v.length;
        long target = (long) (q * total);
        long acc = 0;
        for (int b = 0; b < 256; b++) {
            acc += h[b];
            if (acc >= target) {
                return (b + 0.5f) / 256f;
            }
        }
        return 1f;
    }

    public static float mean(float[] v) {
        double s = 0;
        for (float x : v) s += x;
        return (float) (s / Math.max(1, v.length));
    }

    /** 熵 (8bit 灰度级, 单位 bit) */
    public static float entropy(float[] v) {
        int[] h = histogram(v);
        double e = 0;
        for (int c : h) {
            if (c > 0) {
                double p = (double) c / v.length;
                e -= p * (Math.log(p) / Math.log(2));
            }
        }
        return (float) e;
    }

    /** 中位数（对采样数组排序, 用于小样本） */
    public static float median(float[] v) {
        float[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) * 0.5f;
    }

    /** 平滑阶梯函数: 0 在 a 以下, 1 在 b 以上, 中间 smoothstep 过渡 */
    public static float smoothstep(float a, float b, float x) {
        if (b <= a) return x >= b ? 1f : 0f;
        float t = (x - a) / (b - a);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }
}
