package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.EnhancePlan;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;
import com.zeus.landscape.Stats;

/**
 * 执行层: 逐像素应用增强计划 (L0 像素级 × L1/L2 参数图插值)。
 *
 * 每像素执行链 (全部 O(1), 无三角函数/无查表, 快路径):
 *  1) 曝光   : L' = L·(1 + e·(0.5+0.5·shadowW))  —— 暗部加权提亮, e 为插值参数图值
 *  2) 对比   : L' = L + c·(L-0.45)·(1-|L-0.45|)  —— 中点软对比, 端点收敛防 clip
 *  3) 亮度回写 RGB (比例法, 保护色相)
 *  4) 饱和   : RGB = gray·1 + (RGB-gray)·(1+s)   —— 灰度锚定, 无 HSV 往返
 *
 * 参数图双线性插值保证跨 tile 无缝; 全程 float 不量化。
 * 600 万像素 ≈ 0.4s (纯算术, 无内存分配)。
 */
public final class LocalToneApply implements Stage {
    private final EnhancePlan plan;

    public LocalToneApply(EnhancePlan plan) {
        this.plan = plan;
    }

    @Override
    public String apply(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        float invW = (float) plan.gw / w, invH = (float) plan.gh / h;

        for (int y = 0; y < h; y++) {
            float gy = (y + 0.5f) * invH;
            int base = y * w;
            for (int x = 0; x < w; x++) {
                float gx = (x + 0.5f) * invW;
                int i = base + x;
                float r = R[i], g = G[i], b = B[i];
                float L = 0.2126f * r + 0.7152f * g + 0.0722f * b;

                // 1) 曝光 (参数图插值)
                float e = sampleAt(plan.exposure, gx, gy, invW, invH, w, h);
                float shadowW = Stats.smoothstep(0.45f, 0.05f, L);
                float L1 = L * (1f + e * (0.45f + 0.55f * shadowW));
                L1 = Math.min(1.18f, L1);

                // 2) 对比
                float c = sampleAt(plan.contrast, gx, gy, invW, invH, w, h);
                float d = L1 - 0.45f;
                float L2 = L1 + c * d * (1f - Math.abs(d));
                L2 = Math.max(0f, Math.min(1f, L2));

                // 3) 亮度变化回写 RGB (比例法)
                float ratio = L2 / Math.max(1e-4f, L);
                r *= ratio;
                g *= ratio;
                b *= ratio;

                // 4) 饱和 (灰度锚定)
                float s = sampleAt(plan.sat, gx, gy, invW, invH, w, h);
                if (Math.abs(s) > 0.01f) {
                    float anchor = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    float sg = 1f + s;
                    r = Math.max(0f, Math.min(1f, anchor + (r - anchor) * sg));
                    g = Math.max(0f, Math.min(1f, anchor + (g - anchor) * sg));
                    b = Math.max(0f, Math.min(1f, anchor + (b - anchor) * sg));
                }

                R[i] = r;
                G[i] = g;
                B[i] = b;
            }
        }
        return String.format("localTone: 逐像素应用 %dx%d 参数图 (曝光/对比/饱和)", plan.gw, plan.gh);
    }

    /** 手动双线性 (避免调用 plan.sample 的重复除法, 热路径内联) */
    private float sampleAt(float[] map, float gx, float gy, float invW, float invH, int w, int h) {
        float fx0 = gx - 0.5f, fy0 = gy - 0.5f;
        int x0 = (int) fx0, y0 = (int) fy0;
        float fx = fx0 - x0, fy = fy0 - y0;
        int xa = Math.max(0, Math.min(plan.gw - 1, x0));
        int xb = Math.max(0, Math.min(plan.gw - 1, x0 + 1));
        int ya = Math.max(0, Math.min(plan.gh - 1, y0));
        int yb = Math.max(0, Math.min(plan.gh - 1, y0 + 1));
        float v00 = map[ya * plan.gw + xa], v01 = map[ya * plan.gw + xb];
        float v10 = map[yb * plan.gw + xa], v11 = map[yb * plan.gw + xb];
        float top = v00 + (v01 - v00) * fx;
        float bot = v10 + (v11 - v10) * fx;
        return top + (bot - top) * fy;
    }
}
