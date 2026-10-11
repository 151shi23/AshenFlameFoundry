package com.zeus.landscape;

import java.util.Random;

/**
 * 胶片随手拍质感 (Film Look) —— v9 新增核心算法, 回应一个关键审美判断:
 * "每区域语义分类挑颜色参数过于完美, 失去随手拍的质感" —— 完全正确。
 *
 * 真实相机的质感来自"一台相机"的逻辑, 而非语义分区修图:
 *   ① 全局统一的色彩响应 (一条曲线全图吃, 无分区无保护)
 *   ② 亮度相关胶片颗粒 (暗部颗粒大亮部小, 单色亮度颗粒 —— 胶片的有机本质)
 *   ③ 轻微暗角 (镜头光学特性, 视线自然聚焦中心)
 * Python 三图×四档扫描 (scan6_*.jpg) 定版 light 档: film light 在全部测试图上
 * 优于分区精修版 —— 浓艳=甜腻 AI 感, 统一胶片响应=真实摄影质感。
 *
 * film 模式下管线跳过: 显著度参数图/MSRCR/aerial/cinematic/Zone —— 只留
 * WB + 曝光底子 + 胶片曲线 + 统一饱和 + 颗粒 + 暗角。"一台相机"。
 */
public final class FilmLook {

    private FilmLook() {}

    /** @param strength light=1.0 / medium=1.45 / strong=1.9 */
    public static void apply(FloatImage img, float strength) {
        int w = img.width, h = img.height, n = img.size();
        // ---- ① 统一胶片曲线 (全局 smoothstep 对比混合, 无分区) ----
        float[] L = ColorOps.lumaPlane(img);
        float contrast = 0.75f + 0.2f * (strength - 1f);
        for (int i = 0; i < n; i++) {
            float t = Math.max(0f, Math.min(1f, L[i]));
            float sCurve = t * t * (3 - 2 * t);
            float curve = t + contrast * 0.28f * (sCurve - t);
            float ratio = (curve + 1e-4f) / (L[i] + 1e-4f);
            for (int c = 0; c < 3; c++) {
                img.data[c][i] = Math.max(0f, Math.min(1f, img.data[c][i] * ratio));
            }
        }
        // ---- ② 统一饱和 1.03~1.10 (v8.2 降档: 暖调叠加三层是偏黄根因) ----
        float satGain = 1.03f + 0.04f * (strength - 1f);
        for (int i = 0; i < n; i++) {
            float r = img.data[0][i], g = img.data[1][i], b = img.data[2][i];
            float anchor = ColorOps.luma(r, g, b);
            img.data[0][i] = Math.max(0f, Math.min(1f, anchor + (r - anchor) * satGain));
            img.data[1][i] = Math.max(0f, Math.min(1f, anchor + (g - anchor) * satGain));
            img.data[2][i] = Math.max(0f, Math.min(1f, anchor + (b - anchor) * satGain));
        }
        // ---- ②b 高光中性化 (胶片铁律: 白就是白, 暖调只允许在中间调) ----
        L = ColorOps.lumaPlane(img);
        for (int i = 0; i < n; i++) {
            float l = L[i];
            float hiW = (float) Math.pow(Math.max(0f, Math.min(1f, (l - 0.62f) / 0.35f)), 1.2);
            if (hiW < 0.02f) continue;
            float rb = (img.data[0][i] + 1e-4f) / (img.data[2][i] + 1e-4f);
            float pull = 1f - 0.60f * hiW;                       // 高光区 R/B 比向 1 收 35%
            float gR = 1f + (rb - 1f) * (pull - 1f) * -1f;       // 反向收敛
            gR = (float) Math.pow(rb, -(1f - pull));
            img.data[0][i] = Math.max(0f, Math.min(1f, img.data[0][i] * (float) Math.pow(rb, -(1f - pull))));
            img.data[2][i] = Math.max(0f, Math.min(1f, img.data[2][i] * (float) Math.pow(rb, (1f - pull))));
        }
        // ---- ③ 亮度相关胶片颗粒 (单色, 暗部全额亮部减半) ----
        float sigmaBase = (2.4f + 1.1f * (strength - 1f)) / 255f;
        Random rng = new Random(42);   // 固定种子: 同图同颗粒 (可复现)
        L = ColorOps.lumaPlane(img);
        for (int i = 0; i < n; i++) {
            float sigma = sigmaBase * (1f - L[i] * 0.55f);
            float g = (float) rng.nextGaussian() * sigma;
            for (int c = 0; c < 3; c++) {
                img.data[c][i] = Math.max(0f, Math.min(1f, img.data[c][i] + g));
            }
        }
        // ---- ④ 暗角 (镜头光学) ----
        float vig = 0.08f + 0.04f * (strength - 1f);
        float cx = w / 2f, cy = h / 2f;
        float rx = w / 2f, ry = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / rx, dy = (y - cy) / ry;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float f = 1f - vig * (float) Math.pow(Math.max(0f, r - 0.35f) / 0.65f, 2f) * 1.6f;
                f = Math.max(0.84f, f);
                int i = y * w + x;
                img.data[0][i] *= f;
                img.data[1][i] *= f;
                img.data[2][i] *= f;
            }
        }
    }
}
