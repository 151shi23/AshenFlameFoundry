package com.mineways;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.util.Linkify;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 启动公告（云控）：读工具箱仓库根目录的 <code>ovo.txt</code>，命中条件的在进主界面时弹一次。
 *
 * <h3>文件放哪</h3>
 * <pre>
 *   仓库 151shi23/AshenFlameFoundry  main 分支 根目录  ovo.txt
 *   拉取链：raw.githubusercontent.com → ghproxy.net → raw.gitmirror.com → jsDelivr
 *   10 分钟内只联网一次；拉到内容会缓存，断网时用 24 小时内的旧内容兜底。
 * </pre>
 *
 * <h3>极简写法</h3>
 * <pre>
 *   直接写一段文字 → 所有人、每次启动都弹。
 * </pre>
 *
 * <h3>定向写法：指令 + 空行分块，多条公告用一行 --- 隔开</h3>
 * <pre>
 *   id: national-day                 # 可选，用于「已读」；不给就按内容算
 *   level: alert                     # info(默认) | warn | alert | success，决定标题配色
 *   to: 已登录, 有码                  # 人群简写，见下表；逗号 = 任一命中
 *   when: version_code &gt;= 508 &amp;&amp; uid 在 ("1001","1002")
 *   keyword: qq.com, 张*, /^15\d{9}$/ # 用户名/邮箱/uid/机型 命中任一
 *   not_keyword: 测试, vip
 *   percent: 30                       # 稳定灰度：30% 的用户能看到（同一设备结果固定）
 *   from: 2026-10-01 00:00            # 生效时间（yyyy-MM-dd[ HH:mm] 或毫秒）
 *   until: 2026-10-08 23:59           # 过期时间
 *   min_version: 508                  # 只给 version_code ≥ 508 的版本看
 *   min_android: 26
 *   once: day                         # always(默认) | day | week | month | once(永久)
 *   title: 国庆福利
 *   url: https://wg5p835kmb.coze.site # 可选：点标题打开
 *   body: 今天签到可以多领 1 个激活码
 *   body: 你的码：{{code}}             # {{变量}} 会被替换
 *   copy: 复制签到地址|https://...     # 可选：加一个复制按钮
 *   button: 去签到|url|https://wg5p835kmb.coze.site/checkin-codes
 *   button: 知道了|close
 * </pre>
 *
 * <p><b>to 的人群简写</b>：{@code 所有人/all}、{@code 已登录/login}、{@code 未登录/guest}、
 * {@code 白名单/whitelist}、{@code 有码/未兑换/code}、{@code 没码/no_code}、
 * {@code 新用户/first_run}、{@code 老用户/old}。多个用逗号隔开 = 任一命中。
 *
 * <p><b>按钮动作</b>：{@code close}（默认）/ {@code url|地址} / {@code copy|文本} /
 * {@code checkin}（打开签到领码页）。
 *
 * <h3>可用变量（when 表达式与 {{}} 占位符共用）</h3>
 * <pre>
 *   登录    logged_in  oauth_logged_in  pro_logged_in  authorized  username  nickname
 *           email  uid  token_state
 *   激活    code  code_unused  code_state  code_feature
 *   版本    version  version_name  version_code  app_id  app_name
 *   系统    android  sdk_int  android_name  brand  model  manufacturer  device  hardware
 *           abi  locale  lang  region  timezone  screen_w  screen_h  density  su(疑似 root)
 *   时间    date  time  ts  hour  minute  weekday  weekday_name  year  month  day  is_weekend
 *   使用    install_days  launches  first_at  last_at  days_since_notice  is_first_run
 *   灰度    device_hash  roll(0-99 稳定)
 *   常量    repo_url  checkin_url  qq_group
 * </pre>
 *
 * <p>表达式语法（且/或/非、包含/匹配/开头是/结尾是、在…里、以及一堆函数）见 {@link Expr}。
 * 任何一步出错都只影响这次弹窗，不会拦住启动。
 */
public final class AnnouncementCenter {

    private static final String REPO = "151shi23/AshenFlameFoundry";
    private static final String BRANCH = "main";
    private static final String RAW = "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/ovo.txt";
    /** 多源兜底：国内直连 GitHub 常不通，镜像按顺序试。 */
    private static final String[] SOURCES = {
            RAW,
            "https://ghproxy.net/" + RAW,
            "https://raw.gitmirror.com/" + REPO + "/" + BRANCH + "/ovo.txt",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@" + BRANCH + "/ovo.txt",
    };
    private static final int TIMEOUT_MS = 8000;
    /** 同一进程只查一次（Activity 重建不重复弹）。 */
    private static boolean checkedThisProcess;
    /** 一次启动最多弹几条（命中的按顺序排队）。 */
    private static final int MAX_PER_LAUNCH = 3;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long STALE_OK_MS = 24 * 3600 * 1000L;

    private static final String PREFS = "aff_notice";
    private static final String K_TEXT = "text";
    private static final String K_AT = "at";
    private static final String K_FIRST = "first_at";
    private static final String K_LAST = "last_at";
    private static final String K_LAUNCH = "launches";
    private static final String K_SEEN = "seen_";

    private AnnouncementCenter() {
    }

    // ---------------------------------------------------------------- 对外入口

    /** 进主界面时调用：后台拉 ovo.txt，命中就弹。没文件 / 拉不到 / 没命中 → 什么都不做。 */
    public static void checkAndShow(final Activity activity) {
        if (activity == null || checkedThisProcess) {
            return;
        }
        checkedThisProcess = true;
        final Context app = activity.getApplicationContext();
        bumpLaunch(app);
        final boolean cacheFresh = System.currentTimeMillis() - prefs(app).getLong(K_AT, 0L) < CACHE_TTL_MS;

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = cacheFresh ? prefs(app).getString(K_TEXT, "") : fetch(app);
                final List<Notice> list = parse(text);
                if (list.isEmpty()) {
                    return;
                }
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        showFirstMatch(activity, list);
                    }
                });
            }
        }, "notice-check").start();
    }

    private static void showFirstMatch(final Activity activity, List<Notice> list) {
        final Map<String, String> vars = vars(activity);
        final List<Notice> hits = new ArrayList<>();
        for (final Notice n : list) {
            if (seen(activity, n)) {
                continue;
            }
            boolean hit;
            try {
                hit = toHit(n.to, vars) && keywordHit(n, vars) && timeHit(n)
                        && rangeHit(n, vars) && percentHit(n, vars);
                if (hit && n.when.length() > 0) {
                    hit = Expr.test(n.when, vars);
                }
            } catch (Expr.Error e) {
                hit = false;     // 条件写错 = 这条不发，不影响其它条
            }
            if (hit) {
                hits.add(n);
                if (hits.size() >= MAX_PER_LAUNCH) {
                    break;       // 一次最多弹 3 条，不轰炸
                }
            }
        }
        showNext(activity, hits, vars, 0);
    }

    /** 命中的公告排队弹：关掉一条自动弹下一条（常驻公告不会挡住后面新增的）。 */
    private static void showNext(final Activity activity, final List<Notice> hits,
                                 final Map<String, String> vars, final int index) {
        if (index >= hits.size() || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        show(activity, hits.get(index), vars, new Runnable() {
            @Override
            public void run() {
                showNext(activity, hits, vars, index + 1);
            }
        });
    }

    // ---------------------------------------------------------------- 网络

    private static String fetch(Context app) {
        for (String url : SOURCES) {
            String text = get(url);
            if (text != null && text.trim().length() > 0) {
                prefs(app).edit()
                        .putString(K_TEXT, text)
                        .putLong(K_AT, System.currentTimeMillis())
                        .apply();
                return text;
            }
            if (text != null) {          // 200 但空内容 = 你没写公告，别再用旧缓存
                prefs(app).edit().putString(K_TEXT, "").putLong(K_AT, System.currentTimeMillis()).apply();
                return "";
            }
        }
        long at = prefs(app).getLong(K_AT, 0L);
        if (at > 0 && System.currentTimeMillis() - at < STALE_OK_MS) {
            return prefs(app).getString(K_TEXT, "");
        }
        return "";
    }

    /** @return 200 的正文；其它错误返回 null（换下一个源）。 */
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AshenFlameFoundry-Notice");
            c.setRequestProperty("Accept", "text/plain, */*");
            if (c.getResponseCode() != 200) {
                return null;
            }
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 20000) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 解析

    /** 一条公告。 */
    public static final class Notice {
        String id = "";
        String when = "";
        String to = "";
        String keyword = "";
        String notKeyword = "";
        String level = "info";
        String once = "always";
        String title = "";
        String url = "";
        String copy = "";
        double percent = -1;
        long from;
        long until;
        int minVersion = -1;
        int maxVersion = -1;
        int minAndroid = -1;
        final StringBuilder body = new StringBuilder();
        final List<String[]> buttons = new ArrayList<>();   // {文案, 动作, 参数}

        String key() {
            if (id.length() > 0) {
                return id;
            }
            String s = title + '\u0000' + body;
            return "h" + Integer.toHexString(s.hashCode()) + "_" + s.length();
        }
    }

    /** 解析 ovo.txt：空 → 空列表；一条指令都没有 → 整段当一条纯文本公告。 */
    static List<Notice> parse(String text) {
        List<Notice> out = new ArrayList<>();
        if (text == null || text.trim().length() == 0) {
            return out;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Notice cur = new Notice();
        boolean hasDirective = false;
        boolean sawAny = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if ("---".equals(trimmed) || "***".equals(trimmed) || "===".equals(trimmed)) {
                flush(out, cur, hasDirective);
                cur = new Notice();
                hasDirective = false;
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;                      // 注释
            }
            int colon = trimmed.indexOf(':');
            String key = (colon > 0) ? trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            String val = (colon > 0) ? trimmed.substring(colon + 1).trim() : "";
            if (colon <= 0 || !isDirective(key)) {
                appendBody(cur, line);         // 不是指令 → 当正文（纯文本公告走这里）
                continue;
            }
            hasDirective = true;
            sawAny = true;
            if ("id".equals(key)) {
                cur.id = val;
            } else if ("when".equals(key) || "if".equals(key)) {
                cur.when = val;
            } else if ("to".equals(key) || "who".equals(key)) {
                cur.to = val;
            } else if ("keyword".equals(key) || "keywords".equals(key) || "关键词".equals(key)) {
                cur.keyword = val;
            } else if ("not_keyword".equals(key) || "exclude".equals(key) || "排除".equals(key)) {
                cur.notKeyword = val;
            } else if ("level".equals(key) || "级别".equals(key)) {
                cur.level = val.toLowerCase(Locale.ROOT);
            } else if ("once".equals(key) || "频率".equals(key)) {
                cur.once = val.toLowerCase(Locale.ROOT);
            } else if ("title".equals(key) || "标题".equals(key)) {
                cur.title = val;
            } else if ("body".equals(key) || "正文".equals(key) || "content".equals(key)) {
                appendBody(cur, val);
            } else if ("url".equals(key) || "link".equals(key)) {
                cur.url = val;
            } else if ("copy".equals(key) || "复制".equals(key)) {
                cur.copy = val;
            } else if ("percent".equals(key) || "roll".equals(key) || "灰度".equals(key)) {
                cur.percent = safeNum(val, -1);
            } else if ("from".equals(key) || "start".equals(key)) {
                cur.from = Expr.parseTime(val);
            } else if ("until".equals(key) || "end".equals(key) || "expire".equals(key)) {
                cur.until = Expr.parseTime(val);
            } else if ("min_version".equals(key)) {
                cur.minVersion = (int) safeNum(val, -1);
            } else if ("max_version".equals(key)) {
                cur.maxVersion = (int) safeNum(val, -1);
            } else if ("min_android".equals(key)) {
                cur.minAndroid = (int) safeNum(val, -1);
            } else if ("button".equals(key) || "按钮".equals(key)) {
                cur.buttons.add(splitButton(val));
            }
        }
        flush(out, cur, hasDirective);
        if (!sawAny && out.isEmpty()) {         // 兼容「随手写几句话」的纯文本
            Notice plain = new Notice();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("#") || t.startsWith("//")) {
                    continue;
                }
                appendBody(plain, l);
            }
            if (plain.body.toString().trim().length() > 0) {
                out.add(plain);
            }
        }
        return out;
    }

    private static boolean isDirective(String key) {
        return "id".equals(key) || "when".equals(key) || "if".equals(key) || "to".equals(key) || "who".equals(key)
                || "keyword".equals(key) || "keywords".equals(key) || "关键词".equals(key)
                || "not_keyword".equals(key) || "exclude".equals(key) || "排除".equals(key)
                || "level".equals(key) || "级别".equals(key) || "once".equals(key) || "频率".equals(key)
                || "title".equals(key) || "标题".equals(key) || "body".equals(key) || "正文".equals(key)
                || "content".equals(key) || "url".equals(key) || "link".equals(key)
                || "copy".equals(key) || "复制".equals(key) || "percent".equals(key) || "roll".equals(key)
                || "灰度".equals(key) || "from".equals(key) || "start".equals(key) || "until".equals(key)
                || "end".equals(key) || "expire".equals(key) || "min_version".equals(key)
                || "max_version".equals(key) || "min_android".equals(key) || "button".equals(key)
                || "按钮".equals(key);
    }

    private static double safeNum(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void appendBody(Notice n, String line) {
        if (n.body.length() == 0 && line.trim().length() == 0) {
            return;                      // 正文开头的空行不要（块之间的空行常被带进来）
        }
        if (n.body.length() > 0) {
            n.body.append('\n');
        }
        n.body.append(line);
    }

    /** 去掉正文末尾的空行（一条公告和 --- 之间常常留空行）。 */
    private static void trimBody(Notice n) {
        while (n.body.length() > 0) {
            int lastNl = n.body.lastIndexOf("\n");
            if (lastNl < 0) {
                break;
            }
            if (n.body.substring(lastNl + 1).trim().length() > 0) {
                break;
            }
            n.body.setLength(lastNl);
        }
    }

    /** 按钮写法：{@code 文案|动作|参数}，只写文案就默认「关掉」。 */
    private static String[] splitButton(String val) {
        String[] p = val.split("\\|");
        String label = p.length > 0 ? p[0].trim() : "";
        String action = p.length > 1 ? p[1].trim().toLowerCase(Locale.ROOT) : "close";
        String arg = p.length > 2 ? p[2].trim() : "";
        if (label.length() == 0) {
            label = "知道了";
        }
        return new String[]{label, action, arg};
    }

    private static void flush(List<Notice> out, Notice cur, boolean hasDirective) {
        trimBody(cur);
        boolean empty = cur.title.length() == 0 && cur.body.toString().trim().length() == 0
                && cur.buttons.isEmpty() && !hasDirective;
        if (!empty) {
            out.add(cur);
        }
    }

    // ---------------------------------------------------------------- 命中判定

    /** {@code to:} 人群简写。 */
    static boolean toHit(String to, Map<String, String> v) {
        if (to == null || to.trim().length() == 0) {
            return true;
        }
        boolean logged = truthy(val(v, "logged_in"));
        boolean code = truthy(val(v, "code_unused"));
        int launches = (int) numOf(val(v, "launches"), 0);
        for (String raw : to.split("[,，、;；|\\s]+")) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.length() == 0 || "all".equals(t) || "所有人".equals(t) || "everyone".equals(t)) {
                if (t.length() > 0) {
                    return true;
                }
                continue;
            }
            if ("login".equals(t) || "logged_in".equals(t) || "已登录".equals(t) || "登录用户".equals(t)) {
                if (logged) {
                    return true;
                }
            } else if ("guest".equals(t) || "not_logged_in".equals(t) || "未登录".equals(t) || "游客".equals(t)) {
                if (!logged) {
                    return true;
                }
            } else if ("whitelist".equals(t) || "authorized".equals(t) || "白名单".equals(t)) {
                if (truthy(val(v, "authorized"))) {
                    return true;
                }
            } else if ("pro".equals(t)) {
                if (truthy(val(v, "pro_logged_in"))) {
                    return true;
                }
            } else if ("code".equals(t) || "code_unused".equals(t) || "有码".equals(t) || "未兑换".equals(t)) {
                if (code) {
                    return true;
                }
            } else if ("no_code".equals(t) || "没码".equals(t) || "无码".equals(t)) {
                if (!code) {
                    return true;
                }
            } else if ("first_run".equals(t) || "新用户".equals(t) || "first".equals(t) || "newbie".equals(t)) {
                if (launches <= 1) {
                    return true;
                }
            } else if ("old".equals(t) || "老用户".equals(t) || "veteran".equals(t)) {
                if (launches > 5) {
                    return true;
                }
            } else if (t.startsWith("android")) {           // android>=12 / android12
                int need = (int) numOf(t.replaceAll("[^0-9]", ""), -1);
                if (need > 0 && numOf(val(v, "android"), 0) >= need) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 关键词命中：用户名 / 昵称 / 邮箱 / uid / 品牌 / 机型 / 系统语言 组成的关键词串。
     * 逗号分隔多个关键词；{@code *} 通配；{@code /正则/}；前缀 {@code all:} 表示要全部命中。
     */
    static boolean keywordHit(Notice n, Map<String, String> v) {
        String hay = (val(v, "username") + "\n" + val(v, "nickname") + "\n" + val(v, "email") + "\n"
                + val(v, "uid") + "\n" + val(v, "brand") + "\n" + val(v, "model") + "\n"
                + val(v, "device") + "\n" + val(v, "locale")).toLowerCase(Locale.ROOT);
        if (n.notKeyword != null && n.notKeyword.trim().length() > 0) {
            if (anyKeyword(n.notKeyword, hay)) {
                return false;
            }
        }
        if (n.keyword == null || n.keyword.trim().length() == 0) {
            return true;
        }
        String spec = n.keyword.trim();
        boolean needAll = false;
        if (spec.toLowerCase(Locale.ROOT).startsWith("all:")) {
            needAll = true;
            spec = spec.substring(4);
        }
        String[] parts = spec.split("[,，、;；|\\s]+");
        int total = 0;
        int hits = 0;
        for (String p : parts) {
            String kw = p.trim();
            if (kw.length() == 0) {
                continue;
            }
            total++;
            if (kwMatches(kw, hay)) {
                hits++;
            }
        }
        if (total == 0) {
            return true;
        }
        return needAll ? hits == total : hits > 0;
    }

    private static boolean anyKeyword(String spec, String hay) {
        for (String p : spec.split("[,，、;；|\\s]+")) {
            String kw = p.trim();
            if (kw.length() > 0 && kwMatches(kw, hay)) {
                return true;
            }
        }
        return false;
    }

    private static boolean kwMatches(String kw, String hay) {
        try {
            if (kw.length() > 2 && kw.startsWith("/") && kw.endsWith("/")) {      // /正则/
                return Pattern.compile(kw.substring(1, kw.length() - 1),
                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(hay).find();
            }
            if (kw.indexOf('*') >= 0 || kw.indexOf('?') >= 0) {                  // 通配符
                String re = Pattern.quote(kw).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
                return Pattern.compile(re, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(hay).find();
            }
            return hay.contains(kw.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code from:} / {@code until:} 生效时间窗口。 */
    static boolean timeHit(Notice n) {
        long now = System.currentTimeMillis();
        if (n.from > 0 && now < n.from) {
            return false;
        }
        return n.until <= 0 || now <= n.until;
    }

    /** 版本 / 系统版本门槛。 */
    static boolean rangeHit(Notice n, Map<String, String> v) {
        int code = (int) numOf(val(v, "version_code"), 0);
        if (n.minVersion > 0 && code < n.minVersion) {
            return false;
        }
        if (n.maxVersion > 0 && code > n.maxVersion) {
            return false;
        }
        int android = (int) numOf(val(v, "android"), 0);
        return n.minAndroid <= 0 || android >= n.minAndroid;
    }

    /** 稳定灰度：同一设备/账号结果固定，不会刷一次变一次。 */
    static boolean percentHit(Notice n, Map<String, String> v) {
        if (n.percent < 0) {
            return true;
        }
        if (n.percent <= 0) {
            return false;
        }
        if (n.percent >= 100) {
            return true;
        }
        String seed = val(v, "device_hash") + "#" + val(v, "uid") + "#" + n.key();
        return Expr.stableHash(seed) % 100 < n.percent;
    }

    private static boolean seen(Context c, Notice n) {
        String period = periodOf(n.once);
        if (period == null) {
            return false;                     // always
        }
        long at = prefs(c).getLong(K_SEEN + n.key(), 0L);
        if (at <= 0) {
            return false;
        }
        if (period.length() == 0) {
            return true;                      // once：永久一次
        }
        return stamp(period, at).equals(stamp(period, System.currentTimeMillis()));
    }

    /** 按周期把时间戳压成可比较的键（天/周/月）。 */
    private static String stamp(String period, long ms) {
        if ("yyyy-ww".equals(period)) {
            return weekKey(ms);
        }
        if ("yyyy-MM".equals(period)) {
            return monthKey(ms);
        }
        return periodKey(ms);
    }

    private static void markSeen(Context c, Notice n) {
        if (periodOf(n.once) == null) {
            return;
        }
        prefs(c).edit().putLong(K_SEEN + n.key(), System.currentTimeMillis()).apply();
    }

    /** @return null=每次都弹 ""=永久一次 其它=周期键 */
    private static String periodOf(String once) {
        if (once == null) {
            return null;
        }
        String o = once.trim().toLowerCase(Locale.ROOT);
        if ("day".equals(o) || "daily".equals(o) || "每天".equals(o) || "当天".equals(o)) {
            return "yyyy-MM-dd";
        }
        if ("week".equals(o) || "每周".equals(o)) {
            return "yyyy-ww";
        }
        if ("month".equals(o) || "每月".equals(o)) {
            return "yyyy-MM";
        }
        if ("once".equals(o) || "永久".equals(o) || "只一次".equals(o)) {
            return "";
        }
        return null;
    }

    private static String periodKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    private static String weekKey(long ms) {
        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.WEEK_OF_YEAR));
    }

    private static String monthKey(long ms) {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(ms));
    }

    // ---------------------------------------------------------------- 变量

    /** 组装表达式与占位符能用的变量表。 */
    static Map<String, String> vars(Context c) {
        Map<String, String> v = new HashMap<>();
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();

        // ---- 登录与身份
        OAuthStore.Account acc = OAuthStore.load(c);
        ProAuth.Record pro = ProAuth.load(c);
        boolean oauth = acc.loggedIn();
        boolean proIn = pro.hasToken();
        boolean logged = oauth || proIn;
        String user = oauth && acc.username != null && acc.username.length() > 0 ? acc.username : pro.username;
        String email = pro.email == null ? "" : pro.email;
        long uid = acc.id > 0 ? acc.id : pro.id;

        // ---- 激活码
        ActivationStore.Record code = ActivationStore.load(c, "");
        boolean unused = code.usable();
        boolean everHad = code.code != null && code.code.length() > 0;

        // ---- 设备
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        Locale loc = Locale.getDefault();
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        boolean su = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists()
                || new File("/sbin/su").exists()
                || (Build.TAGS != null && Build.TAGS.contains("test-keys"));

        // ---- 时间
        Calendar cal = new GregorianCalendar();
        cal.setTimeInMillis(now);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;

        // ---- 使用统计
        long first = p.getLong(K_FIRST, 0L);
        long last = p.getLong(K_LAST, 0L);
        long days = first > 0 ? Math.max(0, (now - first) / 86400000L) : 0;
        int launches = p.getInt(K_LAUNCH, 0);
        long lastNotice = p.getLong("last_notice_at", 0L);

        v.put("logged_in", logged ? "true" : "false");
        v.put("oauth_logged_in", oauth ? "true" : "false");
        v.put("pro_logged_in", proIn ? "true" : "false");
        v.put("authorized", ProAuth.isAuthorized(c) ? "true" : "false");
        v.put("username", user == null ? "" : user);
        v.put("nickname", user == null ? "" : user);
        v.put("email", email);
        v.put("uid", uid > 0 ? String.valueOf(uid) : "");
        v.put("token_state", !logged ? "无" : (pro.expired() && !oauth ? "过期" : "有效"));

        v.put("code", unused ? code.code : "");
        v.put("code_unused", unused ? "true" : "false");
        v.put("code_state", unused ? "未兑换" : (everHad ? "已用完" : "没有"));
        v.put("code_feature", code.feature == null ? "" : code.feature);

        v.put("version", BuildConfig.VERSION_NAME);
        v.put("version_name", BuildConfig.VERSION_NAME);
        v.put("version_code", String.valueOf(BuildConfig.VERSION_CODE));
        v.put("app_id", BuildConfig.AFF_APP_ID);
        v.put("app_name", "AshenFlame Foundry");

        v.put("android", String.valueOf(Build.VERSION.SDK_INT));
        v.put("sdk_int", String.valueOf(Build.VERSION.SDK_INT));
        v.put("android_name", Build.VERSION.RELEASE);
        v.put("brand", Build.BRAND == null ? "" : Build.BRAND);
        v.put("model", Build.MODEL == null ? "" : Build.MODEL);
        v.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        v.put("device", Build.DEVICE == null ? "" : Build.DEVICE);
        v.put("hardware", Build.HARDWARE == null ? "" : Build.HARDWARE);
        v.put("abi", abi);
        v.put("locale", loc.toString());
        v.put("lang", loc.getLanguage());
        v.put("region", loc.getCountry());
        v.put("timezone", TimeZone.getDefault().getID());
        v.put("screen_w", String.valueOf(Math.min(dm.widthPixels, dm.heightPixels)));
        v.put("screen_h", String.valueOf(Math.max(dm.widthPixels, dm.heightPixels)));
        v.put("density", String.valueOf(dm.densityDpi));
        v.put("su", su ? "true" : "false");

        v.put("ts", String.valueOf(now));
        v.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)));
        v.put("time", new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
        v.put("hour", String.valueOf(cal.get(Calendar.HOUR_OF_DAY)));
        v.put("minute", String.valueOf(cal.get(Calendar.MINUTE)));
        v.put("weekday", String.valueOf(dow));
        v.put("weekday_name", cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.CHINA));
        v.put("year", String.valueOf(cal.get(Calendar.YEAR)));
        v.put("month", String.valueOf(cal.get(Calendar.MONTH) + 1));
        v.put("day", String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
        v.put("is_weekend", weekend ? "true" : "false");

        v.put("install_days", String.valueOf(days));
        v.put("launches", String.valueOf(launches));
        v.put("first_at", first > 0 ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(first)) : "");
        v.put("last_at", last > 0 ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(last)) : "");
        v.put("days_since_notice", lastNotice > 0 ? String.valueOf((now - lastNotice) / 86400000L) : "-1");
        v.put("is_first_run", launches <= 1 ? "true" : "false");

        String deviceHash = deviceHash(c);
        v.put("device_hash", deviceHash);
        v.put("roll", String.valueOf(Expr.stableHash(deviceHash + "#" + uid) % 100));

        v.put("repo_url", "https://github.com/" + REPO);
        v.put("checkin_url", ActivationConfig.CHECKIN_PAGE);
        v.put("qq_group", "https://qm.qq.com/q/6mxOaslq7e");
        return v;
    }

    private static String deviceHash(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                return id;
            }
        } catch (Throwable ignored) {
            // 拿不到就用机型兜底
        }
        return (Build.MANUFACTURER + "-" + Build.MODEL + "-" + Build.DEVICE).replace(' ', '_');
    }

    private static String val(Map<String, String> v, String k) {
        String s = v.get(k);
        return s == null ? "" : s;
    }

    private static boolean truthy(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static double numOf(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 把 {{xxx}} 换成实际值；认不出的原样留着（方便发现写错了）。 */
    static String expand(String text, Map<String, String> vars) {
        if (text == null) {
            return "";
        }
        if (text.indexOf("{{") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int a = text.indexOf("{{", i);
            if (a < 0) {
                out.append(text.substring(i));
                break;
            }
            int b = text.indexOf("}}", a + 2);
            if (b < 0) {
                out.append(text.substring(i));
                break;
            }
            out.append(text, i, a);
            String name = text.substring(a + 2, b).trim();
            String v = vars.get(name);
            if (v == null) {
                v = vars.get(name.toLowerCase(Locale.ROOT));
            }
            out.append(v != null ? v : "{{" + name + "}}");
            i = b + 2;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 弹窗

    private static void show(final Activity act, final Notice n, final Map<String, String> vars,
                             final Runnable after) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);

        final int BG = 0xFF15171A, LINE = 0x1FFFFFFF, TEXT = 0xFFEDEDED, DIM = 0xFFC7D2DE, ACCENT = 0xFFD9603A;
        int pad = dp(act, 20);
        int levelColor = levelColor(n.level, TEXT, ACCENT);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(act, 16));
        bg.setStroke(dp(act, 1), LINE);
        root.setBackground(bg);

        String title = expand(n.title, vars).trim();
        if (title.length() == 0) {
            title = "公告";
        }
        String badge = levelBadge(n.level);
        TextView head = new TextView(act);
        head.setText(badge.length() > 0 ? badge + " " + title : title);
        head.setTextSize(17);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(levelColor);
        if (n.url != null && n.url.length() > 0) {
            head.setPaintFlags(head.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            head.setClickable(true);
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(act, n.url);
                }
            });
        }
        root.addView(head);

        String bodyText = expand(n.body.toString().trim(), vars);
        if (bodyText.length() == 0) {
            bodyText = "（这条公告没有正文）";
        }
        TextView body = new TextView(act);
        body.setText(bodyText);
        body.setTextSize(14);
        body.setTextColor(DIM);
        body.setLineSpacing(dp(act, 5), 1f);
        body.setPadding(0, dp(act, 12), 0, 0);
        body.setTextIsSelectable(true);          // 福利码 / 激活码可以长按复制
        Linkify.addLinks(body, Linkify.WEB_URLS);   // 正文里的网址自动可点

        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<String[]> btns = new ArrayList<>(n.buttons);
        if (n.copy != null && n.copy.trim().length() > 0) {           // copy: 文本  → 自动加复制按钮
            String[] cp = n.copy.split("\\|");
            String label = cp.length > 1 ? cp[0].trim() : "复制";
            String arg = cp.length > 1 ? cp[1].trim() : n.copy.trim();
            btns.add(new String[]{label.length() > 0 ? label : "复制", "copy", arg});
        }
        if (n.url != null && n.url.length() > 0) {
            btns.add(new String[]{"打开链接", "url", n.url});
        }
        if (btns.isEmpty()) {
            btns.add(new String[]{"知道了", "close", ""});
        }
        for (final String[] b : btns) {
            TextView btn = new TextView(act);
            btn.setText(expand(b[0], vars));
            btn.setTextSize(15);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(ACCENT);
            btn.setPadding(0, dp(act, 12), 0, dp(act, 4));
            btn.setClickable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    runAction(b, act);
                }
            });
            root.addView(btn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                Context app = act.getApplicationContext();
                markSeen(app, n);
                prefs(app).edit().putLong("last_notice_at", System.currentTimeMillis()).apply();
                if (after != null) {        // 稍等一下再弹下一条，避免连点感
                    try {
                        act.getWindow().getDecorView().postDelayed(after, 320);
                    } catch (Throwable ignored) {
                        // 窗口没了就算了，剩下的下次进来再弹
                    }
                }
            }
        });

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * 0.9f), (int) (dm.heightPixels * 0.72f));
        }
        try {
            d.show();
        } catch (Throwable ignored) {
            // Activity 已经不在前台，弹不出来就算了
        }
    }

    private static int levelColor(String level, int normal, int accent) {
        String l = level == null ? "" : level.toLowerCase(Locale.ROOT);
        if ("alert".equals(l) || "error".equals(l) || "紧急".equals(l) || "重要".equals(l)) {
            return 0xFFFF6B6B;
        }
        if ("warn".equals(l) || "warning".equals(l) || "警告".equals(l)) {
            return 0xFFFFB454;
        }
        if ("success".equals(l) || "福利".equals(l) || "good".equals(l)) {
            return 0xFF7DE08A;
        }
        return normal != 0 ? normal : accent;
    }

    private static String levelBadge(String level) {
        String l = level == null ? "" : level.toLowerCase(Locale.ROOT);
        if ("alert".equals(l) || "error".equals(l) || "紧急".equals(l) || "重要".equals(l)) {
            return "【重要】";
        }
        if ("warn".equals(l) || "warning".equals(l) || "警告".equals(l)) {
            return "【注意】";
        }
        if ("success".equals(l) || "福利".equals(l) || "good".equals(l)) {
            return "【福利】";
        }
        return "";
    }

    private static void open(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            // 没浏览器就算了
        }
    }

    /** 执行按钮动作。 */
    private static void runAction(String[] b, Activity activity) {
        String action = b[1];
        String arg = expand(b[2], vars(activity));
        try {
            if ("url".equals(action) || "link".equals(action)) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(arg)));
            } else if ("copy".equals(action)) {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("notice", arg));
                    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
                }
            } else if ("checkin".equals(action)) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ActivationConfig.CHECKIN_PAGE)));
            }
        } catch (Throwable ignored) {
            // 打不开就算了（没浏览器 / 没剪贴板都不该崩）
        }
    }

    // ---------------------------------------------------------------- 杂项

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记录首次 / 最近进入时间与启动次数（install_days / launches / is_first_run 用）。 */
    private static void bumpLaunch(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        if (p.getLong(K_FIRST, 0L) <= 0) {
            e.putLong(K_FIRST, System.currentTimeMillis());
        }
        e.putLong(K_LAST, System.currentTimeMillis());
        e.putInt(K_LAUNCH, p.getInt(K_LAUNCH, 0) + 1);
        e.apply();
    }

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 调试用：清掉缓存，让下次进来重新拉一遍。 */
    static void resetCache(Context c) {
        checkedThisProcess = false;
        prefs(c).edit().remove(K_AT).remove(K_TEXT).apply();
    }
}
