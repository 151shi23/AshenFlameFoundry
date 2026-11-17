package com.mineways.conv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 格式转换页的 PMX（MikuMikuDance 模型）→ GLB 专用通道。
 *
 * <p>内核来自 pmx2glb v3（纯 Java 8、零依赖）：PMX 2.0 解析 + glTF 2.0 GLB 导出，
 * 支持骨骼/Skin、顶点与 UV morph、材质、贴图原字节嵌入。Assimp 没有 PMX 导入器，
 * 所以这条路不能走 libfmtconv，必须在 Java 层直接完成。
 *
 * <p>贴图：从压缩包解压根目录（或 PMX 同目录）递归收集 PNG/JPG/BMP，key 用相对路径；
 * 内核同时支持精确路径与同名文件兜底，PMX 里写 {@code tex/颜.png} 或 {@code ../tex/颜.png}
 * 都能命中。TGA/SPA/SPH 不嵌入（glTF 不支持），会在结果里列出。
 */
public final class PmxConvert {

    /** 单张贴图上限，防止 zip 炸弹/超大 PSD 混入把内存打满。 */
    private static final long MAX_TEXTURE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_TEXTURES = 128;
    /** BMP 解码峰值更大，超过这个大小就不在低内存设备上转 PNG。 */
    private static final long MAX_BMP_DECODE_BYTES = 8L * 1024 * 1024;

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    private PmxConvert() {
    }

    public static Result convert(File pmx, File out, File assetRoot) {
        if (pmx == null || !pmx.isFile()) {
            return new Result(false, "PMX 文件不存在");
        }
        try {
            byte[] bytes = readAll(pmx);
            if (!looksLikePmx(bytes)) {
                return new Result(false, "不是有效的 PMX 文件（文件头应为 PMX 2.0）");
            }
            Pmx2Glb conv = new Pmx2Glb();
            // 先解析一次，按 PMX 实际引用的贴图去收文件；不再把目录里所有图片都读进内存。
            Pmx2Glb.PmxModel model = conv.prepare(bytes);
            Set<String> wanted = wantedTextures(conv.textureNames(model));
            long budget = textureBudget();
            Map<String, byte[]> textures = collectTextures(assetRoot, wanted, budget);
            // PMX 在子目录时，贴图引用通常相对 PMX 自己；再收一遍同目录，精确路径覆盖根目录候选。
            File pmxDir = pmx.getParentFile();
            if (pmxDir != null && assetRoot != null
                    && !pmxDir.getAbsoluteFile().equals(assetRoot.getAbsoluteFile())) {
                textures.putAll(collectTextures(pmxDir, wanted, budget));
            }
            long t0 = System.currentTimeMillis();
            int textureCount = textures.size();
            byte[] glb = conv.convertPrepared(model, textures);
            // GLB 已写完，贴图源字节不再需要；尽早释放给低内存机。
            textures.clear();
            if (glb == null || glb.length < 20) {
                return new Result(false, "PMX 解析完成但没有生成有效 GLB");
            }
            writeAll(out, glb);
            long ms = System.currentTimeMillis() - t0;
            String msg = conv.summary()
                    + (textureCount == 0 ? "\n未找到贴图：只输出材质色；要保留贴图请把 PMX 和 tex 目录一起打成 zip 再选。"
                    : "\n贴图目录：" + textureCount + " 个候选文件")
                    + "\n耗时 " + ms + " ms · 输出 " + human(glb.length);
            return new Result(true, msg);
        } catch (OutOfMemoryError e) {
            return new Result(false, "内存不足：模型/贴图太大，建议先压缩贴图或换小模型");
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(false, "PMX 转换失败：" + (m == null || m.isEmpty() ? t.toString() : m));
        }
    }

    /**
     * PMX → FBX：先由 PMX 内核还原骨骼/蒙皮并生成 GLB，再交给 Assimp 的 FBX 导出器。
     * 不直接把 PMX 丢给 Assimp（它没有 PMX 导入器），也不自己手写 FBX 的集群/绑定矩阵。
     */
    public static Result convertToFbx(File pmx, File out, File assetRoot) {
        if (pmx == null || !pmx.isFile()) return new Result(false, "PMX 文件不存在");
        File parent = out == null ? null : out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return new Result(false, "建不了输出目录");
        }
        File tmp = new File(parent == null ? new File(".") : parent,
                (out == null ? "pmx" : out.getName()) + ".__pmx.glb");
        try {
            Result glb = convert(pmx, tmp, assetRoot);
            if (!glb.ok) return glb;
            FmtConv.Result r = FmtConv.convertModel(tmp.getAbsolutePath(), out.getAbsolutePath());
            if (!r.ok) return new Result(false, "FBX 导出失败：" + r.message);
            if (out == null || !out.isFile() || out.length() < 64) {
                return new Result(false, "FBX 导出后没有有效产物");
            }
            return new Result(true, "PMX 骨骼/蒙皮已还原 → FBX\n"
                    + r.message + "\n" + glb.message);
        } catch (OutOfMemoryError e) {
            return new Result(false, "内存不足：模型/贴图太大，建议先用 GLB 或压缩贴图");
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(false, "PMX → FBX 失败：" + (m == null || m.isEmpty() ? t.toString() : m));
        } finally {
            // 中间 GLB 只是给 Assimp 的输入，不留在产物目录。
            try { if (tmp.isFile()) tmp.delete(); } catch (Throwable ignored) {
            }
        }
    }

    private static boolean looksLikePmx(byte[] b) {
        return b != null && b.length >= 4 + 4 + 8
                && b[0] == 'P' && b[1] == 'M' && b[2] == 'X' && b[3] == ' ';
    }

    /** PMX 实际引用的贴图名，规范成小写路径 + basename，供遍历时提前过滤。 */
    private static Set<String> wantedTextures(List<String> names) {
        Set<String> out = new HashSet<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) continue;
            String k = normPath(n);
            out.add(k);
            int slash = k.lastIndexOf('/');
            if (slash >= 0) out.add(k.substring(slash + 1));
        }
        return out;
    }

    private static String normPath(String p) {
        String k = p.replace('\\', '/').trim();
        while (k.startsWith("./")) k = k.substring(2);
        while (k.startsWith("../")) k = k.substring(3);
        return k.toLowerCase(Locale.ROOT);
    }

    /**
     * 贴图预算按堆大小走：低内存机只留 24MB，高配最多 96MB。
     * 这个值是「解码后字节」的硬上限，不是文件数量，避免多张 8K 图叠加把机器打挂。
     */
    private static long textureBudget() {
        long max = Runtime.getRuntime().maxMemory();
        return Math.min(96L * 1024 * 1024, Math.max(24L * 1024 * 1024, max / 4));
    }

    /**
     * 递归收集被 PMX 引用的贴图（PNG/JPG/BMP）。
     * 只读真正会被嵌入的文件，不再把目录里所有图片都读进内存。
     */
    private static Map<String, byte[]> collectTextures(File root, Set<String> wanted, long budget) {
        Map<String, byte[]> out = new HashMap<>();
        if (root == null || !root.isDirectory() || wanted == null || wanted.isEmpty()) return out;
        collect(root, "", out, wanted, budget, new long[]{0});
        return out;
    }

    private static void collect(File dir, String prefix, Map<String, byte[]> out,
                                Set<String> wanted, long budget, long[] total) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= MAX_TEXTURES || total[0] >= budget) return;
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out, wanted, budget, total);
                continue;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".bmp"))) {
                continue;
            }
            String rel = prefix + f.getName();
            String key = normPath(rel);
            int slash = key.lastIndexOf('/');
            String base = slash >= 0 ? key.substring(slash + 1) : key;
            if (!wanted.contains(key) && !wanted.contains(base)) continue;
            if (f.length() <= 0 || f.length() > MAX_TEXTURE_BYTES) continue;
            if (total[0] + f.length() > budget) continue;
            try {
                byte[] data = readAll(f);
                // glTF 查看器对 image/bmp 支持很差：BMP 在进 GLB 前转成 PNG（失败就保留原字节）。
                if (name.endsWith(".bmp")) {
                    byte[] png = bmpToPng(data);
                    if (png != null) data = png;
                }
                if (total[0] + data.length > budget) continue;
                out.put(rel, data);
                total[0] += data.length;
            } catch (Throwable ignored) {
            }
        }
    }

    /** BMP → PNG（GLB 查看器兼容）；超大/超预算返回 null，调用方保留原字节。 */
    private static byte[] bmpToPng(byte[] bmp) {
        if (bmp == null || bmp.length > MAX_BMP_DECODE_BYTES) return null;
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bmp, 0, bmp.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
                return null;
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory
                    .decodeByteArray(bmp, 0, bmp.length);
            if (bitmap == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            boolean ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
            bitmap.recycle();
            return ok ? bo.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(
                    (int) Math.min(f.length(), 1 << 20));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("建不了输出目录");
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.flush();
        }
    }

    private static String human(long n) {
        if (n >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024L) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return n + " B";
    }
}
