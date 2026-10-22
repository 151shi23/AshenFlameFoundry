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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Java 材质包（目录 / .zip）→ 基岩版材质包 {@code .mcpack}。与 {@link BedrockPackConverter}
 * 反向对称，口径一样：**能搬的一张不落，搬不了的列清单，不出半张错图、不假装成功**。
 *
 * <p>比上游那个 node 工具多做的事（它的产物实测只有 {@code textures/} + {@code manifest.json}，
 * 还把 Java 的 {@code .mcmeta} 原样扔进基岩包 —— 基岩根本不读它，语言与声音一个字节都没转）：
 * <ul>
 *   <li>{@code .mcmeta} 动画 → {@code textures/flipbook_textures.json}；横排/网格帧**重排成竖条**
 *       （基岩只认「帧号 = 第几行」，官方文档口径：每帧是一个正方形、从顶部第 0 帧往下数）；</li>
 *   <li>{@code lang/*.json} → {@code texts/*.lang} + {@code texts/languages.json}；</li>
 *   <li>{@code sounds.json} → {@code sounds/sound_definitions.json}，连音频本体一起搬。</li>
 * </ul>
 *
 * <p>映射的三份依据，全部是**读来的、不是编的**：目录级用官方转换文档
 * （learn.microsoft.com「Converting Texture Packs from Java to Bedrock」那张 Java↔基岩目录表）；
 * 逐文件用 {@code assets/packconv/name_map_java2bedrock.json}（957 条，由上游 RenameConverter
 * 反转，注意它的 Java 一侧写的是复数目录 {@code textures/blocks/}，必须归一化成 Java 的
 * {@code textures/block/}，否则精确级一条都命中不了）；HUD 贴图用
 * {@code src/bedrock2java.py} 里那张 {@code UI_SPRITES_MAP} 反转。文档表里没有、而实测表里一致的
 * 目录（{@code mob_effect → ui}）按实测表走。
 *
 * <p>目录归属只做「Java 目录 → 基岩目录」直映射，不做跨目录猜名：实测那种猜测会把 Java 的
 * {@code item/brick} 抢进基岩 {@code blocks/brick}，和方块贴图撞同一个文件。真需要跨目录的规则
 * （{@code block/crimson_sign → entity/sign_crimson} 这类）本来就在改名表里。两个 Java 文件落到
 * 同一个基岩路径时在降级清单里点名，不静默覆盖。
 */
public final class Java2BedrockConverter {

    /** 与反向一致的进度回调（在转换所在线程上调用）。 */
    public interface Progress {
        void onStep(String stage, int done, int moved, int skipped);
    }

    /** 转换结果，直接映射到页面报告。 */
    public static final class Result {
        public int texturesMoved;
        public int texturesSkipped;
        public int exactHits;
        public int sameNameHits;
        public int tokenHits;
        /** 改名表里没有这条规则 ⇒ 两边名字本来就一样，按同名直搬（占绝大多数）。 */
        public int directMoves;
        public int animations;
        /** 横排/网格帧被重排成基岩竖条的条数（不重排基岩就整条切错）。 */
        public int animationsRebuilt;
        public int langs;
        public int langKeys;
        public int soundsDefs;
        public int soundsFiles;
        public int reencoded;
        public String packName = "";
        public String packDesc = "";
        public String iconSource = "没有（基岩会显示默认图标）";
        public String javaPackFormat = "没写（按基岩 1.21.0 起）";
        public final List<String> degrade = new ArrayList<>();

        @Override
        public String toString() {
            return "moved=" + texturesMoved + "（表内精确 " + exactHits + " / 同名印证 " + sameNameHits
                    + " / 词序重排 " + tokenHits + " / 同名直搬 " + directMoves + "）"
                    + " skipped=" + texturesSkipped
                    + " 动画=" + animations + "（重排竖条 " + animationsRebuilt + "）"
                    + " 语言=" + langs + "/" + langKeys + "键"
                    + " 声音=" + soundsDefs + "定义/" + soundsFiles + "文件"
                    + " 重编码=" + reencoded + " pack_format=" + javaPackFormat
                    + " 降级=" + degrade.size();
        }
    }

    private Java2BedrockConverter() {
    }

    /** 命中类别。 */
    private static final int HIT_EXACT = 0, HIT_SAME = 1, HIT_TOKEN = 2, HIT_MISS = 3;

    /** 映射结果：基岩路径 + 命中级别。 */
    static final class Hit {
        final String bed;
        final int kind;

        Hit(String bed, int kind) {
            this.bed = bed;
            this.kind = kind;
        }
    }

    /**
     * 官方文档的 Java → 基岩目录表（effect / font 在基岩没有对应目录，故为 null）。
     */
    private static final Map<String, String> DOC_DIR = new LinkedHashMap<>();
    static {
        DOC_DIR.put("block", "blocks");
        DOC_DIR.put("item", "items");
        DOC_DIR.put("entity", "entity");
        DOC_DIR.put("environment", "environment");
        DOC_DIR.put("colormap", "colormap");
        DOC_DIR.put("gui", "gui");
        DOC_DIR.put("map", "map");
        DOC_DIR.put("misc", "misc");
        DOC_DIR.put("models", "models");
        DOC_DIR.put("painting", "painting");
        DOC_DIR.put("particle", "particles");
        DOC_DIR.put("trims", "trims");          // 文档表没列，但 bedrock2java.py 的基岩目录表里有 trims
        DOC_DIR.put("effect", null);
        DOC_DIR.put("font", null);
    }

    /**
     * HUD 精灵：Java 1.20.2+ 的 {@code gui/sprites/<v>} 对应基岩 {@code textures/ui/<k>}。
     * 逐条抄自 {@code pack_converter_complete/src/bedrock2java.py} 的 {@code UI_SPRITES_MAP}
     * （反向那张表），这里只是把它倒过来用。
     */
    private static final String[][] HUD_PAIRS = {
            {"armor_full.png", "hud/armor_full.png"},
            {"armor_half.png", "hud/armor_half.png"},
            {"armor_empty.png", "hud/armor_empty.png"},
            {"absorption_heart.png", "hud/heart/absorbing_full.png"},
            {"absorption_heart_half.png", "hud/heart/absorbing_half.png"},
            {"freeze_heart.png", "hud/heart/frozen_full.png"},
            {"freeze_heart_half.png", "hud/heart/frozen_half.png"},
            {"heart.png", "hud/heart/full.png"},
            {"heart_half.png", "hud/heart/half.png"},
            {"heart_flash.png", "hud/heart/container.png"},
            {"heart_background.png", "hud/heart/container.png"},
            {"poison_heart.png", "hud/heart/poisoned_full.png"},
            {"poison_heart_half.png", "hud/heart/poisoned_half.png"},
            {"horse_heart.png", "hud/heart/vehicle_full.png"},
            {"horse_heart_half.png", "hud/heart/vehicle_half.png"},
            {"horse_heart_flash.png", "hud/heart/vehicle_container.png"},
            {"experiencebarfull.png", "hud/experience_bar_progress.png"},
            {"experiencebarempty.png", "hud/experience_bar_background.png"},
            {"hunger_full.png", "hud/food_full.png"},
            {"hunger_half.png", "hud/food_half.png"},
            {"hunger_background.png", "hud/food_empty.png"},
            {"hunger_effect_full.png", "hud/food_full_hunger.png"},
            {"hunger_effect_half.png", "hud/food_half_hunger.png"},
            {"hunger_effect_background.png", "hud/food_empty_hunger.png"},
    };

    /**
     * 同一个基岩文件在两个 Java 版本里的名字：包里装的可能是旧名也可能是新名，两边都得认。
     * {基岩路径, 旧 Java 名, 新 Java 名}，逐条抄自上游 npm 包
     * {@code @modifiedcommand/convert-minecraft-java-texture-to-bedrock-api}
     * 的 {@code RenameConverter.DEFAULT_CONVERTER_DATA}（它比随包那份 957 条导出新，
     * Java 1.20.2 把 {@code cactus_green} 这类染料改名成了 {@code green_dye}）。
     */
    private static final String[][] JAVA_RENAMED = {
            {"textures/items/dye_powder_green.png", "cactus_green.png", "green_dye.png"},
            {"textures/items/dye_powder_yellow.png", "dandelion_yellow.png", "yellow_dye.png"},
            {"textures/items/dye_powder_red.png", "rose_red.png", "red_dye.png"},
    };

    /** Java 目录段 → 基岩目录段：先由映射表实测推导，再退回官方文档表。 */
    private final Map<String, String> dirMap = new HashMap<>(DOC_DIR);
    /** 基岩目录 → 该目录已知文件名。 */
    private final Map<String, Set<String>> bedrockNames = new HashMap<>();
    /** Java 规范路径（{@code textures/block/x.png}） → 基岩路径。 */
    private final Map<String, String> exact = new HashMap<>();
    /** Java {@code gui/sprites/} 下的相对路径 → 基岩 {@code ui/} 文件名。 */
    private final Map<String, String> hud = new HashMap<>();

    // ---------------------------------------------------------------- 入口

    /**
     * 主入口。
     *
     * @param input       Java 材质包：目录，或 .zip（父目录里有 {@code pack.mcmeta}）
     * @param outMcpack   输出的 .mcpack（就是 zip）
     * @param img         PNG 编解码（动画帧要重排、jpg 要重编码；null = 这两类直接进降级清单）
     * @param tmpRoot     解 zip 用的临时目录，本方法自己建 {@code unpack_*} 子目录并在结束时清掉
     * @param nameMapJson {@code name_map_java2bedrock.json}（assets 里那份）的字符流
     * @param cb          进度，可为 null
     */
    public static Result convert(File input, File outMcpack, BedrockPackConverter.ImageAdapter img,
            File tmpRoot, Reader nameMapJson, Progress cb) throws Exception {
        Java2BedrockConverter c = new Java2BedrockConverter();
        c.loadMap(nameMapJson);
        return c.run(input, outMcpack, img, tmpRoot, cb);
    }

    /**
     * 页面自检：真读一遍 assets 里那张映射表，再反查三个已知名字。
     * 表读不到 / 反查不中，第一次进页面就能看见，不用等转换失败。
     */
    public static String selfTest(Reader r) throws Exception {
        Java2BedrockConverter c = new Java2BedrockConverter();
        c.loadMap(r);
        Hit a = c.mapJavaToBedrock("block/oak_planks.png");
        Hit b = c.mapJavaToBedrock("item/green_dye.png");
        Hit d = c.mapJavaToBedrock("gui/sprites/hud/heart/full.png");
        return "反查表 " + c.exact.size() + " 条，目录规则 " + c.dirMap.size() + " 项\n"
                + "block/oak_planks → " + (a == null ? "无" : a.bed) + "\n"
                + "item/green_dye → " + (b == null ? "无" : b.bed) + "\n"
                + "gui/sprites/hud/heart/full → " + (d == null ? "无" : d.bed);
    }

    private void loadMap(Reader r) throws IOException, JSONException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        JSONObject o = new JSONObject(sb.toString());
        Map<String, Map<String, Integer>> evidence = new HashMap<>();
        Iterator<String> k = o.keys();
        while (k.hasNext()) {
            String name = k.next();
            String bed = norm(name);                          // 表是 {基岩路径: Java 路径}
            String java = javaSide(norm(o.optString(name)));
            if (bed.isEmpty() || java.isEmpty() || !bed.startsWith("textures/")
                    || !java.startsWith("textures/")) {
                continue;
            }
            String[] seg = bed.split("/");
            String[] segJ = java.split("/");
            if (seg.length < 3 || segJ.length < 3) {
                continue;                       // 目录级条目（textures/ → textures/）不是文件规则
            }
            exact.put(java, bed);
            String bd = seg[1];
            bedrockNames.computeIfAbsent(bd, x -> new LinkedHashSet<>())
                    .add(bed.substring(bed.lastIndexOf('/') + 1));
            evidence.computeIfAbsent(segJ[1], x -> new HashMap<>())
                    .merge(bd, 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Integer>> e : evidence.entrySet()) {
            String best = null;
            int bestN = 0;
            int total = 0;
            for (Map.Entry<String, Integer> d : e.getValue().entrySet()) {
                total += d.getValue();
                if (d.getValue() > bestN) {
                    bestN = d.getValue();
                    best = d.getKey();
                }
            }
            if (best != null && total >= 2 && bestN * 5 >= total * 4) {
                dirMap.put(e.getKey(), best);   // 实测一致（如 mob_effect → ui）压过文档表
            }
        }
        for (String[] t : JAVA_RENAMED) {
            exact.put("textures/item/" + t[2], t[0]);      // 新 Java 名也指到同一个基岩文件
        }
        for (String[] p : HUD_PAIRS) {
            String prev = hud.get(p[1]);
            if (prev == null || prev.contains("flash")) {
                hud.put(p[1], p[0]);            // container.png 两条都对，基岩那边取 background
            }
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\\', '/').trim();
    }

    /** 映射表里 Java 那一侧写的是复数目录，得归一成真 Java 路径。 */
    private static String javaSide(String v) {
        String s = v.startsWith("assets/minecraft/") ? v.substring("assets/minecraft/".length()) : v;
        if (s.startsWith("assets/")) {
            int i = s.indexOf("/textures/");
            s = i < 0 ? "" : s.substring(i + 1);
        }
        if (s.startsWith("textures/blocks/")) {
            s = "textures/block/" + s.substring("textures/blocks/".length());
        } else if (s.startsWith("textures/items/")) {
            s = "textures/item/" + s.substring("textures/items/".length());
        }
        return s;
    }

    private Result run(File input, File outMcpack, BedrockPackConverter.ImageAdapter img,
            File tmpRoot, Progress cb) throws Exception {
        Result res = new Result();
        File root = input;
        File unpacked = null;
        String lower = input.getName().toLowerCase(Locale.ROOT);
        if (input.isFile() && (lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".mcpack"))) {
            unpacked = new File(tmpRoot, "unpack_" + System.nanoTime());
            root = unpacked;
            unzip(input, root);
        } else if (!input.isDirectory()) {
            throw new IOException("既不是 .zip 也不是目录：" + input.getAbsolutePath());
        }
        try {
            finish(BedrockPackConverter.resolveRoot(root), outMcpack, img, res, cb);
        } finally {
            if (unpacked != null) {
                deleteTree(unpacked);
            }
        }
        return res;
    }

    private void finish(File root, File outMcpack, BedrockPackConverter.ImageAdapter img,
            Result res, Progress cb) throws Exception {
        Map<String, byte[]> out = new TreeMap<>();
        Map<String, String> srcOf = new HashMap<>();
        List<JSONObject> flips = new ArrayList<>();
        List<File> pngs = new ArrayList<>();
        int done = 0;

        readMcmeta(root, res);
        collectImages(root, pngs);

        // ---- 1. 贴图 + 动画 ----
        for (File p : pngs) {
            String rel = relative(root, p);                        // assets/<ns>/textures/<dir>/…
            String ns = namespaceOf(rel);
            String tail = tailOf(rel);                             // <dir>/<sub>/<name>.png
            if (tail == null) {
                res.texturesSkipped++;
                res.degrade.add(rel + "（不在任何命名空间的 textures/ 下）");
                continue;
            }
            if (!"minecraft".equals(ns)) {
                res.texturesSkipped++;
                res.degrade.add(rel + "（命名空间 " + ns + " —— 基岩贴图全在 textures/ 下，没有命名空间这层）");
                continue;
            }
            Hit hit = mapJavaToBedrock(tail);
            if (hit == null) {
                res.texturesSkipped++;
                res.degrade.add(rel + "（" + dirOf(tail) + "/ 在基岩没有对应目录）");
                continue;
            }
            switch (hit.kind) {
                case HIT_EXACT:
                    res.exactHits++;
                    break;
                case HIT_SAME:
                    res.sameNameHits++;
                    break;
                case HIT_TOKEN:
                    res.tokenHits++;
                    break;
                default:
                    res.directMoves++;
                    break;
            }
            byte[] raw = readAll(p);
            String prev = srcOf.put(hit.bed, rel);      // 旧名/新名两条规则可能指向同一个基岩文件
            if (prev != null) {
                res.degrade.add(hit.bed + " 被两个 Java 文件同时映射（" + prev + " 与 " + rel + "），保留后者");
            }
            String ext = extOf(tail);
            if (!".png".equals(ext)) {
                int[] size = new int[2];
                int[] argb = img == null ? null
                        : (".tga".equals(ext) ? Tga.decode(raw, size) : img.loadArgb(raw, size));
                byte[] enc = argb == null || size[0] <= 0 || size[1] <= 0
                        ? null : img.savePng(argb, size[0], size[1]);
                if (enc == null) {
                    res.texturesSkipped++;
                    res.degrade.add(rel + "（" + ext + " 转 PNG，"
                            + (img == null ? "但没有图像适配器" : "解图失败") + "）");
                    continue;
                }
                out.put(hit.bed, enc);
                res.reencoded++;
                raw = enc;
            } else {
                out.put(hit.bed, raw);
            }
            res.texturesMoved++;

            File mcmeta = new File(p.getAbsolutePath() + ".mcmeta");
            if (mcmeta.isFile()) {
                applyAnimation(read(mcmeta), raw, hit.bed, img, out, flips, res, rel);
            }
            if (cb != null) {
                cb.onStep("贴图", ++done, res.texturesMoved, res.texturesSkipped);
            }
        }
        if (!flips.isEmpty()) {
            out.put("textures/flipbook_textures.json",
                    new JSONArray(flips).toString(1).getBytes(StandardCharsets.UTF_8));
        }

        // ---- 2. 语言 ----
        List<File> langJsons = new ArrayList<>();
        collectLang(root, langJsons);
        Set<String> codes = new LinkedHashSet<>();
        for (File f : langJsons) {
            String code = f.getName().replaceFirst("(?i)\\.json$", "");
            String bedCode = bedrockLangCode(code);
            JSONObject o;
            try {
                o = new JSONObject(new String(readAll(f), StandardCharsets.UTF_8));
            } catch (Throwable t) {
                res.degrade.add(relative(root, f) + "（JSON 解析失败）");
                continue;
            }
            List<String> keys = new ArrayList<>();
            Iterator<String> k = o.keys();
            while (k.hasNext()) {
                keys.add(k.next());
            }
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                String bk = bedrockLangKey(key);
                if (!bk.equals(key)) {
                    res.langKeys++;
                }
                sb.append(bk).append('=').append(o.optString(key)).append('\n');
            }
            out.put("texts/" + bedCode + ".lang", sb.toString().getBytes(StandardCharsets.UTF_8));
            codes.add(bedCode);
            res.langs++;
        }
        if (!codes.isEmpty()) {
            JSONArray a = new JSONArray();
            for (String c : codes) {
                a.put(c);
            }
            out.put("texts/languages.json", a.toString(1).getBytes(StandardCharsets.UTF_8));
        }

        // ---- 3. 声音 ----
        File sj = findNamedFile(root, "sounds.json", 3);
        if (sj != null) {
            try {
                convertSounds(root, sj, out, res);
            } catch (Throwable t) {
                res.degrade.add("sounds.json 解析失败：" + t.getClass().getSimpleName());
            }
        }

        // ---- 4. pack_icon ----
        File icon = findNamedFile(root, "pack.png", 1);
        if (icon != null) {
            out.put("pack_icon.png", readAll(icon));
            res.iconSource = "包内自带 " + relative(root, icon);
        } else {
            for (File p : pngs) {
                String tail = tailOf(relative(root, p));
                if (tail != null && tail.startsWith("block/") && !tail.contains(".mcmeta")
                        && tail.substring("block/".length()).indexOf('/') < 0
                        && ".png".equals(extOf(tail))) {
                    out.put("pack_icon.png", readAll(p));
                    res.iconSource = "包里没有图标，拿 block/" + tail.substring(6) + " 顶上";
                    break;
                }
            }
        }

        // ---- 5. manifest ----
        out.put("manifest.json", buildManifest(res).toString(1).getBytes(StandardCharsets.UTF_8));

        // ---- 6. Java 有而基岩没机制的，逐类点名 ----
        for (String[] d : new String[][]{
                {"models", "方块/物品模型 json（基岩走 blocks/*.json + terrain_texture.json）"},
                {"blockstates", "blockstate 选择器（基岩无对应）"},
                {"shaders", "core shader / post_effect（基岩走 RenderDragon）"},
                {"fonts", "TTF 字体提供器（基岩走 glyphs + texts/lang）"},
                {"textures/overlay", "连接纹理 overlay（基岩走 textures/blocks 同名 + .tga 蒙版）"},
        }) {
            int n = countUnder(root, d[0]);
            if (n > 0) {
                res.degrade.add("assets/**/" + d[0] + "/ " + n + " 个 —— " + d[1]);
            }
        }

        // ---- 7. 写盘 ----
        File parent = outMcpack.getAbsoluteFile().getParentFile();
        mkdirs(parent);
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(outMcpack));
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
            cb.onStep("写包", ++done, res.texturesMoved, res.texturesSkipped);
        }
    }

    // ---------------------------------------------------------------- 映射

    /** 四级匹配：精确表 → 同目录同名 → 同目录 token 重排 → 跨目录；目录无对应返回 null。 */
    Hit mapJavaToBedrock(String tail) {
        String dir = dirOf(tail);
        if (dir == null) {
            return null;
        }
        if (tail.startsWith("gui/sprites/")) {
            String h = hud.get(tail.substring("gui/sprites/".length()));
            if (h != null) {
                return new Hit("textures/ui/" + h, HIT_EXACT);
            }
        }
        String javaNorm = "textures/" + tail;
        String hit = exact.get(javaNorm);
        if (hit != null) {
            return new Hit(hit, HIT_EXACT);
        }
        String bedDir = dirMap.get(dir);
        if (bedDir == null) {
            return null;
        }
        String base = tail.substring(tail.lastIndexOf('/') + 1);
        String rest = tail.substring(dir.length() + 1);           // 目录以下的子路径（entity 里的 pig/pig.png）
        String subDir = rest.substring(0, rest.length() - base.length());
        Set<String> known = bedrockNames.get(bedDir);
        if (known != null && known.contains(base)) {
            return new Hit("textures/" + bedDir + "/" + subDir + base, HIT_SAME);
        }
        String sorted = sortedTokens(base);
        if (known != null) {
            for (String cand : known) {
                if (sortedTokens(cand).equals(sorted)) {
                    return new Hit("textures/" + bedDir + "/" + subDir + cand, HIT_TOKEN);
                }
            }
        }
        return new Hit("textures/" + bedDir + "/" + subDir + base, HIT_MISS);
    }

    /** {@code block/stone.png} → {@code block}。 */
    private static String dirOf(String rel) {
        String s = rel.startsWith("textures/") ? rel.substring("textures/".length()) : rel;
        int i = s.indexOf('/');
        return i <= 0 ? null : s.substring(0, i);
    }

    /** {@code assets/minecraft/textures/…} → {@code …}；不在 textures 下返回 null。 */
    private static String tailOf(String rel) {
        int i = rel.indexOf("/textures/");
        if (i < 0) {
            if (rel.startsWith("textures/") && rel.length() > "textures/".length()) {
                return rel.substring("textures/".length());
            }
            return null;
        }
        String t = rel.substring(i + "/textures/".length());
        return t.isEmpty() ? null : t;
    }

    private static String namespaceOf(String rel) {
        if (!rel.startsWith("assets/")) {
            return "minecraft";
        }
        int i = rel.indexOf('/');
        int j = rel.indexOf('/', i + 1);
        return j < 0 ? "minecraft" : rel.substring(i + 1, j);
    }

    /** {@code a/b/x.TGA} → {@code .tga}；没有扩展名返回 {@code .png}（基岩那边统一按 png 写）。 */
    private static String extOf(String path) {
        int d = path.lastIndexOf('.');
        int s = path.lastIndexOf('/');
        if (d < 0 || d < s) {
            return ".png";
        }
        return path.substring(d).toLowerCase(Locale.ROOT);
    }

    /** 文件名的规范形：去扩展、下划线 token 排序（{@code oak_planks} ≙ {@code planks_oak}）。 */
    private static String sortedTokens(String file) {
        String n = file.toLowerCase(Locale.ROOT);
        int d = n.lastIndexOf('.');
        if (d > 0) {
            n = n.substring(0, d);
        }
        String[] t = n.split("_");
        Arrays.sort(t);
        return String.join("_", t);
    }

    // ---------------------------------------------------------------- 动画

    /**
     * Java 竖条 / 横条 / 网格 + {@code .mcmeta} → 基岩竖条 + {@code flipbook_textures.json} 条目。
     * 基岩侧官方口径：每帧是一个正方形，从顶部第 0 帧往下数；{@code frames} 缺省即全帧按序；
     * {@code ticks_per_frame} 与 Java 的 {@code frametime} 同为「每帧占几个 tick」，直接 1:1。
     */
    private void applyAnimation(String mcmetaText, byte[] pngBytes, String bedPath,
            BedrockPackConverter.ImageAdapter img, Map<String, byte[]> out,
            List<JSONObject> flips, Result res, String rel) {
        JSONObject anim;
        try {
            anim = new JSONObject(BedrockPackConverter.stripJsonComments(mcmetaText))
                    .optJSONObject("animation");
        } catch (Throwable t) {
            res.degrade.add(rel + "（.mcmeta 解析失败）");
            return;
        }
        if (anim == null) {
            List<String> tops = new ArrayList<>();
            try {
                JSONObject all = new JSONObject(BedrockPackConverter.stripJsonComments(mcmetaText));
                Iterator<String> k = all.keys();
                while (k.hasNext()) {
                    tops.add(k.next());
                }
            } catch (Throwable ignored) {
            }
            res.degrade.add(rel + "（.mcmeta 的 " + tops + " 基岩无对应机制）");
            return;
        }
        if (img == null) {
            res.degrade.add(rel + "（有动画但没有图像适配器，帧排不了）");
            return;
        }
        int[] size = new int[2];
        int[] argb = img.loadArgb(pngBytes, size);
        if (argb == null || size[0] <= 0 || size[1] <= 0) {
            res.degrade.add(rel + "（动画源图解不开）");
            return;
        }
        int imgW = size[0];
        int imgH = size[1];
        int sw = anim.optInt("sprite_width", 0);
        int sh = anim.optInt("sprite_height", 0);
        int tileW;
        int tileH;
        if (sw > 0 && sh > 0) {
            tileW = sw;
            tileH = sh;
        } else if (imgH > imgW && imgH % imgW == 0) {
            tileW = imgW;
            tileH = imgW;                 // Java 不写 sprite_* 时按「正方形帧、边长 = 图宽」竖排
        } else if (imgW > imgH && imgW % imgH == 0) {
            tileW = imgH;
            tileH = imgH;                 // 横排兜底（反向转换器的同一条推断）
        } else if (imgW == imgH) {
            return;                       // 单帧方图：本来就没有可动的帧（Java 里 animation:{} 只是占位）
        } else {
            res.degrade.add(rel + "（帧布局推不出：" + imgW + "×" + imgH + "）");
            return;
        }
        final int cols = imgW / tileW;
        final int rows = imgH / tileH;
        if (cols < 1 || rows < 1 || cols * rows < 2) {
            res.degrade.add(rel + "（只有 1 帧，没有可动的东西）");
            return;
        }
        List<Integer> order = new ArrayList<>();
        List<Integer> times = new ArrayList<>();
        JSONArray fr = anim.optJSONArray("frames");
        if (fr != null) {
            for (int i = 0; i < fr.length(); i++) {
                Object x = fr.opt(i);
                if (x instanceof Integer) {
                    order.add((Integer) x);
                } else if (x instanceof JSONObject) {
                    JSONObject xo = (JSONObject) x;
                    order.add(xo.optInt("index", i));
                    times.add(xo.optInt("time", 0));
                }
            }
        }
        for (int idx : order) {
            if (idx < 0 || idx >= cols * rows) {
                res.degrade.add(rel + "（frames 里的 " + idx + " 越界，图里只有 " + (cols * rows) + " 帧）");
                return;
            }
        }
        if (cols > 1) {
            // 横排/网格：Java 的帧号是行优先（col = i % cols，row = i / cols），
            // 基岩只认竖排 → 按 Java 帧号顺序重排成一列，帧号本身保持不变，frames 数组仍可原样写。
            byte[] rebuilt = img.savePng(columnOf(argb, imgW, tileW, tileH, cols, rows), tileW, tileH * rows * cols);
            if (rebuilt == null) {
                res.degrade.add(rel + "（帧重排后编码失败）");
                return;
            }
            out.put(bedPath, rebuilt);
            res.animationsRebuilt++;
        }
        String noExt = bedPath.replaceFirst("(?i)\\.png$", "");
        JSONObject fb = new JSONObject();
        try {
            fb.put("flipbook_texture", noExt);
            fb.put("atlas_tile", noExt.substring(noExt.lastIndexOf('/') + 1));
            int ticks = anim.optInt("frametime", 0);
            if (ticks <= 0 && order.size() == times.size() && !times.isEmpty()) {
                int same = times.get(0);
                boolean uniform = true;
                for (int t : times) {
                    if (t != same) {
                        uniform = false;
                    }
                }
                if (uniform && same > 0) {
                    ticks = same;
                } else {
                    res.degrade.add(rel + "（每帧时长不等，基岩只能统一成 frametime）");
                    ticks = 1;
                }
            }
            if (ticks > 1) {
                fb.put("ticks_per_frame", ticks);
            }
            boolean identity = true;
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i) != i) {
                    identity = false;
                }
            }
            if (!order.isEmpty() && (!identity || order.size() != cols * rows)) {
                fb.put("frames", new JSONArray(order));
            }
            if (anim.optBoolean("interpolate", false)) {
                fb.put("blend_frames", true);
            }
        } catch (Throwable t) {
            res.degrade.add(rel + "（动画条目写不出：" + t.getClass().getSimpleName() + "）");
            return;
        }
        flips.add(fb);
        res.animations++;
        if (!"blocks".equals(dirOf(bedPath)) && !"items".equals(dirOf(bedPath))) {
            res.degrade.add(rel + "（动画条目已写出，但基岩的 flipbook 只驱动方块/物品，这一档会被引擎忽略）");
        }
    }

    /** 任意 cols×rows 网格 → 竖条（行优先帧序）。 */
    private static int[] columnOf(int[] argb, int imgW, int tileW, int tileH, int cols, int rows) {
        int[] out = new int[tileW * tileH * cols * rows];
        for (int i = 0; i < cols * rows; i++) {
            int col = i % cols;
            int row = i / cols;
            int srcY = row * tileH;
            int srcX = col * tileW;
            for (int y = 0; y < tileH; y++) {
                System.arraycopy(argb, (srcY + y) * imgW + srcX, out, (i * tileH + y) * tileW, tileW);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 语言 / 声音 / manifest

    /** 基岩语言代号：{@code en_us → en_US}（基岩用大写地区段）。 */
    static String bedrockLangCode(String code) {
        String[] parts = code.split("_");
        if (parts.length == 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
        }
        return code;
    }

    /**
     * Java lang 键 → 基岩 lang 键，是 {@link BedrockPackConverter#javaLangKey} 的反向：
     * {@code block.minecraft.x → tile.x.name} 等五类，认不出的原样保留。
     */
    static String bedrockLangKey(String k) {
        String[] pairs = {
                "block.minecraft.", "tile.", ".name",
                "item.minecraft.", "item.", ".name",
                "entity.minecraft.", "entity.", ".name",
                "effect.minecraft.", "effect.", ".name",
                "enchantment.minecraft.", "enchantment.", ".name",
        };
        for (int i = 0; i < pairs.length; i += 3) {
            String from = pairs[i];
            if (k.startsWith(from) && k.length() > from.length()) {
                return pairs[i + 1] + k.substring(from.length()) + pairs[i + 2];
            }
        }
        return k;
    }

    private void convertSounds(File root, File soundsJson, Map<String, byte[]> out, Result res)
            throws JSONException, IOException {
        JSONObject java = new JSONObject(new String(readAll(soundsJson), StandardCharsets.UTF_8));
        JSONObject defs = new JSONObject();
        List<String> keys = new ArrayList<>();
        Iterator<String> k = java.keys();
        while (k.hasNext()) {
            keys.add(k.next());
        }
        Collections.sort(keys);
        for (String key : keys) {
            Object raw = java.opt(key);
            JSONObject ev = raw instanceof JSONObject ? (JSONObject) raw : null;
            if (ev == null) {
                continue;
            }
            JSONArray list = ev.optJSONArray("sounds");
            if (list == null) {
                continue;
            }
            JSONArray bedrock = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                Object o = list.opt(i);
                String path;
                JSONObject one = null;
                if (o instanceof String) {
                    path = (String) o;
                } else if (o instanceof JSONObject) {
                    one = (JSONObject) o;
                    path = one.optString("name", "");
                } else {
                    continue;
                }
                path = path.replace("minecraft:", "").replace('\\', '/');
                while (path.startsWith("/")) {
                    path = path.substring(1);
                }
                if (path.isEmpty()) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("name", path.startsWith("sounds/") ? path : "sounds/" + path);
                if (one != null) {
                    if (one.has("volume")) {
                        item.put("volume", one.optDouble("volume", 1.0));
                    }
                    if (one.has("pitch")) {
                        item.put("pitch", one.optDouble("pitch", 1.0));
                    }
                    if (one.has("weight")) {
                        item.put("weight", one.optInt("weight", 1));
                    }
                }
                bedrock.put(item);
            }
            if (bedrock.length() == 0) {
                continue;
            }
            JSONObject d = new JSONObject();
            d.put("category", ev.optString("category", "neutral"));
            d.put("min_range", 1);
            d.put("max_range", 13);
            d.put("sounds", bedrock);
            defs.put(key, d);
            res.soundsDefs++;
        }
        if (res.soundsDefs == 0) {
            return;
        }
        JSONObject wrap = new JSONObject();
        wrap.put("format_version", 1);
        wrap.put("sound_definitions", defs);
        out.put("sounds/sound_definitions.json", wrap.toString(1).getBytes(StandardCharsets.UTF_8));
        File audioDir = new File(new File(new File(root, "assets"), "minecraft"), "sounds");
        List<File> audio = new ArrayList<>();
        collectUnder(audioDir, audio, new String[]{".ogg", ".wav", ".mp3", ".flac"});
        String base = canon(audioDir).getPath();
        for (File a : audio) {
            String rel = canon(a).getPath().substring(base.length());
            while (rel.startsWith(File.separator)) {
                rel = rel.substring(File.separator.length());
            }
            out.put("sounds/" + rel.replace('\\', '/'), readAll(a));
            res.soundsFiles++;
        }
    }

    private static JSONObject buildManifest(Result res) throws JSONException {
        String uuidH = UUID.randomUUID().toString();
        String uuidM = UUID.randomUUID().toString();
        JSONArray ver = new JSONArray().put(1).put(0).put(0);
        JSONObject header = new JSONObject();
        header.put("name", res.packName.isEmpty() ? "Converted from Java" : res.packName);
        header.put("description", res.packDesc);
        header.put("uuid", uuidH);
        header.put("version", ver);
        header.put("min_engine_version", new JSONArray().put(1).put(21).put(0));
        JSONObject module = new JSONObject();
        module.put("type", "resources");
        module.put("description", res.packDesc);
        module.put("uuid", uuidM);
        module.put("version", new JSONArray().put(1).put(0).put(0));
        JSONObject manifest = new JSONObject();
        manifest.put("format_version", 2);
        manifest.put("header", header);
        manifest.put("modules", new JSONArray().put(module));
        return manifest;
    }

    private void readMcmeta(File root, Result res) {
        File f = new File(root, "pack.mcmeta");
        if (!f.isFile()) {
            return;
        }
        try {
            JSONObject p = new JSONObject(BedrockPackConverter.stripJsonComments(read(f)))
                    .optJSONObject("pack");
            if (p == null) {
                return;
            }
            res.packName = flatten(p.opt("description"));
            res.packDesc = (res.packName.isEmpty() ? "Converted from Java" : res.packName)
                    + "（由 Java 版转换）";
            int fmt = p.optInt("pack_format", 0);
            JSONObject supported = p.optJSONObject("supported_formats");
            if (fmt == 0 && supported != null) {
                fmt = supported.optInt("max_format", 0);
            }
            if (fmt > 0) {
                res.javaPackFormat = String.valueOf(fmt);
            }
        } catch (Throwable ignored) {
        }
    }

    /** {@code description} 可以是字符串，也可以是 {text:...} / [..] 组件 —— 拉平成一行的显示文本。 */
    private static String flatten(Object o) {
        if (o == null || o == JSONObject.NULL) {
            return "";
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                sb.append(flatten(a.opt(i)));
            }
            return sb.toString();
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String t = j.optString("text", "");
            if (!t.isEmpty()) {
                return t;
            }
            return flatten(j.opt("extra"));
        }
        return String.valueOf(o);
    }

    // ---------------------------------------------------------------- 收集与 IO

    private static void collectImages(File root, List<File> out) {
        File assets = new File(root, "assets");
        File[] ns = assets.isDirectory() ? assets.listFiles() : null;
        if (ns != null) {
            for (File n : ns) {
                File tex = new File(n, "textures");
                if (tex.isDirectory()) {
                    collectUnder(tex, out, new String[]{".png", ".jpg", ".jpeg", ".tga"});
                }
            }
        }
        Collections.sort(out, (a, b) -> canon(a).getPath().compareTo(canon(b).getPath()));
    }

    private static void collectLang(File root, List<File> out) {
        File assets = new File(root, "assets");
        File[] ns = assets.isDirectory() ? assets.listFiles() : null;
        if (ns == null) {
            return;
        }
        for (File n : ns) {
            File lang = new File(n, "lang");
            File[] kids = lang.isDirectory() ? lang.listFiles() : null;
            if (kids == null) {
                continue;
            }
            for (File f : kids) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
    }

    private static void collectUnder(File dir, List<File> out, String[] suffixes) {
        File[] kids = dir.isDirectory() ? dir.listFiles() : null;
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collectUnder(f, out, suffixes);
            } else if (f.isFile()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                for (String s : suffixes) {
                    if (n.endsWith(s)) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
    }

    /** root 下（限深）统计某个相对目录段里有多少文件，给降级清单用。 */
    private static int countUnder(File root, String relDir) {
        String[] segs = relDir.split("/");
        File assets = new File(root, "assets");
        File[] ns = assets.isDirectory() ? assets.listFiles() : null;
        int n = 0;
        if (ns != null) {
            for (File each : ns) {
                File cur = each;
                for (String s : segs) {
                    cur = new File(cur, s);
                }
                List<File> hit = new ArrayList<>();
                collectUnder(cur, hit, new String[]{""});
                n += hit.size();
            }
        }
        return n;
    }

    private static File findNamedFile(File dir, String name, int maxDepth) {
        return maxDepth < 0 ? null : findNamed(dir, name, 0, maxDepth);
    }

    private static File findNamed(File dir, String name, int depth, int maxDepth) {
        if (!dir.isDirectory()) {
            return null;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return null;
        }
        for (File f : kids) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        if (depth >= maxDepth) {
            return null;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                File hit = findNamed(f, name, depth + 1, maxDepth);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void unzip(File zip, File dest) throws IOException {
        mkdirs(dest);
        String canonicalDest = dest.getCanonicalPath() + File.separator;
        InputStream in = new FileInputStream(zip);
        ZipInputStream zin = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            File t = new File(dest, e.getName()).getCanonicalFile();
            if (!t.getPath().startsWith(canonicalDest)) {
                continue;                       // zip slip
            }
            if (e.isDirectory()) {
                mkdirs(t);
            } else {
                mkdirs(t.getParentFile());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                FileOutputStream fo = new FileOutputStream(t);
                fo.write(bo.toByteArray());
                fo.close();
            }
            zin.closeEntry();
        }
        zin.close();
        in.close();
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
