package com.mineways.blender;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第三方开源资产：CC0 贴图 / HDRI（Poly Haven）+ 任意来源的 {@code .blend} 模板包。
 *
 * <p><b>Poly Haven</b>：{@code api.polyhaven.com} 公开 API（无需 key，资产本身 CC0）。
 * 按官方要求，用 live API 的产品需要标注来源 —— UI 上有「Powered by Poly Haven」。
 * 只取最小尺寸（1k 优先），手机流量与存储都友好。</p>
 *
 * <p><b>其它来源</b>：任何能直链的 {@code .blend} 或含 {@code .blend} 的 {@code .zip}
 * 都能当模板/工程导进来（解压会自动挑出 blend 文件）。</p>
 */
public final class AssetLib {

    private AssetLib() {
    }

    private static final String API = "https://api.polyhaven.com";
    private static final String UA = "MinewaysMobile-Android/1.0 (Blender-on-Android integration)";

    public static final int TYPE_HDRI = 0;
    public static final int TYPE_TEXTURE = 1;
    public static final int TYPE_MODEL = 2;

    public static final class Asset {
        public final String id;
        public final String name;
        public final int type;

        Asset(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        public String typeName() {
            return type == TYPE_HDRI ? "HDRI" : type == TYPE_TEXTURE ? "贴图" : "模型";
        }
    }

    /** 一个可下载文件。 */
    public static final class AssetFile {
        public final String key;
        public final String url;
        public final long size;

        AssetFile(String key, String url, long size) {
            this.key = key;
            this.url = url;
            this.size = size;
        }
    }

    // ---------------------------------------------------------------- 搜索

    private static File cacheDir(Context c) {
        final File d = new File(BlenderEnv.workDir(c), "assets");
        FileUtil.mkdirs(d);
        return d;
    }

    /** 拉取 /assets（带本地缓存，1 天内复用）。 */
    private static JSONObject assets(Context c) throws IOException {
        final File cached = new File(cacheDir(c), "polyhaven_assets.json");
        if (cached.isFile() && System.currentTimeMillis() - cached.lastModified() < 86400_000L) {
            try {
                return new JSONObject(FileUtil.readText(cached, 8 << 20));
            } catch (Throwable ignored) {
            }
        }
        final String text = Downloader.text(API + "/assets?ts=" + System.currentTimeMillis(), 12 << 20, UA);
        final JSONObject o = parse(text);
        FileUtil.writeText(cached, text);
        return o;
    }

    /** JSON 解析包装：把 JSONException 统一成 IOException。 */
    private static JSONObject parse(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (Throwable t) {
            throw new IOException("返回的不是 JSON：" + t.getMessage());
        }
    }

    /** 按类型 + 关键词搜资产（关键词为空则返回该类型前 N 个）。 */
    public static List<Asset> search(Context c, int type, String query, int limit) throws IOException {
        final JSONObject all = assets(c);
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final List<Asset> out = new ArrayList<>();
        final java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            final String id = it.next();
            final JSONObject a = all.optJSONObject(id);
            if (a == null) continue;
            if (a.optInt("type", -1) != type) continue;
            final String name = a.optString("name", id);
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)
                    && !name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            out.add(new Asset(id, name, type));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 取下载文件清单，key 分类为 blend/base_color/normal/roughness/arm/disp/ao/hdr/exr。 */
    public static List<AssetFile> files(Context c, String id) throws IOException {
        final String text = Downloader.text(API + "/files/" + id, 4 << 20, UA);
        final JSONObject root = parse(text);
        final List<AssetFile> out = new ArrayList<>();
        walk(root, "", out);
        out.sort((a, b) -> Long.compare(a.size, b.size));
        final List<AssetFile> dedup = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (AssetFile f : out) {
            final String group = f.key.replaceAll("[0-9]+k", "");
            if (!seen.add(group)) continue;
            dedup.add(f);
        }
        return dedup;
    }

    /** 递归找出所有带 url 的对象，用路径推断贴图类型。 */
    private static void walk(JSONObject o, String path, List<AssetFile> out) {
        final java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            final String k = it.next();
            final Object v = o.opt(k);
            final String p = path.isEmpty() ? k : path + "/" + k;
            if (v instanceof JSONObject) {
                final JSONObject child = (JSONObject) v;
                final String url = child.optString("url", "");
                if (!url.isEmpty()) {
                    out.add(new AssetFile(classify(p), url, child.optLong("size", 0)));
                } else {
                    walk(child, p, out);
                }
            } else if (v instanceof JSONArray && k.equals("dependencies")) {
                final JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject d = arr.optJSONObject(i);
                    if (d == null) continue;
                    final String url = d.optString("url", "");
                    if (!url.isEmpty()) {
                        out.add(new AssetFile(classify(p), url, d.optLong("size", 0)));
                    }
                }
            }
        }
    }

    static String classify(String path) {
        final String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith("/bl") || p.contains("/bl/") || p.endsWith("blend")) return "blend";
        if (p.contains("nor_gl")) return "normal_gl";
        if (p.contains("nor")) return "normal";
        if (p.contains("rough")) return "roughness";
        if (p.contains("metal")) return "metallic";
        if (p.contains("arm")) return "arm";
        if (p.contains("disp")) return "displacement";
        if (p.contains("ao")) return "ao";
        if (p.contains("diff") || p.contains("albedo") || p.contains("col")) return "base_color";
        if (p.contains("hdr")) return "hdr";
        if (p.contains("exr")) return "exr";
        return p;
    }

    /** 下载某个文件，返回落地路径。 */
    public static File download(Context c, AssetFile f, String nameHint, Downloader.Progress pr,
                                Downloader.Cancel cancel) throws IOException {
        final File dst = new File(cacheDir(c), safe(nameHint) + extOf(f.url));
        if (dst.isFile() && dst.length() > 1024) return dst;
        Downloader.download(f.url, dst, null, pr, cancel);
        FileUtil.makeTreeReadable(dst.getParentFile());
        return dst;
    }

    private static String extOf(String url) {
        final int q = url.indexOf('?');
        final String u = q > 0 ? url.substring(0, q) : url;
        final int slash = u.lastIndexOf('/');
        final String name = slash >= 0 ? u.substring(slash + 1) : u;
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".bin";
    }

    private static String safe(String s) {
        return String.valueOf(s).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ---------------------------------------------------------------- 应用到工程

    /** 把下载好的贴图按槽位替换进材质（先复制进工程目录，保持相对引用可用）。 */
    public static BlenderOps.Result applyTexture(Context c, File project, String materialRegex,
                                                  String map, File image, BlenderOps.Log log) {
        final File local = ProjectOps.importTexture(project, image);
        return ProjectOps.replaceTexture(c, project, materialRegex, map, local.getAbsolutePath(), log);
    }

    /** 用 HDRI 当世界环境（背景 + 强度）。 */
    public static BlenderOps.Result applyHdri(Context c, File project, File hdr, double strength,
                                              BlenderOps.Log log) {
        final File local = ProjectOps.importTexture(project, hdr);
        final String code = "import math\n"
                + "path = " + ProjectOps.pyq(local.getAbsolutePath()) + "\n"
                + "if not os.path.exists(path):\n"
                + "    err(\"HDRI 不存在: \" + path)\n"
                + "else:\n"
                + "    try:\n"
                + "        img = bpy.data.images.load(path, check_existing=True)\n"
                + "    except BaseException as e:\n"
                + "        err(\"HDRI 加载失败: %s\" % e)\n"
                + "        img = None\n"
                + "    if img is not None:\n"
                + "        sc = bpy.context.scene\n"
                + "        w = sc.world\n"
                + "        if w is None:\n"
                + "            w = bpy.data.worlds.new(\"World\")\n"
                + "            sc.world = w\n"
                + "        if not w.use_nodes:\n"
                + "            w.use_nodes = True\n"
                + "        bg = w.node_tree.nodes.get(\"Background\")\n"
                + "        if bg is None:\n"
                + "            for n in w.node_tree.nodes:\n"
                + "                if n.type == \"BACKGROUND\":\n"
                + "                    bg = n\n"
                + "        if bg is None:\n"
                + "            err(\"世界节点里没有 Background，无法设置 HDRI\")\n"
                + "        else:\n"
                + "            bg.inputs[0].default_value = img\n"
                + "            bg.inputs[1].default_value = " + strength + "\n"
                + "            data(\"hdri\", img.name)\n";
        return BlenderOps.run(c, project, code, 300, log);
    }

    // ---------------------------------------------------------------- 第三方 .blend / zip

    /**
     * 从任意 URL 拉一个 {@code .blend} 或含 {@code .blend} 的 {@code .zip} 模板包。
     *
     * @return 找到的 .blend 列表（通常 1 个）
     */
    public static List<File> fetchTemplatePack(Context c, String url, Downloader.Progress pr,
                                                Downloader.Cancel cancel) throws IOException {
        final File dir = new File(cacheDir(c), "packs");
        FileUtil.mkdirs(dir);
        final File pack = new File(dir, "pack_" + System.currentTimeMillis() + extOf(url));
        Downloader.download(url, pack, null, pr, cancel);
        final List<File> out = new ArrayList<>();
        if (pack.getName().toLowerCase(Locale.ROOT).endsWith(".blend")) {
            out.add(pack);
            return out;
        }
        final File ex = new File(dir, "x_" + pack.getName());
        FileUtil.deleteRec(ex);
        if (!unzip(pack, ex)) {
            throw new IOException("不是能识别的压缩包（只支持 .blend / .zip）");
        }
        collectBlends(ex, out, 0);
        if (out.isEmpty()) {
            throw new IOException("压缩包里没有 .blend 文件");
        }
        return out;
    }

    private static void collectBlends(File dir, List<File> out, int depth) {
        if (depth > 6) return;
        final File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                collectBlends(f, out, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".blend")) {
                out.add(f);
            }
        }
    }

    /** 简单 zip 解包（模板包一般不大；限 4GB / 20000 项，条目越界直接跳过）。 */
    public static boolean unzip(File zip, File dest) {
        long total = 0L;
        int count = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            final Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                final ZipEntry e = en.nextElement();
                if (++count > 20000) return false;
                final File out = new File(dest, e.getName());
                try {
                    if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                        continue;
                    }
                } catch (IOException ex) {
                    continue;
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                final File parent = out.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (InputStream in = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(out)) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        if (total > (4L << 30)) return false;
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                out.setReadable(true, false);
            }
        } catch (Throwable t) {
            return false;
        }
        FileUtil.makeTreeReadable(dest);
        return true;
    }
}
