package com.mineways.plaza;

import android.content.Context;

import com.mineways.blender.Downloader;

import java.io.File;
import java.io.IOException;

/** 官方资源下载：只走固定仓库 Release，按 id/release/sha 分目录，复用断点下载器。 */
public final class PlazaDownloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    private PlazaDownloader() {
    }

    public static File download(Context ctx, PlazaItem item,
                                Downloader.Progress progress,
                                Downloader.Cancel cancel) throws IOException {
        if (item == null || !item.isOfficial()) throw new IOException("不是官方资源");
        String url = item.downloadUrl();
        if (!PlazaRepo.trustedReleaseUrl(url, item.releaseTag, item.assetName)) {
            throw new IOException("下载地址不属于该资源的 Release");
        }
        if (item.size > 2L * 1024 * 1024 * 1024) throw new IOException("资源超过 2GB");
        File dir = cacheDir(ctx, item);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        File dst = new File(dir, safeName(item.assetName));
        if (dst.isFile() && dst.length() == item.size
                && item.sha256.equalsIgnoreCase(sha256(dst))) {
            return dst;
        }
        if (dst.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        return Downloader.download(url, dst, item.sha256, progress, cancel);
    }

    private static String sha256(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static File downloaded(Context ctx, PlazaItem item) {
        if (item == null || !item.isOfficial()) return null;
        File f = new File(cacheDir(ctx, item), safeName(item.assetName));
        return f.isFile() ? f : null;
    }

    private static File cacheDir(Context ctx, PlazaItem item) {
        File base = ctx.getExternalFilesDir("plaza");
        if (base == null) base = new File(ctx.getFilesDir(), "plaza");
        String tag = safeName(item.releaseTag);
        String sha = item.sha256 == null ? "nosha" : item.sha256.substring(0, Math.min(12, item.sha256.length()));
        return new File(new File(new File(new File(base, "downloads"), safeName(item.type)),
                safeName(item.id)), tag + "-" + sha);
    }

    public static String safeName(String s) {
        if (s == null) return "resource";
        String out = s.replace('/', '_').replace('\\', '_').replace("..", "_").trim();
        if (out.isEmpty()) out = "resource";
        return out.length() > 160 ? out.substring(0, 160) : out;
    }
}
