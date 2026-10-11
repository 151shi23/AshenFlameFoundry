package com.mineways;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF 编解码（纯 Java，不依赖第三方库）。
 *
 * <ul>
 *   <li><b>编码</b>：GIF89a + 全局调色板（6×7×6=252 色）+ 1 位透明 + LZW，支持循环。</li>
 *   <li><b>解码</b>：用系统 {@link Movie}（老 API 但系统可用），取出合成后的每一帧。</li>
 * </ul>
 */
public final class GifTool {

    /** 透明色所在调色板索引（0~251 为实色）。 */
    public static final int TRANSPARENT_INDEX = 252;

    private GifTool() {
    }

    // ---------------------------------------------------------------- 编码

    /** 多帧 → GIF89a 字节流。帧尺寸不一致时统一缩放到第一帧尺寸。 */
    public static byte[] encode(List<Bitmap> frames, int delayMs, boolean loop) throws Exception {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("没有可用的帧");
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("帧尺寸不合法");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        // 文件头
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(out, w);
        writeShort(out, h);
        out.write(0xF7);   // 有全局色表 · 色深 8 · 色表 256 项
        out.write(0);      // 背景色索引
        out.write(0);      // 像素宽高比
        for (int i = 0; i < 256; i++) {
            out.write(palR(i));
            out.write(palG(i));
            out.write(palB(i));
        }

        // 循环扩展
        if (loop) {
            out.write(0x21);
            out.write(0xFF);
            out.write(0x0B);
            out.write("NETSCAPE2.0".getBytes("US-ASCII"));
            out.write(0x03);
            out.write(0x01);
            writeShort(out, 0);   // 0 = 无限循环
            out.write(0);
        }

        int delayCs = Math.max(2, delayMs / 10);
        for (Bitmap frame : frames) {
            Bitmap bmp = frame;
            if (frame.getWidth() != w || frame.getHeight() != h) {
                bmp = Bitmap.createScaledBitmap(frame, w, h, true);
            }
            // 图形控制扩展：处置方式=不处置、透明色
            out.write(0x21);
            out.write(0xF9);
            out.write(0x04);
            out.write(0x05);        // 000 001 0 1 → disposal=1, 允许透明
            writeShort(out, delayCs);
            out.write(TRANSPARENT_INDEX);
            out.write(0);
            // 图像描述符
            out.write(0x2C);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, w);
            writeShort(out, h);
            out.write(0);
            writeLzw(out, quantize(bmp, w, h), 8);
            if (bmp != frame) {
                bmp.recycle();
            }
        }

        out.write(0x3B);   // 结束
        return out.toByteArray();
    }

    /** ARGB → 调色板索引（α<128 视为透明）。 */
    private static byte[] quantize(Bitmap bmp, int w, int h) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] out = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (((c >>> 24) & 0xFF) < 128) {
                out[i] = (byte) TRANSPARENT_INDEX;
                continue;
            }
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int r6 = (r * 6) / 256, g7 = (g * 7) / 256, b6 = (b * 6) / 256;
            out[i] = (byte) ((r6 * 7 + g7) * 6 + b6);
        }
        return out;
    }

    private static int palR(int i) {
        if (i >= 252) {
            return 0x15;
        }
        return ((i / 42) % 6) * 255 / 5;
    }

    private static int palG(int i) {
        if (i >= 252) {
            return 0x17;
        }
        return ((i / 6) % 7) * 255 / 6;
    }

    private static int palB(int i) {
        if (i >= 252) {
            return 0x1A;
        }
        return (i % 6) * 255 / 5;
    }

    // ---------------------------------------------------------------- 解码

    /** GIF → 帧（合成后画面，最多 96 帧，步长 40ms 或按总时长均分）。 */
    public static List<Bitmap> decode(InputStream in, int maxFrames) throws Exception {
        byte[] all = readAllBytes(in);
        Movie movie = Movie.decodeByteArray(all, 0, all.length);
        if (movie == null) {
            throw new IllegalArgumentException("这不是一张能解析的 GIF（或系统解码器不支持）");
        }
        int w = movie.width(), h = movie.height();
        int duration = Math.max(movie.duration(), 80);
        int cap = maxFrames <= 0 ? 96 : maxFrames;
        int step = Math.max(40, duration / cap);
        List<Bitmap> frames = new ArrayList<>();
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int t = 0; t < duration && frames.size() < cap; t += step) {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            movie.setTime(t);
            movie.draw(c, 0, 0, p);
            frames.add(bmp);
        }
        if (frames.isEmpty()) {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            movie.setTime(0);
            movie.draw(c, 0, 0, p);
            frames.add(bmp);
        }
        return frames;
    }

    /** 帧垂直拼接（材质包动画贴图格式：宽度 = 单帧宽，高度 = 帧数 × 单帧高）。 */
    public static Bitmap stackVertical(List<Bitmap> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        Bitmap out = Bitmap.createBitmap(w, h * frames.size(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        for (int i = 0; i < frames.size(); i++) {
            c.drawBitmap(frames.get(i), 0, i * h, null);
        }
        return out;
    }

    /** 材质包动画贴图配套的 .mcmeta 文本。 */
    public static String mcmeta(int frametimeTicks) {
        return "{\n"
                + "  \"animation\": {\n"
                + "    \"frametime\": " + Math.max(1, frametimeTicks) + ",\n"
                + "    \"interpolate\": false\n"
                + "  }\n"
                + "}\n";
    }

    // ---------------------------------------------------------------- 底层

    private static void writeShort(OutputStream out, int v) throws Exception {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    /** GIF LZW 压缩：写最小码长、若干子块、结束 0x00。 */
    private static void writeLzw(OutputStream out, byte[] indices, int minCodeSize) throws Exception {
        out.write(minCodeSize);
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int codeSize = minCodeSize + 1;
        int nextCode = eoiCode + 1;

        BitPacker packer = new BitPacker();
        java.util.HashMap<Integer, Integer> dict = new java.util.HashMap<>(8192);
        packer.write(clearCode, codeSize);

        int prefix = -1;
        for (byte raw : indices) {
            int k = raw & 0xFF;
            if (prefix < 0) {
                prefix = k;
                continue;
            }
            int key = (prefix << 8) | k;
            Integer code = dict.get(key);
            if (code != null) {
                prefix = code;
                continue;
            }
            packer.write(prefix, codeSize);
            if (nextCode < 4096) {
                dict.put(key, nextCode);
                if (nextCode == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
                nextCode++;
            } else {
                packer.write(clearCode, codeSize);
                dict.clear();
                nextCode = eoiCode + 1;
                codeSize = minCodeSize + 1;
            }
            prefix = k;
        }
        if (prefix >= 0) {
            packer.write(prefix, codeSize);
        }
        packer.write(eoiCode, codeSize);
        packer.flushBits();

        byte[] data = packer.toByteArray();
        int off = 0;
        while (off < data.length) {
            int n = Math.min(255, data.length - off);
            out.write(n);
            out.write(data, off, n);
            off += n;
        }
        out.write(0);
    }

    /** GIF 的位打包是低位优先。 */
    private static final class BitPacker {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
        private int acc;
        private int bits;

        void write(int code, int size) {
            acc |= (code << bits);
            bits += size;
            while (bits >= 8) {
                buf.write(acc & 0xFF);
                acc >>>= 8;
                bits -= 8;
            }
        }

        void flushBits() {
            if (bits > 0) {
                buf.write(acc & 0xFF);
                acc = 0;
                bits = 0;
            }
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }
    }
}
