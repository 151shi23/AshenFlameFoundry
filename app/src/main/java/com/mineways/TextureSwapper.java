package com.mineways;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 自定义材质导出：用资源包里的贴图覆盖导出目录 {@code tex/} 下的同名贴图。
 *
 * <p>原理：导出时每个方块会写成一张贴图文件，文件名就是<b>原版材质名</b>
 * （{@code stone.png}、{@code grass_top.png}、{@code oak_planks.png}…），
 * OBJ / MTL 只引用文件名。所以把包里同名贴图盖过去，OBJ 立刻变成"你的材质"，
 * 不需要改导出内核。</p>
 *
 * <p>匹配规则（大小写不敏感，找不到就跳过并记进报告）：</p>
 * <ul>
 *   <li>Java 资源包：{@code assets/minecraft/textures/block/<名字>.png}（1.13+）</li>
 *   <li>旧版资源包：{@code assets/minecraft/textures/blocks/<名字>.png}（≤1.12）</li>
 *   <li>基岩版 .mcpack：{@code textures/blocks/<名字>.png}（走兜底规则）</li>
 *   <li>文件名容错：{@code xxx_y.png}（顶/底面变体）与 {@code xxx_<类别>.png} 会回退到 {@code xxx.png}</li>
 * </ul>
 */
public final class TextureSwapper {

    /** 替换结果（用于给用户的报告）。 */
    public static final class Result {
        public int tiles;                 // tex/ 下 PNG 总数
        public int replaced;              // 已替换
        public int missing;               // 包里没有对应贴图
        public String packName = "";
        public String error = "";
        public final List<String> missingSamples = new ArrayList<>();

        public String report() {
            if (error.length() > 0) {
                return "自定义材质失败：" + error;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("用资源包「").append(packName).append("」替换贴图 ")
              .append(replaced).append("/").append(tiles).append(" 张");
            if (missing > 0) {
                sb.append("；包里没有对应贴图 ").append(missing).append(" 张（保持原版）");
                if (!missingSamples.isEmpty()) {
                    sb.append("，例如：");
                    for (int i = 0; i < missingSamples.size(); i++) {
                        if (i > 0) {
                            sb.append('、');
                        }
                        sb.append(missingSamples.get(i));
                    }
                }
            }
            return sb.toString();
        }
    }

    private TextureSwapper() {
    }

    /**
     * 把资源包（zip 文件或已解开的目录）应用到贴图目录。
     *
     * @param texDir 导出产生的贴图目录（默认 {@code <导出目录>/tex}）
     * @param pack   资源包：.zip / .mcpack 文件，或资源包目录
     */
    public static Result apply(File texDir, File pack) {
        Result r = new Result();
        if (pack == null || !pack.exists()) {
            r.error = "资源包不存在：" + (pack == null ? "?" : pack.getAbsolutePath());
            return r;
        }
        r.packName = pack.getName();

        File[] tiles = texDir == null ? null : texDir.listFiles();
        if (tiles == null) {
            r.error = "找不到贴图目录：" + texDir;
            return r;
        }

        ZipFile zf = null;
        try {
            Map<String, Object[]> index = new HashMap<>();   // 基础名(小写) → {优先度, 引用}
            if (pack.isDirectory()) {
                indexDirectory(pack, "", index);
            } else {
                zf = new ZipFile(pack);
                indexZip(zf, index);
            }

            for (File f : tiles) {
                String low = f.getName().toLowerCase(Locale.US);
                if (!low.endsWith(".png")) {
                    continue;
                }
                r.tiles++;
                String src = lookup(index, low);
                if (src == null) {
                    r.missing++;
                    if (r.missingSamples.size() < 8) {
                        r.missingSamples.add(f.getName());
                    }
                    continue;
                }
                if (copyInto(zf, src, f)) {
                    r.replaced++;
                } else {
                    r.missing++;
                }
            }
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    /**
     * 把资源包里的贴图解出来（扁平化到 outDir 根：{@code <基础名>.png}）。
     *
     * <p>给「合成自定义图集」用：内核按贴图名直接找 {@code <dir>/<name>.png}，
     * 所以这里不保留 block/ 之类的子目录层次。</p>
     *
     * @return 解出的 PNG 张数（失败返回 0）
     */
    public static int unzipTextures(File pack, File outDir) {
        if (pack == null || !pack.isFile() || outDir == null) {
            return 0;
        }
        int n = 0;
        ZipFile zf = null;
        try {
            if (!outDir.exists() && !outDir.mkdirs()) {
                return 0;
            }
            zf = new ZipFile(pack);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String path = e.getName().replace('\\', '/');
                String low = path.toLowerCase(Locale.US);
                if (!low.endsWith(".png") || !low.contains("/textures/")) {
                    continue;
                }
                String file = low.substring(low.lastIndexOf('/') + 1);
                File target = new File(outDir, file);
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    in = zf.getInputStream(e);
                    out = new FileOutputStream(target);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                    out.flush();
                    n++;
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 索引

    private static void indexZip(ZipFile zf, Map<String, Object[]> index) {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String path = e.getName().replace('\\', '/');
            String low = path.toLowerCase(Locale.US);
            if (!low.endsWith(".png") || !low.contains("/textures/")) {
                continue;
            }
            add(index, low, path, prio(low));
        }
    }

    private static void indexDirectory(File dir, String prefix, Map<String, Object[]> index) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            String rel = prefix.length() == 0 ? k.getName() : (prefix + "/" + k.getName());
            if (k.isDirectory()) {
                indexDirectory(k, rel, index);
            } else {
                String low = rel.toLowerCase(Locale.US);
                if (!low.endsWith(".png") || !low.contains("/textures/")) {
                    continue;
                }
                add(index, low, k.getAbsolutePath(), prio(low));
            }
        }
    }

    /** 优先度：block/（1.13+）> blocks/（旧版）> 其它。 */
    private static int prio(String lowerPath) {
        if (lowerPath.contains("/textures/block/")) {
            return 0;
        }
        if (lowerPath.contains("/textures/blocks/")) {
            return 1;
        }
        return 2;
    }

    private static void add(Map<String, Object[]> index, String lowerPath, String ref, int prio) {
        int slash = lowerPath.lastIndexOf('/');
        if (slash < 0) {
            return;
        }
        String file = lowerPath.substring(slash + 1);
        String base = file.substring(0, file.length() - 4);   // 去掉 .png
        Object[] old = index.get(base);
        if (old == null || ((Integer) old[0]) > prio) {
            index.put(base, new Object[]{prio, ref});
        }
    }

    /** 依次尝试：原名 → 去掉 _y（顶/底面变体）→ 逐级去掉尾部 _xxx（类别后缀）。 */
    private static String lookup(Map<String, Object[]> index, String lowName) {
        String base = lowName.substring(0, lowName.length() - 4);
        List<String> keys = new ArrayList<>();
        keys.add(base);
        if (base.endsWith("_y")) {
            keys.add(base.substring(0, base.length() - 2));
        }
        // 逐级剥后缀：stone_top_x → stone_top → stone
        for (String k0 : new ArrayList<>(keys)) {
            String k = k0;
            for (int i = 0; i < 2; i++) {
                int us = k.lastIndexOf('_');
                if (us <= 0) {
                    break;
                }
                k = k.substring(0, us);
                keys.add(k);
            }
        }
        for (String k : keys) {
            Object[] hit = index.get(k);
            if (hit != null) {
                return (String) hit[1];
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 落盘

    private static boolean copyInto(ZipFile zf, String ref, File target) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (zf != null) {
                ZipEntry e = zf.getEntry(ref);
                if (e == null) {
                    return false;
                }
                in = zf.getInputStream(e);
            } else {
                in = new java.io.FileInputStream(ref);
            }
            out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
