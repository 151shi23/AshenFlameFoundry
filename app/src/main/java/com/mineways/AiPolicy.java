package com.mineways;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 这一层的<b>治理策略</b>：哪些工具开放、哪些功能可被 AI 提及、哪些入口显示、
 * 提示词怎么拼、最多走几步、温度与长度上限 —— 全部<b>每次现读云控</b>，不在编译期写死。
 *
 * <p>故意不 import 任何 Android 类型：云控读取方通过 {@link Flags} 注进来，
 * 这样这一层能在 JVM 上用 {@code AiPolicyCheck} 直接断言（这台机器编不了 APK 之外的东西，
 * 能测的先测，别等真机）。
 *
 * <p>云控开关（{@code FLAG key=… to=…}，全部可选，不写就走客户端默认）：
 * <ul>
 *   <li>{@code ai_tools_off}　逗号分隔，<b>废掉</b>某个工具：{@code open_site,app_info}</li>
 *   <li>{@code ai_tools_only}　只留这些（白名单模式，优先级高于 off）</li>
 *   <li>{@code ai_features_off}　从 AI 的功能清单里摘掉某些功能（按功能名，逗号分隔）</li>
 *   <li>{@code ai_entries_off}　废掉某个「问 AI」入口：{@code card}（卡片长按）/
 *       {@code search}（搜索没命中的兜底）。<b>功能页顶栏与右上角那两个入口已经删了</b>
 *       （用户判定：挡空间又没用），所以 {@code page} 这个名字现在写了也不起作用。</li>
 *   <li>{@code ai_system}　整段替换默认提示词（写空串 = 干脆不发 system）</li>
 *   <li>{@code ai_system_extra}　在默认提示词后<b>追加</b>规则（保留自动生成的功能清单，日常改口径用这个）</li>
 *   <li>{@code ai_hops}　一条问题最多走几步（默认 3，钳 1~8）</li>
 *   <li>{@code ai_temp} / {@code ai_max_tokens}　采样温度与回答上限（这里先钳到服务端允许区间）</li>
 * </ul>
 */
public final class AiPolicy {

    /** 云控读取口（Android 侧传 {@code AiAccess::flag} 那种实现）。 */
    public interface Flags {
        String get(String key, String def);
    }

    private AiPolicy() {
    }

    /** 工具目录里的一行：名字 + 给模型看的说明。 */
    public static final class Tool {
        public final String name;
        public final String desc;

        Tool(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }
    }

    public static final String T_OPEN = "open";
    public static final String T_SEARCH = "search_tools";
    public static final String T_LIST = "list_tools";
    public static final String T_INFO = "app_info";
    public static final String T_SITE = "open_site";
    public static final String T_DESCRIBE = "describe";
    public static final String T_PAGE_ASK = "ask_page";
    public static final String T_OPS = "ops";
    public static final String T_PICK = "pick";

    /** {@code ops} 里可直接执行的作业名（{@code ai_ops_off} 逐个废）。 */
    public static final String OP_PACK = "pack";
    public static final String OP_GIF = "gif";
    public static final String OP_EXPR = "expr";

    /** 「问 AI」入口的名字（{@code ai_entries_off} 用）。 */
    public static final String E_CARD = "card";
    public static final String E_SEARCH = "search";

    public static List<Tool> allTools() {
        return Arrays.asList(
                new Tool(T_OPEN, "open(name[,input]) 打开某个功能页；input 是一段文字或本地文件路径，"
                        + "只有支持接收内容的页面会用它，不确定就别传"),
                new Tool(T_DESCRIBE, "describe(name) 看某个功能能干什么、要不要激活码、能不能接收内容"),
                new Tool(T_SEARCH, "search_tools(keyword) 按关键词找功能"),
                new Tool(T_LIST, "list_tools() 列出全部功能与分类"),
                new Tool(T_SITE, "open_site(name) 打开首页收录的第三方站点"),
                new Tool(T_INFO, "app_info() 看版本、运行环境、AI 云控生效情况"),
                new Tool(T_PAGE_ASK, "ask_page(question) 读当前这一屏上显示的文字再回答（现在只有首页）"),
                new Tool(T_OPS, "ops(op,args) 不开界面直接把活干完并写出产物："
                        + "op=pack 转材质包（args.path，可选 args.to=to-bedrock|to-java，"
                        + "产物进 Download/材质包转换/）；op=gif 竖条图集转动图（args.path）；"
                        + "op=expr 查条件表达式语法（args.text）"),
                new Tool(T_PICK, "pick(what) 让用户在手机上选一个文件，选完 pack / gif 会自动接上；"
                        + "用户说不清路径时别追问，用这个"));
    }

    // ---------------------------------------------------------------- 可执行作业

    /** 全部可执行作业名。 */
    public static List<String> allOps() {
        return Arrays.asList(OP_PACK, OP_GIF, OP_EXPR);
    }

    /** 某个作业是否允许执行：{@code ops} 工具本身没被废，且没被 {@code ai_ops_off} 点名。 */
    public static boolean opAllowed(Flags f, String op) {
        if (op == null) {
            return false;
        }
        String key = op.toLowerCase(Locale.ROOT);
        if (!toolAllowed(f, T_OPS)) {
            return false;
        }
        return !hit(names(f.get("ai_ops_off", "")), key);
    }

    /** 提示词里那句「现在能直接干什么」，按当前开放集临时拼。 */
    public static String opLines(Flags f) {
        StringBuilder sb = new StringBuilder();
        for (String o : allOps()) {
            if (opAllowed(f, o)) {
                sb.append(sb.length() == 0 ? "现在能直接干的活：" : "、").append(o);
            }
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    /**
     * 「没配」的哨兵值：{@link com.mineways.cloud.FeatureGate#flag} 在 key 缺失时原样返回默认值，
     * 配了但留空则返回空串 —— 用它区分两者，才能表达「云控明确写了空的 {@code ai_system}
     * = 干脆不发内置人设」。<b>必须全是可见字符</b>：{@code AiAccess.flag()} 会 trim()，
     * 控制字符会被削成空串，哨兵就失效了。
     */
    public static final String ABSENT = "<<absent>>";

    /**
     * 云控名单。逗号/分号分隔为主（功能名里带空格必须保住），
     * 同时按空白再切一遍，好让 {@code open_site app_info} 这种手写写法也生效。
     */
    public static Set<String> names(String raw) {
        Set<String> out = new LinkedHashSet<>();
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() == 0) {
            return out;
        }
        for (String p : s.split("[,，;；\n]+")) {
            add(out, p);
        }
        for (String p : s.split("[,，;；\\s\n]+")) {
            add(out, p);
        }
        return out;
    }

    private static void add(Set<String> out, String p) {
        String t = p.trim();
        if (t.length() > 0) {
            out.add(t);
        }
    }

    /** 名单里是否点了这个名字（忽略空格差异，方便写「Java 材质包转基岩」这种带空格的功能名）。 */
    private static boolean hit(Set<String> list, String name) {
        if (list.isEmpty() || name == null) {
            return false;
        }
        String bare = name.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String o : list) {
            if (o.replace(" ", "").equals(bare)) {
                return true;
            }
        }
        return false;
    }

    /** 工具是否开放：{@code ai_tools_only} 优先，其次 {@code ai_tools_off}。 */
    public static boolean toolAllowed(Flags f, String tool) {
        if (tool == null) {
            return false;
        }
        String key = tool.toLowerCase(Locale.ROOT);
        Set<String> only = names(f.get("ai_tools_only", ""));
        if (!only.isEmpty()) {
            return hit(only, key);
        }
        return !hit(names(f.get("ai_tools_off", "")), key);
    }

    public static List<Tool> allowedTools(Flags f) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : allTools()) {
            if (toolAllowed(f, t.name)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 某个功能要不要对 AI 曝光（{@code ai_features_off} 按功能名）。 */
    public static boolean featureVisible(Flags f, String title) {
        return !hit(names(f.get("ai_features_off", "")), title);
    }

    /** 某个「问 AI」入口是否显示（默认全开，{@code ai_entries_off} 逐个废掉）。 */
    public static boolean entryAllowed(Flags f, String entry) {
        return !hit(names(f.get("ai_entries_off", "")), entry);
    }

    /*
     * 功能页顶栏那个「问 AI」入口已经删干净了（用户判定：挡空间又没用），
     * 连它的 pageAsk 判定也一起删 —— 不留恒真开关空转，同 ui_backdisc 的下场。
     */

    public static int hops(Flags f) {
        return clamp(intOf(f.get("ai_hops", ""), 3), 1, 8);
    }

    /** 回答末尾那行「下一步建议」要不要让模型写（{@code ai_suggest=0} 关掉）。 */
    public static boolean suggest(Flags f) {
        return !"0".equals(trim(f.get("ai_suggest", "")));
    }

    /**
     * 功能清单塞多少进提示词（{@code ai_list}）：整段有 4000 字上限，工具越多清单越容易被挤掉，
     * 所以这一档<b>明着管</b>而不是靠运气。
     * <ul>
     *   <li>{@code full}（默认）全量带空格原名；</li>
     *   <li>{@code short} 压掉空格省约三成；</li>
     *   <li>{@code ask} 完全不塞，让模型用 list_tools / search_tools 现查（工具多的时候留额度给它们）。</li>
     * </ul>
     */
    public static String listMode(Flags f) {
        String v = trim(f.get("ai_list", "")).toLowerCase(Locale.ROOT);
        return v.length() == 0 ? "full" : v;
    }

    public static double temperature(Flags f) {
        double v = 0.7;
        String raw = f.get("ai_temp", "").trim();
        if (raw.length() > 0) {
            try {
                v = Double.parseDouble(raw);
            } catch (Throwable ignored) {
                v = 0.7;
            }
        }
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }

    public static int maxTokens(Flags f) {
        return clamp(intOf(f.get("ai_max_tokens", ""), 2000), 100, 8000);
    }

    private static int intOf(String raw, int def) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 提示词里「可用工具」那一段，按当前开放集生成 —— 废掉的工具模型连名字都看不到。 */
    public static String toolLines(Flags f) {
        StringBuilder sb = new StringBuilder();
        List<Tool> ok = allowedTools(f);
        for (Tool t : ok) {
            sb.append("\n- ").append(t.desc);
        }
        return sb.toString();
    }

    /**
     * 本轮实际发给服务端的 system。
     *
     * <p>{@code ai_system} <b>配了</b>（哪怕留空）→ 整段替换：留空 = 干脆不发内置人设；
     * 没配 → 用客户端 {@code builtin}（里面带自动生成的功能清单）。
     * {@code ai_system_extra} 永远追加在最后，并明确标注优先级更高 —— 改口径不必抄一遍长提示词。
     */
    public static String system(Flags f, String builtin) {
        String over = f.get("ai_system", ABSENT);
        String base = ABSENT.equals(over) ? trim(builtin) : trim(over);
        String extra = trim(f.get("ai_system_extra", ""));
        if (extra.length() > 0) {
            base = base.length() == 0
                    ? "补充规则（云控下发）：" + extra
                    : base + "\n\n补充规则（云控下发，优先级高于上面的写法）：" + extra;
        }
        // 服务端 4000 字截断（文档 §4.1），自己先留 100 字余量
        return base.length() > 3900 ? base.substring(0, 3900) : base;
    }

    /** 被云控关掉的东西，给界面与 app_info 显示，别让人猜。 */
    public static String stateNote(Flags f) {
        StringBuilder sb = new StringBuilder();
        List<String> off = new ArrayList<>();
        for (Tool t : allTools()) {
            if (!toolAllowed(f, t.name)) {
                off.add(t.name);
            }
        }
        if (!off.isEmpty()) {
            sb.append("工具已停用 ").append(String.join("/", off)).append("。");
        }
        List<String> ops = new ArrayList<>();
        for (String o : allOps()) {
            if (toolAllowed(f, T_OPS) && !opAllowed(f, o)) {
                ops.add(o);
            }
        }
        if (!ops.isEmpty()) {
            sb.append("可直接执行的活已停用 ").append(String.join("/", ops)).append("。");
        }
        List<String> entries = new ArrayList<>();
        for (String e : Arrays.asList(E_CARD, E_SEARCH)) {
            if (!entryAllowed(f, e)) {
                entries.add(e);
            }
        }
        if (!entries.isEmpty()) {
            sb.append("入口已停用 ").append(String.join("/", entries)).append("。");
        }
        String fo = trim(f.get("ai_features_off", ""));
        if (fo.length() > 0) {
            sb.append("功能不对 AI 曝光 ").append(fo).append("。");
        }
        String sys = f.get("ai_system", ABSENT);
        if (sys != null && !ABSENT.equals(sys)) {
            sb.append(trim(sys).length() > 0 ? "提示词已整段替换。" : "内置提示词已被云控清空。");
        }
        if (trim(f.get("ai_system_extra", "")).length() > 0) {
            sb.append("提示词已追加规则。");
        }
        return sb.length() == 0 ? "无（全部按客户端默认）" : sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
