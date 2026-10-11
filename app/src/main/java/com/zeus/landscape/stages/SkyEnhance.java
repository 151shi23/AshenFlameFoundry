package com.zeus.landscape.stages;

import com.zeus.landscape.ColorOps;
import com.zeus.landscape.Filters;
import com.zeus.landscape.FloatImage;
import com.zeus.landscape.Stage;

/**
 * 阶段 6: 天空增强 (风景特化)。
 *
 * 做三件事:
 *  1. 蓝天检测 (HSV 色相带 185-250°, 全图不限顶部 — 打样教训: 湖面倒影也是天空语义)
 *  2. 蓝色美化: 色相向 215° (自然天蓝) 微推 + 饱和度 +18% + 顶部垂直渐变亮化
 *     (真实大气散射: 天顶更蓝更透, 地平线偏亮偏灰)
 *  3. 云层次: 天区高亮云 (v>0.8) 做轻度局部对比, 压灰提白
 *
 * mask 高斯羽化后融合, 杜绝生硬边界。
 * 直方图统计天区占比 <3% 时跳过 (阴天/逆光无蓝天)。
 */
public final class SkyEnhance implements Stage {
    private float amount = 1.0f;

    public SkyEnhance amount(float a) { this.amount = a; return this; }

    @Override
    public String apply(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();
        float[] R = img.data[0], G = img.data[1], B = img.data[2];

        float[] mask = new float[n];
        float[] satBoost = new float[n];
        int skyPixels = 0;
        float[] hsv = new float[3];
        // 顶部连通性: 只有从图像顶开始的连续蓝色带才算天空 (湖面反光/蓝色屋顶不再误染)
        boolean skyOpen = true;
        for (int y = 0; y < h; y++) {
            float vert = 1f - (float) y / h;             // 顶部权重 (渐变)
            if (skyOpen) {
                // 当前行蓝色占比过低 → 天空带闭合, 之后不再纳入
                int blueRow = 0;
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    ColorOps.rgbToHsv(R[i], G[i], B[i], hsv);
                    if (hsv[0] >= 185f && hsv[0] <= 255f && hsv[1] > 0.12f && hsv[2] > 0.35f) blueRow++;
                }
                if (blueRow < w * 0.05f) skyOpen = false;
            }
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                ColorOps.rgbToHsv(R[i], G[i], B[i], hsv);
                float hue = hsv[0], sat = hsv[1], val = hsv[2];
                boolean blue = skyOpen && hue >= 182f && hue <= 255f && sat > 0.10f && val > 0.25f;
                if (blue) {
                    // 色相越接近纯蓝 mask 越强; 白云 (低饱和) 不动色相, 只保留云对比通道
                    float hueWeight = (1f - Math.min(1f, ColorOps.hueDist(hue, 215f) / 25f)) * (ColorOps.hueDist(hue, 215f) > 30f ? 0.3f : 1f);
                    mask[i] = hueWeight * Math.min(1f, val * 1.4f);
                    skyPixels++;
                }
                // 云: 天蓝区域中的高亮低饱和像素
                if (hue >= 182f && hue <= 255f && val > 0.78f && sat < 0.25f) {
                    satBoost[i] = 1f;
                }
            }
        }
        float skyRatio = (float) skyPixels / n;
        if (skyRatio < 0.03f) {
            return String.format("sky: 蓝天占比%.1f%%<3%%, 跳过", skyRatio * 100);
        }

        // 羽化
        float sigma = Math.max(4f, Math.min(w, h) / 150f);
        mask = Filters.gaussianBlur(mask, w, h, sigma);

        float a = 0.18f * amount;
        for (int i = 0; i < n; i++) {
            float m = mask[i] * amount;
            if (m < 0.003f) continue;
            ColorOps.rgbToHsv(R[i], G[i], B[i], hsv);
            float hue = hsv[0], sat = hsv[1], val = hsv[2];
            // 色相向 215° 拉近 (拉力与 m 成正比, 只拉近不推远)
            float dist = ColorOps.hueDist(hue, 215f);
            if (dist > 1f) {
                int dir = ((hue - 215f + 360f) % 360f) < 180f ? -1 : 1;
                hue = (hue + dir * Math.min(dist * 0.25f, 12f) * m + 360f) % 360f;
            }
            // 饱和 +18% (mask 加权)
            sat = Math.min(1f, sat * (1f + 0.10f * m));
            // 顶部渐变亮化: 天顶 +6%
            float vert = 1f - (float) (i / w) / h;
            val = Math.min(1f, val * (1f + 0.06f * m * vert));

            ColorOps.hsvToRgb(hue, sat, val, hsv);
            R[i] = hsv[0]; G[i] = hsv[1]; B[i] = hsv[2];
        }

        // 云对比: satBoost 模糊后对亮度做轻 S (压中间提两端)
        float[] cloudMask = Filters.gaussianBlur(satBoost, w, h, sigma * 2f);
        float[] L = ColorOps.lumaPlane(img);
        for (int i = 0; i < n; i++) {
            float cm = cloudMask[i];
            if (cm < 0.02f) continue;
            float l = L[i];
            float cl = l < 0.86f ? l - 0.045f * cm * (0.86f - l) * 4f : l;
            cl = Math.max(0f, Math.min(1f, cl));
            float base = Math.max(1e-4f, l);
            float ratio = cl / base;
            R[i] *= ratio; G[i] *= ratio; B[i] *= ratio;
        }

        return String.format("sky: 蓝天%.1f%%, 色相/饱和/渐变增强 + 云对比", skyRatio * 100);
    }
}
