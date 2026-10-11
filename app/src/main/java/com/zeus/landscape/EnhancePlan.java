package com.zeus.landscape;

/**
 * 增强计划 (Plan) —— 超级算法的决策层。
 *
 * 输入: L1 细局部网格 + L2 粗局部网格 + L3 全局统计
 * 输出: ① 逐 tile 参数图 (exposure/contrast/sat/clarity/dehaze 五维)
 *       ② 全局参数 (S 曲线/锐化/预算/去雾开关)
 *
 * 制定规则 (相机 ISP 局部自适应语义):
 *  曝光图  : tile 亮度 → 全局目标回归, 暗部多提、天空负曝光;
 *            融合 L2 语义 (粗局部整体偏暗 = 大区域欠曝, 如背光山体)
 *  对比图  : tile 对比低于健康带 → 补; 已经高对比的岩石 → 不动
 *  饱和图  : tile 饱和低于目标 → 补; 蓝天 tile 额外 +蓝增强; 已鲜艳 → 收
 *  清晰图  : 细节能量充足才给 clarity (平坦区/雾区提细节 = 放大噪声)
 *  去雾图  : tile 暗通道高 (雾厚) → 强; 天空/前景清水区 → 弱
 *
 * 参数图做两轮 3×3 盒滤波平滑: 消除 tile 边界跳变 (执行期逐像素双线性插值)。
 */
public final class EnhancePlan {

    public final int gw, gh;
    /** 逐 tile 参数图 (执行期按像素位置双线性插值) */
    public final float[] exposure, contrast, sat, clarity, dehaze;
    /** 全局参数 */
    public final float budget, sCurve, sharpen, targetLum, targetSat;
    public final boolean dehazeOn;
    public final String rationale;

    private EnhancePlan(int gw, int gh, float[] e, float[] c, float[] s, float[] cl, float[] dz,
                        float budget, float sCurve, float sharpen, float tL, float tS,
                        boolean dehazeOn, String rationale) {
        this.gw = gw;
        this.gh = gh;
        this.exposure = e;
        this.contrast = c;
        this.sat = s;
        this.clarity = cl;
        this.dehaze = dz;
        this.budget = budget;
        this.sCurve = sCurve;
        this.sharpen = sharpen;
        this.targetLum = tL;
        this.targetSat = tS;
        this.dehazeOn = dehazeOn;
        this.rationale = rationale;
    }

    public static EnhancePlan build(MultiScaleAnalysis.TileGrid l1, MultiScaleAnalysis.TileGrid l2,
                                    MultiScaleAnalysis.GlobalStats g) {
        return build(l1, l2, g, null);
    }

    public static EnhancePlan build(MultiScaleAnalysis.TileGrid l1, MultiScaleAnalysis.TileGrid l2,
                                    MultiScaleAnalysis.GlobalStats g, float[] salience) {
        int gw = l1.gw, gh = l1.gh;
        int gw2 = l2.gw, gh2 = l2.gh;
        float[] exp = new float[gw * gh];
        float[] con = new float[gw * gh];
        float[] sat = new float[gw * gh];
        float[] clr = new float[gw * gh];
        float[] dz = new float[gw * gh];

        // 全局目标: 中位亮度回归 0.46 (风景直出的黄金中位), 饱和 0.36
        float targetLum = Math.max(0.38f, Math.min(0.55f, g.p50 < 0.35f ? 0.46f : g.p50 * 1.08f));
        float targetSat = 0.36f;
        float budget = g.budget;

        // 全局雾判定 (五因子: dc + 亮部中性 + 低对比 + 成片覆盖):
        // 覆盖率区分"真雾"(均匀成片) 与"雪谷阴影/暗场"(dc 高但零散, 如雪山亮部纹理)
        float contrastScore = Stats.smoothstep(0.80f, 0.50f, g.contrast);
        float neutralScore = Stats.smoothstep(0.30f, 0.12f, g.brightSat);
        int covCnt = 0;
        float covT = g.hazeLevel * 0.7f;
        for (float v : l1.darkApprox) if (v > covT) covCnt++;
        float coverage = (float) covCnt / (gw * gh);
        boolean dehazeOn = g.hazeLevel > 0.10f && neutralScore > 0.35f
                && contrastScore > 0.25f && coverage > 0.45f;

        // 细节能量归一 (L1 全局)
        float detMax = 1e-5f;
        for (float v : l1.detail) detMax = Math.max(detMax, v);

        StringBuilder why = new StringBuilder();

        for (int ty = 0; ty < gh; ty++) {
            for (int tx = 0; tx < gw; tx++) {
                int t = ty * gw + tx;

                // ---- L2 大局部语义 (粗网格最近邻采样) ----
                int tx2 = Math.min(gw2 - 1, tx * gw2 / gw);
                int ty2 = Math.min(gh2 - 1, ty * gh2 / gh);
                int t2 = ty2 * gw2 + tx2;
                float regionLum = l2.meanLum[t2];
                float regionSat = l2.sat[t2];
                float regionDark = l2.darkApprox[t2];

                // ---- 曝光图: 像素级目标回归 × 细局部亮度 × 大局部语义 ----
                float lum = l1.meanLum[t];
                float shadowW = Stats.smoothstep(0.45f, 0.10f, lum);       // 越暗提越多
                float hiW = Stats.smoothstep(0.78f, 0.92f, lum);           // 高光区禁提
                float e = (targetLum - lum) * (0.55f + 0.75f * shadowW);
                e *= (1f - hiW * 0.9f);
                // 大局部整体偏暗 (背光山体) → 额外提亮; 大局部是亮天空 → 压
                if (regionLum < targetLum * 0.75f) e += 0.10f * shadowW;
                if (regionLum > 0.70f && l1.skyProb[t] > 0.3f) e -= 0.03f;
                exp[t] = Math.max(-0.22f, Math.min(0.50f, e)) * (0.35f + 0.65f * budget);

                // ---- 对比图: 局部对比缺口 ----
                float c = Math.max(0f, 0.115f - l1.contrast[t]) * 2.2f;
                con[t] = Math.min(0.25f, c) * (0.4f + 0.6f * budget);

                // ---- 饱和图: 目标回归 + 蓝天特判 + 大局部语义 ----
                float s = (targetSat - l1.sat[t]) * 0.85f;
                s = Math.max(-0.10f, Math.min(0.42f, s));
                if (l1.skyProb[t] > 0.35f) s += 0.10f;                      // 天空更蓝
                if (regionSat > 0.55f) s *= 0.5f;                           // 大局部已浓烈 (日落) → 收
                sat[t] = s * (0.4f + 0.6f * budget);

                // ---- 清晰图: 细节充足才提 (平坦/雾区防噪) ----
                float det = l1.detail[t] / detMax;
                clr[t] = Math.max(0.15f, Math.min(1.15f, det * 1.4f));

                // ---- 去雾图: 局部暗通道 (tile 尺度雾感) ----
                float d = Stats.smoothstep(0.10f, 0.45f, Math.max(l1.darkApprox[t], regionDark));
                dz[t] = dehazeOn ? d * (0.45f + 0.55f * budget) : 0f;
                // v3: 显著度调制 —— 增强预算花在人眼会看的地方 (Itti-Koch 感知驱动)
                if (salience != null) {
                    float sv = salience[t];
                    clr[t] *= (0.55f + 0.75f * sv);
                    sat[t] *= (0.65f + 0.55f * sv);
                    con[t] *= (0.70f + 0.45f * sv);
                }
            }
        }

        // 参数图平滑: 两轮 3×3 (消除 tile 缝)
        exp = smooth3(exp, gw, gh, 2);
        con = smooth3(con, gw, gh, 2);
        sat = smooth3(sat, gw, gh, 2);
        clr = smooth3(clr, gw, gh, 1);
        dz = smooth3(dz, gw, gh, 2);

        why.append(String.format("目标亮度=%.2f 饱和=%.2f | 全局雾=%s (dc=%.3f 亮部饱和=%.2f 覆盖=%.0f%%) | 预算=%.2f",
                targetLum, targetSat, dehazeOn ? "有" : "无", g.hazeLevel, g.brightSat, coverage * 100, budget));

        return new EnhancePlan(gw, gh, exp, con, sat, clr, dz,
                budget, sCurveAmount(g), sharpenAmount(g), targetLum, targetSat, dehazeOn, why.toString());
    }

    private static float sCurveAmount(MultiScaleAnalysis.GlobalStats g) {
        float a = g.contrast < 0.55f ? 0.80f : g.contrast < 0.70f ? 0.50f
                : g.contrast < 0.85f ? 0.28f : 0.10f;
        return a * (0.4f + 0.6f * g.budget);
    }

    private static float sharpenAmount(MultiScaleAnalysis.GlobalStats g) {
        return (g.entropy < 6.5f ? 0.42f : 0.32f) * (0.5f + 0.5f * g.budget);
    }

    /** 3×3 盒滤波 n 轮 (参数图专用, 边界 clamp) */
    private static float[] smooth3(float[] m, int w, int h, int rounds) {
        float[] cur = m;
        for (int r = 0; r < rounds; r++) {
            float[] out = new float[cur.length];
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
                for (int x = 0; x < w; x++) {
                    int x0 = Math.max(0, x - 1), x1 = Math.min(w - 1, x + 1);
                    float s = 0;
                    for (int yy = y0; yy <= y1; yy++)
                        for (int xx = x0; xx <= x1; xx++)
                            s += cur[yy * w + xx];
                    out[y * w + x] = s / ((x1 - x0 + 1) * (y1 - y0 + 1));
                }
            }
            cur = out;
        }
        return cur;
    }

    /** 像素位置 → 参数图双线性插值 (执行期热路径, 无分配) */
    public float sample(float[] map, int w, int h, float px, float py) {
        float gx = Math.max(0f, Math.min(gw - 1.001f, px / w * gw));
        float gy = Math.max(0f, Math.min(gh - 1.001f, py / h * gh));
        int x0 = (int) gx, y0 = (int) gy;
        int x1 = Math.min(gw - 1, x0 + 1), y1 = Math.min(gh - 1, y0 + 1);
        float fx = gx - x0, fy = gy - y0;
        float v00 = map[y0 * gw + x0], v01 = map[y0 * gw + x1];
        float v10 = map[y1 * gw + x0], v11 = map[y1 * gw + x1];
        float top = v00 + (v01 - v00) * fx;
        float bot = v10 + (v11 - v10) * fx;
        return top + (bot - top) * fy;
    }
}
