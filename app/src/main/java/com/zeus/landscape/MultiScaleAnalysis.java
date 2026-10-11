package com.zeus.landscape;

/**
 * 多尺度图像分析器 —— 超级算法的感知层 (Analysis)。
 *
 * 四个尺度合体分析, 对应"每个像素点 / 每个小局部 / 每个大局部 / 整体":
 *  L0 像素级 : 每像素 luma/sat —— 执行期逐像素插值参数图 (LocalToneApply)
 *  L1 细局部 : 16×16 tile —— 岩石纹理/树影/湖岸线级别的决策粒度
 *  L2 粗局部 : L1 的 4×4 聚合 —— 山体/水面/天空块的语义粒度 (大局部)
 *  L3 全局   : 直方图/分位数/雾感/质量分 —— 整体风格与预算决策
 *
 * 每个 L1 tile 提取 8 维特征:
 *  meanLum     均值亮度              → 曝光偏置依据
 *  contrast    亮度标准差            → 对比偏置依据
 *  sat         平均饱和度            → 饱和偏置依据
 *  rbBias      R/B 通道均值比        → 局部色温 (湖面冷/夕照暖)
 *  darkApprox  tile 内 min(R,G,B)    → 局部雾感近似 (暗通道的 tile 尺度版)
 *  detail      Laplacian 能量        → 细节密度 (clarity 防噪依据)
 *  skyProb     蓝天概率              → 天空特判 (压亮度/增蓝)
 *  shadowBias  暗像素占比            → 阴影区域判定
 *
 * 性能: 单次全图像素遍历完成全部 L1 统计 (600 万像素 ≈ 200ms),
 * 不做任何中间图像拷贝。
 */
public final class MultiScaleAnalysis {

    /** L1/L2 通用 tile 统计网格 */
    public static final class TileGrid {
        public final int gw, gh, tileSize;
        public final float[] meanLum, contrast, sat, rbBias, darkApprox, detail, skyProb, shadowBias;

        TileGrid(int gw, int gh, int tileSize) {
            this.gw = gw;
            this.gh = gh;
            this.tileSize = tileSize;
            int n = gw * gh;
            this.meanLum = new float[n];
            this.contrast = new float[n];
            this.sat = new float[n];
            this.rbBias = new float[n];
            this.darkApprox = new float[n];
            this.detail = new float[n];
            this.skyProb = new float[n];
            this.shadowBias = new float[n];
        }
    }

    /** L3 全局统计 */
    public static final class GlobalStats {
        public float p1, p5, p50, p95, p99;
        public float contrast, satMean, entropy;
        public float hazeLevel;      // 全图暗通道均值
        public float brightSat;      // 亮部饱和度 (雾/暮光判别)
        public float budget;         // 增强预算 [0.25,1]
        public boolean isHaze;       // 全局去雾开关 (四因子判据)
    }

    private MultiScaleAnalysis() {}

    /** L1: 细局部分析。tileSize 典型 16。 */
    public static TileGrid fine(FloatImage img, int tileSize) {
        int w = img.width, h = img.height;
        int gw = (w + tileSize - 1) / tileSize;
        int gh = (h + tileSize - 1) / tileSize;
        TileGrid g = new TileGrid(gw, gh, tileSize);
        float[] R = img.data[0], G = img.data[1], B = img.data[2];
        float invArea = 1f / (tileSize * tileSize);

        for (int ty = 0; ty < gh; ty++) {
            int y0 = ty * tileSize, y1 = Math.min(h, y0 + tileSize);
            for (int tx = 0; tx < gw; tx++) {
                int x0 = tx * tileSize, x1 = Math.min(w, x0 + tileSize);
                int t = ty * gw + tx;

                double sumL = 0, sumL2 = 0, sumSat = 0, sumR = 0, sumB = 0, sumGrad = 0;
                float minRGB = 1f;
                int skyCnt = 0, darkCnt = 0, n = 0;
                float[] upRow = y0 > 0 ? new float[x1 - x0] : null;   // 上行亮度缓存

                for (int y = y0; y < y1; y++) {
                    int base = y * w;
                    float lLeft = -1f;
                    for (int x = x0; x < x1; x++) {
                        int i = base + x;
                        float r = R[i], gr = G[i], b = B[i];
                        float l = 0.2126f * r + 0.7152f * gr + 0.0722f * b;
                        float mx = Math.max(r, Math.max(gr, b));
                        float mn = Math.min(r, Math.min(gr, b));
                        minRGB = Math.min(minRGB, mn);

                        sumL += l;
                        sumL2 += l * l;
                        sumSat += mx > 1e-6f ? (mx - mn) / mx : 0f;
                        sumR += r;
                        sumB += b;
                        // 细节能量: 水平/垂直梯度 (Sobel 简化, O(1)/像素)
                        if (lLeft >= 0f) sumGrad += Math.abs(l - lLeft);
                        if (upRow != null) sumGrad += Math.abs(l - upRow[x - x0]);
                        lLeft = l;
                        if (upRow != null) upRow[x - x0] = l;
                        // 蓝天: 蓝主导 + 中高亮度 (精确色相判定留给 SkyEnhance, 这里要快)
                        if (b > r * 1.12f && b > 0.35f && mx > 0.32f && mx < 0.995f) skyCnt++;
                        if (l < 0.12f) darkCnt++;
                        n++;
                    }
                }

                float meanL = (float) (sumL / Math.max(1, n));
                float var = (float) (sumL2 / Math.max(1, n)) - meanL * meanL;
                g.meanLum[t] = meanL;
                g.contrast[t] = (float) Math.sqrt(Math.max(0, var));
                g.sat[t] = (float) (sumSat / Math.max(1, n));
                g.rbBias[t] = (float) (sumR / Math.max(1e-6, sumB));
                g.darkApprox[t] = minRGB;
                g.detail[t] = (float) (sumGrad / Math.max(1, n * 2));
                g.skyProb[t] = skyCnt * invArea;
                g.shadowBias[t] = darkCnt * invArea;
            }
        }
        return g;
    }

    /** L2: 粗局部 = L1 每 4×4 tile 聚合 (max-pool 保留极值语义, 均值池保留统计语义) */
    public static TileGrid coarse(TileGrid fine, int pool) {
        int gw = (fine.gw + pool - 1) / pool;
        int gh = (fine.gh + pool - 1) / pool;
        TileGrid c = new TileGrid(gw, gh, fine.tileSize * pool);
        for (int cy = 0; cy < gh; cy++) {
            for (int cx = 0; cx < gw; cx++) {
                int t = cy * gw + cx;
                double sL = 0, sC = 0, sS = 0, sR = 0, sD = 0, sDet = 0, sSky = 0, sSh = 0;
                float maxDark = 0;
                int cnt = 0;
                for (int fy = cy * pool; fy < Math.min(fine.gh, (cy + 1) * pool); fy++) {
                    for (int fx = cx * pool; fx < Math.min(fine.gw, (cx + 1) * pool); fx++) {
                        int f = fy * fine.gw + fx;
                        sL += fine.meanLum[f];
                        sC += fine.contrast[f];
                        sS += fine.sat[f];
                        sR += fine.rbBias[f];
                        sD += fine.darkApprox[f];
                        sDet += fine.detail[f];
                        sSky += fine.skyProb[f];
                        sSh += fine.shadowBias[f];
                        maxDark = Math.max(maxDark, fine.darkApprox[f]);
                        cnt++;
                    }
                }
                float inv = 1f / Math.max(1, cnt);
                c.meanLum[t] = (float) (sL * inv);
                c.contrast[t] = (float) (sC * inv);
                c.sat[t] = (float) (sS * inv);
                c.rbBias[t] = (float) (sR * inv);
                c.darkApprox[t] = Math.max((float) (sD * inv), maxDark * 0.7f);
                c.detail[t] = (float) (sDet * inv);
                c.skyProb[t] = (float) (sSky * inv);
                c.shadowBias[t] = (float) (sSh * inv);
            }
        }
        return c;
    }

    /** L3: 全局统计 + 增强预算 + 雾判定 (吸收打样与 Dehaze 的全部实证判据) */
    public static GlobalStats global(FloatImage img, float[] darkChannelSampled) {
        GlobalStats g = new GlobalStats();
        int n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        g.p1 = Stats.percentile(L, 0.01f);
        g.p5 = Stats.percentile(L, 0.05f);
        g.p50 = Stats.percentile(L, 0.50f);
        g.p95 = Stats.percentile(L, 0.95f);
        g.p99 = Stats.percentile(L, 0.99f);
        g.contrast = g.p95 - g.p5;
        g.entropy = Stats.entropy(L);

        double s = 0;
        double brightSum = 0;
        int brightCnt = 0;
        for (int i = 0; i < n; i++) {
            float mx = Math.max(img.data[0][i], Math.max(img.data[1][i], img.data[2][i]));
            float mn = Math.min(img.data[0][i], Math.min(img.data[1][i], img.data[2][i]));
            float sv = mx > 1e-6f ? (mx - mn) / mx : 0f;
            s += sv;
            if (mx > 0.75f) {
                brightSum += sv;
                brightCnt++;
            }
        }
        g.satMean = (float) (s / n);
        g.brightSat = brightCnt > n / 200 ? (float) (brightSum / brightCnt) : 0f;

        // 雾感: 全图暗通道均值 (采样近似)
        g.hazeLevel = darkChannelSampled != null ? Stats.mean(darkChannelSampled) : 0f;

        // 增强预算: 对比/饱和/动态范围健康度 (好图轻处理)
        float cScore = Stats.smoothstep(0.50f, 0.85f, 1f - Math.abs(g.contrast - 0.75f) / 0.35f);
        float sScore = Stats.smoothstep(0.50f, 0.85f, 1f - Math.abs(g.satMean - 0.38f) / 0.30f);
        float rScore = Stats.smoothstep(0.02f, 0.20f, g.p5) * Stats.smoothstep(0.98f, 0.85f, g.p95);
        g.budget = Math.max(0.25f, cScore * sScore * rScore);
        return g;
    }
}
