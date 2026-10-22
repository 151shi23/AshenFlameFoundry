package com.mineways;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动闸门：每次启动**必须**联网读一遍策略（GitHub Releases，多源兜底），据此决定：
 *
 * <ol>
 *   <li><b>强制更新（拦截）</b>：命中强更规则（版本规则 / 安卓系统规则），只允许更新；</li>
 *   <li><b>提示更新（不拦截）</b>：命中提示/日志规则 -> 显示完整 Release 更新内容，可继续使用；</li>
 *   <li><b>多级强制链</b>：只引导升级到"比当前版本高的、最小的那个被强制版本"；</li>
 *   <li><b>离线可用</b>：策略缓存（last-known-good）+ 静态 policy.json + 镜像，全挂时按缓存执行。</li>
 * </ol>
 *
 * 指令写在 Release 正文里，一行一条、必须行首（详见仓库 README / 协议文档）：
 * <pre>
 *   强更 全部 / FORCE ALL                  所有低版本强制
 *   强更 2.1  / 强更 2.1-2.5 / 强更 排除 2.2,2.3
 *   更新 全部 / 提示更新 2.4                 只提示、不强制
 *   日志 全部 / CHANGELOG 全部              显示完整更新内容（可与强更组合）
 *   忽略 3.21 / SKIP 3.21                  这些版本直接放行（忽略 全部 = 临时关闸门）
 *   最低 3.1 / MIN 3.1                     低于该版本一律强制
 *   停止 3.0 / EOL 3.0                     该版本已停用（强制 + 红字）
 *   安卓 强制 13 / ANDROID FORCE 13          Android 13 及以下必须更新
 *   安卓 跳过 12 / 安卓 提示 13 / 安卓 日志 13 / 安卓 最低 12
 *   离线 放行|拦截 / 缓存 7 / 镜像: URL / 重试 3 / 超时 12
 *   截止 2026-10-01 / 窗口 2026-10-01~2026-10-07 / 生效 2026-10-05
 *   标题: … / 说明: … / 按钮: … / 公告 … / 紧急 / 倒计时 10 / 每日一次 / 重复 关闭
 *   链接: URL / 备用链接: URL / 提取码: abcd / SHA256: … / 大小: 74MB
 *   灰度 10% / 灰度 排除 10%
 * </pre>
 */
public class UpdateGateActivity extends Activity {

    // ---------------------------------------------------------------- 常量

    private static final String API = "https://api.github.com/repos/151shi23/AshenFlameFoundry/releases?per_page=100";
    private static final String PAGE = "https://github.com/151shi23/AshenFlameFoundry/releases/latest";
    private static final String QQ_GROUP = "https://qm.qq.com/q/6mxOaslq7e";

    /** 备用策略源（不吃 GitHub API 限流；国内直连不通时可被 镜像: 指令扩展）。 */
    private static final String[] FALLBACK_SOURCES = new String[]{
            "https://raw.githubusercontent.com/151shi23/AshenFlameFoundry/main/policy.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/151shi23/AshenFlameFoundry/main/policy.json",
            "https://raw.gitmirror.com/151shi23/AshenFlameFoundry/main/policy.json",
    };

    private static final int DEFAULT_RETRY = 3;
    private static final int DEFAULT_TIMEOUT = 12;
    private static final int DEFAULT_CACHE_DAYS = 7;
    private static final String CACHE_PREFS = "update_gate_cache";
    private static final String KEY_CACHE_JSON = "policy_json";
    private static final String KEY_CACHE_AT = "fetched_at";
    private static final String KEY_LAST_NOTIFY = "last_notify_ver";

    // ---------------------------------------------------------------- 正则

    /** 版本类规则：关键字 + 参数。 */
    private static final Pattern RE_VERSION = Pattern.compile(
            "(?im)^\\s*(强更|FORCE|提示更新|更新|UPDATE|NOTIFY|日志|更新日志|CHANGELOG|忽略|免检|SKIP"
                    + "|最低|MIN|停止|EOL)\\s+(ALL|全部|EXCEPT\\s+[^\\n]+|排除\\s+[^\\n]+|[0-9][0-9.,\\s\\-]*)");

    /** 安卓系统类规则：安卓/ANDROID + 动作 + 参数。 */
    private static final Pattern RE_ANDROID = Pattern.compile(
            "(?im)^\\s*(安卓|ANDROID)\\s*(强制|FORCE|跳过|SKIP|提示|NOTIFY|日志|CHANGELOG|最低|MIN)"
                    + "\\s+(ALL|全部|EXCEPT\\s+[^\\n]+|排除\\s+[^\\n]+|[0-9][0-9.,\\s\\-]*)");

    /**
     * 单条规则自己的条件：同一行里写 {@code when="…"} / {@code if="…"} / {@code 条件: …}。
     * 引号可省，但表达式里有空格时建议用双引号包住。
     */
    private static final Pattern RE_WHEN = Pattern.compile(
            "(?i)(?:when|if|条件)\\s*[=:：]\\s*(?:\"([^\"]*)\"|'([^']*)'|(.+))");

    /** 离线策略：离线 放行 / 离线 拦截。 */
    private static final Pattern RE_OFFLINE = Pattern.compile(
            "(?im)^\\s*(离线|OFFLINE)\\s*(放行|PASS|拦截|BLOCK)");    /** 缓存天数。 */
    private static final Pattern RE_CACHE = Pattern.compile("(?im)^\\s*(缓存|CACHE)\\s+(\\d{1,3})");
    /** 重试次数 / 超时秒数。 */
    private static final Pattern RE_RETRY = Pattern.compile("(?im)^\\s*(重试|RETRY)\\s+(\\d{1,2})");
    private static final Pattern RE_TIMEOUT = Pattern.compile("(?im)^\\s*(超时|TIMEOUT)\\s+(\\d{1,3})");
    /** 镜像源（可多行）。 */
    private static final Pattern RE_MIRROR = Pattern.compile("(?im)^\\s*(镜像|备用源|MIRROR)\\s*[:：]\\s*(https?://\\S+)");

    /** 时间类：截止 / 窗口 / 生效。 */
    private static final Pattern RE_DEADLINE = Pattern.compile("(?im)^\\s*(截止|DEADLINE)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern RE_WINDOW = Pattern.compile("(?im)^\\s*(窗口|WINDOW)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})\\s*[~～-]\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern RE_ACTIVATE = Pattern.compile("(?im)^\\s*(生效|ACTIVATE)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})");

    /** 展示类。 */
    private static final Pattern RE_TITLE = Pattern.compile("(?im)^\\s*(标题|TITLE)\\s*[:：]\\s*(.+)$");
    private static final Pattern RE_DESC = Pattern.compile("(?im)^\\s*(说明|副标题|DESC)\\s*[:：]\\s*(.+)$");
    private static final Pattern RE_BUTTON = Pattern.compile("(?im)^\\s*(按钮|BUTTON)\\s*[:：]\\s*(.+)$");
    private static final Pattern RE_NOTICE = Pattern.compile("(?im)^\\s*(公告|NOTICE)\\s*[:：]?\\s*(.+)$");
    private static final Pattern RE_URGENT = Pattern.compile("(?im)^\\s*(紧急|URGENT)\\s*$");
    private static final Pattern RE_COUNTDOWN = Pattern.compile("(?im)^\\s*(倒计时|COUNTDOWN)\\s+(\\d{1,3})");
    private static final Pattern RE_ONCE_A_DAY = Pattern.compile("(?im)^\\s*(每日一次|ONCE\\s*A\\s*DAY)\\s*$");
    private static final Pattern RE_REPEAT_OFF = Pattern.compile("(?im)^\\s*(重复|REPEAT)\\s*[:：]?\\s*(关闭|OFF)\\s*$");

    /** 下载类。 */
    private static final Pattern RE_BACKUP_LINK = Pattern.compile("(?im)^\\s*(备用链接|备用下载|备用|MIRROR\\s*LINK|BACKUP)\\s*[:：]\\s*(https?://\\S+)");
    private static final Pattern RE_CODE = Pattern.compile("(?im)^\\s*(提取码|访问码|密码|CODE)\\s*[:：]\\s*(\\S+)");
    private static final Pattern RE_SHA = Pattern.compile("(?im)^\\s*(SHA256|校验|SHA-256)\\s*[:：]\\s*([0-9a-fA-F]{16,64})");
    private static final Pattern RE_SIZE = Pattern.compile("(?im)^\\s*(大小|SIZE)\\s*[:：]\\s*(\\S+)");
    /** 主下载链接：关键词 + 冒号 + 链接（GitHub 自身链接会被跳过）。 */
    private static final Pattern RE_MAIN_LINK = Pattern.compile(
            "(?im)^\\s*[^\\n:：]{0,24}(?:链接|下载|网盘|地址|直链|蓝奏云|百度网盘|夸克|阿里云盘|天翼云盘|迅雷|奶牛快传"
                    + "|download|link|mirror|url|apk)\\s*[:：]\\s*(https?://\\S+)");

    /** 灰度：灰度 10% / 灰度 排除 10%。 */
    private static final Pattern RE_ROLLOUT = Pattern.compile("(?im)^\\s*(灰度|ROLLOUT)\\s*(排除|EXCEPT)?\\s*(\\d{1,3})\\s*%?");

    /** 任意链接（兜底取链用）。 */
    private static final Pattern RE_ANY_LINK = Pattern.compile("(https?://[^\\s\\)\\]\"'<>]+)");

    // ---------------------------------------------------------------- 状态

    private SharedPreferences prefs;
    private String currentVersion;
    private int currentApi;
    private int currentMajor;

    private Policy cachedPolicy;      // 上次成功拉取时的策略（离线用）
    private long cacheAt;             // 上次成功拉取时间
    private int retryCount = DEFAULT_RETRY;
    private int timeoutMs = DEFAULT_TIMEOUT * 1000;

    private TextView tvTitle, tvDesc, tvNotice, tvMsg, tvContent, tvCountdown, tvRule;
    private Button btnMain, btnGh, btnCopy, btnRetry, btnKeep, btnQq;

    private Decision decision;
    private int countdownLeft;
    /** 已经放过行：防止看门狗 / 倒计时 / 按钮重复拉起 MainActivity。 */
    private boolean passed;

    /** 云控 update_off=1：整层闸门关闭。读不到 / 读错一律 false（fail-open）。 */
    private volatile boolean cloudOff;
    /** 云控 update_force_off=1：强更降级为提示，仍然给下载入口。 */
    private volatile boolean cloudForceOff;
    /** 云控 update_quiet=1：静音，只算状态不弹窗不拦人。 */
    private volatile boolean cloudQuiet;
    /** 云控 update_block：这些版本不当作更新目标（发了坏包时先别推它）。 */
    private volatile String cloudBlock = "";
    /** 云控 update_hold：当前版本命中这些版本就拦住，引导降级（回收坏版本）。 */
    private volatile String cloudHold = "";
    /** 云控 update_hold_to：降级到哪个版本。 */
    private volatile String cloudHoldTo = "";
    /** 云控 update_hold_url：降级包的下载页。 */
    private volatile String cloudHoldUrl = "";
    /** 云控 update_hold_notice：拦住时给用户看的话。 */
    private volatile String cloudHoldNotice = "";
    /** 云控 update_max_wait：检查更新最多等几秒，超时直接放行（0 = 不设限）。 */
    private volatile int cloudMaxWait;
    /** 超时看门狗：只在"还没算出结论"时才放行，绝不影响已经判定的强更。 */
    private android.os.Handler watchdog;
    /**
     * 条件变量表（与公告 / 功能目录同一套，另把云控 FLAG 合进来）。
     * 在后台线程算好，UI 线程只读 —— 里面要读登录态、设备名、共享偏好，不适合放主线程。
     */
    private volatile Map<String, String> condVars = Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        currentVersion = versionName();
        currentApi = Build.VERSION.SDK_INT;
        currentMajor = androidMajorOf(currentApi, Build.VERSION.RELEASE);
        // 先归零：这次没算完就退出，也别把上一次的旧状态留给公告 / 功能目录
        UpdateState.save(this, "none", "");
        loadCache();
        buildUi();
        check(0);
    }

    @Override
    public void onBackPressed() {
        // 强制更新时禁止返回键绕过；非强制时等同于"继续使用"
        if (decision != null && decision.forced) {
            return;
        }
        pass();
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
        int pad = (int) (getResources().getDisplayMetrics().density * 22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(pad, pad, pad, pad);

        tvTitle = new TextView(this);
        tvTitle.setText("MinewaysMobile");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(22);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        root.addView(tvTitle);

        tvDesc = new TextView(this);
        tvDesc.setTextColor(Color.parseColor("#8FA3B5"));
        tvDesc.setTextSize(14);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setVisibility(View.GONE);
        root.addView(tvDesc);

        tvNotice = new TextView(this);
        tvNotice.setTextColor(Color.parseColor("#7BE3C0"));
        tvNotice.setTextSize(14);
        tvNotice.setGravity(Gravity.CENTER);
        tvNotice.setPadding(0, pad / 2, 0, 0);
        tvNotice.setVisibility(View.GONE);
        root.addView(tvNotice);

        tvMsg = new TextView(this);
        tvMsg.setText("正在检查更新…");
        tvMsg.setTextColor(Color.parseColor("#B9C4CF"));
        tvMsg.setTextSize(15);
        tvMsg.setGravity(Gravity.CENTER);
        tvMsg.setPadding(0, pad, 0, pad);
        root.addView(tvMsg);

        tvRule = new TextView(this);
        tvRule.setTextColor(Color.parseColor("#FFB74D"));
        tvRule.setTextSize(13);
        tvRule.setGravity(Gravity.CENTER);
        tvRule.setVisibility(View.GONE);
        root.addView(tvRule);

        tvContent = new TextView(this);
        tvContent.setTextColor(Color.parseColor("#D6DEE6"));
        tvContent.setTextSize(14);
        tvContent.setLineSpacing(6f, 1f);
        tvContent.setTextIsSelectable(true);
        tvContent.setVisibility(View.GONE);
        root.addView(tvContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvCountdown = new TextView(this);
        tvCountdown.setTextColor(Color.parseColor("#FFD54F"));
        tvCountdown.setTextSize(14);
        tvCountdown.setGravity(Gravity.CENTER);
        tvCountdown.setPadding(0, pad / 2, 0, 0);
        tvCountdown.setVisibility(View.GONE);
        root.addView(tvCountdown);

        btnMain = new Button(this);
        btnMain.setText("前往下载更新");
        btnMain.setVisibility(View.GONE);
        btnMain.setOnClickListener(v -> openUrl(decision != null ? decision.downloadTarget() : PAGE));
        root.addView(btnMain, centerParams());

        btnCopy = new Button(this);
        btnCopy.setText("复制提取码");
        btnCopy.setVisibility(View.GONE);
        btnCopy.setOnClickListener(v -> {
            if (decision != null && decision.code != null) {
                copyText(decision.code);
                toast("提取码已复制：" + decision.code);
            }
        });
        root.addView(btnCopy, centerParams());

        btnKeep = new Button(this);
        btnKeep.setText("继续使用当前版本");
        btnKeep.setVisibility(View.GONE);
        btnKeep.setOnClickListener(v -> pass());
        root.addView(btnKeep, centerParams());

        btnRetry = new Button(this);
        btnRetry.setText("重试检查");
        btnRetry.setVisibility(View.GONE);
        btnRetry.setOnClickListener(v -> {
            btnRetry.setVisibility(View.GONE);
            btnMain.setVisibility(View.GONE);
            tvContent.setVisibility(View.GONE);
            tvRule.setVisibility(View.GONE);
            tvNotice.setVisibility(View.GONE);
            tvCountdown.setVisibility(View.GONE);
            check(0);
        });
        root.addView(btnRetry, centerParams());

        btnGh = new Button(this);
        btnGh.setText("去 GitHub Release 页面");
        btnGh.setOnClickListener(v -> openUrl(PAGE));
        root.addView(btnGh, centerParams());

        btnQq = new Button(this);
        btnQq.setText("点击链接加入群聊【AshenFlame-No. 2】");
        btnQq.setOnClickListener(v -> openUrl(QQ_GROUP));
        root.addView(btnQq, centerParams());

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

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) {
            url = PAGE;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("MinewaysMobile", text));
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void pass() {
        // 看门狗、自动倒计时、按钮三条路都可能调到，别把 MainActivity 拉起两次
        if (passed) {
            return;
        }
        passed = true;
        if (watchdog != null) {
            watchdog.removeCallbacksAndMessages(null);
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---------------------------------------------------------------- 云控联动

    /**
     * 读云控的两个<b>放宽</b>开关。
     *
     * <p>这里刻意只读"能让闸门更宽松"的开关，不读任何"能从云端新增拦截"的开关 ——
     * 闸门是唯一入口，一个写错的 FLAG 就能让所有人打不开 App，这个扳机不该存在。
     * 任何异常都按 false 处理（fail-open），云控没配也等于 false。
     */
    private void readCloudSwitches() {
        try {
            com.mineways.cloud.FeatureGate g = com.mineways.cloud.CloudCatalog.gate();
            cloudOff = "1".equals(g.flag("update_off", ""));
            cloudForceOff = "1".equals(g.flag("update_force_off", ""));
            cloudQuiet = "1".equals(g.flag("update_quiet", ""));
            cloudBlock = g.flag("update_block", "");
            cloudHold = g.flag("update_hold", "");
            cloudHoldTo = g.flag("update_hold_to", "");
            cloudHoldUrl = g.flag("update_hold_url", "");
            cloudHoldNotice = g.flag("update_hold_notice", "");
            int w = intOf(g.flag("update_max_wait", ""), 0);
            // 越界（0 或离谱的数）当没配：宁可慢，也不能因为一个写错的数字把人挡在门外
            cloudMaxWait = (w >= 3 && w <= 60) ? w : 0;
        } catch (Throwable t) {
            cloudOff = false;
            cloudForceOff = false;
            cloudQuiet = false;
            cloudBlock = "";
            cloudHold = "";
            cloudHoldTo = "";
            cloudHoldUrl = "";
            cloudHoldNotice = "";
            cloudMaxWait = 0;
        }
    }

    /**
     * 条件变量表：与公告 / 功能目录<b>同一套</b>（{@link AnnouncementCenter#vars}），
     * 另外把云控 FLAG 合进来，于是 {@code when="flag_xxx == 1"} 也能写。
     *
     * <p>只允许在后台线程调用：里面要读登录态、设备名、共享偏好，全是磁盘活。
     */
    private Map<String, String> buildCondVars() {
        try {
            Map<String, String> v = new HashMap<>(AnnouncementCenter.vars(this));
            v.putAll(com.mineways.cloud.CloudCatalog.gate().condVars(v));
            return v;
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    /**
     * 灰度分桶：优先用配置 / 公告那套 {@code roll}（deviceHash + uid），
     * 这样「看到新功能的人」和「收到新版本的人」才是同一批，A/B 才对得上。
     * 取不到才退回旧的 ANDROID_ID 哈希（老逻辑保留，避免异常时把灰度算成 0）。
     */
    private int bucketNow() {
        try {
            String r = condVars.get("roll");
            if (r != null) {
                int b = Integer.parseInt(r.trim());
                if (b >= 0 && b <= 99) {
                    return b;
                }
            }
        } catch (Throwable ignored) {
        }
        return rolloutBucket(this);
    }

    /**
     * 把云控结论盖在本地判定之上，并把结果回传给公告 / 功能目录（{@link UpdateState}）。
     *
     * <p>优先级：<b>急停 → 回收坏版本 → 屏蔽坏版本 → 静音 → 强更降级</b>。
     * 前三条里只有「回收坏版本」会拦人，其余全是"放人进去"；而且它只认精确版本号，
     * 写错的爆炸半径限定在"装了那个具体版本的人"。
     */
    private void applyCloud(Decision d) {
        if (d == null) {
            return;
        }

        // ① 急停：整层关掉，连强更都不算
        if (cloudOff) {
            d.forced = false;
            d.target = null;                      // 放行：render 末尾会直接 pass()
            d.rule = joinRule(d.rule, "云控 update_off");
            UpdateState.save(this, UpdateState.OFF, "");
            return;
        }

        // ② 回收坏版本：当前版本在名单里 → 拦住，指到上一版
        if (holdHit()) {
            Rel to = holdTarget();
            d.forced = true;
            d.target = to;
            d.title = "该版本已停止使用";
            d.desc = holdNotice();
            d.button = "前往下载";
            d.urgent = true;
            d.countdown = 0;
            d.link = to.page;                     // 不能再用坏版本那个下载地址
            d.backupLink = null;
            d.code = null;
            d.rule = joinRule(d.rule, "云控 update_hold " + currentVersion);
            UpdateState.save(this, UpdateState.HOLD, to.tag);
            return;
        }

        // ③ 屏蔽坏版本：不把它当目标 —— 宁可先放人进去，也不推一个有问题的包
        if (d.target != null && blockHit(d.target)) {
            d.rule = joinRule(d.rule, "云控 update_block " + d.target.tag);
            d.forced = false;
            d.target = null;
        }

        // ④ 静音：状态照算，但不弹窗不拦人（用来"先静音、看状态、再决定"）
        if (cloudQuiet) {
            UpdateState.save(this, UpdateState.QUIET, d.target == null ? "" : d.target.tag);
            d.forced = false;
            d.target = null;
            return;
        }

        // ⑤ 强更降级为提示：target 保留，下载入口还在
        if (cloudForceOff && d.forced) {
            d.forced = false;
            d.rule = joinRule(d.rule, "云控 update_force_off");
        }

        UpdateState.save(this,
                d.forced ? UpdateState.FORCE
                        : (d.target != null ? UpdateState.NOTIFY : UpdateState.NONE),
                d.target == null ? "" : d.target.tag);
    }

    /**
     * 当前版本是否在 update_hold 名单里。
     *
     * <p>这里刻意用<b>版本名字符串逐字比对</b>（去掉前缀 v），不用 {@link #code}：
     * code 会把 5.13 和 5.13.1 归成同一个值，而这个开关是唯一会拦人的一条，
     * 差一个补丁号就误伤一批用户，代价太大。
     */
    private boolean holdHit() {
        String cur = normVer(currentVersion);
        if (cur.length() == 0) {
            return false;
        }
        for (String s : splitList(cloudHold)) {
            if (normVer(s).equals(cur)) {
                return true;
            }
        }
        return false;
    }

    private static String normVer(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("v") ? s.substring(1) : s;
    }

    /** 目标版本是否在 update_block 名单里。 */
    private boolean blockHit(Rel t) {
        int v = t.ver > 0 ? t.ver : code(t.tag);
        if (v <= 0) {
            return false;
        }
        for (String s : splitList(cloudBlock)) {
            if (code(s) == v) {
                return true;
            }
        }
        return false;
    }

    /** 造一个"降到上一版"的目标：页面优先用 update_hold_url，没配就回 Release 页。 */
    private Rel holdTarget() {
        Rel r = new Rel();
        String to = cloudHoldTo == null ? "" : cloudHoldTo.trim();
        r.tag = to.length() == 0 ? "旧版本" : to;
        r.ver = code(to);
        r.page = (cloudHoldUrl == null || cloudHoldUrl.length() == 0) ? PAGE : cloudHoldUrl.trim();
        return r;
    }

    private String holdNotice() {
        String s = cloudHoldNotice == null ? "" : cloudHoldNotice.trim();
        return s.length() == 0
                ? "当前版本存在问题，已暂停使用。请下载上一版本覆盖安装，数据不会丢失。"
                : s;
    }

    /**
     * 检查超时的看门狗：只在「还没算出结论」时放行。
     * 已经判成强更的不受影响 —— 否则这个开关就成了绕过强更的后门。
     */
    private void armWatchdog() {
        if (cloudMaxWait <= 0) {
            return;
        }
        if (watchdog == null) {
            watchdog = new android.os.Handler(getMainLooper());
        }
        watchdog.removeCallbacksAndMessages(null);
        watchdog.postDelayed(() -> {
            if (decision == null && !isFinishing() && !isDestroyed()) {
                tvMsg.setText("检查更新超时，已按云端配置放行…");
                UpdateState.save(this, UpdateState.NONE, "");
                pass();
            }
        }, cloudMaxWait * 1000L);
    }

    @Override
    protected void onDestroy() {
        if (watchdog != null) {
            watchdog.removeCallbacksAndMessages(null);
            watchdog = null;
        }
        super.onDestroy();
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String x : s.split("[,，\\s]+")) {
            if (x != null && x.trim().length() > 0) {
                out.add(x.trim());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 检查

    private void check(final int attempt) {
        tvMsg.setText(attempt == 0 ? "正在检查更新…" : ("网络异常，正在重试（" + attempt + "/" + retryCount + "）…"));
        new Thread(() -> {
            readCloudSwitches();          // 云控开关在后台读：flag() 首次会触发整份配置重放
            condVars = buildCondVars();   // 条件变量也在后台算：登录态 / 设备名 / 偏好都是磁盘活
            runOnUiThread(this::armWatchdog);
            String json = null;
            String err = null;
            try {
                json = fetchPolicy();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fJson = json;
            final String fErr = err;
            runOnUiThread(() -> onFetched(fJson, fErr, attempt));
        }, "update-gate").start();
    }

    private void onFetched(String json, String err, int attempt) {
        boolean empty = (json == null) || json.trim().isEmpty() || "[]".equals(json.trim());
        if (empty) {
            if (attempt + 1 < retryCount) {
                check(attempt + 1);
                return;
            }
            onOffline(err);
            return;
        }

        saveCache(json);

        List<Rel> rels = parseReleases(json);
        decision = decide(rels, currentVersion, currentApi, currentMajor, bucketNow(), condVars);
        applyCloud(decision);
        render(decision);
    }

    /** 所有源都取不到：按缓存策略执行（离线 放行/拦截 + 缓存有效期）。 */
    private void onOffline(String err) {
        Policy p = cachedPolicy;
        long ageDays = cacheAt <= 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - cacheAt) / 86400000L;
        int allowed = (p != null && p.cacheDays > 0) ? p.cacheDays : DEFAULT_CACHE_DAYS;
        boolean offlinePass = (p == null) || p.offlinePass;

        if (p != null && ageDays <= allowed) {
            // 有可用缓存：按缓存策略走（含它自己的强更/提示规则）
            List<Rel> rels = parseReleases(p.rawReleases);
            decision = decide(rels, currentVersion, currentApi, currentMajor, bucketNow(), condVars);
            decision.offline = true;
            decision.cachedAt = cacheAt;
            applyCloud(decision);
            render(decision);
            return;
        }

        if (p == null && offlinePass) {
            // 全新安装 + 无网 + 无缓存：默认放行，联网后立刻补检
            tvMsg.setText("无法联网检查更新，已放行进入。\n（下次联网启动会自动补检）");
            btnKeep.setVisibility(View.VISIBLE);
            autoPass(3);
            return;
        }

        // 云端急停：连"缓存过期不许进"也一起放行 —— 这条开关存在的意义就是救这种场
        if (cloudOff) {
            UpdateState.save(this, "off", "");
            tvMsg.setText("已按云端配置跳过更新检查，正在进入…");
            autoPass(2);
            return;
        }

        // 缓存过期 / 策略要求拦截
        String why = (p != null && ageDays > allowed)
                ? ("已超过 " + allowed + " 天未成功联网检查，请联网后重试")
                : "无法完成联网检查，无法进入应用";
        tvMsg.setText(why + "\n" + (err == null ? "" : ("（错误：" + err + "）")));
        btnRetry.setVisibility(View.VISIBLE);
        tvTitle.setText("MinewaysMobile");
    }

    private void autoPass(final int seconds) {
        countdownLeft = seconds;
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.post(new Runnable() {
            @Override
            public void run() {
                if (countdownLeft <= 0) {
                    pass();
                    return;
                }
                tvCountdown.setText(countdownLeft + " 秒后自动进入…");
                countdownLeft--;
                tvCountdown.postDelayed(this, 1000);
            }
        });
    }

    // ---------------------------------------------------------------- 渲染

    private void render(Decision d) {
        tvTitle.setText(d.title != null ? d.title : (d.forced ? "必须更新" : "发现新版本"));
        if (d.desc != null) {
            tvDesc.setText(d.desc);
            tvDesc.setVisibility(View.VISIBLE);
        }
        if (d.notice != null) {
            tvNotice.setText(d.notice);
            tvNotice.setVisibility(View.VISIBLE);
        }
        btnMain.setText(d.button != null ? d.button
                : (d.downloadTarget().equals(PAGE) ? "去 GitHub Release 页面" : "前往下载更新"));

        // 状态文案
        StringBuilder sb = new StringBuilder();
        if (d.forced) {
            sb.append("当前版本 ").append(currentVersion).append(" 需要更新到 ").append(d.target.tag.replaceFirst("(?i)^v", ""));
            if (d.chainText != null && d.chainText.length() > 0) {
                sb.append("\n升级顺序：").append(d.chainText);
            }
        } else if (d.target != null) {
            sb.append("发现新版本 ").append(d.target.tag.replaceFirst("(?i)^v", "")).append("（不强制，可继续使用当前版本）");
        } else {
            sb.append("当前版本 ").append(currentVersion).append(" 可继续使用。");
        }
        if (d.offline) {
            sb.append("\n（离线：按 ").append(ageText(d.cachedAt)).append(" 的缓存策略执行）");
        }
        tvMsg.setText(sb.toString());

        // 命中规则（调试/透明）
        if (d.rule != null && d.rule.length() > 0) {
            tvRule.setText("命中规则：" + d.rule);
            tvRule.setVisibility(View.VISIBLE);
        }

        // 完整更新内容
        if (d.showContent && d.contentBody != null && d.contentBody.trim().length() > 0) {
            tvContent.setText(renderMarkdown(d.contentBody));
            tvContent.setMovementMethod(LinkMovementMethod.getInstance());
            tvContent.setVisibility(View.VISIBLE);
        }

        // 下载信息
        String extra = "";
        if (d.size != null) {
            extra += "  大小：" + d.size;
        }
        if (d.sha != null) {
            extra += "\nSHA256：" + d.sha;
        }
        if (d.code != null) {
            extra += "\n提取码：" + d.code;
            btnCopy.setVisibility(View.VISIBLE);
        }
        if (extra.length() > 0) {
            tvRule.setText((tvRule.getVisibility() == View.VISIBLE ? tvRule.getText() + "\n" : "") + extra.trim());
            tvRule.setVisibility(View.VISIBLE);
        }

        if (d.target != null) {
            btnMain.setVisibility(View.VISIBLE);
        }
        if (!d.forced && d.target != null) {
            btnKeep.setVisibility(View.VISIBLE);
        }
        if (d.forced) {
            if (d.urgent) {
                tvTitle.setTextColor(Color.parseColor("#FF5252"));
                tvMsg.setTextColor(Color.parseColor("#FF8A80"));
            }
            if (d.countdown > 0) {
                startForceCountdown(d.countdown);
            }
        }
        if (d.forced && d.target == null) {
            // 理论上不会发生（强更一定带目标版本），这里兜底允许重试
            btnRetry.setVisibility(View.VISIBLE);
        }
        if (d.target == null && !d.forced) {
            // 无需更新：直接进入
            pass();
        }
    }

    private void startForceCountdown(final int seconds) {
        countdownLeft = seconds;
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.post(new Runnable() {
            @Override
            public void run() {
                if (countdownLeft <= 0) {
                    tvCountdown.setText("请尽快更新后继续使用");
                    return;
                }
                tvCountdown.setText("请在 " + countdownLeft + " 秒后按提示更新（此页无法跳过）");
                countdownLeft--;
                tvCountdown.postDelayed(this, 1000);
            }
        });
    }

    private String ageText(long at) {
        if (at <= 0) {
            return "未知时间";
        }
        long days = (System.currentTimeMillis() - at) / 86400000L;
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return f.format(new Date(at)) + (days > 0 ? ("（" + days + " 天前）") : "");
    }

    /** 轻量 Markdown 渲染：标题加粗放大、列表转圆点、表格转一行、去掉指令行与代码围栏。 */
    static CharSequence renderMarkdown(String body) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = body.replace("\r", "").split("\n");
        boolean inFence = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (isDirective(line)) {
                continue;   // 指令行不给用户看
            }
            if (line.length() == 0) {
                out.append("\n");
                continue;
            }
            if (line.startsWith("|")) {
                String cells = line.replaceAll("^\\|", "").replaceAll("\\|$", "").trim();
                if (cells.matches("[-: |]+")) {
                    continue;   // 分隔行
                }
                line = cells.replace("|", " · ").trim();
            } else if (line.startsWith("#")) {
                line = line.replaceAll("^#+\\s*", "");
            } else if (line.startsWith(">")) {
                line = "▎ " + line.replaceAll("^>+\\s*", "");
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                line = "· " + line.substring(2);
            }
            int start = out.length();
            out.append(line).append("\n");
            int end = out.length();
            // 链接 [文字](url)
            Matcher m = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)").matcher(out.subSequence(start, end).toString());
            while (m.find()) {
                int s = start + m.start();
                int e = start + m.end();
                out.replace(s, e, m.group(1));
                out.setSpan(new URLSpan(m.group(2)), s, s + m.group(1).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                end = out.length();
            }
            // 行内链接（裸 URL）
            Matcher u = RE_ANY_LINK.matcher(out.subSequence(start, out.length()).toString());
            while (u.find()) {
                int s = start + u.start();
                int e = start + u.end();
                if (!hasUrlSpan(out, s, e)) {
                    out.setSpan(new URLSpan(u.group(1)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // 粗体 **x**
            Matcher b = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(out.subSequence(start, out.length()).toString());
            while (b.find()) {
                int s = start + b.start();
                int e = start + b.end();
                out.replace(s, e, b.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, s + b.group(1).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // 行内代码 `x`
            Matcher c = Pattern.compile("`([^`]+)`").matcher(out.subSequence(start, out.length()).toString());
            while (c.find()) {
                int s = start + c.start();
                int e = start + c.end();
                out.replace(s, e, c.group(1));
                out.setSpan(new TypefaceSpan("monospace"), s, s + c.group(1).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (raw.trim().startsWith("#")) {
                out.setSpan(new RelativeSizeSpan(1.18f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static boolean hasUrlSpan(Spanned sp, int start, int end) {
        URLSpan[] spans = sp.getSpans(start, end, URLSpan.class);
        return spans != null && spans.length > 0;
    }

    /** 该行是否是一条指令（展示时剥离）。 */
    static boolean isDirective(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String s = line.trim();
        // 用 "· " 前缀可以在正文里安全地举例说明指令写法
        if (s.startsWith("· ")) {
            return false;
        }
        return RE_VERSION.matcher(s).find()
                || RE_ANDROID.matcher(s).find()
                || RE_OFFLINE.matcher(s).find()
                || RE_CACHE.matcher(s).find()
                || RE_RETRY.matcher(s).find()
                || RE_TIMEOUT.matcher(s).find()
                || RE_MIRROR.matcher(s).find()
                || RE_DEADLINE.matcher(s).find()
                || RE_WINDOW.matcher(s).find()
                || RE_ACTIVATE.matcher(s).find()
                || RE_TITLE.matcher(s).find()
                || RE_DESC.matcher(s).find()
                || RE_BUTTON.matcher(s).find()
                || RE_NOTICE.matcher(s).find()
                || RE_URGENT.matcher(s).find()
                || RE_COUNTDOWN.matcher(s).find()
                || RE_ONCE_A_DAY.matcher(s).find()
                || RE_REPEAT_OFF.matcher(s).find()
                || RE_BACKUP_LINK.matcher(s).find()
                || RE_CODE.matcher(s).find()
                || RE_SHA.matcher(s).find()
                || RE_SIZE.matcher(s).find()
                || RE_ROLLOUT.matcher(s).find()
                || RE_MAIN_LINK.matcher(s).find();
    }

    // ---------------------------------------------------------------- 网络与缓存

    /** 依次尝试：GitHub API -> 静态 policy.json -> 镜像（指令可扩展）。 */
    private String fetchPolicy() {
        List<String> urls = new ArrayList<>();
        urls.add(API);
        if (cachedPolicy != null && cachedPolicy.mirrors != null) {
            urls.addAll(cachedPolicy.mirrors);
        }
        for (String f : FALLBACK_SOURCES) {
            if (!urls.contains(f)) {
                urls.add(f);
            }
        }
        for (String u : urls) {
            try {
                String body = httpGet(u);
                String normal = normalize(body);
                if (normal != null && !normal.trim().isEmpty() && !"[]".equals(normal.trim())) {
                    return normal;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** policy.json 包成 {"releases":[…]}，这里统一成数组字符串。 */
    private static String normalize(String body) {
        if (body == null) {
            return null;
        }
        String t = body.trim();
        if (t.startsWith("[")) {
            return t;
        }
        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                JSONArray arr = o.optJSONArray("releases");
                return arr == null ? null : arr.toString();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json");
        conn.setRequestProperty("User-Agent", "MinewaysMobile-UpdateGate");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
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
        return sb.toString();
    }

    private void loadCache() {
        String json = prefs.getString(KEY_CACHE_JSON, null);
        cacheAt = prefs.getLong(KEY_CACHE_AT, 0L);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                if (arr.length() > 0) {
                    cachedPolicy = parsePolicy(newestRel(arr), 0, 0);
                    cachedPolicy.rawReleases = json;
                    if (cachedPolicy.retry > 0) {
                        retryCount = cachedPolicy.retry;
                    }
                    if (cachedPolicy.timeout > 0) {
                        timeoutMs = cachedPolicy.timeout * 1000;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void saveCache(String json) {
        try {
            prefs.edit().putString(KEY_CACHE_JSON, json)
                    .putLong(KEY_CACHE_AT, System.currentTimeMillis()).apply();
            cacheAt = System.currentTimeMillis();
            JSONArray arr = new JSONArray(json);
            if (arr.length() > 0) {
                cachedPolicy = parsePolicy(newestRel(arr), 0, 0);
                cachedPolicy.rawReleases = json;
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 数据与判定

    static class Rel {
        int ver;
        String tag = "";
        String body = "";
        String page = "";
        long publishedAt;
    }

    static class Policy {
        String rawReleases;              // 离线回放用的原始 JSON
        // 版本规则（多条）
        List<Rule> versionRules = new ArrayList<>();
        // 安卓规则（多条）
        List<Rule> androidRules = new ArrayList<>();
        // 网络
        boolean offlinePass = true;      // 默认放行（避免新装用户在地铁里第一次打开就被卡死）
        int cacheDays = DEFAULT_CACHE_DAYS;
        int retry = DEFAULT_RETRY;
        int timeout = DEFAULT_TIMEOUT;
        List<String> mirrors = new ArrayList<>();
        // 时间
        long deadline;                   // >0：到点后 提示 -> 强制
        long windowFrom, windowTo;       // >0：仅窗口期内生效
        long activateAt;                 // >0：到点才生效
        // 展示
        String title, desc, button, notice, code, sha, size;
        boolean urgent, onceADay, repeatOff;
        int countdown;
        // 下载
        String link, backupLink;
        // 灰度
        boolean rolloutSet;
        boolean rolloutExcept;
        int rolloutPercent;
    }

    /** 一条规则：种类 + 匹配方式 + 参数。 */
    static class Rule {
        String kind;      // FORCE / NOTIFY / LOG / SKIP / MIN / EOL
        String mode;      // ALL / LIST / EXCEPT / RANGE
        List<Integer> values = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        String text = "";
        /** 这条规则自己的条件（同一行写 when="…"）；空 = 无条件。 */
        String when = "";
    }

    static class Decision {
        Rel target;            // 需要更新到的版本（强制或提示）
        Policy policy;
        String contentBody;    // 要展示的完整更新内容
        boolean forced;
        boolean showContent;
        boolean offline;
        long cachedAt;
        String rule = "";
        String chainText = "";
        String link, backupLink, code, sha, size;
        // 展示类文案（来自策略）
        String title, desc, button, notice;
        boolean urgent;
        int countdown;

        String downloadTarget() {
            if (link != null && !link.isEmpty()) {
                return link;
            }
            if (backupLink != null && !backupLink.isEmpty()) {
                return backupLink;
            }
            return target != null && target.page != null && !target.page.isEmpty() ? target.page : PAGE;
        }
    }

    /** 单条 JSON -> Rel（兼容 GitHub API 的 tag_name/html_url 与 policy.json 的 tag/url）。 */
    static Rel relOf(JSONObject o) {
        if (o == null) {
            return null;
        }
        Rel r = new Rel();
        r.tag = o.optString("tag", o.optString("tag_name", ""));
        r.ver = code(r.tag);
        r.body = o.optString("body", "");
        r.page = o.optString("url", o.optString("html_url", ""));
        return r;
    }

    /** 取数组里版本号最高的那条（离线策略的默认值取自它）。 */
    static Rel newestRel(JSONArray arr) {
        Rel best = null;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Rel r = relOf(arr.optJSONObject(i));
            if (r == null || r.ver <= 0) {
                continue;
            }
            if (best == null || r.ver > best.ver) {
                best = r;
            }
        }
        return best;
    }

    static List<Rel> parseReleases(String json) {
        List<Rel> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optBoolean("draft", false)) {
                    continue;
                }
                String tag = o.optString("tag", o.optString("tag_name", ""));
                int v = code(tag);
                if (v <= 0) {
                    continue;
                }
                Rel r = new Rel();
                r.ver = v;
                r.tag = tag;
                r.body = o.optString("body", "");
                r.page = o.optString("url", o.optString("html_url", ""));
                r.publishedAt = o.optLong("published_at_ms", 0L);
                out.add(r);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new Comparator<Rel>() {
            @Override
            public int compare(Rel a, Rel b) {
                return a.ver - b.ver;
            }
        });
        return out;
    }

    /**
     * 关键判定：
     * 1) 放行优先：任何 release 里写了 `忽略 当前版本` / `安卓 跳过 当前系统` -> 直接放行；
     * 2) 逐个更高的版本（升序），命中强更（版本规则或安卓规则）即取"最小的被强制版本"；
     * 3) 没有强制 -> 目标为最新版，仅提示（自愿更新）；
     * 4) 展示：本版策略为"全显示"，正文完整呈现（指令行自动剥离）。
     */
    static Decision decide(List<Rel> rels, String currentVersion, int api, int major, int bucket) {
        return decide(rels, currentVersion, api, major, bucket, null);
    }

    /** 带条件变量的版本：规则里的 when="…" 按 {@code vars} 求值。 */
    static Decision decide(List<Rel> rels, String currentVersion, int api, int major, int bucket,
                           Map<String, String> vars) {
        Decision d = new Decision();
        int cur = code(currentVersion);
        long now = System.currentTimeMillis();

        List<Rel> higher = new ArrayList<>();
        for (Rel r : rels) {
            if (r.ver > cur) {
                higher.add(r);
            }
        }
        Collections.sort(higher, new Comparator<Rel>() {
            @Override
            public int compare(Rel a, Rel b) {
                return a.ver - b.ver;
            }
        });

        Rel newest = higher.isEmpty() ? null : higher.get(higher.size() - 1);
        Policy newestPolicy = newest != null ? parsePolicy(newest, api, major, vars)
                : (rels.isEmpty() ? null : parsePolicy(rels.get(rels.size() - 1), api, major, vars));

        // 1) 放行优先：忽略（白名单）/ 安卓跳过
        for (Rel r : rels) {
            Policy p = (r == newest) ? newestPolicy : parsePolicy(r, api, major, vars);
            if (!active(p, now, bucket)) {
                continue;
            }
            for (Rule rule : p.versionRules) {
                if ("SKIP".equals(rule.kind) && match(rule, cur)) {
                    d.rule = "忽略 " + currentVersion;
                    return d;   // 无目标 = 放行
                }
            }
            for (Rule rule : p.androidRules) {
                if ("SKIP".equals(rule.kind) && matchAndroid(rule, api, major)) {
                    d.rule = "安卓 跳过 " + major;
                    return d;
                }
            }
        }

        if (higher.isEmpty()) {
            return d;   // 已是最新 -> 放行
        }

        Policy display = newestPolicy;
        // 2) 逐个更高的版本，找"最小的被强制版本"
        for (Rel r : higher) {
            Policy p = (r == newest) ? newestPolicy : parsePolicy(r, api, major, vars);
            if (!active(p, now, bucket)) {
                continue;
            }
            boolean force = false;
            boolean showLog = false;
            String forceRule = "";
            for (Rule rule : p.versionRules) {
                if ("SKIP".equals(rule.kind)) {
                    continue;
                }
                if ("FORCE".equals(rule.kind) && match(rule, cur)) {
                    force = true;
                    forceRule = "强更 " + rule.text;
                } else if ("EOL".equals(rule.kind) && match(rule, cur)) {
                    force = true;
                    forceRule = "停止 " + rule.text;
                    p.urgent = true;
                } else if ("MIN".equals(rule.kind) && cur < rule.min) {
                    force = true;
                    forceRule = "最低 " + rule.min;
                } else if ("LOG".equals(rule.kind) && match(rule, cur)) {
                    showLog = true;
                }
            }
            for (Rule rule : p.androidRules) {
                if ("FORCE".equals(rule.kind) && matchAndroid(rule, api, major)) {
                    force = true;
                    forceRule = joinRule(forceRule, "安卓 强制 " + rule.text);
                } else if ("MIN".equals(rule.kind) && major < rule.min) {
                    force = true;
                    forceRule = joinRule(forceRule, "安卓 最低 " + rule.min);
                } else if ("LOG".equals(rule.kind) && matchAndroid(rule, api, major)) {
                    showLog = true;
                }
            }

            if (force) {
                d.forced = true;
                d.target = r;
                display = p;
                d.rule = forceRule;
                break;
            }
            // 截止时间：过了截止，提示升格为强制
            if (hitNotify(p, cur, api, major) && p.deadline > 0 && now >= p.deadline) {
                d.forced = true;
                d.target = r;
                display = p;
                d.rule = "截止 " + new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(p.deadline)) + "（已过）";
                break;
            }
            if (showLog && d.contentBody == null) {
                d.contentBody = r.body;   // 记下最早的"要展示日志"的版本
            }
        }

        if (!d.forced) {
            d.target = newest;              // 自愿更新：目标=最新版，仅提示
            display = newestPolicy;
        }
        if (d.contentBody == null) {
            d.contentBody = d.target != null ? d.target.body : (newest != null ? newest.body : null);
        }

        // 3) 展示：本版策略"全显示"；展示类文案/下载信息优先取被强更的版本，其次最新版
        d.showContent = true;
        d.policy = display != null ? display : new Policy();
        d.urgent = d.policy.urgent;
        d.countdown = d.policy.countdown;
        Policy np = newestPolicy;
        d.title = firstNonEmpty(d.policy.title, np == null ? null : np.title);
        d.desc = firstNonEmpty(d.policy.desc, np == null ? null : np.desc);
        d.button = firstNonEmpty(d.policy.button, np == null ? null : np.button);
        d.notice = firstNonEmpty(d.policy.notice, np == null ? null : np.notice);
        d.link = firstNonEmpty(d.policy.link, np == null ? null : np.link);
        d.backupLink = firstNonEmpty(d.policy.backupLink, np == null ? null : np.backupLink);
        d.code = firstNonEmpty(d.policy.code, np == null ? null : np.code);
        d.sha = firstNonEmpty(d.policy.sha, np == null ? null : np.sha);
        d.size = firstNonEmpty(d.policy.size, np == null ? null : np.size);

        StringBuilder chain = new StringBuilder();
        for (Rel x : higher) {
            if (chain.length() > 0) {
                chain.append(" → ");
            }
            chain.append(x.tag.replaceFirst("(?i)^v", ""));
            if (x == d.target && d.forced) {
                chain.append("（现在这一步）");
            }
        }
        d.chainText = chain.toString();
        return d;
    }

    /** 该 release 的策略此刻是否生效（窗口 / 生效时间 / 灰度）。 */
    static boolean active(Policy p, long now, int bucket) {
        if (p == null) {
            return false;
        }
        if (p.windowFrom > 0 && (now < p.windowFrom || now > p.windowTo)) {
            return false;
        }
        if (p.activateAt > 0 && now < p.activateAt) {
            return false;
        }
        return rolloutHit(p, bucket);
    }

    private static String joinRule(String a, String b) {
        return (a == null || a.isEmpty()) ? b : (a + " + " + b);
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static boolean hitLogAny(List<Rel> higher, int cur, int api, int major,
                                     Policy fallback, Map<String, String> vars) {
        for (Rel r : higher) {
            Policy p = parsePolicy(r, api, major, vars);
            for (Rule rule : p.versionRules) {
                if ("LOG".equals(rule.kind) && match(rule, cur)) {
                    return true;
                }
            }
            for (Rule rule : p.androidRules) {
                if ("LOG".equals(rule.kind) && matchAndroid(rule, api, major)) {
                    return true;
                }
            }
        }
        return fallback != null;
    }

    /** 是否有"提示更新"规则命中（不含强制）。 */
    static boolean hitNotify(Policy p, int cur, int api, int major) {
        for (Rule rule : p.versionRules) {
            if ("NOTIFY".equals(rule.kind) && match(rule, cur)) {
                return true;
            }
        }
        for (Rule rule : p.androidRules) {
            if ("NOTIFY".equals(rule.kind) && matchAndroid(rule, api, major)) {
                return true;
            }
        }
        return false;
    }

    /** 版本规则匹配。 */
    static boolean match(Rule rule, int cur) {
        if ("ALL".equals(rule.mode)) {
            return true;
        }
        if ("LIST".equals(rule.mode)) {
            for (int v : rule.values) {
                if (cur <= v) {
                    return true;
                }
            }
            return false;
        }
        if ("RANGE".equals(rule.mode)) {
            return cur >= rule.min && cur <= rule.max;
        }
        if ("EXCEPT".equals(rule.mode)) {
            return !rule.values.contains(cur);
        }
        return false;
    }

    /** 安卓规则匹配：值 >= 20 视为 API 级别，否则视为 Android 大版本；FORCE 语义为"该值及以下"。 */
    static boolean matchAndroid(Rule rule, int api, int major) {
        if ("ALL".equals(rule.mode)) {
            return true;
        }
        List<Integer> list = rule.values;
        if ("EXCEPT".equals(rule.mode)) {
            for (int v : list) {
                if (hitOne(v, api, major)) {
                    return false;
                }
            }
            return true;
        }
        if ("RANGE".equals(rule.mode)) {
            for (int v = rule.min; v <= rule.max; v++) {
                if (hitOne(v, api, major)) {
                    return true;
                }
            }
            return false;
        }
        for (int v : list) {
            if (hitOne(v, api, major)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hitOne(int v, int api, int major) {
        return v >= 20 ? api <= v : major <= v;
    }

    // ---------------------------------------------------------------- 解析

    /** 取 from 位置所在那一行的剩余部分，用于读同一行尾巴上的 when="…"。 */
    private static String restOfLine(String body, int from) {
        if (body == null || from < 0 || from >= body.length()) {
            return "";
        }
        int end = body.indexOf('\n', from);
        return end < 0 ? body.substring(from) : body.substring(from, end);
    }

    /** 从一行里抽 when="…" / if='…' / 条件: …；没有就返回空串。 */
    private static String whenOf(String line) {
        if (line == null || line.length() == 0) {
            return "";
        }
        Matcher m = RE_WHEN.matcher(line);
        if (!m.find()) {
            return "";
        }
        String s = m.group(1) != null ? m.group(1)
                : (m.group(2) != null ? m.group(2) : m.group(3));
        return s == null ? "" : s.trim();
    }

    /**
     * 规则条件求值。三种情况：
     * <ul>
     *   <li>没写条件 → 生效；</li>
     *   <li>还没算变量（离线读缓存那一趟）→ 生效，不拿条件卡人；</li>
     *   <li>表达式写错 → <b>不生效</b>，绝不因为一个坏条件把所有人强更。</li>
     * </ul>
     */
    private static boolean condOk(String when, Map<String, String> vars) {
        if (when == null || when.length() == 0 || vars == null) {
            return true;
        }
        try {
            return Expr.test(when, vars);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析一个 release 正文里的全部指令（不按条件过滤，给离线读缓存那趟用）。 */
    static Policy parsePolicy(Rel rel, int api, int major) {
        return parsePolicy(rel, api, major, null);
    }

    /**
     * 解析一个 release 正文里的全部指令。
     *
     * <p>{@code vars} 非空时，每条规则自己那行写的 {@code when="…"} 会在这里求值：
     * 不成立的规则<b>直接丢掉</b>，后面的 decide 根本看不到它 —— 于是
     * {@code 强更 全部 when="authorized"} 就只对白名单生效。
     *
     * <p>求值失败（表达式写错）按"不生效"处理，绝不因为一个坏条件把所有人强更。
     */
    static Policy parsePolicy(Rel rel, int api, int major, Map<String, String> vars) {
        Policy p = new Policy();
        if (rel == null) {
            return p;
        }
        String body = rel.body == null ? "" : rel.body;
        p.rawReleases = null;
        p.link = extractDownloadLink(body);

        Matcher m = RE_VERSION.matcher(body);
        while (m.find()) {
            Rule r = ruleOf(m.group(1), m.group(2));
            if (r != null) {
                r.when = whenOf(restOfLine(body, m.start()));
                if (condOk(r.when, vars)) {
                    p.versionRules.add(r);
                }
            }
        }
        m = RE_ANDROID.matcher(body);
        while (m.find()) {
            Rule r = ruleOf(m.group(2), m.group(3));
            if (r != null) {
                r.when = whenOf(restOfLine(body, m.start()));
                if (condOk(r.when, vars)) {
                    p.androidRules.add(r);
                }
            }
        }
        m = RE_OFFLINE.matcher(body);
        if (m.find()) {
            String v = m.group(2).toUpperCase();
            p.offlinePass = v.startsWith("放行") || v.startsWith("PASS");
        }
        m = RE_CACHE.matcher(body);
        if (m.find()) {
            try {
                p.cacheDays = Integer.parseInt(m.group(2));
            } catch (Throwable ignored) {
            }
        }
        m = RE_RETRY.matcher(body);
        if (m.find()) {
            try {
                p.retry = Math.max(1, Math.min(5, Integer.parseInt(m.group(2))));
            } catch (Throwable ignored) {
            }
        }
        m = RE_TIMEOUT.matcher(body);
        if (m.find()) {
            try {
                p.timeout = Math.max(5, Math.min(30, Integer.parseInt(m.group(2))));
            } catch (Throwable ignored) {
            }
        }
        m = RE_MIRROR.matcher(body);
        while (m.find()) {
            p.mirrors.add(m.group(2));
        }
        m = RE_DEADLINE.matcher(body);
        if (m.find()) {
            p.deadline = parseDate(m.group(2), true);
        }
        m = RE_WINDOW.matcher(body);
        if (m.find()) {
            p.windowFrom = parseDate(m.group(2), false);
            p.windowTo = parseDate(m.group(3), true);
        }
        m = RE_ACTIVATE.matcher(body);
        if (m.find()) {
            p.activateAt = parseDate(m.group(2), false);
        }
        m = RE_TITLE.matcher(body);
        if (m.find()) {
            p.title = m.group(2).trim();
        }
        m = RE_DESC.matcher(body);
        if (m.find()) {
            p.desc = m.group(2).trim();
        }
        m = RE_BUTTON.matcher(body);
        if (m.find()) {
            p.button = m.group(2).trim();
        }
        m = RE_NOTICE.matcher(body);
        if (m.find()) {
            p.notice = m.group(2).trim();
        }
        p.urgent = RE_URGENT.matcher(body).find();
        m = RE_COUNTDOWN.matcher(body);
        if (m.find()) {
            try {
                p.countdown = Math.max(0, Math.min(120, Integer.parseInt(m.group(2))));
            } catch (Throwable ignored) {
            }
        }
        p.onceADay = RE_ONCE_A_DAY.matcher(body).find();
        p.repeatOff = RE_REPEAT_OFF.matcher(body).find();
        m = RE_BACKUP_LINK.matcher(body);
        if (m.find()) {
            p.backupLink = cleanUrl(m.group(2));
        }
        m = RE_CODE.matcher(body);
        if (m.find()) {
            p.code = m.group(2).trim();
        }
        m = RE_SHA.matcher(body);
        if (m.find()) {
            p.sha = m.group(2).trim();
        }
        m = RE_SIZE.matcher(body);
        if (m.find()) {
            p.size = m.group(2).trim();
        }
        m = RE_ROLLOUT.matcher(body);
        if (m.find()) {
            try {
                p.rolloutSet = true;
                p.rolloutExcept = m.group(2) != null && m.group(2).length() > 0;
                p.rolloutPercent = Math.max(0, Math.min(100, Integer.parseInt(m.group(3))));
            } catch (Throwable ignored) {
            }
        }
        return p;
    }

    /** 关键字 -> 规则种类。 */
    private static Rule ruleOf(String kw, String argRaw) {
        if (kw == null || argRaw == null) {
            return null;
        }
        String k = kw.trim().toUpperCase(Locale.ROOT);
        String kind;
        if (k.startsWith("强更") || k.equals("FORCE")) {
            kind = "FORCE";
        } else if (k.startsWith("提示更新") || k.startsWith("更新") || k.equals("UPDATE") || k.equals("NOTIFY")) {
            kind = "NOTIFY";
        } else if (k.startsWith("日志") || k.equals("CHANGELOG")) {
            kind = "LOG";
        } else if (k.startsWith("忽略") || k.startsWith("免检") || k.equals("SKIP")) {
            kind = "SKIP";
        } else if (k.startsWith("最低") || k.equals("MIN")) {
            kind = "MIN";
        } else if (k.startsWith("停止") || k.equals("EOL")) {
            kind = "EOL";
        } else if (k.startsWith("强制") || k.equals("FORCE")) {
            kind = "FORCE";
        } else if (k.startsWith("跳过") || k.equals("SKIP")) {
            kind = "SKIP";
        } else if (k.startsWith("提示") || k.equals("NOTIFY")) {
            kind = "NOTIFY";
        } else {
            return null;
        }

        Rule r = new Rule();
        r.kind = kind;
        String arg = argRaw.trim();
        String upper = arg.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ALL") || arg.startsWith("全部")) {
            r.mode = "ALL";
            r.text = "全部";
            return r;
        }
        boolean except = upper.startsWith("EXCEPT") || arg.startsWith("排除");
        String nums = except ? arg.replaceFirst("(?i)^(EXCEPT|排除)", "").trim() : arg;
        List<Integer> list = parseVersions(nums);
        if (list.isEmpty()) {
            // 无参数：按"全部"理解（例如 忽略 全部 之外的写法容错）
            r.mode = "ALL";
            r.text = "全部";
            return r;
        }
        if (except) {
            r.mode = "EXCEPT";
            r.values = list;
            r.text = "排除 " + nums;
            return r;
        }
        if (nums.contains("-") && list.size() > 1 && !nums.contains(",")) {
            r.mode = "RANGE";
            r.min = Math.min(list.get(0), list.get(list.size() - 1));
            r.max = Math.max(list.get(0), list.get(list.size() - 1));
            r.text = r.min + "-" + r.max;
            return r;
        }
        r.mode = "LIST";
        r.values = list;
        r.text = nums;
        return r;
    }

    /** 某个下标所在的那一行是否是"备用链接"指令（备用链接不能当主下载地址）。 */
    private static boolean isBackupLine(String body, int index) {
        int s = body.lastIndexOf('\n', Math.max(0, index - 1));
        int e = body.indexOf('\n', index);
        String line = body.substring(s < 0 ? 0 : s + 1, e < 0 ? body.length() : e);
        return RE_BACKUP_LINK.matcher(line.trim()).find();
    }

    static String extractDownloadLink(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher m = RE_MAIN_LINK.matcher(body);
        while (m.find()) {
            String u = cleanUrl(m.group(1));
            if (u.contains("github.com/151shi23/AshenFlameFoundry")) {
                continue;   // 仓库自身链接只作兜底入口
            }
            if (isBackupLine(body, m.start())) {
                continue;   // 备用链接单独解析
            }
            return u;
        }
        m = RE_ANY_LINK.matcher(body);
        while (m.find()) {
            String u = cleanUrl(m.group(1));
            if (!u.contains("github.com/151shi23/AshenFlameFoundry")) {
                return u;
            }
        }
        return null;
    }

    private static String cleanUrl(String u) {
        return u == null ? null : u.replaceAll("[\\s，。；、）)】\\]]+$", "");
    }

    /** 3.20 / 3.20.0 / v3.20 -> 320（主版本 *100 + 次版本）。 */
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
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                for (int v = lo; v <= hi && out.size() <= 200; v++) {
                    out.add(v);
                }
            } else if (p.matches(".*\\d.*")) {
                out.add(code(p));
            }
        }
        return out;
    }

    /** yyyy-MM-dd -> 毫秒；endOfDay=true 时取当天 23:59:59。 */
    static long parseDate(String s, boolean endOfDay) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Date d = f.parse(s.trim());
            if (d == null) {
                return 0;
            }
            long t = d.getTime();
            return endOfDay ? t + 86399999L : t;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Android 大版本（12 / 13 …）：优先用 RELEASE 字符串，其次按 API 映射。 */
    static int androidMajorOf(int api, String release) {
        try {
            if (release != null) {
                String[] p = release.trim().split("\\.");
                int v = Integer.parseInt(p[0].trim());
                if (v >= 4 && v <= 30) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        int[][] map = {{26, 8}, {28, 9}, {29, 10}, {30, 11}, {31, 12}, {33, 13}, {34, 14}, {35, 15}, {36, 16}};
        int major = 8;
        for (int[] pair : map) {
            if (api >= pair[0]) {
                major = pair[1];
            }
        }
        return major;
    }

    /** 设备稳定分桶（0-99），用于灰度。 */
    /**
     * 旧的灰度分桶：只吃 ANDROID_ID。现在只作 {@link #bucketNow()} 的兜底 ——
     * 正常路径走配置那套 {@code roll}，两套哈希不同会导致 A/B 人群对不上。
     */
    static int rolloutBucket(Context ctx) {
        String id;
        try {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable t) {
            id = null;
        }
        if (id == null || id.isEmpty()) {
            id = Build.FINGERPRINT + Build.MODEL;
        }
        int h = 0;
        for (int i = 0; i < id.length(); i++) {
            h = h * 31 + id.charAt(i);
        }
        return Math.abs(h) % 100;
    }

    /** 灰度命中判断（在 UI 线程用真实设备分桶再判一次）。 */
    static boolean rolloutHit(Policy p, int bucket) {
        if (p == null || !p.rolloutSet) {
            return true;
        }
        boolean in = bucket < p.rolloutPercent;
        return p.rolloutExcept ? !in : in;
    }
}
