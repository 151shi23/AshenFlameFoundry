package com.mineways.p3d;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Prisma3D 工程包的容器层：识别版本、修头、解包、重打包、抽取信息。
 *
 * <p>实测结论（用 2.0 / 3.0 样本解出来的）：
 * <ul>
 *   <li>2.0：标准 ZIP（本地头 50 4B），内部 {@code <工程名>/<工程名>.proj} + {@code res/} + 截图；</li>
 *   <li>3.0：本地头被改成 60 4B（改回 50 就能解），内部 {@code <uuid>/.meta} +
 *       {@code project.proj} + {@code content/*.pobject} + {@code clip/*.pclip} + {@code res/}，
 *       且每份数据都有 {@code X} 与 {@code X+0} 两个副本。</li>
 * </ul>
 */
public final class P3DPack {

    public static final int V_UNKNOWN = 0;
    public static final int V_200 = 2;
    public static final int V_300 = 3;

    /** 3.0 把本地文件头的 'P' 改成了 0x60，用来防止普通解压工具直接打开。 */
    public static final byte FAKE_ZIP_HEAD = 0x60;
    public static final byte REAL_ZIP_HEAD = 0x50;

    public static final class Entry {
        public final String name;
        public final byte[] data;
        /** 原条目是「不压缩存储」（method=0）时，重打包保持不压缩 —— 内容逐字节保留，不再压一遍。 */
        public boolean stored;

        /** 公开构造：修复器（com.mineways.repair）要往包里补条目。 */
        public Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public static final class Pack {
        public int version = V_UNKNOWN;
        /** 原文件头是不是被改过（3.0 的 60 4B）。 */
        public boolean fakeHeader;
        public final List<Entry> entries = new ArrayList<>();
        /** 工程目录名：2.0 是工程名，3.0 是 32 位 UUID。 */
        public String projectDir = "";
        public String projectName = "";
        public int objectCount;
        public int materialCount;
        public int meshCount;
        public int clipCount;
        public int textureCount;

        public Entry find(String name) {
            for (Entry e : entries) {
                if (e.name.equals(name)) {
                    return e;
                }
            }
            return null;
        }

        public List<Entry> matching(String suffix) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries) {
                if (e.name.endsWith(suffix)) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    private P3DPack() {
    }

    // ------------------------------------------------------------------ 打开

    public static Pack open(byte[] raw) throws IOException {
        if (raw == null || raw.length < 4) {
            throw new IOException("文件太小，不是 P3D 工程包");
        }
        Pack p = new Pack();
        byte[] data = raw;
        if ((raw[0] & 0xFF) == FAKE_ZIP_HEAD && (raw[1] & 0xFF) == 0x4B) {
            p.fakeHeader = true;
            data = raw.clone();
            data[0] = REAL_ZIP_HEAD;
        } else if ((raw[0] & 0xFF) != REAL_ZIP_HEAD || (raw[1] & 0xFF) != 0x4B) {
            throw new IOException("文件头不是 ZIP（0x" + Integer.toHexString(raw[0] & 0xFF)
                    + "），不是 P3D 工程包");
        }
        unzipInto(data, p);
        classify(p);
        return p;
    }

    /** 解包，并记录每个条目原本是否「不压缩存储」（修复时保持原样，内容逐字节不变）。 */
    private static void unzipInto(byte[] data, Pack p) throws IOException {
        final ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            final byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                final Entry en = new Entry(e.getName(), bos.toByteArray());
                en.stored = e.getMethod() == ZipEntry.STORED;
                p.entries.add(en);
            }
        } finally {
            zin.close();
        }
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                out.put(e.getName(), bos.toByteArray());
            }
        } finally {
            zin.close();
        }
        return out;
    }

    /** 判定版本并数一数里面的东西。 */
    private static void classify(Pack p) {
        boolean hasMeta = false;
        boolean hasProjectProj = false;
        boolean hasPObject = false;
        String legacyProj = null;
        for (Entry e : p.entries) {
            String n = e.name;
            if (n.endsWith("/.meta") || n.equals(".meta")) {
                hasMeta = true;
                p.projectDir = dirOf(n);
                p.projectName = readJsonString(e.data, "projectName");
            } else if (n.endsWith("/project.proj") || n.equals("project.proj")) {
                hasProjectProj = true;
                if (p.projectDir.length() == 0) {
                    p.projectDir = dirOf(n);
                }
            } else if (n.endsWith(".pobject")) {
                hasPObject = true;
                if (!n.endsWith("+0.pobject")) {
                    p.objectCount++;
                }
            } else if (n.endsWith(".pclip")) {
                if (!n.endsWith("+0.pclip")) {
                    p.clipCount++;
                }
            } else if (n.endsWith(".proj")) {
                legacyProj = n;
            } else if (n.contains("/res/") || n.startsWith("res/")) {
                p.textureCount++;
            }
        }
        if (hasPObject || hasMeta || hasProjectProj) {
            p.version = V_300;
        } else if (legacyProj != null) {
            p.version = V_200;
            p.projectDir = dirOf(legacyProj);
            String file = legacyProj.substring(legacyProj.lastIndexOf('/') + 1);
            p.projectName = file.endsWith(".proj")
                    ? file.substring(0, file.length() - ".proj".length()) : file;
        }
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    /** 从 .meta 里抠一个字符串字段（不引 org.json，够用就行）。 */
    private static String readJsonString(byte[] data, String key) {
        String s = new String(data, java.nio.charset.Charset.forName("UTF-8"));
        String pat = "\"" + key + "\"";
        int i = s.indexOf(pat);
        if (i < 0) {
            return "";
        }
        int colon = s.indexOf(':', i + pat.length());
        if (colon < 0) {
            return "";
        }
        int q1 = s.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        int q2 = q1 + 1;
        StringBuilder sb = new StringBuilder();
        while (q2 < s.length() && s.charAt(q2) != '"') {
            char c = s.charAt(q2);
            if (c == '\\' && q2 + 1 < s.length()) {
                sb.append(s.charAt(q2 + 1));
                q2 += 2;
                continue;
            }
            sb.append(c);
            q2++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 打包

    /**
     * 按原结构重新打包。
     *
     * @param fakeHeader3 3.0 的包要写回 60 4B 的伪装头（否则 3.0 可能不认）
     */
    public static byte[] zip(Pack p, boolean fakeHeader3) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            for (Entry e : p.entries) {
                ZipEntry ze = new ZipEntry(e.name);
                ze.setTime(0L);     // 固定时间戳，保证同样的输入出同样的输出
                if (e.stored && e.data != null) {
                    // 原本不压缩的（已是压缩格式的大资源）继续不压缩：内容逐字节保留
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
        byte[] out = bos.toByteArray();
        if (fakeHeader3 && out.length > 0) {
            out[0] = FAKE_ZIP_HEAD;
        }
        return out;
    }

    // ------------------------------------------------------------------ 信息

    public static String describe(Pack p) {
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(p.version == V_300 ? "Prisma3D 3.0（模块化）"
                : p.version == V_200 ? "Prisma3D 2.0（单体）" : "未知");
        if (p.fakeHeader) {
            sb.append(" · 头部是 60 4B 伪装（可修复）");
        }
        sb.append('\n').append("工程名：").append(p.projectName.isEmpty() ? "（未读到）" : p.projectName);
        sb.append('\n').append("目录：").append(p.projectDir.isEmpty() ? "—" : p.projectDir);
        sb.append('\n').append("对象 ").append(p.objectCount)
                .append(" · 动画轨 ").append(p.clipCount)
                .append(" · 贴图 ").append(p.textureCount)
                .append(" · 条目 ").append(p.entries.size());
        return sb.toString();
    }
}
