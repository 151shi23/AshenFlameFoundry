package com.zeus.landscape;

/**
 * Base/Detail 分离色调映射 (v2 核心, 替代 v1 CLAHE 与失败的合成曝光融合)。
 *
 * 文献: Durand & Dorsey, "Fast Bilateral Filtering for the Display of
 *   High-Dynamic-Range Images", SIGGRAPH 2002 —— 保细节局部色调映射的经典范式。
 * 实现用引导滤波替代双边滤波 (He ECCV 2010): O(N)、无 halo、参数稳定。
 *
 * 原理 (为什么它自然):
 *   L = base + detail
 *   base  = 引导滤波(L, 大半径, 小eps) —— 大尺度明暗框架 (天空亮/山体暗)
 *   detail= L - base —— 纹理/噪声/渐变细节 (感知上"画质"的本体)
 * 只对 base 做亮度重分布 (阴影框架提亮/高光框架压缩), detail 以 1.0~1.15 放回:
 *  - 纹理与噪声原样保留 → 无"油画感/塑料感"
 *  - 渐变不被直方图重映射破坏 → 天空/水面无 banding
 *  - base 是低频 → 无 halo
 * v1 的 CLAHE (直方图重映射) 与合成曝光融合 (把一切拉向中灰) 均违反此原则, 已废弃。
 */
public final class BaseDetailTone {

    private BaseDetailTone() {}

    /**
     * @param lin linear 域 RGB (原地修改)
     * @param strength [0,1] 亮度重分布力度
     */
    public static void apply(float[][] lin, int w, int h, float strength) {
        int n = lin[0].length;
        float[] L = new float[n];
        for (int i = 0; i < n; i++) {
            L[i] = ColorOps.luma(lin[0][i], lin[1][i], lin[2][i]);
        }
        // 全局中位 (linear): filmic 的归一化锚点
        float p50 = Stats.percentile(ColorOps.lumaToSrgb(L), 0.5f);
        float anchor = Math.max(0.04f, ColorOps.srgb2lin(p50));

        // base: 大半径引导滤波 (半径 = 短边 1/4: 只保留"场景级"明暗框架)
        int r = Math.max(30, Math.min(w, h) / 4);
        GuidedFilter gf = new GuidedFilter(L, w, h, r, 0.03f);
        float[] base = gf.filter(L);
        float[] detail = new float[n];
        for (int i = 0; i < n; i++) detail[i] = L[i] - base[i];

        // base 域亮度重分布: 围绕锚点的 filmic 重映射 (shadow 框架提亮, highlight 框架软压)
        float lo = 1e-4f, hi = 1e-4f;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, base[i]);
            hi = Math.max(hi, base[i]);
        }
        float targetHi = 0.85f * anchor + hi * 0.15f;   // 框架高光压到锚点的 ~4.7 倍以内
        float targetLo = lo + (0.30f * anchor - lo) * 0.32f * strength; // 框架阴影提亮 (收敛防亮斑)

        float gainHi = Math.min(1.6f, targetHi / Math.max(1e-4f, hi));
        float gainLo = Math.min(1.5f, targetLo / Math.max(1e-4f, lo));
        // 分段增益 + 黑位锚定: v3 关键 —— 提亮阴影的同时把框架黑位压住 (v2 发灰根因)
        float[] newBase = new float[n];
        float blackFloor = anchor * 0.045f;   // 框架黑位: 锚点的 4.5% (linear)
        for (int i = 0; i < n; i++) {
            float b = Math.max(0f, base[i]);
            float wHi = Stats.smoothstep(anchor * 0.85f, hi, b);
            float g = gainLo + (gainHi - gainLo) * wHi;
            float nb = b * g;
            // 黑位压锚: 低于 floor 的框架更深 (透), 高于的不动 —— 软膝不切
            float over = Stats.smoothstep(blackFloor * 3f, blackFloor * 0.6f, nb);
            nb = nb * (1f - 0.35f * over);
            newBase[i] = nb;
        }

        // detail 放回: 实质性增强 (清晰度的主体)。ratio 必须基于 luma 统一 (保 hue)!
        // (教训: 分母写成逐通道值会把每个通道各自缩放到目标亮度 → 直接灰度化)
        // v3: 1.35x 中频增强 —— v2 的 1.1x 保守过头导致"变软变灰" (用户: 越改越烂)
        float dGain = 1f + 0.35f * strength;
        for (int i = 0; i < n; i++) {
            float newLum = newBase[i] + detail[i] * dGain;
            float ratio = newLum / Math.max(1e-4f, L[i]);
            for (int c = 0; c < 3; c++) {
                lin[c][i] = Math.max(0f, lin[c][i] * ratio);
            }
        }
    }
}
