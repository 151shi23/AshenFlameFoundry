package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 故事墙的内容源：仓库根目录的 <code>story.txt</code>。
 *
 * <p>和公告（{@code ovo.txt}）是同一套思路：只吃数据、不下发代码；改内容不用发版。
 * 区别是这里<b>不弹窗</b>，只把内容交给 {@link StoryWallActivity} 渲染成一页。
 *
 * <h3>文件格式</h3>
 * <pre>
 * id: s-001                     # 可选，不写按顺序编号
 * title: 那个夏天                # 必填
 * by: 玩家A                      # 可选
 * date: 2026-10-01              # 可选，纯展示，不做排序
 * tag: 回忆                      # 可选
 * body: 第一段                   # 可重复，一行一段
 * body:
 * body: 第二段
 *
 * ---                           # 三个短横线分块（*** 与 === 也认）
 * </pre>
 *
 * <p>没写 {@code body:} 前缀的裸行会被当成正文接上去，所以直接贴一整段也可以。
 * 一行里 {@code #} 开头或 {@code //} 开头是注释。
 *
 * <h3>缓存</h3>
 * 10 分钟内不联网；断网时用 24 小时内的旧内容；全都没有就回空表（页面显示"暂时没有内容"）。
 */
public final class StoryStore {

    /** 与配置/公告同一套镜像顺序：raw → ghproxy → gitmirror → jsDelivr。 */
    private static final String[] SOURCES = new String[]{
            "https://raw.githubusercontent.com/151shi23/AshenFlameFoundry/main/story.txt",
            "https://ghproxy.net/https://raw.githubusercontent.com/151shi23/AshenFlameFoundry/main/story.txt",
            "https://raw.gitmirror.com/151shi23/AshenFlameFoundry/main/story.txt",
            "https://cdn.jsdelivr.net/gh/151shi23/AshenFlameFoundry@main/story.txt",
    };

    private static final String PREFS = "aff_story";
    private static final String K_TEXT = "text";
    private static final String K_AT = "at";

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long STALE_OK_MS = 24 * 3600 * 1000L;
    private static final int CONNECT_TIMEOUT = 6000;
    private static final int READ_TIMEOUT = 12000;

    /**
     * 主线程 Handler，延迟创建。
     *
     * <p>不写成 {@code static final}：那样类一加载就去碰 {@code Looper}，
     * 纯 JVM 下（跑解析测试）会直接抛 {@code RuntimeException: Stub!}。
     * 延迟创建同时也避开了在无 Looper 线程里初始化的问题。
     */
    private static volatile Handler sMain;

    private static Handler main() {
        if (sMain == null) {
            synchronized (StoryStore.class) {
                if (sMain == null) {
                    sMain = new Handler(Looper.getMainLooper());
                }
            }
        }
        return sMain;
    }

    private StoryStore() {
    }

    /** 一篇故事。 */
    public static final class Story {
        public String id = "";
        public String title = "";
        public String by = "";
        public String date = "";
        public String tag = "";
        public final StringBuilder body = new StringBuilder();

        public String bodyText() {
            return body.toString().trim();
        }
    }

    public interface Callback {
        /**
         * @param list      解析出来的故事（可能为空）
         * @param fromCache 是否来自本地缓存（没走网络）
         * @param error     网络/解析失败的说明；成功为 null
         */
        void onStories(List<Story> list, boolean fromCache, String error);
    }

    // ---------------------------------------------------------------- 对外

    /** 有 24 小时内的缓存就立刻给；没有就返回空表（不联网，给"先画一屏"用）。 */
    public static List<Story> cached(Context c) {
        try {
            long at = prefs(c).getLong(K_AT, 0L);
            if (at <= 0 || System.currentTimeMillis() - at > STALE_OK_MS) {
                return new ArrayList<>();
            }
            return parse(prefs(c).getString(K_TEXT, ""));
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * 拉一次内容。10 分钟内的缓存直接用；否则先网络，失败退回缓存。
     * 回调在主线程。
     */
    public static void load(final Context context, final Callback cb) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                long at = prefs(app).getLong(K_AT, 0L);
                boolean fresh = at > 0 && System.currentTimeMillis() - at < CACHE_TTL_MS;
                if (fresh) {
                    final List<Story> list = parse(prefs(app).getString(K_TEXT, ""));
                    post(cb, list, true, null);
                    return;
                }

                String text = null;
                String err = null;
                for (String url : SOURCES) {
                    try {
                        text = get(url);
                        if (text != null && text.trim().length() > 0) {
                            break;
                        }
                        text = null;
                    } catch (Throwable t) {
                        err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }

                if (text == null) {
                    // 网络全挂：用 24 小时内的旧内容，别让页面空着
                    long old = prefs(app).getLong(K_AT, 0L);
                    boolean usable = old > 0 && System.currentTimeMillis() - old < STALE_OK_MS;
                    final List<Story> list = usable
                            ? parse(prefs(app).getString(K_TEXT, ""))
                            : new ArrayList<Story>();
                    post(cb, list, usable, err == null ? "没有取到内容" : err);
                    return;
                }

                prefs(app).edit()
                        .putString(K_TEXT, text)
                        .putLong(K_AT, System.currentTimeMillis())
                        .apply();
                post(cb, parse(text), false, null);
            }
        }, "story-load").start();
    }

    /**
     * 云控 {@code story_hide} 名单命中判断：逗号/空格分隔的 uid 列表。
     * 拿不到 uid 时一律不隐藏（fail-open，宁可多给看，也不误伤）。
     */
    public static boolean hiddenFor(Context c, String list) {
        if (list == null || list.trim().length() == 0 || c == null) {
            return false;
        }
        String uid = currentUid(c);
        if (uid.length() == 0) {
            return false;
        }
        for (String s : list.split("[,，\\s]+")) {
            if (s != null && s.trim().equals(uid)) {
                return true;
            }
        }
        return false;
    }

    private static String currentUid(Context c) {
        try {
            OAuthStore.Account acc = OAuthStore.load(c);
            if (acc != null && acc.id > 0) {
                return String.valueOf(acc.id);
            }
            ProAuth.Record pro = ProAuth.load(c);
            if (pro != null && pro.id > 0) {
                return String.valueOf(pro.id);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ---------------------------------------------------------------- 解析

    /** 把 story.txt 解析成故事列表。空文本 → 空表。 */
    static List<Story> parse(String text) {
        List<Story> out = new ArrayList<>();
        if (text == null || text.trim().length() == 0) {
            return out;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Story cur = new Story();
        for (String line : lines) {
            String t = line.trim();
            if ("---".equals(t) || "***".equals(t) || "===".equals(t)) {
                flush(out, cur);
                cur = new Story();
                continue;
            }
            if (t.startsWith("#") || t.startsWith("//")) {
                continue;
            }
            int colon = t.indexOf(':');
            String key = colon > 0 ? t.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            String val = colon > 0 ? t.substring(colon + 1).trim() : "";
            if ("id".equals(key)) {
                cur.id = val;
            } else if ("title".equals(key) || "标题".equals(key)) {
                cur.title = val;
            } else if ("by".equals(key) || "author".equals(key) || "作者".equals(key)) {
                cur.by = val;
            } else if ("date".equals(key) || "日期".equals(key) || "时间".equals(key)) {
                cur.date = val;
            } else if ("tag".equals(key) || "标签".equals(key)) {
                cur.tag = val;
            } else if ("body".equals(key) || "正文".equals(key) || "content".equals(key)) {
                appendBody(cur, val);
            } else {
                appendBody(cur, line);      // 不是指令 → 当正文
            }
        }
        flush(out, cur);

        // 没写 id 的按顺序补一个，方便页面上做 key
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id.length() == 0) {
                out.get(i).id = "s-" + (i + 1);
            }
        }
        return out;
    }

    private static void appendBody(Story s, String line) {
        String v = line == null ? "" : line.trim();
        if (s.body.length() == 0 && v.length() == 0) {
            return;                         // 开头空行不要
        }
        if (s.body.length() > 0) {
            s.body.append('\n');
        }
        s.body.append(v);
    }

    private static void flush(List<Story> out, Story s) {
        if (s == null) {
            return;
        }
        while (s.body.length() > 0 && s.body.charAt(s.body.length() - 1) == '\n') {
            s.body.setLength(s.body.length() - 1);
        }
        if (s.title.trim().length() == 0 && s.body.length() == 0) {
            return;                         // 空块不要
        }
        out.add(s);
    }

    // ---------------------------------------------------------------- 网络

    private static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "MinewaysMobile/story");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) {
                    break;                  // 2MB 上限，防呆
                }
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void post(final Callback cb, final List<Story> list,
                             final boolean fromCache, final String error) {
        if (cb == null) {
            return;
        }
        main().post(new Runnable() {
            @Override
            public void run() {
                cb.onStories(list, fromCache, error);
            }
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
