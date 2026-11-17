package com.mineways.open;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.util.Locale;

/**
 * 外部「打开方式 / 分享」投递箱。
 *
 * <p>{@link OpenWithActivity} 把外部 Uri 放进目标页 Intent 的 {@link #EXTRA_OPEN_URI}；
 * 目标页在 {@code onCreate} 里 {@link #take(Activity)} 读一次即置空，避免重建时重复消费。
 * 只承载 Uri 与文件名，不复制文件（修图兜底复制除外）。
 */
public final class OpenInbox {

    public static final String EXTRA_OPEN_URI = "open_uri";
    public static final String EXTRA_OPEN_NAME = "open_name";

    private OpenInbox() {
    }

    /** 读一次即置空：目标页重建（旋转 / 被回收）不会再次触发外部载入。 */
    public static Uri take(Activity a) {
        Intent it = a.getIntent();
        if (it == null) {
            return null;
        }
        Uri u = it.getParcelableExtra(EXTRA_OPEN_URI);
        if (u == null) {
            return null;
        }
        it.removeExtra(EXTRA_OPEN_URI);
        it.removeExtra(EXTRA_OPEN_NAME);
        return u;
    }

    public static String nameOf(Context c, Uri u) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    name = cur.getString(i);
                }
            }
        } catch (Throwable ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = u.getLastPathSegment();
        }
        if (name == null) {
            name = "file";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    public static long sizeOf(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !cur.isNull(i)) {
                    return cur.getLong(i);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 扩展名（小写，不含点）；{@code *.animation.json} 整体当作一个扩展名。 */
    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".animation.json")) {
            return "animation.json";
        }
        int dot = n.lastIndexOf('.');
        return dot >= 0 && dot < n.length() - 1 ? n.substring(dot + 1) : "";
    }

    public static String mimeOf(Context c, Uri u) {
        String mime = null;
        try {
            mime = c.getContentResolver().getType(u);
        } catch (Throwable ignored) {
        }
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            String guess = guessMime(extOf(nameOf(c, u)));
            if (guess != null) {
                return guess;
            }
        }
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String guessMime(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "gif": return "image/gif";
            case "heic": case "heif": return "image/heif";
            case "avif": return "image/avif";
            case "tif": case "tiff": return "image/tiff";
            case "ico": return "image/x-icon";
            case "tga": case "hdr": case "psd": return "image/" + ext;
            case "mp4": case "m4v": return "video/mp4";
            case "mov": return "video/quicktime";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "flv": return "video/x-flv";
            case "3gp": return "video/3gpp";
            case "ts": return "video/mp2t";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": case "opus": return "audio/ogg";
            case "amr": return "audio/amr";
            case "obj": case "glb": case "gltf": case "fbx": case "stl":
            case "ply": case "dae": case "3ds": case "pmx": case "vrm":
                return "model/" + ext;
            case "bbmodel": return "application/json";
            case "json": return "application/json";
            case "zip": case "mcpack": case "jar": case "apk": return "application/zip";
            case "txt": case "log": case "csv": return "text/plain";
            default: return null;
        }
    }

    /** 跳到目标页并带上外部文件；授予读权限，目标页 {@link #take} 消费。 */
    public static void launch(Activity a, Class<?> cls, Uri u, String name) {
        Intent it = new Intent(a, cls);
        it.putExtra(EXTRA_OPEN_URI, u);
        it.putExtra(EXTRA_OPEN_NAME, name);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.setClipData(ClipData.newRawUri("open", u));
        a.startActivity(it);
    }

    public static String humanSize(long n) {
        if (n < 0) {
            return "";
        }
        if (n >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        }
        if (n >= 1024L) {
            return String.format(Locale.US, "%.1f KB", n / 1024.0);
        }
        return n + " B";
    }
}
