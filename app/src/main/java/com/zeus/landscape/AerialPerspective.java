package com.zeus.landscape;

/**
 * 大气透视增强 (Aerial Perspective Enhancement) —— v7 新增核心算法: 层次感的来源。
 *
 * 物理依据: 大气散射使远景趋向大气光 (低对比/低饱和/趋亮) —— 人眼靠这个判断纵深。
 * 风景后期"层次感"的本质 = 强化这个自然信号:
 *   近区 (对比/饱和高): 加饱和、加反差 → 实
 *   远区 (对比/饱和低): 轻提亮、降饱和 → 退
 * 近深远浅被人为拉开后, 纵深感"跳出来"。
 *
 * 深度估计 (无学习的物理近似): 近度 = 0.5×饱和归一 + 0.5×局部对比归一,
 * 大半径平滑。雾区天然 sat/对比低 → near 低 → 自动走"远区"分支 (逻辑自洽,
 * 提亮降饱和与雾一致, 不会破坏柔雾 —— Python 扫描 fog1 medium 档实测)。
 *
 * 结构层 Dodge&Burn: 大尺度亮框架 +、暗框架 - (软膝防死黑) —— 大范围明暗
 * 关系的反差 = "影调层次"。
 *
 * 参数出处: Python 三图×四档网格扫描 (scan3_*.jpg), medium 档三图全胜:
 *   strength=0.9, near_sat=0.45, far_lift=0.10, db=0.075
 */
public final class AerialPerspective {

    private AerialPerspective() {}

    public static void apply(FloatImage img, float strength) {
        // 高饱和图 (日落/花海) 的近区增饱和收敛: 原图已浓, 再推即荧光 (sunset 实测)
        float satMean = 0f;
        for (int i = 0; i < img.size(); i++) {
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
            satMean += mx > 1e-5f ? (mx - mn) / mx : 0f;
        }
        satMean /= img.size();
        float satScale = satMean > 0.45f ? 0.35f : 1f;

        int w = img.width, h = img.height, n = img.size();
        float sigLarge = Math.max(8f, w / 40f);

        // ---- 深度(近度)图 ----
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            lum[i] = ColorOps.luma(img.data[0][i], img.data[1][i], img.data[2][i]);
        }
        float[] sat = new float[n];
        for (int i = 0; i < n; i++) {
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
            sat[i] = mx > 1e-5f ? (mx - mn) / mx : 0f;
        }
        float[] lumBlur = Filters.gaussianBlur(lum, w, h, sigLarge);
        float[] lumSq = new float[n];
        for (int i = 0; i < n; i++) lumSq[i] = lum[i] * lum[i];
        float[] sqBlur = Filters.gaussianBlur(lumSq, w, h, sigLarge);
        float[] std = new float[n];
        float stdMax = 1e-6f, satMax = 1e-6f;
        for (int i = 0; i < n; i++) {
            std[i] = (float) Math.sqrt(Math.max(0f, sqBlur[i] - lumBlur[i] * lumBlur[i]));
            stdMax = Math.max(stdMax, std[i]);
            satMax = Math.max(satMax, sat[i]);
        }
        float[] near = new float[n];
        float nMin = 1f, nMax = 0f;
        for (int i = 0; i < n; i++) {
            near[i] = 0.5f * (sat[i] / satMax) + 0.5f * (std[i] / stdMax);
            nMin = Math.min(nMin, near[i]);
            nMax = Math.max(nMax, near[i]);
        }
        float nSpan = Math.max(1e-5f, nMax - nMin);
        // 大半径平滑 (深度图不该有 tile 级跳变)
        float[] nearS = Filters.gaussianBlur(near, w, h, w / 25f);
        for (int i = 0; i < n; i++) near[i] = (nearS[i] - nMin) / nSpan;

        // ---- 结构层 (Dodge&Burn 用) ----
        float[] struct = Filters.gaussianBlur(lum, w, h, w / 8f);
        float[] sSorted = struct.clone();
        java.util.Arrays.sort(sSorted);
        float anchor = sSorted[n / 2];
        float sLo = sSorted[0], sHi = sSorted[n - 1];

        // ---- 应用 ----
        float nearSat = 0.45f * satScale, farLift = 0.10f, db = 0.075f;
        for (int i = 0; i < n; i++) {
            float far = 1f - near[i];
            for (int c = 0; c < 3; c++) {
                float v = img.data[c][i];
                // 远区趋雾色 (向 0.5 灰收敛)
                v += farLift * strength * far * (0.5f - v) * 0.6f;
                v = Math.max(0f, Math.min(1f, v));
                img.data[c][i] = v;
            }
        }
        // 饱和调制 (近区增益, 远区收敛) — 灰度锚定法
        for (int i = 0; i < n; i++) {
            float nearV = near[i];
            float gain = 1f + nearSat * strength * (nearV - 0.55f);
            gain = Math.max(0.78f, Math.min(1.28f, gain));
            if (Math.abs(gain - 1f) < 0.01f) continue;
            float r = img.data[0][i], g = img.data[1][i], b = img.data[2][i];
            float anchorL = ColorOps.luma(r, g, b);
            img.data[0][i] = Math.max(0f, Math.min(1f, anchorL + (r - anchorL) * gain));
            img.data[1][i] = Math.max(0f, Math.min(1f, anchorL + (g - anchorL) * gain));
            img.data[2][i] = Math.max(0f, Math.min(1f, anchorL + (b - anchorL) * gain));
        }
        // 结构层 Dodge&Burn
        float[] L2 = ColorOps.lumaPlane(img);
        for (int i = 0; i < n; i++) {
            float wHi = Stats.smoothstep(0f, 1f, (struct[i] - anchor) / Math.max(1e-5f, sHi - anchor));
            float wLo = Stats.smoothstep(0f, 1f, (anchor - struct[i]) / Math.max(1e-5f, anchor - sLo));
            float dv = db * strength * (wHi * 0.35f - wLo * 0.25f) * Math.abs(L2[i] - 0.5f) * 2f;
            float ratio = (L2[i] + dv) / Math.max(1e-4f, L2[i]);
            for (int c = 0; c < 3; c++) {
                img.data[c][i] = Math.max(0f, Math.min(1f, img.data[c][i] * ratio));
            }
        }
    }
}
