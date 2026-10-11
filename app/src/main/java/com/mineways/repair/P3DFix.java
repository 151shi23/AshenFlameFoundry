package com.mineways.repair;

import com.mineways.p3d.Mp;
import com.mineways.p3d.P3DConvert;
import com.mineways.p3d.P3DPack;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3D（Prisma3D）工程包修复 —— <b>容器层 + 数据层（补全式，绝不删数据）</b>。
 *
 * <p><b>容器层</b>：补 +0 快照副本、补 screenshot.png、重建 ZIP（CRC / 本地头 / 中央目录重生成），
 * 3.0 保留 60 4B 伪装头；另出「标准 ZIP 头」版本给解压工具。原本不压缩的条目重打包时保持不压缩。
 *
 * <p><b>数据层</b>：工程里每个根对象都该有一份 <code>content/&lt;uuid&gt;.pobject</code>（对象内容文件）。
 * 有些"打开是空的 / 报错"的包正缺这个 —— 但对象记录本身还留在 <code>project.proj</code> 里（相机、光源这类
 * 没有几何的对象尤其常见）。这时按真实外壳格式把内容文件<b>合成</b>出来：
 *
 * <pre>
 *   [ 20, &lt;int64 时间戳&gt;, &lt;29 个 int = uuid 的 ASCII[3:32]&gt;, [1](&lt;对象记录&gt;), [] ]
 * </pre>
 *
 * <p>只补不删：真的一点记录都没有的对象才在报告里点名，交给用户去找备份。
 * {@link #diagnoseData} 只做体检、不改动任何字节，供"先检测再决定要不要修"用。
 */
public final class P3DFix {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern UUID_ARR = Pattern.compile("\"([0-9a-fA-F]{32})\"");

    public static final class Result {
        public P3DPack.Pack pack;
        public byte[] asIs;
        public byte[] plainZip;
        public byte[] asV200;
        public String convertReport;
        public final List<String> notes = new ArrayList<>();
        /** 合成出来的对象内容文件（给人看的说明）。 */
        public final List<String> synthesized = new ArrayList<>();
        /** 真没法恢复的（报告里点名）。 */
        public final List<String> unrecoverable = new ArrayList<>();

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append(P3DPack.describe(pack));
            for (String n : notes) {
                sb.append('\n').append("· ").append(n);
            }
            if (!synthesized.isEmpty()) {
                sb.append("\n✦ 补全对象内容文件 ").append(synthesized.size()).append(" 个：");
                for (String s : synthesized) {
                    sb.append("\n    ").append(s);
                }
            }
            if (!unrecoverable.isEmpty()) {
                sb.append("\n⚠ 无法恢复 ").append(unrecoverable.size()).append(" 处：");
                for (String s : unrecoverable) {
                    sb.append("\n    ").append(s);
                }
            }
            if (convertReport != null) {
                sb.append("\n\n— 转 2.0 报告 —\n").append(convertReport);
            }
            return sb.toString();
        }
    }

    /** 数据层体检结论（只读，不改动任何东西）。 */
    public static final class Dx {
        public boolean damaged;
        /** 发现的问题。 */
        public final List<String> findings = new ArrayList<>();
        /** 连记录都没有、救不回来的。 */
        public final List<String> hard = new ArrayList<>();
        /** 检查通过的项目。 */
        public final List<String> checks = new ArrayList<>();
        /** 可做可不做的优化（不算损坏）。 */
        public final List<String> optional = new ArrayList<>();
    }

    private P3DFix() {
    }

    public static Result repair(byte[] raw, boolean alsoV200) throws Exception {
        return repair(raw, alsoV200, false);
    }

    /**
     * @param force 用户对「未损坏」文件选择的强制修复：只重建容器 / 补全缺失结构，
     *              不删除任何条目、不改写内容字节、不改 .meta 声明值。
     */
    public static Result repair(byte[] raw, boolean alsoV200, boolean force) throws Exception {
        final Result r = new Result();
        r.pack = P3DPack.open(raw);
        if (r.pack.version != P3DPack.V_300 && r.pack.version != P3DPack.V_200) {
            // 普通 ZIP / APK 之类：交给通用 ZIP 修复，不要当成 P3D 工程包
            throw new IllegalStateException("不是 Prisma3D 工程包（没有 .meta / .proj，或版本认不出）");
        }

        // ---------------- 1) 数据层：补全缺失的对象内容文件（只补不删）
        int synthesized = 0;
        if (r.pack.version == P3DPack.V_300) {
            synthesized = completeMissingContent(r);
        } else {
            r.notes.add("2.0 单体工程：对象与网格都在同一个 .proj 里，无需补内容文件");
        }

        // ---------------- 2) 补 +0 快照副本（放在补全之后，新合成的也会带上）
        int plus0 = 0;
        for (P3DPack.Entry e : new ArrayList<>(r.pack.entries)) {
            final String n = e.name;
            if (n.contains("+0.") || n.endsWith(".meta") || n.contains("/res/")) {
                continue;
            }
            String cand = null;
            if (n.endsWith("project.proj")) {
                cand = n.substring(0, n.length() - "project.proj".length()) + "project+0.proj";
            } else if (n.endsWith(".pobject") || n.endsWith(".pclip")) {
                final int dot = n.lastIndexOf('.');
                cand = n.substring(0, dot) + "+0" + n.substring(dot);
            }
            if (cand != null && r.pack.find(cand) == null) {
                r.pack.entries.add(new P3DPack.Entry(cand, e.data));
                plus0++;
            }
        }
        r.notes.add(plus0 > 0 ? "补齐 " + plus0 + " 个 +0 快照副本" : "+0 快照副本已经齐全");

        // ---------------- 3) 补 screenshot.png
        boolean shotAdded = false;
        final String shot = (r.pack.projectDir.isEmpty() ? "" : r.pack.projectDir + "/") + "screenshot.png";
        if (r.pack.find(shot) == null) {
            P3DPack.Entry best = null;
            for (P3DPack.Entry e : r.pack.entries) {
                if (e.name.contains("/res/") && e.name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    if (best == null || e.data.length > best.data.length) {
                        best = e;
                    }
                }
            }
            if (best != null) {
                r.pack.entries.add(new P3DPack.Entry(shot, best.data));
                shotAdded = true;
                r.notes.add("补了缩略图 screenshot.png（取自 " + base(best.name) + "）");
            } else {
                r.notes.add("包内没有 PNG 贴图，缩略图没得补（不影响打开）");
            }
        } else {
            r.notes.add("缩略图已存在");
        }

        // ---------------- 4) 一致性核对（只报告，不改动声明值）
        final Dx dx = diagnoseData(r.pack);
        for (String c : dx.checks) {
            r.notes.add(c);
        }
        for (String f : dx.findings) {
            r.notes.add("⚠ " + f);
        }
        r.unrecoverable.addAll(dx.hard);

        // ---------------- 5) 重建容器；一处都没改动时不重建（原始字节副本 = 最大保留）
        final boolean eocdOk = ZipFix.lastIndexOf(raw, new byte[]{0x50, 0x4B, 0x05, 0x06}) >= 0;
        if (eocdOk && synthesized == 0 && plus0 == 0 && !shotAdded) {
            r.asIs = raw.clone();
            r.notes.add("容器与数据都没问题、没有任何需要改动的地方 → 产物就是原始字节的副本（逐字节一致，零重压缩）");
        } else {
            r.asIs = P3DPack.zip(r.pack, r.pack.version == P3DPack.V_300);
        }
        r.plainZip = P3DPack.zip(r.pack, false);

        // ---------------- 6) 可选转 2.0（用补全后的包转，才带得上相机/光源）
        if (alsoV200 && r.pack.version == P3DPack.V_300) {
            try {
                final P3DConvert.Result cr = P3DConvert.toV200(r.asIs);
                r.asV200 = cr.zip;
                r.convertReport = cr.report();
            } catch (Throwable t) {
                r.notes.add("转 2.0 失败：" + t.getMessage());
            }
        } else if (alsoV200) {
            r.notes.add("这本来就是 2.0 包，无需转换（2.0 在 2.0/3.0 里都能打开）");
        }

        // ---------------- 7) 强制修复：把「最大保留」说清楚
        if (force) {
            int stored = 0;
            for (P3DPack.Entry e : r.pack.entries) {
                if (e.stored) {
                    stored++;
                }
            }
            r.notes.add("强制修复（最大数据保留）：条目一个没删（共 " + r.pack.entries.size()
                    + " 个），内容字节未被改写"
                    + (stored > 0 ? "；其中 " + stored + " 个原本不压缩的条目保持不压缩存储" : "")
                    + "，.meta 声明值原样保留");
        }
        return r;
    }

    // ------------------------------------------------------------------ 体检（只读）
    /** 数据层体检：内容文件齐不齐、.meta 清单对不对得上、体积声明像不像保存中断。 */
    public static Dx diagnoseData(P3DPack.Pack p) {
        final Dx dx = new Dx();
        final P3DPack.Entry meta = p.find(p.projectDir.isEmpty() ? ".meta" : p.projectDir + "/.meta");
        final String json = meta == null ? null : new String(meta.data, UTF8);

        if (p.version == P3DPack.V_300) {
            final P3DPack.Entry proj = p.find(p.projectDir.isEmpty()
                    ? "project.proj" : p.projectDir + "/project.proj");
            List<Object> roots = null;
            if (proj != null) {
                try {
                    final List<Object> vals = new Mp.Reader(proj.data, 0).readAll();
                    if (vals.size() > 11 && vals.get(11) instanceof List) {
                        @SuppressWarnings("unchecked")
                        final List<Object> rr = (List<Object>) vals.get(11);
                        roots = rr;
                    }
                } catch (Throwable ignored) {
                }
            }
            final Set<String> haveObj = new HashSet<>();
            final Set<String> haveClip = new HashSet<>();
            for (P3DPack.Entry e : p.entries) {
                if (e.name.endsWith(".pobject")) {
                    haveObj.add(uuidOf(e.name));
                } else if (e.name.endsWith(".pclip")) {
                    haveClip.add(uuidOf(e.name));
                }
            }

            if (proj == null) {
                dx.damaged = true;
                dx.findings.add("包里没有 project.proj —— 工程主体缺失");
                dx.hard.add("project.proj 缺失");
            } else if (roots == null) {
                dx.damaged = true;
                dx.findings.add("project.proj 解析不了（结构损坏）");
                dx.hard.add("project.proj 结构损坏");
            } else {
                int miss = 0;
                final StringBuilder names = new StringBuilder();
                for (Object o : roots) {
                    final String uid = firstUuid(o);
                    if (uid == null || haveObj.contains(uid)) {
                        continue;
                    }
                    miss++;
                    if (miss <= 6) {
                        names.append(names.length() == 0 ? "" : "、").append(titleOf(o));
                    }
                }
                if (miss > 0) {
                    dx.damaged = true;
                    dx.findings.add(miss + " 个对象缺内容文件（" + names + (miss > 6 ? " 等" : "")
                            + "）—— 这类缺失会让工程「打开是空的」，可按 project.proj 里的对象记录补全");
                } else {
                    dx.checks.add("根对象 " + roots.size() + " 个，内容文件齐全");
                }
            }

            if (json == null) {
                dx.damaged = true;
                dx.findings.add("包里没有 .meta（工程清单缺失）");
                dx.hard.add(".meta 缺失");
            } else {
                // 对象：内容文件或 project.proj 里的记录，有其一就还有救
                final Set<String> knownObj = new HashSet<>(haveObj);
                if (roots != null) {
                    for (Object o : roots) {
                        final String uid = firstUuid(o);
                        if (uid != null) {
                            knownObj.add(uid);
                        }
                    }
                }
                final int m1 = countMissing(json, "usedRootContents", knownObj);
                final int m2 = countMissing(json, "usedClips", haveClip);
                if (m1 > 0) {
                    dx.damaged = true;
                    dx.findings.add(".meta 清单里还有 " + m1 + " 个根对象，包里既没有内容文件也没有对象记录（救不回来）");
                    dx.hard.add(m1 + " 个根对象（.meta 列了，包内既无内容文件也无记录）");
                } else {
                    dx.checks.add(".meta 的 usedRootContents 与包内对象/记录一致");
                }
                if (m2 > 0) {
                    dx.damaged = true;
                    dx.findings.add(".meta 清单里还有 " + m2 + " 条动画轨，包里没有对应的 .pclip（动画数据无从恢复）");
                    dx.hard.add(m2 + " 条动画轨（.meta 列了但包里没有 .pclip）");
                } else {
                    dx.checks.add(".meta 的 usedClips 与包内动画轨一致");
                }
                final long declared = readNumber(json, "projectSizeInBytes");
                final long actual = payloadSize(p);
                if (declared > 0 && actual > 0 && declared > actual * 2L) {
                    dx.damaged = true;
                    dx.findings.add(".meta 声明工程体积 " + declared + " 字节，实际内容约 " + actual
                            + " 字节 —— 很像「保存中断」留下的：可能还有别的东西没写进去");
                } else if (declared > 0) {
                    dx.checks.add("工程体积声明与实际相符（约 " + actual + " 字节）");
                }
            }
        } else if (p.version == P3DPack.V_200) {
            P3DPack.Entry proj = null;
            for (P3DPack.Entry e : p.entries) {
                if (e.name.endsWith(".proj")) {
                    proj = e;
                    break;
                }
            }
            if (proj == null) {
                dx.damaged = true;
                dx.findings.add("包里没有 .proj 工程主体");
                dx.hard.add(".proj 缺失");
            } else {
                try {
                    new Mp.Reader(proj.data, 0).readAll();
                    dx.checks.add("2.0 工程主体 .proj 可解析（" + proj.data.length + " 字节）");
                } catch (Throwable t) {
                    dx.damaged = true;
                    dx.findings.add(".proj 解析不了（结构损坏）：" + t.getMessage());
                    dx.hard.add(".proj 结构损坏");
                }
            }
            dx.optional.add("可以转成 3.0 结构或反之（修复时按开关生成）");
        } else {
            dx.damaged = true;
            dx.findings.add("认不出 Prisma3D 版本（既不是 2.0 也不是 3.0 结构）");
        }
        return dx;
    }

    // ------------------------------------------------------------------ 数据层：补全内容文件
    /** @return 合成出来的内容文件个数（0 表示一处没改） */
    private static int completeMissingContent(Result r) {
        P3DPack.Entry proj = r.pack.find(r.pack.projectDir + "/project.proj");
        if (proj == null) {
            proj = r.pack.find("project.proj");
        }
        if (proj == null) {
            r.notes.add("包里没有 project.proj，无法核对对象");
            return 0;
        }
        final List<Object> roots;
        try {
            final List<Object> vals = new Mp.Reader(proj.data, 0).readAll();
            if (vals.size() <= 11 || !(vals.get(11) instanceof List)) {
                r.notes.add("project.proj 结构异常（第 12 项不是根对象列表），跳过补全");
                return 0;
            }
            @SuppressWarnings("unchecked")
            final List<Object> rr = (List<Object>) vals.get(11);
            roots = rr;
        } catch (Throwable t) {
            r.notes.add("project.proj 解析失败（跳过补全）：" + t.getMessage());
            return 0;
        }

        final Set<String> have = new HashSet<>();
        for (P3DPack.Entry e : r.pack.entries) {
            if (e.name.endsWith(".pobject")) {
                have.add(uuidOf(e.name));
            }
        }
        long stamp = -9071775938627227084L;
        for (P3DPack.Entry e : r.pack.entries) {
            if (e.name.endsWith(".pobject") && !e.name.contains("+0.")) {
                try {
                    final List<Object> v = new Mp.Reader(e.data, 0).readAll();
                    if (v.size() > 1 && v.get(1) instanceof Number) {
                        stamp = ((Number) v.get(1)).longValue();
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        int done = 0;
        for (Object o : roots) {
            if (!(o instanceof List)) {
                continue;
            }
            final String uid = firstUuid(o);
            if (uid == null || have.contains(uid)) {
                continue;
            }
            try {
                final List<Object> payload = new ArrayList<>();
                payload.add(20L);
                payload.add(stamp);
                final String u = uid.toLowerCase(Locale.ROOT);
                for (int i = 3; i < u.length() && i < 32; i++) {
                    payload.add((long) (int) u.charAt(i));
                }
                payload.add(Arrays.asList(1L, o));
                payload.add(new ArrayList<Object>());
                final byte[] content = Mp.writeAll(payload);

                final String dir = r.pack.projectDir.isEmpty() ? "" : r.pack.projectDir + "/";
                final String name = dir + "content/" + uid + ".pobject";
                if (r.pack.find(name) == null) {
                    r.pack.entries.add(new P3DPack.Entry(name, content));
                    done++;
                    r.synthesized.add("「" + titleOf(o) + "」" + uid + "  → 合成 " + content.length + " 字节");
                }
            } catch (Throwable t) {
                r.unrecoverable.add("「" + titleOf(o) + "」" + uid + "（合成失败：" + t.getMessage() + "）");
            }
        }
        if (done > 0) {
            r.notes.add("按 project.proj 里的对象记录补全了 " + done + " 个对象内容文件（没有删任何东西）");
        } else {
            r.notes.add("所有根对象都已有内容文件，无需补全");
        }
        return done;
    }

    // ------------------------------------------------------------------ 小工具
    private static int countMissing(String json, String key, Set<String> keep) {
        final Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) {
            return 0;
        }
        int miss = 0;
        final Matcher q = UUID_ARR.matcher(m.group(1));
        while (q.find()) {
            if (!keep.contains(q.group(1).toLowerCase(Locale.ROOT))) {
                miss++;
            }
        }
        return miss;
    }

    private static long readNumber(String json, String key) {
        final Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private static long payloadSize(P3DPack.Pack p) {
        long sum = 0;
        for (P3DPack.Entry e : p.entries) {
            if (!e.name.contains("+0.") && !e.name.endsWith("screenshot.png")) {
                sum += e.data.length;
            }
        }
        return sum;
    }

    private static String uuidOf(String path) {
        final String file = base(path);
        final int dot = file.lastIndexOf('.');
        final String stem = (dot > 0 ? file.substring(0, dot) : file).replace("+0", "");
        return stem.toLowerCase(Locale.ROOT);
    }

    private static String base(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String firstUuid(Object o) {
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof String && ((String) x).matches("[0-9a-fA-F]{32}")) {
                    return ((String) x).toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static String titleOf(Object o) {
        if (o instanceof List) {
            final List<?> l = (List<?>) o;
            for (int i = 1; i < Math.min(l.size(), 4); i++) {
                if (l.get(i) instanceof String && !((String) l.get(i)).matches("[0-9a-fA-F]{32}")) {
                    return (String) l.get(i);
                }
            }
        }
        return "?";
    }
}
