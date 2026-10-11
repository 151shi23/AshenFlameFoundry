package com.mineways.repair;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 通用 ZIP 修复：<b>中央目录优先，坏掉才回退到本地头扫描</b>，最后重建成规范 ZIP。
 *
 * <p>为什么要两条路：中央目录在时它是权威（条目名/长度/CRC/偏移都齐），按它读最准；
 * 中央目录或 EOCD 损坏时（"压缩包已损坏"的典型），才用本地头 + 数据描述符把条目一条条扫出来 ——
 * 这是唯一能把内容救回来的办法。
 *
 * <p>顺带处理：0x60 伪装头（Prisma3D 3.0 把 PK 的 'P' 改过）、CRC 重算、剥离前后垃圾、统一重写容器。
 */
public final class ZipFix {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] LFH = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] FAKE_LFH = {0x60, 0x4B, 0x03, 0x04};
    private static final byte[] CDH = {0x50, 0x4B, 0x01, 0x02};
    private static final byte[] EOCD = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] DD = {0x50, 0x4B, 0x07, 0x08};

    public static final class Entry {
        public String name;
        public int method;
        public long crc;
        public byte[] data;
        public int localOffset;
        public boolean crcWasBad;
        public boolean fromDescriptor;
    }

    public static final class Report {
        public final List<Entry> entries = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public boolean hadEocd;
        public boolean hadCd;
        public boolean fakeHeader;
        public int scannedLocalHeaders;
        public boolean rebuiltByScan;

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append("条目 ").append(entries.size()).append(" 个");
            if (rebuiltByScan) {
                sb.append("（中央目录不可用 → 由本地头扫描重建，扫描到 ").append(scannedLocalHeaders).append(" 个本地头）");
            }
            if (fakeHeader) {
                sb.append(" · 头部是 0x60 伪装（ZIP 的 'P' 被改过）");
            }
            sb.append(hadCd ? " · 中央目录存在" : " · 中央目录缺失/损坏");
            sb.append(hadEocd ? " · EOCD 存在" : " · EOCD 缺失");
            return sb.toString();
        }
    }

    private ZipFix() {
    }

    public static Report scan(byte[] d) {
        final Report r = new Report();
        if (d == null || d.length < 30) {
            return r;
        }
        r.fakeHeader = (d[0] & 0xFF) == 0x60 && d[1] == 0x4B;
        final int eocd = lastIndexOf(d, EOCD);
        r.hadEocd = eocd >= 0;

        // ---------------- 路线 A：中央目录优先
        if (eocd >= 0 && eocd + 22 <= d.length) {
            final int total = u16(d, eocd + 10);
            final long cdOff = u32(d, eocd + 16);
            if (cdOff > 0 && cdOff + 46 <= d.length && match(d, (int) cdOff, CDH)) {
                r.hadCd = true;
                int p = (int) cdOff;
                for (int k = 0; k < total && p + 46 <= d.length; k++) {
                    if (!match(d, p, CDH)) {
                        r.notes.add("中央目录在第 " + (k + 1) + " 条处断裂");
                        break;
                    }
                    final int flags = u16(d, p + 8);
                    final int method = u16(d, p + 10);
                    final long crc = u32(d, p + 16);
                    final long csize = u32(d, p + 20);
                    final int nameLen = u16(d, p + 28);
                    final int extraLen = u16(d, p + 30);
                    final int cmtLen = u16(d, p + 32);
                    final long localOff = u32(d, p + 42);
                    if (p + 46 + nameLen + extraLen + cmtLen > d.length) {
                        r.notes.add("中央目录条目越界，停止解析");
                        break;
                    }
                    final String name = new String(d, p + 46, nameLen, UTF8);
                    p += 46 + nameLen + extraLen + cmtLen;

                    final int lo = (int) localOff;
                    if (lo + 30 > d.length || !(match(d, lo, LFH) || match(d, lo, FAKE_LFH))) {
                        r.notes.add("条目「" + name + "」的本地头位置无效，跳过");
                        continue;
                    }
                    final int dataStart = lo + 30 + u16(d, lo + 26) + u16(d, lo + 28);
                    final byte[] plain = readEntry(d, dataStart, (int) csize, method,
                            (flags & 0x08) != 0);
                    if (plain == null) {
                        r.notes.add("条目「" + name + "」解压失败，跳过");
                        continue;
                    }
                    final CRC32 cc = new CRC32();
                    cc.update(plain);
                    final Entry e = new Entry();
                    e.name = name;
                    e.method = method;
                    e.crc = cc.getValue();
                    e.data = plain;
                    e.localOffset = lo;
                    e.crcWasBad = crc != 0 && crc != e.crc;
                    r.entries.add(e);
                }
            }
        }

        // ---------------- 路线 B：中央目录不可用 → 扫本地头
        if (r.entries.isEmpty()) {
            r.rebuiltByScan = true;
            scanLocalHeaders(d, r);
        }
        if (r.fakeHeader) {
            r.notes.add("检测到 0x60 伪装头（Prisma3D 3.0 特征），已按标准 ZIP 处理");
        }
        return r;
    }

    private static void scanLocalHeaders(byte[] d, Report r) {
        int i = 0;
        while (i + 30 <= d.length) {
            int sig = indexOf(d, i, LFH);
            final int fake = indexOf(d, i, FAKE_LFH);
            if (fake >= 0 && (sig < 0 || fake < sig)) {
                sig = fake;                      // 首个条目可能带伪装头
            }
            if (sig < 0) {
                break;
            }
            r.scannedLocalHeaders++;
            final int flags = u16(d, sig + 6);
            final int method = u16(d, sig + 8);
            final long hdrCrc = u32(d, sig + 14);
            int csize = (int) u32(d, sig + 18);
            final int nameLen = u16(d, sig + 26);
            final int extraLen = u16(d, sig + 28);
            if (sig + 30 + nameLen > d.length) {
                break;
            }
            final String name = new String(d, sig + 30, nameLen, UTF8);
            final int dataStart = sig + 30 + nameLen + extraLen;
            final boolean descriptor = (flags & 0x08) != 0;
            final byte[] plain = readEntry(d, dataStart, csize, method, descriptor);
            if (plain == null) {
                r.notes.add("条目「" + name + "」解压失败，跳过");
                i = Math.max(i + 4, dataStart + Math.max(1, csize));
                continue;
            }
            // 推进：按真实吃掉的字节数找数据描述符，再跳到下一条
            final int consumed = consumedBytes(d, dataStart, plain.length, method, csize);
            int next = dataStart + Math.max(1, consumed);
            if (descriptor) {
                int dp = indexOf(d, Math.max(dataStart, next - 8), DD);
                if (dp >= 0 && dp < next + 32) {
                    next = dp + 16;
                }
            }
            final CRC32 cc = new CRC32();
            cc.update(plain);
            final Entry e = new Entry();
            e.name = name;
            e.method = method;
            e.crc = cc.getValue();
            e.data = plain;
            e.localOffset = sig;
            e.crcWasBad = !descriptor && hdrCrc != 0 && hdrCrc != e.crc;
            e.fromDescriptor = descriptor;
            r.entries.add(e);
            i = Math.max(i + 4, next);
        }
    }

    /** 解出条目内容（stored / deflate；长度未知时靠 inflate 自己吃掉多少算多少）。 */
    private static byte[] readEntry(byte[] d, int dataStart, int csize, int method, boolean descriptor) {
        if (dataStart < 0 || dataStart >= d.length) {
            return null;
        }
        try {
            if (method == 0) {
                int len = csize;
                if (descriptor || len <= 0 || dataStart + len > d.length) {
                    int next = indexOf(d, dataStart, LFH);
                    final int fake = indexOf(d, dataStart, FAKE_LFH);
                    if (fake >= 0 && (next < 0 || fake < next)) {
                        next = fake;
                    }
                    len = (next < 0 ? d.length : next) - dataStart;
                    int dp = indexOf(d, Math.max(dataStart, dataStart + len - 20), DD);
                    if (dp >= 0 && dp - dataStart > 0) {
                        len = dp - dataStart;
                    }
                }
                if (len <= 0) {
                    return null;
                }
                final byte[] out = new byte[len];
                System.arraycopy(d, dataStart, out, 0, len);
                return out;
            }
            if (method == 8) {
                final Inflater inf = new Inflater(true);
                inf.setInput(d, dataStart, d.length - dataStart);
                final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, csize));
                final byte[] buf = new byte[1 << 16];
                while (!inf.finished()) {
                    final int n = inf.inflate(buf);
                    if (n > 0) {
                        bos.write(buf, 0, n);
                    } else if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    } else {
                        break;
                    }
                }
                inf.end();
                return bos.toByteArray();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 该条目实际吃掉了多少字节（用于推进扫描位置）。 */
    private static int consumedBytes(byte[] d, int dataStart, int plainLen, int method, int csize) {
        if (csize > 0) {
            return csize;
        }
        if (method == 0) {
            return Math.max(1, plainLen);
        }
        try {
            final Inflater inf = new Inflater(true);
            inf.setInput(d, dataStart, d.length - dataStart);
            final byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                final int n = inf.inflate(buf);
                if (n <= 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                if (n == 0 && !inf.finished()) {
                    break;
                }
            }
            final int consumed = (int) inf.getBytesRead();
            inf.end();
            return Math.max(1, consumed);
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 用重建的条目表写出规范 ZIP（原本不压缩存储的条目继续保持不压缩：内容逐字节保留）。 */
    public static byte[] rebuild(Report r) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        final ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            for (Entry e : r.entries) {
                final ZipEntry ze = new ZipEntry(e.name);
                ze.setTime(0L);
                if (e.method == 0 && e.data != null) {
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(e.data.length);
                    ze.setCompressedSize(e.data.length);
                    final CRC32 crc = new CRC32();
                    crc.update(e.data);
                    ze.setCrc(crc.getValue());
                }
                zos.putNextEntry(ze);
                zos.write(e.data);
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 小工具
    public static int indexOf(byte[] d, int from, byte[] pat) {
        outer:
        for (int i = Math.max(0, from); i + pat.length <= d.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (d[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static int lastIndexOf(byte[] d, byte[] pat) {
        for (int i = d.length - pat.length; i >= 0; i--) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) {
                if (d[i + j] != pat[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    private static boolean match(byte[] d, int off, byte[] pat) {
        if (off < 0 || off + pat.length > d.length) {
            return false;
        }
        for (int i = 0; i < pat.length; i++) {
            if (d[off + i] != pat[i]) {
                return false;
            }
        }
        return true;
    }

    public static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    public static long u32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8)
                | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
