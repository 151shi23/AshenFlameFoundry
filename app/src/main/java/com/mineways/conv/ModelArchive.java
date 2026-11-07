package com.mineways.conv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模型压缩包（.zip）的最小处理：认出 zip、解到目录、挑一个 Assimp 能读的模型。
 *
 * <p>为什么要有它：格式转换页原来把 .zip 直接丢给 Assimp，Assimp 只会回
 * 「No suitable reader found」。模型包通常是一堆贴图 + 一个 obj/glb/blend，
 * 正确做法是先解开再拿那个模型去转。
 *
 * <p>纯 Java，电脑上可以直接跑断言；解压带 zip-slip 防护和 512MB / 5000 条上限。
 */
public final class ModelArchive {

    /**
     * 可转换的模型扩展名；越靠前越优先（一个包里多个模型时挑最像"主模型"的）。
     * pmx 排 glb 后：包内已经有转换好的 glb 就优先用它，否则再走 PMX 专用通道。
     */
    private static final String[] EXT = {"glb", "gltf", "pmx", "blend", "obj", "fbx", "dae", "stl",
            "ply", "3ds", "objnomtl", "assbin", "assxml", "md2", "md3", "x", "smd", "lwo", "off",
            "ac", "ase", "x3d", "wrl"};

    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;

    private ModelArchive() {
    }

    public static boolean isZip(File f, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) return true;
        if (f == null || !f.isFile()) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只扫中央目录、不解压：给选择页显示"包里的模型是哪个"。没有返回 null。 */
    public static String pickName(File zip) {
        try (ZipFile zf = new ZipFile(zip)) {
            final Enumeration<? extends ZipEntry> en = zf.entries();
            String best = null;
            int bestRank = Integer.MAX_VALUE;
            long bestSize = -1;
            while (en.hasMoreElements()) {
                final ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                final String name = e.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/")) continue;
                final int rank = rankOf(name);
                if (rank < 0) continue;
                if (rank < bestRank || (rank == bestRank && e.getSize() > bestSize)) {
                    bestRank = rank;
                    bestSize = e.getSize();
                    best = name;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解到 dir（整个包都解，模型旁边的 .mtl / 贴图要跟着），返回挑中的模型。
     *
     * @throws Exception 结构不对 / 没有可读模型 / 超限
     */
    public static File unpack(File zip, File dir) throws Exception {
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("建不了解压目录");
        final String root = dir.getCanonicalPath();
        long total = 0;
        int count = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            final Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                final ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                final String name = e.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue;
                if (++count > MAX_ENTRIES) throw new Exception("压缩包条目太多（超过 " + MAX_ENTRIES + " 个）");
                total += Math.max(0, e.getSize());
                if (total > MAX_TOTAL) throw new Exception("解压后超过 512MB，已拒绝");
                final File target = new File(dir, name).getCanonicalFile();
                if (!target.getPath().startsWith(root + File.separator)) {
                    throw new Exception("压缩包里有非法路径：" + e.getName());
                }
                final File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new Exception("建不了目录：" + parent);
                }
                try (InputStream is = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(target)) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                }
            }
        }
        final File best = pick(dir);
        if (best == null) {
            throw new Exception("压缩包里没有可转换的模型（支持 pmx / obj / glb / gltf / fbx / dae / blend / stl / ply…；"
                    + "只有贴图或 JSON 的包先自己解开再选模型）");
        }
        return best;
    }

    /** 在已解开的目录里挑模型（优先级 + 体积最大）。 */
    public static File pick(File dir) {
        final List<File> files = new ArrayList<>();
        collectFiles(dir, files);
        File best = null;
        int bestRank = Integer.MAX_VALUE;
        long bestSize = -1;
        for (File f : files) {
            final int rank = rankOf(f.getName());
            if (rank < 0) continue;
            if (rank < bestRank || (rank == bestRank && f.length() > bestSize)) {
                best = f;
                bestRank = rank;
                bestSize = f.length();
            }
        }
        return best;
    }

    private static int rankOf(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        final int dot = n.lastIndexOf('.');
        if (dot < 0) return -1;
        final String ext = n.substring(dot + 1);
        for (int i = 0; i < EXT.length; i++) {
            if (EXT[i].equals(ext)) return i;
        }
        return -1;
    }

    private static void collectFiles(File dir, List<File> out) {
        final File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectFiles(k, out);
            else out.add(k);
        }
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        final File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
