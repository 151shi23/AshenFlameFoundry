package com.mineways.guardlink;

import android.content.Context;

import com.mineways.AppLog;
import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureCatalog;
import com.zeus.guardlink.GuardLinkCore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「谁不该被当成嫌疑人」—— 常见 App 与游戏的豁免名单。
 *
 * <p>内核原本的口径是<b>能力越像外挂就越可疑</b>：能静默装包 +30、声明无障碍 +20、开着无障碍 +40、
 * 悬浮窗 +15、开机自启 +10、流量前三 +20、事发窗口内界面归它 +50，累计到 60 就推举成「主要嫌疑」。
 * 这套打分对真外挂很准，但微信 / QQ / 支付宝 / 抖音 / 淘宝 / 各家输入法天生就同时占着
 * 「无障碍 + 悬浮窗 + 自启 + 大流量」几条，随便凑就过线；它们在自己页面上弹个窗、刷出一条新消息，
 * 界面就「没人碰却在动」。于是正常 App 被推举成嫌疑对象，接着就允许对它精确断网、甚至卸载。
 * 系统软件那边也只排掉了一半：纯系统包在采集阶段就跳过了
 * （{@link GuardProfiles} 读 {@code FLAG_SYSTEM}），但被厂商商店更新过的系统包照样建档打分。
 *
 * <p>所以豁免分三档，<b>强度递增</b>，一条包名只落在其中一档：
 * <ul>
 *   <li>{@link Level#IRREVERSIBLE} —— 照检测、照报警、照上榜，但禁止对它精确断网 / 冻结 / 卸载。
 *       给游戏与常见 App：它们不该被误伤，但它们的界面异动仍然值得提醒用户。</li>
 *   <li>{@link Level#RANK} —— 会触发报警，但它自己进不了推举榜（采集阶段就跳过），
 *       因而也就无从对它动手。给大厂主力 App。</li>
 *   <li>{@link Level#EVIDENCE} —— 连「界面自己在动」这条证据都不算它的。
 *       给输入法、系统界面这类<b>本来就该自己动</b>的东西：不挡它们，用户每敲一个字都报警。</li>
 * </ul>
 *
 * <p>三级来源，<b>云控 &gt; 本机 &gt; 内置</b>：内置那份在包内 {@code assets/guard/trust-packages.txt}
 * （逐条带出处），本机加的是应急出口（线上误报不用等发版），云控用 {@code PKG add/drop} 行下发，
 * 能把内置那条撤掉或改档。每条查询都会说明它现在听谁的 —— 免得界面显示「已豁免」而内核其实不认。
 *
 * <p>合并与查找本身是纯逻辑（{@link Table}），不碰 Android，所以这三档语义在 JVM 里就能断言；
 * 这个类只负责从资产 / prefs / 云控把三份输入读出来。
 */
public final class GuardTrust {

    /** 豁免强度，从小到大约等于「挡得越狠」。 */
    public enum Level {
        NONE, IRREVERSIBLE, RANK, EVIDENCE;

        /** 显示名，界面与日志共用。 */
        public String cn() {
            switch (this) {
                case EVIDENCE:
                    return "不算证据";
                case RANK:
                    return "不进榜";
                case IRREVERSIBLE:
                    return "不许动手";
                default:
                    return "不豁免";
            }
        }
    }

    /** 名单里的一条。{@link #pkg} 以点结尾时按前缀（目录语义）匹配。 */
    public static final class Entry {
        public final String pkg;
        public final String name;
        public final Level level;
        /** 「内置 / 本机 / 云控」—— 三级里最终生效那一级的名字。 */
        public final String source;
        /** 出处说明：内置那条来自哪个数据源，云控那条是哪一行。 */
        public final String from;

        Entry(String pkg, String name, Level level, String source, String from) {
            this.pkg = pkg;
            this.name = name == null || name.length() == 0 ? pkg : name;
            this.level = level;
            this.source = source;
            this.from = from == null ? "" : from;
        }

        public boolean isDir() {
            return pkg.endsWith(".");
        }
    }

    /** 包内清单：一行一条，字段用竖线分隔，# 开头是注释。 */
    private static final String ASSET = "guard/trust-packages.txt";
    private static final String PREF_LOCAL = "guard_trust_local";

    private static volatile Map<String, Entry> effective = Collections.emptyMap();
    private static volatile String note = "还没装配过（打开守护页或启用守护时自动装配）";

    private GuardTrust() {
    }

    // ---------------------------------------------------------------- 纯逻辑层

    /** 三级来源的合并与查找，全部不依赖 Android，JVM 断言直接打这一层。 */
    static final class Table {

        /** 小写键：Android 包名区分大小写，但手打配置的大小写不该决定生不生效。 */
        static String key(String pkg) {
            return pkg == null ? "" : pkg.trim().toLowerCase(Locale.ROOT);
        }

        /**
         * 内核 {@code A11yPolicy.isTrusted} 那条<b>目录语义</b>，一字不差：
         * {@code com.tencent.} 放行 {@code com.tencent.mm} 与它的子包，但<b>不放行</b>
         * {@code com.tencentx.evil} —— 仿冒知名前缀正是这份名单最容易被绕的地方。
         */
        static boolean matches(String entryPkg, String lowercasedQuery) {
            String bare = entryPkg.endsWith(".")
                    ? key(entryPkg.substring(0, entryPkg.length() - 1))
                    : key(entryPkg);
            return lowercasedQuery.equals(bare) || lowercasedQuery.startsWith(bare + ".");
        }

        /**
         * 三级合并：内置打底，本机盖内置，云控最后说话。
         * {@code cloudDrops} 里的包名连内置那条一起撤掉 —— 云控说撤就是撤，不留「名单里还有一条」。
         */
        static Map<String, Entry> merge(List<Entry> builtin, List<Entry> local,
                                        Map<String, String> cloudLevels,
                                        Map<String, String> cloudNames, Set<String> cloudDrops) {
            Map<String, Entry> out = new LinkedHashMap<>();
            putAll(out, builtin);
            putAll(out, local);
            if (cloudDrops != null) {
                for (String k : cloudDrops) {
                    out.remove(key(k));
                }
            }
            if (cloudLevels != null) {
                for (Map.Entry<String, String> e : cloudLevels.entrySet()) {
                    Level lv = toLevel(FeatureCatalog.pkgLevelOf(e.getValue()));
                    if (lv == Level.NONE) {
                        continue;         // 解析层已经拦过；这里再兜一道，宁可当没这条
                    }
                    String name = cloudNames == null ? "" : cloudNames.get(e.getKey());
                    out.put(key(e.getKey()), new Entry(e.getKey(), name == null ? "" : name,
                            lv, "云控", "PKG add level=" + e.getValue()));
                }
            }
            return out;
        }

        private static void putAll(Map<String, Entry> dst, List<Entry> src) {
            for (Entry e : src) {
                dst.put(key(e.pkg), e);
            }
        }

        /** 命中查询：精确优先，再按目录语义扫前缀条目。 */
        static Entry find(Map<String, Entry> table, String pkg) {
            if (pkg == null || pkg.length() == 0) {
                return null;
            }
            String k = key(pkg);
            Entry exact = table.get(k);
            if (exact != null) {
                return exact;
            }
            for (Entry e : table.values()) {
                if (matches(e.pkg, k)) {
                    return e;
                }
            }
            return null;
        }

        /** 这条包名算第几档；不在名单上返回 {@link Level#NONE}。 */
        static Level levelOf(Map<String, Entry> t, String pkg) {
            Entry e = find(t, pkg);
            return e == null ? Level.NONE : e.level;
        }

        /** ①档：它自己的界面活动不进判定。只有这一档挡证据。 */
        static boolean notEvidence(Map<String, Entry> t, String pkg) {
            return levelOf(t, pkg) == Level.EVIDENCE;
        }

        /** ②档及以上：采集阶段就跳过，进不了推举榜，也就无从被动手。 */
        static boolean keepOutOfRank(Map<String, Entry> t, String pkg) {
            return levelOf(t, pkg).ordinal() >= Level.RANK.ordinal();
        }

        /** ③档及以上：不许精确断网 / 冻结 / 卸载。 */
        static boolean blockIrreversible(Map<String, Entry> t, String pkg) {
            return levelOf(t, pkg).ordinal() >= Level.IRREVERSIBLE.ordinal();
        }

        /** 挡下来时要回给用户看的那一句（失败必须可见）。 */
        static String blockReason(Entry e) {
            if (e == null) {
                return null;
            }
            return e.name + "（" + e.level.cn() + "豁免，来源：" + e.source + "）：不做断网 / 冻结 / 卸载。";
        }

        /**
         * 不可逆动作的统一拒绝规则，纯逻辑。两条一起判，一条都不能少：
         * <ul>
         *   <li><b>系统应用一律不动手</b>，而且不靠名单 —— 靠 {@code FLAG_SYSTEM}（系统给的事实，
         *       包名伪造不出来）。名单永远列不全厂商组件，而冻结那条路拿到的是 UsageStats 报的
         *       陌生前台包：少这一道，保护窗口里就会把厂商安全中心、系统设置 force-stop 掉，
         *       表现就是「手机像被冻住了」。</li>
         *   <li>名单里三档都挡：③不许动手，②①更不用说了。</li>
         * </ul>
         *
         * @param verb 动作名，用来把拒绝语说清楚（精确断网 / 冻结 / 卸载）
         */
        static String refusal(Map<String, Entry> t, String pkg, boolean isSystemApp, String verb) {
            if (isSystemApp) {
                return pkg + " 是系统应用（FLAG_SYSTEM）：本功能不对系统应用做「" + verb
                        + "」—— 它卸载不掉，冻了还可能把机器弄成没法用的样子。"
                        + "系统组件自己弹界面是常态，所以它也不会被写成嫌疑人。";
            }
            return blockIrreversible(t, pkg) ? blockReason(find(t, pkg)) : null;
        }

        /**
         * 解析一行 {@code 包名|展示名|档位|出处}。坏行返回 {@code null} 让调用方跳过：
         * 一条写错的不能把整份名单带走。
         */
        static Entry parseLine(String raw, String source, String defaultFrom) {
            String s = raw == null ? "" : raw.trim();
            if (s.length() == 0 || s.charAt(0) == '#') {
                return null;
            }
            String[] p = s.split("\\|");
            String pkg = p[0].trim();
            if (!GuardShell.safePkg(pkg)) {
                return null;
            }
            Level lv = toLevel(FeatureCatalog.pkgLevelOf(p.length > 2 ? p[2].trim() : ""));
            if (lv == Level.NONE) {
                return null;             // 档位认不出来就当没这条，不能猜成某一档
            }
            String name = p.length > 1 ? p[1].trim() : "";
            String from = p.length > 3 ? p[3].trim() : defaultFrom;
            return new Entry(pkg, name, lv, source, from);
        }

        static Level toLevel(String canonical) {
            if (FeatureCatalog.PKG_EVIDENCE.equals(canonical)) {
                return Level.EVIDENCE;
            }
            if (FeatureCatalog.PKG_RANK.equals(canonical)) {
                return Level.RANK;
            }
            if (FeatureCatalog.PKG_IRREVERSIBLE.equals(canonical)) {
                return Level.IRREVERSIBLE;
            }
            return Level.NONE;
        }

        static String levelName(Level lv) {
            if (lv == Level.EVIDENCE) {
                return FeatureCatalog.PKG_EVIDENCE;
            }
            if (lv == Level.RANK) {
                return FeatureCatalog.PKG_RANK;
            }
            return FeatureCatalog.PKG_IRREVERSIBLE;
        }
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 重算生效名单：内置文件 + 本机改动 + 云控下发，三级合并。
     *
     * <p>每次启用守护、每次云控落地、每次打开守护页都要调它 —— 名单不是编译期常量，
     * 云控刚下发的第 N+1 条不能等重启才生效。
     */
    public static synchronized void refresh(Context ctx) {
        Context app = ctx.getApplicationContext();
        List<Entry> builtin = loadBuiltin(app);
        List<Entry> local = loadLocal(app);
        Map<String, String> cloud = CloudCatalog.gate().pkgLevels();
        Set<String> dropped = CloudCatalog.gate().pkgDropped();
        Map<String, String> names = new LinkedHashMap<>();
        for (String k : cloud.keySet()) {
            String n = CloudCatalog.gate().pkgName(k);
            if (n.length() > 0) {
                names.put(k, n);
            }
        }
        effective = Table.merge(builtin, local, cloud, names, dropped);
        note = "内置 " + builtin.size() + " 条 · 本机 " + local.size() + " 条 · 云控 +"
                + cloud.size() + " −" + dropped.size();
    }

    /**
     * 包内清单。读不到就退回<b>内核自带的那份大厂前缀表</b>
     * （{@code A11yPolicy.TRUSTED_PKG_PREFIXES}）当「不进榜」用：宁可少挡几条游戏，
     * 也不能因为一个文件读失败就变成「微信被推举成主要嫌疑」。
     */
    private static List<Entry> loadBuiltin(Context ctx) {
        List<Entry> out = new ArrayList<>();
        InputStream in = null;
        try {
            in = ctx.getAssets().open(ASSET);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                Entry e = Table.parseLine(line, "内置", "");
                if (e != null) {
                    out.add(e);
                }
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "豁免名单读取失败，退回内核大厂前缀表：" + ASSET, t);
            out.clear();
        } finally {
            close(in);
        }
        if (out.isEmpty()) {
            for (String pre : GuardLinkCore.A11yPolicy.TRUSTED_PKG_PREFIXES) {
                String pkg = pre.endsWith(".") ? pre : pre + ".";
                out.add(new Entry(pkg, "", Level.RANK, "内置（兜底）",
                        "内核 A11yPolicy.TRUSTED_PKG_PREFIXES"));
            }
        }
        return out;
    }

    /** 本机加的应急条目：{@code 包名|档位} 一行一条存在 prefs 里。 */
    private static List<Entry> loadLocal(Context ctx) {
        List<Entry> out = new ArrayList<>();
        for (String l : localLines(ctx)) {
            Entry e = Table.parseLine(l, "本机", "本机临时豁免");
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 还没装配过就先装配，已经装配过就什么都不做。
     *
     * <p>必须有的原因：豁免表是读资产 + 读云控算出来的，而第一条无障碍事件可能比
     * {@code GuardRuntime.enable()} 更早到（无障碍服务自己就起来了）。那时名单是空的，
     * 空名单意味着<b>谁都不豁免</b> —— 输入法每敲一个字都能报警，正是这套名单要消掉的误报。
     * 生效名单永远不会是空的（读不到资产也会退回内核前缀表兜底），所以「空」就等于「还没装配」。
     */
    public static synchronized void ensureLoaded(Context ctx) {
        if (effective.isEmpty()) {
            refresh(ctx);
        }
    }

    /** 这条包名现在算第几档豁免；不在名单上返回 {@link Level#NONE}。 */
    public static Level level(String pkg) {
        return Table.levelOf(effective, pkg);
    }

    /** 命中查询，供界面显示「它现在听谁的」。 */
    public static Entry entryOf(String pkg) {
        return Table.find(effective, pkg);
    }

    /** 它自己的界面活动能不能当作「没人碰却在动」的证据。 */
    public static boolean notEvidence(String pkg) {
        return Table.notEvidence(effective, pkg);
    }

    /** 能不能给它建档打分（进了榜才谈得上被断网、被卸载）。 */
    public static boolean keepOutOfRank(String pkg) {
        return Table.keepOutOfRank(effective, pkg);
    }

    /**
     * 不可逆动作的统一拒绝语；返回 {@code null} 表示允许执行。
     *
     * <p>两条来源分开判，一条都不能少：
     * <ul>
     *   <li><b>系统应用一律不动手</b>。这条不靠名单，靠 {@code FLAG_SYSTEM}：名单永远列不全厂商组件，
     *       而冻结走的是 UsageStats 给的陌生前台包 —— 少这一道，保护窗口里就会把
     *       厂商安全中心、系统设置这类东西 force-stop 掉，表现就是「手机像被冻住了」。</li>
     *   <li>名单里三档都挡：③不许动手，②①更不用说了。</li>
     * </ul>
     *
     * @param isSystemApp 由调用方现查 {@code PackageManager} 传进来，这里只讲规则，所以能在 JVM 里断言
     * @param verb        动作名，用来把拒绝语说清楚（精确断网 / 冻结 / 卸载）
     */
    public static String irreversibleRefusal(String pkg, boolean isSystemApp, String verb) {
        return Table.refusal(effective, pkg, isSystemApp, verb);
    }

    /** 现在生效的条目，按豁免强度从强到弱、再按包名排。 */
    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>(effective.values());
        Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.level != b.level) {
                    return b.level.ordinal() - a.level.ordinal();
                }
                return a.pkg.compareToIgnoreCase(b.pkg);
            }
        });
        return out;
    }

    /** 首屏那行汇总。 */
    public static String summary(Context ctx) {
        if (effective.isEmpty()) {
            refresh(ctx);
        }
        return "生效 " + effective.size() + " 条：不算证据 " + count(Level.EVIDENCE)
                + " · 不进榜 " + count(Level.RANK) + " · 不许动手 " + count(Level.IRREVERSIBLE)
                + "。" + note;
    }

    private static int count(Level lv) {
        int n = 0;
        for (Entry e : effective.values()) {
            if (e.level == lv) {
                n++;
            }
        }
        return n;
    }

    /** 复制用的全量清单（带档位与来源）。 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries()) {
            sb.append(e.level.name()).append("  ").append(e.pkg);
            if (!e.name.equals(e.pkg)) {
                sb.append("  // ").append(e.name);
            }
            sb.append("  [").append(e.source).append(']');
            if (e.from.length() > 0) {
                sb.append(' ').append(e.from);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 本机改动

    /** 线上误报时不用等发版：本机先挡一条。返回 null 表示成功，否则是给用户看的原因。 */
    public static String addLocal(Context ctx, String pkg, String levelRaw) {
        String p = pkg == null ? "" : pkg.trim();
        if (!GuardShell.safePkg(p)) {
            return "包名不对：只能是字母数字和 . _ -，且至少两段";
        }
        Level lv = Table.toLevel(FeatureCatalog.pkgLevelOf(levelRaw));
        if (lv == Level.NONE) {
            return "档位不认识：可写 不算证据 / 不进榜 / 不许动手";
        }
        List<String> next = new ArrayList<>();
        for (String l : localLines(ctx)) {
            if (!Table.key(l.split("\\|")[0]).equals(Table.key(p))) {
                next.add(l);
            }
        }
        next.add(p + "|" + Table.levelName(lv));
        Collections.sort(next);
        writeLocal(ctx, next);
        refresh(ctx);
        return null;
    }

    public static String removeLocal(Context ctx, String pkg) {
        String p = Table.key(pkg);
        List<String> next = new ArrayList<>();
        boolean hit = false;
        for (String l : localLines(ctx)) {
            if (Table.key(l.split("\\|")[0]).equals(p)) {
                hit = true;
                continue;
            }
            next.add(l);
        }
        if (!hit) {
            return "本机名单里没有 " + pkg;
        }
        writeLocal(ctx, next);
        refresh(ctx);
        return null;
    }

    public static Set<String> localPackages(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : localLines(ctx)) {
            out.add(l.split("\\|")[0].trim());
        }
        return out;
    }

    private static List<String> localLines(Context ctx) {
        String raw = ctx.getSharedPreferences("guardlink", Context.MODE_PRIVATE)
                .getString(PREF_LOCAL, "");
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String l : raw.split("\n")) {
            if (l.trim().length() > 0) {
                out.add(l.trim());
            }
        }
        return out;
    }

    private static void writeLocal(Context ctx, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        ctx.getSharedPreferences("guardlink", Context.MODE_PRIVATE).edit()
                .putString(PREF_LOCAL, sb.toString()).apply();
    }

    private static void close(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (java.io.IOException ignored) {
            // 关不上也没什么可做的：内容已经读完了
        }
    }
}
