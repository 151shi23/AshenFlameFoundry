package com.mineways.music;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 下载当前播放的音频。网页里的 File System Access API 在 Android WebView 上没有，
 * 所以下载落盘走原生：Android 10+ 进 MediaStore 的「下载/MinewaysMobile/音乐」，
 * 9 及以下退到 App 专属目录（避免为了几首歌去要全盘权限）。
 */
public final class MusicDownloader {

    public interface Callback {
        void done(boolean ok, String message);
    }

    private MusicDownloader() {
    }

    private static Context app;

    public static void downloadCurrent(Context c, Callback cb) {
        app = c.getApplicationContext();
        if (!MusicSources.downloadAllowed(c)) {
            finish(cb, false, "云控没有开放下载（music_dl 未开）");
            return;
        }
        String url = MusicCtl.url();
        if (url == null || url.length() == 0) {
            finish(cb, false, "还没有正在播放的歌");
            return;
        }
        String name = safeName(MusicCtl.title(), MusicCtl.artist()) + ".mp3";
        new Thread(() -> download(c, url, name, cb), "mw-download").start();
    }

    private static void download(Context c, String url, String name, Callback cb) {
        HttpURLConnection conn = null;
        try {
            // 音源给的是 302 端址，真实地址常是明文 http（实测酷狗）。跨协议跳转 HttpURLConnection
            // 不会自己跟（不跟就拿到 302 存个空文件），明文又被安全策略拦 —— 先跟到底再说
            url = MusicResolve.audioUrl(url, "MinewaysMobile").url;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "MinewaysMobile");
            if (url.contains("music.126.net")) {
                conn.setRequestProperty("Referer", "https://music.163.com/");
            } else if (url.contains("kugou.com")) {
                conn.setRequestProperty("Referer", "https://www.kugou.com/");
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                finish(cb, false, "下载失败：HTTP " + code);
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStore(c, conn, name, cb);
            } else {
                saveLegacy(c, conn, name, cb);
            }
        } catch (Throwable t) {
            finish(cb, false, "下载失败：" + MusicResolve.why(t));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void saveViaMediaStore(Context c, HttpURLConnection conn, String name, Callback cb)
            throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/MinewaysMobile/音乐");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            finish(cb, false, "系统拒绝创建下载文件");
            return;
        }
        long bytes = 0;
        try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new java.io.IOException("打不开输出流");
            }
            bytes = copy(conn.getInputStream(), out);
        } catch (Throwable t) {
            c.getContentResolver().delete(uri, null, null);
            finish(cb, false, "写入失败：" + t.getMessage());
            return;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        c.getContentResolver().update(uri, done, null, null);
        finish(cb, true, "已保存到 下载/MinewaysMobile/音乐/" + name + "（" + kb(bytes) + "）");
    }

    private static void saveLegacy(Context c, HttpURLConnection conn, String name, Callback cb)
            throws Exception {
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "音乐");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File out = new File(dir, name);
        long bytes;
        try (OutputStream os = new FileOutputStream(out)) {
            bytes = copy(conn.getInputStream(), os);
        }
        finish(cb, true, "已保存到 " + out.getAbsolutePath() + "（" + kb(bytes) + "）");
    }

    private static long copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 200L * 1024 * 1024) {
                    throw new java.io.IOException("文件超过 200 MB，已中止");
                }
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return total;
    }

    public static String safeName(String title, String artist) {
        String t = (title == null || title.trim().length() == 0) ? "未命名" : title.trim();
        String a = (artist == null || artist.trim().length() == 0) ? "" : artist.trim();
        String raw = a.length() > 0 ? a + " - " + t : t;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 80; i++) {
            char ch = raw.charAt(i);
            boolean bad = ch < 0x20 || "/\\:*?\"<>|".indexOf(ch) >= 0;
            sb.append(bad ? '_' : ch);
        }
        return sb.toString();
    }

    private static String kb(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void finish(Callback cb, boolean ok, String msg) {
        if (cb != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(ok, msg));
        }
        if (app != null) {
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
        }
    }
}
