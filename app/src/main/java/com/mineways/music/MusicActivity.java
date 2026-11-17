package com.mineways.music;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐页：MSCD 的静态站 + 原生播放桥。
 *
 * <p>网页只做「浏览器」职责（搜索、歌单、队列、歌词），出声与锁屏控制在
 * {@link MusicService}。所有失败都在页面上可见：底部的状态条 + 可复制的诊断。
 */
public class MusicActivity extends AppCompatActivity {

    private static final String ENTRY = "https://appassets.androidplatform.net/assets/music/index.html";
    private static final int REQ_SCRIPT = 0x4D31;
    private static final int REQ_NOTIF = 0x4D32;

    private static WeakReference<MusicActivity> sLive;

    private WebView web;
    private TextView status;
    private TextView diag;
    private TextView sourceProbe;
    private TextView srcChip;
    private LinearLayout errBar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String lastReport = "";
    private long readyAt;
    private boolean pausedByPolicy;

    private LinearLayout webHost;
    private boolean rendererDead;

    private final MusicCtl.Remote remote = new MusicCtl.Remote() {
        @Override
        public void action(String name) {
            eval("window.MWRemote && MWRemote.action(" + quote(name) + ")");
        }

        @Override
        public void seek(double seconds) {
            eval("window.MWRemote && MWRemote.seek(" + seconds + ")");
        }
    };

    private final MusicCtl.Listener stateListener = this::renderState;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MusicSources.invalidateGateCache();
        // 把「网页上手选的平台」读进内存，server() 那个无参方法才看得到
        MusicSources.loadPicks(this);
        // 云控下发的音源脚本（music_js_url）：异步装，不挡界面
        syncCloudScriptsAsync();
        sLive = new WeakReference<>(this);
        setContentView(buildRoot());

        MusicCtl.addListener(stateListener);
        MusicCtl.bindRemote(remote);
        renderState();
        // 一进来就后台探一遍音源，否则状态行与诊断永远是「未探活」，用户没法判断到底哪条活着
        MusicSources.probeAllAsync(this, false, this::afterProbe);

        // 系统悬浮条只在离开 App 后出现（MinewaysApp 的生命周期回调负责挂），
        // 进音乐页时不再启动它 —— 否则会和应用内那条叠在一起。
        requestNotificationPermission();
    }

    /**
     * 把云控 `music_js_url` 里的脚本装到本地。
     *
     * <p>异步做：下载要走网络，几百 KB 卡在主线程会 ANR。
     * 装完重载一次网页，让 MWSources 拿到新脚本。
     */
    private void syncCloudScriptsAsync() {
        new Thread(() -> {
            try {
                final int n = MusicSources.syncCloudScripts(MusicActivity.this);
                if (n > 0) {
                    runOnUiThread(() -> {
                        try {
                            if (web != null) {
                                web.reload();
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }, "music-scripts").start();
    }

    @Override
    protected void onDestroy() {
        MusicCtl.removeListener(stateListener);
        MusicCtl.unbindRemote(remote);
        if (sLive != null && sLive.get() == this) {
            sLive = null;
        }
        if (web != null) {
            web.removeJavascriptInterface("MWMusic");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 云控不让「系统外播放」时，离开 App 就停 —— 这是这一档唯一能落实的位置
        if (!isChangingConfigurations() && MusicCtl.playing()
                && !MusicSources.backgroundAllowed(this)) {
            MusicCtl.pause(this);
            pausedByPolicy = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedByPolicy) {
            pausedByPolicy = false;
            String tip = MusicSources.denyTip(this, true);
            toast(tip.length() == 0 ? "已按云控暂停" : tip + " · 已暂停");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (askBarBeforeLeave()) {
            return;
        }
        super.onBackPressed();
    }

    /**
     * 离开音乐页时问一句「还在软件里放吗」。答过之后不再骚扰（想改在「⋯」里翻）。
     *
     * <p>只在「还没答过 + 真的在放 + 这一档云控没关 + 系统悬浮条没开着」时才弹，判定全在
     * {@link MusicBarPolicy}。三个答案都算数：继续放并挂悬浮条 / 只留声音走通知栏 / 直接停。
     * 关掉对话框等于这次没答，下次再问 —— 不替用户默认。
     */
    private boolean askBarBeforeLeave() {
        if (!MusicCtl.active()) {
            return false;
        }
        if (!MusicSources.barPolicy(this).ask) {
            return false;
        }
        new AlertDialog.Builder(this)
                .setTitle("离开音乐页，还在软件里放吗？")
                .setMessage("继续放的话，每个页面都会悬浮一条液态玻璃播放条：能拖动、能折成小圆、能切歌暂停。\n\n"
                        + "只想听声音、不要这条 → 选「只放声音」，声音照走通知栏。\n"
                        + "想在别的软件上也听 → 回「⋯ → 开启系统悬浮条」。")
                .setPositiveButton("继续放（带悬浮条）", (d, w) -> {
                    MusicSources.setBarConsent(this, MusicBarPolicy.afterAnswer(true));
                    finish();
                })
                .setNeutralButton("只放声音", (d, w) -> {
                    MusicSources.setBarConsent(this, MusicBarPolicy.afterAnswer(false));
                    finish();
                })
                .setNegativeButton("停止播放", (d, w) -> {
                    MusicSources.setBarConsent(this, MusicBarPolicy.OFF);
                    MusicCtl.stop(this);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
        return true;
    }

    // ------------------------------------------------------------------ 界面

    private View buildRoot() {
        // 云控按机型/包名判定「软件内播放」不允许时，页面不加载，只把自定义提示语摊开
        if (!MusicSources.inAppAllowed(this)) {
            MusicAccess.Result acc = MusicSources.access(this);
            return deniedView(acc.inApp.tip, acc.inApp.reason);
        }
        // 上下排布：顶栏在网页**之上**，不再压在网页上（之前用 FrameLayout 叠加，
        // 40dp 的栏正好盖住 Mscd 自己的顶栏 —— 用户看到的就是「被遮挡」）。
        web = new WebView(this);
        setupWeb();

        // 顶栏：细到只占一条，玻璃感交给半透明 + 分隔线，不抢网页自己的头部
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        bar.setBackgroundColor(0xF20A0A0C);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), 0, dp(12), dp(2));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(40)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("音乐");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        titles.addView(title);
        status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(10);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(status);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 换源入口：只在云控下发的那几条里改「先用哪一条」，不新增音源、不改集合
        srcChip = new TextView(this);
        srcChip.setTextSize(11);
        srcChip.setTextColor(Color.WHITE);
        srcChip.setGravity(Gravity.CENTER);
        srcChip.setPadding(dp(9), 0, dp(9), 0);
        srcChip.setSingleLine(true);
        srcChip.setMaxWidth(dp(104));
        srcChip.setOnClickListener(v -> showRootPicker());
        bar.addView(srcChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        renderSourceChip();

        TextView menu = new TextView(this);
        menu.setText("⋯");
        menu.setTextSize(22);
        menu.setTextColor(Color.WHITE);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(40)));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webHost = column;
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 诊断条：只出问题才出现，点一下复制整条链路（不挡网页，因为只在出错时占底部一条）
        errBar = new LinearLayout(this);
        errBar.setOrientation(LinearLayout.HORIZONTAL);
        errBar.setGravity(Gravity.CENTER_VERTICAL);
        errBar.setBackgroundColor(0xF21A0606);

        diag = new TextView(this);
        diag.setText("");
        diag.setTextColor(0xFFFF8A80);
        diag.setTextSize(10);
        diag.setPadding(dp(14), dp(8), dp(8), dp(8));
        diag.setOnClickListener(v -> copyDiagnostics());
        errBar.addView(diag, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextColor(0xFFFFFFFF);
        retry.setTextSize(11);
        retry.setPadding(dp(12), dp(8), dp(14), dp(8));
        retry.setOnClickListener(v -> reloadPage());
        errBar.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        errBar.setVisibility(View.GONE);

        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.BOTTOM;
        root.addView(errBar, dlp);
        return root;
    }

    /** 云控判定不支持的落地页：文案由云端给，没给就用默认；原因单独一行小字，便于排查。 */
    private View deniedView(String tip, String reason) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(this);
        t.setText(tip == null || tip.length() == 0 ? "这台设备暂不支持音乐播放" : tip);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView why = new TextView(this);
        why.setText(reason);
        why.setTextColor(0x77FFFFFF);
        why.setTextSize(11);
        why.setGravity(Gravity.CENTER);
        why.setPadding(0, dp(10), 0, 0);
        box.addView(why);

        TextView back = new TextView(this);
        back.setText("返回");
        back.setTextColor(Color.BLACK);
        back.setTextSize(14);
        back.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.WHITE);
        back.setBackground(bg);
        back.setPadding(dp(28), dp(10), dp(28), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        back.setOnClickListener(v -> finish());
        box.addView(back, blp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(box, lp);
        return root;
    }

    private void renderState() {
        if (status == null) {
            return;
        }        String where = MusicCtl.preparing() ? "缓冲中"
                : (MusicCtl.playing() ? "播放中" : (MusicCtl.active() ? "已暂停" : "未播放"));
        String t = MusicCtl.title();
        String line = where + (t.length() > 0 ? " · " + t : "");
        if (MusicCtl.error().length() > 0) {
            line = "错误：" + MusicCtl.error();
        }
        status.setText(line);
        if (PlaybackStateCompatLike.isError(MusicCtl.error())) {
            showError("播放：" + MusicCtl.error());
        }
    }

    /** 页面/播放出错时底部出现一条可复制的说明 + 重试；正常状态自动收起。 */
    private void showError(String msg) {
        lastReport = msg;
        ui.post(() -> {
            if (diag == null || errBar == null) {
                return;
            }
            diag.setText("⚠ " + msg + "（点我复制诊断）");
            errBar.setVisibility(View.VISIBLE);
        });
    }

    private void clearError() {
        ui.post(() -> {
            if (errBar != null) {
                errBar.setVisibility(View.GONE);
            }
        });
    }

    /**
     * 探活跑完：只要有一条活就不打扰用户；全军覆没才把这条挂到底部诊断条（点一下能复制整条链路）。
     * 选了 JS 脚本时音源不生效，不报。
     */
    private void afterProbe() {
        ui.post(() -> {
            MusicProfile.Result r = MusicSources.effective(this);
            if (r.scriptId.length() > 0 || r.roots.isEmpty()) {
                renderSourceProbe(r);
                return;
            }
            int ok = 0, known = 0;
            for (MusicProfile.Root root : r.roots) {
                MusicSources.Probe p = MusicSources.cachedProbe(this, root);
                if (p != null) {
                    known++;
                    if (p.ok) {
                        ok++;
                    }
                }
            }
            // 逐条回调会多次进这里：只有「每条都探过了且一条都不活」才报，别在半路上闪红条
            if (known == r.roots.size() && ok == 0) {
                showError("音源全部不可用（" + MusicSources.probeSummary(this, r.roots)
                        + " · " + r.roots.size() + " 条），可在「⋯ → 设置音源地址」里换");
            }
            renderSourceProbe(r);
        });
    }

    /** 只为了让诊断文案有个判断处，别把 Build 版本判断散在 renderState 里。 */
    static final class PlaybackStateCompatLike {
        static boolean isError(String err) {
            return err != null && err.length() > 0;
        }
    }

    // ------------------------------------------------------------------ WebView

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MinewaysMusic/1.0");
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/userdata/", new DirHandler(new File(getFilesDir(), MusicSources.SCRIPT_DIR)))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                com.mineways.AdaptInject.apply(MusicActivity.this, view, url);
                ui.postDelayed(MusicActivity.this::syncFromNative, 400);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                showError("页面加载失败：" + (error == null ? "" : error.getDescription())
                        + "（" + (error == null ? 0 : error.getErrorCode()) + "）");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                showError("页面返回 HTTP " + (errorResponse == null ? "?" : errorResponse.getStatusCode()));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 返回 true 表示我们自己处理，不然整个进程会被系统杀掉
                rendererDead = true;
                showError("网页进程崩溃（didCrash="
                        + (detail != null && detail.didCrash()) + "），点「重试」重建页面");
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) {
                    return false;
                }
                String scheme = u.getScheme();
                if ("https".equals(scheme) && u.getHost() != null
                        && u.getHost().contains("appassets.androidplatform.net")) {
                    return false;                       // 站内
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Throwable ignored) {
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "MWMusic");
        web.loadUrl(ENTRY);
    }

    /** 网页刚打开时，如果原生已经在放，把状态推回去，界面不至于显示「暂停」。 */
    private void syncFromNative() {
        eval("window.MWNativeAudio && MWNativeAudio.dispatch('"
                + (MusicCtl.playing() ? "playing" : "paused") + "',{currentTime:"
                + (MusicCtl.positionMs() / 1000.0) + ",duration:" + (MusicCtl.durationMs() / 1000.0) + "})");
    }

    void eval(String js) {
        WebView w = web;
        if (w == null) {
            return;
        }
        ui.post(() -> {
            try {
                w.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    static void dispatchToPage(String type, double positionSec, double durationSec, String message) {
        WeakReference<MusicActivity> ref = sLive;
        MusicActivity a = ref == null ? null : ref.get();
        if (a == null) {
            return;                                   // 页面不在：状态仍在 MusicCtl，回来再同步
        }
        StringBuilder js = new StringBuilder("window.MWNativeAudio && MWNativeAudio.dispatch(")
                .append(quote(type)).append(",{currentTime:").append(positionSec)
                .append(",duration:").append(durationSec);
        if (message != null && message.length() > 0) {
            js.append(",message:").append(quote(message));
        }
        js.append("})");
        a.eval(js.toString());
    }

    // ------------------------------------------------------------------- 菜单

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "设置音源地址");
        pm.getMenu().add(0, 2, 1, "导入音源脚本（.js）");
        pm.getMenu().add(0, 3, 2, "下载当前歌曲");
        pm.getMenu().add(0, 4, 3, MusicSources.overlayOn(this)
                ? "退出后系统悬浮条：已开" : "退出后系统悬浮条：已关");
        pm.getMenu().add(0, 5, 4, "重新加载页面");
        pm.getMenu().add(0, 6, 5, "复制诊断信息");
        pm.getMenu().add(0, 7, 6, barMenuTitle());
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showSourceDialog();
                    return true;
                case 2:
                    pickScript();
                    return true;
                case 3:
                    MusicDownloader.downloadCurrent(this, (ok, msg) -> toast(msg));
                    return true;
                case 4:
                    toggleOverlay();
                    return true;
                case 5:
                    if (web != null) {
                        web.loadUrl(ENTRY);
                    }
                    return true;
                case 6:
                    copyDiagnostics();
                    return true;
                case 7:
                    flipBarConsent();
                    return true;
                default:
                    return false;
            }
        });
        pm.show();
    }

    /** 悬浮条这一档现在是什么状态，就写成菜单标题。 */
    private String barMenuTitle() {
        String c = MusicSources.barConsent(this);
        return "软件内悬浮条：" + (MusicBarPolicy.ON.equals(c) ? "已开"
                : MusicBarPolicy.OFF.equals(c) ? "已关" : "还没问过我");
    }

    /** 退出时那次询问的答案在这里翻回来：开 → 关 → 开；「还没问过」点一下就当同意。 */
    private void flipBarConsent() {
        String c = MusicSources.barConsent(this);
        String next = MusicBarPolicy.ON.equals(c) ? MusicBarPolicy.OFF : MusicBarPolicy.ON;
        MusicSources.setBarConsent(this, next);
        MusicBarPolicy p = MusicSources.barPolicy(this);
        toast("软件内悬浮条：" + (MusicBarPolicy.ON.equals(next) ? "已开" : "已关")
                + "（" + p + "）");
    }

    private void showSourceDialog() {
        MusicProfile.Result r = MusicSources.effective(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, dp(4));

        TextView hint = new TextView(this);
        hint.setText("每行一个 Meting 兼容接口，可写「名字=https://…#netease」。\n"
                + "#后面是平台（netease/tencent/kugou/kuwo/migu），不写就跟着云控的 music_server。\n"
                + "云控下发了音源时默认按云控那几条轮换；这里填的会进顶栏「换源」列表，"
                + "选它才优先用，也可以在那里直接删掉。\n"
                + "只接受 https，指向内网的会被拒绝。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF666666);
        box.addView(hint);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setHint("https://example.com/meting/");
        input.setText(TextUtils.join("\n", MusicSources.userLines(this)));
        box.addView(input);

        sourceProbe = new TextView(this);
        sourceProbe.setTextSize(12);
        sourceProbe.setPadding(0, dp(8), 0, 0);
        box.addView(sourceProbe);
        renderSourceProbe(r);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("音源")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    List<String> lines = new ArrayList<>();
                    for (String line : input.getText().toString().split("\n")) {
                        if (line.trim().length() > 0) {
                            lines.add(line.trim());
                        }
                    }
                    List<String> bad = new ArrayList<>();
                    for (String line : lines) {
                        MusicProfile.Root root = MusicProfile.parseRootLine(line);
                        if (root == null || !MusicProfile.isSafeUrl(root.url)) {
                            bad.add(line);
                        }
                    }
                    if (!bad.isEmpty()) {
                        toast("这些地址不合法，已拦下：" + bad.get(0));
                    }
                    List<String> good = new ArrayList<>(lines);
                    good.removeAll(bad);
                    MusicSources.setUserLines(this, good);
                    MusicProfile.Result now = MusicSources.effective(this);
                    if (good.isEmpty()) {
                        toast("已清空用户音源");
                    } else if (now.cloudWins) {
                        toast("已保存 " + good.size() + " 条；默认还是走云控那 " + now.roots.size()
                                + " 条，要用自己这条就在顶栏「换源」里选它");
                    } else {
                        toast("已保存 " + good.size() + " 个音源");
                    }
                    reloadPage();
                    MusicSources.probeAllAsync(this, true, this::afterProbe);
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("探活", null)
                .create();
        dlg.setOnDismissListener(d -> sourceProbe = null);
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    // 探的是输入框里这几行（还没保存也算），这样用户能先验证再决定保不保存
                    List<MusicProfile.Root> pend = new ArrayList<>();
                    for (String line : input.getText().toString().split("\n")) {
                        if (line.trim().length() == 0) {
                            continue;
                        }
                        MusicProfile.Root root = MusicProfile.parseRootLine(line);
                        if (root != null && MusicProfile.isSafeUrl(root.url)) {
                            pend.add(root);
                        }
                    }
                    if (pend.isEmpty()) {
                        if (sourceProbe != null) {
                            sourceProbe.setText("没有合法的 https 地址可探活");
                        }
                        return;
                    }
                    if (sourceProbe != null) {
                        sourceProbe.setText("正在探活 " + pend.size() + " 条 …");
                    }
                    MusicSources.probeAsync(this, pend, () -> ui.post(() -> {
                        if (sourceProbe == null) {
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        for (MusicProfile.Root root : pend) {
                            MusicSources.Probe p = MusicSources.cachedProbe(this, root);
                            sb.append("· ").append(MusicSources.label(root)).append(" → ")
                                    .append(p == null ? "未探活" : p.text()).append('\n');
                        }
                        sourceProbe.setText(sb.toString());
                    }));
                }));
        dlg.show();
    }

    /** 逐条摊开「名字[平台] + 地址 + 探活结果」；三条云控源同址不同平台，只有平台能分开它们。 */
    private void renderSourceProbe(MusicProfile.Result r) {
        TextView tv = sourceProbe;
        if (tv == null) {
            return;
        }
        if (r.roots.isEmpty()) {
            tv.setText(r.scriptId.length() > 0 ? "当前由音源脚本接管：" + r.scriptName : "当前没有可用音源");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("生效 ").append(r.roots.size()).append(" 条（")
                .append(MusicSources.probeSummary(this, r.roots)).append("）\n");
        for (MusicProfile.Root root : r.roots) {
            MusicSources.Probe p = MusicSources.cachedProbe(this, root);
            sb.append("· ").append(MusicSources.label(root)).append('\n')
                    .append("  ").append(root.url).append(" → ")
                    .append(p == null ? "未探活" : p.text()).append('\n');
        }
        for (String problem : r.problems) {
            sb.append("！").append(problem).append('\n');
        }
        tv.setText(sb.toString());
    }

    /** 顶栏那颗换源按钮的字：手选过就写那条，没选过就写云控给的第一条。 */
    private void renderSourceChip() {
        if (srcChip == null) {
            return;
        }
        String pick = MusicSources.rootPick(this);
        String head = pick;
        if (head.length() == 0) {
            MusicProfile.Result r = MusicSources.effective(this);
            head = r.roots.isEmpty() ? "" : r.roots.get(0).name;
        }
        srcChip.setText((head.length() == 0 ? "音源" : head) + " ▾");
    }

    /**
     * 换源面板：**云控没下架的那几条**（`music_api` 撤掉的自然就不在这里了）＋ **本机导入的那几条**
     * （可以当场删）。手选本机那条是用户主动动作，允许把它插到队首，云控那几条排在后面继续轮换；
     * 不选就完全是云控给的顺序 —— 面板本身不新增音源，加源只能去「设置音源地址」。
     */
    private void showRootPicker() {
        MusicProfile.Result r = MusicSources.effective(this);
        if (r.roots.isEmpty() && r.userOnly.isEmpty()) {
            toast(r.scriptId.length() > 0 ? "现在由音源脚本接管：" + r.scriptName
                    : "云控还没下发音源，「设置音源地址」里可以自己加");
            return;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(4), pad, 0);

        box.addView(pickerGroup("云控下发 · 没下架的就这几条"));
        for (MusicProfile.Root root : r.roots) {
            box.addView(pickerRow(root, false, holder));
        }
        if (!r.userOnly.isEmpty()) {
            box.addView(pickerGroup("本机导入 · 选了才优先用，可删除"));
            for (MusicProfile.Root root : r.userOnly) {
                box.addView(pickerRow(root, true, holder));
            }
        }
        TextView reset = new TextView(this);
        String pick = MusicSources.rootPick(this);
        reset.setText(pick.length() == 0 ? "● 按云控给的顺序（当前）" : "○ 交回云控给的顺序");
        reset.setTextSize(13);
        reset.setTextColor(Color.WHITE);
        reset.setPadding(0, dp(12), 0, dp(12));
        reset.setOnClickListener(v -> applyPick("", holder[0]));
        box.addView(reset);

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("换源：先用哪一条")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
    }

    private TextView pickerGroup(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11);
        t.setTextColor(0x88FFFFFF);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private View pickerRow(MusicProfile.Root root, boolean mine, final AlertDialog[] holder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        MusicSources.Probe p = MusicSources.cachedProbe(this, root);
        String head = MusicSources.label(root);
        if (mine) {
            head = head + "（本机）";
        }
        if (head.equals(MusicSources.rootPick(this))) {
            head = "● " + head;
        } else {
            head = "○ " + head;
        }
        TextView col = new TextView(this);
        col.setTextSize(12);
        col.setTextColor(Color.WHITE);
        col.setText(head + "\n" + root.url + "\n" + (p == null ? "未探活" : p.text()));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final String name = root.name;
        col.setOnClickListener(v -> applyPick(name, holder[0]));

        if (mine) {
            TextView del = new TextView(this);
            del.setText("删");
            del.setTextSize(12);
            del.setTextColor(0xFFFF8A80);
            del.setPadding(dp(14), dp(8), dp(6), dp(8));
            del.setOnClickListener(v -> {
                int gone = MusicSources.removeUserRoot(this, name);
                toast(gone == 0 ? "这条已经不在了" : "已删掉本机那条：" + name);
                if (holder[0] != null) {
                    try {
                        holder[0].dismiss();
                    } catch (Throwable ignored) {
                    }
                }
                renderSourceChip();
                reloadPage();
                showRootPicker();
            });
            row.addView(del, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /** 记下手选 → 关面板 → 重载网页（下一条请求就走新首选）→ 顺手补一轮探活。 */
    private void applyPick(String name, AlertDialog dlg) {
        MusicSources.setRootPick(this, name);
        if (dlg != null) {
            try {
                dlg.dismiss();
            } catch (Throwable ignored) {
            }
        }
        renderSourceChip();
        reloadPage();
        MusicSources.probeAllAsync(this, false, this::afterProbe);
        toast(name.length() == 0 ? "已交回云控给的顺序" : "先用 " + name);
    }

    private void pickScript() {
        if (!MusicSources.scriptsAllowed(this)) {
            toast("云控没有开放音源脚本（music_js 未开）");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_SCRIPT);
        } catch (Throwable t) {
            toast("打不开文件选择器：" + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCRIPT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String name = uri.getLastPathSegment();
        String err = MusicSources.importScript(this, uri, name);
        toast(err == null ? "音源脚本已导入并启用" : err);
        if (err == null) {
            reloadPage();
        }
    }

    private void toggleOverlay() {
        if (!MusicSources.backgroundAllowed(this)) {
            String tip = MusicSources.denyTip(this, true);
            toast(tip.length() == 0 ? "云控已关闭系统外播放" : tip);
            return;
        }
        if (MusicSources.overlayOn(this)) {
            MusicSources.setOverlayOn(this, false);
            MusicOverlay.stop(this);
            toast("退出软件后不再显示系统悬浮条");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            toast("需要先允许「显示在其他应用上层」");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                toast("打不开授权页：" + t);
            }
            return;
        }
        MusicSources.setOverlayOn(this, true);
        toast("退出软件后会显示系统悬浮条");
    }

    private void reloadPage() {
        if (rendererDead) {
            rebuildWeb();
            return;
        }
        if (web != null) {
            web.loadUrl(ENTRY);
            clearError();
        }
    }

    /** 渲染进程崩了以后旧 WebView 已经废了，只能整个换一只再加载。 */
    private void rebuildWeb() {
        if (webHost == null) {
            return;
        }
        ui.post(() -> {
            try {
                if (web != null) {
                    webHost.removeView(web);
                    web.removeJavascriptInterface("MWMusic");
                    web.destroy();
                }
            } catch (Throwable ignored) {
            }
            web = new WebView(this);
            setupWeb();
            webHost.addView(web, 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            rendererDead = false;
            clearError();
        });
    }

    private void copyDiagnostics() {
        MusicProfile.Result r = MusicSources.effective(this);
        StringBuilder sb = new StringBuilder();
        sb.append("MinewaysMobile 音乐诊断 ").append(new java.util.Date()).append('\n');
        sb.append("云控：总闸=").append(MusicSources.gateOpen(this))
                .append(" 内=").append(MusicSources.inAppAllowed(this))
                .append(" 外=").append(MusicSources.backgroundAllowed(this))
                .append(" js=").append(MusicSources.scriptsAllowed(this))
                .append(" dl=").append(MusicSources.downloadAllowed(this))
                .append(" server=").append(MusicSources.server()).append('\n');
        // 「悬浮条怎么没出现」必须能从复制里答出来：用户答过什么、判定结论、被谁挡了
        sb.append("悬浮条：答案=").append(MusicSources.barConsent(this))
                .append(" 系统条=").append(MusicSources.overlayOn(this))
                .append(" 位置=").append(MusicSources.barX(this)).append(',').append(MusicSources.barY(this))
                .append(" 判定=").append(MusicSources.barPolicy(this)).append('\n');
        String pick = MusicSources.rootPick(this);
        sb.append("音源数：").append(r.roots.size()).append(" 用户 ").append(MusicSources.userLines(this).size())
                .append(" · 手选 ").append(pick.length() == 0 ? "无（按云控顺序）" : pick)
                .append(" · ").append(MusicSources.probeSummary(this, r.roots))
                .append(" · 轮换 ").append(r.rotate > 0
                        ? Math.min(r.rotate, r.roots.size()) : r.roots.size()).append(" 条").append('\n');
        for (MusicProfile.Root root : r.roots) {
            MusicSources.Probe p = MusicSources.cachedProbe(this, root);
            sb.append(" - ").append(MusicSources.label(root)).append(" ").append(root.url)
                    .append(" → ").append(p == null ? "未探活" : p.text()).append('\n');
        }
        // 云控写重复 / 点不到条目时的唯一线索来源，别只在关于页那行滚字里闪过
        for (String problem : r.problems) {
            sb.append(" ！").append(problem).append('\n');
        }
        sb.append("脚本：").append(r.scriptId.length() == 0 ? "未选" : r.scriptName)
                .append(r.scriptError.length() == 0 ? "" : " / " + r.scriptError).append('\n');
        sb.append("播放：").append(MusicCtl.stateJson()).append('\n');
        if (lastReport.length() > 0) {
            sb.append("最近上报：").append(lastReport).append('\n');
        }
        sb.append("页面 ready=").append(readyAt > 0).append(" err=").append(MusicCtl.error()).append('\n');
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("music-diag", sb.toString()));
                toast("诊断已复制，发给开发者");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } catch (Throwable ignored) {
            }
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ JS 桥

    private final class Bridge {

        @JavascriptInterface
        public String configJson() {
            return MusicSources.configJson(MusicActivity.this);
        }

        @JavascriptInterface
        public void play(String url, String metaJson) {
            try {
                JSONObject meta = metaJson == null || metaJson.length() == 0
                        ? new JSONObject() : new JSONObject(metaJson);
                MusicCtl.play(MusicActivity.this, url, meta);
            } catch (Throwable t) {
                report("play", String.valueOf(t));
            }
        }

        @JavascriptInterface
        public void pause() {
            MusicCtl.pause(MusicActivity.this);
        }

        @JavascriptInterface
        public void resume() {
            MusicCtl.resume(MusicActivity.this);
        }

        @JavascriptInterface
        public void seek(int ms) {
            MusicCtl.seekTo(MusicActivity.this, ms);
        }

        @JavascriptInterface
        public void setVolume(float v) {
            MusicCtl.setVolume(MusicActivity.this, v);
        }

        @JavascriptInterface
        public void meta(String json) {
            try {
                MusicCtl.pageMeta(new JSONObject(json));
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void playState(String state) {
            MusicCtl.pageState(state);
        }

        /** 网页解析好的歌词：{@code 毫秒\t文本} 逐行，交给原生供悬浮胶囊与系统条显示。 */
        @JavascriptInterface
        public void lyric(String wire) {
            MusicCtl.setLyric(wire);
        }

        @JavascriptInterface
        public void cleared() {
            MusicCtl.pageMeta(new JSONObject());
        }

        @JavascriptInterface
        public String state() {
            return MusicCtl.stateJson();
        }

        @JavascriptInterface
        public void report(String step, String message) {
            showError(step + "：" + message);
        }

        @JavascriptInterface
        public void ready() {
            readyAt = System.currentTimeMillis();
            clearError();
            ui.post(MusicActivity.this::syncFromNative);
            // 云控可能在页面打开之后才落下来，ready 时再补一轮（十分钟内的缓存不会重复打网络）
            MusicSources.probeAllAsync(MusicActivity.this, false, MusicActivity.this::afterProbe);
        }

        @JavascriptInterface
        public boolean scriptsEnabled() {
            return MusicSources.scriptsAllowed(MusicActivity.this);
        }

        /**
         * 网页上切平台。空串 = 回到「跟随云控」。
         *
         * <p>以前只能在原生标题栏切，用户要求挪到网页里 —— 那边有列表、能显示每个源的状态，
         * 比在标题栏挤一行按钮好用。
         */
        @JavascriptInterface
        public void setServer(String name) {
            try {
                MusicSources.setServerPick(MusicActivity.this, name);
            } catch (Throwable t) {
                report("setServer", String.valueOf(t));
            }
        }

        /** 当前生效的平台（含用户手选），供网页回显。 */
        @JavascriptInterface
        public String currentServer() {
            try {
                return MusicSources.server();
            } catch (Throwable t) {
                return "netease";
            }
        }

        /** 网页上选脚本音源。空串 = 不用脚本，走 Meting。 */
        @JavascriptInterface
        public void setScript(String id) {
            try {
                MusicSources.setScriptId(MusicActivity.this, id == null ? "" : id.trim());
            } catch (Throwable t) {
                report("setScript", String.valueOf(t));
            }
        }

        /**
         * 已导入的脚本清单：[{id, name}]。
         *
         * <p>网页自己也能从 manifest.json 读到，但那份是异步加载的；
         * 这里同步给一份，界面一打开就能画出来，不用等脚本加载完。
         */
        @JavascriptInterface
        public String scriptsJson() {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String[] row : MusicSources.scripts(MusicActivity.this)) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", row[0]);
                    o.put("name", row[1]);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public boolean downloadAllowed() {
            return MusicSources.downloadAllowed(MusicActivity.this);
        }

        @JavascriptInterface
        public void openSourceSetup() {
            ui.post(MusicActivity.this::showSourceDialog);
        }

        @JavascriptInterface
        public void importScript() {
            ui.post(MusicActivity.this::pickScript);
        }

        @JavascriptInterface
        public void download() {
            ui.post(() -> MusicDownloader.downloadCurrent(MusicActivity.this,
                    (ok, msg) -> toast(msg)));
        }

        @JavascriptInterface
        public void toast(String msg) {
            MusicActivity.this.toast(msg);
        }
    }

    // ------------------------------------------------------------- 本地文件服务

    /** `/userdata/` → filesDir/music-sources；只放行目录内的普通文件，挡穿越。 */
    private static final class DirHandler implements WebViewAssetLoader.PathHandler {
        private final File root;

        DirHandler(File root) {
            this.root = root;
        }

        @Nullable
        @Override
        public WebResourceResponse handle(@NonNull String path) {
            try {
                String rel = path.startsWith("/") ? path.substring(1) : path;
                if (rel.contains("..") || rel.contains("\\")) {
                    return null;
                }
                File f = new File(root, Uri.decode(rel));
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(root.getCanonicalPath() + File.separator)) {
                    return null;
                }
                if (!f.isFile()) {
                    return null;
                }
                String mime = guessMime(f.getName());
                InputStream in = new FileInputStream(f);
                return new WebResourceResponse(mime, "utf-8", in);
            } catch (Throwable t) {
                return null;
            }
        }

        private static String guessMime(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".js")) {
                return "application/javascript";
            }
            if (n.endsWith(".json")) {
                return "application/json";
            }
            if (n.endsWith(".css")) {
                return "text/css";
            }
            if (n.endsWith(".html")) {
                return "text/html";
            }
            if (n.endsWith(".png")) {
                return "image/png";
            }
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (n.endsWith(".svg")) {
                return "image/svg+xml";
            }
            String g = URLConnection.guessContentTypeFromName(name);
            return g == null ? "application/octet-stream" : g;
        }
    }
}
