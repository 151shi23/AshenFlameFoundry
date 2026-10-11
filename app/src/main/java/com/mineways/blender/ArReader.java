package com.mineways.blender;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Debian {@code .deb} 读取器。deb 就是 ar 归档：{@code debian-binary} + {@code control.tar.*} + {@code data.tar.*}。
 * 这里只关心 {@code data.tar.*}（真正的文件内容），直接从归档里流出数据，
 * 配合 {@link TarExtractor} 边解压边落盘，不用把整包再写一份临时 tar。
 */
public final class ArReader {

    /** 归档里的一个条目（流已被限制在本条目长度内）。 */
    public static final class Entry {
        public final String name;
        private final InputStream stream;
        private long remaining;

        Entry(String name, InputStream stream, long size) {
            this.name = name;
            this.stream = stream;
            this.remaining = size;
        }

        public long remaining() {
            return remaining;
        }

        public InputStream stream() {
            return stream;
        }
    }

    private ArReader() {
    }

    /** 列出条目名（调试用）。 */
    public static List<String> list(File deb) throws IOException {
        final List<String> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(deb)) {
            final byte[] magic = new byte[8];
            if (!readFully(in, magic, 8)) throw new IOException("deb 文件太短：" + deb.getName());
            if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("不是 ar 归档：" + deb.getName());
            }
            final byte[] hdr = new byte[60];
            while (true) {
                if (!readFully(in, hdr, 60)) break;
                String name = new String(hdr, 0, 16, StandardCharsets.UTF_8).trim();
                long size = octal(hdr, 48, 10);
                out.add(name);
                skip(in, size + (size % 2));
            }
        }
        return out;
    }

    /** 打开 {@code data.tar.*} 条目；调用方负责关闭流。 */
    public static Entry openData(File deb) throws IOException {
        final FileInputStream in = new FileInputStream(deb);
        try {
            final byte[] magic = new byte[8];
            if (!readFully(in, magic, 8)) throw new IOException("deb 文件太短：" + deb.getName());
            if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("不是 ar 归档：" + deb.getName());
            }
            final byte[] hdr = new byte[60];
            while (true) {
                if (!readFully(in, hdr, 60)) break;
                String name = new String(hdr, 0, 16, StandardCharsets.UTF_8).trim();
                while (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                long size = octal(hdr, 48, 10);
                if (name.startsWith("data.tar")) {
                    return new Entry(name, new BoundedStream(in, size), size);
                }
                skip(in, size + (size % 2));
            }
        } catch (Throwable t) {
            FileUtil.closeQuietly(in);
            throw (t instanceof IOException) ? (IOException) t : new IOException(t);
        }
        FileUtil.closeQuietly(in);
        throw new IOException("deb 里没有 data.tar 条目：" + deb.getName());
    }

    /** 按 {@code data.tar} 后面那截后缀（xz/gz）选解压方式。 */
    public static InputStream decompress(Entry entry) throws IOException {
        final String name = entry.name == null ? "" : entry.name;
        final InputStream raw = entry.stream();
        if (name.endsWith(".xz")) {
            return xzStream(raw);
        }
        if (name.endsWith(".gz")) {
            return new java.util.zip.GZIPInputStream(raw, 1 << 16);
        }
        return new java.io.BufferedInputStream(raw, 1 << 16);
    }

    private static InputStream xzStream(InputStream raw) throws IOException {
        try {
            Class<?> cls = Class.forName("org.tukaani.xz.XZInputStream");
            java.lang.reflect.Constructor<?> ctor = cls.getConstructor(InputStream.class);
            return (InputStream) ctor.newInstance(raw);
        } catch (Throwable t) {
            throw new IOException("xz 解码器不可用：" + t, t);
        }
    }

    /** 只允许读固定字节数的流。 */
    private static final class BoundedStream extends InputStream {
        private final InputStream src;
        private long left;

        BoundedStream(InputStream src, long size) {
            this.src = src;
            this.left = size;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int c = src.read();
            if (c >= 0) left--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = src.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(Integer.MAX_VALUE, Math.min(left, src.available()));
        }

        @Override
        public void close() {
            FileUtil.closeQuietly(src);
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;
                left--;
            } else {
                left -= s;
            }
        }
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
        }
        return v;
    }
}
