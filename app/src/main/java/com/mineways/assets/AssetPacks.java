package com.mineways.assets;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 按需下载的资源包。
 *
 * <p>主 APK 不再内置大体积素材（OpenCut 那套 344 MB 的贴纸/模型），
 * 改由这个类在用户第一次用到某功能时下载。资源放在独立仓库的 Release 里，
 * 仓库根目录的 {@code manifest.json} 是唯一的事实来源 —— 加新包不用发版。
 *
 * <h3>为什么走镜像</h3>
 * 直连 {@code github.com} 在国内基本不通。镜像转发 Release 资源时<b>保留了 Range 支持</b>
 * （返回 206），所以断点续传和多路并行都能用。实测单路 1.1 MB/s，4 路并行 4.3 MB/s。
 *
 * <h3>为什么多路并行</h3>
 * 108 MB 单路要 95 秒，4 路只要 25 秒。gh-proxy 对单连接限速，但对总带宽不卡。
 */
public final class AssetPacks {

    private AssetPacks() {
    }

    // ------------------------------------------------------------ 仓库坐标

    public static final String OWNER = "151shi23";
    public static final String REPO = "AshenFlameAssets";
    public static final String BRANCH = "main";

    /** 并行下载的分段数。再多收益递减，而且手机上线程太多反而抢带宽。 */
    public static final int THREADS = 4;

    /** 本地安装根目录：filesDir/assets/<installDir>/ */
    private static final String ROOT = "assets";

    /** 版本记录文件名（每个包一个）。 */
    private static final String STAMP = ".installed";

    // ------------------------------------------------------------ 镜像

    /** 前缀 + 原始 URL。空串表示直连（国外网络可用，国内基本超时）。 */
    private static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            ""
    };

    /** 给界面显示的镜像名。 */
    public static String mirrorLabel(String prefix) {
        if (prefix.startsWith("https://gh-proxy.com")) return "gh-proxy";
        if (prefix.startsWith("https://ghfast.top")) return "ghfast";
        return "直连";
    }

    /** manifest 的原始地址。 */
    public static String manifestRawUrl() {
        return "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/" + BRANCH
                + "/manifest.json";
    }

    /**
     * Release 资源的原始地址。
     *
     * @param tag release 标签（如 v1）
     */
    public static String assetRawUrl(String tag, String fileName) {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases/download/"
                + tag + "/" + fileName;
    }

    /** 套上镜像。 */
    public static String withMirror(String prefix, String rawUrl) {
        return prefix + rawUrl;
    }

    // ------------------------------------------------------------ 数据

    /** manifest 里的一条资源包。 */
    public static final class Pack {
        public String id = "";
        public String name = "";
        public String desc = "";
        /** 可选：这一版更新了什么（manifest 里的 notes），弹更新提示时显示。 */
        public String notes = "";
        public String version = "";
        public long size = 0;
        public String sha256 = "";
        public int fileCount = 0;
        /** 原始下载地址（不含镜像前缀）。 */
        public String url = "";
        /** 解压到 filesDir/assets/<installDir>/，zip 内顶层目录名也是它。 */
        public String installDir = "";
        public boolean required = false;

        /** 从 url 里抠出 release tag，用来拼镜像地址。 */
        public String tag() {
            int i = url.indexOf("/releases/download/");
            if (i < 0) return "";
            String rest = url.substring(i + "/releases/download/".length());
            int j = rest.indexOf('/');
            return j < 0 ? rest : rest.substring(0, j);
        }

        public String fileName() {
            int i = url.lastIndexOf('/');
            return i < 0 ? url : url.substring(i + 1);
        }

        /** 人类可读体积。 */
        public String sizeText() {
            if (size >= 1L << 30) return String.format("%.1f GB", size / (double) (1L << 30));
            return String.format("%.0f MB", size / (double) (1L << 20));
        }
    }

    /** 下载 / 解压进度回调。 */
    public interface Progress {
        /**
         * @param stage 阶段名（"连接" / "下载" / "校验" / "解压"）
         * @param done  已完成
         * @param total 总量；未知时传 -1
         */
        void onProgress(String stage, long done, long total);

        /** 用户取消时返回 true。 */
        default boolean isCancelled() {
            return false;
        }
    }

    // ------------------------------------------------------------ manifest

    /**
     * 拉 manifest。依次试镜像，第一个成功的就用。
     *
     * @return 解析结果；全失败抛异常，异常信息里带每个源的失败原因
     */
    public static List<Pack> fetchManifest(String[] usedMirror) throws IOException {
        StringBuilder errs = new StringBuilder();
        // 清单文件名固定，镜像/CDN 会缓存它；带一分钟粒度的查询串，发新版本后不会被旧副本拖住
        long stamp = System.currentTimeMillis() / 60000L;
        for (String prefix : MIRRORS) {
            String url = withMirror(prefix, manifestRawUrl() + "?t=" + stamp);
            try {
                String text = httpGet(url, 20000, 20000);
                List<Pack> list = parseManifest(text);
                if (!list.isEmpty()) {
                    if (usedMirror != null && usedMirror.length > 0) {
                        usedMirror[0] = mirrorLabel(prefix);
                    }
                    return list;
                }
                errs.append(mirrorLabel(prefix)).append(": 内容为空\n");
            } catch (Throwable t) {
                errs.append(mirrorLabel(prefix)).append(": ").append(t.getMessage()).append('\n');
            }
        }
        throw new IOException("拿不到资源清单\n" + errs);
    }

    /** 解析 manifest 文本。单独抽出来是为了能在电脑上直接测。 */
    public static List<Pack> parseManifest(String text) throws IOException {
        List<Pack> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("packs");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Pack p = new Pack();
                p.id = o.optString("id", "");
                p.name = o.optString("name", p.id);
                p.desc = o.optString("desc", "");
                p.notes = o.optString("notes", "");
                p.version = o.optString("version", "");
                p.size = o.optLong("size", 0);
                p.sha256 = o.optString("sha256", "");
                p.fileCount = o.optInt("fileCount", 0);
                p.url = o.optString("url", "");
                p.installDir = o.optString("installDir", p.id);
                p.required = o.optBoolean("required", false);
                if (!p.id.isEmpty() && !p.url.isEmpty()) out.add(p);
            }
        } catch (Throwable t) {
            throw new IOException("manifest 格式不对：" + t.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------ 本地状态

    /** 安装目录：filesDir/assets/<installDir> */
    public static File installDir(Context c, Pack p) {
        return new File(new File(c.getFilesDir(), ROOT), p.installDir);
    }

    /** 已装版本；没装返回空串。 */
    public static String installedVersion(Context c, Pack p) {
        File stamp = new File(installDir(c, p), STAMP);
        if (!stamp.isFile()) return "";
        try (InputStream in = new FileInputStream(stamp)) {
            byte[] b = new byte[(int) Math.min(stamp.length(), 64)];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 是否已装且版本够新。 */
    public static boolean isReady(Context c, Pack p) {
        String v = installedVersion(c, p);
        if (v.isEmpty()) return false;
        return compareVersion(v, p.version) >= 0;
    }

    /**
     * 版本号逐段数字比较（{@code 1.10 > 1.9}）。
     * 字符串比较会把 1.10 判成旧版，素材包一旦升到两位小数就会静默不再更新。
     */
    public static int compareVersion(String a, String b) {
        String[] x = (a == null ? "" : a).trim().split("\\.");
        String[] y = (b == null ? "" : b).trim().split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long vx = i < x.length ? leadingNumber(x[i]) : 0;
            long vy = i < y.length ? leadingNumber(y[i]) : 0;
            if (vx != vy) return vx < vy ? -1 : 1;
        }
        return 0;
    }

    private static long leadingNumber(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return v;
    }

    /** 下载前检查空间：需要 压缩包 + 解压后 两份。 */
    public static boolean hasSpace(Context c, Pack p, long extra) {
        try {
            StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
            long free = fs.getAvailableBytes();
            // 解压后体积约为压缩包的 3.2 倍（实测 344/108），留 20% 余量
            long need = p.size + (long) (p.size * UNPACK_RATIO * 1.2) + extra;
            return free > need;
        } catch (Throwable t) {
            return true;   // 查不到就别拦
        }
    }

    /** 解压后的估算体积（MB），给用户看的。 */
    public static long unpackedMb(Pack p) {
        return (long) (p.size * UNPACK_RATIO) / 1024 / 1024;
    }

    /** 实测 344 MB / 108 MB ≈ 3.2。 */
    private static final double UNPACK_RATIO = 3.2;

    // ------------------------------------------------------------ 下载

    /**
     * 下载并安装一个资源包。
     *
     * <p>流程：多路并行下到缓存 → 校验 sha256 → 解压到临时目录 → 整体替换 → 写版本戳。
     * 中途失败不会破坏已装版本（替换是最后一步）。
     */
    public static void install(Context c, Pack p, Progress cb) throws IOException {
        if (p.size <= 0 || p.sha256.isEmpty()) {
            throw new IOException("manifest 缺少 size 或 sha256，拒绝安装");
        }
        if (!hasSpace(c, p, 0)) {
            throw new IOException("存储空间不足：这个资源包需要约 "
                    + p.sizeText() + " 下载 + 约 " + unpackedMb(p) + " MB 解压");
        }

        File cache = new File(c.getCacheDir(), "pack-" + p.id + ".zip");
        File progressFile = new File(c.getCacheDir(), "pack-" + p.id + ".progress");

        // 已下好的直接跳过，只补校验
        boolean needDownload = !(cache.isFile() && cache.length() == p.size
                && p.sha256.equalsIgnoreCase(sha256(cache)));

        if (needDownload) {
            downloadParallel(cache, progressFile, p, cb);
            if (cb != null && cb.isCancelled()) throw new IOException("已取消");

            if (cb != null) cb.onProgress("校验", 0, p.size);
            String got = sha256(cache);
            if (!got.equalsIgnoreCase(p.sha256)) {
                cache.delete();
                progressFile.delete();
                throw new IOException("校验失败，文件可能下载不完整。\n期望 " + p.sha256
                        + "\n实际 " + got + "\n已删除，请重试");
            }
            if (cb != null) cb.onProgress("校验", p.size, p.size);
        }

        // 解压到临时目录，成功后再整体替换 —— 中途失败不影响已装版本
        File dst = installDir(c, p);
        File tmp = new File(dst.getParentFile(), p.installDir + ".new");
        deleteTree(tmp);
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("建不了临时目录：" + tmp);
        }
        try {
            unzip(cache, tmp, p.installDir, cb);
            // 清单声明的文件数是最后一道结构校验：解压目录少一层/多一层都会在这里被抓住，
            // 而不是等页面 404 之后用户看到一片白。
            long files = countFiles(tmp);
            if (p.fileCount > 0 && files != p.fileCount) {
                throw new IOException("解压后文件数与清单不符：期望 " + p.fileCount
                        + "，实际 " + files + "（压缩包结构可能变了）");
            }
            writeStamp(tmp, p.version);

            File old = new File(dst.getParentFile(), p.installDir + ".old");
            deleteTree(old);
            boolean keepOld = false;
            if (dst.exists()) {
                if (dst.renameTo(old)) {
                    keepOld = true;
                } else {
                    // 有些机型 rename 到已存在目录会失败，退回先删再换
                    deleteTree(dst);
                }
            }
            if (!tmp.renameTo(dst)) {
                // 更新失败必须把旧素材换回来：用户刚还在用它，不能因为一次改名失败就没有素材
                if (keepOld) old.renameTo(dst);
                throw new IOException("安装失败：无法重命名 " + tmp + " → " + dst);
            }
            deleteTree(old);
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }

        cache.delete();
        progressFile.delete();
        if (cb != null) cb.onProgress("完成", p.size, p.size);
    }

    /**
     * 多路并行下载。
     *
     * <p>把文件切成 {@link #THREADS} 段，每段一个线程，各自 {@code RandomAccessFile.seek}
     * 到自己的位置写 —— 段之间不重叠，所以不需要锁。
     *
     * <p>进度存在 {@code .progress} 文件里（每段一个 long）。断线重来只补没下完的部分。
     */
    public static void downloadParallel(File out, File progressFile, Pack p, Progress cb)
            throws IOException {
        long size = p.size;
        int n = (int) Math.max(1, Math.min(THREADS, size / (2L << 20) + 1));  // 小文件少开线程
        long chunk = size / n;

        final long[] done = new long[n];
        if (out.isFile() && progressFile.isFile()) {
            // 续传：读上次进度
            try (InputStream in = new FileInputStream(progressFile)) {
                byte[] b = new byte[8 * n];
                int r = in.read(b);
                for (int i = 0; i < n && (i + 1) * 8 <= r; i++) {
                    long v = 0;
                    for (int k = 0; k < 8; k++) v = (v << 8) | (b[i * 8 + k] & 0xff);
                    done[i] = Math.max(0, Math.min(chunk, v));
                }
            } catch (Throwable ignored) {
            }
        } else {
            deleteTree(out);
            progressFile.delete();
        }

        // 预先占好文件长度，各线程才能 seek 到任意位置写
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(size);
        }

        String[] used = new String[1];
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        // 总进度：各段往同一个计数器里加。回调按 150ms 节流，进度文件每 2 秒落一次盘
        // （只在失败时写的话，进程被杀掉就白下了）。
        final Object lock = new Object();
        final long[] totalDone = new long[1];
        for (long v : done) totalDone[0] += v;
        final long[] lastReportAt = {0};
        final long[] lastSaveAt = {System.currentTimeMillis()};

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final long start = i * chunk;
            final long end = (i == n - 1) ? size : (start + chunk);
            Thread t = new Thread(() -> {
                try {
                    long from = start + done[idx];
                    if (from >= end) return;
                    RandomAccessFile raf = new RandomAccessFile(out, "rw");
                    try {
                        raf.seek(from);
                        streamRange(p, from, end - 1, raf, written -> {
                            synchronized (lock) {
                                done[idx] += written;
                                totalDone[0] += written;
                                long now = System.currentTimeMillis();
                                if (now - lastReportAt[0] >= 150 || totalDone[0] >= size) {
                                    lastReportAt[0] = now;
                                    if (cb != null) cb.onProgress("下载", totalDone[0], size);
                                }
                                if (now - lastSaveAt[0] >= 2000) {
                                    lastSaveAt[0] = now;
                                    saveProgress(progressFile, done);
                                }
                            }
                        }, cb, cancelled, used);
                    } finally {
                        try {
                            raf.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            }, "pack-dl-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        if (cancelled.get() || (cb != null && cb.isCancelled())) {
            synchronized (lock) {
                saveProgress(progressFile, done);
            }
            throw new IOException("已取消");
        }
        if (!errors.isEmpty()) {
            synchronized (lock) {
                saveProgress(progressFile, done);
            }
            Throwable e = errors.get(0);
            throw new IOException("下载失败：" + e.getMessage(), e);
        }
    }

    /** 一段的下载循环，带重试与镜像回退。 */
    private static void streamRange(Pack p, long from, long to, RandomAccessFile raf,
                                    java.util.function.IntConsumer onBytes,
                                    Progress cb, AtomicBoolean cancelled, String[] usedMirror)
            throws IOException {
        long pos = from;
        int attempt = 0;
        IOException last = null;
        while (pos <= to) {
            if (cb != null && cb.isCancelled()) {
                cancelled.set(true);
                return;
            }
            // 镜像轮换：每次重试换一个源
            String prefix = MIRRORS[Math.min(attempt, MIRRORS.length - 1)];
            String url = withMirror(prefix, p.url);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setRequestProperty("Range", "bytes=" + pos + "-" + to);
                conn.setRequestProperty("User-Agent", "AshenFlameFoundry");
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code == 206) {
                    // 服务端可能"收了 Range 但给错区间"，照写会把整段数据挪位
                    String cr = conn.getHeaderField("Content-Range");
                    if (cr != null) {
                        int sp = cr.indexOf(' ');
                        int dash = cr.indexOf('-');
                        if (sp >= 0 && dash > sp) {
                            long startAt;
                            try {
                                startAt = Long.parseLong(cr.substring(sp + 1, dash).trim());
                            } catch (Throwable t) {
                                startAt = pos;
                            }
                            if (startAt != pos) throw new IOException("服务器返回的区间不对");
                        }
                    }
                } else if (code == 200) {
                    // 200 一定是从文件头开始给：只有偏移为 0 的那一段能直接收
                    if (pos != 0) throw new IOException("服务器不支持断点续传");
                } else {
                    throw new IOException("HTTP " + code);
                }
                synchronized (usedMirror) {
                    if (usedMirror[0] == null) usedMirror[0] = mirrorLabel(prefix);
                }

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        if (cb != null && cb.isCancelled()) {
                            cancelled.set(true);
                            return;
                        }
                        long can = to - pos + 1;
                        if (r > can) r = (int) can;
                        raf.write(buf, 0, r);
                        pos += r;
                        onBytes.accept(r);
                        if (pos > to) return;
                    }
                }
                if (pos > to) return;
                throw new IOException("连接提前结束");
            } catch (IOException e) {
                last = e;
                attempt++;
                if (attempt > MIRRORS.length * 2) throw e;
                try {
                    Thread.sleep(600L * attempt);
                } catch (InterruptedException ignored) {
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (last != null && pos <= to) throw last;
    }

    private static void saveProgress(File f, long[] done) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[done.length * 8];
            for (int i = 0; i < done.length; i++) {
                long v = done[i];
                for (int k = 7; k >= 0; k--) {
                    b[i * 8 + k] = (byte) (v & 0xff);
                    v >>>= 8;
                }
            }
            o.write(b);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 解压 / 校验 / 工具

    /**
     * 解压到 {@code dst}，顺手剥掉 zip 内的顶层目录。
     *
     * <p>资源包是用「带上层目录」的方式压的：{@code opencut/index.html}。而安装目录本身就叫
     * {@code opencut}，直接解进 {@code opencut.new} 再改名会变成 {@code opencut/opencut/index.html}
     * —— 页面永远找不到入口。这里把 {@code <installDir>/} 前缀剥掉；zip 本来就没有顶层目录时原样解。
     *
     * <p>包内可见：电脑上的 {@code PackTest} 直接调它验证结构。
     */
    static void unzip(File zip, File dst, String stripPrefix, Progress cb) throws IOException {
        // 进度按「解压后字节」算：ZipInputStream 的 compressedSize 在流式压缩包里是 -1，
        // 而手机解体量上千个文件要几十秒，进度条卡在 0 看起来就像死了。
        long total = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                long s = en.nextElement().getSize();
                if (s > 0) total += s;
            }
        } catch (Throwable ignored) {
            total = zip.length();
        }
        long done = 0;
        byte[] buf = new byte[64 * 1024];
        String prefix = stripPrefix == null || stripPrefix.isEmpty() ? "" : stripPrefix + "/";
        String dstPath = dst.getCanonicalPath();
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), 128 * 1024))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (cb != null && cb.isCancelled()) throw new IOException("已取消");
                String name = e.getName().replace('\\', '/');
                if (!prefix.isEmpty()) {
                    if (name.equals(stripPrefix) || name.equals(prefix)) continue;
                    if (name.startsWith(prefix)) name = name.substring(prefix.length());
                }
                if (name.isEmpty()) continue;
                File target = new File(dst, name).getCanonicalFile();
                // 防 zip slip：条目名里带 ../ 就跳出去
                if (!target.getPath().startsWith(dstPath + File.separator)) {
                    throw new IOException("压缩包里有非法路径：" + e.getName());
                }
                if (e.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("建不了目录：" + parent);
                }
                long written = 0;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        written += r;
                    }
                }
                done += written;
                if (cb != null) cb.onProgress("解压", total > 0 ? Math.min(done, total) : done,
                        total > 0 ? total : -1);
            }
        }
    }

    /** 递归数文件（用来核对清单里的 fileCount）。 */
    static long countFiles(File dir) {
        long n = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    private static void writeStamp(File dir, String version) {
        try (FileOutputStream o = new FileOutputStream(new File(dir, STAMP))) {
            o.write(version.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 流式算 sha256，108 MB 不占内存。 */
    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(f), 128 * 1024)) {
                byte[] buf = new byte[128 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("算哈希失败：" + t.getMessage());
        }
    }

    private static String httpGet(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "AshenFlameFoundry");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16 * 1024];
                int r;
                while ((r = in.read(b)) > 0) bos.write(b, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 递归删除。 */
    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
