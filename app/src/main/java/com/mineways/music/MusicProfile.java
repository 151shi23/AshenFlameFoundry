package com.mineways.music;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 音源配置的纯判定层（零安卓依赖，可 javac 直接跑断言）。
 *
 * <p>生效顺序：<b>云控下发 &gt; 用户导入</b> —— 云控有源时就按云控那几条轮换和显示，
 * 本机导入的只在云控一条都没下发时顶上（脚本音源仍由用户自己选中，一旦选中就整体接管
 * search/song/url/lyric）。包里绝不含任何公共实例 —— 没配就是没配，界面显示「还没有配置音源」。
 *
 * <pre>
 * FLAG key=music_api    to=https://a.example/meting/;备用=https://b.example/meting/
 * FLAG key=music_server to=netease|tencent|kugou|kuwo|migu   # 只影响 Meting 根址的音源
 * FLAG key=music_js     to=1          # 允许用户导入 / 运行 JS 音源脚本
 * FLAG key=music_dl     to=1          # 允许下载
 * FLAG key=music_on     to=1          # 功能入口总闸（不配 = 不露出）
 * FLAG key=music_off    to=1          # 紧急停发
 * FLAG key=music_primary to=咪咕      # 首选哪条（按名字 / 平台 / 主机名匹配，匹配不到就不改顺序）
 * FLAG key=music_order  to=咪咕,酷狗,网易   # 整队顺序，没点名的按原顺序跟在后面
 * FLAG key=music_rotate to=2         # 坏点时最多换几条（1..5），不配就按「有几条换几条」
 * </pre>
 */
public final class MusicProfile {

    /**
     * 轮换列表上限。4 条太紧：实测公共实例大多是「一个站挂一个平台」，要凑出跨平台的几条有效源
     * 就得写七八条（2026-10-23 他要求加有效源时放宽到 12）。超出的条目仍然会被截掉并记一条诊断。
     */
    public static final int MAX_ROOTS = 12;
    public static final int MAX_URL = 300;
    public static final int MAX_NAME = 24;
    /** 音源脚本体积上限：超过一律不收（沙箱再宽也不该喂它几 MB 的代码）。 */
    public static final int MAX_SCRIPT_BYTES = 256 * 1024;

    public static final class Root {
        public final String name;
        public final String url;
        /** Meting 平台：netease / tencent / kugou / kuwo / migu；空 = 跟随全局 music_server。 */
        public final String server;

        public Root(String name, String url) {
            this(name, url, "");
        }

        public Root(String name, String url, String server) {
            this.name = name;
            this.url = url;
            this.server = server == null ? "" : server;
        }

        @Override
        public String toString() {
            return name + "=" + url + (server.length() == 0 ? "" : "#" + server);
        }
    }

    public static final class Result {
        public final List<Root> roots = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
        public String scriptId = "";
        public String scriptName = "";
        public String scriptError = "";
        /** 云控 `music_primary` 点名的首选（原样存着，只给诊断看）。 */
        public String primary = "";
        /** 云控 {@code music_rotate}：0 = 不覆盖，网页按「有几条换几条」轮换。 */
        public int rotate;
        /** true = 生效的这几条全部来自云控；本机导入的那几条默认不参与轮换和显示。 */
        public boolean cloudWins;
        /**
         * 本机导入、但因为云控有源而没进轮换的那些。<b>它们仍然可以在顶栏「换源」里被手选</b>
         * （手选是用户主动动作，允许越过默认顺序），并且可以当场删除。
         */
        public final List<Root> userOnly = new ArrayList<>();

        public boolean usable() {
            return !roots.isEmpty() || scriptId.length() > 0;
        }
    }

    private MusicProfile() {
    }

    // ------------------------------------------------------------------ 解析

    public static Result resolve(String cloudSpec, List<String> userLines,
                                 String scriptId, String scriptName) {
        return resolve(cloudSpec, userLines, scriptId, scriptName, "");
    }

    /**
     * @param defaultServer 云控 {@code music_server} 的值。平台留空的条目<b>按解析后的平台</b>参与去重：
     *        「咪咕=https://…（没写 #migu）」其实走的是 netease，不解析就会和「咪咕=https://…#migu」
     *        当成两条一起进轮换 —— 真机面板上那个重复的「咪咕」就是这么来的。
     */
    public static Result resolve(String cloudSpec, List<String> userLines,
                                 String scriptId, String scriptName, String defaultServer) {
        Result r = new Result();
        String def = defaultServer == null ? "" : defaultServer.trim();

        List<Root> user = new ArrayList<>();
        Set<String> seenUser = new HashSet<>();
        if (userLines != null) {
            for (String line : userLines) {
                Root root = parseRootLine(line);
                if (root == null) {
                    continue;
                }
                if (!isSafeUrl(root.url)) {
                    r.problems.add("本机音源被拒绝（只接受 https、且不能指向内网）" + shorten(root.url));
                    continue;
                }
                addUnique(user, seenUser, root, def, r.problems);
            }
        }

        List<Root> cloud = new ArrayList<>();
        Set<String> seenCloud = new HashSet<>();
        for (Root root : parseCloud(cloudSpec)) {
            addUnique(cloud, seenCloud, root, def, r.problems);
        }

        // 云控有源时，默认轮换与显示就完全按云控那几条走；本机导入的留着当「可手选、可删除」的一组，
        // 顶栏「换源」里能选它们（手选是用户主动动作），但不会被塞进默认轮换。
        // 两边的查重集合是分开的，否则用户那条会把云控的同址条目挤掉 —— 那就是「云控是什么不是什么」。
        r.roots.addAll(!cloud.isEmpty() ? cloud : user);
        r.cloudWins = !cloud.isEmpty();
        if (!cloud.isEmpty()) {
            // 面板里不出现两条一模一样的：本机那条如果和云控某条同址同平台，就不必再列一遍
            for (Root root : user) {
                if (!seenCloud.contains(key(root, def))) {
                    r.userOnly.add(root);
                }
            }
            if (!r.userOnly.isEmpty()) {
                r.problems.add("默认用云控那 " + cloud.size() + " 条；本机导入的 " + r.userOnly.size()
                        + " 条不自动轮换，可在顶栏「换源」里选，选了就优先用那条");
            }
        }

        if (r.roots.size() > MAX_ROOTS) {
            r.problems.add("音源过多，只保留前 " + MAX_ROOTS + " 个");
            while (r.roots.size() > MAX_ROOTS) {
                r.roots.remove(r.roots.size() - 1);
            }
        }

        String id = scriptId == null ? "" : scriptId.trim();
        if (id.length() > 0) {
            if (!isSafeScriptId(id)) {
                r.scriptError = "音源脚本 id 非法：" + shorten(id);
            } else {
                r.scriptId = id;
                r.scriptName = scriptName == null || scriptName.trim().length() == 0
                        ? id : scriptName.trim();
            }
        }
        return r;
    }

    /**
     * 云控排序：<b>order 先整队，primary 再把某一条顶到第一位</b>，和 {@code music_api} 里书写的顺序一样，
     * 以后换首选只改这两行，不用重出包。
     *
     * <p>匹配口径：名字（忽略大小写与空格）&gt; 平台名 &gt; 主机名 &gt; 互相包含。
     * 兜底：一个 token 都没匹配上就<b>保持原顺序</b>并把这条写进 {@code problems}（诊断里看得见），
     * 绝不因为云端多打一个字就把音源列表清空；空值等于不干预。
     */
    public static void arrange(Result r, String primary, String order, String rotate) {
        r.rotate = rotateOf(rotate);
        r.primary = primary == null ? "" : primary.trim();
        List<Root> in = new ArrayList<>(r.roots);
        if (order != null && order.trim().length() > 0) {
            List<Root> out = new ArrayList<>();
            List<String> done = new ArrayList<>();
            boolean any = false;
            for (String token : order.split("[;,\\n，、|]+")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.length() == 0) {
                    continue;
                }
                if (done.contains(t)) {
                    continue;      // 云端把同一条写两遍是手滑，不是两条音源：首次出现生效，后面静默丢掉
                }
                done.add(t);
                Root hit = pick(in, token);
                if (hit == null) {
                    r.problems.add("music_order 里没匹配到这条音源：" + shorten(token.trim()));
                    continue;
                }
                if (in.remove(hit)) {
                    out.add(hit);
                    any = true;
                }
            }
            if (any) {
                out.addAll(in);          // 没被点名的按原顺序排在后面，一条都不丢
                r.roots.clear();
                r.roots.addAll(out);
            }
        }
        if (primary != null && primary.trim().length() > 0) {
            Root hit = pick(r.roots, primary);
            if (hit == null) {
                r.problems.add("music_primary 没匹配到任何音源：" + shorten(primary.trim()));
            } else {
                r.roots.remove(hit);
                r.roots.add(0, hit);
            }
        }
    }

    /**
     * 本机手选的首选：把匹配上的那条挪到第 0 位。<b>只在已经生效的那几条里挪，不新增也不删</b> ——
     * 云控下发了什么还是什么，用户能选的只是「先用哪一条」。
     *
     * @return true 表示找到了那条；匹配口径同 {@code pick}（名字 &gt; 平台 &gt; 主机名 &gt; 互相包含）
     */
    /**
     * 界面「换源」的手选：先在被云控压住之外的那组（也就是当前生效列表）里找；找不到再去
     * <b>本机导入的那一组</b>找 —— 手选是用户主动动作，所以允许把一条本机音源插到队首，
     * 云控那几条依次排在它后面继续轮换。集合始终不超过 {@code MAX_ROOTS} 条。
     * 云控把某条撤掉（下架）后原手选哪儿都找不到：不动顺序、记一条诊断，等用户重新选。
     *
     * @return true 表示匹配上了
     */
    public static boolean applyPick(Result r, String pick) {
        if (r == null || pick == null || pick.trim().length() == 0) {
            return false;
        }
        Root hit = pick(new ArrayList<>(r.roots), pick);
        if (hit != null) {
            r.roots.remove(hit);
            r.roots.add(0, hit);
            return true;
        }
        Root mine = pick(new ArrayList<>(r.userOnly), pick);
        if (mine != null) {
            r.userOnly.remove(mine);
            r.roots.add(0, mine);
            r.problems.add("手选用了本机导入的这条：" + shorten(mine.name) + "（云控那几条排在它后面轮换）");
            return true;
        }
        r.problems.add("本机手选的音源已经不在列表里（云控可能已下架）：" + shorten(pick.trim()));
        return false;
    }

    /** 先按精确口径找（名字 / 平台 / 主机），都找不到才允许互相包含，避免「咪」这种短词抢错位置。 */
    private static Root pick(List<Root> list, String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.length() == 0) {
            return null;
        }
        for (Root root : list) {
            String name = root.name.toLowerCase(Locale.ROOT).replace(" ", "");
            if (name.equals(t) || root.server.toLowerCase(Locale.ROOT).equals(t)
                    || hostOf(root.url).toLowerCase(Locale.ROOT).equals(t)) {
                return root;
            }
        }
        for (Root root : list) {
            String name = root.name.toLowerCase(Locale.ROOT).replace(" ", "");
            if (name.contains(t) || t.contains(name) || root.url.toLowerCase(Locale.ROOT).contains(t)) {
                return root;
            }
        }
        return null;
    }

    /** `music_rotate` 允许的上限：比 MAX_ROOTS 宽一档，云端写了但只配了三条时按条数自动收敛。 */
    public static final int MAX_ROTATE = 5;

    /** `music_rotate`：坏点时最多换几条。1..MAX_ROTATE 之外的值和空值都返回 0 = 不覆盖内置行为。 */
    public static int rotateOf(String v) {
        if (v == null) {
            return 0;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= 1 && n <= MAX_ROTATE) {
                return n;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /** 云控 `music_api`：分号/逗号/顿号/换行分隔，每项可以是 `名字=https://…` 或裸地址。 */
    public static List<Root> parseCloud(String spec) {
        List<Root> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String token : spec.split("[;,\\n，、|]+")) {
            Root root = parseRootLine(token);
            if (root == null) {
                continue;
            }
            if (!isSafeUrl(root.url)) {
                continue;   // 云控写错一条不连坐其它条，界面上由 probe 结果体现
            }
            out.add(root);
        }
        return out;
    }

    public static Root parseRootLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.length() == 0 || s.startsWith("#")) {
            return null;
        }
        String name = "";
        String url = s;
        int tab = s.indexOf('\t');
        int scheme = s.indexOf("://");
        int eq = s.indexOf('=');
        if (tab > 0) {
            // 本机存储用「名字\t地址」：tab 在地址之前才算名字
            name = s.substring(0, tab).trim();
            url = s.substring(tab + 1).trim();
        } else if (eq > 0 && (scheme < 0 || eq < scheme)) {
            // 只有等号出现在 :// 之前才算「名字=地址」，否则 query 里的 = 会把地址切坏
            name = s.substring(0, eq).trim();
            url = s.substring(eq + 1).trim();
        }
        url = keepApiPath(url);

        // 末尾 #netease 这种写法 = 这个根址固定用哪个平台；不是平台名就当普通地址
        String server = "";
        int hash = url.lastIndexOf('#');
        if (hash > 0 && hash < url.length() - 1) {
            String srv = url.substring(hash + 1).trim().toLowerCase(Locale.ROOT);
            if (isServer(srv)) {
                server = srv;
                url = keepApiPath(url.substring(0, hash).trim());
            }
        }
        if (url.length() == 0) {
            return null;
        }
        if (name.length() == 0) {
            name = hostOf(url);
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        return new Root(name, url, server);
    }

    public static boolean isServer(String s) {
        return "netease".equals(s) || "tencent".equals(s) || "kugou".equals(s)
                || "kuwo".equals(s) || "migu".equals(s);
    }

    /** 去重键：同一个根址挂不同平台是三条音源，不能互相顶掉。 */
    private static String key(Root r, String defaultServer) {
        // 查重按「去掉结尾斜杠」比对：请求地址要照抄（带不带斜杠决定会不会撞 301），
        // 但同一个接口不应该因为多一个斜杠就被算成两条音源。
        String u = r.url;
        while (u.length() > 1 && u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u + "#" + resolvedServer(r, defaultServer);
    }

    /** 这条音源实际走哪个平台：写了 {@code #kugou} 就按写的，没写就跟着 {@code music_server}。 */
    public static String resolvedServer(Root r, String defaultServer) {
        if (r.server.length() > 0) {
            return r.server;
        }
        String d = defaultServer == null ? "" : defaultServer.trim().toLowerCase(Locale.ROOT);
        return d.length() == 0 ? "netease" : d;
    }

    /**
     * 收进生效列表：同址同平台只留第一条，被去掉的那条<b>在诊断里说清楚它和谁重复</b> ——
     * 面板上出现两条同名音源时，这行就是唯一的解释。
     */
    private static void addUnique(List<Root> roots, Set<String> seen, Root root,
                                  String defaultServer, List<String> problems) {
        String k = key(root, defaultServer);
        if (seen.add(k)) {
            roots.add(root);
            return;
        }
        for (Root kept : roots) {
            if (key(kept, defaultServer).equals(k)) {
                problems.add("重复音源已去掉：" + root.name + "（与 " + kept.name + " 同为 "
                        + resolvedServer(root, defaultServer) + "）");
                return;
            }
        }
    }

    /**
     * 只收 https + 公网主机。内网/本机/用户名密码一律拒绝：
     * 云控一旦被投毒，这一条会决定手机的请求能不能打到局域网里的路由器。
     */
    public static boolean isSafeUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim();
        if (u.length() < 10 || u.length() > MAX_URL) {
            return false;
        }
        if (!u.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return false;
        }
        String rest = u.substring(8);
        if (rest.indexOf('@') >= 0) {
            return false;                         // 不允许 user:pass@
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c <= 0x20 || c == '\\' || c == '"' || c == '<' || c == '>') {
                return false;
            }
        }
        String host = hostOf(u).toLowerCase(Locale.ROOT);
        if (host.length() == 0) {
            return false;
        }
        if (host.startsWith("localhost") || host.endsWith(".local") || host.equals("::1")) {
            return false;
        }
        return !isPrivateV4(host) && !isPrivateV6(host);
    }

    private static boolean isPrivateV4(String host) {
        String[] p = host.split("\\.");
        if (p.length != 4) {
            return false;
        }
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].length() == 0 || p[i].length() > 3) {
                return false;
            }
            for (int j = 0; j < p[i].length(); j++) {
                if (!Character.isDigit(p[i].charAt(j))) {
                    return false;
                }
            }
            n[i] = Integer.parseInt(p[i]);
            if (n[i] > 255) {
                return false;
            }
        }
        if (n[0] == 10 || n[0] == 127 || n[0] == 0) {
            return true;
        }
        if (n[0] == 169 && n[1] == 254) {
            return true;
        }
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) {
            return true;
        }
        return n[0] == 192 && n[1] == 168;
    }

    private static boolean isPrivateV6(String host) {
        return host.startsWith("[") || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd");
    }

    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int i = u.indexOf("://");
        if (i > 0) {
            u = u.substring(i + 3);
        }
        int end = u.length();
        for (int k = 0; k < u.length(); k++) {
            char c = u.charAt(k);
            if (c == '/' || c == '?' || c == '#') {
                end = k;
                break;
            }
        }
        String host = u.substring(0, end);
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    public static boolean isSafeScriptId(String id) {
        if (id == null || id.length() == 0 || id.length() > 48) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return !id.startsWith(".") && !id.contains("..");
    }

    /** 供断言使用：脚本治理口径集中在一处，别在别处再写一遍。 */
    public static String scriptRejectReason(int bytes, boolean jsAllowed) {
        if (!jsAllowed) {
            return "云控没有开放音源脚本（music_js 未开）";
        }
        if (bytes <= 0) {
            return "脚本是空文件";
        }
        if (bytes > MAX_SCRIPT_BYTES) {
            return "脚本超过 " + (MAX_SCRIPT_BYTES / 1024) + " KB 上限";
        }
        return "";
    }

    /**
     * 音源地址要**照抄云端写的那个样子**，不能顺手把结尾的 <code>/</code> 去掉：
     * {@code api.qijieya.cn/meting}（没斜杠）的第一跳是 <b>301</b>，而那条 301 响应里
     * <b>没有 Access-Control-Allow-Origin</b> —— 原生和 curl 会跟着跳，看着一切正常；
     * 网页里的 fetch 却在第一跳就被 CORS 拦死，用户拿到的就是 Failed to fetch。
     * 实测：<code>/meting/</code>（带斜杠）五个 type 全部 200 且 <code>ACAO: *</code>。
     * 这里只把多余的斜杠并掉，不再去掉结尾那一个。
     */
    private static String keepApiPath(String s) {
        String u = s.trim();
        while (u.endsWith("//")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 48 ? s : s.substring(0, 45) + "…";
    }
}
