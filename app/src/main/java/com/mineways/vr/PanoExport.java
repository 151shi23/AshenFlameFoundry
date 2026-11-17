package com.mineways.vr;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 「一张 360 全景图 → 一段能发抖音的 360 视频」的完整流水线。
 *
 * <ol>
 *   <li>按需把图缩到 4K 宽（再大手机纹理和显存都吃不住）；</li>
 *   <li>{@link GlEncoder} 逐帧渲染并硬编成 MP4；</li>
 *   <li>{@link SphericalMeta} 注入球形元数据（V1 + V2）；</li>
 *   <li>拷进公共 Download。</li>
 * </ol>
 */
public final class PanoExport {

    /** 抖音要求 4K 及以上；2:1 的 4K 就是 3840×1920。 */
    public static final int OUT_W = 3840;
    public static final int OUT_H = 1920;

    public static final String FOLDER = "Mineways全景";

    /** 分辨率档位。 */
    public static final int RES_4K = 0;
    public static final int RES_8K = 1;
    /** 画质档位（影响码率）。 */
    public static final int Q_STD = 0;
    public static final int Q_HIGH = 1;
    public static final int Q_MAX = 2;
    /** 输出模式。 */
    public static final int MODE_360 = 0;    // 3D 全景：等距柱状，抖音可转头
    public static final int MODE_FLAT = 1;   // 2D 平面：普通透视视频

    public static final class Options {
        public int resolution = RES_4K;
        public int quality = Q_HIGH;
        public int mode = MODE_360;
        public int fps = 30;
        public int seconds = 6;
        public float yawSpeed = 6f;
        public float fov = 90f;
        public String software = "MinewaysMobile";

        /** 目标尺寸：4K 全景 = 3840×1920；4K 平面 = 3840×2160；8K 同理翻倍。 */
        public int[] size() {
            boolean big = resolution == RES_8K;
            if (mode == MODE_FLAT) {
                return big ? new int[]{7680, 4320} : new int[]{3840, 2160};
            }
            return big ? new int[]{7680, 3840} : new int[]{3840, 1920};
        }

        /** 码率系数（百分比）。 */
        public int bpp() {
            return quality == Q_STD ? 8 : (quality == Q_MAX ? 20 : 12);
        }
    }

    public interface Listener {
        /** @param stage 阶段说明，例如「渲染编码」「写入元数据」 */
        void onProgress(String stage, int done, int total);
    }

    public static final class Result {
        public final File file;
        public final String displayPath;

        Result(File f, String p) {
            this.file = f;
            this.displayPath = p;
        }
    }

    private PanoExport() {
    }

    /** 从 Uri 读图，并按最大宽度做降采样（避免 OOM）。 */
    public static Bitmap loadPano(Context c, Uri uri, int maxWidth) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("读不出图片尺寸");
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new IOException("解码图片失败");

        if (bmp.getWidth() > maxWidth) {
            int h = Math.max(2, Math.round(bmp.getHeight() * (maxWidth / (float) bmp.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, maxWidth, (h / 2) * 2, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    /**
     * 执行导出。必须在后台线程调用（内部会做几十秒的重活）。
     *
     * @param pano 等距柱状全景图（2:1）
     */
    public static Result export(Context c, Bitmap pano, Options opt, Listener l)
            throws Exception {
        if (pano == null) throw new IOException("没有图片");

        // 先按用户选的分辨率，再看本机编码器吃不吃得下（8K 只有少数机器支持）
        int[] want = opt.size();
        int[] max = GlEncoder.maxEncodeSize();
        int w = (want[0] / 2) * 2;
        int h = (want[1] / 2) * 2;
        if (w > max[0] || h > max[1]) {
            float k = Math.min(max[0] / (float) w, max[1] / (float) h);
            w = ((int) (w * k) / 2) * 2;
            h = ((int) (h * k) / 2) * 2;
            if (l != null) {
                l.onProgress("本机编码器最高 " + max[0] + "×" + max[1]
                        + "，已降到 " + w + "×" + h, 0, 1);
            }
        }
        final int fw = w;
        final int fh = h;

        File tmp = new File(c.getCacheDir(), "pano_render.mp4");
        File signed = new File(c.getCacheDir(), "pano_signed.mp4");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        //noinspection ResultOfMethodCallIgnored
        signed.delete();

        final PanoRenderer renderer = new PanoRenderer()
                .size(fw, fh)
                .mode(opt.mode)
                .fov(opt.fov)
                .yawSpeed(opt.yawSpeed);

        final boolean[] inited = {false};
        final Bitmap src = pano;

        try {
            if (l != null) l.onProgress("渲染并编码", 0, opt.fps * opt.seconds);
            int bitrate = GlEncoder.bitrateFor(fw, fh, opt.fps, opt.bpp());
            GlEncoder.encode(tmp, fw, fh, opt.fps, opt.seconds, bitrate, (index, t) -> {
                if (!inited[0]) {
                    renderer.setup(src);
                    inited[0] = true;
                }
                renderer.drawFrame(index, t);
            }, (done, total) -> {
                if (l != null) l.onProgress("渲染并编码", done, total);
            });

            if (opt.mode == MODE_360) {
                if (l != null) l.onProgress("写入全景元数据", 0, 1);
                SphericalMeta.inject(tmp, signed, opt.software, true);
                if (l != null) l.onProgress("写入全景元数据", 1, 1);
            } else {
                // 平面视频不写球形元数据：写了平台可能当成 360 去展开，画面会被拉变形
                copy(tmp, signed);
            }

            if (l != null) l.onProgress("保存到下载目录", 0, 1);
            String name = "全景_" + System.currentTimeMillis() + ".mp4";
            String path = writeDownload(c, FOLDER, signed, name);
            if (l != null) l.onProgress("保存到下载目录", 1, 1);

            File finalFile = new File(c.getCacheDir(), name);
            copy(signed, finalFile);
            return new Result(finalFile, path);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            signed.delete();
        }
    }

    // ------------------------------------------------------------ 落盘

    /** Android 10+ 走 MediaStore（免权限），老系统退回 File API。 */
    private static String writeDownload(Context c, String relDir, File src, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + relDir);
                Uri uri = c.getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            pump(in, os);
                            return "Download/" + relDir + "/" + name;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 落到下面
            }
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), relDir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, name);
            copy(src, out);
            MediaScannerConnection.scanFile(c, new String[]{out.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return src.getAbsolutePath();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            pump(in, out);
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
