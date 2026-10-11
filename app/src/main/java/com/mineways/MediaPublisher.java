package com.mineways;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 把一个私有目录里的视频/图片送进系统相册（MediaStore）。工作室自动接管与「我的作品」页共用。 */
public final class MediaPublisher {

    private MediaPublisher() {
    }

    /** @return 给用户看的一句话（成功或失败原因） */
    public static String publish(Context context, File src) {
        return publish(context, src, "Mine-imator");
    }

    /**
     * 送进系统相册，并指定相册里的子目录。
     *
     * <p>相册写权限走 MediaStore（Q 以上不需要申请存储权限），所以这里只管插一条记录、把字节拷过去。</p>
     */
    public static String publish(Context context, File src, String album) {
        final String name = src.getName();
        final String mime = mimeOf(name);
        final boolean video = mime.startsWith("video/");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                final ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + album);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                final Uri collection = video
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                final Uri uri = context.getContentResolver().insert(collection, cv);
                if (uri == null) {
                    return "系统相册拒绝写入：" + name;
                }
                copy(src, context.getContentResolver().openOutputStream(uri));
                cv.clear();
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, cv, null, null);
                return "已保存到相册：" + name;
            }
            final File dir = new File(Environment.getExternalStoragePublicDirectory(
                    video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), album);
            if (!dir.exists() && !dir.mkdirs()) {
                return "无法创建目录：" + dir;
            }
            final File dst = new File(dir, name);
            copy(src, new java.io.FileOutputStream(dst));
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)));
            return "已保存到：" + dst.getAbsolutePath();
        } catch (Throwable t) {
            return "保存失败：" + t.getMessage();
        }
    }

    public static String mimeOf(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (n.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private static void copy(File src, OutputStream out) throws Exception {
        if (out == null) {
            throw new IllegalStateException("无法打开输出流");
        }
        final InputStream in = new FileInputStream(src);
        try {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
