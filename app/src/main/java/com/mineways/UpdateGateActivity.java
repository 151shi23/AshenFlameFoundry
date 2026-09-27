package com.mineways;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动强更闸门：每次启动**必须**先联网读一次 GitHub Releases 最新版的介绍正文，
 * 按正文里的关键词决定是否强制更新。命中则**不可跳过**（只能去更新），未命中才放行到主界面。
 *
 * 关键词规则（写进 release 正文即可，支持多条、可中英混用）：
 * <pre>
 *   FORCE ALL                -> 所有版本强制更新
 *   FORCE 3.16               -> 当前版本 &lt;= 3.16 强制更新（可写多个：FORCE 3.16, 3.18）
 *   FORCE EXCEPT 3.15,3.16   -> 反选：只排除这些版本，其余版本一律强更
 *   强更 全部 / 强更 3.16 / 强更 排除 3.15      （中文同义写法）
 * </pre>
 *
 * 无网络/请求失败：自动重试 {@link #MAX_RETRY} 次，仍未成功则**卡在检查页**（不放行，符合"必须联网检查"要求）。
 */
public class UpdateGateActivity extends Activity {

    /** 最新 release 接口（仓库 151shi23/MinewaysMobile）。 */
    private static final String API = "https://api.github.com/repos/151shi23/MinewaysMobile/releases/latest";
    /** 官方 QQ 群加群链接。 */
    private static final String QQ_GROUP = "https://qm.qq.com/q/6mxOaslq7e";

    /** release 页面（"去更新"按钮用）。 */
    private static final String PAGE = "https://github.com/151shi23/MinewaysMobile/releases/latest";
    /** 每启动最多重试次数。 */
    private static final int MAX_RETRY = 3;
    /** 单次请求超时（毫秒）。 */
    private static final int TIMEOUT = 10000;

    private static final Pattern RULE = Pattern.compile(
            "(?im)^\\s*(?:FORCE|强更)\\s+(ALL|全部|EXCEPT\\s+[^\\n]+|排除\\s+[^\\n]+|[0-9][0-9.,\\s]*)");

    private TextView msg;
    private Button retryBtn;
    private Button openBtn;
    private String currentVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentVersion = versionName();
        buildUi();
        check(0);
    }

    @Override
    public void onBackPressed() {
        // 检查中/强更时禁止返回键退出绕过
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        int pad = (int) (getResources().getDisplayMetrics().density * 24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MinewaysMobile");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        msg = new TextView(this);
        msg.setText("正在检查更新…");
        msg.setTextColor(Color.parseColor("#B9C4CF"));
        msg.setTextSize(15);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, pad, 0, pad);
        root.addView(msg);

        retryBtn = new Button(this);
        retryBtn.setText("重试检查");
        retryBtn.setVisibility(View.GONE);
        retryBtn.setOnClickListener(v -> {
            retryBtn.setVisibility(View.GONE);
            openBtn.setVisibility(View.GONE);
            check(0);
        });
        root.addView(retryBtn, centerParams());

        // 官方 QQ 群入口（常驻可见，点开直达加群页）
        Button qqBtn = new Button(this);
        qqBtn.setText("点击链接加入群聊【🔥AshenFlame-No. 2【建设中】】");
        qqBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP)));
            } catch (Throwable ignored) {
            }
        });
        root.addView(qqBtn, centerParams());

        openBtn = new Button(this);
        openBtn.setText("前往 GitHub 下载更新");
        openBtn.setVisibility(View.GONE);
        openBtn.setOnClickListener(v -> {
            Object tag = v.getTag();
            String target = (tag instanceof String && !((String) tag).isEmpty()) ? (String) tag : PAGE;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            } catch (Throwable ignored) {
            }
        });
        root.addView(openBtn, centerParams());

        ScrollView holder = new ScrollView(this);
        holder.setBackgroundColor(Color.parseColor("#101418"));
        holder.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(holder);
    }

    private LinearLayout.LayoutParams centerParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 10);
        return lp;
    }

    // ---------------------------------------------------------------- 检查

    private void check(final int attempt) {
        msg.setText(attempt == 0 ? "正在检查更新…" : ("网络异常，正在重试（" + attempt + "/" + MAX_RETRY + "）…"));
        new Thread(() -> {
            String body = null;
            String err = null;
            try {
                body = fetchReleaseBody();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fBody = body;
            final String fErr = err;
            runOnUiThread(() -> onFetched(fBody, fErr, attempt));
        }, "update-gate").start();
    }

    private void onFetched(String body, String err, int attempt) {
        // 拿不到"可判定的内容"就等同于没检测到更新：重试后仍失败则**禁止进入软件**（不给绕）
        if (body == null || body.trim().isEmpty()) {
            if (err == null) {
                err = "未取到 release 内容";
            }
            if (attempt + 1 < MAX_RETRY) {
                check(attempt + 1);
            } else {
                // 强制联网检查：拿不到结果就不放行
                msg.setText("无法完成联网检查，无法进入应用。\n请检查网络后重试（错误：" + (err == null ? "无响应" : err) + "）");
                retryBtn.setVisibility(View.VISIBLE);
                openBtn.setVisibility(View.VISIBLE);
            }
            return;
        }
        String hit = forcedRule(body);
        if (hit == null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        String dl = extractDownloadLink(body);
        openBtn.setTag(dl);
        openBtn.setText(dl != null ? "网盘下载最新版" : "前往 GitHub 下载更新");
        msg.setText("当前版本 " + currentVersion + " 已停止使用，必须更新后才能继续。\n\n命中规则：" + hit
                + (dl != null ? ("\n\n下载地址（国内网盘 / 直链）：\n" + dl)
                              : "\n\n请点击下方按钮前往 GitHub 下载最新版本。"));
        retryBtn.setVisibility(View.VISIBLE);
        openBtn.setVisibility(View.VISIBLE);
    }

    private String fetchReleaseBody() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "MinewaysMobile-UpdateGate");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            conn.disconnect();
        }
        JSONObject json = new JSONObject(sb.toString());
        String body = json.optString("body", "");
        return body == null ? "" : body;
    }

    // ---------------------------------------------------------------- 下载链接

    /** 关键词 + 英文冒号 + 链接（也兼容全角冒号）：认定为"国内网盘/直链下载地址"。 */
    private static final Pattern KEYWORD_LINK = Pattern.compile(
            "(?im)^\\s*[^\\n:：]{0,24}(?:链接|下载|网盘|地址|直链|蓝奏云|百度网盘|夸克|阿里云盘|天翼云盘|迅雷|奶牛快传"
                    + "|download|link|mirror|url|apk)\\s*[:：]\\s*(https?://\\S+)");

    /** 正文里任意 http(s) 链接（兜底）。 */
    private static final Pattern ANY_LINK = Pattern.compile("(https?://[^\\s\\)\\]\"'<>]+)");

    /**
     * 从 release 正文里取下载地址：优先"关键词 + 冒号 + 链接"，其次正文里任意链接；
     * 都排除本仓库自身的 releases 页面（那个由 GitHub 按钮负责）。
     */
    static String extractDownloadLink(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher m = KEYWORD_LINK.matcher(body);
        while (m.find()) {
            String u = cleanUrl(m.group(1));
            if (!u.contains("github.com/151shi23/MinewaysMobile")) {
                return u;
            }
        }
        m = ANY_LINK.matcher(body);
        while (m.find()) {
            String u = cleanUrl(m.group(1));
            if (!u.contains("github.com/151shi23/MinewaysMobile")) {
                return u;
            }
        }
        return null;
    }

    private static String cleanUrl(String u) {
        return u == null ? null : u.replaceAll("[\\s，。；、）)】\\]]+$", "");
    }

    // ---------------------------------------------------------------- 规则判定

    /**
     * 命中强更则返回命中的规则文本，否则返回 null。
     * 规则可多条并存；EXCEPT 反选优先于版本号列表。
     */
    static String forcedRule(String body) {
        return forcedRule(body, "0");
    }

    static String forcedRule(String body, String currentVersion) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher m = RULE.matcher(body);
        boolean exceptRule = false;
        List<int[]> exceptRanges = new ArrayList<>();
        int cur = code(currentVersion);
        while (m.find()) {
            String arg = m.group(1).trim();
            String upper = arg.toUpperCase();
            if (upper.startsWith("ALL") || arg.startsWith("全部")) {
                return arg;
            }
            boolean except = upper.startsWith("EXCEPT") || arg.startsWith("排除");
            String nums = arg.replaceAll("(?i)^(EXCEPT|排除)", "").trim();
            List<Integer> list = parseVersions(nums);
            if (except) {
                exceptRule = true;
                for (int v : list) {
                    exceptRanges.add(new int[]{v, v});
                }
            } else {
                for (int v : list) {
                    if (cur <= v) {
                        return arg;
                    }
                }
            }
        }
        if (exceptRule) {
            boolean excluded = false;
            for (int[] r : exceptRanges) {
                if (cur >= r[0] && cur <= r[1]) {
                    excluded = true;
                    break;
                }
            }
            // 反选：只有被排除的版本不参与强更，其余版本一律强更
            return excluded ? null : "EXCEPT(排除 " + currentVersion + ")";
        }
        return null;
    }

    /** 3.16 / 3.16.0 / v3.16 -> 316；取主次版本两位十进制。 */
    static int code(String v) {
        if (v == null) {
            return 0;
        }
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int major = 0, minor = 0;
        try {
            if (parts.length > 0 && !parts[0].isEmpty()) {
                major = Integer.parseInt(parts[0]);
            }
            if (parts.length > 1 && !parts[1].isEmpty()) {
                minor = Integer.parseInt(parts[1]);
            }
        } catch (Throwable ignored) {
        }
        return major * 100 + minor;
    }

    static List<Integer> parseVersions(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String part : s.split("[,\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int dash = p.indexOf('-');
            if (dash > 0) {
                int a = code(p.substring(0, dash));
                int b = code(p.substring(dash + 1));
                for (int v = Math.min(a, b); v <= Math.max(a, b); v++) {
                    out.add(v);
                }
            } else if (p.matches(".*\\d.*")) {
                out.add(code(p));
            }
        }
        return out;
    }
}
