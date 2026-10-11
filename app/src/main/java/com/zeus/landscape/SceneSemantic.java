package com.zeus.landscape;

/**
 * 场景语义分区增强 (Scene Semantic Enhancement) —— v9 新增核心算法。
 *
 * 这是"统计增强 → 语义增强"的跃迁, 大疆直出味感的真正来源:
 * 此前所有阶段 (CLAHE/vibrance/对比) 对画面均匀作用; 而人眼/相机 ISP 的
 * 色彩科学是语义级的 —— 天空该青蓝、植被该翠绿、岩石该暖棕、水面该深沉。
 *
 * 语义分类 (手写, 无 ML, 纯物理/色彩先验, L1 网格粒度):
 *   sky      天空   : skyProb 高 (顶部连通蓝天, 复用 SaliencyMap 前的判定)
 *   veg      植被   : hue 70-160° + 饱和中等 + 纹理中等 (叶簇)
 *   water    水面   : 纹理极低 (镜面) 或 蓝青主导且亮度中低 (倒影)
 *   rock     岩地   : 低饱和 + 纹理高 (岩石/泥土/建筑)
 *   snow     雪峰   : 高亮度 + 低饱和 + 大块 (顶部区域)
 * 每 tile 主导语义 (soft 权重), 逐像素双线性插值 → 分区调色。
 *
 * 各语义的调色 (摄影后期惯例, 幅度保守):
 *   veg   : 绿相向 115° 标准叶绿素微收 + 明度对比 +6% (叶簇立体感)
 *   water : 亮度 -3% + 蓝青增强 +4% + 纹理 0.92 (镜面感)
 *   rock  : 暖化 (R+3% B-3%) + 纹理 1.12 (颗粒质感)
 *   sky/snow : 交给 SkyEnhance / GoldenHour (已有专责阶段)
 */
public final class SceneSemantic {

    private SceneSemantic() {}

    /** 语义权重图 (5 类, 各 n 像素), 由 L1 网格双线性上采样 */
    public static final int SKY = 0, VEG = 1, WATER = 2, ROCK = 3, SNOW = 4;

    public static float[][] computeMaps(FloatImage img, MultiScaleAnalysis.TileGrid l1) {
        int w = img.width, h = img.height, n = img.size();
        int gw = l1.gw, gh = l1.gh;
        float[][] maps = new float[5][n];
        float[] hue = new float[n], sat = new float[n], lum = new float[n];
        for (int i = 0; i < n; i++) {
            ColorOps.rgbToHsv(img.data[0][i], img.data[1][i], img.data[2][i], TMP);
            hue[i] = TMP[0];
            sat[i] = TMP[1];
            lum[i] = ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]);
        }
        float detMax = 1e-5f;
        for (float v : l1.detail) detMax = Math.max(detMax, v);

        // 每 tile 语义 soft 权重
        for (int ty = 0; ty < gh; ty++) {
            for (int tx = 0; tx < gw; tx++) {
                int t = ty * gw + tx;
                float wSky = l1.skyProb[t];
                float det = l1.detail[t] / detMax;
                // 语义判定基于 tile 主导像素的色相: 采样 tile 中心区重算 hue 直方
                // (简化: 用 rbBias+sat+det 近似, 精确色相在逐像素加权时按像素 hue 判定)
                float wVeg = 0, wWater = 0, wRock = 0, wSnow = 0;
                if (l1.meanLum[t] > 0.80f && l1.sat[t] < 0.18f && wSky < 0.3f) wSnow = 0.8f;   // 亮白大块=雪
                if (det < 0.35f && wSky < 0.2f) wWater = 0.7f;                        // 超平滑=水面
                if (det > 0.45f && l1.sat[t] < 0.30f) wRock = 0.75f;                  // 高纹理低饱和=岩地
                wVeg = Math.max(0f, 1f - wSky - wSnow - wWater - wRock);              // 余量给植被
                float[] wts = {wSky, wVeg, wWater, wRock, wSnow};
                float s = 0;
                for (float v : wts) s += v;
                if (s < 1e-4f) { maps[ROCK][t] = 1f; continue; }
                for (int k = 0; k < 5; k++) maps[k][t] = wts[k] / s;
            }
        }
        // 上采样到像素 (双线性)
        float[][] px = new float[5][n];
        for (int k = 0; k < 5; k++) {
            for (int y = 0; y < h; y++) {
                float gy = Math.min(gh - 1.001f, (y + 0.5f) * gh / (float) h);
                int y0 = (int) gy, y1c = Math.min(gh - 1, y0 + 1);
                float fy = gy - y0;
                for (int x = 0; x < w; x++) {
                    float gx = Math.min(gw - 1.001f, (x + 0.5f) * gw / (float) w);
                    int x0 = (int) gx, x1c = Math.min(gw - 1, x0 + 1);
                    float fx = gx - x0;
                    float v00 = maps[k][y0 * gw + x0], v01 = maps[k][y0 * gw + x1c];
                    float v10 = maps[k][y1c * gw + x0], v11 = maps[k][y1c * gw + x1c];
                    px[k][y * w + x] = (v00 + (v01 - v00) * fx) * (1 - fy)
                            + (v10 + (v11 - v10) * fx) * fy;
                }
            }
        }
        return px;
    }

    private static final float[] TMP = new float[3];

    /** 分区调色 (逐像素, 语义权重混合) */
    public static void enhance(FloatImage img, float[][] maps) {
        int n = img.size();
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        for (int i = 0; i < n; i++) {
            float wVeg = maps[VEG][i], wWater = maps[WATER][i], wRock = maps[ROCK][i];
            if (wVeg + wWater + wRock < 0.15f) continue;   // 天空/雪由专责阶段处理
            float r = R[i], g = G[i], b = B[i];
            float hue = ColorOps.hueOf(r, g, b);

            // ---- 植被: 绿相收拢 + 明度对比 ----
            if (wVeg > 0.15f && hue >= 60f && hue <= 170f) {
                float w = wVeg * (1f - Math.abs(hue - 115f) / 60f);
                if (w > 0) {
                    hue += (115f - hue) * 0.18f * w;                      // 绿相标准化
                    float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    float v = (mx + mn) / 2f;
                    float nv = v + (v - 0.42f) * 0.10f * w;               // 明度微对比 (锚 0.42)
                    float sc = nv / Math.max(1e-4f, v);
                    r *= sc; g *= sc; b *= sc;
                }
            }
            // ---- 水面: 沉+蓝青 + 镜面感 (纹理抑制由 texturePass 语义版承担, 这里做色彩) ----
            if (wWater > 0.15f) {
                float w = wWater;
                b += 0.035f * w * (1f - b);
                r -= 0.022f * w * r;
                float gray = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                gray *= 0.975f;
                float sc = gray / Math.max(1e-4f, ColorOps.luma(r, g, b));
                r *= sc; g *= sc; b *= sc;
            }
            // ---- 岩地: 暖化 + 颗粒 (清晰度由 detail 链路) ----
            if (wRock > 0.15f) {
                float w = wRock;
                r += 0.030f * w * (1f - r);
                b -= 0.028f * w * b;
            }
            R[i] = Math.max(0f, Math.min(1f, r));
            G[i] = Math.max(0f, Math.min(1f, g));
            B[i] = Math.max(0f, Math.min(1f, b));
        }
    }
}
