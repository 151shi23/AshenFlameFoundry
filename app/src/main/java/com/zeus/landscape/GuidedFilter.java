package com.zeus.landscape;

/**
 * 灰度引导滤波 (Guided Filter)。
 *
 * 文献: He Kaiming, Sun Jian, Tang Xiaoou,
 *   "Guided Image Filtering", ECCV 2010 / TPAMI 2013。
 *
 * 性质: 在引导图 I 的边缘处保持输入 P 的边缘且无 halo（区别于 USM）,
 * 复杂度 O(N) 与半径无关。本实现为灰度引导单通道版本。
 * 用途: ① DCP 透射率精化 ② Clarity 基底分离 ③ 光晕抑制。
 *
 * 模型: q_i = a_k * I_i + b_k（窗口 k 内线性拟合）, 全图输出为系数的盒均值展开。
 */
public final class GuidedFilter {
    private final float[] guide;
    private final int w, h, r;
    private final float eps;
    private final float[] meanI, meanII;

    /**
     * @param guide 引导图 (通常为亮度), 取值 [0,1]
     * @param r     窗口半径 (像素)
     * @param eps   正则: 大 → 平滑(近似均值滤波), 小 → 贴边。
     *              典型: 透射率精化 1e-4~1e-3
     */
    public GuidedFilter(float[] guide, int w, int h, int r, float eps) {
        this.guide = guide;
        this.w = w;
        this.h = h;
        this.r = r;
        this.eps = eps;
        this.meanI = Filters.boxFilter(guide, w, h, r);
        float[] II = new float[guide.length];
        for (int i = 0; i < guide.length; i++) II[i] = guide[i] * guide[i];
        this.meanII = Filters.boxFilter(II, w, h, r);
    }

    /** 对输入 P 滤波, 返回 q。可对多个 P 复用同一引导统计量。 */
    public float[] filter(float[] p) {
        float[] meanP = Filters.boxFilter(p, w, h, r);
        float[] IP = new float[p.length];
        for (int i = 0; i < p.length; i++) IP[i] = guide[i] * p[i];
        float[] meanIP = Filters.boxFilter(IP, w, h, r);

        float[] a = new float[p.length];
        float[] b = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            float covIP = meanIP[i] - meanI[i] * meanP[i];
            float varI = meanII[i] - meanI[i] * meanI[i];
            a[i] = covIP / (varI + eps);
            b[i] = meanP[i] - a[i] * meanI[i];
        }
        float[] meanA = Filters.boxFilter(a, w, h, r);
        float[] meanB = Filters.boxFilter(b, w, h, r);

        float[] q = new float[p.length];
        for (int i = 0; i < q.length; i++) {
            q[i] = meanA[i] * guide[i] + meanB[i];
        }
        return q;
    }
}
