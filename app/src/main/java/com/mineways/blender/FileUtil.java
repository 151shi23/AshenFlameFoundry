package com.mineways.blender;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** 通用小工具：体积显示、拷贝、递归删除、SHA-256。 */
public final class FileUtil {

    private FileUtil() {
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] unit = {"B", "KB", "MB", "GB", "TB"};
        int i = (int) (Math.log(bytes) / Math.log(1024));
        if (i < 0) i = 0;
        if (i > unit.length - 1) i = unit.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, i), unit[i]);
    }

    public static boolean mkdirs(File dir) {
        return dir != null && (dir.isDirectory() || dir.mkdirs());
    }

    public static File child(File dir, String rel) {
        File f = new File(dir, rel);
        try {
            String c = f.getCanonicalPath();
            String base = dir.getCanonicalPath();
            if (!c.startsWith(base)) {
                throw new IOException("路径越界：" + rel);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        return f;
    }

    public static void copyStream(InputStream in, File dst) throws IOException {
        if (!dst.getParentFile().isDirectory() && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建目录：" + dst.getParent());
        }
        try (OutputStream out = new FileOutputStream(dst)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        dst.setReadable(true, false);
    }

    public static void copy(File src, File dst) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src))) {
            copyStream(in, dst);
        }
        dst.setLastModified(src.lastModified());
    }

    public static void writeText(File dst, String text) throws IOException {
        if (!dst.getParentFile().isDirectory() && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建目录：" + dst.getParent());
        }
        try (OutputStream out = new FileOutputStream(dst)) {
            out.write(text.getBytes("UTF-8"));
        }
        dst.setReadable(true, false);
        dst.setWritable(true, false);
    }

    public static String readText(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            final byte[] buf = new byte[Math.min(maxBytes, 1 << 20)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 递归删除（尽力而为，失败不抛）。 */
    public static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long sum = 0L;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) sum += dirSize(k);
        }
        return sum;
    }

    public static String sha256(File f) throws IOException {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("设备不支持 SHA-256", e);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        final byte[] d = md.digest();
        final StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 把文件设成「所有人可读、可执行」，让高权限 shell 也能直接跑它。 */
    public static void makeExecutable(File f) {
        if (f == null || !f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable(true, false);
    }

    public static void makeTreeReadable(File dir) {
        if (dir == null || !dir.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        dir.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dir.setExecutable(true, false);
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                makeTreeReadable(k);
            }
        }
    }

    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
