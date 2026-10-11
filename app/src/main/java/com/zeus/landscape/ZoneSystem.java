package com.zeus.landscape;

import com.zeus.landscape.ColorOps;

/**
 * 区域曝光法 (Zone System) 数字化 —— v5 新增核心算法。
 *
 * 文献: Ansel Adams, "The Negative" (经典暗房理论);
 *   数字化分区实践见 photo.stackexchange / thecustomizewindows 十分区体系。
 *
 * Ansel Adams 的 11 分区: 0=纯黑, I=近黑, III=暗部有细节, V=中灰(18%),
 * VII=亮部有细节, X=纯白。风光摄影的百年验证体系 —— "好看"的确定性来源。
 *
 * 数字实现: 把图像亮度直方图的**关键分位锚定到分区目标值**,
 * 分段单调映射 (PCHIP 风格单调三次插值, 无过冲):
 *   p1  → Zone I   (0.045)
 *   p10 → Zone II  (0.11)
 *   p50 → Zone V   (0.46)
 *   p92 → Zone VIII(0.76)
 *   p99 → Zone IX  (0.88)
 *
 * 这一个模块替代 v1 的 AutoLevels+ShadowHighlight+ToneCurve 三个职能重复的
 * 阶段 —— 统一的曝光哲学, 而非三个互踩的补丁。强度参数控制锚定的松紧。
 */
public final class ZoneSystem {

    private ZoneSystem() {}

    /** @param strength [0,1] 1=完全锚定到分区目标, 0=不动 */
    public static void apply(FloatImage img, float strength) {
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float p1 = Stats.percentile(L, 0.01f);
        float p10 = Stats.percentile(L, 0.10f);
        float p50 = Stats.percentile(L, 0.50f);
        float p92 = Stats.percentile(L, 0.92f);
        float p99 = Stats.percentile(L, 0.99f);

        // 当前场景关键值: 中位离 Zone V 的距离
        float keyShift = (0.46f - p50) * strength;

        // 源锚点 → 目标锚点 (Zone 值)。强度 = 目标与当前值的插值比。
        float[] srcX = {0f, p1, p10, p50, p92, p99, 1f};
        float[] dstY = new float[srcX.length];
        float[] zoneTarget = {0f, 0.045f, 0.11f, 0.46f, 0.76f, 0.88f, 1f};
        for (int i = 0; i < srcX.length; i++) {
            // 目标 = 当前 + (zone - 当前) * strength; 端点(0,1)固定
            dstY[i] = i == 0 || i == srcX.length - 1
                    ? srcX[i]
                    : srcX[i] + (zoneTarget[i] - srcX[i]) * strength;
        }
        // 单调性保护 (源可能非单调, 如 p1>p10 不可能但浮点保险)
        for (int i = 1; i < dstY.length; i++) dstY[i] = Math.max(dstY[i], dstY[i - 1]);

        // LUT: 单调分段三次 (Catmull-Rom 于控制点), 2048 级
        float[] lut = new float[2049];
        for (int i = 0; i <= 2048; i++) {
            float x = i / 2048f;
            lut[i] = monotoneInterp(srcX, dstY, x);
        }
        float[] newL = new float[n];
        for (int i = 0; i < n; i++) {
            newL[i] = lut[(int) (Math.max(0f, Math.min(1f, L[i])) * 2048f)];
        }
        for (int c = 0; c < 3; c++) {
            float[] d = img.data[c];
            for (int i = 0; i < n; i++) {
                float ratio = newL[i] / Math.max(1e-4f, L[i]);
                d[i] = Math.max(0f, Math.min(1f, d[i] * ratio));
            }
        }
    }

    /** 单调分段插值: 控制点间用 Hermite (端点切线=0, 中段 Catmull-Rom) */
    private static float monotoneInterp(float[] xs, float[] ys, float x) {
        int seg = xs.length - 1;
        if (x <= xs[0]) return ys[0];
        if (x >= xs[seg]) return ys[seg];
        int k = 0;
        for (int i = 0; i < seg; i++) {
            if (x >= xs[i] && x <= xs[i + 1]) { k = i; break; }
        }
        float t = (x - xs[k]) / Math.max(1e-6f, xs[k + 1] - xs[k]);
        // 平滑 step 混合 (C1 连续): h(t)=t*t*(3-2t)
        float h = t * t * (3 - 2 * t);
        return ys[k] + (ys[k + 1] - ys[k]) * h;
    }
}
