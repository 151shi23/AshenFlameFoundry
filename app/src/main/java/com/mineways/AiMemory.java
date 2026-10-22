package com.mineways;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 的<b>本机短记忆</b>：最近产物路径、用户点过名的功能、常用参数。
 *
 * <p>为什么要有：用户说完「把 D:/包/a 转了」，下一句是「刚才那个转得怎么样」——
 * 没有这条记忆，AI 只能让用户再报一遍路径。这不是模型能力问题，是 App 没告诉它。
 *
 * <p>刻意<b>不是</b>数据库、也不上传：就一个 {@code filesDir/ai/mem.json}，
 * 只留最近 12 条、每条最多 200 字，读不到就当没有。聊天记录里已经有了的东西不重复存。
 *
 * <p>它进两条路：① 拼进系统提示词的「本机记忆」段（{@link #forPrompt()}）；
 * ② {@code app_info} 里回一段，用户能看见 AI 到底记住了什么 —— 记了什么必须可见，
 * 不能让它成为一个看不见的旁路。
 */
public final class AiMemory {

    /** 最多记几条、每条多长（超限从头截，新的永远留下）。 */
    private static final int KEEP = 12;
    private static final int MAX_ITEM = 200;

    private static volatile List<String> cache = new ArrayList<>();
    private static volatile File file;
    private static volatile boolean loaded;

    private AiMemory() {
    }

    private static synchronized File target(Context c) {
        if (file == null) {
            file = new File(c.getFilesDir(), "ai/mem.json");
        }
        return file;
    }

    /** 记一条（key 归类，值给模型看）。异步落盘，主线程不碰磁盘。 */
    public static void remember(Context c, String key, String value) {
        String v = value == null ? "" : value.trim();
        if (c == null || v.length() == 0) {
            return;
        }
        if (v.length() > MAX_ITEM) {
            v = v.substring(0, MAX_ITEM) + "…";
        }
        String line = key + "：" + v;
        List<String> next = new ArrayList<>(cache);
        next.remove(line);
        next.add(line);
        while (next.size() > KEEP) {
            next.remove(0);
        }
        cache = next;
        load(c);                       // 顺带把 loaded 标记立起来，避免首次覆盖丢档
        final JSONArray arr = new JSONArray();
        for (String s : next) {
            try {
                arr.put(s);
            } catch (Throwable ignored) {
            }
        }
        final File f = target(c);
        Thread t = new Thread(() -> {
            try {
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
                try (java.io.OutputStream os = new java.io.FileOutputStream(f, false)) {
                    os.write(arr.toString().getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
                // 记不住就算了，绝不能因此把对话弄崩
            }
        }, "mw-ai-mem");
        t.setDaemon(true);
        t.start();
    }

    /** 读档（一次；之后走内存）。 */
    public static void load(Context c) {
        if (loaded || c == null) {
            return;
        }
        loaded = true;
        try {
            File f = target(c);
            if (!f.isFile()) {
                return;
            }
            byte[] raw = new byte[(int) Math.min(f.length(), 64 * 1024)];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(raw);
                if (n < raw.length && n > 0) {
                    byte[] cut = new byte[n];
                    System.arraycopy(raw, 0, cut, 0, n);
                    raw = cut;
                }
            }
            JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s.length() > 0) {
                    out.add(s);
                }
            }
            cache = out;
        } catch (Throwable ignored) {
            cache = new ArrayList<>();
        }
    }

    /** 提示词里那一段；没记东西就回空串（不占字数）。 */
    public static String forPrompt() {
        List<String> l = cache;
        if (l.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n本机记忆（最近 " + l.size() + " 条，"
                + "都是用户自己这台机器上的事，别说出去当公开信息）：");
        for (String s : l) {
            sb.append("\n- ").append(s);
        }
        return sb.toString();
    }

    /** 全部条目（界面/AI 执行层要用，例如「用户刚选的那个文件」）。 */
    public static List<String> items(Context c) {
        load(c);
        return cache;
    }

    /** 给界面 / app_info 看的整段。 */
    public static String dump() {
        List<String> l = cache;
        if (l.isEmpty()) {
            return "还没记住什么";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            sb.append(s).append("；");
        }
        return sb.toString();
    }

    /** 用户清对话时一起清掉，否则他会觉得「我明明说了重开，它还记着」。 */
    public static synchronized void clear(Context c) {
        cache = new ArrayList<>();
        loaded = true;
        try {
            if (c != null) {
                target(c).delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 从一堆条目里挑出不重复的功能名（ask_home / describe 用得上）。 */
    public static Set<String> distinct(List<String> items) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : items) {
            if (s != null && s.trim().length() > 0) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
