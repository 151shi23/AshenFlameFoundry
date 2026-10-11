package com.zeus.landscape;

/**
 * 山体检测 (Mountain Detection) —— v8.2 新增核心算法。
 * 日照金山的门控: --golden 仅在检测到明显山体时生效。
 *
 * 物理近似 (无学习):
 *  1. 脊线强度: 行均值亮度在 10%~75% 高度内的最大突变 (山脊=天空与山体的分界)
 *  2. 脊线水平起伏: 脊线邻域内每列梯度峰值行位置的标准差 ——
 *     山脊起伏 (实测 0.015~0.023), 海平线平直 (实测 0.0008), 相差 20 倍 →
 *     起伏度是"山 vs 海平线/地平线"的决定性判据 (Python 五图验证 5/5)
 *
 * 阈值: undulation >= 0.012 判定山体存在。
 */
public final class MountainDetector {

    private MountainDetector() {}

    public static boolean hasMountain(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();
        float[] L = ColorOps.lumaPlane(img);

        // 行均值 + 相邻行差分
        float[] rowMean = new float[h];
        for (int y = 0; y < h; y++) {
            double s = 0;
            int base = y * w;
            for (int x = 0; x < w; x++) s += L[base + x];
            rowMean[y] = (float) (s / w);
        }
        float[] diff = new float[h - 1];
        float dynHi = 0, dynLo = 1;
        for (int i = 0; i < n; i++) {
            dynHi = Math.max(dynHi, L[i]);
            dynLo = Math.min(dynLo, L[i]);
        }
        float dyn = Math.max(1e-3f, dynHi - dynLo);
        int lo = (int) (h * 0.10), hi = (int) (h * 0.75);
        int ridgeY = lo;
        float ridgeS = 0;
        for (int y = lo; y < Math.min(h - 1, hi); y++) {
            diff[y] = Math.abs(rowMean[y + 1] - rowMean[y]);
            if (diff[y] > ridgeS) { ridgeS = diff[y]; ridgeY = y; }
        }
        float ridgeStrength = ridgeS / dyn;

        // 脊线水平起伏: 邻域每列梯度峰值行位置的标准差
        int band = (int) (h * 0.04) + 2;
        int y0 = Math.max(0, ridgeY - band), y1 = Math.min(h - 2, ridgeY + band);
        int cols = Math.max(8, w / 2);   // 隔列采样提速
        float[] colPeak = new float[cols];
        for (int k = 0; k < cols; k++) {
            int x = k * (w - 1) / Math.max(1, cols - 1);
            float best = 0;
            int bestY = ridgeY;
            for (int y = y0; y <= y1; y++) {
                float d = Math.abs(L[(y + 1) * w + x] - L[y * w + x]);
                if (d > best) { best = d; bestY = y; }
            }
            colPeak[k] = bestY;
        }
        float mean = 0;
        for (float v : colPeak) mean += v;
        mean /= cols;
        double var = 0;
        for (float v : colPeak) var += (v - mean) * (v - mean);
        float undulation = (float) (Math.sqrt(var / cols) / h);

        boolean isMountain = undulation >= 0.012f;
        return isMountain;
    }

    /** 诊断信息 (写处理日志) */
    public static String diag(FloatImage img) {
        int w = img.width, h = img.height, n = img.size();
        float[] L = ColorOps.lumaPlane(img);
        float[] rowMean = new float[h];
        for (int y = 0; y < h; y++) {
            double s = 0;
            for (int x = 0; x < w; x++) s += L[y * w + x];
            rowMean[y] = (float) (s / w);
        }
        int lo = (int) (h * 0.10), hi = (int) (h * 0.75);
        float ridgeS = 0;
        int ridgeY = lo;
        float dynHi = 0, dynLo = 1;
        for (int i = 0; i < n; i++) { dynHi = Math.max(dynHi, L[i]); dynLo = Math.min(dynLo, L[i]); }
        for (int y = lo; y < Math.min(h - 1, hi); y++) {
            float d = Math.abs(rowMean[y + 1] - rowMean[y]);
            if (d > ridgeS) { ridgeS = d; ridgeY = y; }
        }
        int band = (int) (h * 0.04) + 2;
        int y0 = Math.max(0, ridgeY - band), y1 = Math.min(h - 2, ridgeY + band);
        int cols = Math.max(8, w / 2);
        float[] colPeak = new float[cols];
        for (int k = 0; k < cols; k++) {
            int x = k * (w - 1) / Math.max(1, cols - 1);
            float best = 0;
            int bestY = ridgeY;
            for (int y = y0; y <= y1; y++) {
                float d = Math.abs(L[(y + 1) * w + x] - L[y * w + x]);
                if (d > best) { best = d; bestY = y; }
            }
            colPeak[k] = bestY;
        }
        float mean = 0;
        for (float v : colPeak) mean += v;
        mean /= cols;
        double var = 0;
        for (float v : colPeak) var += (v - mean) * (v - mean);
        return String.format("脊线=%.2f 强度=%.3f 起伏=%.4f", (float) ridgeY / h, ridgeS / Math.max(1e-3f, dynHi - dynLo), Math.sqrt(var / cols) / h);
    }
}
