package com.mineways.blender;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 纯 Java 的 tar 解包器（不依赖系统 tar）。
 *
 * <p>需要支持的是 Debian {@code data.tar.xz} 与 Blender 官方 {@code .tar.xz}：
 * ustar / GNU 长名（{@code L}/{@code K}）/ PAX 头（{@code x}）/ 目录 / 符号链接 / 硬链接。
 * 解出来的软链接目标如果是绝对路径（Debian 的 {@code /lib → usr/lib} 就是这种），
 * 会改写成同目录下的相对名 —— 这样 sysroot 才能还原成「合并 /usr」的真实布局。</p>
 */
public final class TarExtractor {

    public interface Progress {
        void onFile(String name);
    }

    public static final class Stats {
        public int files;
        public int dirs;
        public int links;
        public long bytes;
        public String lastName = "";
    }

    private static final int BLOCK = 512;

    private TarExtractor() {
    }

    public static Stats extract(InputStream in, File dest, Progress pr, Downloader.Cancel cancel) throws IOException {
        final Stats st = new Stats();
        final String base;
        String canonical = dest.getCanonicalPath();
        if (!canonical.endsWith(File.separator)) canonical = canonical + File.separator;
        base = canonical;

        byte[] hdr = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxName = null;
        String paxLink = null;

        while (true) {
            if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
            if (!readFully(in, hdr)) break;
            if (isZero(hdr)) continue;

            String name = str(hdr, 0, 100);
            final int mode = (int) octal(hdr, 100, 8);
            final long size = octal(hdr, 124, 12);
            final long mtime = octal(hdr, 136, 12);
            final int type = hdr[156] & 0xFF;
            final String linkName = str(hdr, 157, 100);

            if (type == 'L') {
                longName = readTextBlock(in, size);
                continue;
            }
            if (type == 'K') {
                longLink = readTextBlock(in, size);
                continue;
            }
            if (type == 'x' || type == 'g') {
                String rec = readTextBlock(in, size);
                if (type == 'x') {
                    paxName = paxValue(rec, "path", paxName);
                    paxLink = paxValue(rec, "linkpath", paxLink);
                }
                continue;
            }

            if (longName != null) {
                name = longName;
                longName = null;
            } else if (paxName != null) {
                name = paxName;
                paxName = null;
            }
            String link = longLink != null ? longLink : linkName;
            if (paxLink != null) {
                link = paxLink;
                paxLink = null;
            }
            longLink = null;

            name = cleanName(name);
            if (name == null) {
                skip(in, size);
                continue;
            }

            final File target = new File(dest, name);
            String tc = target.getCanonicalPath();
            if (!tc.startsWith(base) && !tc.equals(canonical)) {
                throw new IOException("归档里有越界路径，已中止：" + name);
            }
            st.lastName = name;
            if (pr != null) pr.onFile(name);

            switch (type) {
                case '5': {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    target.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    target.setExecutable(true, false);
                    st.dirs++;
                    break;
                }
                case '2': {
                    makeLink(target, link);
                    st.links++;
                    break;
                }
                case '1': {
                    File src = new File(dest, cleanName(link) == null ? "" : cleanName(link));
                    if (src.isFile()) {
                        FileUtil.copy(src, target);
                    } else {
                        makeLink(target, link);
                        st.links++;
                    }
                    st.files++;
                    break;
                }
                case '3':
                case '4':
                case '6': {
                    skip(in, size);
                    break;
                }
                default: {
                    if (size > 0) {
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException("无法创建目录：" + parent);
                        }
                        try (OutputStream out = new FileOutputStream(target)) {
                            copyExactly(in, out, size, cancel);
                        }
                        st.bytes += size;
                        st.files++;
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        target.mkdirs();
                    }
                    if (mtime > 0) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setLastModified(mtime * 1000L);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    target.setReadable(true, false);
                    if ((mode & 0111) != 0) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setExecutable(true, false);
                    }
                    break;
                }
            }
            skipPadding(in, size);
        }
        FileUtil.makeTreeReadable(dest);
        return st;
    }

    // ---------------------------------------------------------------- 内部

    private static void makeLink(File target, String link) {
        if (link == null || link.isEmpty()) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileUtil.deleteRec(target);
        String dest = link;
        if (dest.startsWith("/")) {
            // 绝对软链接在 sysroot 里会指向宿主根目录，统一降级成同目录同名目标
            dest = dest.substring(dest.lastIndexOf('/') + 1);
        }
        try {
            Files.createSymbolicLink(target.toPath(), Paths.get(dest));
        } catch (Throwable ignored) {
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long size, Downloader.Cancel cancel)
            throws IOException {
        final byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) throw new IOException("归档提前结束");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        long left = size;
        final byte[] buf = new byte[1 << 16];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            left -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) skip(in, pad);
    }

    private static String readTextBlock(InputStream in, long size) throws IOException {
        if (size <= 0 || size > (1 << 20)) {
            skip(in, size);
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) size);
        final byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            bos.write(buf, 0, n);
            left -= n;
        }
        skipPadding(in, size);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off != 0 ? fail() : false;
            off += n;
        }
        return true;
    }

    private static boolean fail() throws IOException {
        throw new IOException("归档头不完整");
    }

    private static boolean isZero(byte[] b) {
        for (byte v : b) {
            if (v != 0) return false;
        }
        return true;
    }

    /** 去掉 {@code ./} 前缀、拒绝 {@code ..} 与绝对路径。 */
    private static String cleanName(String raw) {
        if (raw == null) return null;
        String n = raw.trim();
        if (n.isEmpty()) return null;
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        if (n.contains("..")) return null;
        if (n.indexOf('\0') >= 0) return null;
        return n;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        final int max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** 八进制数字（兼容 GNU 的 base-256 大数）。 */
    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = off + 1; i < off + len; i++) {
                v = (v << 8) | (b[i] & 0xFF);
            }
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (any) break;
                continue;
            }
            if (c < '0' || c > '7') return v;
            v = (v << 3) | (c - '0');
            any = true;
        }
        return v;
    }

    /** PAX 记录：{@code "<len> key=value\n"}。 */
    private static String paxValue(String records, String key, String fallback) {
        if (records == null) return fallback;
        int pos = 0;
        while (pos < records.length()) {
            int sp = records.indexOf(' ', pos);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(records.substring(pos, sp).trim());
            } catch (Throwable t) {
                break;
            }
            if (len <= 0 || pos + len > records.length()) break;
            String rec = records.substring(sp + 1, pos + len - 1);
            int eq = rec.indexOf('=');
            if (eq > 0 && rec.substring(0, eq).equals(key)) {
                return rec.substring(eq + 1);
            }
            pos += len;
        }
        return fallback;
    }

    /** 便利方法：把一个 tar（自动识别 xz/gz）解到目录。 */
    public static Stats extractArchive(File archive, File dest, Progress pr, Downloader.Cancel cancel)
            throws IOException {
        try (InputStream in = Xz.openArchive(archive)) {
            return extract(in, dest, pr, cancel);
        }
    }
}
