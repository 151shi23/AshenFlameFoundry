package com.zeus.landscape;

/**
 * 提亮-滚降 (Brighten & Roll-off) —— v6 主力曝光引擎 (Python 网格扫描实测定版)。
 *
 * 出处: 18+16 组配方网格目检扫描 (scan2_overcast.jpg), 胜出配方:
 *   黑位下沉(p1×0.25) + 微 gamma 提亮 + EV+0.25 + 高光软膝 0.72 + CLAHE 1.6 + sat 1.18
 *
 * 大疆直出的真相: "通透鲜活" = 往上提亮, 不是往下压 (v1-v5 的去雾优先架构全错)。
 * 黑位轻下沉制造对比, 中间调整体提亮, 高光软膝保云层次 —— 明亮而不惨白。
 * 替代 v5 的 BaseDetailTone/exposureNormalize 等全部实验性阶段。
 */
public final class BrightenRoll {

    private BrightenRoll() {}

    /** @param ev 曝光增量档位 (0.25 = +1/4 EV) @param hazeScene 雾景时黑位下沉减半 */
    public static void apply(FloatImage img, float ev, float blackLift, boolean hazeScene) {
        int w = img.width, h = img.height, n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p1 = Stats.percentile(L, 0.01f);
        // (a) 黑位下沉 + 微 gamma 提亮 (sRGB 域, 与 Python 扫描版一致)
        float s = blackLift * (hazeScene ? 0.45f : 1f);
        float k = p1 * 0.5f * s;
        float gamma = 1f / (1f + 0.3f * s);
        // (b0) 高光结构保护: "亮且有局部结构" = 体积光/云隙光束/反光斑 ——
        //      EV 提亮在此衰减, 防止光束被推爆成太阳 (v8.2 实测翻车点)
        float[] blur = Filters.gaussianBlur(L, w, h, Math.max(6f, w / 25f));
        float[] structW = new float[n];
        for (int i = 0; i < n; i++) {
            float detail = Math.abs(L[i] - blur[i]);
            structW[i] = Stats.smoothstep(0.004f, 0.014f, detail)
                    * Stats.smoothstep(0.68f, 0.90f, L[i]);
        }
        // (b) EV 提亮 (结构区增益衰减)
        float gain = (float) Math.pow(2, ev);
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                float v = Math.max(0f, Math.min(1f, d[i]));
                v = (v - k) / Math.max(1e-3f, 1f - k);
                v = (float) Math.pow(Math.max(0f, v), gamma);
                float g = 1f + (gain - 1f) * (1f - 0.72f * structW[i]);
                v *= g;
                // (c) 高光渐进软膝: 0.76 起滚降, 斜率随亮度递减 (光束保持层次)
                if (v > 0.76f) {
                    float over = v - 0.76f;
                    float knee = 0.68f - 0.30f * Stats.smoothstep(0.76f, 1.05f, v);
                    v = 0.76f + over * knee;
                }
                d[i] = Math.max(0f, Math.min(1f, v));
            }
        }
    }
}
