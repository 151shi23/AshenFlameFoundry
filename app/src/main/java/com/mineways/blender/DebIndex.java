package com.mineways.blender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Debian 包索引（{@code Packages} 文件）解析 + 依赖闭包求解。
 *
 * <p>不用 apt、不用 chroot：直接读镜像上的 {@code Packages.xz} 拿到每个包的
 * {@code Filename} / {@code SHA256} / {@code Depends}，自己算出需要哪几个 deb，
 * 下载后把 {@code data.tar.*} 解到同一个 sysroot 目录里。这样 sysroot 里就是
 * 一棵纯 glibc 的文件树，交给官方 {@code ld-linux-aarch64.so.1} 加载即可运行。</p>
 */
public final class DebIndex {

    public static final class Pkg {
        public String name = "";
        public String version = "";
        public String filename = "";
        public String sha256 = "";
        public final List<String> depends = new ArrayList<>();
        public String arch = "";

        /** 包在索引里的原名（含 epoch/架构后缀），用于报错定位。 */
        public String display() {
            return name + " " + version;
        }
    }

    private final Map<String, Pkg> byName = new HashMap<>();

    public int size() {
        return byName.size();
    }

    public Pkg get(String name) {
        return byName.get(name);
    }

    public static DebIndex parse(File textFile, Downloader.Cancel cancel) throws IOException {
        final DebIndex idx = new DebIndex();
        try (InputStream fis = new FileInputStream(textFile);
             BufferedReader r = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8), 1 << 16)) {
            Pkg cur = null;
            String field = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
                if (line.isEmpty()) {
                    if (cur != null && !cur.name.isEmpty()) {
                        idx.byName.put(cur.name, cur);
                    }
                    cur = null;
                    field = null;
                    continue;
                }
                if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                    // 续行
                    if (cur == null) continue;
                    append(cur, field, line.trim());
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                field = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (cur == null) cur = new Pkg();
                append(cur, field, value);
            }
            if (cur != null && !cur.name.isEmpty()) {
                idx.byName.put(cur.name, cur);
            }
        }
        return idx;
    }

    private static void append(Pkg p, String field, String value) {
        if (field == null) return;
        switch (field) {
            case "Package":
                p.name = value;
                break;
            case "Version":
                p.version = value;
                break;
            case "Architecture":
                p.arch = value;
                break;
            case "Filename":
                p.filename = value;
                break;
            case "SHA256":
                p.sha256 = value;
                break;
            case "Depends":
            case "Pre-Depends":
                for (String dep : splitDeps(value)) {
                    p.depends.add(dep);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 把依赖串拆成包名列表：{@code "a (>= 1) | b, c:any"} → {@code [a, b, c]}。
     * 每个 OR 组只取第一个（真正严谨的选择交给 apt，我们只需要「能跑起来」的最小集）。
     */
    static List<String> splitDeps(String value) {
        final List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String group : value.split(",")) {
            String g = group.trim();
            if (g.isEmpty()) continue;
            String first = g;
            int bar = g.indexOf('|');
            if (bar >= 0) first = g.substring(0, bar);
            String name = normalize(first);
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /** 去掉版本约束、架构限定、multiarch 后缀。 */
    static String normalize(String dep) {
        String s = dep.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        int br = s.indexOf('[');
        if (br > 0) s = s.substring(0, br);
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        return s.trim();
    }

    /**
     * 依赖闭包（BFS）。
     *
     * @param roots   直接依赖的顶层包
     * @param missing 索引里找不到的包名（多为「虚拟包」，可安全忽略）
     */
    public List<Pkg> closure(Collection<String> roots, List<String> missing) {
        final List<Pkg> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final ArrayDeque<String> queue = new ArrayDeque<>();
        for (String r : roots) {
            if (seen.add(r)) queue.add(r);
        }
        while (!queue.isEmpty()) {
            final String name = queue.poll();
            final Pkg p = byName.get(name);
            if (p == null) {
                if (missing != null && !missing.contains(name)) missing.add(name);
                continue;
            }
            out.add(p);
            for (String d : p.depends) {
                if (seen.add(d)) queue.add(d);
            }
        }
        return out;
    }

    /** 取某个包的绝对下载地址候选（按镜像顺序）。 */
    public static List<String> urls(List<String> mirrors, String suite, String arch, Pkg p) {
        final List<String> out = new ArrayList<>();
        final String rel = p.filename;
        if (rel == null || rel.isEmpty()) return out;
        for (String m : mirrors) {
            String base = m.endsWith("/") ? m.substring(0, m.length() - 1) : m;
            out.add(base + "/" + rel);
        }
        return out;
    }

    /** {@code Packages} 索引文件的候选地址。 */
    public static List<String> indexUrls(List<String> mirrors, String suite, String arch) {
        final List<String> out = new ArrayList<>();
        for (String m : mirrors) {
            String base = m.endsWith("/") ? m.substring(0, m.length() - 1) : m;
            out.add(base + "/dists/" + suite + "/main/binary-" + arch + "/Packages.xz");
        }
        return out;
    }
}
