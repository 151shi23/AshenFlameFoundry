package com.mineways.vr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 多张照片 → 一张等距柱状（2:1）全景图。**纯 Java，不依赖 OpenCV。**
 *
 * <h3>为什么自己写</h3>
 * Maven 上的 {@code org.opencv:opencv} AAR 只带 core / imgproc / dnn / ml 等模块，
 * <b>不含 stitching 和 features2d</b>，OpenCV 的 Stitcher 根本用不了；
 * 换成 opencv.org 的完整 SDK 又要多背几十 MB。而这里的素材主要是
 * 「同一台设备、同一个机位、只转了个方向」的截图/照片，用不着通用 SfM，
 * 一条柱面拼接就够，还完全可控、可测。
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>把每张图按估计的焦距当成「绕垂直轴转过的 hfov 度」；</li>
 *   <li>相邻两张用<b>归一化互相关</b>在重叠区里找水平偏移，累加成每张的朝向角；</li>
 *   <li>按等距柱状反投影逐像素采样（每张图只扫它覆盖的那几列），用余弦权重羽化接缝；</li>
 *   <li>没覆盖到的上下区域用最近的有效行延展（天空 / 地面），并轻微淡化。</li>
 * </ol>
 */
public final class PanoStitcher {

    /** 单张输入最大宽度，再大内存吃不消。 */
    public static final int MAX_INPUT_W = 2400;
    /** 最多拼几张。 */
    public static final int MAX_IMAGES = 16;
    /** 默认水平视场角：手机主摄大约 65~70°，游戏截图常见 70°。 */
    public static final float DEFAULT_HFOV = 70f;

    public interface Listener {
        void onProgress(String stage, int done, int total);
    }

    private PanoStitcher() {
    }

    // ============================================================ 对外

    public static Bitmap stitch(Context c, List<Uri> uris, int outW, int outH, Listener l)
            throws IOException {
        if (uris == null || uris.size() < 2) {
            throw new IOException("至少需要 2 张照片才能拼接");
        }
        if (uris.size() > MAX_IMAGES) {
            throw new IOException("最多 " + MAX_IMAGES + " 张，当前 " + uris.size() + " 张");
        }

        List<int[]> imgs = new ArrayList<>();
        int w = 0;
        int h = 0;
        try {
            for (int i = 0; i < uris.size(); i++) {
                Bitmap b = loadScaled(c, uris.get(i), MAX_INPUT_W);
                if (w == 0) {
                    w = b.getWidth();
                    h = b.getHeight();
                }
                int[] px = new int[b.getWidth() * b.getHeight()];
                b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                if (b.getWidth() != w || b.getHeight() != h) {
                    px = resize(px, b.getWidth(), b.getHeight(), w, h);
                }
                imgs.add(px);
                b.recycle();
                if (l != null) l.onProgress("读取照片", i + 1, uris.size());
            }
            int[] out = stitchCore(imgs, w, h, outW, outH, DEFAULT_HFOV, l);
            Bitmap bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            bmp.setPixels(out, 0, outW, 0, 0, outW, outH);
            return bmp;
        } finally {
            imgs.clear();
        }
    }

    /**
     * 核心拼接。纯 int[] 运算，可以在电脑上直接跑测试。
     *
     * @param imgs 每张图的 ARGB 像素（尺寸必须都是 w×h）
     */
    static int[] stitchCore(List<int[]> imgs, int w, int h, int outW, int outH,
                            float hfovDeg, Listener l) throws IOException {
        int n = imgs.size();
        if (n < 2) throw new IOException("至少 2 张");

        // ---- 1) 估每张的朝向角
        float[] yaw = new float[n];
        yaw[0] = 0f;
        for (int i = 1; i < n; i++) {
            float d = estimateDeltaDeg(imgs.get(i - 1), imgs.get(i), w, h, hfovDeg);
            yaw[i] = yaw[i - 1] + d;
            if (l != null) {
                l.onProgress("对齐第 " + (i + 1) + " 张（转 " + Math.round(d) + "°）", i, n - 1);
            }
        }

        // 完整一圈就保持第 1 张在正前方（用户拍的第一张就是"正面"）；
        // 只拍了一段才居中摆放。
        float span = Math.abs(yaw[n - 1] - yaw[0]) + hfovDeg;
        if (span < 340f) {
            float mid = (yaw[0] + yaw[n - 1]) * 0.5f;
            for (int i = 0; i < n; i++) yaw[i] -= mid;
        }

        double f = (w * 0.5) / Math.tan(Math.toRadians(hfovDeg * 0.5));
        int[] out = new int[outW * outH];
        int[] cnt = new int[outH];

        // ---- 2) 分块反投影
        // 全画布同时累加要 4 个 int 数组：4K 就是 118MB、8K 是 470MB，手机上必崩。
        // 按 128 行一块，峰值降到几 MB。
        final int TILE = 128;
        final float SCALE = 64f;      // 权重定点精度

        for (int ty = 0; ty < outH; ty += TILE) {
            int th = Math.min(TILE, outH - ty);
            int size = outW * th;
            int[] accR = new int[size];
            int[] accG = new int[size];
            int[] accB = new int[size];
            int[] wsum = new int[size];

            for (int k = 0; k < n; k++) {
                int[] src = imgs.get(k);
                for (int x = 0; x < outW; x++) {
                    double theta = ((x + 0.5) / outW - 0.5) * 360.0;
                    double dth = theta - yaw[k];
                    if (dth > 180) dth -= 360;
                    if (dth < -180) dth += 360;
                    if (Math.abs(dth) > hfovDeg * 0.5) continue;

                    double rad = Math.toRadians(dth);
                    double sx = f * Math.tan(rad) + w * 0.5;
                    if (sx < 0 || sx >= w) continue;

                    double cosd = Math.cos(rad);
                    if (cosd < 0.15) continue;

                    double u = dth / (hfovDeg * 0.5);
                    double cw = Math.cos(u * Math.PI * 0.5);
                    float wt = (float) (cw * cw);
                    if (wt <= 0.001f) continue;
                    int wi = Math.max(1, Math.round(wt * SCALE));

                    for (int yy = 0; yy < th; yy++) {
                        int y = ty + yy;
                        double psi = (0.5 - (y + 0.5) / outH) * Math.PI;
                        // psi 为正表示向上，图像里向上是 y 变小，所以是减号
                        double sy = h * 0.5 - (f * Math.tan(psi)) / cosd;
                        if (sy < 0 || sy >= h) continue;

                        int c = sample(src, w, h, sx, sy);
                        int idx = yy * outW + x;
                        accR[idx] += (((c >> 16) & 0xff) * wi);
                        accG[idx] += (((c >> 8) & 0xff) * wi);
                        accB[idx] += ((c & 0xff) * wi);
                        wsum[idx] += wi;
                        cnt[y]++;
                    }
                }
                if (l != null) l.onProgress("合成全景", k + 1, n);
            }

            // 写回这一块
            for (int yy = 0; yy < th; yy++) {
                int base = yy * outW;
                int row = (ty + yy) * outW;
                for (int x = 0; x < outW; x++) {
                    int idx = base + x;
                    int s = wsum[idx];
                    if (s <= 0) {
                        out[row + x] = 0xFF000000;
                        continue;
                    }
                    int r = accR[idx] / s;
                    int g = accG[idx] / s;
                    int b = accB[idx] / s;
                    out[row + x] = 0xFF000000
                            | (Math.min(255, r) << 16)
                            | (Math.min(255, g) << 8)
                            | Math.min(255, b);
                }
            }
        }

        // ---- 3) 补极点（没覆盖的行抄最近的有效行）
        int lastGood = -1;
        for (int y = 0; y < outH; y++) {
            if (cnt[y] == 0) {
                int srcRow = lastGood >= 0 ? lastGood : nextGood(cnt, y);
                if (srcRow < 0) srcRow = 0;
                System.arraycopy(out, srcRow * outW, out, y * outW, outW);
                continue;
            }
            lastGood = y;
        }

        fadePoles(out, outW, outH, cnt);
        return out;
    }

    // ============================================================ 对齐

    /**
     * 估相邻两张之间的转角（度）。
     *
     * <p>纯旋转拍摄时，两张图之间只差一个"绕垂直轴转了多少度"。
     * 所以先把每张图压成<b>一条角度域的一维亮度轮廓</b>（按 tan 关系取列、按行求均值），
     * 再在轮廓上做一维归一化互相关找位移 —— 位移量直接就是转角。
     *
     * <p>比在二维图上滑窗快得多，也不会被上下不同的天空/地面带偏。
     */
    static float estimateDeltaDeg(int[] a, int[] b, int w, int h, float hfovDeg) {
        final int N = 240;                       // 角度采样数
        float[] pa = profile(a, w, h, hfovDeg, N);
        float[] pb = profile(b, w, h, hfovDeg, N);

        double best = -2;
        int bestShift = 0;
        // 位移范围：至少转 2°，最多转 95% 的视场
        int minS = Math.max(1, Math.round(N * 2f / hfovDeg));
        int maxS = Math.round(N * 0.95f);

        for (int sh = minS; sh <= maxS; sh++) {
            double num = 0, da = 0, db = 0;
            int cnt = 0;
            for (int i = 0; i + sh < N; i++) {
                double va = pa[i + sh];          // A 在更靠右的角度
                double vb = pb[i];               // B 在同一角度
                num += va * vb;
                da += va * va;
                db += vb * vb;
                cnt++;
            }
            if (cnt < N / 3) continue;
            double ncc = num / (Math.sqrt(da * db) + 1e-6);
            if (ncc > best) {
                best = ncc;
                bestShift = sh;
            }
        }
        // pa[i+sh] ≈ pb[i]：A 在角度 θ+Δ 处的内容 == B 在 θ 处的内容，所以 Δ = sh
        return bestShift * hfovDeg / N;
    }

    /**
     * 把一张图压成角度域的亮度轮廓：第 i 个采样对应角度 -hfov/2 + i*hfov/(N-1)，
     * 取该角度对应的列（x = f*tan(θ) + w/2），再对中间 60% 的行求均值。
     */
    private static float[] profile(int[] src, int w, int h, float hfovDeg, int n) {
        double f = (w * 0.5) / Math.tan(Math.toRadians(hfovDeg * 0.5));
        int y0 = (int) (h * 0.2);
        int y1 = (int) (h * 0.8);
        if (y1 <= y0) { y0 = 0; y1 = h; }

        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double theta = Math.toRadians(-hfovDeg * 0.5 + hfovDeg * i / (n - 1.0));
            int x = (int) Math.round(f * Math.tan(theta) + w * 0.5);
            if (x < 0) x = 0;
            if (x >= w) x = w - 1;
            long sum = 0;
            int cnt = 0;
            for (int y = y0; y < y1; y++) {
                int c = src[y * w + x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                cnt++;
            }
            out[i] = cnt > 0 ? (float) (sum / (double) cnt) : 0f;
        }
        return out;
    }

    // ============================================================ 采样 / 工具

    private static int sample(int[] src, int w, int h, double x, double y) {
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);

        int c00 = src[y0 * w + x0];
        int c10 = src[y0 * w + x1];
        int c01 = src[y1 * w + x0];
        int c11 = src[y1 * w + x1];

        int r = lerp(lerp((c00 >> 16) & 0xff, (c10 >> 16) & 0xff, fx),
                lerp((c01 >> 16) & 0xff, (c11 >> 16) & 0xff, fx), fy);
        int g = lerp(lerp((c00 >> 8) & 0xff, (c10 >> 8) & 0xff, fx),
                lerp((c01 >> 8) & 0xff, (c11 >> 8) & 0xff, fx), fy);
        int b = lerp(lerp(c00 & 0xff, c10 & 0xff, fx),
                lerp(c01 & 0xff, c11 & 0xff, fx), fy);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    private static int nextGood(int[] cnt, int from) {
        for (int y = from; y < cnt.length; y++) if (cnt[y] > 0) return y;
        for (int y = from; y >= 0; y--) if (cnt[y] > 0) return y;
        return -1;
    }

    private static void fadePoles(int[] pix, int w, int h, int[] cnt) {
        int top = 0;
        while (top < h && cnt[top] == 0) top++;
        int bottom = h - 1;
        while (bottom >= 0 && cnt[bottom] == 0) bottom--;
        int bandTop = Math.max(1, top);
        int bandBottom = Math.max(1, h - 1 - bottom);
        for (int y = 0; y < bandTop; y++) {
            float t = (1f - y / (float) bandTop) * 0.7f;
            blendRow(pix, w, y, t, 0x2A3440);
        }
        for (int y = 0; y < bandBottom; y++) {
            float t = (1f - y / (float) bandBottom) * 0.7f;
            blendRow(pix, w, h - 1 - y, t, 0x3A3226);
        }
    }

    private static void blendRow(int[] pix, int w, int row, float t, int target) {
        int tr = (target >> 16) & 0xff, tg = (target >> 8) & 0xff, tb = target & 0xff;
        int base = row * w;
        for (int x = 0; x < w; x++) {
            int c = pix[base + x];
            int r = (int) (((c >> 16) & 0xff) * (1 - t) + tr * t);
            int g = (int) (((c >> 8) & 0xff) * (1 - t) + tg * t);
            int b = (int) ((c & 0xff) * (1 - t) + tb * t);
            pix[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static int[] grayResize(int[] src, int w, int h, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = y * h / nh;
            for (int x = 0; x < nw; x++) {
                int sx = x * w / nw;
                int c = src[sy * w + sx];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                out[y * nw + x] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        }
        return out;
    }

    private static int[] resize(int[] src, int w, int h, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = y * h / nh;
            for (int x = 0; x < nw; x++) {
                out[y * nw + x] = src[sy * w + x * w / nw];
            }
        }
        return out;
    }

    private static Bitmap loadScaled(Context c, Uri uri, int maxW) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= maxW) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new IOException("解码失败：" + uri);
        if (bmp.getWidth() > maxW) {
            int h = Math.max(2, Math.round(bmp.getHeight() * (maxW / (float) bmp.getWidth())));
            Bitmap s2 = Bitmap.createScaledBitmap(bmp, maxW, h, true);
            if (s2 != bmp) bmp.recycle();
            bmp = s2;
        }
        return bmp;
    }
}
