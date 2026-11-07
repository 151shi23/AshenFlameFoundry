package com.mineways.conv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
    private static final long MAX_TEXTURE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_TEXTURES = 256;

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
            Map<String, byte[]> textures = collectTextures(assetRoot);
            // PMX 在子目录时，贴图引用通常相对 PMX 自己；再收一遍同目录，精确路径覆盖根目录候选。
            File pmxDir = pmx.getParentFile();
            if (pmxDir != null && assetRoot != null
                    && !pmxDir.getAbsoluteFile().equals(assetRoot.getAbsoluteFile())) {
                textures.putAll(collectTextures(pmxDir));
            }
            long t0 = System.currentTimeMillis();
            Pmx2Glb conv = new Pmx2Glb();
            byte[] glb = conv.convert(bytes, textures);
            if (glb == null || glb.length < 20) {
                return new Result(false, "PMX 解析完成但没有生成有效 GLB");
            }
            writeAll(out, glb);
            long ms = System.currentTimeMillis() - t0;
            String msg = conv.summary()
                    + (textures.isEmpty() ? "\n未找到贴图：只输出材质色；要保留贴图请把 PMX 和 tex 目录一起打成 zip 再选。"
                    : "\n贴图目录：" + textures.size() + " 个候选文件")
                    + "\n耗时 " + ms + " ms · 输出 " + human(glb.length);
            return new Result(true, msg);
        } catch (OutOfMemoryError e) {
            return new Result(false, "内存不足：模型/贴图太大，建议先压缩贴图或换小模型");
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(false, "PMX 转换失败：" + (m == null || m.isEmpty() ? t.toString() : m));
        }
    }

    private static boolean looksLikePmx(byte[] b) {
        return b != null && b.length >= 4 + 4 + 8
                && b[0] == 'P' && b[1] == 'M' && b[2] == 'X' && b[3] == ' ';
    }

    /**
     * 递归收集贴图（只读内核能嵌入的 PNG/JPG/BMP）。
     * key 用相对 {@code root} 的正斜杠路径；同名文件也由内核兜底匹配。
     */
    private static Map<String, byte[]> collectTextures(File root) {
        Map<String, byte[]> out = new HashMap<>();
        if (root == null || !root.isDirectory()) return out;
        collect(root, "", out, new long[]{0});
        return out;
    }

    private static void collect(File dir, String prefix, Map<String, byte[]> out, long[] total) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= MAX_TEXTURES || total[0] >= 512L * 1024 * 1024) return;
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out, total);
                continue;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".bmp"))) {
                continue;
            }
            if (f.length() <= 0 || f.length() > MAX_TEXTURE_BYTES) continue;
            try {
                byte[] data = readAll(f);
                // glTF 查看器对 image/bmp 支持很差：BMP 在进 GLB 前转成 PNG（失败就保留原字节）。
                if (name.endsWith(".bmp")) {
                    byte[] png = bmpToPng(data);
                    if (png != null) data = png;
                }
                out.put(prefix + f.getName(), data);
                total[0] += data.length;
            } catch (Throwable ignored) {
            }
        }
    }

    /** BMP → PNG（GLB 查看器兼容）；解码失败返回 null，调用方保留原字节。 */
    private static byte[] bmpToPng(byte[] bmp) {
        try {
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
