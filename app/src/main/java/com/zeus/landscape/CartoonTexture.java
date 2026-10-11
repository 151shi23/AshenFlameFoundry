package com.zeus.landscape;

/**
 * 结构-纹理分解 (Cartoon-Texture Decomposition) —— v5 新增核心算法。
 *
 * 文献: Rudin, Osher, Fatemi, "Nonlinear Total Variation based Noise Removal",
 *   Physica D 1992 (分解思想的源头);
 *   Xu, Zheng, Jia, "Structure-Texture Image Decomposition", CVPR 2011 (实用化);
 *   实现用引导滤波器组近似 TV 正则 (He 2010, O(N))。
 *
 * 三层语义 (这是"清晰但不脏"的数学保证):
 *   structure (结构) = 两轮大/中半径引导滤波的级联平滑 —— 天空/水体/雾面的大形态
 *   texture   (纹理) = L - structure —— 岩石颗粒/树叶/水波 (画质的本体)
 *   (噪声已包含在 texture 的微小幅度段, 由 DetailDenoise 在纹理层内处理)
 *
 * 与 v2 Base/Detail 的区别: v2 单层大半径 base 把中频结构(山脊线)也当成了
 * "可重分布框架" → 重分布时结构被破坏(亮斑/灰斑)。v5 两轮级联半径
 * (1/8 → 1/3) 只提取真正的"超低频光照/色彩框架", 山脊线完整留在纹理层。
 *
 * 用法: 结构层承载 tone/黑位 (Zone System), 纹理层承载增强 (×1.25) + 去噪收缩。
 */
public final class CartoonTexture {

    private CartoonTexture() {}

    /**
     * @return [0]=structure, [1]=texture (均为亮度域)
     */
    public static float[][] decompose(float[] L, int w, int h) {
        int r1 = Math.max(8, Math.min(w, h) / 8);
        int r2 = Math.max(20, Math.min(w, h) / 3);
        GuidedFilter g1 = new GuidedFilter(L, w, h, r1, 0.002f);
        float[] mid = g1.filter(L);
        GuidedFilter g2 = new GuidedFilter(mid, w, h, r2, 0.05f);
        float[] structure = g2.filter(mid);
        float[] texture = new float[L.length];
        for (int i = 0; i < L.length; i++) texture[i] = L[i] - structure[i];
        return new float[][]{structure, texture};
    }
}
