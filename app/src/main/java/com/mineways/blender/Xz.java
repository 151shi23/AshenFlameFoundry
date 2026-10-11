package com.mineways.blender;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.zip.GZIPInputStream;

/**
 * XZ / GZIP 解压入口。
 *
 * <p>Blender 官方 Linux 包与 Debian 的 {@code Packages.xz}、{@code .deb} 里的
 * {@code data.tar.xz} 全是 xz。Android 的 {@code java.util.zip} 只有 deflate/gzip，
 * 所以优先用 {@code org.tukaani:xz}（纯 Java，在线构建会打进 APK；用反射调用是为了
 * 离线构建没拉到依赖时仍能编译，并自动回退到设备上的 {@code xz} 命令）。</p>
 */
public final class Xz {

    private Xz() {
    }

    private static InputStream newXzStream(InputStream in) throws IOException {
        try {
            Class<?> cls = Class.forName("org.tukaani.xz.XZInputStream");
            Constructor<?> ctor = cls.getConstructor(InputStream.class);
            return (InputStream) ctor.newInstance(in);
        } catch (Throwable t) {
            FileUtil.closeQuietly(in);
            throw new IOException("xz 解码器不可用（" + t + "）", t);
        }
    }

    /** 打开 {@code .xz} 解压流（调用方负责关闭）。 */
    public static InputStream openXz(File f) throws IOException {
        return newXzStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16));
    }

    /** 打开 {@code .gz} 解压流。 */
    public static InputStream openGz(File f) throws IOException {
        return new GZIPInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16), 1 << 16);
    }

    /**
     * 把 xz 流解成普通文件：优先纯 Java；没有解码器就借设备的 {@code xz -dc}（要 Shizuku）。
     *
     * @return 是否成功
     */
    public static boolean decompressToFile(File src, File dst) {
        if (RootShell.xzAvailable()) {
            try (InputStream in = openXz(src);
                 OutputStream out = new FileOutputStream(dst)) {
                final byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                FileUtil.makeTreeReadable(dst.getParentFile());
                return true;
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
        }
        if (!RootShell.isReady() || !RootShell.hasCommand("xz")) return false;
        final String cmd = "xz -dc " + RootShell.q(src.getAbsolutePath())
                + " > " + RootShell.q(dst.getAbsolutePath()) + " 2>/dev/null";
        return RootShell.sh(cmd).ok() && dst.isFile() && dst.length() > 0;
    }

    /** 按文件头猜压缩类型。 */
    public enum Kind { XZ, GZ, ZSTD, PLAIN, UNKNOWN }

    public static Kind sniff(File f) {
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            final byte[] h = new byte[6];
            int n = in.read(h);
            if (n < 2) return Kind.UNKNOWN;
            if ((h[0] & 0xFF) == 0xFD && h[1] == '7' && h[2] == 'z' && h[3] == 'X') return Kind.XZ;
            if ((h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) return Kind.GZ;
            if ((h[0] & 0xFF) == 0x28 && (h[1] & 0xFF) == 0xB5 && (h[2] & 0xFF) == 0x2F) return Kind.ZSTD;
            return Kind.PLAIN;
        } catch (Throwable t) {
            return Kind.UNKNOWN;
        }
    }

    /** 打开（可能是压缩的）归档流：自动识别 xz / gz。 */
    public static InputStream openArchive(File f) throws IOException {
        switch (sniff(f)) {
            case XZ:
                return openXz(f);
            case GZ:
                return openGz(f);
            case PLAIN:
                return new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16);
            default:
                throw new IOException("不支持的压缩格式：" + f.getName()
                        + "（zstd 包请换一个 Debian 套件，本工程的解压器只支持 xz/gzip）");
        }
    }
}
