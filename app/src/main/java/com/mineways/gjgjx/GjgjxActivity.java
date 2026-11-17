package com.mineways.gjgjx;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

/**
 * 「搞机工具箱」Web 移植版的容器页。
 *
 * <p>前端资源全部离线打包在 {@code assets/gjgjx}，通过
 * {@code https://appassets.androidplatform.net/assets/...} 加载，
 * 这样 ES Module、IndexedDB（ADB 密钥）、localStorage 都处在安全上下文里。
 *
 * <p>Android WebView 没有 WebUSB，页面里的 {@code js/android-usb.js} 把
 * {@code navigator.usb} 转给 {@link GjgjxUsbBridge}，由原生 UsbManager 真正收发 ADB 数据。
 *
 * <p>许可：页面与 Tango、xterm.js 均为 GPL-3.0 / MIT，随包附带了 LICENSE 文件。
 */
public class GjgjxActivity extends Activity {

    private static final int REQ_PICK_FILE = 7001;

    private WebView web;
    private GjgjxUsbBridge bridge;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!GjgjxFlags.on(this)) {
            String notice = GjgjxFlags.notice(this);
            Toast.makeText(this, notice.length() == 0 ? "搞机工具箱已被云端关闭" : notice,
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1116);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web);
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        bridge = new GjgjxUsbBridge(this, js -> web.evaluateJavascript(js, null));
        web.addJavascriptInterface(bridge, "AndroidUsb");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme())
                        && "appassets.androidplatform.net".equals(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(GjgjxActivity.this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_PICK_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        web.loadUrl("https://appassets.androidplatform.net/assets/gjgjx/index.html");

        // 云端只关了 USB 时，页面照常打开，但给一条明确理由，别让用户以为是线坏了
        if (!GjgjxFlags.usb(this)) {
            String notice = GjgjxFlags.notice(this);
            Toast.makeText(this, notice.length() == 0 ? "云端已关闭 USB 连机功能" : notice,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE) {
            return;
        }
        if (fileCallback == null) {
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.dispose();
            bridge = null;
        }
        if (web != null) {
            web.removeJavascriptInterface("AndroidUsb");
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){var d=document.getElementById('modal');"
                            + "return d&&d.open?'modal':(location.hash?'hash':'none')})()",
                    value -> {
                        String v = value == null ? "none" : value.replace("\"", "");
                        if ("modal".equals(v)) {
                            web.evaluateJavascript(
                                    "document.getElementById('modal-close')?.click()", null);
                        } else if ("hash".equals(v)) {
                            web.evaluateJavascript("history.back()", null);
                        } else {
                            finish();
                        }
                    });
        } else {
            super.onBackPressed();
        }
    }
}
