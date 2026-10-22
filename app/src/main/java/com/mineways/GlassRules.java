package com.mineways;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 液态玻璃的<b>按机型策略</b>：规则怎么解析、命中之后该走哪条路。
 *
 * <p>纯 Java、零 Android 依赖，能在 JVM 上断言（{@code GlassRulesCheck}）。
 * "K70 强制降低、K60 强制关闭、K80 询问用户"这种事，判错一次就会让一部分人永久看不到玻璃
 * 或者永久被弹窗打扰，所以先在这儿测干净。
 *
 * <p>云控写法（一条 FLAG，逗号分隔，<b>先匹配到的生效</b>）：
 * <pre>
 * FLAG key=ui_glass_models to=K70*:low,K60*:off,K80*:ask
 * </pre>
 * 动作可以写中文：{@code 降低 / 关闭 / 询问 / 保持}。名单里的模式支持 {@code *} 通配，
 * 不写通配时"等于或包含"都算命中（{@code K70} 能命中 {@code Redmi K70 Pro}）。
 * 匹配对象是 {@code DeviceId.names()} —— 内部型号与设置里显示的名字<b>任一命中即生效</b>。
 */
public final class GlassRules {

    /** 小幅度降低：去色散、去重力高光、去错峰入场，折射与模糊保持。 */
    public static final String LOW = "low";
    /**
     * 大幅度降低：在小幅度之上再<b>关折射、模糊减半、关流光与动效</b>，
     * 只剩一层薄薄的毛玻璃底。与 {@link #OFF} 的区别是这里还留着模糊。
     */
    public static final String DEEP = "deep";
    /** 强制关闭液态玻璃（连 RenderEffect 都不用，退回半透底 + 描边）。 */
    public static final String OFF = "off";
    /** 询问用户要不要降低。 */
    public static final String ASK = "ask";
    /** 明确不动。 */
    public static final String KEEP = "keep";

    /** 全部合法动作（顺序用于诊断输出）。 */
    public static List<String> actions() {
        return Arrays.asList(LOW, DEEP, OFF, ASK, KEEP);
    }

    private GlassRules() {
    }

    /** 一条规则：机型模式 + 动作。 */
    public static final class Rule {
        public final String pattern;
        public final String action;

        Rule(String pattern, String action) {
            this.pattern = pattern;
            this.action = action;
        }

        @Override
        public String toString() {
            return pattern + ":" + action;
        }
    }

    /** 动作归一（含中文写法）；认不出来的按 {@link #KEEP} 处理，绝不当成关闭。 */
    public static String action(String raw) {
        String a = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (a.length() == 0) {
            return "";
        }
        // 大幅度必须排在小幅度前面判：「大幅降低」里也含「降低」
        if (a.equals("deep") || a.equals("heavy") || a.equals("very-low") || a.equals("verylow")
                || a.equals("max") || a.startsWith("low-") || a.contains("大幅") || a.contains("明显")
                || a.contains("重度") || a.contains("强制大幅")) {
            return DEEP;
        }
        if (a.equals("low") || a.equals("down") || a.equals("lite") || a.equals("light")
                || a.equals("mild") || a.startsWith("reduce") || a.contains("降低")
                || a.contains("降级") || a.contains("减少") || a.contains("小幅") || a.contains("轻微")) {
            return LOW;
        }
        if (a.equals("off") || a.equals("close") || a.equals("disable") || a.equals("flat")
                || a.contains("关闭") || a.contains("关掉") || a.contains("禁用")) {
            return OFF;
        }
        if (a.equals("ask") || a.equals("confirm") || a.equals("query") || a.equals("prompt")
                || a.contains("询问") || a.contains("问询") || a.contains("提示用户")
                || a.contains("自行选择") || a.contains("让用户选")) {
            return ASK;
        }
        if (a.equals("keep") || a.equals("on") || a.equals("full") || a.equals("high")
                || a.contains("保持") || a.contains("不变") || a.contains("不降")) {
            return KEEP;
        }
        return KEEP;
    }

    /**
     * 解析规则表。支持两种写法混用：
     * {@code K70*:low}（冒号）与 {@code K70=off}（等号）。
     * 只写机型不写动作（{@code K60}）按 {@link #OFF} 理解 —— 名单里点了名，最合理的意图就是关掉。
     */
    public static List<Rule> parse(String spec) {
        List<Rule> out = new ArrayList<>();
        String s = spec == null ? "" : spec.trim();
        if (s.length() == 0) {
            return out;
        }
        for (String part : s.split("[,，;；\n]+")) {
            String p = part.trim();
            if (p.length() == 0) {
                continue;
            }
            int sep = indexOfSep(p);
            String pattern;
            String act;
            if (sep < 0) {
                pattern = p;
                act = OFF;
            } else {
                pattern = p.substring(0, sep).trim();
                act = action(p.substring(sep + 1).trim());
                if (act.length() == 0) {
                    act = OFF;
                }
            }
            if (pattern.length() == 0) {
                continue;
            }
            out.add(new Rule(pattern.toLowerCase(Locale.ROOT), act));
        }
        return out;
    }

    private static int indexOfSep(String p) {
        int a = p.indexOf(':');
        int b = p.indexOf('=');
        int c = p.indexOf('：');
        int best = -1;
        int[] all = {a, b, c};
        for (int i : all) {
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /** 单条模式是否命中某个身份：带 {@code *} 走通配，不写就"等于或包含"；空格不敏感。 */
    public static boolean hit(String pattern, String identity) {
        if (pattern == null || identity == null) {
            return false;
        }
        String pat = pattern.trim().toLowerCase(Locale.ROOT);
        String name = identity.trim().toLowerCase(Locale.ROOT);
        if (pat.length() == 0 || name.length() == 0) {
            return false;
        }
        if (one(pat, name)) {
            return true;
        }
        return one(pat.replace(" ", ""), name.replace(" ", ""));
    }

    private static boolean one(String pat, String name) {
        if (pat.indexOf('*') >= 0) {
            try {
                // 用 find（" anywhere"）而不是 matches（整串）：写 `K70*` 的人想命中的是
                // "Redmi K70 Pro" 这种带品牌前缀的整名，不是只有以 K70 开头的。
                return java.util.regex.Pattern.compile(pat.replace(".", "\\.").replace("*", ".*"))
                        .matcher(name).find();
            } catch (Throwable t) {
                return name.contains(pat.replace("*", ""));
            }
        }
        return name.equals(pat) || name.contains(pat);
    }

    /** 任意身份命中任意一条 → 返回那条规则；否则 null。 */
    public static Rule decide(List<Rule> rules, List<String> identities) {
        if (rules == null || identities == null) {
            return null;
        }
        for (Rule r : rules) {
            for (String name : identities) {
                if (hit(r.pattern, name)) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 最终动作：规则说 {@code ask} 且用户已经表过态，就按用户选的走 ——
     * 询问只有一次意义，不能每次启动都拦人。
     *
     * @param chosen 用户选过的动作（{@link #LOW}/{@link #OFF}/{@link #KEEP}，没选过传 null）
     */
    public static String resolve(Rule r, String chosen) {
        if (r == null) {
            return "";
        }
        if (!ASK.equals(r.action)) {
            return r.action;
        }
        String c = action(chosen);
        return c.length() == 0 ? ASK : c;
    }

    /** {@code low} 降到哪一档：云控可指定，默认 balanced；写错一律按 balanced。 */
    public static String lowTier(String cfg) {
        String v = cfg == null ? "" : cfg.trim().toLowerCase(Locale.ROOT);
        List<String> ok = Arrays.asList("high", "balanced", "flat");
        if (v.length() == 0) {
            return "balanced";
        }
        if (v.equals("mid") || v.equals("middle")) {
            return "balanced";
        }
        if (v.equals("off") || v.equals("low") || v.equals("lite")) {
            return "flat";
        }
        return ok.contains(v) ? v : "balanced";
    }
}
