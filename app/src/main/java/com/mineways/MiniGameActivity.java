package com.mineways;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 内置小游戏《寂零快跑》。
 * <p>
 * 游戏本体是一套纯静态 HTML5 页面（canvas + 9 个 JS 模块 + 41.7MB 素材），放在
 * {@code assets/minigame/}，由 {@link WebViewAssetLoader} 以
 * {@code https://appassets.androidplatform.net/assets/minigame/index.html} 加载。
 * <p>
 * 为什么不用 {@code file://}：走 HTTPS 虚拟源才有真实 origin，游戏依赖的
 * localStorage（存档，{@code jiling_} 前缀）与 IndexedDB（UGC 作品与自定义图片）
 * 才能正常工作；同时也避开了 WebView 对本地文件的同源限制。
 * 游戏没有任何外链依赖，纯离线可玩。
 * <p>
 * 本类只做容器：屏幕常亮、加载进度、返回键处理。
 * <ul>
 *   <li>返回键 / 工具栏返回：先给游戏发一次 {@code Escape}（游戏内 Escape = 暂停），
 *       再弹「退出游戏？」确认，避免误触丢局；</li>
 *   <li>切到后台：暂停 WebView 并同样发一次 Escape，让 BGM 停下来。</li>
 * </ul>
 */
public class MiniGameActivity extends AppCompatActivity {

    /** assets 内游戏入口（WebViewAssetLoader 拦截该域名）。 */
    private static final String ASSET_URL =
            "https://appassets.androidplatform.net/assets/minigame/index.html";

    /** 给游戏派发一次 Escape：游戏把 Escape/P 绑定为暂停。 */
    private static final String JS_PAUSE =
            "(function(){try{window.dispatchEvent(new KeyboardEvent('keydown',"
            + "{code:'Escape',key:'Escape',bubbles:true}));return true;}catch(e){return false;}})()";

    private WebView webView;
    private ProgressBar progressBar;
    private WebViewAssetLoader assetLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_minigame);

        webView = findViewById(R.id.mg_webview);
        progressBar = findViewById(R.id.mg_progress);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        com.mineways.assets.PackAssets.handler(this, "minigame"))
                .build();

        MaterialToolbar bar = findViewById(R.id.mg_toolbar);
        bar.setTitle(R.string.mg_title);
        Drawable back = AppCompatResources.getDrawable(this, R.drawable.ic_back);
        if (back != null) back.setTint(ContextCompat.getColor(this, android.R.color.white));
        bar.setNavigationIcon(back);
        bar.setNavigationOnClickListener(v -> confirmExit());

        // 返回键与工具栏返回走同一条确认逻辑，避免误触直接丢局
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        setupWebView();
        // 素材已移出 APK：老包直接加载，新包先确保资源就绪
        com.mineways.assets.PackAssets.ensure(this, "minigame", "寂零快跑",
                () -> webView.loadUrl(ASSET_URL));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 存档：游戏用 localStorage 存最佳成绩/皮肤/星级，用 IndexedDB 存 UGC 作品
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 开局就有 BGM（游戏在首次手势时启动音乐），这里放开手势限制
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // 游戏是竖屏画布，锁住缩放避免误缩放手势打断操作
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        webView.setBackgroundColor(0xFF10151D);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                AdaptInject.apply(MiniGameActivity.this, view, url);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MiniGameActivity.this,
                            R.string.mg_load_fail, Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        // 跑酷时别熄屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 先让游戏暂停，再问是否退出；选「继续游戏」就留在暂停界面。 */
    private void confirmExit() {
        try {
            webView.evaluateJavascript(JS_PAUSE, null);
        } catch (Throwable ignored) {
            // 页面没就绪也无所谓，下面照样给用户选择
        }
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.mg_exit_title)
                    .setMessage(R.string.mg_exit_message)
                    .setPositiveButton(R.string.mg_exit_confirm, (d, w) -> finish())
                    .setNegativeButton(R.string.mg_exit_cancel, null)
                    .show();
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        // 切后台：暂停页面并让游戏进入暂停菜单，BGM 随之停下
        try {
            webView.evaluateJavascript(JS_PAUSE, null);
            webView.onPause();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            webView.onResume();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.loadUrl("about:blank");
                webView.destroy();
            }
        } catch (Throwable ignored) {
        }
        webView = null;
        super.onDestroy();
    }
}
