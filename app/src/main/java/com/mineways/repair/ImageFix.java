package com.mineways.repair;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片格式修复：先按<b>魔数</b>判真格式（扩展名骗人的情况一堆），再补结构尾、剥掉尾随垃圾。
 *
 * <ul>
 *   <li>PNG：逐块走到 IEND；没走到就补一个合法 IEND；IEND 之后的垃圾剥掉；</li>
 *   <li>JPEG：末尾必须有 EOI（FF D9）；缺了补上，后面多出来的剥掉；</li>
 *   <li>GIF：末尾补 trailer（0x3B）；</li>
 *   <li>WEBP：修 RIFF 长度字段；BMP：修文件长度字段。</li>
 * </ul>
 */
public final class ImageFix {

    public static final class Result {
        public String realFormat = "未知";
        public String ext = "";
        public byte[] data;
        public final List<String> notes = new ArrayList<>();
    }

    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] IEND = {'I', 'E', 'N', 'D'};

    private ImageFix() {
    }

    /** 按魔数判格式（不看扩展名）。 */
    public static String magic(byte[] d) {
        if (d == null || d.length < 4) {
            return "未知";
        }
        if (startsWith(d, PNG_SIG)) {
            return "PNG";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "JPEG";
        }
        if (d.length > 5 && (startsWith(d, new byte[]{'G', 'I', 'F', '8', '7', 'a'})
                || startsWith(d, new byte[]{'G', 'I', 'F', '8', '9', 'a'}))) {
            return "GIF";
        }
        if (d.length > 12 && startsWith(d, new byte[]{'R', 'I', 'F', 'F'})
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "WEBP";
        }
        if ((d[0] & 0xFF) == 0x42 && (d[1] & 0xFF) == 0x4D) {
            return "BMP";
        }
        if (startsWith(d, new byte[]{'I', 'I', 0x2A, 0x00}) || startsWith(d, new byte[]{'M', 'M', 0x00, 0x2A})) {
            return "TIFF";
        }
        if (startsWith(d, new byte[]{'R', 'I', 'F', 'F'}) && d.length > 11
                && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E') {
            return "WAV";
        }
        return "未知";
    }

    public static Result fix(byte[] d) {
        final Result r = new Result();
        r.data = d == null ? new byte[0] : d;
        r.realFormat = magic(r.data);
        switch (r.realFormat) {
            case "PNG":
                r.ext = "png";
                r.data = fixPng(r.data, r.notes);
                break;
            case "JPEG":
                r.ext = "jpg";
                r.data = fixJpeg(r.data, r.notes);
                break;
            case "GIF":
                r.ext = "gif";
                r.data = fixGif(r.data, r.notes);
                break;
            case "WEBP":
                r.ext = "webp";
                r.data = fixRiff(r.data, r.notes);
                break;
            case "WAV":
                r.ext = "wav";
                r.data = AudioFix.fix(r.data).data;
                r.notes.add("其实这是音频（WAV），已按音频处理");
                break;
            case "BMP":
                r.ext = "bmp";
                r.data = fixBmp(r.data, r.notes);
                break;
            case "TIFF":
                r.ext = "tif";
                r.notes.add("TIFF 结构复杂，只做识别与扩展名纠正，不改动内容");
                break;
            default:
                r.notes.add("魔数不认识，无法判断真实图片格式（可能不是图片，或头部已被破坏）");
                break;
        }
        return r;
    }

    // ------------------------------------------------------------------ PNG
    private static byte[] fixPng(byte[] d, List<String> notes) {
        final byte[] out = d.clone();
        int p = 8;
        int crcFixed = 0;
        while (p + 8 <= out.length) {
            final long len = be32(out, p);
            if (len < 0 || p + 12 + len > out.length) {
                notes.add("PNG 块结构在偏移 " + p + " 处断裂，已补上 IEND 收尾");
                if (crcFixed > 0) {
                    notes.add("重算了 " + crcFixed + " 个块的 CRC");
                }
                return append(out, iendChunk());
            }
            final String type = new String(out, p + 4, 4, java.nio.charset.Charset.forName("US-ASCII"));
            final int end = (int) (p + 12 + len);
            // 块 CRC 重算：类型 + 数据 的 CRC32（严格读取器会因为 CRC 不符整张图拒收）
            try {
                final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(out, p + 4, (int) len + 4);
                final long stored = be32(out, p + 8 + (int) len);
                if (stored != crc.getValue()) {
                    putBe32(out, p + 8 + (int) len, crc.getValue());
                    crcFixed++;
                }
            } catch (Throwable ignored) {
            }
            if ("IEND".equals(type)) {
                if (crcFixed > 0) {
                    notes.add("重算了 " + crcFixed + " 个块的 CRC（原 CRC 与内容不符）");
                }
                if (end < out.length) {
                    notes.add("IEND 之后有 " + (out.length - end) + " 字节垃圾，已剥掉");
                    final byte[] trimmed = new byte[end];
                    System.arraycopy(out, 0, trimmed, 0, end);
                    return trimmed;
                }
                return out;
            }
            p = end;
        }
        if (crcFixed > 0) {
            notes.add("重算了 " + crcFixed + " 个块的 CRC");
        }
        notes.add("PNG 没有收尾块（IEND），已补上");
        return append(out, iendChunk());
    }

    private static byte[] iendChunk() {
        final byte[] c = new byte[12];
        c[4] = 'I';
        c[5] = 'E';
        c[6] = 'N';
        c[7] = 'D';
        c[8] = (byte) 0xAE;
        c[9] = 0x42;
        c[10] = 0x60;
        c[11] = (byte) 0x82;    // IEND 的固定 CRC
        return c;
    }

    // ------------------------------------------------------------------ JPEG
    private static byte[] fixJpeg(byte[] d, List<String> notes) {
        int last = -1;
        for (int i = d.length - 2; i >= 2; i--) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            notes.add("JPEG 缺结束标记 EOI（FF D9），已补上");
            return append(d, new byte[]{(byte) 0xFF, (byte) 0xD9});
        }
        final int end = last + 2;
        if (end < d.length) {
            notes.add("EOI 之后有 " + (d.length - end) + " 字节垃圾，已剥掉");
            final byte[] out = new byte[end];
            System.arraycopy(d, 0, out, 0, end);
            return out;
        }
        return d;
    }

    // ------------------------------------------------------------------ GIF / RIFF / BMP
    private static byte[] fixGif(byte[] d, List<String> notes) {
        if (d.length > 0 && (d[d.length - 1] & 0xFF) == 0x3B) {
            return d;
        }
        notes.add("GIF 缺结尾字节 0x3B，已补上");
        return append(d, new byte[]{0x3B});
    }

    private static byte[] fixRiff(byte[] d, List<String> notes) {
        if (d.length < 12) {
            return d;
        }
        final long declared = le32(d, 4);
        final long actual = d.length - 8L;
        if (declared != actual) {
            notes.add("RIFF 长度字段 = " + declared + "，实际 " + actual + "，已修正");
            final byte[] out = d.clone();
            putLe32(out, 4, actual);
            return out;
        }
        return d;
    }

    private static byte[] fixBmp(byte[] d, List<String> notes) {
        if (d.length < 6) {
            return d;
        }
        final long declared = le32(d, 2);
        if (declared != d.length) {
            notes.add("BMP 文件长度字段 = " + declared + "，实际 " + d.length + "，已修正");
            final byte[] out = d.clone();
            putLe32(out, 2, d.length);
            return out;
        }
        return d;
    }

    // ------------------------------------------------------------------ 工具
    public static boolean startsWith(byte[] d, byte[] pat) {
        if (d.length < pat.length) {
            return false;
        }
        for (int i = 0; i < pat.length; i++) {
            if (d[i] != pat[i]) {
                return false;
            }
        }
        return true;
    }

    public static long be32(byte[] d, int o) {
        return ((d[o] & 0xFFL) << 24) | ((d[o + 1] & 0xFFL) << 16)
                | ((d[o + 2] & 0xFFL) << 8) | (d[o + 3] & 0xFFL);
    }

    public static long le32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8)
                | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    public static void putBe32(byte[] d, int o, long v) {
        d[o] = (byte) ((v >> 24) & 0xFF);
        d[o + 1] = (byte) ((v >> 16) & 0xFF);
        d[o + 2] = (byte) ((v >> 8) & 0xFF);
        d[o + 3] = (byte) (v & 0xFF);
    }

    public static void putLe32(byte[] d, int o, long v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
        d[o + 2] = (byte) ((v >> 16) & 0xFF);
        d[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    public static byte[] append(byte[] d, byte[] tail) {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(d.length + tail.length);
        bos.write(d, 0, d.length);
        bos.write(tail, 0, tail.length);
        return bos.toByteArray();
    }
}
