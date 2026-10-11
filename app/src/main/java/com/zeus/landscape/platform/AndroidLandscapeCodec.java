package com.zeus.landscape.platform;

import android.graphics.Bitmap;

import com.zeus.landscape.FloatImage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 安卓侧的平台桥接：{@code android.graphics.Bitmap ↔ FloatImage}。
 *
 * <p>核心算法包 {@code com.zeus.landscape.*} 全是纯 Java 数值运算，零平台依赖；桌面端那一层
 * 边界是 {@code ImgIO}（{@code java.awt} + {@code javax.imageio}，安卓没有），这里就是它的替身。</p>
 *
 * <p><b>映射必须与桌面逐位一致</b>：{@code ImgIO.fromBufferedImage} 是 8bit 分量直接 {@code /255f}
 * （即 sRGB 编码值，不做 EOTF 线性化），写出端是 {@code (int)(v*255f+0.5f)} 四舍五入。
 * 换一种换算（比如在这里先转线性）画面观感就变了，也就没法拿桌面的基准图对答案。</p>
 *
 * <p>透明像素按桌面行为处理：{@code ImgIO.read} 把图 {@code drawImage} 到 {@code TYPE_INT_RGB}
 * 上，等于合成到黑底 —— 所以这里对 alpha 做一次预乘，别让半透明 PNG 走出一条不同的路。</p>
 */
public final class AndroidLandscapeCodec {

    private AndroidLandscapeCodec() {
    }

    /** 输出 JPEG 的质量，与桌面端 {@code ImgIO.writeJpeg(..., 0.95f)} 对齐。 */
    public static final int JPEG_QUALITY = 95;

    /** Bitmap → FloatImage，RGB 各一个 float[]，取值 [0,1] 的 sRGB 编码值。 */
    public static FloatImage decode(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        FloatImage image = new FloatImage(w, h);
        float[] r = image.data[0];
        float[] g = image.data[1];
        float[] b = image.data[2];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int a = (p >>> 24) & 0xFF;
            if (a == 255) {
                r[i] = ((p >> 16) & 0xFF) / 255f;
                g[i] = ((p >> 8) & 0xFF) / 255f;
                b[i] = (p & 0xFF) / 255f;
            } else {
                // 合成到黑底：与桌面 drawImage 到 TYPE_INT_RGB 的结果一致
                float k = a / 255f;
                r[i] = ((p >> 16) & 0xFF) * k / 255f;
                g[i] = ((p >> 8) & 0xFF) * k / 255f;
                b[i] = (p & 0xFF) * k / 255f;
            }
        }
        return image;
    }

    /** FloatImage → Bitmap（自动 clamp，不透明度拉满）。 */
    public static Bitmap encode(FloatImage image) {
        int w = image.width;
        int h = image.height;
        int[] pixels = new int[w * h];
        float[] r = image.data[0];
        float[] g = image.data[1];
        float[] b = image.data[2];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (clamp8(r[i]) << 16) | (clamp8(g[i]) << 8) | clamp8(b[i]);
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    /** FloatImage → JPEG 输出流（内部临时 Bitmap 用完即回收）。 */
    public static void writeJpeg(FloatImage image, OutputStream out, int quality) throws IOException {
        Bitmap bitmap = encode(image);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("JPEG 编码失败");
            }
            out.flush();
        } finally {
            bitmap.recycle();
        }
    }

    private static int clamp8(float v) {
        int x = (int) (Math.max(0f, Math.min(1f, v)) * 255f + 0.5f);
        return Math.max(0, Math.min(255, x));
    }
}
