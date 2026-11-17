package com.mineways.vr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 给 MP4 注入「球形视频」元数据，让播放器知道这是一段 360 全景视频。
 *
 * <p>不加这个，抖音即便开了「全景播放」也只会当普通视频播（画面被拉成一条扁带）。
 *
 * <h3>两种规范</h3>
 * <ul>
 *   <li><b>V1</b>：{@code moov.trak.uuid}，UUID {@code ffcc8263-f855-4a93-8814-587a02521fdd}，
 *       内容是 GSpherical 的 XMP。兼容性最好，绝大多数播放器都认。</li>
 *   <li><b>V2</b>：{@code stsd.avc1.sv3d}，二进制盒子（svhd / proj / prhd / equi），
 *       Google 现行规范，V1 与 V2 同时存在时播放器优先用 V2。</li>
 * </ul>
 *
 * <p>本类两个都写 —— 播放器认哪个都能用，这是最稳的做法。
 *
 * <h3>为什么可以在手机上做</h3>
 * {@code MediaMuxer} 默认把 {@code moov} 写在文件末尾，往 trak 里插盒子**不会移动 mdat 的
 * chunk 偏移**，所以只要重建 moov、改几个 size 字段即可。若 moov 不在末尾（faststart），
 * 则把 stco / co64 里的偏移统一加上增量。
 */
public final class SphericalMeta {

    /** V1 规范规定的 UUID：ffcc8263-f855-4a93-8814-587a02521fdd */
    private static final byte[] UUID_SPHERICAL = new byte[]{
            (byte) 0xff, (byte) 0xcc, (byte) 0x82, (byte) 0x63,
            (byte) 0xf8, (byte) 0x55, (byte) 0x4a, (byte) 0x93,
            (byte) 0x88, (byte) 0x14, (byte) 0x58, (byte) 0x7a,
            (byte) 0x02, (byte) 0x52, (byte) 0x1f, (byte) 0xdd
    };

    /** 这些盒子里装着子盒子，需要往里递归。 */
    private static final String[] CONTAINERS = {
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex"
    };

    private SphericalMeta() {
    }

    // ============================================================ 对外

    /**
     * 注入元数据。
     *
     * @param src      源 MP4（通常是 MediaMuxer 刚写出来的）
     * @param dst      目标文件；可以和 src 相同（内部先写临时文件再替换）
     * @param software 写进 StitchingSoftware 的软件名
     * @param withV2   是否同时写 V2 的 sv3d 盒子（建议 true）
     */
    public static void inject(File src, File dst, String software, boolean withV2) throws IOException {
        byte[] head;      // moov 之前的原始字节
        byte[] moov;      // 原始 moov
        byte[] tail;      // moov 之后的原始字节
        long moovOffset;

        try (RandomAccessFile raf = new RandomAccessFile(src, "r")) {
            long len = raf.length();
            if (len < 16) throw new IOException("文件太小，不像 MP4");
            moovOffset = -1;
            long off = 0;
            while (off + 8 <= len) {
                raf.seek(off);
                long size = readU32(raf);
                String type = readType(raf);
                int hdr = 8;
                if (size == 1) {
                    size = raf.readLong();
                    hdr = 16;
                } else if (size == 0) {
                    size = len - off;
                }
                if (size < hdr) break;
                if ("moov".equals(type)) {
                    moovOffset = off;
                    break;
                }
                off += size;
            }
            if (moovOffset < 0) throw new IOException("找不到 moov 盒子");

            raf.seek(moovOffset);
            long moovSize = readU32(raf);
            int hdr = 8;
            if (moovSize == 1) {
                raf.seek(moovOffset + 8);
                moovSize = raf.readLong();
                hdr = 16;
            }
            if (moovSize <= 0 || moovOffset + moovSize > len) {
                throw new IOException("moov 尺寸不合法：" + moovSize);
            }

            head = new byte[(int) moovOffset];
            raf.seek(0);
            raf.readFully(head);

            moov = new byte[(int) moovSize];
            raf.seek(moovOffset);
            raf.readFully(moov);

            long tailLen = len - moovOffset - moovSize;
            tail = new byte[(int) tailLen];
            if (tailLen > 0) {
                raf.seek(moovOffset + moovSize);
                raf.readFully(tail);
            }
        }

        byte[] newMoov = rebuildMoov(moov, software, withV2);
        long delta = newMoov.length - moov.length;

        // moov 后面还有数据（faststart）→ 后面的盒子整体后移，chunk 偏移要跟着改
        if (delta != 0 && tail.length > 0) {
            patchChunkOffsets(newMoov, delta);
        }

        File out = dst;
        File tmp = null;
        if (dst.equals(src)) {
            tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
            out = tmp;
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(head);
            fos.write(newMoov);
            fos.write(tail);
        }
        if (tmp != null) {
            if (!tmp.renameTo(dst)) {
                copyFile(tmp, dst);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 文件里是否已经有球形元数据（V1 的 uuid 或 V2 的 sv3d）。 */
    public static boolean has(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long off = 0;
            long moovOff = -1;
            long moovSize = 0;
            while (off + 8 <= len) {
                raf.seek(off);
                long size = readU32(raf);
                String type = readType(raf);
                int hdr = 8;
                if (size == 1) {
                    size = raf.readLong();
                    hdr = 16;
                } else if (size == 0) {
                    size = len - off;
                }
                if (size < hdr) break;
                if ("moov".equals(type)) {
                    moovOff = off;
                    moovSize = size;
                    break;
                }
                off += size;
            }
            // moov 通常在文件末尾，不能只看开头几 MB
            if (moovOff < 0 || moovSize <= 0 || moovSize > 64L * 1024 * 1024) return false;
            byte[] b = new byte[(int) moovSize];
            raf.seek(moovOff);
            raf.readFully(b);
            return indexOf(b, b.length, "sv3d".getBytes(StandardCharsets.US_ASCII)) >= 0
                    || indexOf(b, b.length, UUID_SPHERICAL) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================ moov 重建

    private static byte[] rebuildMoov(byte[] moov, String software, boolean withV2) {
        List<Box> kids = scan(moov, 8, moov.length);
        // 只有顶层第一个盒子才是 moov 本身
        if (kids.isEmpty()) return moov;

        byte[] v1 = withV2 ? buildV1Box(software) : buildV1Box(software);
        byte[] v2 = withV2 ? buildSv3dBox(software) : null;

        boolean changed = false;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();

        for (Box b : kids) {
            if (!"trak".equals(b.type)) {
                bos.write(moov, b.off, b.size);
                continue;
            }
            byte[] trak = java.util.Arrays.copyOfRange(moov, b.off, b.off + b.size);
            byte[] newTrak = rebuildTrak(trak, v1, v2);
            if (newTrak.length != trak.length) changed = true;
            bos.write(newTrak, 0, newTrak.length);
        }
        if (!changed) return moov;

        byte[] body = bos.toByteArray();
        int total = body.length + 8;
        byte[] out = new byte[total];
        writeU32(out, 0, total);
        writeType(out, 4, "moov");
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static byte[] rebuildTrak(byte[] trak, byte[] v1, byte[] v2) {
        List<Box> kids = scan(trak, 8, trak.length);
        if (kids.isEmpty() || !isVideoTrak(trak, kids)) return trak;

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean hasV1 = false;
        boolean hasV2 = false;

        for (Box b : kids) {
            if ("uuid".equals(b.type) && b.size >= 24
                    && matches(trak, b.off + 8, UUID_SPHERICAL)) {
                hasV1 = true;
            }
            if ("mdia".equals(b.type)) {
                byte[] mdia = java.util.Arrays.copyOfRange(trak, b.off, b.off + b.size);
                byte[] newMdia = rebuildMdia(mdia, v2);
                hasV2 = hasV2 || newMdia.length != mdia.length;
                bos.write(newMdia, 0, newMdia.length);
            } else {
                bos.write(trak, b.off, b.size);
            }
        }
        if (!hasV1) bos.write(v1, 0, v1.length);

        byte[] body = bos.toByteArray();
        byte[] out = new byte[body.length + 8];
        writeU32(out, 0, out.length);
        writeType(out, 4, "trak");
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    /** 往 mdia.minf.stbl.stsd.avc1 里塞 sv3d。 */
    private static byte[] rebuildMdia(byte[] mdia, byte[] v2) {
        if (v2 == null) return mdia;
        List<Box> kids = scan(mdia, 8, mdia.length);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean changed = false;
        for (Box b : kids) {
            if ("minf".equals(b.type)) {
                byte[] minf = java.util.Arrays.copyOfRange(mdia, b.off, b.off + b.size);
                byte[] newMinf = rebuildMinf(minf, v2);
                changed = changed || newMinf.length != minf.length;
                bos.write(newMinf, 0, newMinf.length);
            } else {
                bos.write(mdia, b.off, b.size);
            }
        }
        if (!changed) return mdia;
        return wrap("mdia", bos.toByteArray());
    }

    private static byte[] rebuildMinf(byte[] minf, byte[] v2) {
        List<Box> kids = scan(minf, 8, minf.length);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean changed = false;
        for (Box b : kids) {
            if ("stbl".equals(b.type)) {
                byte[] stbl = java.util.Arrays.copyOfRange(minf, b.off, b.off + b.size);
                byte[] newStbl = rebuildStbl(stbl, v2);
                changed = changed || newStbl.length != stbl.length;
                bos.write(newStbl, 0, newStbl.length);
            } else {
                bos.write(minf, b.off, b.size);
            }
        }
        if (!changed) return minf;
        return wrap("minf", bos.toByteArray());
    }

    private static byte[] rebuildStbl(byte[] stbl, byte[] v2) {
        List<Box> kids = scan(stbl, 8, stbl.length);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        boolean changed = false;
        for (Box b : kids) {
            if ("stsd".equals(b.type)) {
                byte[] stsd = java.util.Arrays.copyOfRange(stbl, b.off, b.off + b.size);
                byte[] newStsd = rebuildStsd(stsd, v2);
                changed = changed || newStsd.length != stsd.length;
                bos.write(newStsd, 0, newStsd.length);
            } else {
                bos.write(stbl, b.off, b.size);
            }
        }
        if (!changed) return stbl;
        return wrap("stbl", bos.toByteArray());
    }

    /** stsd = 8 字节头 + version/flags(4) + entry_count(4) + 若干个 sample entry。 */
    private static byte[] rebuildStsd(byte[] stsd, byte[] v2) {
        if (stsd.length < 16) return stsd;
        int count = (int) u32(stsd, 12);
        if (count <= 0) return stsd;

        int p = 16;
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(stsd, 8, 8);           // version/flags + entry_count 原样
        boolean changed = false;

        for (int i = 0; i < count && p + 8 <= stsd.length; i++) {
            long size = u32(stsd, p);
            String type = fourcc(stsd, p + 4);
            if (size < 8 || p + size > stsd.length) break;

            if (isVisualEntry(type)) {
                byte[] entry = java.util.Arrays.copyOfRange(stsd, p, p + (int) size);
                byte[] newEntry = appendChild(entry, type, 78, v2);
                changed = changed || newEntry.length != entry.length;
                body.write(newEntry, 0, newEntry.length);
            } else {
                body.write(stsd, p, (int) size);
            }
            p += size;
        }
        if (!changed) return stsd;
        return wrap("stsd", body.toByteArray());
    }

    /** VisualSampleEntry 固定 78 字节（8 字节盒头 + 70 字节字段），后面才是子盒子。 */
    private static byte[] appendChild(byte[] entry, String type, int fixedHeader, byte[] child) {
        if (entry.length < fixedHeader) return entry;
        byte[] out = new byte[entry.length + child.length];
        System.arraycopy(entry, 0, out, 0, entry.length);
        System.arraycopy(child, 0, out, entry.length, child.length);
        writeU32(out, 0, out.length);
        writeType(out, 4, type);
        return out;
    }

    // ============================================================ 盒子构造

    /** V1：moov.trak.uuid，内容是一段 GSpherical 的 XMP。 */
    static byte[] buildV1Box(String software) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<rdf:SphericalVideo xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                + " xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\">\n"
                + "  <GSpherical:Spherical>true</GSpherical:Spherical>\n"
                + "  <GSpherical:Stitched>true</GSpherical:Stitched>\n"
                + "  <GSpherical:StitchingSoftware>" + escape(software) + "</GSpherical:StitchingSoftware>\n"
                + "  <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>\n"
                + "</rdf:SphericalVideo>\n";
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + 16 + body.length];
        writeU32(out, 0, out.length);
        writeType(out, 4, "uuid");
        System.arraycopy(UUID_SPHERICAL, 0, out, 8, 16);
        System.arraycopy(body, 0, out, 24, body.length);
        return out;
    }

    /**
     * V2：sv3d（svhd + proj(prhd + equi)），放进 avc1 里。
     *
     * <p>字段全部大端；pose 是 16.16 定点（单位度），bounds 是 0.32 定点（裁剪比例），
     * 不做裁剪时四个 bounds 全 0。
     */
    static byte[] buildSv3dBox(String software) {
        // svhd：FullBox(version0/flags0) + null 结尾的 UTF-8
        byte[] src = (software == null ? "" : software).getBytes(StandardCharsets.UTF_8);
        byte[] svhdBody = new byte[4 + src.length + 1];
        System.arraycopy(src, 0, svhdBody, 4, src.length);
        byte[] svhd = wrap("svhd", svhdBody);

        // prhd：FullBox + 三个 16.16 定点（yaw/pitch/roll 都为 0）
        byte[] prhd = wrap("prhd", new byte[4 + 12]);

        // equi：FullBox + 四个 0.32 定点（全 0 = 不裁剪）
        byte[] equi = wrap("equi", new byte[4 + 16]);

        byte[] projBody = concat(prhd, equi);
        byte[] proj = wrap("proj", projBody);

        byte[] sv3dBody = concat(svhd, proj);
        return wrap("sv3d", sv3dBody);
    }

    // ============================================================ 偏移修正

    /** moov 不在末尾时，它后面的数据整体后移 delta，所有 chunk 偏移要加 delta。 */
    private static void patchChunkOffsets(byte[] moov, long delta) {
        patchRecursive(moov, 0, moov.length, delta);
    }

    private static void patchRecursive(byte[] b, int start, int end, long delta) {
        int i = start;
        while (i + 8 <= end) {
            long size = u32(b, i);
            String type = fourcc(b, i + 4);
            int hdr = 8;
            if (size == 1) {
                size = u64(b, i + 8);
                hdr = 16;
            }
            if (size < hdr || i + size > end) return;

            if ("stco".equals(type)) {
                long n = u32(b, i + hdr + 4);
                int p = i + hdr + 8;
                for (int k = 0; k < n && p + 4 <= i + size; k++, p += 4) {
                    long v = u32(b, p) + delta;
                    writeU32(b, p, (int) v);
                }
            } else if ("co64".equals(type)) {
                long n = u32(b, i + hdr + 4);
                int p = i + hdr + 8;
                for (int k = 0; k < n && p + 8 <= i + size; k++, p += 8) {
                    long v = u64(b, p) + delta;
                    writeU64(b, p, v);
                }
            } else if (isContainer(type)) {
                patchRecursive(b, i + hdr, i + (int) size, delta);
            }
            i += size;
        }
    }

    // ============================================================ 小工具

    private static final class Box {
        final int off;
        final int size;
        final String type;

        Box(int off, int size, String type) {
            this.off = off;
            this.size = size;
            this.type = type;
        }
    }

    private static List<Box> scan(byte[] b, int start, int end) {
        List<Box> out = new ArrayList<>();
        int i = start;
        while (i + 8 <= end) {
            long size = u32(b, i);
            String type = fourcc(b, i + 4);
            int hdr = 8;
            if (size == 1) {
                if (i + 16 > end) break;
                size = u64(b, i + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - i;
            }
            if (size < hdr || i + size > end) break;
            out.add(new Box(i, (int) size, type));
            i += size;
        }
        return out;
    }

    private static boolean isVideoTrak(byte[] trak, List<Box> kids) {
        for (Box b : kids) {
            if (!"mdia".equals(b.type)) continue;
            List<Box> mdiaKids = scan(trak, b.off + 8, b.off + b.size);
            for (Box m : mdiaKids) {
                if (!"hdlr".equals(m.type)) continue;
                // hdlr: version/flags(4) + pre_defined(4) + handler_type(4)
                int p = m.off + 8 + 8;
                if (p + 4 <= trak.length && "vide".equals(fourcc(trak, p))) return true;
            }
        }
        return false;
    }

    private static boolean isVisualEntry(String type) {
        return "avc1".equals(type) || "avc3".equals(type) || "hvc1".equals(type)
                || "hev1".equals(type) || "mp4v".equals(type) || "av01".equals(type);
    }

    private static boolean isContainer(String type) {
        for (String c : CONTAINERS) if (c.equals(type)) return true;
        return false;
    }

    private static byte[] wrap(String type, byte[] body) {
        byte[] out = new byte[body.length + 8];
        writeU32(out, 0, out.length);
        writeType(out, 4, type);
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static long readU32(RandomAccessFile raf) throws IOException {
        return raf.readInt() & 0xFFFFFFFFL;
    }

    private static String readType(RandomAccessFile raf) throws IOException {
        byte[] t = new byte[4];
        raf.readFully(t);
        return new String(t, StandardCharsets.US_ASCII);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    private static String fourcc(byte[] b, int off) {
        if (off + 4 > b.length) return "";
        return new String(b, off, 4, StandardCharsets.US_ASCII);
    }

    private static void writeU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void writeU64(byte[] b, int off, long v) {
        writeU32(b, off, (int) (v >>> 32));
        writeU32(b, off + 4, (int) v);
    }

    private static void writeType(byte[] b, int off, String t) {
        byte[] s = t.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, b, off, 4);
    }

    private static boolean matches(byte[] b, int off, byte[] pat) {
        if (off < 0 || off + pat.length > b.length) return false;
        for (int i = 0; i < pat.length; i++) if (b[off + i] != pat[i]) return false;
        return true;
    }

    private static int indexOf(byte[] b, int len, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= len; i++) {
            for (int j = 0; j < pat.length; j++) if (b[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
