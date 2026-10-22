package com.mineways;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureCatalog;
import com.mineways.cloud.FeatureGate;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * 网页工具容器（兼容层）：
 * <ul>
 *   <li>站内跳转全部留在 App 内；点导出/下载 → 走系统下载器落到 {@code 下载/AFF/网页工具/}</li>
 *   <li>皮肤/图片上传走系统文件选择器（onShowFileChooser）</li>
 *   <li>Cookie / localStorage 保留登录态；注入深色偏好（prefers-color-scheme: dark）</li>
 *   <li>返回键 = 页内后退，到底再退出；标题栏可一键用外部浏览器打开</li>
 *   <li><b>就地汉化</b>：按云端下发的 {@code TR} 词条把页面上的英文换成中文（顶栏「汉化」按钮切换，
 *       按站点记忆）。词表全部来自配置文件，包里没有预置任何译文，也没有第三方翻译接口</li>
 * </ul>
 */
public class WebToolActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "tool_url";
    public static final String EXTRA_TITLE = "tool_title";

    private static final int REQ_FILE = 4001;
    private static final int BG = 0xFF0E0F11;
    private static final int LINE = 0x1FFFFFFF;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8A9099;

    private WebView web;
    private ProgressBar progress;
    private TextView titleView;
    private ValueCallback<Uri[]> fileCallback;
    private String homeUrl;
    private String toolTitle;

    // ---- 就地汉化 ----
    private TextView i18nButton;
    private String host = "";
    private boolean i18nOn;
    private int i18nReplaced;
    private int i18nMissed;
    /** 注入脚本全文，进程内读一次就够（同一份给所有站点用）。 */
    private static volatile String i18nScript;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        homeUrl = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        toolTitle = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE) : null;
        if (homeUrl == null || homeUrl.length() == 0) {
            finish();
            return;
        }
        setContentView(buildUi());
        host = FeatureCatalog.hostOf(homeUrl);
        i18nOn = i18nWanted();
        web.loadUrl(homeUrl);
    }

    /**
     * 这个站点该不该开汉化：用户手动切过就听他的（按站点记），没切过就问云端 ——
     * {@code SITE add … i18n=on|off|auto} 或 {@code FEAT id=… i18n=on}，都没写就是 auto（不开，
     * 因为这个特性靠词表，没词表的站开了也没东西可换）。
     */
    private boolean i18nWanted() {
        String saved = getSharedPreferences("web_i18n", MODE_PRIVATE).getString(host, null);
        if (saved != null) {
            return "on".equals(saved);
        }
        FeatureGate gate = CloudCatalog.gate();
        String mode = gate.i18nMode(null, host);
        // auto：只在这个站的专属词表**够厚**时才自动开 —— 只翻了三五个按钮的页面
        // 半中半英比整页英文更难看，词表没铺满之前先让顶栏按钮手动开。
        return FeatureCatalog.I18N_ON.equals(mode)
                || (FeatureCatalog.I18N_AUTO.equals(mode)
                && gate.siteEntryCount(host) >= FeatureCatalog.I18N_AUTO_MIN_ENTRIES);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        bar.setBackgroundColor(BG);

        titleView = new TextView(this);
        titleView.setText(toolTitle != null ? toolTitle : "网页工具");
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        i18nButton = barButton("汉化");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = dp(14);
        i18nButton.setLayoutParams(ilp);
        i18nButton.setOnClickListener(v -> {
            i18nOn = !i18nOn;
            getSharedPreferences("web_i18n", MODE_PRIVATE).edit()
                    .putString(host, i18nOn ? "on" : "off").apply();
            applyI18n(true);
        });
        bar.addView(i18nButton);

        TextView reload = barButton("刷新");
        reload.setOnClickListener(v -> {
            web.reload();
            applyI18n(true);
        });
        bar.addView(reload);

        TextView open = barButton("浏览器打开");
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(14);
        open.setLayoutParams(olp);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
            } catch (Throwable ignored) {
            }
        });
        bar.addView(open);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        FrameLayout holder = new FrameLayout(this);
        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " AshenFlameFoundry/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        // 只回传"换了几处"这种统计数字，页面里没有任何东西能靠它调本机能力
        web.addJavascriptInterface(new I18nBridge(), "AFF_I18N");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return false;   // 站内一切跳转都留在容器里
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectDarkHint(view);
                applyI18n(false);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    startActivityForResult(Intent.createChooser(pick, "选择文件"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        // 下载：交给系统下载器，落到 下载/AFF/网页工具/
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AFF/网页工具/" + name);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        toast("已开始下载到「下载/AFF/网页工具/」：" + name);
                    }
                } catch (Throwable t) {
                    toast("下载失败，可用「浏览器打开」再试");
                }
            }
        });

        holder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        progress.setLayoutParams(plp);
        holder.addView(progress);
        return root;
    }

    /** 告诉站点我们偏好深色（支持的站点会自己切深色主题）。 */
    private void injectDarkHint(WebView view) {
        try {
            view.evaluateJavascript(
                    "(function(){try{var s=document.createElement('meta');"
                            + "s.name='color-scheme';s.content='dark light';"
                            + "document.head.appendChild(s);"
                            + "document.documentElement.style.colorScheme='dark light';}catch(e){}})();", null);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 就地汉化

    /**
     * 开或关汉化。词表从云端门面取，包里没有预置译文；脚本只注入一次，
     * 之后靠 {@code feed} / {@code stop} 切换，避免同一页注入两遍。
     */
    private void applyI18n(boolean force) {
        if (web == null) {
            return;
        }
        Map<String, String> table = CloudCatalog.gate().translationsFor(host);
        if (!i18nOn) {
            evaluate("try{window.__affI18n&&__affI18n.stop();}catch(e){}");
            renderI18nButton(table.size(), false);
            return;
        }
        if (table.isEmpty()) {
            renderI18nButton(0, false);
            if (force) {
                toast("这个站还没有译文库：" + host + "\n在云端配置里加 TR add host=" + host
                        + " en=\"…\" zh=\"…\" 就能生效");
            }
            return;
        }
        String script = loadI18nScript();
        if (script == null) {
            renderI18nButton(0, false);
            if (force) {
                toast("汉化脚本读不出来，重新装包或到「关于」页刷新云端配置再试");
            }
            return;
        }
        evaluate("try{window.__affI18n&&__affI18n.stop();}catch(e){}");
        evaluate(script);
        // 整站逐句翻译会有几千上万条，一次 evaluateJavascript 既塞不下也不该塞：分块喂，
        // 首块重建表，后续块只并表不扫页面，最后一块喂完再 restart() 统一开跑。
        final int chunkSize = 400;
        java.util.Iterator<Map.Entry<String, String>> it = table.entrySet().iterator();
        int sent = 0;
        while (it.hasNext()) {
            StringBuilder sb = new StringBuilder(8192);
            for (int i = 0; i < chunkSize && it.hasNext(); i++) {
                Map.Entry<String, String> e = it.next();
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(JSONObject.quote(e.getKey())).append(':').append(JSONObject.quote(e.getValue()));
            }
            evaluate("try{__affI18n&&__affI18n.feed({" + sb + "}," + (sent == 0 ? "false" : "true") + ");}catch(e){}");
            sent++;
        }
        evaluate("try{__affI18n&&__affI18n.restart();}catch(e){}");
        renderI18nButton(table.size(), true);
    }

    private void evaluate(String js) {
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
            // 页面正在跳转时 evaluate 会失败，下一轮 onPageFinished 会再来一次
        }
    }

    /** 按钮上写清这次能换多少处；一个词都没有时不要装作可用。 */
    private void renderI18nButton(int tableSize, boolean on) {
        if (i18nButton == null) {
            return;
        }
        String text;
        if (tableSize == 0) {
            text = "无词库";
        } else if (on && i18nReplaced > 0) {
            text = "已换 " + i18nReplaced + " 处";
        } else {
            text = on ? "原文" : "汉化";
        }
        i18nButton.setText(text);
        i18nButton.setTextColor(on || tableSize > 0 ? 0xFFD9603A : 0xFF5C6268);
    }

    private String loadI18nScript() {
        String cached = i18nScript;
        if (cached != null) {
            return cached;
        }
        try {
            InputStream in = getAssets().open("i18n/site-i18n.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            cached = new String(bos.toByteArray(), Charset.forName("UTF-8"));
            i18nScript = cached;
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 页面 → App 的唯一一条通道：只回传统计数字。 */
    private class I18nBridge {

        @android.webkit.JavascriptInterface
        public void stats(final int replaced, final int missed) {
            i18nReplaced = replaced;
            i18nMissed = missed;
            try {
                runOnUiThread(() -> renderI18nButton(replaced > 0 ? i18nReplaced : 0, i18nOn));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    private TextView barButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(DIM);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setClickable(true);
        return tv;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
