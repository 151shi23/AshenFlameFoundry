package com.zeus.landscape.stages;

import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * CLAHE (Contrast Limited Adaptive Histogram Equalization)。
 * 文献: Zuiderveld, "Contrast Limited Adaptive Histogram Equalization", 1994 (Graphics Gems IV)。
 *
 * 实现要点:
 *  - tile × tile 独立直方图, clip 后余量均匀摊回 (限制噪声放大)
 *  - 像素映射时对 4 个相邻 tile 的映射函数做双线性插值 (消除 tile 边界缝)
 *  - 作用于亮度通道 (Rec.709 luma), 不碰色度 → 无色偏
 *
 * 与全局 tone mapping 的配合: CLAHE 管"局部层次" (岩石纹理/树影细节),
 * 全局 S 曲线管"整体对比"。两者正交。
 */
public final class Clahe {

    /**
     * @param lum        亮度平面 [0,1] (会被原地修改)
     * @param w,h        尺寸
     * @param tilesX/Y   tile 网格 (典型 8×8)
     * @param clipFactor clip 极限系数 (典型 2~4): 每桶上限 = factor * tile像素数/256
     */
    public static void apply(float[] lum, int w, int h, int tilesX, int tilesY, float clipFactor) {
        int tw = w / tilesX, th = h / tilesY;
        if (tw < 8 || th < 8) return;

        // 1) 每 tile 构建映射 LUT
        float[][] luts = new float[tilesX * tilesY][256];
        int tilePixels = tw * th;
        int clipLimit = Math.max(1, (int) (clipFactor * tilePixels / 256f));

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int[] hist = new int[256];
                for (int y = ty * th; y < (ty + 1) * th; y++) {
                    int base = y * w;
                    for (int x = tx * tw; x < (tx + 1) * tw; x++) {
                        hist[Math.min(255, (int) (lum[base + x] * 255f))]++;
                    }
                }
                // clip + 摊回
                int excess = 0;
                for (int b = 0; b < 256; b++) {
                    if (hist[b] > clipLimit) {
                        excess += hist[b] - clipLimit;
                        hist[b] = clipLimit;
                    }
                }
                int bonus = excess / 256;
                int rem = excess - bonus * 256;
                for (int b = 0; b < 256; b++) hist[b] += bonus;
                for (int b = 0; b < rem; b++) hist[b]++;

                // CDF → LUT
                float scale = 1f / tilePixels;
                float acc = 0;
                for (int b = 0; b < 256; b++) {
                    acc += hist[b];
                    luts[ty * tilesX + tx][b] = Math.min(1f, acc * scale);
                }
            }
        }

        // 2) 双线性插值映射
        float cx = (float) tw / 2f, cy = (float) th / 2f;
        for (int y = 0; y < h; y++) {
            float gy = (y - cy) / (float) th;           // tile 坐标 (浮点)
            int ty0 = clampi((int) Math.floor(gy), 0, tilesY - 1);
            int ty1 = Math.min(tilesY - 1, ty0 + 1);
            float fy = Math.max(0f, Math.min(1f, gy - ty0));
            for (int x = 0; x < w; x++) {
                float gx = (x - cx) / (float) tw;
                int tx0 = clampi((int) Math.floor(gx), 0, tilesX - 1);
                int tx1 = Math.min(tilesX - 1, tx0 + 1);
                float fx = Math.max(0f, Math.min(1f, gx - tx0));

                int bin = Math.min(255, (int) (lum[y * w + x] * 255f));
                float v00 = luts[ty0 * tilesX + tx0][bin];
                float v01 = luts[ty0 * tilesX + tx1][bin];
                float v10 = luts[ty1 * tilesX + tx0][bin];
                float v11 = luts[ty1 * tilesX + tx1][bin];
                float top = v00 + (v01 - v00) * fx;
                float bot = v10 + (v11 - v10) * fx;
                lum[y * w + x] = top + (bot - top) * fy;
            }
        }
    }

    private static int clampi(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
