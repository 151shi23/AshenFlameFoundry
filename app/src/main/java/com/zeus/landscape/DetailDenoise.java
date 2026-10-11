package com.zeus.landscape;

/**
 * 保细节去噪 (Detail-Preserving Denoise) —— v3 新增核心算法。
 *
 * 解决的行业难题: 提亮阴影 / detail 增强 1.35x 时, 噪声与纹理一起放大。
 * 传统中值/高斯去噪连纹理一起抹掉 → "油画感" (另一种 AI 味)。
 *
 * 方法: detail 层软阈值收缩 (Wiener 风格) + 噪声水平估计。
 *   detail = L - guided(L, 中半径)   (已完成分离)
 *   噪声底 σ ≈ detail 绝对值的 20 分位 (平坦区的 detail = 纯噪声)
 *   detail' = detail · max(0, 1 - (σ/detail幅值)²·k)   —— 幅值远大于 σ 的纹理保留,
 *              幅值接近 σ 的噪声收缩 —— 信噪比加权, 纹理/噪声区别对待。
 *
 * 文献依据: Wiener 收缩思想 (Donoho soft-thresholding 的比例化版本);
 *   噪声估计用分位法 (Immerkær 2008 快速噪声估计的分位简化)。
 * 在提亮后的图上运行 (噪声已被放大后才可见/可估), 这是正确时序。
 */
public final class DetailDenoise {

    private DetailDenoise() {}

    /** @param detail detail 平面 (原地收缩) @param strength [0,1] */
    public static void shrink(float[] detail, float strength) {
        int n = detail.length;
        // 噪声水平估计: |detail| 的 25 分位 ≈ 平坦区噪声幅值
        float[] abs = new float[n];
        for (int i = 0; i < n; i++) abs[i] = Math.abs(detail[i]);
        float sigma = percentile(abs, 0.25f);
        if (sigma < 1e-5f) return;

        float k = 2.2f * strength;   // 收缩力度
        for (int i = 0; i < n; i++) {
            float a = Math.abs(detail[i]);
            // 信噪比收缩: a >> σ 保持, a ≈ σ 收缩到 0
            float w = Math.max(0f, 1f - (sigma * k / Math.max(1e-6f, a)) * (sigma * k / Math.max(1e-6f, a)) * 0.5f);
            detail[i] *= w;
        }
    }

    private static float percentile(float[] v, float q) {
        float[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (q * c.length))];
    }
}
