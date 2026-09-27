package com.mineways;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网易版存档「解密 + 导出到下载目录」。
 * <p>
 * 关键约定：<b>绝不改动玩家原存档</b>。整条链路是
 * 「复制到本应用的可写临时区（Android/media/&lt;包名&gt;，shell 也能写）→ 在副本上解密 → 导出到下载目录 → 清理临时区」。
 * <p>
 * 三种来源：
 * <ol>
 *   <li>{@link #export} 且条目可直读：Java 递归复制；</li>
 *   <li>{@link #export} 且条目来自 Shizuku：用 shell {@code cp -r} 复制（shell 能读被隔离的
 *       {@code /Android/data/com.netease.x19}，并写入 {@code Android/media} 这个共享可写区）；</li>
 *   <li>{@link #exportFromTree}：用户用系统文件选择器手动指定的目录（SAF），用 DocumentFile 复制。</li>
 * </ol>
 * 导出落点：Android 10+ 走 MediaStore 写入 {@code 下载/MinewaysMobile/网易存档/<世界名>/…}；
 * 旧系统直接写公共下载目录（失败则退回应用外部目录，并在结果里说明真实路径）。
 */
public final class NeteaseSaveExporter {

    private NeteaseSaveExporter() {
    }

    /** 导出结果。 */
    public static final class Result {
        public final boolean success;
        /** 导出的世界根目录（直读路径；MediaStore 情况下为逻辑路径描述）。 */
        public final String destDir;
        public final int files;
        public final long bytes;
        public final boolean wasEncrypted;
        public final int decryptedFiles;
        public final String message;

        Result(boolean success, String destDir, int files, long bytes,
               boolean wasEncrypted, int decryptedFiles, String message) {
            this.success = success;
            this.destDir = destDir;
            this.files = files;
            this.bytes = bytes;
            this.wasEncrypted = wasEncrypted;
            this.decryptedFiles = decryptedFiles;
            this.message = message;
        }
    }

    public interface Progress {
        /** @param ratio 0~1（未知时给 -1） */
        void onStep(String message, double ratio);
    }

    // ---------------------------------------------------------------- 入口

    /** 导出一个扫描到的世界。 */
    public static Result export(Context ctx, NeteaseWorldScanner.World world, Progress cb) {
        File tmp = null;
        try {
            countAdded = 0;
            tmp = newTempDir(ctx, world.name);
            step(cb, "正在复制存档到临时区…", 0.05);
            if (world.readable) {
                copyDir(new File(world.path), tmp, cb);
            } else {
                if (!ShizukuWorldImporter.isReady()) {
                    return new Result(false, null, 0, 0, false, 0,
                            "该存档目录被系统隔离，需要 Shizuku 才能读取。");
                }
                if (!shellCopy(world.path, tmp)) {
                    return new Result(false, null, 0, 0, false, 0,
                            "Shizuku 复制失败（可改用「手动指定文件夹」）。");
                }
            }
            return finish(ctx, tmp, world.name, world.encrypted, cb);
        } catch (Throwable t) {
            return new Result(false, null, 0, 0, false, 0, String.valueOf(t.getMessage()));
        } finally {
            cleanQuiet(tmp);
        }
    }

    /** 导出用户手动指定的目录（SAF，可读任意用户可见位置）。 */
    public static Result exportFromTree(Context ctx, Uri treeUri, Progress cb) {
        File tmp = null;
        try {
            DocumentFile tree = DocumentFile.fromTreeUri(ctx, treeUri);
            if (tree == null || !tree.isDirectory()) {
                return new Result(false, null, 0, 0, false, 0, "无法读取所选目录。");
            }
            String name = tree.getName() == null ? "netease_world" : tree.getName();
            tmp = newTempDir(ctx, name);
            step(cb, "正在复制所选目录…", 0.05);
            int[] count = {0};
            if (!copyDocumentTree(ctx, tree, tmp, count, cb)) {
                return new Result(false, null, 0, 0, false, 0, "复制所选目录失败。");
            }
            return finish(ctx, tmp, name, false, cb);
        } catch (Throwable t) {
            return new Result(false, null, 0, 0, false, 0, String.valueOf(t.getMessage()));
        } finally {
            cleanQuiet(tmp);
        }
    }

    // ---------------------------------------------------------------- 公共后段

    /** 副本 -> 定位世界根 -> 解密 -> 导出到下载目录。 */
    private static Result finish(Context ctx, File tmp, String name,
                                 boolean encryptedHint, Progress cb) {
        // 1) 定位真正含 level.dat 的那一层（有的存档多套一层）
        File root = locateWorldRoot(tmp);
        if (root == null) {
            return new Result(false, null, 0, 0, false, 0,
                    "没找到 level.dat/db —— 这可能不是 Minecraft 存档目录。");
        }

        // 2) 在副本上解密（原存档不受影响）
        boolean encrypted = encryptedHint;
        int decrypted = 0;
        try {
            NeteaseDecryptor.Result r = NeteaseDecryptor.decrypt(root, null);
            encrypted = r.encrypted;
            decrypted = r.decryptedFiles;
            if (r.encrypted && !r.success) {
                return new Result(false, null, 0, 0, true, 0, "解密失败：" + r.message);
            }
        } catch (Throwable t) {
            return new Result(false, null, 0, 0, encrypted, 0, "解密异常：" + t.getMessage());
        }

        // 3) 导出到下载目录
        step(cb, "正在写入下载目录…", 0.6);
        String rel = "MinewaysMobile/" + "网易存档" + "/" + safeName(name);
        int[] stat = new int[]{0, 0};   // files, dirs
        long bytes;
        String dest;
        if (Build.VERSION.SDK_INT >= 29) {
            bytes = writeViaMediaStore(ctx, root, rel, stat, cb);
            dest = "下载/" + rel;
        } else {
            File out = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), rel);
            bytes = writeToDir(root, out, stat, cb);
            dest = out.getAbsolutePath();
        }

        String msg = "导出完成："
                + (encrypted ? ("网易加密存档已解密（" + decrypted + " 个文件），") : "存档未加密，")
                + "共 " + stat[0] + " 个文件 / " + human(bytes) + "。原存档未被改动。";
        step(cb, msg, 1.0);
        return new Result(true, dest, stat[0], bytes, encrypted, decrypted, msg);
    }

    private static File locateWorldRoot(File dir) {
        if (NeteaseWorldScanner.isWorldDir(dir)) return dir;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = locateWorldRoot(k);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 复制

    private static void copyDir(File src, File dst, Progress cb) throws IOException {
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File out = new File(dst, k.getName());
            if (k.isDirectory()) {
                if (!out.exists() && !out.mkdirs()) throw new IOException("mkdir 失败：" + out);
                copyDir(k, out, cb);
            } else if (k.isFile()) {
                copyFile(k, out);
                if ((countAdded % 64) == 0 && cb != null) {
                    cb.onStep("正在复制：" + k.getName(), -1);
                }
            }
        }
    }

    private static int countAdded = 0;

    private static void copyFile(File src, File dst) throws IOException {
        // 256KB 缓冲：网易存档里 db/ 多为几十 KB~几 MB 的文件，文件数上千时收益明显
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 1 << 18);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 18)) {
            byte[] buf = new byte[1 << 18];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        countAdded++;
    }

    /** SAF 目录递归复制。 */
    private static boolean copyDocumentTree(Context ctx, DocumentFile dir, File dst,
                                            int[] count, Progress cb) {
        DocumentFile[] kids = dir.listFiles();
        for (DocumentFile k : kids) {
            String name = k.getName() == null ? ("file_" + count[0]) : k.getName();
            File out = new File(dst, name);
            if (k.isDirectory()) {
                if (!out.exists() && !out.mkdirs()) continue;
                if (!copyDocumentTree(ctx, k, out, count, cb)) return false;
            } else {
                try (InputStream in = ctx.getContentResolver().openInputStream(k.getUri());
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    if (in == null) continue;
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    count[0]++;
                    if ((count[0] % 64) == 0 && cb != null) {
                        cb.onStep("正在复制：" + name, -1);
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 写盘

    /** MediaStore 逐个文件写入 RELATIVE_PATH（会自动建目录）。返回总字节数。 */
    private static long writeViaMediaStore(Context ctx, File root, String relPath,
                                           int[] stat, Progress cb) {
        return writeViaMediaStoreRec(ctx, root, root, relPath, stat, cb);
    }

    private static long writeViaMediaStoreRec(Context ctx, File root, File dir, String relPath,
                                              int[] stat, Progress cb) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long total = 0;
        for (File f : kids) {
            String sub = relPath + "/" + f.getName();
            if (f.isDirectory()) {
                total += writeViaMediaStoreRec(ctx, root, f, sub, stat, cb);
                continue;
            }
            if (!f.isFile()) continue;
            String mime = guessMime(f.getName());
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/" + relPath);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) continue;
            try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 18);
                 OutputStream os = new BufferedOutputStream(
                         ctx.getContentResolver().openOutputStream(uri), 1 << 18)) {
                if (os == null) continue;
                byte[] buf = new byte[1 << 18];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                total += f.length();
                stat[0]++;
                if ((stat[0] % 32) == 0 && cb != null) {
                    cb.onStep("正在写入下载目录：" + f.getName(), -1);
                }
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    /** 旧系统直接写公共下载目录。 */
    private static long writeToDir(File src, File dst, int[] stat, Progress cb) {
        File[] kids = src.listFiles();
        if (kids == null) return 0;
        long total = 0;
        for (File f : kids) {
            File out = new File(dst, f.getName());
            if (f.isDirectory()) {
                if (!out.exists() && !out.mkdirs()) continue;
                total += writeToDir(f, out, stat, cb);
            } else if (f.isFile()) {
                try {
                    copyFile(f, out);
                    total += f.length();
                    stat[0]++;
                    if ((stat[0] % 32) == 0 && cb != null) {
                        cb.onStep("正在写入下载目录：" + f.getName(), -1);
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 临时工作区：优先 {@code Android/media/<包名>/}（shell 与 App 都能写，
     * 这正是 Shizuku 复制能落地的关键），没有则退回应用外部私有目录。
     */
    private static File newTempDir(Context ctx, String name) throws IOException {
        File base = null;
        try {
            File[] media = ctx.getExternalMediaDirs();
            if (media != null && media.length > 0 && media[0] != null) base = media[0];
        } catch (Throwable ignored) {
        }
        if (base == null) base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(new File(base, "netease_tmp"), safeName(name) + "_" + System.currentTimeMillis());
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建临时目录：" + dir);
        return dir;
    }

    /** 用 shell 复制（shell 能读被隔离目录；目标在 Android/media 下 shell 也能写）。 */
    private static boolean shellCopy(String srcPath, File dst) {
        String src = srcPath.replace("'", "");
        String d = dst.getAbsolutePath().replace("'", "");
        String cmd = "cp -r '" + src + "/.' '" + d + "/' 2>&1; chmod -R u+rwX '" + d + "' 2>/dev/null; "
                + "ls '" + d + "' 2>/dev/null | head -3";
        String out = ShizukuWorldImporter.execShell(new String[]{"/system/bin/sh", "-c", cmd});
        if (out == null) return false;
        File[] kids = dst.listFiles();
        return kids != null && kids.length > 0;
    }

    private static void cleanQuiet(File dir) {
        try {
            if (dir != null) deleteRec(dir);
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String guessMime(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".dat") || n.endsWith(".ldb") || n.endsWith(".log") || n.endsWith(".sst")) {
            return "application/octet-stream";
        }
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static String safeName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.isEmpty() ? "netease_world" : s;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    private static void step(Progress cb, String msg, double ratio) {
        if (cb != null) cb.onStep(msg, ratio);
    }
}
