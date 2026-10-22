package com.mineways;

import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.mineways.cloud.LoginPolicy;

/**
 * 内嵌授权页 —— 只在<b>这台设备连一个能用的浏览器都拉不起来</b>时才当兜底。
 *
 * <p>Google 对 WebView 的 UA 会回 {@code disallowed_useragent}，这不是 bug 而是它家的规则，
 * 所以这里不假装能成：真撞上就把这句话连同「装一个 Chrome/Edge 再试」显示在顶上，
 * 而不是转圈或者报「登录失败」。微软那边一般能在内嵌页里走完。
 */
public class SocialWebActivity extends AppCompatActivity {

    private static final int BG = 0xFF000000, PANEL = 0xCC15171A, TEXT = 0xFFEDEDED, DIM = 0xFF9AA1AB;

    private WebView web;
    private TextView tvStatus;
    private String scope = "";
    private boolean settled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scope = SocialLogin.scopeOfIntent(getIntent());
        if (scope.length() == 0) {
            scope = SocialLogin.pendingScope();
        }
        setContentView(build());
        String url = SocialLogin.pendingUrl();
        if (url.length() == 0) {
            status("这次登录没有开始（配置没读到或已经过期），回上一页重新点一次。");
            return;
        }
        status("① 在下方完成" + LoginPolicy.scopeCn(scope) + "授权\n"
                + "② 授权后会自动回到 App —— 这一页不用你抄任何东西");
        web.loadUrl(url);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(TEXT);
        tvStatus.setLineSpacing(dp(4), 1f);
        tvStatus.setPadding(dp(16), dp(14), dp(16), dp(14));
        tvStatus.setBackground(round(PANEL, 0));
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " MinewaysMobile");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return capture(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return req == null || req.getUrl() == null
                        || capture(req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (settled || url == null) {
                    return;
                }
                if (url.contains("disallowed_useragent")) {
                    status("Google 不允许在应用内网页里授权（disallowed_useragent）。\n"
                            + "这家人家的硬规则：请装一个系统浏览器（Chrome / Edge / Firefox）后重试。\n"
                            + "微软登录不受这条限制。");
                    return;
                }
                status("授权页已打开，完成" + LoginPolicy.scopeCn(scope) + "授权后会自动回来。");
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** 地址栏跳出回调前缀就代表授权完成，页面本身不用继续加载。 */
    private boolean capture(String url) {
        if (SocialLogin.isCallback(url)) {
            settled = true;
            status("已经拿到授权码，正在换取登录状态…");
            SocialLogin.handleRedirect(url, "应用内授权页");
            return true;
        }
        return false;
    }

    private void status(String s) {
        final String t = s;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvStatus != null) {
                    tvStatus.setText(t);
                }
            }
        });
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.destroy();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
