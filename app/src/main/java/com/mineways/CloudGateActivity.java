package com.mineways;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 启动墙：**风控 + 云控**（专业版唯一入口，取代原来的登录墙与更新闸门）。
 *
 * <h3>云控规则</h3>
 * <p>看云控仓库 <code>151shi23/Mineways-Gate</code> <b>根目录的文件数量</b>：</p>
 * <ul>
 *   <li><b>1 个文件</b>（README.md）→ 放行，直接进主界面；</li>
 *   <li><b>2 个及以上</b> → 拦截：<b>读取第二个文件</b>，如果是 <code>.txt</code>
 *       就把里面的内容整段显示出来当公告；不是 txt 就显示默认停服文案 + 文件名。</li>
 * </ul>
 *
 * <h3>风控（拿不到结论时怎么办）</h3>
 * <ol>
 *   <li>先走 GitHub Contents API 数文件（原始规则）；</li>
 *   <li>API 被限流 / 直连不通 → 逐个镜像探测「第二个文件」：拿到 .txt 就拦截并显示，
 *       明确 404 就放行；</li>
 *   <li>全都不通 → 用本地缓存的上次结论（TTL {@link #CACHE_TTL_MS}）；
 *       从没成功过则放行，断网不会把人锁死；</li>
 *   <li>拦截页没有「继续使用」，返回键无效 —— 只有你在仓库里删掉那个文件才能恢复。</li>
 * </ol>
 */
public class CloudGateActivity extends Activity {

    private static final String REPO = "151shi23/Mineways-Gate";
    private static final String BRANCH = "main";
    private static final String API = "https://api.github.com/repos/" + REPO + "/contents/";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/";
    private static final String[] MIRRORS = {
            "https://ghproxy.net/" + RAW_BASE,
            "https://raw.gitmirror.com/" + REPO + "/" + BRANCH + "/",
            RAW_BASE,
    };
    private static final String REPO_URL = "https://github.com/" + REPO;
    private static final String SITE = "http://110.42.53.159:4321/";
    private static final int TIMEOUT_MS = 8000;
    /** 缓存有效期：5 分钟内不重复联网（拦截页有「重新检查」按钮可立刻刷新）。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final String PREFS = "cloud_gate";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_NOTICE = "notice";
    private static final String KEY_AT = "checked_at";

    private LinearLayout root;
    private TextView status;
    private int pad;
    private boolean blocked;
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        // 缓存还在有效期内就直接用，避免每次启动都打网络
        SharedPreferences p = prefs();
        if (p.contains(KEY_BLOCKED)
                && System.currentTimeMillis() - p.getLong(KEY_AT, 0L) < CACHE_TTL_MS) {
            apply(p.getInt(KEY_BLOCKED, 0) == 1, p.getString(KEY_NOTICE, ""));
            return;
        }
        check();
    }

    @Override
    public void onBackPressed() {
        if (blocked || checking) {
            return;
        }
        super.onBackPressed();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 云控 + 风控

    private void check() {
        checking = true;
        status.setText("正在校验服务状态…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] verdict = verdict();   // [0]=拦截?1:0  [1]=公告文本
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (verdict == null) {
                            pass();               // 三级链路都拿不到结论 → 不锁人
                        } else {
                            apply("1".equals(verdict[0]), verdict[1]);
                        }
                    }
                });
            }
        }, "cloud-gate").start();
    }

    /** @return {拦截标记, 公告文本}；拿不到任何结论时返回 null */
    private String[] verdict() {
        JSONArray listing = listRoot();
        if (listing != null) {
            int files = 0;
            String second = "";
            for (int i = 0; i < listing.length(); i++) {
                JSONObject o = listing.optJSONObject(i);
                if (o == null || "dir".equals(o.optString("type"))) {
                    continue;
                }
                files++;
                if (files == 2) {
                    second = o.optString("name", "");
                }
            }
            boolean block = files >= 2;
            String notice = block ? noticeOf(second) : "";
            remember(block, notice);
            return new String[]{block ? "1" : "0", notice};
        }
        // 降级：镜像里找「第二个文件」（优先 block.txt）
        for (String name : new String[]{"block.txt"}) {
            Boolean exists = probe(name);
            if (exists != null) {
                String notice = exists ? noticeOf(name) : "";
                remember(exists, notice);
                return new String[]{exists ? "1" : "0", notice};
            }
        }
        // 兜底：上次结论
        SharedPreferences p = prefs();
        if (p.contains(KEY_BLOCKED)) {
            return new String[]{p.getInt(KEY_BLOCKED, 0) == 1 ? "1" : "0", p.getString(KEY_NOTICE, "")};
        }
        return null;
    }

    private void remember(boolean block, String notice) {
        prefs().edit()
                .putInt(KEY_BLOCKED, block ? 1 : 0)
                .putString(KEY_NOTICE, notice == null ? "" : notice)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
    }

    /** 拦截时展示的公告：第二个文件是 .txt 就读它的内容。 */
    private String noticeOf(String name) {
        String fallback = "本项目已停止更新与分发。\n\n"
                + "· 不再提供安装包，也不再提供支持；\n"
                + "· 想要继续用，可以自行编译源码。";
        if (name == null || name.length() == 0) {
            return fallback;
        }
        if (!name.toLowerCase().endsWith(".txt")) {
            return fallback + "\n\n（云控文件：" + name + "）";
        }
        String text = fetchText(name);
        if (text == null || text.trim().length() == 0) {
            return fallback + "\n\n（云控文件：" + name + "，内容读取失败）";
        }
        return text;
    }

    private JSONArray listRoot() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "AshenFlameFoundry-Gate");
            if (c.getResponseCode() != 200) {
                return null;
            }
            return new JSONArray(readAll(c.getInputStream()));
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 镜像探测：200=存在 404=不存在 网络异常=null（换下一个镜像）。 */
    private Boolean probe(String name) {
        for (String base : MIRRORS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + name).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "AshenFlameFoundry-Gate");
                int code = c.getResponseCode();
                if (code == 200) {
                    return Boolean.TRUE;
                }
                if (code == 404) {
                    return Boolean.FALSE;
                }
            } catch (Throwable ignored) {
                // 换下一个镜像
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return null;
    }

    /** 取 .txt 全文（走镜像链）。 */
    private String fetchText(String name) {
        for (String base : MIRRORS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + name).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "AshenFlameFoundry-Gate");
                if (c.getResponseCode() != 200) {
                    continue;
                }
                return readAll(c.getInputStream());
            } catch (Throwable ignored) {
                // 换下一个镜像
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return null;
    }

    private String readAll(java.io.InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 界面

    private void pass() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void apply(boolean block, String notice) {
        if (!block) {
            pass();
            return;
        }
        blocked = true;
        status.setText("服务已停止");
        status.setTextColor(Color.parseColor("#FF6B6B"));
        status.setTextSize(20);
        status.setTypeface(Typeface.DEFAULT_BOLD);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView body = new TextView(this);
        body.setText(notice);
        body.setTextColor(Color.parseColor("#C7D2DE"));
        body.setTextSize(14);
        body.setPadding(0, pad, 0, pad);
        box.addView(body);
        sv.addView(box);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svLp);
        root.addView(sv);

        root.addView(button("重新检查", null));
        root.addView(button("打开云控仓库", REPO_URL));
        root.addView(button("访问 Sincerity 工具站", SITE));
    }

    private void buildUi() {
        pad = (int) (getResources().getDisplayMetrics().density * 22);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AshenFlame Foundry");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("正在校验服务状态…");
        status.setTextColor(Color.parseColor("#8FA3B5"));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad / 2, 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private Button button(final String text, final String url) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 3;
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (url == null) {          // 「重新检查」：清缓存立刻重判
                    prefs().edit().remove(KEY_AT).apply();
                    checking = false;
                    blocked = false;
                    root.removeAllViews();
                    buildUi();
                    check();
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable ignored) {
                }
            }
        });
        return b;
    }
}
