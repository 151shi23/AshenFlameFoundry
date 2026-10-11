package com.mineways;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.URLDecoder;

/**
 * App 内一键授权登录（不抄码、不粘贴）。
 *
 * <p>流程：打开论坛的「授权应用」页 → 你登录（已登录则直接用）→ 页面里的脚本
 * 用你自己会话调用论坛的 <b>/api/user/oauth/code</b> 取到授权码 → 通过自定义 URL
 * 回传原生层 → App 立刻用 <b>/api/oauth/token</b> 兑换 token → 拉资料 → 存档。</p>
 *
 * <p>这样做把「网页显示的码形态与用户在键盘上输入的形态不一致」这类问题彻底绕开：
 * 码由接口原样返回、原样提交，中间没有任何人工环节。</p>
 */
public class OAuthWebActivity extends AppCompatActivity {

    private static final int BG = 0xFF0F1013, PANEL = 0xFF15171A, TEXT = 0xFFEDEDED,
            DIM = 0xFF8A9099, ACCENT = 0xFFD9603A;

    private WebView web;
    private TextView tvStatus;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(TEXT);
        tvStatus.setPadding(dp(16), dp(14), dp(16), dp(14));
        tvStatus.setBackground(round(PANEL, 0));
        tvStatus.setText("① 在下面登录论坛（已登录会直接跳过）\n"
                + "② 保持页面打开：App 会自动「取码 → 确认授权 → 兑换登录」全程完成");
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 论坛登录态存在 localStorage
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " MinewaysMobile");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("affoauth://")) {
                    onCallback(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject(view);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web.loadUrl(OAuthClient.homePage() + "/settings/oauth");
        return root;
    }

    /** 页面加载完成后注入取码脚本（用页面自己的登录态调论坛接口）。 */
    private void inject(WebView view) {
        if (finished) {
            return;
        }
        final String appKey = BuildConfig.AFF_OAUTH_APP_KEY;
        if (appKey == null || appKey.length() == 0) {
            status("local.properties 里没有 AFF_OAUTH_APP_KEY，无法一键授权");
            return;
        }
        String js = "(function(){"
                + "function rep(s){try{location.href='affoauth://result?data='+encodeURIComponent(s);}catch(e){}}"
                + "if(window.__affBusy){return;}window.__affBusy=true;"
                + "function hint(txt){var j=null;try{j=JSON.parse(txt);}catch(e){}return (j&&(j.error_description||j.error||j.message))||txt||'unknown';}"
                + "var t=0;var iv=setInterval(function(){"
                + "  t++;"
                + "  var tok=null;try{tok=localStorage.getItem('token');}catch(e){}"
                + "  if(!tok){if(t>90){clearInterval(iv);rep('NOLOGIN');}return;}"
                + "  clearInterval(iv);"
                + "  fetch('/api/user/oauth/code',{method:'POST',headers:{'Content-Type':'application/json',"
                + "'Authorization':'Bearer '+tok},body:JSON.stringify({appKey:'" + appKey + "',permissions:['profile']})})"
                + "  .then(function(r){return r.text();})"
                + "  .then(function(txt){var j=null;try{j=JSON.parse(txt);}catch(e){}"
                + "    if(!(j&&j.success&&j.code)){rep('ERR:'+hint(txt));return;}"
                + "    var c=j.code;"
                // 论坛状态机：unused --(第三方 submit)--> pending --(用户 confirm)--> confirmed --(token)--> 通过
                + "    var appSecret='" + BuildConfig.AFF_OAUTH_APP_SECRET + "';"
                + "    function doConfirm(){"
                + "      fetch('/api/user/oauth/confirm/'+encodeURIComponent(c),{method:'POST',"
                + "        headers:{'Content-Type':'application/json','Authorization':'Bearer '+tok},"
                + "        body:JSON.stringify({action:'confirm'})})"
                + "        .then(function(r3){return r3.text();})"
                + "        .then(function(t3){var k=null;try{k=JSON.parse(t3);}catch(e){}"
                + "          if(k&&k.success===false){rep('CONFIRM_FAIL:'+hint(t3)+'|'+c);}else{rep('CODE:'+c);}})"
                + "        .catch(function(){rep('CODE:'+c);});"
                + "    }"
                + "    fetch('/api/oauth/submit',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "      body:JSON.stringify({code:c,appKey:'" + appKey + "',appSecret:appSecret})})"
                + "      .then(function(r2){return r2.text();})"
                + "      .then(function(t2){doConfirm();})"
                + "      .catch(function(){doConfirm();});"
                + "  })"
                + "  .catch(function(e){rep('ERR:'+e);});"
                + "},1000);"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    /** 收到页面回传：CODE:xxxx 立即兑换。 */
    private void onCallback(String url) {
        String data = "";
        try {
            int i = url.indexOf("data=");
            if (i >= 0) {
                data = URLDecoder.decode(url.substring(i + 5), "UTF-8");
            }
        } catch (Throwable ignored) {
        }
        if (data.startsWith("CODE:")) {
            tryExchange(data.substring(5).trim());
        } else if (data.startsWith("CONFIRM_FAIL:")) {
            // 确认失败：还是把码拿出去试一次（可能本来就已确认过）
            String rest = data.substring("CONFIRM_FAIL:".length());
            int bar = rest.indexOf('|');
            String why = bar > 0 ? rest.substring(0, bar) : rest;
            final String code = bar > 0 ? rest.substring(bar + 1).trim() : "";
            status("授权确认失败（" + why + "）→ 仍尝试兑换一次…");
            if (code.length() > 0) {
                tryExchange(code);
            }
        } else if ("NOLOGIN".equals(data)) {
            status("还没检测到登录态：请在上面的页面登录论坛，登录后脚本会自动继续。");
            web.evaluateJavascript("window.__affBusy=false;", null);
        } else if (data.startsWith("ERR:")) {
            status("论坛返回： " + data.substring(4)
                    + "\n（可能权限没勾选 / 应用被禁用 / 需要重新登录）");
            web.evaluateJavascript("window.__affBusy=false;", null);
        }
    }

    /** 拿码去兑换（已确认过或确认失败但想再试一次时都走这里）。 */
    private void tryExchange(final String code) {
        status("③ 已取到授权码（已确认），正在兑换登录…");
        new Thread(() -> {
            final OAuthClient.Token t = OAuthClient.exchangeToken(code);
            final OAuthClient.User u = t.ok ? OAuthClient.userInfo(t.accessToken) : null;
            runOnUiThread(() -> {
                if (t.ok) {
                    OAuthStore.save(this, t.accessToken, u);
                    String name = (u != null && u.username.length() > 0) ? u.username : "已登录";
                    status("✅ 登录成功：" + name);
                    Toast.makeText(this, "登录成功：" + name, Toast.LENGTH_SHORT).show();
                    finished = true;
                    finish();
                    return;
                }
                status("兑换失败：\n"
                        + OAuthClient.friendlyError(t.code, t.message, t.httpStatus, t.rawBody)
                        + "\n〔取到的码原文〕" + code
                        + "\n\n（把这个信息发我，就能定位到服务端侧）");
            });
        }, "oauth-web").start();
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
        try {
            if (web != null) {
                web.destroy();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void status(String s) {
        runOnUiThread(() -> tvStatus.setText(s));
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
