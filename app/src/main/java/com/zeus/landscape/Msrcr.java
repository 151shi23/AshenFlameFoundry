package com.zeus.landscape;

/**
 * 多尺度 Retinex + 颜色恢复 (MSRCR) —— v3 新增核心算法。
 *
 * 文献: Jobson, Rahman, Woodell,
 *   "A Multiscale Retinex for Bridging the Gap between Color Images and the
 *    Human Observation of Scenes", IEEE TIP 1997;
 *   "Properties and Performance of a Center/Surround Retinex", TIP 1997。
 *
 * 物理模型: 观测 = 反射率 × 光照。Retinex 用中心/环绕高斯估计光照,
 * 输出 log(反射率) —— 即"人眼感知"的恒常场景 (对大面积雾霭/色罩天然免疫)。
 *
 * 与 DCP 去雾的关系 (互补而非替代):
 *   DCP 是物理散射模型 (大气光透射率), 管"雾";
 *   MSRCR 是视觉恒常模型 (光照归一), 管"罩"—— 阴天灰罩、逆光色罩、传感器偏色。
 * 两者串联 = 物理层 + 感知层的双重通透化。
 *
 * 经典参数: 三尺度 σs = {小, 中, 大} (光照→颜色恒常过渡), 等权;
 * 颜色恢复项 CR = β·log(α·I_i/ΣI) 防止 MSRCR 固有的偏色 (灰世界倾向)。
 *
 * 本实现的域适配: 经典论文在 [0,255] 域, 这里全套换算到 [0,1] (log 域自然伸缩),
 * 输出经 2% 分位 clip 后线性归一 (MSRCR 输出的动态范围不规则, 必须重整)。
 */
public final class Msrcr {

    /** @param strength [0,1] 总体力度 (建议 0.5~0.9) */
    public static void apply(FloatImage img, float strength) {
        int w = img.width, h = img.height, n = img.size();
        // 三尺度环绕半径 (短边比例, 与 Jobson {15,80,250}@RGB[0,255] 等效换算)
        int base = Math.min(w, h);
        int[] sigmas = {
                Math.max(4, base / 64),      // 小尺度: 动态范围压缩 (局部)
                Math.max(12, base / 16),     // 中尺度: 颜色恒常
                Math.max(40, base / 4)       // 大尺度: 光照归一
        };
        float wK = 1f / 3f * strength;
        float[][] gain = new float[3][n];

        for (int k = 0; k < 3; k++) {
            // 环绕 (中心/环绕): 高斯模糊的各通道
            float[][] blur = new float[3][];
            for (int c = 0; c < 3; c++) {
                blur[c] = Filters.gaussianBlur(img.data[c], w, h, sigmas[k]);
            }
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 3; c++) {
                    float I = Math.max(1e-4f, img.data[c][i]);
                    float B = Math.max(1e-4f, blur[c][i]);
                    // log(I/B): 反射率估计 (log 域做减法 = 物理做除法)
                    float r = (float) (Math.log(I) - Math.log(B));
                    gain[c][i] += wK * r;
                }
            }
        }

        // 颜色恢复项: β·log(α·I_c/ΣI) (Jobson 1997, 防 MSRCR 灰世界偏色)
        float beta = 0.62f * strength, alpha = 12.5f;
        float[][] out = new float[3][n];
        float[] lo = {1e3f, 1e3f, 1e3f}, hi = {-1e3f, -1e3f, -1e3f};
        for (int i = 0; i < n; i++) {
            float sum = img.data[0][i] + img.data[1][i] + img.data[2][i] + 1e-4f;
            for (int c = 0; c < 3; c++) {
                float I = Math.max(1e-4f, img.data[c][i]);
                float cr = beta * (float) Math.log(alpha * I / sum);
                float v = gain[c][i] + cr;
                out[c][i] = v;
                lo[c] = Math.min(lo[c], v);
                hi[c] = Math.max(hi[c], v);
            }
        }
        // 动态范围重整 —— 三通道【联合】分位归一 (铁律: 绝不能逐通道独立归一,
        // 否则通道间相对关系被破坏 → 偏色爆炸/负片化, v3 首跑实测蓝紫负片)
        float[] all = new float[n * 3];
        for (int c = 0; c < 3; c++) System.arraycopy(out[c], 0, all, c * n, n);
        java.util.Arrays.sort(all);
        float plo = all[(int) (all.length * 0.02f)];
        float phi = all[(int) (all.length * 0.98f)];
        float span = Math.max(0.3f, phi - plo);
        float inv = 1f / span;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) {
                float v = (out[c][i] - plo) * inv;
                img.data[c][i] = Math.max(0f, Math.min(1.3f, v));
            }
        }
    }
}
