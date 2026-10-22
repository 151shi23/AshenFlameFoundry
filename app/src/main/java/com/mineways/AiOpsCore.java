package com.mineways;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * AI「干活工具」的<b>判定层</b>：给一个路径，决定该往哪个方向转、产物叫什么名字、能不能转。
 *
 * <p>故意不 import 任何 Android 类型（与 {@link AiPolicy} 同一套想法）：这一层的每条口径都能在
 * JVM 上用 {@code AiOpsCoreCheck} 直接断言。真机编不出来之前，先把能测的测干净 ——
 * 转换本身已经由 {@code PackConvertActivity} 在真机上跑通，这里只负责<b>别把参数判错</b>。
 *
 * <p>三条铁律：
 * <ol>
 *   <li><b>判不准就说不准</b>：目录里没有 {@code pack.assets} / {@code textures} 也没有
 *       {@code pack.mcmeta}，就回「看不出是材质包」并列出顶层，而不是猜个方向硬转；</li>
 *   <li><b>不覆盖</b>：产物撞名一律加 {@code _2} {@code _3}，用户的旧产物被 AI 悄悄吃掉是不行的；</li>
 *   <li><b>只写 Download/&lt;功能&gt;/</b>：源目录永不动，这一层连写文件都不做，只出方案。</li>
 * </ol>
 */
public final class AiOpsCore {

    /** 与转换页同一道上限（320 MB），超了别白跑一趟。 */
    public static final long MAX_BYTES = 320L * 1024L * 1024L;

    public static final String TO_BEDROCK = "to-bedrock";
    public static final String TO_JAVA = "to-java";
    public static final String AUTO = "auto";

    private AiOpsCore() {
    }

    /** 一次可执行的方案；{@link Plan#error} 非空就是不能干，理由已经写给模型看。 */
    public static final class Plan {
        public final File input;
        public final String direction;
        public final String outName;
        public final File outDir;
        public final String why;

        Plan(File input, String direction, String outName, File outDir, String why) {
            this.input = input;
            this.direction = direction;
            this.outName = outName;
            this.outDir = outDir;
            this.why = why == null ? "" : why;
        }

        public boolean ok() {
            return error == null;
        }

        /** 不能干的原因（可空）。 */
        public String error;
    }

    /** 路径归一化：容忍 `file://`、引号、结尾分隔符（模型很爱写这些）。 */
    public static File resolve(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            return null;
        }
        s = s.replace("`", "").replace("\"", "").replace("'", "");
        if (s.startsWith("file://")) {
            s = s.substring("file://".length());
        }
        while (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            File f = new File(s);
            return f.exists() && f.canRead() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 顶层像不像一个材质包（Java 侧看 pack.mcmeta / assets，基岩侧看 pack.assets / textures）。 */
    public static String looksLike(File dir) {
        if (dir == null) {
            return "";
        }
        if (dir.isFile()) {
            String n = dir.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".mcpack")) {
                return TO_BEDROCK;
            }
            if (n.endsWith(".zip")) {
                return "zip";
            }
            return "";
        }
        boolean javaish = new File(dir, "pack.mcmeta").isFile()
                || new File(dir, "assets").isDirectory();
        boolean bedrockish = new File(dir, "pack.assets").isFile()
                || new File(dir, "textures").isDirectory();
        if (javaish && bedrockish) {
            return "both";
        }
        if (javaish) {
            return TO_JAVA;
        }
        if (bedrockish) {
            return TO_BEDROCK;
        }
        return "";
    }

    /**
     * 定方向：显式优先；没说就按包体特征推，推不出来就<b>明说推不出来</b>。
     *
     * @param want {@link #TO_BEDROCK} / {@link #TO_JAVA} / {@link #AUTO}
     */
    public static String direction(File input, String want) {
        String w = want == null ? AUTO : want.trim().toLowerCase(Locale.ROOT);
        // 模型会写 to-bedrock / bedrock / j2b / java2bedrock / 转成基岩 —— 一律收到同一个方向
        String t = w.startsWith("to-") ? w.substring(3) : w;
        if (t.contains("2bedrock") || t.contains("2mcpack") || t.startsWith("j2b")) {
            return TO_BEDROCK;
        }
        if (t.contains("2java") || t.startsWith("b2j")) {
            return TO_JAVA;
        }
        if (t.equals("bedrock") || t.equals("mcpack") || t.equals("基岩") || t.equals("基岩版")) {
            return TO_BEDROCK;
        }
        if (t.equals("java") || t.equals("java版") || t.equals("资源包")) {
            return TO_JAVA;
        }
        String k = looksLike(input);
        if (TO_BEDROCK.equals(k)) {
            return TO_JAVA;            // 基岩包 → 转成 Java
        }
        if (TO_JAVA.equals(k)) {
            return TO_BEDROCK;         // Java 包 → 转成基岩
        }
        // 两种都像、裸 zip、或者根本看不出来：不猜
        return "";
    }

    /**
     * 出方案：输入、方向、产物名（含撞名避让）、产物目录、以及一句「为什么这么判」。
     *
     * @param dlRoot 公共 Download 目录（Android 侧传 {@code Environment.getExternalStoragePublicDirectory}）
     * @param relDir Download 下的子目录名（沿用转换页的「材质包转换」）
     */
    public static Plan planPack(File input, String want, File dlRoot, String relDir) {
        if (input == null) {
            return fail("没给路径，或者这个路径读不到（AI 不能凭空造路径，要用户整段贴过来）");
        }
        if (input.isDirectory() && looksLike(input).length() == 0) {
            return fail("这个目录看不出是材质包：顶层是 " + top(input)
                    + "；请给到包根目录那一层");
        }
        String dir = direction(input, want);
        if (dir.length() == 0) {
            String k = looksLike(input);
            return fail("说不出该往哪边转（" + ("both".equals(k) ? "目录里两种特征都有"
                    : "裸 zip 看不出内层") + "），请明确写 to-bedrock 还是 to-java");
        }
        long size = input.isFile() ? input.length() : walk(input);
        if (size > MAX_BYTES) {
            return fail("包太大（" + human(size) + "），这一版上限 " + human(MAX_BYTES));
        }
        if (dlRoot == null || relDir == null) {
            return fail("没给输出目录");
        }
        File outDir = new File(dlRoot, relDir);
        mkdir(outDir);              // 不建目录的话撞名检测会瞎掉
        String base = input.getName().replaceFirst("(?i)\\.(mcpack|zip)$", "");
        String ext = TO_BEDROCK.equals(dir) ? "_bedrock.mcpack" : "_java.zip";
        String name = unique(outDir, base + ext);
        Plan p = new Plan(input, dir, name, outDir,
                (TO_BEDROCK.equals(dir) ? "Java → 基岩" : "基岩 → Java")
                        + "，输入 " + human(size));
        return p;
    }

    private static Plan fail(String why) {
        Plan p = new Plan(null, "", null, null, why);
        p.error = why;
        return p;
    }

    /** 撞名避让： {@code x.zip} 已存在就 {@code x_2.zip}，最多试 50 个，绝不覆盖。 */
    public static String unique(File dir, String name) {
        if (dir == null || !dir.isDirectory()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        if (!new File(dir, name).exists()) {
            return name;
        }
        for (int i = 2; i <= 50; i++) {
            String cand = stem + "_" + i + tail;
            if (!new File(dir, cand).exists()) {
                return cand;
            }
        }
        return stem + "_" + System.currentTimeMillis() + tail;
    }

    /** 目录大小（走一遍，深度与条目都设上限，别为一个坏路径卡死）。 */
    public static long walk(File dir) {
        long[] sum = {0L};
        int[] seen = {0};
        walkInto(dir, sum, seen, 0);
        return sum[0];
    }

    private static void walkInto(File f, long[] sum, int[] seen, int depth) {
        if (f == null || depth > 12 || seen[0] > 20000) {
            return;
        }
        if (f.isFile()) {
            sum[0] += f.length();
            seen[0]++;
            return;
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            seen[0]++;
            walkInto(k, sum, seen, depth + 1);
        }
    }

    private static String top(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) {
            return "（空的）";
        }
        List<String> names = new ArrayList<>();
        Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (int i = 0; i < kids.length && i < 8; i++) {
            names.add(kids[i].getName() + (kids[i].isDirectory() ? "/" : ""));
        }
        if (kids.length > 8) {
            names.add("…共 " + kids.length + " 项");
        }
        return String.join("、", names);
    }

    /** 给模型看的一句人话体积。 */
    public static String human(long bytes) {
        if (bytes >= 1048576) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576d);
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        }
        return bytes + " B";
    }

    // ---------------------------------------------------------------- 其它 ops 的纯判定

    /** {@code expr_check}：文本要不要收、收进来先归一化。 */
    public static String textArg(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            return "";
        }
        // 模型常把 JSON 包在 ``` 里，先剥掉围栏
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        return s;
    }

    /** 多长就要提醒用户/模型：语法检查器不是给整个模组用的。 */
    public static final int MAX_TEXT = 20000;

    /** {@code frames_gif}：竖条图集能出 GIF 吗 —— 只认 PNG 序列或竖条图，判不了就不开工。 */
    public static Plan planFrames(File input, int frameH, File dlRoot, String relDir) {
        if (input == null || !input.isFile()) {
            Plan p = new Plan(null, "", null, null, "gif 工具要的是一个图片文件路径");
            p.error = "gif 工具要的是一个图片文件路径";
            return p;
        }
        String n = input.getName().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".png") && !n.endsWith(".jpg") && !n.endsWith(".jpeg")) {
            Plan p = new Plan(null, "", null, null, "只吃 png/jpg 图集，收到的是 " + n);
            p.error = "只吃 png/jpg 图集，收到的是 " + n;
            return p;
        }
        int fh = frameH <= 0 ? 0 : frameH;
        File outDir = new File(dlRoot, relDir);
        mkdir(outDir);
        Plan p = new Plan(input, "frames", unique(outDir,
                input.getName().replaceFirst("(?i)\\.(png|jpe?g)$", "") + ".gif"), outDir,
                fh <= 0 ? "按图片高度自动分帧" : "每帧高 " + fh + " px");
        return p;
    }

    private static void mkdir(File f) {
        if (f != null && !f.isDirectory()) {
            f.mkdirs();
        }
    }
}
