package com.mineways.repair;

import com.mineways.p3d.P3DPack;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * 文件修复总入口：<b>先体检 → 报告坏没坏 → （没坏时由用户决定）强制修复</b>。
 *
 * <p>覆盖五类：Prisma3D 工程包（2.0 / 3.0）、通用 ZIP、图片、音频、BB（Blockbench）导出物。
 * 认不出来也不抛异常，返回一条说明让人把样本发过来加进识别表。
 *
 * <p>强制修复 = <b>最大数据保留</b>：
 * <ul>
 *   <li>容器类（ZIP / P3D）：一个条目都不删，只重建索引；内容字节逐字节保留；
 *       原本不压缩存储的条目继续保持不压缩；.meta 等声明值原样保留；</li>
 *   <li>非容器类（图片 / 音频 / JSON / OBJ / MTL）：体检认为内容完整时，产物就是原始字节的副本
 *       （最多只规范扩展名），不做任何重写。</li>
 * </ul>
 */
public final class Repair {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    public static final class Out {
        public final String name;
        public final byte[] data;

        public Out(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public static final class Outcome {
        public String kind = "未知类型";
        public String detail = "";
        public boolean ok;
        public final List<String> notes = new ArrayList<>();
        public final List<Out> outputs = new ArrayList<>();

        public String report() {
            final StringBuilder sb = new StringBuilder();
            sb.append("识别为：").append(kind).append('\n');
            if (detail != null && detail.length() > 0) {
                sb.append(detail).append('\n');
            }
            for (String n : notes) {
                sb.append("· ").append(n).append('\n');
            }
            if (!outputs.isEmpty()) {
                sb.append("\n产物 ").append(outputs.size()).append(" 个：\n");
                for (Out o : outputs) {
                    sb.append("  ").append(o.name).append("（").append(o.data.length).append(" 字节）\n");
                }
            }
            return sb.toString();
        }
    }

    /** 体检结论（只读，不动文件）。 */
    public static final class Health {
        public String kind = "未知类型";
        public String detail = "";
        public boolean damaged;
        /** 损坏点。 */
        public final List<String> findings = new ArrayList<>();
        /** 检查通过项。 */
        public final List<String> checks = new ArrayList<>();
        /** 可选优化（不算损坏）。 */
        public final List<String> optional = new ArrayList<>();

        public String summary() {
            final StringBuilder sb = new StringBuilder();
            sb.append("识别为：").append(kind).append('\n');
            if (detail != null && detail.length() > 0) {
                sb.append(detail).append('\n');
            }
            sb.append("检测结论：").append(damaged
                    ? "发现 " + findings.size() + " 处损坏" : "本文件未损坏").append('\n');
            for (String c : checks) {
                sb.append("  ✓ ").append(c).append('\n');
            }
            for (String f : findings) {
                sb.append("  ✗ ").append(f).append('\n');
            }
            for (String o : optional) {
                sb.append("  · 可选：").append(o).append('\n');
            }
            return sb.toString();
        }
    }

    private Repair() {
    }

    public static String stripExt(String name) {
        final int i = name == null ? -1 : name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : (name == null ? "file" : name);
    }

    public static String extOf(String name) {
        final int i = name == null ? -1 : name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    // ================================================================== 体检

    /** 只体检、不改动：告诉调用方这个文件到底坏没坏。 */
    public static Health diagnose(byte[] d, String fileName) {
        final Health h = new Health();
        if (d == null || d.length == 0) {
            h.kind = "空文件";
            h.damaged = true;
            h.findings.add("文件是空的（0 字节）");
            return h;
        }
        final String ext = extOf(fileName);
        try {
            final boolean zipish = ZipFix.indexOf(d, 0, new byte[]{0x50, 0x4B, 0x03, 0x04}) >= 0
                    || ZipFix.indexOf(d, 0, new byte[]{0x60, 0x4B, 0x03, 0x04}) >= 0;
            if (zipish) {
                if (d.length > 96L * 1024 * 1024) {
                    // 超大包（APK 之类）：只做轻量结构检查，避免把整包解进内存
                    h.kind = "超大压缩包（" + (d.length / (1024 * 1024)) + " MB）";
                    if (ZipFix.lastIndexOf(d, new byte[]{0x50, 0x4B, 0x05, 0x06}) < 0) {
                        h.damaged = true;
                        h.findings.add("EOCD 缺失（打包尾部被截断）");
                    } else {
                        h.checks.add("EOCD 存在");
                    }
                    h.checks.add("文件超过 96 MB：只做轻量结构检查（省内存），未做逐条目 CRC 全量校验");
                    h.optional.add("这类超大包建议只做容器层重建；要逐条目校验请用更小的文件");
                    return h;
                }
                final ZipFix.Report zr = ZipFix.scan(d);
                try {
                    final P3DPack.Pack p = P3DPack.open(d);
                    if (p.version != P3DPack.V_300 && p.version != P3DPack.V_200) {
                        throw new IllegalStateException("不是 P3D 工程包");
                    }
                    h.kind = "Prisma3D 工程包（"
                            + (p.version == P3DPack.V_300 ? "3.0 模块化" : "2.0 单体") + "）";
                    h.detail = P3DPack.describe(p);
                    containerCheck(zr, h, true);
                    final P3DFix.Dx dx = P3DFix.diagnoseData(p);
                    if (dx.damaged) {
                        h.damaged = true;
                        h.findings.addAll(dx.findings);
                    }
                    h.checks.addAll(dx.checks);
                    h.optional.addAll(dx.optional);
                    return h;
                } catch (Throwable notP3d) {
                    // 通用 ZIP
                }
                h.kind = "ZIP 压缩包";
                h.detail = zr.describe();
                containerCheck(zr, h, false);
                return h;
            }

            final String img = ImageFix.magic(d);
            if (!"未知".equals(img) && !"WAV".equals(img)) {
                checkImage(d, img, h);
                final String real = extOf("x." + img.toLowerCase(Locale.ROOT));
                if (!ext.isEmpty() && !real.equals(ext) && !img.toLowerCase(Locale.ROOT).equals(ext)) {
                    h.optional.add("扩展名 ." + ext + " 与真实格式 " + img + " 不符（修复会顺带纠正）");
                }
                return h;
            }
            if ("WAV".equals(img)) {
                checkWav(d, h);
                if (!ext.isEmpty() && !"wav".equals(ext)) {
                    h.optional.add("扩展名 ." + ext + " 与真实格式 WAV 不符");
                }
                return h;
            }
            final String aud = AudioFix.magic(d);
            if (!"未知".equals(aud)) {
                checkAudio(d, aud, h);
                final String real = aud.toLowerCase(Locale.ROOT);
                if (!ext.isEmpty() && !real.equals(ext)) {
                    h.optional.add("扩展名 ." + ext + " 与真实格式 " + aud + " 不符");
                }
                return h;
            }
            if (BbFix.looksLikeJson(d) || "bbmodel".equals(ext) || "json".equals(ext)) {
                checkJson(d, ext, h);
                return h;
            }
            if ("obj".equals(ext) || "mtl".equals(ext)) {
                checkObjMtl(d, ext, h);
                return h;
            }
            h.kind = "未知类型";
            h.optional.add("认不出类型（不是 ZIP / P3D 工程包、图片、音频、JSON / OBJ / MTL）——"
                    + "强制修复也只会给你一份原样副本");
            return h;
        } catch (Throwable t) {
            h.damaged = true;
            h.findings.add("体检过程异常：" + t);
            return h;
        }
    }

    /** 容器层检查（ZIP / P3D 共用）。 */
    private static void containerCheck(ZipFix.Report zr, Health h, boolean p3d) {
        if (!zr.hadEocd) {
            h.damaged = true;
            h.findings.add("EOCD 缺失（压缩包尾部被截断 —— 下载/拷贝中断的典型）");
        } else {
            h.checks.add("EOCD 完好");
        }
        if (zr.rebuiltByScan) {
            h.damaged = true;
            h.findings.add("中央目录不可用，只能靠本地头扫描（结构已损坏）");
        } else {
            h.checks.add("中央目录完好（" + zr.entries.size() + " 个条目）");
        }
        int bad = 0;
        for (ZipFix.Entry e : zr.entries) {
            if (e.crcWasBad) {
                bad++;
            }
        }
        if (bad > 0) {
            h.damaged = true;
            h.findings.add(bad + " 个条目的 CRC 不符（内容与记录对不上）");
        } else if (!zr.entries.isEmpty()) {
            h.checks.add("全部条目 CRC 校验通过");
        }
        if (zr.fakeHeader) {
            if (p3d) {
                h.checks.add("头部是 60 4B 伪装（Prisma3D 3.0 的正常特征，不算损坏）");
            } else {
                h.damaged = true;
                h.findings.add("首字节被改成 0x60（ZIP 的 'P' 被改过，标准解压工具打不开）");
            }
        }
        for (String n : zr.notes) {
            if (n.contains("断裂") || n.contains("越界") || n.contains("失败")
                    || n.contains("跳过") || n.contains("缺失")) {
                h.damaged = true;
                h.findings.add(n);
            } else {
                h.checks.add(n);     // “检测到 0x60 伪装头”这类说明不算损坏
            }
        }
    }

    private static void checkImage(byte[] d, String img, Health h) {
        h.kind = "图片（" + img + "）";
        if ("PNG".equals(img)) {
            final byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            if (!ImageFix.startsWith(d, sig)) {
                h.damaged = true;
                h.findings.add("PNG 签名不完整（文件头被改动）");
            } else {
                int p = 8;
                int badCrc = 0;
                int chunks = 0;
                boolean iend = false;
                int endAt = -1;
                while (p + 12 <= d.length) {
                    final int len = (int) ImageFix.be32(d, p);
                    if (len < 0 || p + 12 + len > d.length) {
                        break;
                    }
                    final String type = new String(d, p + 4, 4, ASCII);
                    final CRC32 c = new CRC32();
                    c.update(d, p + 4, 4 + len);
                    if (c.getValue() != ImageFix.be32(d, p + 8 + len)) {
                        badCrc++;
                    }
                    chunks++;
                    p += 12 + len;
                    if ("IEND".equals(type)) {
                        iend = true;
                        endAt = p;
                        break;
                    }
                }
                if (chunks == 0) {
                    h.damaged = true;
                    h.findings.add("PNG 数据块一个都读不出来（结构损坏）");
                }
                if (badCrc > 0) {
                    h.damaged = true;
                    h.findings.add(badCrc + " 个数据块 CRC 不符（图像数据可能被改坏）");
                } else if (chunks > 0) {
                    h.checks.add(chunks + " 个数据块 CRC 全部通过");
                }
                if (!iend) {
                    h.damaged = true;
                    h.findings.add("缺 IEND 结束块（文件被截断）");
                } else {
                    h.checks.add("IEND 结束块完好");
                    if (endAt > 0 && endAt < d.length) {
                        h.optional.add("结尾有 " + (d.length - endAt) + " 字节多余数据（修复会剥掉）");
                    }
                }
            }
            return;
        }
        if ("JPEG".equals(img)) {
            h.checks.add("JPEG 起始标记 FFD8 正常");
            int eoi = -1;
            for (int i = d.length - 2; i >= Math.max(0, d.length - 4096); i--) {
                if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) {
                    eoi = i + 2;
                    break;
                }
            }
            if (eoi < 0) {
                h.damaged = true;
                h.findings.add("找不到 EOI 结束标记（文件被截断）");
            } else if (eoi < d.length) {
                h.checks.add("EOI 结束标记完好");
                h.optional.add("结尾有 " + (d.length - eoi) + " 字节多余数据（修复会剥掉）");
            } else {
                h.checks.add("EOI 结束标记完好（文件尾部干净）");
            }
            return;
        }
        h.checks.add("魔数识别为 " + img);
        h.optional.add("这类格式只做魔数与扩展名核对（不做重编码）");
    }

    private static void checkWav(byte[] d, Health h) {
        h.kind = "音频（WAV）";
        final boolean riff = d.length >= 12 && new String(d, 0, 4, ASCII).equals("RIFF")
                && new String(d, 8, 4, ASCII).equals("WAVE");
        if (!riff) {
            h.damaged = true;
            h.findings.add("RIFF/WAVE 头不完整");
            return;
        }
        h.checks.add("RIFF/WAVE 头正常");
        final long declared = ImageFix.le32(d, 4) + 8L;
        if (declared != d.length) {
            h.damaged = true;
            h.findings.add("长度字段声明 " + declared + " 字节，实际 " + d.length
                    + " 字节（播放器可能中途截断）");
        } else {
            h.checks.add("长度字段与实际一致（" + d.length + " 字节）");
        }
    }

    private static void checkAudio(byte[] d, String fmt, Health h) {
        h.kind = "音频（" + fmt + "）";
        if ("MP3".equals(fmt)) {
            final boolean id3 = d.length > 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3';
            final boolean sync = d.length > 1 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0;
            if (id3 || sync) {
                h.checks.add(id3 ? "ID3 标签头正常" : "MPEG 帧同步头正常");
            } else {
                h.damaged = true;
                h.findings.add("既没有 ID3 标签也没有有效的 MPEG 帧头");
            }
            return;
        }
        h.checks.add("魔数识别为 " + fmt);
    }

    private static void checkJson(byte[] d, String ext, Health h) {
        h.kind = "bbmodel".equals(ext) ? "BB 模型（JSON）" : "JSON 文本";
        final String s = new String(d, Charset.forName("UTF-8"));
        final String t = s.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            h.damaged = true;
            h.findings.add("JSON 不是以 { 或 [ 开头（被截断或不是 JSON）");
            return;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    h.damaged = true;
                    h.findings.add("括号闭合顺序错乱（第 " + i + " 字符处多了一个闭合符）");
                    return;
                }
            }
        }
        if (inStr) {
            h.damaged = true;
            h.findings.add("字符串引号没有闭合（文件被截断）");
        } else if (depth != 0) {
            h.damaged = true;
            h.findings.add("还差 " + depth + " 个闭合括号（文件被截断）");
        } else {
            h.checks.add("结构完整（括号与引号全部闭合，" + t.length() + " 字符）");
        }
    }

    private static void checkObjMtl(byte[] d, String ext, Health h) {
        final String s = new String(d, Charset.forName("UTF-8"));
        if ("obj".equals(ext)) {
            h.kind = "Blockbench / Wavefront OBJ";
            final boolean verts = s.contains("\nv ") || s.startsWith("v ");
            final boolean faces = s.contains("\nf ") || s.startsWith("f ");
            final boolean mtllib = s.contains("mtllib ");
            if (!verts) {
                h.damaged = true;
                h.findings.add("没有顶点行（v）—— 不是有效的 OBJ");
            } else {
                h.checks.add("顶点行正常");
            }
            if (!faces) {
                h.damaged = true;
                h.findings.add("没有面行（f）—— 模型为空");
            } else {
                h.checks.add("面行正常");
            }
            if (!mtllib) {
                h.optional.add("没有 mtllib（材质库）声明，强制修复会补上");
            } else {
                h.checks.add("mtllib 声明存在");
            }
            return;
        }
        h.kind = "Wavefront MTL";
        if (!s.contains("newmtl ")) {
            h.damaged = true;
            h.findings.add("没有 newmtl 段（不是有效的材质库）");
        } else {
            h.checks.add("newmtl 段存在");
        }
        if (!s.contains("Kd ")) {
            h.optional.add("材质缺 Kd（漫反射色），强制修复会补上");
        }
    }

    // ================================================================== 修复

    public static Outcome run(byte[] d, String fileName, boolean p3dToV200) {
        return run(d, fileName, p3dToV200, false);
    }

    /**
     * @param force 用户对「未损坏」文件选择的强制修复（最大数据保留；完成后再人工确认一遍）：
     *              容器类只重建索引、内容字节逐字节保留；非容器类内容完整时直接给原始字节副本。
     */
    public static Outcome run(byte[] d, String fileName, boolean p3dToV200, boolean force) {
        final Outcome o = new Outcome();
        if (d == null || d.length == 0) {
            o.notes.add("文件是空的，没得修");
            return o;
        }
        final String base = stripExt(fileName);
        final String ext = extOf(fileName);
        try {
            // ---------------------------------------------------------- 1) ZIP / P3D
            final boolean zipish = ZipFix.indexOf(d, 0, new byte[]{0x50, 0x4B, 0x03, 0x04}) >= 0
                    || ZipFix.indexOf(d, 0, new byte[]{0x60, 0x4B, 0x03, 0x04}) >= 0;
            if (zipish) {
                boolean p3dHandled = false;
                try {
                    final P3DFix.Result pr = P3DFix.repair(d, p3dToV200, force);
                    p3dHandled = true;
                    o.kind = "Prisma3D 工程包（"
                            + (pr.pack.version == P3DPack.V_300 ? "3.0 模块化" : "2.0 单体") + "）";
                    o.detail = P3DPack.describe(pr.pack);
                    o.notes.addAll(pr.notes);
                    o.outputs.add(new Out(base + "_修复.prisma", pr.asIs));
                    o.outputs.add(new Out(base + "_修复_标准ZIP头.zip", pr.plainZip));
                    if (pr.asV200 != null) {
                        o.outputs.add(new Out(base + "_修复_转2.0.prisma", pr.asV200));
                        o.notes.add("另外生成了一份 2.0 单体工程（2.0 / 3.0 的 P3D 都能打开）");
                    }
                    o.ok = true;
                } catch (Throwable notP3d) {
                    // 不是 P3D 工程包 → 走通用 ZIP 修复
                }
                if (p3dHandled) {
                    return o;
                }
                final ZipFix.Report zr = ZipFix.scan(d);
                if (!zr.entries.isEmpty()) {
                    o.kind = "ZIP 压缩包（通用修复）";
                    o.detail = zr.describe();
                    o.notes.addAll(zr.notes);
                    int fixed = 0;
                    int stored = 0;
                    for (ZipFix.Entry e : zr.entries) {
                        if (e.crcWasBad) {
                            fixed++;
                        }
                        if (e.method == 0) {
                            stored++;
                        }
                    }
                    if (fixed > 0) {
                        o.notes.add("重算了 " + fixed + " 个条目的 CRC");
                    }
                    if (force) {
                        o.notes.add("强制修复（最大数据保留）：条目一个没删（共 " + zr.entries.size()
                                + " 个），内容字节未被改写"
                                + (stored > 0 ? "；其中 " + stored + " 个原本不压缩的条目保持不压缩存储" : ""));
                    }
                    o.outputs.add(new Out(base + "_修复.zip", ZipFix.rebuild(zr)));
                    o.ok = true;
                    return o;
                }
                o.notes.add("像是 ZIP，但一个条目都没扫出来（可能已被整体截断）");
                return o;
            }

            // ---------------------------------------------------------- 2) 图片
            final String img = ImageFix.magic(d);
            if (!"未知".equals(img) && !"WAV".equals(img)) {
                final boolean keep = force && !diagnose(d, fileName).damaged;
                if (keep) {
                    o.kind = "图片（" + img + "）";
                    o.notes.add("强制修复（最大数据保留）：体检未发现损坏，产物是原始字节的副本（仅规范扩展名）");
                    o.outputs.add(new Out(base + "_修复." + img.toLowerCase(Locale.ROOT), d));
                    o.ok = true;
                    return o;
                }
                final ImageFix.Result ir = ImageFix.fix(d);
                o.kind = "图片（" + ir.realFormat + "）";
                o.notes.addAll(ir.notes);
                if (!ir.ext.isEmpty()) {
                    o.outputs.add(new Out(base + "_修复." + ir.ext, ir.data));
                    o.ok = true;
                    if (!ext.isEmpty() && !ir.ext.equals(ext)) {
                        o.notes.add("原扩展名 ." + ext + " 与真实格式不符，产物已用 ." + ir.ext);
                    }
                }
                return o;
            }

            // ---------------------------------------------------------- 3) 音频
            final String aud = !"未知".equals(ImageFix.magic(d)) ? "WAV" : AudioFix.magic(d);
            if (!"未知".equals(aud)) {
                final boolean keep = force && !diagnose(d, fileName).damaged;
                if (keep) {
                    o.kind = "音频（" + aud + "）";
                    o.notes.add("强制修复（最大数据保留）：体检未发现损坏，产物是原始字节的副本（仅规范扩展名）");
                    o.outputs.add(new Out(base + "_修复." + aud.toLowerCase(Locale.ROOT), d));
                    o.ok = true;
                    return o;
                }
                final AudioFix.Result ar = AudioFix.fix(d);
                o.kind = "音频（" + ar.realFormat + "）";
                o.notes.addAll(ar.notes);
                if (!ar.ext.isEmpty()) {
                    o.outputs.add(new Out(base + "_修复." + ar.ext, ar.data));
                    o.ok = true;
                    if (!ext.isEmpty() && !ar.ext.equals(ext)) {
                        o.notes.add("原扩展名 ." + ext + " 与真实格式不符，产物已用 ." + ar.ext);
                    }
                }
                return o;
            }

            // ---------------------------------------------------------- 4) BB / JSON / OBJ / MTL
            if ("obj".equals(ext) || "mtl".equals(ext)
                    || BbFix.looksLikeJson(d) || "bbmodel".equals(ext) || "json".equals(ext)) {
                final boolean keep = force && !diagnose(d, fileName).damaged;
                if (keep) {
                    o.kind = "obj".equals(ext) ? "Blockbench / Wavefront OBJ"
                            : "mtl".equals(ext) ? "Wavefront MTL"
                            : ("bbmodel".equals(ext) ? "BB 模型（JSON）" : "JSON 文本");
                    o.notes.add("强制修复（最大数据保留）：体检未发现损坏，产物是原始字节的副本（未做任何改写）");
                    o.outputs.add(new Out(base + "_修复." + (ext.isEmpty() ? "json" : ext), d));
                    o.ok = true;
                    return o;
                }
                if ("obj".equals(ext)) {
                    final BbFix.Result br = BbFix.fixObj(d, null);
                    o.kind = br.kind;
                    o.notes.addAll(br.notes);
                    o.outputs.add(new Out(base + "_修复.obj", br.data));
                    o.ok = true;
                    return o;
                }
                if ("mtl".equals(ext)) {
                    final BbFix.Result br = BbFix.fixMtl(d);
                    o.kind = br.kind;
                    o.notes.addAll(br.notes);
                    o.outputs.add(new Out(base + "_修复.mtl", br.data));
                    o.ok = true;
                    return o;
                }
                final BbFix.Result br = BbFix.fixJson(d);
                o.kind = "bbmodel".equals(ext) ? "BB 模型（JSON）" : "JSON 文本";
                o.notes.addAll(br.notes);
                o.outputs.add(new Out(base + "_修复." + ("bbmodel".equals(ext) ? "bbmodel" : "json"), br.data));
                o.ok = true;
                return o;
            }

            // ---------------------------------------------------------- 5) 兜底
            o.notes.add("认不出类型：不是 ZIP / P3D 工程包、图片、音频、JSON / OBJ / MTL。"
                    + "把样本发我可以加进识别表。");
            return o;
        } catch (Throwable t) {
            o.notes.add("修复过程异常（已兜住，不会崩）：" + t);
            return o;
        }
    }
}
