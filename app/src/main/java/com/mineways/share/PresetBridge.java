package com.mineways.share;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 生产工具预设的读取 / 待应用暂存。
 *
 * <p>两类工具：
 * <ul>
 *   <li><b>网页型</b>（Blockbench、Snowstorm）：设置存在 WebView 的 localStorage，Java 侧读不到，
 *       因此由对应 Activity 在页面加载完成后注入脚本抓取；导入时写入待应用槽，下次打开该工具时
 *       回灌 localStorage 并重载一次页面。</li>
 *   <li><b>原生型</b>（PhotoCraft / PS）：偏好与预设库是应用私有目录里的文件（{@code prefs.json}
 *       与 {@code presets/}），这里直接读文件、编码进分享码；导入时先备份原文件再写回，
 *       在 PhotoCraft 启动前完成，引擎启动时自然读到新值。</li>
 * </ul>
 *
 * <p>待应用槽存在 {@code share_presets} 这个 pref 里，PrefCatalog 已把它整体排除，
 * 避免把未生效的预设再分享出去。
 */
public final class PresetBridge {

    /** 待应用槽所在的 pref（整份不参与分享）。 */
    public static final String PREF = "share_presets";

    public static final String BLOCKBENCH = "blockbench";
    public static final String SNOWSTORM = "snowstorm";
    public static final String PHOTOCRAFT = "photocraft";

    /** 网页型工具单个预设体上限（字符）。 */
    private static final int MAX_WEB_BODY = 60000;
    /** 原生型工具文件总量上限（原始字节）。 */
    private static final int MAX_FILE_TOTAL = 160000;
    /** 原生型工具单文件上限（原始字节）。 */
    private static final int MAX_FILE_ONE = 120000;

    private static final String[] TOOLS = {BLOCKBENCH, SNOWSTORM, PHOTOCRAFT};

    private PresetBridge() {
    }

    public static String[] all() {
        return TOOLS.clone();
    }

    public static boolean isWeb(String id) {
        return BLOCKBENCH.equals(id) || SNOWSTORM.equals(id);
    }

    public static String title(String id) {
        if (BLOCKBENCH.equals(id)) return "Blockbench 建模器";
        if (SNOWSTORM.equals(id)) return "Snowstorm 粒子动画";
        if (PHOTOCRAFT.equals(id)) return "PhotoCraft 修图（PS）";
        return id == null ? "" : id;
    }

    public static String hint(String id) {
        if (BLOCKBENCH.equals(id)) return "建模设置、界面状态、快捷键与插件开关";
        if (SNOWSTORM.equals(id)) return "粒子编辑器界面设置与上次编辑状态";
        if (PHOTOCRAFT.equals(id)) return "偏好设置 + 画笔/渐变/形状/工具预设库";
        return "工具预设";
    }

    // ------------------------------------------------------------ 网页型：脚本

    /**
     * 抓取 localStorage 的脚本。排除缓存/插件源码/最近记录这类体积大且与预设无关的键；
     * 抓完通过 {@code MWPreset.deliver(json)} 回传。总大小超限时提前截断，避免撑爆分享码。
     */
    public static String captureScript() {
        return "(function(){try{"
                + "var o={},b=0,skip=/cache|source|recent|tmp|temp|installed_plugins/i;"
                + "for(var i=0;i<localStorage.length;i++){"
                + "var k=localStorage.key(i);if(!k)continue;if(skip.test(k))continue;"
                + "var v=localStorage.getItem(k);if(v==null)continue;"
                + "if(v.length>" + MAX_WEB_BODY + ")continue;"
                + "b+=k.length+v.length;if(b>" + MAX_WEB_BODY + ")break;"
                + "o[k]=v;}"
                + "var s=JSON.stringify(o);"
                + "if(window.MWPreset)window.MWPreset.deliver(s);"
                + "}catch(e){try{if(window.MWPreset)window.MWPreset.deliver('{}');}catch(x){}}})()";
    }

    /** 把预设写回 localStorage（调用后由原生重载页面生效）。 */
    public static String applyScript(String json) {
        return "(function(){try{"
                + "var o=JSON.parse(" + JSONObject.quote(json == null ? "{}" : json) + ");"
                + "for(var k in o){if(Object.prototype.hasOwnProperty.call(o,k))"
                + "localStorage.setItem(k,o[k]);}"
                + "return 'ok';}catch(e){return 'err';}})()";
    }

    // ------------------------------------------------------------ 待应用槽

    public static void putPending(Context c, String id, String body) {
        if (id == null || body == null) return;
        sp(c).edit().putString("pending_" + id, body).apply();
    }

    public static boolean hasPending(Context c, String id) {
        return getPending(c, id) != null;
    }

    public static String getPending(Context c, String id) {
        String v = sp(c).getString("pending_" + id, null);
        return v == null || v.isEmpty() ? null : v;
    }

    /** 取出并清除：预设只回灌一次。 */
    public static String takePending(Context c, String id) {
        String v = getPending(c, id);
        if (v != null) sp(c).edit().remove("pending_" + id).apply();
        return v;
    }

    public static void clearPending(Context c, String id) {
        sp(c).edit().remove("pending_" + id).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 原生型：PhotoCraft 文件

    /**
     * 把 PhotoCraft 的偏好与预设库打包成一段 JSON（文件内容 Base64）。
     * 返回 null 表示本机还没生成过这些文件（没打开过 PS）。
     */
    public static String readFilePreset(Context c) throws Exception {
        File prefs = findFile(rootCandidates(c), "prefs.json", 2);
        File root = prefs != null ? prefs.getParentFile() : c.getFilesDir();
        JSONObject files = new JSONObject();
        int total = 0;

        if (prefs != null && prefs.isFile()) {
            byte[] data = readAll(prefs);
            if (data.length > 0 && data.length <= MAX_FILE_ONE) {
                files.put("prefs.json", Base64.encodeToString(data, Base64.NO_WRAP));
                total += data.length;
            }
        }
        total = collectDir(new File(root, "presets"), root, files, total);
        collectDir(new File(root, "brushes"), root, files, total);
        if (files.length() == 0) return null;

        JSONObject body = new JSONObject();
        body.put("kind", "photocraft");
        body.put("files", files);
        return body.toString();
    }

    /** 预读一次，供 UI 判断是否可分享。 */
    public static boolean hasFilePreset(Context c) {
        try {
            return readFilePreset(c) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** PhotoCraft 启动前调用：有待应用预设就先备份原文件再写回。 */
    public static boolean applyPendingFile(Context c) {
        String body = takePending(c, PHOTOCRAFT);
        if (body == null) return false;
        try {
            applyFilePreset(c, body);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static int applyFilePreset(Context c, String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject files = root.optJSONObject("files");
        if (files == null) return 0;
        File dir = findFile(rootCandidates(c), "prefs.json", 2);
        File base = dir != null ? dir.getParentFile() : c.getFilesDir();
        if (base == null) throw new Exception("找不到应用目录");
        String basePath = base.getCanonicalPath();
        int n = 0;
        for (java.util.Iterator<String> it = files.keys(); it.hasNext(); ) {
            String rel = it.next();
            if (rel == null || rel.isEmpty() || rel.contains("..")) continue;
            File out = new File(base, rel);
            String p = out.getCanonicalPath();
            if (!p.startsWith(basePath + File.separator)) continue;
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
            byte[] data = Base64.decode(files.optString(rel, ""), Base64.DEFAULT);
            if (out.isFile()) {
                copy(out, new File(out.getPath() + ".sharebak"));
            }
            FileOutputStream fo = new FileOutputStream(out);
            try {
                fo.write(data);
            } finally {
                fo.close();
            }
            n++;
        }
        return n;
    }

    /** 导入后跳转到对应工具（网页型会在打开时自动回灌预设）。 */
    public static Intent launchIntent(Context c, String id) {
        try {
            if (BLOCKBENCH.equals(id)) {
                return new Intent(c, Class.forName("com.mineways.BlockbenchActivity"))
                        .putExtra("bb_mode", "offline");
            }
            if (SNOWSTORM.equals(id)) {
                return new Intent(c, Class.forName("com.mineways.SnowstormActivity"));
            }
            if (PHOTOCRAFT.equals(id)) {
                return new Intent(c, Class.forName("com.mineways.PhotoCraftActivity"));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------ 文件工具

    private static File[] rootCandidates(Context c) {
        return new File[]{
                c.getFilesDir(),
                new File(c.getApplicationInfo().dataDir, "files"),
                c.getExternalFilesDir(null)
        };
    }

    private static File findFile(File[] roots, String name, int depth) {
        if (roots == null) return null;
        for (File r : roots) {
            File f = findFile(r, name, depth);
            if (f != null) return f;
        }
        return null;
    }

    private static File findFile(File dir, String name, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return null;
        File direct = new File(dir, name);
        if (direct.isFile()) return direct;
        if (depth == 0) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = findFile(k, name, depth - 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static int collectDir(File dir, File base, JSONObject files, int total) {
        if (dir == null || !dir.isDirectory() || total >= MAX_FILE_TOTAL) return total;
        File[] kids = dir.listFiles();
        if (kids == null) return total;
        int cap = 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (cap++ > 20) continue;
                total = collectDir(f, base, files, total);
                if (total >= MAX_FILE_TOTAL) return total;
                continue;
            }
            if (!f.isFile() || f.length() <= 0 || f.length() > MAX_FILE_ONE) continue;
            if (total + f.length() > MAX_FILE_TOTAL) continue;
            if (f.getName().endsWith(".sharebak")) continue;
            try {
                String rel = relPath(base, f);
                if (rel == null) continue;
                files.put(rel, Base64.encodeToString(readAll(f), Base64.NO_WRAP));
                total += f.length();
            } catch (Exception ignored) {
            }
        }
        return total;
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
