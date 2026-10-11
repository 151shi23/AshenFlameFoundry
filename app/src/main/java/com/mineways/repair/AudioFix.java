package com.mineways.repair;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频格式修复：
 *
 * <ul>
 *   <li><b>WAV</b>：重算 RIFF 与 data 块长度 —— 头里长度写错/被截断是最常见的一类坏（播放器直接拒收）；</li>
 *   <li><b>MP3</b>：ID3v2 头长度不合理就剥掉；开头有垃圾就裁到第一个有效帧同步；</li>
 *   <li>OGG / FLAC / M4A：识别与扩展名纠正（结构复杂，不做破坏性改动）。</li>
 * </ul>
 */
public final class AudioFix {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    public static final class Result {
        public String realFormat = "未知";
        public String ext = "";
        public byte[] data;
        public final List<String> notes = new ArrayList<>();
    }

    private AudioFix() {
    }

    public static String magic(byte[] d) {
        if (d == null || d.length < 12) {
            return "未知";
        }
        if (ImageFix.startsWith(d, new byte[]{'R', 'I', 'F', 'F'}) && d[8] == 'W' && d[9] == 'A'
                && d[10] == 'V' && d[11] == 'E') {
            return "WAV";
        }
        if (d.length > 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            return "MP3";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) {
            return "MP3";
        }
        if (ImageFix.startsWith(d, new byte[]{'O', 'g', 'g', 'S'})) {
            return "OGG";
        }
        if (ImageFix.startsWith(d, new byte[]{'f', 'L', 'a', 'C'})) {
            return "FLAC";
        }
        if (d.length > 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return "M4A";
        }
        return "未知";
    }

    public static Result fix(byte[] d) {
        final Result r = new Result();
        r.data = d == null ? new byte[0] : d;
        r.realFormat = magic(r.data);
        switch (r.realFormat) {
            case "WAV":
                r.ext = "wav";
                r.data = fixWav(r.data, r.notes);
                break;
            case "MP3":
                r.ext = "mp3";
                r.data = fixMp3(r.data, r.notes);
                break;
            case "OGG":
                r.ext = "ogg";
                r.notes.add("OGG 容器：只做识别与扩展名纠正");
                break;
            case "FLAC":
                r.ext = "flac";
                r.notes.add("FLAC：只做识别与扩展名纠正");
                break;
            case "M4A":
                r.ext = "m4a";
                r.notes.add("M4A/AAC：只做识别与扩展名纠正");
                break;
            default:
                r.notes.add("魔数不认识，无法判断真实音频格式");
                break;
        }
        return r;
    }

    // ------------------------------------------------------------------ WAV
    private static byte[] fixWav(byte[] d, List<String> notes) {
        if (d.length < 44) {
            notes.add("文件太小，不像完整 WAV");
            return d;
        }
        final byte[] out = d.clone();
        final long declared = ImageFix.le32(out, 4);
        final long actual = out.length - 8L;
        if (declared != actual) {
            notes.add("RIFF 长度字段 " + declared + " → " + actual + "（已修正）");
            ImageFix.putLe32(out, 4, actual);
        }
        int p = 12;
        while (p + 8 <= out.length) {
            final String id = new String(out, p, 4, ASCII);
            final long size = ImageFix.le32(out, p + 4);
            if ("data".equals(id)) {
                final long avail = out.length - (p + 8);
                if (size != avail) {
                    notes.add("data 块长度 " + size + " → " + avail
                            + (size > avail ? "（音频被截断，按实际长度修正）" : "（头部少写了，已补齐）"));
                    ImageFix.putLe32(out, p + 4, avail);
                }
                return out;
            }
            final long adv = 8 + size + (size % 2);
            if (size < 0 || p + adv > out.length || adv <= 8) {
                notes.add("块「" + id + "」长度越界（" + size + "），遍历停止");
                return out;
            }
            p += adv;
        }
        notes.add("没找到 data 块，无法修复音频数据长度");
        return out;
    }

    // ------------------------------------------------------------------ MP3
    private static byte[] fixMp3(byte[] d, List<String> notes) {
        if (d.length > 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            final long size = ((d[6] & 0x7FL) << 21) | ((d[7] & 0x7FL) << 14)
                    | ((d[8] & 0x7FL) << 7) | (d[9] & 0x7FL);
            final long end = 10 + size;
            if (end > d.length) {
                notes.add("ID3v2 头声明 " + size + " 字节标签，但文件只有 " + d.length
                        + " 字节（头损坏），已剥掉这 10 字节头");
                return tail(d, 10);
            }
            notes.add("ID3v2 标签正常（" + size + " 字节）");
            return d;
        }
        // 没有 ID3：开头如果有垃圾，裁到第一个帧同步
        final int limit = Math.min(4096, d.length - 1);
        for (int i = 0; i < limit; i++) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xE0) == 0xE0) {
                if (i > 0) {
                    notes.add("开头有 " + i + " 字节垃圾，已裁掉");
                    return tail(d, i);
                }
                break;
            }
        }
        return d;
    }

    private static byte[] tail(byte[] d, int from) {
        final byte[] out = new byte[d.length - from];
        System.arraycopy(d, from, out, 0, out.length);
        return out;
    }
}
