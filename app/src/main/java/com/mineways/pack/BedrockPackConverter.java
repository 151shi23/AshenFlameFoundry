package com.mineways.pack;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 基岩材质包（.mcpack / .zip / 已解包目录）→ Java 版材质包 zip 的编排层。
 *
 * <p>重活全在 {@link PackCore}（libpackcore.so）：四级命名解析链、HUD 语义子表、flipbook 拆帧、
 * {@code .lang → JSON}、{@code pack.mcmeta}。这里只做文件遍历、JSON 解析与打包。
 *
 * <p>从原始 {@code java_core/} 移植时按 Android 改了三件事（原实现只能在 JVM 上跑）：
 * <ul>
 *   <li>{@code java.nio.file} 全家换成 {@code java.io.File}：原版用了 {@code Files.readString}
 *       （Java 11 API）与 {@code Files.walk}，Android 上要么没有、要么行为不一致；</li>
 *   <li>删掉 {@code ImageIOAdapter}（{@code javax.imageio} / {@code java.awt} 在 Android 上不存在），
 *       图像编解码由调用方注入的 {@link ImageAdapter} 负责；</li>
 *   <li>补了两处会炸的空值：{@code savePng} 返回 null 会让写 zip 直接 NPE；
 *       {@code flipbook_texture} 写成不带目录的裸文件名时 {@code getParent()} 是 null。</li>
 * </ul>
 *
 * <p>转换是同步阻塞的（Faithful 64x 实测 1.3 秒 / 1621 张），调用方自己放后台线程；
 * 每处理一个条 {@code Progress} 回调一次，页面能显示走到哪。
 */
public final class BedrockPackConverter {

    /** 输出包的 {@code pack_format}。34 = Java 1.21.4+（原作者口径，原版材质包体系到这一档）。 */
    public static final int JAVA_PACK_FORMAT = 34;

    /** PNG ↔ ARGB 像素的编解码由宿主提供（Android = {@code android.graphics.Bitmap}）。 */
    public interface ImageAdapter {
        /** 读 PNG → ARGB int[]；{@code sizeOut[0]=宽 sizeOut[1]=高}；解不开返回 null（这张会被跳过）。 */
        int[] loadArgb(byte[] pngBytes, int[] sizeOut);

        /** ARGB → PNG 字节；失败返回 null（这张按降级计入清单，不炸整个转换）。 */
        byte[] savePng(int[] argb, int w, int h);
    }

    /** 进度回调（在转换所在线程上调用，UI 侧自己 post 回主线程）。 */
    public interface Progress {
        void onStep(String stage, int done, int moved, int skipped);
    }

    /** 转换结果统计，直接映射到页面上的报告。 */
    public static final class Result {
        public int texturesMoved;
        public int texturesSkipped;
        public int flipbooks;
        public int langs;
        public int soundsDefs;
        /** 非 PNG 输入（.tga / .jpg）被重编码成 PNG 的张数。 */
        public int reencoded;
        /** 基岩命名空间改写成 Java 键的条数（tile.x.name → block.minecraft.x 这类）。 */
        public int langKeysFixed;
        /** 一起搬进包的音频文件数（不搬的话 sounds.json 全是死引用）。 */
        public int soundsFiles;
        /** pack.mcmeta 实际写出去的 pack_format（按 min_engine_version 推的）。 */
        public int packFormat = JAVA_PACK_FORMAT;
        /** 靠包自带材质册别名反查救回来的张数（按文件名直查会降级的那些）。 */
        public int byAlias;
        /** 逐文件的降级清单（基岩相对路径原文）—— Java 侧没有对应机制的那些。 */
        public final List<String> degrade = new ArrayList<>();

        @Override
        public String toString() {
            return "moved=" + texturesMoved + " skipped=" + texturesSkipped
                    + " reencoded=" + reencoded + " 别名救回=" + byAlias
                    + " flipbooks=" + flipbooks
                    + " langs=" + langs + "(键改写 " + langKeysFixed + ")"
                    + " sounds=" + soundsDefs + "/" + soundsFiles + " 个文件"
                    + " pack_format=" + packFormat + " degrade=" + degrade.size();
        }
    }

    private BedrockPackConverter() {
    }

    /**
     * 主入口。
     *
     * @param input     {@code .mcpack} / {@code .zip} 文件，或已经解包的材质包目录
     * @param outputZip 输出的 Java 版材质包 zip（父目录会自动建）
     * @param img       PNG 编解码实现
     * @param tmpRoot   解包用的临时目录；本方法自己建 {@code unpack_*} 子目录并在结束时清掉
     * @param cb        进度，可为 null
     */
    public static Result convert(File input, File outputZip, ImageAdapter img,
            File tmpRoot, Progress cb) throws IOException, JSONException {
        Result r = new Result();
        File root = input;
        File unpacked = null;
        String lower = input.getName().toLowerCase(Locale.ROOT);
        if (input.isFile() && (lower.endsWith(".zip") || lower.endsWith(".mcpack"))) {
            unpacked = new File(tmpRoot, "unpack_" + System.nanoTime());
            root = unpacked;
            unzip(input, root);
        } else if (!input.isDirectory()) {
            throw new IOException("既不是 .mcpack/.zip 也不是目录：" + input.getAbsolutePath());
        }
        try {
            run(resolveRoot(root), outputZip, img, r, cb);
        } finally {
            if (unpacked != null) {
                deleteTree(unpacked);   // 原版不删，一次转换在缓存里留几十兆
            }
        }
        return r;
    }

    /** 手工压缩的包常整体套一层文件夹（{@code MyPack/textures/…}），先降下去再找。 */
    static File resolveRoot(File dir) {
        File cur = dir;
        for (int i = 0; i < 3; i++) {
            if (new File(cur, "textures").isDirectory() || new File(cur, "manifest.json").isFile()
                    || new File(cur, "assets").isDirectory()) {
                return cur;
            }
            File[] kids = cur.listFiles();
            File only = null;
            int n = 0;
            if (kids == null) {
                return cur;
            }
            for (File k : kids) {
                if (k.isDirectory()) {
                    n++;
                    only = k;
                }
            }
            if (n != 1) {
                return cur;
            }
            cur = only;
        }
        return cur;
    }

    private static void run(File root, File outputZip, ImageAdapter img, Result r, Progress cb)
            throws IOException, JSONException {
        Map<String, byte[]> out = new TreeMap<>();
        int done = 0;

        // ---- 0. 材质册别名表：terrain/item_texture.json 里的短名才是「规范名」 ----
        // 基岩贴图文件名经常是变体名（textures/items/bed_armor_stand.png），而它的规范短名写在
        // texture_data 的键里（armor_stand）。以前只按文件名查表 → 这类直接降级；
        // 现在先读包自带的册子做一层反查，**只对原本要丢的那些文件生效**（纯增益，不会改坏已命中的）。
        Map<String, String> alias = textureBookAliases(root);

        // ---- 1. 贴图：全部走 so 的四级解析链 ----
        File texRoot = new File(root, "textures");
        List<File> imgs = new ArrayList<>();
        collectImages(texRoot, imgs);
        for (File p : imgs) {
            String rel = relative(root, p);
            String ext = extOf(rel);
            // 查表时按 .png 形态喂给 C（表里的键是 Java 习惯的 xxx.png），
            // 而基岩包里确实有 .tga 贴图（Faithful 64x 的 kelp 全系）—— 以前这些连降级清单都进不去
            String lookup = ".tga".equals(ext) ? rel.substring(0, rel.length() - 4) + ".png" : rel;
            String jp = PackCore.resolveTexturePath(lookup);
            if ((jp == null || jp.isEmpty()) && alias.containsKey(lookup)) {
                jp = PackCore.resolveTexturePath(alias.get(lookup));
                if (jp != null && !jp.isEmpty()) {
                    r.byAlias++;
                }
            }
            if (jp == null || jp.isEmpty()) {
                r.texturesSkipped++;
                r.degrade.add(rel);
                continue;
            }
            if (".png".equals(ext)) {
                out.put(jp, readAll(p));
                r.texturesMoved++;
            } else {
                // tga / jpg：Java 侧不认（或路径已改名），统一重编码成 PNG 再进包
                int[] size = new int[2];
                int[] argb = ".tga".equals(ext)
                        ? Tga.decode(readAll(p), size)
                        : img.loadArgb(readAll(p), size);
                byte[] png = argb == null || size[0] <= 0 || size[1] <= 0
                        ? null : img.savePng(argb, size[0], size[1]);
                if (png == null) {
                    r.texturesSkipped++;
                    r.degrade.add(rel + "（" + ext + " 解不了）");
                    continue;
                }
                out.put(jp, png);
                r.reencoded++;
                r.texturesMoved++;
            }
            if (cb != null) {
                cb.onStep("贴图", ++done, r.texturesMoved, r.texturesSkipped);
            }
        }

        // ---- 2. flipbook 动画：基岩大图 → Java 竖条 + .mcmeta ----
        File fbFile = new File(new File(root, "textures"), "flipbook_textures.json");
        if (fbFile.isFile()) {
            JSONArray arr = new JSONArray(stripJsonComments(read(fbFile)));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fb = arr.optJSONObject(i);
                if (fb == null) {
                    continue;
                }
                String tex = fb.optString("flipbook_texture", "");
                String tile = fb.optString("atlas_tile", "");
                JSONArray frames = fb.optJSONArray("frames");
                if (tex.isEmpty() || tile.isEmpty() || frames == null || frames.length() == 0) {
                    continue;
                }
                File src = findTexture(root, tex);
                if (src == null) {
                    r.degrade.add("flipbook 源图找不到:" + tex);
                    continue;
                }
                int[] size = new int[2];
                int[] argb = decodeImage(img, src, size);
                if (argb == null || size[0] <= 0 || size[1] <= 0) {
                    r.degrade.add("flipbook 源图解不了:" + tex);
                    continue;
                }
                int imgW = size[0];
                int imgH = size[1];
                int[] fidx = new int[frames.length()];
                for (int k = 0; k < fidx.length; k++) {
                    fidx[k] = frames.optInt(k, k);
                }
                // 帧布局推断（照参考实现的规则）：竖条优先、横条兜底。
                // 原来一律按「帧高 = 图宽」的正方形竖条处理 —— kelp_a 是 64×1280/20 帧，
                // 帧高恰好等于图宽才碰巧对；非正方形或横排的包会整条切错。
                int framePx;
                boolean vertical;
                if (imgH % fidx.length == 0 && imgH / fidx.length <= imgW) {
                    framePx = imgH / fidx.length;
                    vertical = true;
                } else if (imgW % fidx.length == 0) {
                    framePx = imgW / fidx.length;
                    vertical = false;
                } else {
                    r.degrade.add("flipbook 帧布局推不出:" + tex);
                    continue;
                }
                int tileW = vertical ? imgW : framePx;
                int tileH = vertical ? framePx : imgH;
                int[] strip = vertical
                        ? PackCore.flipbookToStrip(argb, imgW, imgH, imgW, framePx, fidx)
                        : stripFromRows(argb, imgW, imgH, framePx, fidx);
                if (strip == null) {
                    r.degrade.add("flipbook 帧越界:" + tex);
                    continue;
                }
                byte[] png = img.savePng(strip, tileW, tileH * fidx.length);
                if (png == null) {
                    r.texturesSkipped++;
                    r.degrade.add("flipbook 编码失败:" + tex);
                    continue;
                }
                // 源图在哪个子目录就按那个目录判（blocks→block、items→item），裸文件名兜底到 block
                String dir = parentName(tex);
                String jd = "blocks".equals(dir) ? "block" : ("items".equals(dir) ? "item" : dir);
                String outRel = "assets/minecraft/textures/" + jd + "/" + tile + ".png";
                out.put(outRel, png);
                JSONObject anim = new JSONObject();
                // 权威字段是 ticks_per_frame（实测 Faithful 64x 六条动画全用它）；
                // 原来只认 fps，结果 frametime 一个都没写出去 —— 动画即使转出来也是满速闪。
                int tpf = 0;
                if (fb.has("ticks_per_frame")) {
                    tpf = Math.max(1, fb.optInt("ticks_per_frame", 1));
                } else if (fb.has("fps")) {
                    double fps = fb.optDouble("fps", 0);
                    if (fps > 0) {
                        tpf = Math.max(1, Math.round(20f / (float) fps));
                    }
                }
                if (tpf > 1) {
                    anim.put("frametime", tpf);
                }
                if (fb.has("blend_frames")) {
                    anim.put("interpolate", true);
                }
                JSONObject mcmeta = new JSONObject();
                mcmeta.put("animation", anim);
                out.put(outRel + ".mcmeta", mcmeta.toString().getBytes(StandardCharsets.UTF_8));
                r.flipbooks++;
                if (cb != null) {
                    cb.onStep("动画", ++done, r.texturesMoved, r.texturesSkipped);
                }
            }
        }

        // ---- 3. manifest → pack.mcmeta（pack_format 按包自己的 min_engine_version 推）----
        for (String cand : new String[]{"manifest.json", "pack_manifest.json"}) {
            File mf = new File(root, cand);
            if (mf.isFile()) {
                JSONObject m = new JSONObject(stripJsonComments(read(mf)));
                JSONObject header = m.optJSONObject("header");
                String desc = header == null ? "converted pack"
                        : header.optString("name", "converted pack");
                int fmt = packFormatOf(header);
                r.packFormat = fmt;
                out.put("pack.mcmeta", PackCore.buildPackMcmeta(desc, fmt)
                        .getBytes(StandardCharsets.UTF_8));
                break;
            }
        }

        // ---- 4. 语言文件 .lang → lang JSON ----
        List<File> langFiles = new ArrayList<>();
        collectSuffix(new File(root, "texts"), ".lang", langFiles);
        for (File p : langFiles) {
            String fn = p.getName();
            String code = fn.substring(0, fn.length() - 5).toLowerCase(Locale.ROOT);
            // 两件事都得做：① 文件名必须小写（基岩是 en_US.lang，Java 只读 lang/en_us.json，
            //    原名照抄的话整个文件在游戏里根本不会被加载）；② 键的命名空间要换
            //    （基岩 tile.stone.name / item.apple.name → Java block.minecraft.stone /
            //     item.minecraft.apple），不换的那类翻译在 Java 里是死键。
            JSONObject raw = new JSONObject(PackCore.langToJson(read(p)));
            JSONObject fixed = new JSONObject();
            Iterator<String> lk = raw.keys();
            while (lk.hasNext()) {
                String k = lk.next();
                String nk = javaLangKey(k);
                if (!nk.equals(k)) {
                    r.langKeysFixed++;
                }
                try {
                    fixed.put(nk, raw.get(k));
                } catch (JSONException ignored) {
                }
            }
            out.put("assets/minecraft/lang/" + code + ".json",
                    fixed.toString(1).getBytes(StandardCharsets.UTF_8));
            r.langs++;
            if (cb != null) {
                cb.onStep("语言", ++done, r.texturesMoved, r.texturesSkipped);
            }
        }

        // ---- 5. 声音定义 sound_definitions.json → sounds.json ----
        File sd = new File(new File(root, "sounds"), "sound_definitions.json");
        if (sd.isFile()) {
            // Android 自带的 org.json：没有两参数的 optJSONObject，也没有 keySet()，只有 keys() 迭代器
            JSONObject holder = new JSONObject(stripJsonComments(read(sd)));
            JSONObject defs = holder.optJSONObject("sound_definitions");
            if (defs == null) {
                defs = new JSONObject();
            }
            JSONObject jmap = new JSONObject();
            List<String> names = new ArrayList<>();
            Iterator<String> ks = defs.keys();
            while (ks.hasNext()) {
                names.add(ks.next());
            }
            Collections.sort(names);
            for (String k : names) {
                JSONObject v = defs.optJSONObject(k);
                if (v == null) {
                    continue;
                }
                JSONArray files = v.optJSONArray("sounds");
                List<String> fl = new ArrayList<>();
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        Object o = files.opt(i);
                        String p = o instanceof String ? (String) o
                                : (o instanceof JSONObject
                                        ? ((JSONObject) o).optString("name", "") : "");
                        p = javaSoundPath(p);
                        if (!p.isEmpty()) {
                            fl.add(p);
                        }
                    }
                }
                if (fl.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject ev = new JSONObject().put("sounds", new JSONArray(fl));
                    String cat = v.optString("category", "");
                    if ("music".equals(cat) || "record".equals(cat) || "weather".equals(cat)
                            || "block".equals(cat) || "hostile".equals(cat) || "neutral".equals(cat)
                            || "player".equals(cat) || "ambient".equals(cat) || "voice".equals(cat)
                            || "master".equals(cat)) {
                        ev.put("category", cat);   // Java 的音量分组，带上才不会全挤在 master 里
                    }
                    jmap.put(k, ev);
                } catch (Exception ignored) {
                }
            }
            out.put("assets/minecraft/sounds.json", jmap.toString().getBytes(StandardCharsets.UTF_8));
            r.soundsDefs = names.size();
            // 声音文件本体必须一起搬：以前只写 sounds.json，引用全指向包里没有的文件，等于一堆死键
            List<File> audio = new ArrayList<>();
            collectSuffix(new File(root, "sounds"), ".ogg", audio);
            collectSuffix(new File(root, "sounds"), ".wav", audio);
            for (File a : audio) {
                out.put("assets/minecraft/" + relative(root, a), readAll(a));
                r.soundsFiles++;
            }
        }

        // ---- 6. 打包输出 ----
        File parent = outputZip.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不出输出目录：" + parent.getAbsolutePath());
        }
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(outputZip));
        try {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                zo.putNextEntry(new ZipEntry(e.getKey()));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        } finally {
            zo.close();
        }
        if (cb != null) {
            cb.onStep("写包", ++done, r.texturesMoved, r.texturesSkipped);
        }
    }

    /** 剥离基岩 JSON 的 {@code //} 与块注释（基岩解析器容忍，org.json 不容忍）。 */
    public static String stripJsonComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (d == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                    continue;
                }
                if (d == '*') {
                    i += 2;
                    while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 内部工具

    /** 解 zip/mcpack 到 dest，带路径穿越防护（{@code ../} 之类条目直接丢）。 */
    private static void unzip(File zip, File dest) throws IOException {
        if (!dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("建不出临时解包目录：" + dest.getAbsolutePath());
        }
        InputStream in = new FileInputStream(zip);
        try {
            ZipInputStream zin = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File t = new File(dest, e.getName()).getCanonicalFile();
                if (!t.getPath().startsWith(dest.getCanonicalPath() + File.separator)) {
                    continue;   // 路径穿越防护
                }
                if (e.isDirectory()) {
                    mkdirs(t);
                } else {
                    mkdirs(t.getParentFile());
                    FileOutputStream fo = new FileOutputStream(t);
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                        }
                    } finally {
                        fo.close();
                    }
                }
                zin.closeEntry();
            }
            zin.close();
        } finally {
            in.close();
        }
    }

    /**
     * 读包自带的材质册（{@code textures/terrain_texture.json} / {@code textures/item_texture.json}），
     * 建「文件实际路径 → 规范短名路径」反查表。册子里的 {@code textures} 字段基岩有三种写法
     * （裸字符串、{@code {"path": …}}、上面两者的数组），全吃；带 {@code tint_color} 的变体取第一条。
     * 只读得到就用，读不到（没这文件或 JSON 坏）就空表 —— 不影响主流程。
     */
    private static Map<String, String> textureBookAliases(File root) {
        Map<String, String> out = new HashMap<>();
        String[][] books = {
                {"textures/terrain_texture.json", "textures/blocks/"},
                {"textures/item_texture.json", "textures/items/"},
        };
        for (String[] b : books) {
            File f = new File(root, b[0]);
            if (!f.isFile()) {
                continue;
            }
            try {
                JSONObject data = new JSONObject(stripJsonComments(read(f)))
                        .optJSONObject("texture_data");
                if (data == null) {
                    continue;
                }
                Iterator<String> k = data.keys();
                while (k.hasNext()) {
                    String key = k.next();
                    String path = firstPath(data.opt(key));
                    if (path == null || path.isEmpty()) {
                        continue;
                    }
                    String norm = path.replace('\\', '/');
                    while (norm.startsWith("/")) {
                        norm = norm.substring(1);
                    }
                    if (!norm.startsWith("textures/")) {
                        norm = "textures/" + norm;
                    }
                    String canon = b[1] + key + ".png";
                    if (!norm.endsWith(".png")) {
                        norm = norm + ".png";
                    }
                    if (!norm.equals(canon)) {
                        out.put(norm, canon);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 从 textures 字段里挑出第一个可用路径（字符串 / {path} / 数组 三种写法）。 */
    private static String firstPath(Object v) {
        if (!(v instanceof JSONObject)) {
            return null;
        }
        Object t = ((JSONObject) v).opt("textures");
        if (t instanceof String) {
            return (String) t;
        }
        if (t instanceof JSONArray) {
            JSONArray a = (JSONArray) t;
            return a.length() == 0 ? null : firstOf(a.opt(0));
        }
        return firstOf(t);
    }

    private static String firstOf(Object o) {
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof JSONObject) {
            String p = ((JSONObject) o).optString("path", "");
            return p.isEmpty() ? null : p;
        }
        return null;
    }

    /** 收贴图：png / jpg / jpeg / tga —— 只收 png 的话，带 .tga 的包会静默少掉几十张（Faithful 74 张）。 */
    private static void collectImages(File dir, List<File> out) {
        collectSuffix(dir, ".png", out);
        collectSuffix(dir, ".jpg", out);
        collectSuffix(dir, ".jpeg", out);
        collectSuffix(dir, ".tga", out);
    }

    private static String extOf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i).toLowerCase(Locale.ROOT);
    }

    /** 基岩的 flipbook_texture 常不带扩展名（也可能指 .tga），三种后缀都试一次。 */
    private static File findTexture(File root, String rel) {
        String clean = rel.startsWith("/") ? rel.substring(1) : rel;
        if (new File(root, clean).isFile()) {
            return new File(root, clean);
        }
        String[] tries = {clean + ".png", clean + ".tga", clean + ".jpg"};
        for (String t : tries) {
            File f = new File(root, t);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** 解一张图成 ARGB：tga 走自带的 TGA 解码器（Android 的 Bitmap 也不认 tga），其余交给宿主适配器。 */
    private static int[] decodeImage(ImageAdapter img, File f, int[] sizeOut) {
        try {
            byte[] raw = readAll(f);
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".tga")) {
                return Tga.decode(raw, sizeOut);
            }
            return img.loadArgb(raw, sizeOut);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 横排帧（沿 X 铺开）时 C 算子不适用（它按「帧号 = 第几行」取源），这里自己拼竖条。 */
    private static int[] stripFromRows(int[] argb, int imgW, int imgH, int frameW, int[] frames) {
        int[] out = new int[frameW * imgH * frames.length];
        for (int i = 0; i < frames.length; i++) {
            int col = frames[i] * frameW;
            if (col < 0 || col + frameW > imgW) {
                return null;
            }
            for (int y = 0; y < imgH; y++) {
                System.arraycopy(argb, y * imgW + col, out, (i * imgH + y) * frameW, frameW);
            }
        }
        return out;
    }

    /**
     * 基岩的声音路径 → Java 的引用写法：去掉开头的 {@code sounds/} 与扩展名；
     * Java 是按 {@code assets/minecraft/sounds/<路径>} 查文件的。
     */
    static String javaSoundPath(String p) {
        if (p == null) {
            return "";
        }
        String s = p.replace('\\', '/').trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.startsWith("minecraft/sounds/")) {
            s = s.substring("minecraft/sounds/".length());
        } else if (s.startsWith("sounds/")) {
            s = s.substring("sounds/".length());
        }
        int dot = s.lastIndexOf('.');
        int slash = s.lastIndexOf('/');
        if (dot > slash) {
            s = s.substring(0, dot);
        }
        return s;
    }

    /**
     * 基岩 lang 键 → Java lang 键。只改**有把握**的五类前缀（不改就是死键）：
     * {@code tile.x.name → block.minecraft.x}、{@code item.x.name → item.minecraft.x}、
     * {@code entity.x.name → entity.minecraft.x}、{@code effect.}、{@code enchantment.}。
     * 其余（gui./options./container./advancements./death./subtitles. 等）原样保留 ——
     * 两边共用一大批键名，硬猜反而把好的改坏。
     */
    static String javaLangKey(String k) {
        if (k == null || k.length() == 0) {
            return "";
        }
        String[] pairs = {
                "tile.", "block.minecraft.",
                "item.", "item.minecraft.",
                "entity.", "entity.minecraft.",
                "effect.", "effect.minecraft.",
                "enchantment.", "enchantment.minecraft.",
        };
        for (int i = 0; i < pairs.length; i += 2) {
            String from = pairs[i];
            if (!k.startsWith(from) || k.length() <= from.length()) {
                continue;
            }
            String rest = k.substring(from.length());
            if (rest.endsWith(".name")) {
                rest = rest.substring(0, rest.length() - 5);
            }
            if (rest.isEmpty() || rest.contains(".")) {
                return k;      // 二级结构（item.spawn_egg.entity.x.name 之类）不硬改
            }
            return pairs[i + 1] + rest;
        }
        return k;
    }

    /**
     * pack_format 按包自己的 {@code min_engine_version} 推（写死 34 会让新引擎的包被 Java
     * 判成「为旧版本制作」）。分档与参考实现同一张表。
     */
    static int packFormatOf(JSONObject header) {
        int[][] marks = {{1, 21, 0}, {1, 21, 40}, {1, 21, 60}, {1, 21, 80}, {1, 21, 90}, {1, 22, 0}};
        int[] fmts = {34, 42, 46, 55, 60, 64};
        long ver = ((long) 1 << 40) | ((long) 21 << 20);
        if (header != null) {
            JSONArray a = header.optJSONArray("min_engine_version");
            if (a != null && a.length() >= 3) {
                ver = ((long) a.optInt(0, 1) << 40) | ((long) a.optInt(1, 21) << 20) | a.optInt(2, 0);
            }
        }
        int pf = fmts[0];
        for (int i = 0; i < marks.length; i++) {
            long bound = ((long) marks[i][0] << 40) | ((long) marks[i][1] << 20) | marks[i][2];
            if (ver >= bound) {
                pf = fmts[i];
            }
        }
        return pf;
    }

    private static void collectSuffix(File dir, String suffix, List<File> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collectSuffix(f, suffix, out);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                out.add(f);
            }
        }
    }

    private static String relative(File root, File f) {
        String rp = canon(root).getPath();
        String fp = canon(f).getPath();
        String rel = fp.startsWith(rp) ? fp.substring(rp.length()) : fp;
        while (rel.startsWith(File.separator)) {
            rel = rel.substring(File.separator.length());
        }
        return rel.replace('\\', '/');
    }

    /** {@code textures/blocks/stone.png} → {@code blocks}；裸文件名返回空串（不炸 getParent()==null）。 */
    private static String parentName(String rel) {
        int i = rel.lastIndexOf('/');
        if (i <= 0) {
            return "block";
        }
        String head = rel.substring(0, i);
        int j = head.lastIndexOf('/');
        return j >= 0 ? head.substring(j + 1) : head;
    }

    private static File canon(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static void mkdirs(File f) {
        if (f != null && !f.isDirectory()) {
            f.mkdirs();
        }
    }

    private static String read(File f) throws IOException {
        return new String(readAll(f), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(f.length(), 1024));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    deleteTree(k);
                } else {
                    k.delete();
                }
            }
        }
        f.delete();
    }
}
