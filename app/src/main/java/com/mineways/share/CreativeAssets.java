package com.mineways.share;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 创作资产目录：用户自己产出的「内容」——词库之外的音源脚本、剪辑字体、上传素材、AI 记忆等。
 *
 * <p>这些不是开关式的设置，而是文件，所以单独走一条通道：导出时按用户勾选逐项打包进分享码，
 * 导入时写回应用私有目录并保留 {@code .sharebak} 备份，撤销时还原。
 *
 * <p>分享码是文本，容量有限，因此单项原始体积超过 {@link #MAX_RAW} 的条目会标记为不可分享，
 * 界面直接禁用，避免生成一个必然失败的码。
 */
public final class CreativeAssets {

    /** 单项可放进分享码的原始体积上限（字节）。再大压缩后也会超出码长上限。 */
    public static final int MAX_RAW = 40000;

    /** 一项可分享的创作资产。 */
    public static final class Item {
        public final String id;
        public final String title;
        public final String hint;
        public final String tool;
        public final int files;
        public final long bytes;

        Item(String id, String title, String hint, String tool, int files, long bytes) {
            this.id = id;
            this.title = title;
            this.hint = hint;
            this.tool = tool;
            this.files = files;
            this.bytes = bytes;
        }

        public boolean shareable() {
            return files > 0 && bytes > 0 && bytes <= MAX_RAW;
        }

        public String sizeText() {
            if (files <= 0) return "本机还没有";
            if (bytes < 1024) return files + " 个文件 · " + bytes + " B";
            return files + " 个文件 · " + (bytes / 1024) + " KB";
        }
    }

    /** {id, 标题, 说明, 所属工具}。id 前缀决定扫描方式。 */
    private static final String[][] DEFS = {
            {"dir:music-sources", "音乐音源脚本", "导入的音源 .js、索引与清单", "音乐播放器"},
            {"file:ai/mem.json", "AI 记忆", "最近产物路径与常用参数（不含聊天内容）", "AI 动画助手"},
            {"dir:res-fonts", "剪辑字体", "OpenCut 导入的字体文件", "视频剪辑"},
            {"dir:uploads", "上传素材", "OpenCut / AI 上传的图片与资源", "视频剪辑"},
    };

    private CreativeAssets() {
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File root = c.getFilesDir();
        for (String[] d : DEFS) {
            File f = target(root, d[0]);
            int[] n = new int[1];
            long[] size = new long[1];
            if (f != null) walk(f, n, size, 0);
            out.add(new Item(d[0], d[1], d[2], d[3], n[0], size[0]));
        }
        return out;
    }

    public static String title(String id) {
        for (String[] d : DEFS) if (d[0].equals(id)) return d[1];
        return id == null ? "" : id;
    }

    public static String tool(String id) {
        for (String[] d : DEFS) if (d[0].equals(id)) return d[3];
        return "";
    }

    /** 打包一项资产成 {"files":{"相对路径":"base64"}}。 */
    public static String read(Context c, String id) throws Exception {
        File root = c.getFilesDir();
        File f = target(root, id);
        if (f == null || !f.exists()) return null;
        JSONObject files = new JSONObject();
        int[] total = new int[1];
        if (f.isFile()) {
            put(root, f, files, total);
        } else {
            walkAndPut(root, f, files, total, 0);
        }
        if (files.length() == 0) return null;
        JSONObject body = new JSONObject();
        body.put("files", files);
        return body.toString();
    }

    /**
     * 写回一项资产。{@code overwrite=false} 时只补齐缺失文件。
     * {@code written} 记录每份文件的来源状态，供撤销使用。
     */
    public static int apply(Context c, String body, boolean overwrite, JSONArray written) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject files = root.optJSONObject("files");
        if (files == null) return 0;
        File base = c.getFilesDir();
        String basePath = base.getCanonicalPath();
        int n = 0;
        for (java.util.Iterator<String> it = files.keys(); it.hasNext(); ) {
            String rel = it.next();
            if (rel == null || rel.isEmpty() || rel.contains("..")) continue;
            File out = new File(base, rel);
            String p = out.getCanonicalPath();
            if (!p.startsWith(basePath + File.separator)) continue;
            boolean existed = out.isFile();
            if (existed && !overwrite) continue;
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
            if (existed) {
                File bak = new File(out.getPath() + ".sharebak");
                if (!bak.exists()) copy(out, bak);
            }
            byte[] data = Base64.decode(files.optString(rel, ""), Base64.DEFAULT);
            FileOutputStream fo = new FileOutputStream(out);
            try {
                fo.write(data);
            } finally {
                fo.close();
            }
            JSONObject rec = new JSONObject();
            rec.put("p", rel);
            rec.put("b", existed ? 1 : 0);
            written.put(rec);
            n++;
        }
        return n;
    }

    /** 撤销一次资产写入：备份存在就还原，原本不存在的就删掉。 */
    public static void restore(Context c, JSONArray written) {
        if (written == null) return;
        File base = c.getFilesDir();
        for (int i = 0; i < written.length(); i++) {
            JSONObject rec = written.optJSONObject(i);
            if (rec == null) continue;
            String rel = rec.optString("p", "");
            if (rel.isEmpty() || rel.contains("..")) continue;
            File out = new File(base, rel);
            File bak = new File(out.getPath() + ".sharebak");
            try {
                if (rec.optInt("b", 0) == 1 && bak.isFile()) {
                    copy(bak, out);
                } else if (rec.optInt("b", 0) == 0) {
                    if (out.isFile()) out.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 扫描

    private static File target(File root, String id) {
        if (id == null || !id.contains(":")) return null;
        String rel = id.substring(id.indexOf(':') + 1);
        if (rel.isEmpty() || rel.contains("..")) return null;
        return new File(root, rel);
    }

    private static void walk(File f, int[] n, long[] size, int depth) {
        if (f == null || depth > 6) return;
        if (f.isFile()) {
            if (skip(f)) return;
            n[0]++;
            size[0] += f.length();
            return;
        }
        if (!f.isDirectory()) return;
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) walk(k, n, size, depth + 1);
    }

    private static void walkAndPut(File root, File f, JSONObject files, int[] total, int depth) throws Exception {
        if (f == null || depth > 6) return;
        if (f.isFile()) {
            put(root, f, files, total);
            return;
        }
        if (!f.isDirectory()) return;
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) walkAndPut(root, k, files, total, depth + 1);
    }

    private static void put(File root, File f, JSONObject files, int[] total) throws Exception {
        if (skip(f)) return;
        long len = f.length();
        if (len <= 0 || total[0] + len > MAX_RAW) return;
        String rel = relPath(root, f);
        if (rel == null) return;
        files.put(rel, Base64.encodeToString(readAll(f), Base64.NO_WRAP));
        total[0] += (int) len;
    }

    private static boolean skip(File f) {
        String n = f.getName();
        return n.endsWith(".sharebak") || n.endsWith(".tmp") || n.startsWith(".");
    }

    private static String relPath(File base, File f) {
        try {
            String b = base.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (!p.startsWith(b + File.separator)) return null;
            return p.substring(b.length() + 1).replace(File.separatorChar, '/');
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copy(File from, File to) {
        try {
            byte[] data = readAll(from);
            FileOutputStream fo = new FileOutputStream(to);
            try {
                fo.write(data);
            } finally {
                fo.close();
            }
        } catch (Exception ignored) {
        }
    }
}
