package com.mineways;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 社区头像加载（不引第三方库）。
 *
 * <pre>
 *   内存 LruCache  →  磁盘缓存（cacheDir/avatar）  →  网络下载
 *   加载中/失败/未登录 → 首字母圆形占位（不会出现空白或问号图）
 * </pre>
 *
 * 站内相对路径（如 {@code /uploads/avatar/xx.png}）会自动拼上论坛域名。
 */
public final class AvatarLoader {

    private static final LruCache<String, Bitmap> MEM = new LruCache<>(32);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    /** 解码目标边长：46dp 的头像在任何密度下都够清晰，又不占内存。 */
    private static final int SIZE = 144;
    /** 磁盘缓存目录（cacheDir/avatar）；没喂进来就只用内存缓存。 */
    private static File cacheDir;

    private AvatarLoader() {
    }

    /** 相对路径 → 绝对地址（已是 http(s) 或空值则原样返回）。 */
    public static String resolve(String url) {
        if (url == null || url.trim().length() == 0) {
            return "";
        }
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        String base = OAuthClient.homePage();
        if (base == null || base.length() == 0) {
            return u;
        }
        return u.startsWith("/") ? base + u : base + "/" + u;
    }

    /** 异步加载到 ImageView：先占位，拿到图再换（同一 ImageView 换了地址不会串图）。 */
    public static void load(final ImageView view, final String url, final String name) {
        if (view == null) {
            return;
        }
        final String full = resolve(url);
        final String key = full.length() > 0 ? full : "letter:" + (name == null ? "" : name);
        view.setTag(key);
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageBitmap(letterAvatar(name));
        if (full.length() == 0) {
            return;
        }
        POOL.execute(() -> {
            Bitmap bmp = fromDisk(full);
            if (bmp == null) {
                bmp = download(full);
                if (bmp != null) {
                    toDisk(full, bmp);
                }
            }
            if (bmp == null) {
                return;
            }
            final Bitmap out = circle(bmp);
            MEM.put(key, out);
            view.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(out);
                }
            });
        });
    }

    // ------------------------------------------------------------------ 占位 / 裁剪

    /** 首字母圆形占位（用户名首位，空则用「?」）。 */
    private static Bitmap letterAvatar(String name) {
        String ch = (name == null || name.trim().length() == 0)
                ? "?" : name.trim().substring(0, 1).toUpperCase();
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2A2E35);
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p);
        p.setColor(0xFFECECEC);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(SIZE * 0.44f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(ch, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bmp;
    }

    /** 居中裁剪成圆形。 */
    private static Bitmap circle(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path clip = new Path();
        clip.addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW);
        c.clipPath(clip);
        float scale = Math.max(SIZE / (float) src.getWidth(), SIZE / (float) src.getHeight());
        float w = src.getWidth() * scale;
        float h = src.getHeight() * scale;
        RectF dst = new RectF((SIZE - w) / 2f, (SIZE - h) / 2f, (SIZE + w) / 2f, (SIZE + h) / 2f);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (src != out && !src.isRecycled()) {
            src.recycle();
        }
        return out;
    }

    // ------------------------------------------------------------------ 缓存 / 网络

    private static Bitmap fromDisk(String url) {
        try {
            File f = cacheFile(url);
            if (f == null || !f.isFile() || f.length() == 0) {
                return null;
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void toDisk(String url, Bitmap bmp) {
        File f = cacheFile(url);
        if (f == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 磁盘缓存文件；目录拿不到就返回 null（调用方跳过缓存，只影响速度、不影响显示）。 */
    private static File cacheFile(String url) {
        File dir = cacheDir;
        if (dir == null) {
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, Integer.toHexString(url.hashCode()) + ".png");
    }

    /** 由界面在本进程启动时喂一次（Activity.getCacheDir()），不调就只用内存缓存。 */
    public static void init(File cacheRoot) {
        if (cacheRoot != null && cacheDir == null) {
            cacheDir = new File(cacheRoot, "avatar");
        }
    }

    private static Bitmap download(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MinewaysMobile");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            byte[] data = bos.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= SIZE
                    && bounds.outHeight / (sample * 2) >= SIZE) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        } catch (Throwable t) {
            return null;
        }
    }
}
