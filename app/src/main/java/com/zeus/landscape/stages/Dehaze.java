package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.EnhancePlan;
import com.zeus.landscape.Filters;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.GuidedFilter;
import com.zeus.landscape.Stats;
import com.zeus.landscape.Stage;

/**
 * 阶段 2: 单帧去雾 —— 暗通道先验 (DCP) + 引导滤波透射率精化, 自适应强度。
 *
 * 文献:
 *  - He Kaiming, Sun Jian, Tang Xiaoou, "Single Image Haze Removal Using Dark
 *    Channel Prior", CVPR 2009 / TPAMI 2011。
 *      J(x) = (I(x) - A) / max(t(x), t0) + A
 *      t(x) = 1 - ω · min_{y∈Ω(x)} min_c I_c(y) / A     (ω=0.95 保留薄雾层次)
 *  - 引导滤波替代软抠图 (soft matting) 精化透射率: 快三个量级且无块状带。
 *
 * 风景特化 (与论文的关键差异):
 *  1. 自适应强度: 雾感指标 = 暗通道均值。无雾图 (dc<0.04, 如雪景/特写) 完全跳过,
 *     防止"去雾"引入偏色; 浓雾图全强度; 之间线性过渡。
 *  2. 天空保护: 暗通道在蓝天区域天然≈0 → 透射率≈1 → 天空基本不动 (模型自洽);
 *     但浓雾天空暗通道也低, 因此对 t>0.9 的区域衰减去雾增益, 防天空发灰。
 *  3. 亮度域验证: 去雾后 luminance 可能越过 1.0, 后续 ShadowHighlight 会压缩。
 */
public final class Dehaze implements Stage {
    private static final int PATCH = 15;        // 暗通道窗口 (论文 15x15)
    private static final float OMEGA = 0.95f;   // 保留 5% 雾感: 风景需要空气透视
    private static final float T0 = 0.1f;       // 透射率下限
    private static final float STRONG_DC = 0.14f;
    private static final float NO_DC = 0.04f;
    /** 增强计划: 提供时使用计划的逐 tile 去雾图 (空间自适应), 否则用内置四因子标量 */
    private EnhancePlan plan;

    public Dehaze plan(EnhancePlan p) { this.plan = p; return this; }

    @Override
    public String apply(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();

        // 1) 暗通道: min(R,G,B) 的 15x15 min filter (van Herk O(N))
        float[][] minRGB = new float[3][];
        for (int c = 0; c < 3; c++) minRGB[c] = img.data[c];
        float[] minFirst = minOf3(img);
        float[] dark = Filters.minFilter(minFirst, w, h, PATCH / 2);

        // 2) 雾感检测 (四因子联合判据):
        //    纯 dc 均值会把暮光/暗场误判为浓雾。物理与统计联合:
        //    a) dcScore      暗通道均值 — 雾的必要条件
        //    b) neutralScore 亮部中性度 — 大气光是中性白, 暮光亮部高饱和 (紫橙)
        //    c) coverage     dc 高像素占比 — 雾均匀覆盖全图, 暗场 dc 高但零散
        //    d) contrastScore 低对比 — 雾压对比; 高对比图去雾收益低风险高
        float hazeLevel = Stats.mean(dark);
        float dcScore = Math.max(0f, Math.min(1f, (hazeLevel - NO_DC) / (STRONG_DC - NO_DC)));
        float brightSat = estimateBrightSaturation(img);
        float neutralScore = Stats.smoothstep(0.30f, 0.12f, brightSat);
        int covCnt = 0;
        float covThresh = hazeLevel * 0.7f;
        for (float v : dark) if (v > covThresh) covCnt++;
        float coverage = (float) covCnt / dark.length;
        float coverageScore = Stats.smoothstep(0.30f, 0.55f, coverage);
        float[] Lall = ColorOps.lumaPlane(img);
        float contrast = Stats.percentile(Lall, 0.95f) - Stats.percentile(Lall, 0.05f);
        float contrastScore = Stats.smoothstep(0.80f, 0.50f, contrast);

        float strength;
        boolean planMode = plan != null && plan.dehazeOn;
        if (planMode) {
            strength = 1f;   // 实际强度 = 计划逐 tile 去雾图 × (预算已在 map 生成时计入)
        } else {
            strength = dcScore * neutralScore * coverageScore * contrastScore * 0.75f;
        }
        if (strength <= 0.02f) {
            return String.format(
                    "dehaze: 跳过 (dc=%.3f 亮部饱和=%.3f 覆盖=%.0f%% 对比=%.2f — 非雾场景, 保护色彩)",
                    hazeLevel, brightSat, coverage * 100, contrast);
        }

        // 3) 大气光 A: 暗通道前 0.1% 亮像素中, 取原图亮度最高 1% 像素的均值
        float[] A = estimateA(img, dark);

        // 4) 粗透射率: t = 1 - ω · min_c(I_c / A_c)   (A 取三分量 max, 简化保守估计)
        float amax = Math.max(A[0], Math.max(A[1], A[2]));
        float[] t = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = 1f - OMEGA * Math.min(1f, dark[i] / Math.max(0.05f, amax));
        }

        // 5) 引导滤波精化 (引导=灰度, r=60/全分辨率, eps=1e-3)
        float[] gray = ColorOps.lumaPlane(img);
        GuidedFilter gf = new GuidedFilter(gray, w, h, Math.max(30, Math.min(w, h) / 20), 1e-3f);
        float[] tRef = gf.filter(t);

        // 6) 复原 + 天空保护衰减 + 强度混合 (计划模式: 逐 tile 去雾图双线性插值 → 空间自适应)
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        float invW = planMode ? (float) plan.gw / w : 0f;
        float invH = planMode ? (float) plan.gh / h : 0f;
        for (int y = 0; y < h; y++) {
            float gy = planMode ? (y + 0.5f) * invH : 0f;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float ti = Math.max(T0, tRef[i]);
                float skyFall = Stats.smoothstep(0.85f, 0.97f, ti); // 天空/云区衰减 (提前, 防云团提亮成假太阳)
                float sPx = strength;
                if (planMode) {
                    sPx = plan.sample(plan.dehaze, w, h, x + 0.5f, gy);
                }
                float eff = sPx * (1f - 0.85f * skyFall);
                float invT = 1f / Math.max(T0, ti * eff + (1 - eff));
                R[i] = (R[i] - A[0]) * invT + A[0];
                G[i] = (G[i] - A[1]) * invT + A[1];
                B[i] = (B[i] - A[2]) * invT + A[2];
            }
        }
        clamp(img);
        return String.format("dehaze: 雾感=%.3f 强度=%.0f%% A=(%.2f,%.2f,%.2f) GF精化 %s",
                hazeLevel, strength * 100, A[0], A[1], A[2],
                planMode ? "· 计划参数图空间自适应" : "· 全局标量");
    }

    private float[] minOf3(FloatImage img) {
        float[] m = new float[img.size()];
        for (int i = 0; i < m.length; i++) {
            m[i] = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
        }
        return m;
    }

    /** 大气光估计: dark channel top 0.1% 像素中, 取原图亮度最高的 1/1000 样本均值 */
    private float[] estimateA(FloatImage img, float[] dark) {
        int n = dark.length;
        int topCount = Math.max(8, n / 1000);
        // 阈值搜索: 256-bin 直方图从亮到暗累计
        int[] hist = new int[256];
        for (float v : dark) hist[Math.min(255, (int) (v * 256))]++;
        float threshold = 1f;
        int acc = 0;
        for (int b = 255; b >= 0; b--) {
            acc += hist[b];
            if (acc >= topCount) {
                threshold = b / 256f;
                break;
            }
        }
        // 候选可能远多于 topCount (dc 相等区域), 收集上限放宽 3 倍
        float[] cl = new float[topCount * 3], cr = new float[topCount * 3],
                cg = new float[topCount * 3], cb = new float[topCount * 3];
        int cnt = 0;
        for (int i = 0; i < n && cnt < topCount * 3; i++) {
            if (dark[i] >= threshold) {
                cl[cnt] = ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]);
                cr[cnt] = img.data[0][i];
                cg[cnt] = img.data[1][i];
                cb[cnt] = img.data[2][i];
                cnt++;
            }
        }
        float[] A = new float[3];
        if (cnt == 0) { A[0] = A[1] = A[2] = 0.9f; return A; }
        // 论文法: 候选中取原图亮度最高的 25% 求均值 (防白色物体污染大气光)
        float[] ls = new float[cnt];
        System.arraycopy(cl, 0, ls, 0, cnt);
        java.util.Arrays.sort(ls);
        float cut = ls[Math.max(0, (int) (cnt * 0.75f))];
        double ar = 0, ag = 0, ab = 0;
        int k = 0;
        for (int i = 0; i < cnt; i++) {
            if (cl[i] >= cut) {
                ar += cr[i]; ag += cg[i]; ab += cb[i]; k++;
            }
        }
        if (k == 0) { A[0] = A[1] = A[2] = 0.9f; return A; }
        A[0] = (float) Math.max(0.1f, Math.min(1f, ar / k));
        A[1] = (float) Math.max(0.1f, Math.min(1f, ag / k));
        A[2] = (float) Math.max(0.1f, Math.min(1f, ab / k));
        return A;
    }

    /** 亮部 (v>0.75) 平均饱和度: 雾/阴天亮部接近中性 (低), 暮光/火烧云亮部高饱和 */
    private float estimateBrightSaturation(FloatImage img) {
        int n = img.size();
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            if (mx > 0.75f) {
                float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
                sum += (mx - mn) / Math.max(1e-6f, mx);
                cnt++;
            }
        }
        return cnt > n / 200 ? (float) (sum / cnt) : 0f;   // 亮部占比 <0.5% 视为无信息 → 不判雾
    }

    private void clamp(FloatImage img) {
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < d.length; i++) {
                if (d[i] < 0f) d[i] = 0f;
                else if (d[i] > 1.4f) d[i] = 1.4f;
            }
        }
    }
}
