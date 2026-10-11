package com.zeus.landscape;

import java.util.Arrays;

/**
 * 基础滤波器族, 全部 O(N) 或 O(N·k) 手写实现, 零第三方依赖。
 *
 * - boxFilter: 可分离滑动累积和 (O(N)), 半径按像素
 * - minFilter: van Herk/Gil-Werman O(N) 滑动窗口最小值 (DCP 暗通道核心)
 * - gaussianBlur: 三次 box 近似 (Wells 1986 / OpenCV 同款近似)
 *
 * 边界: 全部 clamp 到图像边缘 (replicate), 与 OpenCV BORDER_REPLICATE 语义一致。
 */
public final class Filters {
    private Filters() {}

    /** 可分离 box filter, 半径 r (实际窗口 2r+1)。输出新数组。 */
    public static float[] boxFilter(float[] src, int w, int h, int r) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];
        float inv = 1f / (2 * r + 1);
        // 水平
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float acc = 0;
            for (int k = -r; k <= r; k++) {
                int x = Math.min(w - 1, Math.max(0, k));
                acc += src[row + x];
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = acc * inv;
                int xAdd = Math.min(w - 1, x + r + 1);
                int xSub = Math.max(0, x - r);
                acc += src[row + xAdd] - src[row + xSub];
            }
        }
        // 垂直
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int k = -r; k <= r; k++) {
                int y = Math.min(h - 1, Math.max(0, k));
                acc += tmp[y * w + x];
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = acc * inv;
                int yAdd = Math.min(h - 1, y + r + 1);
                int ySub = Math.max(0, y - r);
                acc += tmp[yAdd * w + x] - tmp[ySub * w + x];
            }
        }
        return dst;
    }

    /**
     * van Herk/Gil-Werman O(N) 一维最小值滤波 + 可分离组合。
     * 文献: van Herk 1992 "A fast algorithm for local minimum and maximum filters"
     */
    public static float[] minFilter(float[] src, int w, int h, int r) {
        int n = src.length;
        float[] tmp = new float[n];
        float[] dst = new float[n];
        float[] blockMinH = horizontalMin(src, w, h, r);
        System.arraycopy(blockMinH, 0, tmp, 0, n);
        // 垂直 pass: 复用同一 1D 算法, 逐列
        int win = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            // 提取列
            float[] col = new float[h];
            for (int y = 0; y < h; y++) col[y] = tmp[y * w + x];
            float[] cmin = min1D(col, h, r, win);
            for (int y = 0; y < h; y++) dst[y * w + x] = cmin[y];
        }
        return dst;
    }

    private static float[] horizontalMin(float[] src, int w, int h, int r) {
        float[] dst = new float[src.length];
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            float[] row = new float[w];
            System.arraycopy(src, y * w, row, 0, w);
            float[] rmin = min1D(row, w, r, win);
            System.arraycopy(rmin, 0, dst, y * w, w);
        }
        return dst;
    }

    /** van Herk 1D min: 块内前向 min + 块后向 min, 窗口 min 取两者组合 */
    private static float[] min1D(float[] v, int n, int r, int win) {
        float[] out = new float[n];
        int blocks = (n + win - 1) / win;
        float[] fwd = new float[blocks * win];
        float[] bwd = new float[blocks * win];
        Arrays.fill(fwd, Float.MAX_VALUE);
        Arrays.fill(bwd, Float.MAX_VALUE);
        for (int b = 0; b < blocks; b++) {
            int start = b * win;
            float m = Float.MAX_VALUE;
            for (int i = 0; i < win; i++) {
                int idx = start + i;
                float val = idx < n ? v[idx] : Float.MAX_VALUE;
                m = Math.min(m, val);
                fwd[start + i] = m;
            }
            m = Float.MAX_VALUE;
            for (int i = win - 1; i >= 0; i--) {
                int idx = start + i;
                float val = idx < n ? v[idx] : Float.MAX_VALUE;
                m = Math.min(m, val);
                bwd[start + i] = m;
            }
        }
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - r);
            int hi = Math.min(n - 1, i + r);
            int bLo = lo / win, bHi = hi / win;
            if (bLo == bHi) {
                out[i] = v[lo];
                for (int k = lo; k <= hi; k++) out[i] = Math.min(out[i], v[k]);
            } else {
                float a = bwd[lo];                      // 从 lo 到本块末尾的最小值
                float b2 = fwd[hi];                     // 从 hi 所在块开头到 hi 的最小值
                out[i] = Math.min(a, b2);
            }
        }
        return out;
    }

    /** 三次 box 近似高斯 (sigma → 等效 box 半径, OpenCV getGaussianKernel 同思路) */
    public static float[] gaussianBlur(float[] src, int w, int h, float sigma) {
        if (sigma < 0.3f) return src.clone();
        int r = Math.max(1, (int) (sigma * Math.sqrt(12f / 3f) / 2f + 0.5f));
        float[] t = boxFilter(src, w, h, r);
        t = boxFilter(t, w, h, r);
        return boxFilter(t, w, h, r);
    }

    /** 双图逐像素 min (每通道 RGB 逐平面) */
    public static float[][] perPixelMin(float[][] a, float[][] b) {
        float[][] out = new float[3][];
        for (int c = 0; c < 3; c++) {
            out[c] = new float[a[c].length];
            for (int i = 0; i < a[c].length; i++) out[c][i] = Math.min(a[c][i], b[c][i]);
        }
        return out;
    }
}
