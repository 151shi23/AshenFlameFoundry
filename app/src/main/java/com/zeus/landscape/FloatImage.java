package com.zeus.landscape;

/**
 * 浮点 RGB 图像容器。通道平面存储（planar）：R/G/B 各一个 float[]，取值 [0,1]。
 * 平面存储对逐通道滤波（box/guided/min）缓存友好。
 *
 * 全程 float 单精度：8bit JPEG 读入后归一化，管线内不做量化，
 * 避免 8bit 串级处理的带状伪影（banding）。
 */
public final class FloatImage {
    public final int width, height;
    /** 平面数据: data[c][y*width + x], c ∈ {0=R, 1=G, 2=B} */
    public final float[][] data;

    public FloatImage(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new float[3][width * height];
    }

    public FloatImage(int width, int height, float[][] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public float get(int c, int x, int y) {
        return data[c][y * width + x];
    }

    public void set(int c, int x, int y, float v) {
        data[c][y * width + x] = v;
    }

    public void add(int c, int x, int y, float dv) {
        data[c][y * width + x] += dv;
    }

    public int size() {
        return width * height;
    }

    /** 深拷贝 */
    public FloatImage copy() {
        return new FloatImage(width, height, new float[][]{
                data[0].clone(), data[1].clone(), data[2].clone()});
    }

    /** 将全部像素 clamp 到 [0,1] */
    public void clampInPlace() {
        for (int c = 0; c < 3; c++) {
            float[] ch = data[c];
            for (int i = 0; i < ch.length; i++) {
                float v = ch[i];
                ch[i] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
        }
    }

    /** Rec.709 亮度 (gamma 空间的 luma) */
    public float[] luminance() {
        float[] L = new float[size()];
        for (int i = 0; i < L.length; i++) {
            L[i] = 0.2126f * data[0][i] + 0.7152f * data[1][i] + 0.0722f * data[2][i];
        }
        return L;
    }

    /** 在 HSV 域做任意逐像素操作（避免反复转换的精度损失, 每次进入返回 H/S/V 数组） */
    public float[][] toHsv() {
        int n = size();
        float[] H = new float[n], S = new float[n], V = new float[n];
        for (int i = 0; i < n; i++) {
            float r = data[0][i], g = data[1][i], b = data[2][i];
            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            float d = max - min;
            float h;
            if (d < 1e-7f) {
                h = 0;
            } else if (max == r) {
                h = 60f * (((g - b) / d) % 6f);
            } else if (max == g) {
                h = 60f * ((b - r) / d + 2f);
            } else {
                h = 60f * ((r - g) / d + 4f);
            }
            if (h < 0) h += 360f;
            H[i] = h;
            S[i] = (max < 1e-7f) ? 0 : d / max;
            V[i] = max;
        }
        return new float[][]{H, S, V};
    }

    /** 用 H/S/V 数组覆写 RGB（H 不变域, S/V 已被外部修改） */
    public void setHsv(float[] H, float[] S, float[] V) {
        int n = size();
        for (int i = 0; i < n; i++) {
            float h = H[i] % 360f;
            if (h < 0) h += 360f;
            float s = Math.max(0, Math.min(1, S[i]));
            float v = Math.max(0, Math.min(1, V[i]));
            int hi = (int) (h / 60f) % 6;
            float f = h / 60f - hi;
            float p = v * (1 - s);
            float q = v * (1 - f * s);
            float t = v * (1 - (1 - f) * s);
            switch (hi) {
                case 0 -> { data[0][i] = v; data[1][i] = t; data[2][i] = p; }
                case 1 -> { data[0][i] = q; data[1][i] = v; data[2][i] = p; }
                case 2 -> { data[0][i] = p; data[1][i] = v; data[2][i] = t; }
                case 3 -> { data[0][i] = p; data[1][i] = q; data[2][i] = v; }
                case 4 -> { data[0][i] = t; data[1][i] = p; data[2][i] = v; }
                default -> { data[0][i] = v; data[1][i] = p; data[2][i] = q; }
            }
        }
    }

    }
