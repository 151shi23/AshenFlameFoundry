package com.mineways.blender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 断点续传下载器（HttpURLConnection，工程里没有 OkHttp）。
 * 用法：先 {@link #sizeOf} 拿总长做进度，再 {@link #download}；
 * 中断后重试会用 HTTP Range 从已有长度接着下。
 */
public final class Downloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static final Cancel NEVER = new Cancel() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 60_000;
    private static final String UA = "Mozilla/5.0 (Linux; Android) MinewaysBlender/1.0";
    private static final int MAX_RETRY = 3;

    private Downloader() {
    }

    public static long sizeOf(String url) {
        try {
            HttpURLConnection c = open(url, "HEAD");
            c.connect();
            try {
                long len = c.getContentLengthLong();
                if (len < 0) len = c.getHeaderFieldLong("Content-Length", -1);
                return len;
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 下载到 {@code dst}（已存在则续传）。
     *
     * @param sha256 期望的 SHA-256（null/空 = 不校验；校验失败会删掉半成品并抛异常）
     * @return 文件路径
     */
    public static File download(String url, File dst, String sha256, Progress pr, Cancel cancel) throws IOException {
        if (!dst.getParentFile().isDirectory() && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建目录：" + dst.getParent());
        }
        long have = dst.isFile() ? dst.length() : 0L;
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            if (cancel.isCancelled()) throw new IOException("已取消");
            try {
                have = once(url, dst, have, pr, cancel);
                if (sha256 == null || sha256.trim().isEmpty()) {
                    return dst;
                }
                String real = FileUtil.sha256(dst);
                if (real.equalsIgnoreCase(sha256.trim())) {
                    return dst;
                }
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw new IOException("SHA-256 不匹配（期望 " + sha256.substring(0, Math.min(12, sha256.length()))
                        + "…，实际 " + real.substring(0, 12) + "…），已删除重下");
            } catch (IOException e) {
                last = e;
                have = dst.isFile() ? dst.length() : 0L;
                try {
                    Thread.sleep(600L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已取消");
                }
            }
        }
        throw last == null ? new IOException("下载失败：" + url) : last;
    }

    private static long once(String url, File dst, long have, Progress pr, Cancel cancel) throws IOException {
        HttpURLConnection c = open(url, "GET");
        if (have > 0) {
            c.setRequestProperty("Range", "bytes=" + have + "-");
        }
        c.connect();
        final int code = c.getResponseCode();
        boolean append = false;
        if (code == 206) {
            append = true;
        } else if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + "：" + url);
        } else {
            // 服务器不支持续传（或第一次下载）：从头写
            have = 0L;
        }
        final long header = c.getContentLengthLong();
        final long total = header >= 0 ? have + header : -1L;

        try (RandomAccessFile raf = new RandomAccessFile(dst, "rw");
             InputStream in = c.getInputStream()) {
            raf.setLength(have);
            raf.seek(have);
            if (append) {
                // 续传：内容从文件尾接着写
            }
            final byte[] buf = new byte[1 << 16];
            long done = have;
            int n;
            long tick = 0;
            while ((n = in.read(buf)) > 0) {
                raf.write(buf, 0, n);
                done += n;
                if (pr != null && ++tick % 8 == 0) {
                    pr.onProgress(done, total);
                }
                if (cancel.isCancelled()) {
                    throw new IOException("已取消");
                }
            }
        } finally {
            c.disconnect();
        }
        if (pr != null) pr.onProgress(dst.length(), total > 0 ? total : dst.length());
        return dst.length();
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        if ("HEAD".equals(method)) {
            c.setRequestMethod("HEAD");
        } else {
            c.setRequestMethod("GET");
        }
        return c;
    }

    /** 下载一个小文本文件（会顺带处理 gzip 编码）。 */
    public static String text(String url, int maxBytes) throws IOException {
        return text(url, maxBytes, UA);
    }

    /** 同上，但可指定 User-Agent（某些 API 要求可识别的 UA）。 */
    public static String text(String url, int maxBytes, String userAgent) throws IOException {
        HttpURLConnection c = open(url, "GET");
        if (userAgent != null && !userAgent.isEmpty()) {
            c.setRequestProperty("User-Agent", userAgent);
        }
        c.connect();
        try (InputStream raw = c.getInputStream()) {
            InputStream in = raw;
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                in = new GZIPInputStream(raw);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[1 << 16];
            int n;
            while (bos.size() < maxBytes && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, Math.min(n, maxBytes - bos.size()));
            }
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** 把下载中的临时文件删掉（失败不抛）。 */
    public static void discard(File f) {
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        FileUtil.closeQuietly(c);
    }

    static void copyAndClose(InputStream in, File dst) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dst)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
