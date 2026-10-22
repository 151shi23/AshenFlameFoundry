package com.mineways.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 登录与白名单门禁的<b>唯一判定层</b>（纯 JVM，不碰安卓类，所以能用 javac + main 直接断言）。
 *
 * <p>两件事分开：
 * <ul>
 *   <li><b>身份</b>：这台机器现在拿着哪几张登录牌 —— 论坛 / Google / 微软（{@link #FORUM}
 *       / {@link #GOOGLE} / {@link #MS}），可以同时持有；</li>
 *   <li><b>门禁</b>：某个功能要求哪一种身份，以及要不要查名单。</li>
 * </ul>
 *
 * <p>门禁只有两个家族，云控在 {@code FEAT … gate=} 上写：
 * <pre>
 * gate=free                明确不要登录（用来盖掉旧的 tier=whitelist）
 * gate=login               登录就解锁：论坛 / Google / 微软 任一种都行
 * gate=login:forum         只有论坛登录算
 * gate=login:ms            只有微软登录算
 * gate=login:google        只有 Google 登录算
 * gate=login:google+ms     任一种（并集就是这里的「特殊参数」）
 * gate=wl                  白名单：登录了 <b>并且</b> 命中对应档名单
 * gate=wl:forum            论坛登录 + 论坛名单
 * </pre>
 * 中文写法同样认：{@code 登录} / {@code 白名单} / {@code 免费} / {@code 论坛} / {@code 微软} / {@code 谷歌} / {@code 通用}。
 *
 * <p>三条口径，都是「宁可少放行，不能多解锁」：
 * <ol>
 *   <li><b>Google / 微软是最基础的登录</b>：它们只发身份牌，天生进不了 {@code :forum} 那一档 ——
 *       想放进来只能在具体那条规则上写并集（{@code gate=wl:forum+ms}），没有全局后门；</li>
 *   <li><b>论坛登录权限最高</b>：默认（{@code login_forum_scope=full}）论坛身份连 {@code :google}
 *       / {@code :ms} 的功能也认，因为论坛账号是这套体系里唯一有服务端复验的身份；</li>
 *   <li><b>这个「最高」云控可以降</b>：{@code login_forum_scope=own} 只保住自己那一档，
 *       {@code =common} 直接降成普通身份（论坛档功能谁都进不去，只剩并集写明的）。</li>
 * </ol>
 *
 * <p>名单也是分档的：云控 {@code WL add email=… list=forum|ms|google}，<b>不写 list= 的旧写法
 * 落进共享名单</b>（{@link #ANY}），对任何一档都算命中 —— 这样 3.9.26 之前那份白名单一条都不用改。
 * 踢人优先：{@code WL drop} 过的账号即使在内置名单里也不放行。
 */
public final class LoginPolicy {

    /** 三种登录身份 + 一个「任一种」。 */
    public static final String FORUM = "forum";
    public static final String MS = "ms";
    public static final String GOOGLE = "google";
    public static final String ANY = "any";

    /** 真实身份（{@link #ANY} 不是身份，是选择器）。 */
    public static final List<String> SCOPES = Collections.unmodifiableList(
            Arrays.asList(FORUM, GOOGLE, MS));

    /** 门禁家族。 */
    public static final String FREE = "free";
    public static final String LOGIN = "login";
    public static final String WL = "wl";

    /** 论坛身份的权限档（云控 {@code login_forum_scope}）。 */
    public static final String FORUM_FULL = "full";
    public static final String FORUM_OWN = "own";
    public static final String FORUM_COMMON = "common";

    /** 名单档名（共享档 = 旧的不写 list= 的写法）。 */
    public static final String LIST_SHARED = ANY;

    private LoginPolicy() {
    }

    /**
     * 判定要问的外部世界。安卓侧实现它（现读云控与本地凭证），
     * 断言里用假实现 —— 这一层不认识 Context，也不认识 SharedPreferences。
     */
    public interface Env {
        /** 这台机器现在持有的登录牌（{@link #FORUM} / {@link #GOOGLE} / {@link #MS}）。 */
        List<String> activeScopes();

        /**
         * 这一档的名单里有没有对应登录身份的账号。
         *
         * <p>约定：实现方把<b>共享名单</b>并进每一档的查询里（命中共享名单 = 命中任何一档），
         * {@code scope=}{@link #ANY} 只问「有没有任何登录账号命中了共享名单」。
         * 「通用白名单」要不要放宽成「命中任何一档名单」由 {@link #listedFor} 决定，不在这里猜。
         */
        boolean listed(String scope);

        /** 云控开关。 */
        String flag(String key, String def);
    }

    /** 一条 {@code gate=} 的解析结果。解析失败时 {@link #bad} 非空，调用方按「没这条」处理。 */
    public static final class Gate {
        public final String raw;
        public String family = FREE;
        public final List<String> scopes = new ArrayList<>();
        public String bad = "";

        Gate(String raw) {
            this.raw = raw;
        }

        public boolean isFree() {
            return FREE.equals(family);
        }

        /** 给人看的档名：{@code 白名单·论坛} / {@code 登录·任一种}。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(FREE.equals(family) ? "不需登录"
                    : (WL.equals(family) ? "白名单" : "登录即解锁"));
            if (!FREE.equals(family)) {
                sb.append('·').append(scopeNames(scopes));
            }
            return sb.toString();
        }
    }

    // ============================================================ 解析

    /**
     * 解析 {@code gate=} 的值。空值返回 {@code null}（= 这条规则不参与登录门禁，沿用档位判定）。
     *
     * <p>容错口径：认得的别名尽量认（中英、大小写、{@code microsoft}、{@code 论坛登录}），
     * 但<b>猜不出来的绝不给个默认档</b> —— 返回的 {@link Gate#bad} 会被解析器当成写错的行上报，
     * 那条指令就不执行。把「看不懂」变成「什么都不做」才是安全的。
     */
    public static Gate parse(String spec) {
        String s = trim(spec);
        if (s.length() == 0) {
            return null;
        }
        Gate g = new Gate(spec);
        String fam = s;
        String rest = "";
        int colon = s.indexOf(':');
        if (colon < 0) {
            colon = s.indexOf('：');                      // 中文冒号也要能吃
        }
        if (colon >= 0) {
            fam = s.substring(0, colon);
            rest = s.substring(colon + 1);
        }
        String f = familyOf(fam);
        if (f == null) {
            // 只写档不写家族的偷懒写法（gate=论坛、gate=通用）：按「登录就解锁·这一档」理解。
            // 只有整段就是一个档位名时才这样兜，带 : 的仍然报错 —— 免得把写错的家族悄悄吞掉。
            String solo = trim(rest).length() == 0 ? scopeOf(fam) : null;
            if (solo != null) {
                g.family = LOGIN;
                g.scopes.add(solo);
                return g;
            }
            g.bad = "gate 的家族不认识：" + fam + "（可写 free / login / wl，或 免费 / 登录 / 白名单）";
            return g;
        }
        g.family = f;
        if (FREE.equals(f)) {
            if (trim(rest).length() > 0) {
                g.bad = "gate=free 不用带档位：" + rest;
            }
            return g;
        }
        if (trim(rest).length() == 0) {
            g.scopes.add(ANY);                            // 没写档位 = 通用（任一种登录）
            return g;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : rest.split("[+,，]")) {
            String p = trim(piece);
            if (p.length() == 0) {
                continue;
            }
            String sc = scopeOf(p);
            if (sc == null) {
                g.bad = "gate 的档位不认识：" + p + "（可写 any / forum / google / ms，或 通用 / 论坛 / 谷歌 / 微软）";
                return g;
            }
            seen.add(sc);
        }
        if (seen.isEmpty()) {
            g.bad = "gate 写了档位却一个也没认出来：" + rest;
            return g;
        }
        g.scopes.addAll(seen);
        return g;
    }

    /** 家族别名。 */
    private static String familyOf(String s) {
        String t = norm(s);
        if (t.equals(LOGIN) || t.equals("登录") || t.equals("登陆") || t.equals("signin")
                || t.equals("sign_in") || t.equals("any_login")) {
            return LOGIN;
        }
        if (t.equals(WL) || t.equals("whitelist") || t.equals("白名单") || t.equals("名单")
                || t.equals("allowlist")) {
            return WL;
        }
        if (t.equals(FREE) || t.equals("开") || t.equals("开放")
                || t.equals("免费") || t.equals("不限") || t.equals("无门槛") || t.equals("none")
                || t.equals("off") || t.equals("normal")) {
            return FREE;
        }
        return null;
    }

    /** 档位别名。{@code all} / {@code 通用} 都归到 {@link #ANY}。 */
    private static String scopeOf(String s) {
        String t = norm(s);
        if (t.length() == 0) {
            return null;
        }
        if (t.equals(ANY) || t.equals("all") || t.equals("通用") || t.equals("任意")
                || t.equals("任一") || t.equals("任意登录") || t.equals("*")) {
            return ANY;
        }
        if (t.equals(FORUM) || t.startsWith("forum") || t.equals("论坛") || t.equals("论坛登录")
                || t.equals("社区") || t.equals("社区登录") || t.equals("bbs")) {
            return FORUM;
        }
        if (t.equals(MS) || t.equals("microsoft") || t.equals("微软")
                || t.equals("微软登录") || t.equals("windows") || t.equals("win")) {
            return MS;
        }
        if (t.equals(GOOGLE) || t.startsWith("google") || t.equals("谷歌") || t.equals("谷歌登录")) {
            return GOOGLE;
        }
        return null;
    }

    // ============================================================ 判定

    /** 论坛身份现在算多大（云控可降权）。 */
    public static String forumScope(Env env) {
        String v = norm(env == null ? "" : env.flag("login_forum_scope", FORUM_FULL));
        if (v.equals(FORUM_OWN) || v.equals("only") || v.equals("自己") || v.equals("本档") || v.equals("仅论坛")) {
            return FORUM_OWN;
        }
        if (v.equals(FORUM_COMMON) || v.equals("降权") || v.equals("普通") || v.equals("通用")) {
            return FORUM_COMMON;
        }
        return FORUM_FULL;
    }

    /**
     * 这个身份牌够不够进 {@code required} 那一档。
     *
     * <p>{@code required=any} 只看「有没有登录」；其余按牌对号入座，
     * 唯一的例外是论坛的最高档权限（{@link #FORUM_FULL}）。
     */
    public static boolean satisfies(Env env, String required, List<String> active) {
        if (required == null || active == null || active.isEmpty()) {
            return false;
        }
        if (ANY.equals(required)) {
            return true;
        }
        if (FORUM.equals(required)) {
            // 降到「通用」以后，论坛牌就不再是论坛牌了 —— 这是用户要的「给它降权限」
            return active.contains(FORUM) && !FORUM_COMMON.equals(forumScope(env));
        }
        if (active.contains(required)) {
            return true;
        }
        // 只有没降权的论坛牌能替 google / 微软 那一档说话
        return FORUM_FULL.equals(forumScope(env)) && active.contains(FORUM);
    }

    /**
     * 这一档的名单查不查得到。
     *
     * <p>写明了档位（{@code :forum} / {@code :google} / {@code :ms}）就<b>只查那一档</b>（加上共享档），
     * 论坛身份再高也替不了别人名单里的人 —— 名单是「谁」，身份是「用哪种方式登录的」，两件事。
     * 档位是 {@link #ANY}（也就是 {@code gate=wl}）时才放宽：任何一张登录牌命中自己那档的名单就算白名单用户，
     * 这样 3.9.26 之前那些 {@code tier=whitelist} 的功能一行都不用改就还是「白名单可用」。
     */
    public static boolean listedFor(Env env, String required) {
        if (env == null) {
            return false;
        }
        if (env.listed(ANY)) {
            return true;                                  // 共享名单：四档通吃
        }
        if (!ANY.equals(required)) {
            return env.listed(required);
        }
        List<String> active = env.activeScopes();
        if (active != null) {
            for (String s : active) {
                if (env.listed(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 放不放行。{@code gateSpec} 为空或解析不了 → 按「这条规则不存在」放行给上层（档位判定）。 */
    public static boolean allowed(Env env, String gateSpec) {
        Gate g = parse(gateSpec);
        return g == null || g.bad.length() > 0 || allowed(env, g);
    }

    public static boolean allowed(Env env, Gate g) {
        if (g == null || g.bad.length() > 0 || g.isFree()) {
            return true;
        }
        List<String> active = env == null ? Collections.<String>emptyList() : env.activeScopes();
        if (active == null) {
            active = Collections.emptyList();
        }
        if (LOGIN.equals(g.family)) {
            return anySatisfied(env, g.scopes, active);
        }
        for (String r : g.scopes) {
            if (satisfies(env, r, active) && listedFor(env, r)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anySatisfied(Env env, List<String> required, List<String> active) {
        for (String r : required) {
            if (satisfies(env, r, active)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拦下来时要说人话：缺哪张牌、还是牌有了但名单没这个人。
     * 界面要能直接把这句话贴给测试，不要让他去抓 log。
     */
    public static String denyReason(Env env, Gate g) {
        if (g == null || g.isFree()) {
            return "";
        }
        List<String> active = env == null ? Collections.<String>emptyList() : env.activeScopes();
        if (active == null) {
            active = Collections.emptyList();
        }
        String want = scopeNames(g.scopes);
        if (active.isEmpty()) {
            return "还没登录 —— 这个功能要" + (WL.equals(g.family) ? "「" + want + "登录，且账号在白名单里」"
                    : "用" + want + "登录一下");
        }
        boolean anyId = false;
        for (String r : g.scopes) {
            if (satisfies(env, r, active)) {
                anyId = true;
                break;
            }
        }
        if (!anyId) {
            if (active.contains(FORUM) && FORUM_COMMON.equals(forumScope(env))) {
                return "论坛登录已经被云控降权（login_forum_scope=common），现在进不了「"
                        + want + "」这一档";
            }
            return "需要" + want + "登录（这台机器现在是" + scopeNames(active) + "）";
        }
        return "已经登录了，但「" + want + "」的名单里没有这个账号";
    }

    /** 关于页那一行：身份牌 + 名单命中 + 论坛降权，一次全摊开。 */
    public static String debugLine(Env env) {
        if (env == null) {
            return "（没有判定环境）";
        }
        List<String> active = env.activeScopes();
        StringBuilder sb = new StringBuilder();
        sb.append(active == null || active.isEmpty() ? "未登录" : "身份：" + scopeNames(active));
        sb.append(" · 论坛权限档：").append(forumCn(forumScope(env)));
        List<String> hits = new ArrayList<>();
        String[] lists = {ANY, FORUM, GOOGLE, MS};
        for (String l : lists) {
            if (env.listed(l)) {
                hits.add(listName(l));
            }
        }
        sb.append(" · 名单：").append(hits.isEmpty() ? "都没命中" : String.join("/", hits));
        return sb.toString();
    }

    public static String listName(String scope) {
        return ANY.equals(scope) ? "共享" : scopeCn(scope);
    }

    private static String forumCn(String fs) {
        if (FORUM_OWN.equals(fs)) {
            return "只认自己那一档（own）";
        }
        if (FORUM_COMMON.equals(fs)) {
            return "已降为普通身份（common）";
        }
        return "最高档，四档全通（full）";
    }

    /** 档位的中文名（写进界面提示与状态面板）。 */
    public static String scopeCn(String scope) {
        if (FORUM.equals(scope)) {
            return "论坛登录";
        }
        if (GOOGLE.equals(scope)) {
            return "Google 登录";
        }
        if (MS.equals(scope)) {
            return "微软登录";
        }
        if (ANY.equals(scope)) {
            return "任一种登录";
        }
        return scope;
    }

    private static String scopeNames(List<String> scopes) {
        List<String> cn = new ArrayList<>();
        for (String s : scopes) {
            cn.add(scopeCn(s));
        }
        return String.join(" 或 ", cn);
    }

    /** 某个功能「需要什么」的一句话，显示在未解锁的卡片上。 */
    public static String requireText(Gate g) {
        if (g == null || g.isFree()) {
            return "";
        }
        return WL.equals(g.family) ? "白名单·" + scopeNames(g.scopes) : "登录·" + scopeNames(g.scopes);
    }

    // ============================================================ 小工具

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 云控写档位的规范拼法（给 Lint 与文档用的白名单）。 */
    public static Set<String> scopeKeys() {
        return new LinkedHashSet<>(Arrays.asList(ANY, FORUM, GOOGLE, MS));
    }

    /**
     * {@code WL … list=} 的档名归一。认不出来返回 null —— 名单写错档位会让整个人群对不上，
     * 必须让解析器把那行报成「写错」，不能默默落到共享名单里。
     */
    public static String listKey(String s) {
        String t = norm(s);
        if (t.length() == 0 || t.equals("shared") || t.equals("share") || t.equals("全局")
                || t.equals("共享")) {
            return ANY;
        }
        return scopeOf(t);
    }

    /** 供 Lint / 断言用：这条 gate 能不能落成一个真规则。 */
    public static String validate(String spec) {
        Gate g = parse(spec);
        if (g == null) {
            return "gate 是空的";
        }
        return g.bad.length() == 0 ? null : g.bad;
    }
}
