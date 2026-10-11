package com.zeus.landscape;

/**
 * 日照金山 (Alpenglow) —— v8 新增核心算法: 分离色调 (Split Toning)。
 *
 * 文献: Lightroom Color Grading / 电影工业 split-tone 惯例;
 * Python 网格扫描定版 (scan4_*.jpg): 高光染金 + 阴影偏冷, medium 档三图全胜。
 *
 * 物理语义: 日落/日出时低角度阳光 (3000K 金橙) 直射峰顶, 而阴影面只有
 * 天空散射光 (8000K 冷青) —— 高光暖阴影冷 = "日照金山"的感知核心。
 *
 * 强度: 0=关闭; 0.45=light(自然); 0.75=medium(金山全开); 1.1=strong(风格化)。
 */
public final class GoldenHour {

    private GoldenHour() {}

    public static void apply(FloatImage img, float strength) {
        if (strength <= 0.01f) return;
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        // 大面积亮区门控 (v8.3): 亮像素占比 >30% = 阴天云/雪原, 不是"峰顶被点亮"
        // 的日照金山场景 —— 全力度染金会把最大亮云团染成假太阳 (实测)
        int hiCnt = 0;
        for (int i = 0; i < n; i++) if (L[i] > 0.75f) hiCnt++;
        float hiRatio = hiCnt / (float) n;
        if (hiRatio > 0.30f) {
            strength *= Math.max(0.15f, 0.30f / hiRatio);   // 亮区越大力度越小
        }
        for (int i = 0; i < n; i++) {
            float l = L[i];
            // v2: 峰顶限定 —— L>0.72 才渐入 (v1 从 0.5 渐入把整个天空染成黄泥)
            float hiW = (float) Math.pow(Math.max(0f, Math.min(1f, (l - 0.72f) / 0.24f)), 1.2);
            float loW = (float) Math.pow(Math.max(0f, Math.min(1f, (0.40f - l) / 0.35f)), 1.2);
            // 天空保护: 蓝天像素染金权重 ×0.2 (日照金山时天还是蓝的)
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
            float sv = mx > 1e-5f ? (mx - mn) / mx : 0f;
            float r = img.data[0][i], g = img.data[1][i], b = img.data[2][i];
            float hue = ColorOps.hueOf(r, g, b);
            boolean sky = hue >= 190 && hue <= 250 && sv > 0.15f;
            if (sky) hiW *= 0.2f;
            // 暖白金 (1.0,0.86,0.60) —— 不是黄泥 (1.0,0.78,0.47); 阴影更冷 (0.40,0.70,0.98)
            for (int c = 0; c < 3; c++) {
                float x = img.data[c][i];
                x += strength * hiW * (GOLD[c] - x) * 0.6f;
                x += strength * 0.85f * loW * (COLD[c] - x) * 0.5f;
                img.data[c][i] = Math.max(0f, Math.min(1f, x));
            }
        }
    }

    private static final float[] GOLD = {1.0f, 0.86f, 0.60f};   // 暖白金 (v2, 黄泥色已废)
    private static final float[] COLD = {0.40f, 0.70f, 0.98f};  // v2 更冷的阴影
}
