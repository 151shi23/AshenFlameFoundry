package com.mineways.conv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 图片格式转换：**平台直读优先，stb 兜底**。
 *
 * <p>为什么两条路：
 * <ul>
 *   <li>平台 Bitmap 能读 **WebP / AVIF / HEIC / JPEG / PNG**（各机型覆盖不同），写 JPEG/PNG/WEBP，
 *       速度最快、不占额外体积；</li>
 *   <li>stb（原生）能读 **BMP / TGA / PSD / HDR / GIF(首帧) / PNM** 这类平台不认的，
 *       并能写 BMP/TGA/HDR（平台写不了）。</li>
 * </ul>
 */
public final class ImageConvert {

    /** 界面展示用的输入格式（含两条路线）。 */
    public static final String[] INPUT_EXTS = {
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "tga", "psd", "hdr", "avif", "heic", "ico"};

    /** 输出格式：平台优先集合 + 原生集合。 */
    public static final String[] OUTPUT_EXTS = {"png", "jpg", "webp", "bmp", "tga", "hdr"};

    public static final class Result {
        public boolean ok;
        public String message = "";

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private ImageConvert() {
    }

    /** 这条输出格式走原生（stb）还是平台。 */
    public static boolean nativeOnlyOutput(String ext) {
        return "bmp".equals(ext) || "tga".equals(ext) || "hdr".equals(ext);
    }

    /** 转换。quality 只对 JPEG/WEBP 有效（1-100）。 */
    public static Result convert(File in, File out, String targetExt, int quality) {
        final String ext = targetExt == null ? "" : targetExt.toLowerCase();
        Result r;

        // 平台写不了 bmp/tga/hdr → 直接交给原生
        if (nativeOnlyOutput(ext)) {
            return viaStb(in, out, ext, quality);
        }

        // 先试平台（WebP/AVIF/HEIC 只有平台能读）
        Bitmap bmp = null;
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeFile(in.getAbsolutePath(), o);
        } catch (Throwable ignored) {
        }

        if (bmp != null) {
            OutputStream os = null;
            try {
                os = new FileOutputStream(out);
                final Bitmap.CompressFormat cf;
                if ("png".equals(ext)) {
                    cf = Bitmap.CompressFormat.PNG;
                } else if ("webp".equals(ext)) {
                    cf = Bitmap.CompressFormat.WEBP;
                } else {
                    cf = Bitmap.CompressFormat.JPEG;
                }
                final int q = (cf == Bitmap.CompressFormat.PNG) ? 100
                        : Math.max(1, Math.min(100, quality <= 0 ? 92 : quality));
                final boolean ok = bmp.compress(cf, q, os);
                os.flush();
                if (!ok) {
                    return new Result(false, "平台编码失败（" + ext + "）");
                }
                r = new Result(true, String.format("平台直读转换完成：%s → %s\n%dx%d · 输出 %s",
                        in.getName(), out.getName(), bmp.getWidth(), bmp.getHeight(),
                        human(out.length())));
            } catch (Throwable t) {
                return new Result(false, "写文件失败：" + t);
            } finally {
                try {
                    if (os != null) {
                        os.close();
                    }
                } catch (Throwable ignored) {
                }
                bmp.recycle();
            }
            return r;
        }

        // 平台读不了（bmp/tga/psd/hdr/gif…）→ 原生兜底
        return viaStb(in, out, ext, quality);
    }

    /** 原生（stb）路线：平台读不了的格式，或平台写不了的输出格式。 */
    private static Result viaStb(File in, File out, String ext, int quality) {
        if (!FmtConv.available()) {
            return new Result(false, "平台读不了这个格式，而原生内核不可用（" + FmtConv.loadError() + "）");
        }
        final FmtConv.Result r = FmtConv.convertImage(in.getAbsolutePath(), out.getAbsolutePath(), quality);
        return new Result(r.ok, r.message);
    }

    /** 图片信息（优先原生 stb，失败退回平台）。 */
    public static String info(File f) {
        if (FmtConv.available()) {
            final FmtConv.Result r = FmtConv.imageInfo(f.getAbsolutePath());
            if (r.ok) {
                return r.message;
            }
        }
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                return o.outWidth + "×" + o.outHeight + " · "
                        + (o.outMimeType == null ? "未知" : o.outMimeType);
            }
        } catch (Throwable ignored) {
        }
        return "读不出图片信息";
    }

    private static String human(long bytes) {
        if (bytes >= 1048576) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
