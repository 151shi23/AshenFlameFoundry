package com.ember.logan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 压缩包/单文件遍历：解 zip（含一层嵌套 zip）、编码探测（UTF-8 → GBK 兜底）。 */
public final class ZipWalker {

    public static final class Entry {
        public final String name;   // 展示名（zip 内路径）
        public final byte[] head;   // 前 512 字节（魔数探测）
        public final List<String> lines;
        public final long size;
        public final boolean binary;
        public final String binaryNote;

        Entry(String name, byte[] head, List<String> lines, long size, boolean binary, String binaryNote) {
            this.name = name; this.head = head; this.lines = lines;
            this.size = size; this.binary = binary; this.binaryNote = binaryNote;
        }
    }

    /**
     * 安卓侧适配：手机堆装不下「整包所有条目的行」，所以边解包边交出去。
     * 上游 walk(File) 返回 List 的用法仍然保留（收集一条即可）。
     */
    public interface EntrySink {
        void onEntry(Entry entry) throws IOException;
    }

    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024; // 单条目上限 32MB
    private static final int MAX_ENTRIES = 300;
    private static final int MAX_TOTAL = 128 * 1024 * 1024;        // 总解压上限 128MB

    /** 入口：zip 或普通文件 → Entry 列表。 */
    public static List<Entry> walk(File f) throws IOException {
        final List<Entry> out = new ArrayList<>();
        walk(f, new EntrySink() {
            @Override
            public void onEntry(Entry entry) {
                out.add(entry);
            }
        });
        return out;
    }

    /** 流式入口：每解出一个可分析条目立刻交给 sink，不在内存里攒整包。 */
    public static void walk(File f, EntrySink sink) throws IOException {
        if (isZip(f)) {
            readZip(f, sink, 0);
        } else {
            try (InputStream in = new FileInputStream(f)) {
                Entry entry = readStream(f.getName(), in, f.length(), 0);
                if (entry != null) sink.onEntry(entry);
            }
        }
    }

    private static boolean isZip(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] m = new byte[4];
            int r = in.read(m);
            return r == 4 && m[0] == 'P' && m[1] == 'K' && (m[2] == 3 || m[2] == 7);
        }
    }

    private static void readZip(File f, EntrySink sink, int depth) throws IOException {
        if (depth > 1) return;
        long total = 0;
        int produced = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(f), Charset.forName("UTF-8"))) {
            ZipEntry e;
            int count = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (count++ >= MAX_ENTRIES) break;
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase();
                if (name.endsWith("/") || lower.contains("__macosx") || lower.endsWith(".ds_store")) continue;
                if (!looksLikeLog(name) && !depth2(e)) continue;
                long size = e.getSize() > 0 ? e.getSize() : 4_000_000;
                if (size > MAX_ENTRY_BYTES) continue;
                total += size;
                if (total > MAX_TOTAL) break;
                Entry entry = readStream(name, zin, size, depth);
                if (entry != null) {
                    produced++;
                    sink.onEntry(entry);
                }
                // 嵌套 zip：解一层
                if (entry != null && entry.head.length > 2 && entry.head[0] == 'P' && entry.head[1] == 'K') {
                    // 重新读完整内容以支持嵌套 —— 简化：跳过嵌套（FCL/启动器导出通常不嵌套）
                }
            }
        }
        if (produced == 0) throw new IOException("压缩包中没有可分析的日志文件");
    }

    private static boolean depth2(ZipEntry e) { return true; }

    private static boolean looksLikeLog(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".png") || n.endsWith(".jpg")
                || n.endsWith(".json") || n.endsWith(".db") || n.endsWith(".index")) return false;
        return true; // 宽容：.log/.txt/无后缀都收，识别器会兜底为 UNKNOWN
    }

    private static Entry readStream(String name, InputStream in, long sizeHint, int depth) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int r, total = 0;
        while ((r = in.read(tmp)) > 0 && total < MAX_ENTRY_BYTES) {
            buf.write(tmp, 0, r);
            total += r;
        }
        byte[] all = buf.toByteArray();
        byte[] head = new byte[Math.min(512, all.length)];
        System.arraycopy(all, 0, head, 0, head.length);

        // 二进制魔数探测
        String magicNote = magicOf(head);
        if (magicNote != null) {
            return new Entry(name, head, new ArrayList<>(), all.length, true, magicNote);
        }

        // 编码探测：BOM → UTF-8 严格 → GB18030 兜底
        Charset cs = detectCharset(all);
        String text = new String(all, cs);
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\r?\n")) {
            lines.add(l);
            if (lines.size() >= 60_000) break;
        }
        return new Entry(name, head, lines, all.length, false, cs == StandardCharsets.UTF_8 ? "UTF-8" : "GBK/GB18030");
    }

    private static String magicOf(byte[] h) {
        if (h.length >= 8 && h[0] == 'E' && h[1] == 'l' && h[2] == 'f' && h[3] == 'F'
                && h[4] == 'i' && h[5] == 'l' && h[6] == 'e' && h[7] == 0)
            return "Windows 事件日志二进制（.evtx），本工具已识别；如需深度解析请在事件查看器中导出为 TXT/XML";
        if (h.length >= 8 && h[0] == 'P' && h[1] == 'A' && h[2] == 'G' && h[3] == 'E'
                && (h[4] == 'D' || h[4] == 'E'))
            return "Windows 蓝屏 minidump（.dmp），需 WinDbg 深度解析；可用 BlueScreenView 快速查看停止码";
        return null;
    }

    private static Charset detectCharset(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF)
            return StandardCharsets.UTF_8;
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            d.decode(java.nio.ByteBuffer.wrap(data));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException ex) {
            return legacyChineseCharset();
        }
    }

    /** ROM 的字符集注册表不一定给 GB18030，逐级退到 GBK/GB2312，最后至少别把中文日志读崩。 */
    private static Charset legacyChineseCharset() {
        for (String name : new String[]{"GB18030", "GBK", "GB2312"}) {
            try {
                return Charset.forName(name);
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return StandardCharsets.ISO_8859_1;
    }
}
