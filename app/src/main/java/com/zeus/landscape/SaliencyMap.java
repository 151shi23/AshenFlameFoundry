package com.zeus.landscape;

/**
 * 显著度图 (Saliency Map) —— v3 新增核心算法: 计划层从"统计规则"升级为"感知驱动"。
 *
 * 文献: Itti, Koch, Niebur, "A Model of Saliency-Based Visual Attention for
 *   Rapid Scene Analysis", IEEE PAMI 1998 (简化实现);
 *   Achanta et al., "Saliency Detection using Maximum Symmetric Surround", CVPR 2012。
 *
 * 视觉注意力机制: 人眼看风景时不是均匀分配注意力 ——
 *   ① 高对比区域 (山脊线/岩石纹理) 显著
 *   ② 色彩与众不同的区域 (绿谷中的红屋顶) 显著
 *   ③ 与周边差异大的区域 (雾中孤树) 显著
 * 把显著度调制进 EnhancePlan: 显著区域获得更高的 clarity/saturation/exposure
 * 预算, 平坦区域 (天空/大面积水面) 少动 —— "把增强花在人眼会看的地方"。
 * 这是大疆级直出观感的隐形要素: 整图均匀增强 = 没有视觉焦点 = 平淡。
 */
public final class SaliencyMap {

    private SaliencyMap() {}

    /**
     * 在 L1 tile 网格上计算显著度 (0~1), 结果写回 grid.detailWeight 语义的独立数组。
     * 三因子: 对比显著 × 色彩稀有 × 局部独特性 (与周边 tile 的差异)。
     */
    public static float[] compute(MultiScaleAnalysis.TileGrid l1, MultiScaleAnalysis.TileGrid l2) {
        int gw = l1.gw, gh = l1.gh, n = gw * gh;
        float[] sal = new float[n];

        // ---- 因子1: 对比显著 (tile 内对比度的全局归一) ----
        float cMax = 1e-5f;
        for (float v : l1.contrast) cMax = Math.max(cMax, v);

        // ---- 因子2: 色彩稀有度 (tile 平均色相在全局直方图中的稀缺性) ----
        int[] hueHist = new int[12];
        float[][] hueOf = new float[n][];
        // 每 tile 的主导色相 (用 rbBias 近似冷暖 + sat 加权, 避免逐像素 HSV 的开销:
        // 用 L1 已有统计: sat>0.15 的 tile 按 (r-b) 符号分冷暖 12 类)
        for (int t = 0; t < n; t++) {
            float warmth = l1.rbBias[t];
            float satW = l1.sat[t];
            int bucket = satW < 0.12f ? 0
                    : warmth < 0.75f ? 1
                    : warmth < 0.9f ? 2
                    : warmth < 1.02f ? 3
                    : warmth < 1.15f ? 4 : 5;
            hueHist[bucket]++;
            hueOf[t] = new float[]{bucket, satW};
        }

        // ---- 因子3: 独特性 (与 5×5 邻域的亮度/饱和差异) ----
        float[] uniq = new float[n];
        for (int ty = 0; ty < gh; ty++) {
            for (int tx = 0; tx < gw; tx++) {
                int t = ty * gw + tx;
                float dL = 0, dS = 0;
                int cnt = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int y = ty + dy, x = tx + dx;
                        if (y < 0 || y >= gh || x < 0 || x >= gw || (dx == 0 && dy == 0)) continue;
                        int q = y * gw + x;
                        dL += Math.abs(l1.meanLum[t] - l1.meanLum[q]);
                        dS += Math.abs(l1.sat[t] - l1.sat[q]);
                        cnt++;
                    }
                }
                uniq[t] = cnt > 0 ? (dL / cnt) * 2.2f + (dS / cnt) : 0f;
            }
        }
        float uMax = 1e-5f;
        for (float v : uniq) uMax = Math.max(uMax, v);

        // ---- 融合 (Itti 归一化算子 N(): 除以自身最大值后平方抑制弥散) ----
        float cSMax = 0, uSMax = 0;
        float[] cS = new float[n], uS = new float[n];
        for (int t = 0; t < n; t++) {
            cS[t] = (l1.contrast[t] / cMax);
            cS[t] = cS[t] * cS[t];                       // 平方: 抑制低对比弥散
            float rarity = hueOf[t][0] == 0 ? 0.25f
                    : 1f - Math.min(1f, hueHist[(int) hueOf[t][0]] / (float) Math.max(1, n) * 4f);
            uS[t] = uniq[t] / uMax;
            uS[t] = uS[t] * uS[t] * (0.55f + 0.45f * rarity);
            cSMax = Math.max(cSMax, cS[t]);
            uSMax = Math.max(uSMax, uS[t]);
        }
        // L2 语义平滑: 显著度不该 tile 级跳变, 用粗网格 majority 支撑
        for (int t = 0; t < n; t++) {
            int tx2 = Math.min(l2.gw - 1, (t % gw) * l2.gw / gw);
            int ty2 = Math.min(l2.gh - 1, (t / gw) * l2.gh / gh);
            float regionBias = l2.skyProb[ty2 * l2.gw + tx2];
            float base = 0.45f * (cS[t] / Math.max(1e-5f, cSMax)) + 0.55f * (uS[t] / Math.max(1e-5f, uSMax));
            // 天空/平坦大区域显著度压低 (增强预算让给主体)
            sal[t] = base * (1f - 0.55f * Math.min(1f, regionBias * 1.6f));
        }
        // 归一 [0.15, 1]
        float sMax = 1e-5f;
        for (float v : sal) sMax = Math.max(sMax, v);
        for (int t = 0; t < n; t++) {
            sal[t] = 0.15f + 0.85f * (sal[t] / sMax);
        }
        return sal;
    }
}
