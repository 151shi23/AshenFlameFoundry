package com.mineways.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 音源的落地面：云控 FLAG + 用户导入的根址 + 用户导入的 JS 脚本。
 *
 * <p>优先级写在 {@link MusicProfile#resolve} 里：云控 &gt; 用户导入（云控有源时本机导入不参与）；
 * 脚本选中后接管搜索与取址。这里只做存储、探活和「给网页吃的配置 JSON」，判定不重复实现。
 */
public final class MusicSources {

    private static final String PREFS = "mw_music";
    private static final String K_USER = "user_roots";       // 每行：名字\t地址
    private static final String K_SCRIPT = "script_id";
    private static final String K_SCRIPT_ON = "script_on";
    private static final String K_PROBE = "probe_";          // + sha1(url) 前 12 位
    private static final String K_VOLUME = "volume";
    private static final String K_OVERLAY = "overlay";
    private static final String K_BAR_X = "bar_x";
    private static final String K_BAR_Y = "bar_y";
    private static final String K_BAR_CONSENT = "bar_consent";   // ask / on / off
    private static final String K_BAR_MINI = "bar_mini";         // 悬浮条折叠态
    private static final String K_BAR_PEEK = "bar_peek";         // 贴边收纳（只露一根边）
    private static final String K_SEEN = "seen_on";
    private static final String K_PICK = "root_pick";      // 界面手选的首选（空 = 按云控顺序）
    private static final String K_SERVER_PICK = "server_pick";  // 界面手选的平台（空 = 跟云控 music_server）

    public static final String SCRIPT_DIR = "music-sources";

    public static final class Probe {
        public final boolean ok;
        public final int code;
        public final int bytes;
        public final String note;
        public final long at;

        Probe(boolean ok, int code, int bytes, String note, long at) {
            this.ok = ok;
            this.code = code;
            this.bytes = bytes;
            this.note = note;
            this.at = at;
        }

        public String text() {
            return (ok ? "可用" : "不可用") + " · HTTP " + code + " · " + bytes + "B"
                    + (note == null || note.length() == 0 ? "" : " · " + note);
        }
    }

    private MusicSources() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- 云控开关

    private static String flag(String key, String def) {
        return com.mineways.cloud.CloudFlags.str(key, def);
    }

    /**
     * 三档能力的判定结果（总闸 / 软件内 / 系统外）。5 秒内复用，避免每次重组都重放整条云控链；
     * 打开音乐页或刷新配置时调 {@link #invalidateGateCache()}。
     */
    public static MusicAccess.Result access(Context c) {
        long now = System.currentTimeMillis();
        MusicAccess.Result cached = sAccess;
        if (cached != null && now - sAccessAt < 5000) {
            return cached;
        }
        MusicAccess.Result fresh = MusicAccessCtx.evaluate(c);
        sAccess = fresh;
        sAccessAt = now;
        return fresh;
    }

    private static volatile MusicAccess.Result sAccess;
    private static volatile long sAccessAt;

    public static boolean gateOpen(Context c) {
        return access(c).main.allowed;
    }

    public static boolean inAppAllowed(Context c) {
        return access(c).inApp.allowed;
    }

    public static boolean backgroundAllowed(Context c) {
        return access(c).background.allowed;
    }

    /** 不支持时给用户看的那句话；空串表示云端让这条不弹。 */
    public static String denyTip(Context c, boolean background) {
        MusicAccess.Result r = access(c);
        return background ? r.background.tip : r.inApp.tip;
    }

    /** 给 Compose 宿主用的短缓存版本（等价于 {@link #gateOpen(Context)}）。 */
    public static boolean gateOpenCached(Context c) {
        return gateOpen(c);
    }

    public static void invalidateGateCache() {
        sAccessAt = 0;
        MusicAccessCtx.invalidate();
    }

    /** 脚本/下载同样是内置能力：默认开，`music_js_off=1` / `music_dl_off=1`（或写成 0）才关。 */
    public static boolean scriptsAllowed(Context c) {
        return gateOpen(c) && !off("music_js");
    }

    public static boolean downloadAllowed(Context c) {
        return gateOpen(c) && !off("music_dl");
    }

    /** 软件内悬浮条也是内置能力：默认允许（还会问用户一次），{@code music_bar_off=1} 才整块禁掉。 */
    public static boolean barAllowed(Context c) {
        return gateOpen(c) && inAppAllowed(c) && !off("music_bar");
    }

    /** 悬浮条该不该画 / 退出时该不该问，全在这一句里（判定本身是纯函数，可跑断言）。 */
    public static MusicBarPolicy barPolicy(Context c) {
        return MusicBarPolicy.of(barConsent(c), barAllowed(c), inAppAllowed(c),
                MusicCtl.active(), MusicOverlay.isRunning());
    }

    /** 用户对「退出后在软件内播放吗」的回答：ask（还没问过）/ on / off。 */
    public static String barConsent(Context c) {
        return MusicBarPolicy.normalize(prefs(c).getString(K_BAR_CONSENT, MusicBarPolicy.ASK));
    }

    public static void setBarConsent(Context c, String value) {
        prefs(c).edit().putString(K_BAR_CONSENT, MusicBarPolicy.normalize(value)).apply();
    }

    /** 悬浮条是不是收成一颗小圆（折叠态记在本机，跨页面保持）。 */
    public static boolean barCollapsed(Context c) {
        return prefs(c).getBoolean(K_BAR_MINI, false);
    }

    public static void setBarCollapsed(Context c, boolean mini) {
        prefs(c).edit().putBoolean(K_BAR_MINI, mini).apply();
    }

    /**
     * 贴边收纳：整条只露一根边，碰它就恢复。应用内与系统悬浮条共用这一个状态 —— 
     * 用户只表达过「现在不想看到它」一次，不该两处各存一份、各藏各的。
     */
    public static boolean barPeek(Context c) {
        return prefs(c).getBoolean(K_BAR_PEEK, false);
    }

    public static void setBarPeek(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_BAR_PEEK, on).apply();
    }

    private static boolean off(String key) {
        return "1".equals(flag(key + "_off", "")) || "0".equals(flag(key, ""));
    }

    /**
     * 用户界面手选的平台，进程内缓存。
     *
     * <p>{@link #server()} 是无参静态方法，调用点有八九处，全改成带 Context 动静太大；
     * 所以进程起来时 {@link #loadPicks} 把偏好读进来，之后 server() 直接看这个值。
     */
    private static String pickCache = null;

    /** 进程启动时调一次，把界面手选的平台读进内存。 */
    public static void loadPicks(Context c) {
        pickCache = prefs(c).getString(K_SERVER_PICK, "");
    }

    /** 界面手选的平台（空 = 跟随云控）。 */
    public static void setServerPick(Context c, String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        prefs(c).edit().putString(K_SERVER_PICK, s).apply();
        pickCache = s;
        invalidateGateCache();
    }

    /**
     * 当前生效的平台。
     *
     * <p><b>用户手选优先，没选才听云控</b> —— 这样用户在网页上切了酷狗，
     * 不会被云控的 music_server 悄悄改回去；清空选择就回到云控口径。
     */
    public static String server() {
        String v = pickCache;
        if (v == null || v.length() == 0) {
            v = flag("music_server", "netease");
        }
        return normServer(v);
    }

    /** qq → tencent，其余不认识的回落 netease。 */
    public static String normServer(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("qq")) {
            return "tencent";                     // 用户嘴里的 QQ，在 Meting 里叫 tencent
        }
        if (s.equals("netease") || s.equals("tencent") || s.equals("kugou")
                || s.equals("kuwo") || s.equals("migu")) {
            return s;
        }
        return "netease";
    }

    // ------------------------------------------------------------- 用户音源

    public static List<String> userLines(Context c) {
        String raw = prefs(c).getString(K_USER, "");
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.trim().length() > 0) {
                out.add(line);
            }
        }
        return out;
    }

    public static void setUserLines(Context c, List<String> lines) {
        prefs(c).edit().putString(K_USER, join(lines, "\n")).apply();
    }

    public static void addUserRoot(Context c, String name, String url) {
        List<String> lines = userLines(c);
        String n = name == null || name.trim().length() == 0 ? MusicProfile.hostOf(url) : name.trim();
        lines.add(n + "\t" + url.trim());
        while (lines.size() > MusicProfile.MAX_ROOTS) {
            lines.remove(0);
        }
        setUserLines(c, lines);
    }

    public static void clearUserRoots(Context c) {
        prefs(c).edit().remove(K_USER).apply();
    }

    /**
     * 「换源」面板里当场删一条本机导入的音源：按名字或地址匹配（不区分大小写），删完顺手把
     * 指向它的手选清掉，免得留一个选不到的首选。
     *
     * @return 删掉的条数
     */
    public static int removeUserRoot(Context c, String nameOrUrl) {
        String want = nameOrUrl == null ? "" : nameOrUrl.trim().toLowerCase(Locale.ROOT);
        if (want.length() == 0) {
            return 0;
        }
        List<String> keep = new ArrayList<>();
        int gone = 0;
        for (String line : userLines(c)) {
            MusicProfile.Root root = MusicProfile.parseRootLine(line);
            String name = root == null ? "" : root.name.toLowerCase(Locale.ROOT);
            String url = root == null ? "" : root.url.toLowerCase(Locale.ROOT);
            if (root != null && (name.equals(want) || url.equals(want))) {
                gone++;
            } else {
                keep.add(line);
            }
        }
        if (gone > 0) {
            setUserLines(c, keep);
            if (want.equalsIgnoreCase(rootPick(c))) {
                setRootPick(c, "");
            }
        }
        return gone;
    }

    // ------------------------------------------------------------- 脚本音源

    public static String scriptId(Context c) {
        return prefs(c).getString(K_SCRIPT, "");
    }

    public static void setScriptId(Context c, String id) {
        prefs(c).edit().putString(K_SCRIPT, id == null ? "" : id).apply();
    }

    public static File scriptDir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), SCRIPT_DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static List<String[]> scripts(Context c) {
        List<String[]> out = new ArrayList<>();
        File idx = new File(scriptDir(c), "index.txt");
        if (!idx.isFile()) {
            return out;
        }
        try {
            String raw = readFile(idx, 64 * 1024);
            for (String line : raw.split("\n")) {
                String[] p = line.split("\t");
                if (p.length >= 3 && MusicProfile.isSafeScriptId(p[0])) {
                    out.add(new String[]{p[0], p[1], p[2]});
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void writeIndex(Context c, List<String[]> list) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String[] s : list) {
            sb.append(s[0]).append('\t').append(s[1]).append('\t').append(s[2]).append('\n');
        }
        writeFile(new File(scriptDir(c), "index.txt"), sb.toString());
        rebuildManifest(c, list);
    }

    /** 网页只能从这里读到脚本清单；文件名由原生生成，脚本本身改不了别人的条目。 */
    private static void rebuildManifest(Context c, List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s[0]);
                o.put("name", s[1]);
                o.put("file", s[2]);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        try {
            writeFile(new File(scriptDir(c), "manifest.json"), arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** 导入一个 .js 音源。返回 null 表示成功，否则是给人看的原因。 */
    public static String importScript(Context c, Uri uri, String displayName) {
        if (!scriptsAllowed(c)) {
            return "云控没有开放音源脚本（music_js 未开）";
        }
        String name = displayName == null ? "source" : displayName;
        if (name.toLowerCase(Locale.ROOT).endsWith(".js")) {
            name = name.substring(0, name.length() - 3);
        }
        String id = safeId(name);
        if (!MusicProfile.isSafeScriptId(id)) {
            return "文件名不能当 id：" + name;
        }
        byte[] data;
        try {
            data = readAll(c.getContentResolver().openInputStream(uri), MusicProfile.MAX_SCRIPT_BYTES + 1);
        } catch (Throwable t) {
            return "读取脚本失败：" + t;
        }
        String reject = MusicProfile.scriptRejectReason(data.length, true);
        if (reject.length() > 0) {
            return reject;
        }
        String file = id + ".js";
        try {
            writeFile(new File(scriptDir(c), file), new String(data, StandardCharsets.UTF_8));
            List<String[]> list = scripts(c);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i)[0].equals(id)) {
                    list.remove(i);
                }
            }
            list.add(new String[]{id, name, file});
            writeIndex(c, list);
            setScriptId(c, id);
            prefs(c).edit().putBoolean(K_SCRIPT_ON, true).apply();
            return null;
        } catch (Throwable t) {
            return "写入脚本失败：" + t;
        }
    }

    /**
     * 云控下发的脚本地址（`music_js_url`，空格或逗号分隔，可以写多个）。
     *
     * <p>直连 raw.githubusercontent.com 国内不通，所以下载时逐个套镜像前缀。
     */
    public static List<String> cloudScriptUrls(Context c) {
        String raw = flag("music_js_url", "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String s : raw.split("[\\s,]+")) {
            String u = s.trim();
            if (u.startsWith("http://") || u.startsWith("https://")) {
                out.add(u);
            }
        }
        return out;
    }

    /** raw 的镜像前缀，最后一个空串是直连（国外网络用得上）。 */
    private static final String[] SCRIPT_MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "",
    };

    /**
     * 从 URL 下载并安装一个音源脚本。
     *
     * <p>为什么要这个：{@link #importScript} 只吃本地 Uri，用户得先自己把文件存到手机上。
     * 云控想「下发就自动装好」就必须能直接拉 URL。
     *
     * @return null 表示成功，否则是给人看的原因
     */
    public static String installFromUrl(Context c, String url) {
        if (!scriptsAllowed(c)) {
            return "云控没有开放音源脚本（music_js 未开）";
        }
        String fileName = url;
        int q = fileName.indexOf('?');
        if (q > 0) {
            fileName = fileName.substring(0, q);
        }
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".js")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        String id = safeId(fileName);
        if (!MusicProfile.isSafeScriptId(id)) {
            return "URL 里的文件名不能当 id：" + fileName;
        }

        Throwable last = null;
        for (String prefix : SCRIPT_MIRRORS) {
            try {
                byte[] data = httpBytes(prefix + url, MusicProfile.MAX_SCRIPT_BYTES + 1);
                String reject = MusicProfile.scriptRejectReason(data.length, true);
                if (reject.length() > 0) {
                    return reject;
                }
                writeFile(new File(scriptDir(c), id + ".js"),
                        new String(data, StandardCharsets.UTF_8));
                List<String[]> list = scripts(c);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i)[0].equals(id)) {
                        list.remove(i);
                    }
                }
                list.add(new String[]{id, fileName, id + ".js"});
                writeIndex(c, list);
                setScriptId(c, id);
                prefs(c).edit().putBoolean(K_SCRIPT_ON, true).apply();
                return null;
            } catch (Throwable t) {
                last = t;
            }
        }
        return "下载失败（镜像都试过了）：" + (last == null ? "未知" : last.getMessage());
    }

    /**
     * 把云控下发的脚本全部同步一遍。
     *
     * @return 成功安装的个数
     */
    public static int syncCloudScripts(Context c) {
        int n = 0;
        for (String u : cloudScriptUrls(c)) {
            try {
                if (installFromUrl(c, u) == null) {
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 简单 GET，带大小上限。 */
    private static byte[] httpBytes(String url, int max) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MinewaysMobile");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            return readAll(conn.getInputStream(), max);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void removeScript(Context c, String id) {
        List<String[]> list = scripts(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i)[0].equals(id)) {
                //noinspection ResultOfMethodCallIgnored
                new File(scriptDir(c), list.get(i)[2]).delete();
                list.remove(i);
            }
        }
        try {
            writeIndex(c, list);
        } catch (Throwable ignored) {
        }
        if (id != null && id.equals(scriptId(c))) {
            setScriptId(c, list.isEmpty() ? "" : list.get(0)[0]);
        }
    }

    private static String safeId(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 32; i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        String s = sb.toString();
        return s.length() == 0 ? "source" : s;
    }

    // --------------------------------------------------------------- 生效配置

    public static MusicProfile.Result effective(Context c) {
        String jsId = scriptsAllowed(c) ? scriptId(c) : "";
        String jsName = "";
        for (String[] s : scripts(c)) {
            if (s[0].equals(jsId)) {
                jsName = s[1];
            }
        }
        MusicProfile.Result r = MusicProfile.resolve(flag("music_api", ""), userLines(c), jsId, jsName, server());
        // 排序是云控的事：以后换首选只改 music_primary / music_order，不用重出包。
        // music_rotate 内置默认 5 条（比内置上限还宽 = 全轮换），云控写 1..5 才收窄。
        MusicProfile.arrange(r, flag("music_primary", ""), flag("music_order", ""), flag("music_rotate", "5"));
        // 界面上手选的那一条挪到队首（本机导入的那几条也允许被手选，那是用户主动动作）。
        // 手选的对象一旦消失 —— 云控下架了，或者本机那条被删了 —— 就自动交回云控顺序，不留空转。
        String pick = rootPick(c);
        if (pick.length() > 0 && !MusicProfile.applyPick(r, pick)) {
            setRootPick(c, "");
        }
        return r;
    }

    // ------------------------------------------------------------ 本机手选首选

    /** 空串 = 按云控给的顺序。 */
    public static String rootPick(Context c) {
        return prefs(c).getString(K_PICK, "");
    }

    /** 记下用户选的首选调用一次；名字给空串就是交回云控顺序。 */
    public static void setRootPick(Context c, String name) {
        prefs(c).edit().putString(K_PICK, name == null ? "" : name.trim()).apply();
    }

    /** 给网页吃的那份配置。没有音源时 backends 为空，页面自己显示「还没有配置音源」。 */
    public static String configJson(Context c) {
        MusicProfile.Result r = effective(c);
        try {
            JSONObject root = new JSONObject();
            JSONObject backends = new JSONObject();
            JSONObject meting = new JSONObject();
            meting.put("base", r.roots.isEmpty() ? "" : r.roots.get(0).url);
            backends.put("meting", meting);
            JSONObject ne = new JSONObject();
            ne.put("base", flag("music_api_netease", ""));
            backends.put("netease", ne);
            root.put("backends", backends);
            root.put("backend", r.roots.isEmpty() ? "" : r.roots.get(0).url);
            root.put("server", r.roots.isEmpty() || r.roots.get(0).server.length() == 0
                    ? server() : r.roots.get(0).server);
            // 网页按这个列表轮换：一个根址挂了就换下一条（每条可带自己的平台）
            JSONArray roots = new JSONArray();
            for (MusicProfile.Root rt : r.roots) {
                JSONObject o = new JSONObject();
                o.put("name", rt.name);
                o.put("url", rt.url);
                o.put("server", rt.server.length() == 0 ? server() : rt.server);
                roots.put(o);
            }
            root.put("roots", roots);
            if (r.rotate > 0) {
                root.put("rotate", r.rotate);      // 坏点时最多换几条（云控 music_rotate）
            }
            root.put("customSource", r.scriptId);
            root.put("proxy", "");
            JSONObject q = new JSONObject();
            q.put("default", 320);
            q.put("download", 320);
            root.put("quality", q);
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ----------------------------------------------------------------- 探活

    /**
     * 根址是否活着：打一个最小搜索，能解析出 JSON 数组就算活。网络调用，必须在后台线程。
     * 关键词用「love」——实测 qijieya 对 `mineways` 回 200 但是空数组，只有常见词能验出「真能搜到东西」。
     */
    public static Probe probe(Context c, MusicProfile.Root root) {
        String base = root.url;
        String srv = root.server.length() == 0 ? server() : root.server;
        long now = System.currentTimeMillis();
        String url = base + (base.contains("?") ? "&" : "?")
                + "server=" + srv + "&type=search&id="
                + URLEncoder.encode("love") + "&format=json";
        Probe p = probeOnce(url, now);
        if (!p.ok && (p.code == 404 || p.code >= 500)) {
            // 一次连着探三条时，服务端/边缘节点偶发回 404 或 5xx（同一条地址隔 0.5 秒又是 200），
            // 不能就此把真源判死 —— 退一下再打一次，结果以第二次为准。
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            Probe again = probeOnce(url, System.currentTimeMillis());
            if (again.ok || p.code <= 0) {
                p = again;
            }
        }
        cacheProbe(c, cacheKey(root, srv), p);
        return p;
    }

    /**
     * 探活的口径必须是**浏览器口径**，不是 curl 口径：不跟着重定向，并且要求响应带
     * {@code Access-Control-Allow-Origin}。原生跟着跳能播不代表网页 fetch 得动 ——
     * 实测少一个结尾斜杠就是「301 无 CORS 头」，探测报可用、页面上却是 Failed to fetch。
     */
    private static Probe probeOnce(String url, long now) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "MinewaysMobile");
            int code = conn.getResponseCode();
            String acao = conn.getHeaderField("Access-Control-Allow-Origin");
            boolean cors = acao != null && acao.length() > 0;
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (cors) {
                    return new Probe(true, code, 0, "会重定向（带 CORS 头，网页能用）", now);
                }
                return new Probe(false, code, 0,
                        "第一跳 " + code + " 的响应没有 CORS 头，网页里 fetch 不通；地址请写成 " + redirectTarget(url, loc), now);
            }
            byte[] body = readPrefix(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), 64 * 1024);
            String text = new String(body, StandardCharsets.UTF_8);
            boolean shape = code == 200 && (text.trim().startsWith("[") || text.contains("\"url\""));
            String note;
            if (!cors) {
                note = "响应没有 Access-Control-Allow-Origin，网页取不到";
            } else if (!shape) {
                note = snippet(text);
            } else {
                note = (text.contains("\"name\"") || text.contains("\"title\"")) ? "" : "空结果";
            }
            return new Probe(shape && cors, code, body.length, note, now);
        } catch (Throwable t) {
            return new Probe(false, -1, 0, String.valueOf(t.getMessage()), now);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 把 301 的目标剪成能直接抄进 music_api 的样子（只留协议+主机+路径，去掉查询串）。 */
    private static String redirectTarget(String from, String loc) {
        String base = (loc == null || loc.length() == 0) ? from : loc;
        int q = base.indexOf('?');
        if (q > 0) {
            base = base.substring(0, q);
        }
        return base;
    }

    public static Probe cachedProbe(Context c, MusicProfile.Root root) {
        String srv = root.server.length() == 0 ? server() : root.server;
        String raw = prefs(c).getString(K_PROBE + probeKey(cacheKey(root, srv)), "");
        if (raw == null || raw.length() == 0) {
            return null;
        }
        String[] p = raw.split("\\|", 5);
        try {
            return new Probe("1".equals(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), p.length > 4 ? p[4] : "", Long.parseLong(p[3]));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cacheKey(MusicProfile.Root root, String srv) {
        return root.url + "#" + srv;
    }

    private static void cacheProbe(Context c, String base, Probe p) {
        try {
            prefs(c).edit().putString(K_PROBE + probeKey(base),
                    (p.ok ? "1" : "0") + "|" + p.code + "|" + p.bytes + "|" + p.at + "|" + p.note).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 同一根址十分钟内不重复打网络；换音源或点「探活」时用 force 绕过。 */
    private static final long PROBE_TTL = 10 * 60 * 1000L;
    /** 失败只记一分钟：瞬时拒绝（404/5xx/断网）不该把一条真源钉住十分钟。 */
    private static final long PROBE_FAIL_TTL = 60 * 1000L;

    private static volatile boolean sProbing;

    /**
     * 后台探一遍当前生效的音源，结果写进缓存，状态行/诊断/音源面板都只读缓存。
     * 打开音乐页、页面 ready、换音源之后各调一次；同时只允许跑一轮。
     */
    public static void probeAllAsync(Context c, boolean force, Runnable onDone) {
        if (sProbing) {
            return;
        }
        List<MusicProfile.Root> pending = new ArrayList<>();
        long now = System.currentTimeMillis();
        MusicProfile.Result r = effective(c);
        List<MusicProfile.Root> all = new ArrayList<>(r.roots);
        all.addAll(r.userOnly);      // 本机导入的那几条也要有探活结果，换源面板才敢列出来
        for (MusicProfile.Root root : all) {
            Probe p = cachedProbe(c, root);
            long ttl = p != null && !p.ok ? PROBE_FAIL_TTL : PROBE_TTL;
            if (force || p == null || now - p.at > ttl) {
                pending.add(root);
            }
        }
        probeAsync(c, pending, onDone);
    }

    /**
     * 探一批指定的根址（音源面板里「还没保存的那几行」也走这里）。同样在后台线程跑。
     * <p>{@code onEach} 每探完一条就回调一次，界面能逐条出结果 —— 一轮要几秒钟，
     * 只在最后回调会让用户盯着满屏「未探活」以为没跑。
     */
    public static void probeAsync(Context c, List<MusicProfile.Root> roots, Runnable onDone) {
        if (roots == null || roots.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (sProbing) {
            return;
        }
        sProbing = true;
        final Context app = c.getApplicationContext();
        final List<MusicProfile.Root> list = new ArrayList<>(roots);
        new Thread(() -> {
            try {
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        // 连着打三条时服务端/边缘节点偶发回 404，隔开半秒就不是这个结果了（实测）
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    probe(app, list.get(i));
                    if (onDone != null) {
                        onDone.run();          // 逐条回报，界面立刻出这一行的结果
                    }
                }
            } finally {
                sProbing = false;
            }
        }, "mw-music-probe").start();
    }

    /** 「探活 2/3 可用」；一条都没探过就说未探活，别让用户以为音源是好的。 */
    public static String probeSummary(Context c, List<MusicProfile.Root> roots) {
        int known = 0, ok = 0;
        for (MusicProfile.Root root : roots) {
            Probe p = cachedProbe(c, root);
            if (p != null) {
                known++;
                if (p.ok) {
                    ok++;
                }
            }
        }
        if (known == 0) {
            return "未探活";
        }
        return "探活 " + ok + "/" + known + " 可用";
    }

    /** 三条云控音源常常是同一个地址不同平台，光看 url 分不出来，标签必须带上**实际生效**的平台。 */
    public static String label(MusicProfile.Root root) {
        return (root.name.length() == 0 ? MusicProfile.hostOf(root.url) : root.name)
                + "[" + MusicProfile.resolvedServer(root, server()) + "]";
    }

    private static String probeKey(String base) {
        long h = 1125899906842597L;
        for (int i = 0; i < base.length(); i++) {
            h = 31 * h + base.charAt(i);
        }
        return Long.toHexString(Math.abs(h));
    }

    // ---------------------------------------------------------- 关于页状态行

    public static String statusLine(Context c) {
        MusicAccess.Result acc = access(c);
        StringBuilder sb = new StringBuilder();
        sb.append("总闸 ").append(acc.main.allowed ? "开" : "关（" + acc.main.reason + "）");
        sb.append(" · 软件内 ").append(acc.inApp.allowed ? "开" : "关（" + acc.inApp.reason + "）");
        sb.append(" · 系统外 ").append(acc.background.allowed ? "开" : "关（" + acc.background.reason + "）");
        if (!acc.main.allowed) {
            for (String note : acc.notes) {
                sb.append(" · ").append(note);
            }
            return sb.toString();
        }
        MusicProfile.Result r = effective(c);
        int delivered = MusicProfile.parseCloud(flag("music_api", "")).size();
        sb.append(" · 在用 ").append(r.roots.size()).append(" 条（云控下发 ").append(delivered)
                .append(" · 本机可换 ").append(r.userOnly.size()).append("）");
        if (!r.roots.isEmpty()) {
            Probe p = cachedProbe(c, r.roots.get(0));
            sb.append(" · 首选 ").append(label(r.roots.get(0)));
            sb.append(p == null ? " · 未探活" : " · " + p.text());
            sb.append(" · ").append(probeSummary(c, r.roots));
            String pick = rootPick(c);
            if (pick.length() > 0) {
                sb.append(" · 本机手选 ").append(pick);
            }
            // 印「实际会换几条」，不是印云端写的原始值：写 5 但只有 3 条时页面最多试 3 条
            int cap = r.rotate > 0 ? Math.min(r.rotate, r.roots.size()) : r.roots.size();
            sb.append(" · 坏点最多换 ").append(cap).append(" 条");
        }
        if (r.scriptId.length() > 0) {
            sb.append(" · 脚本 ").append(r.scriptName);
        } else if (scriptsAllowed(c)) {
            sb.append(" · 脚本未选");
        }
        for (String problem : r.problems) {
            sb.append(" · ").append(problem);
        }
        for (String note : acc.notes) {
            sb.append(" · ").append(note);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 杂项存储

    public static float volume(Context c) {
        return prefs(c).getFloat(K_VOLUME, 1f);
    }

    public static void setVolume(Context c, float v) {
        prefs(c).edit().putFloat(K_VOLUME, Math.max(0f, Math.min(1f, v))).apply();
    }

    /**
     * 是否允许「退出软件后」显示系统悬浮条。默认开 —— 有没有窗权限由系统决定，
     * 没有权限就不会真的出现。软件内那条的显隐看 {@link MusicOverlay#isRunning()}，
     * 不能看这个偏好，否则一开 App 里的条就被它顶没了。
     */
    public static boolean overlayOn(Context c) {
        return prefs(c).getBoolean(K_OVERLAY, true);
    }

    public static void setOverlayOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_OVERLAY, on).apply();
    }

    public static int barX(Context c) {
        return prefs(c).getInt(K_BAR_X, -1);
    }

    public static int barY(Context c) {
        return prefs(c).getInt(K_BAR_Y, -1);
    }

    public static void setBarPos(Context c, int x, int y) {
        prefs(c).edit().putInt(K_BAR_X, x).putInt(K_BAR_Y, y).apply();
    }

    // --------------------------------------------------------------- 工具

    public static String readFile(File f, int max) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            return new String(readAll(in, max), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void writeFile(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!tmp.renameTo(f)) {
            throw new java.io.IOException("无法写入 " + f.getName());
        }
    }

    public static byte[] readAll(InputStream in, int max) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new java.io.IOException("响应超过 " + max + " 字节");
                }
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return bos.toByteArray();
    }

    /**
     * 只取前 max 字节就收工，**不因为超量而抛**。探活要判断的是「有没有回 JSON 数组」，
     * 用带异常的上限读整页会把一次正常搜索（响应 >8 KB）判成接口不可用 —— 真机上就栽在这里。
     */
    static byte[] readPrefix(InputStream in, int max) {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(max, 16384));
        byte[] buf = new byte[8192];
        try {
            int n;
            while (bos.size() < max && (n = in.read(buf)) > 0) {
                bos.write(buf, 0, Math.min(n, max - bos.size()));
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return bos.toByteArray();
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
