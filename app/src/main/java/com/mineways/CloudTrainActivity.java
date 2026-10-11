package com.mineways;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 《云间列车》—— 落日、云海与穿云而过的列车（WebGL2 实时动画）。
 *
 * <p>页面本体是零依赖单文件 {@code assets/cloudtrain/index.html}，用
 * {@link WebViewAssetLoader} 以 {@code https://appassets.androidplatform.net/...} 加载：
 * 走虚拟 HTTPS 源才有真实 origin，页面里的 localStorage（参数预设
 * {@code cloud-train-saved-settings:v1}）才能正常存取。</p>
 *
 * <p>移植改动只有三处，都写在页面里带「移植补丁」注释：
 * 手机默认 <em>resolution</em> 降到 0.5、DPR 上限压到 1.0、安全区/窄屏面板样式。
 * 渲染核心与参数面板保持上游原样（出处注释保留在 HTML 头部）。</p>
 *
 * <p>本页定位是「观赏 + 调参」：隐藏状态栏、屏幕常亮、面板可折叠；
 * 返回键直接退出（没有进度可丢）。</p>
 *
 * <p>锁定横屏（清单里 {@code sensorLandscape}，随重力左右翻转）：落日云海是横向构图，
 * 竖屏只剩中间一条，看不出层次。页面里的 ResizeObserver 会跟着旋转重算分辨率与后处理
 * 缓冲，所以横竖来回切不会糊、也不会卡。</p>
 */
public class CloudTrainActivity extends AppCompatActivity {

    private static final String ASSET_URL =
            "https://appassets.androidplatform.net/assets/cloudtrain/index.html";

    private WebView webView;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05090C);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = android.view.Gravity.CENTER;
        progressBar.setLayoutParams(plp);
        root.addView(progressBar);

        setContentView(root);
        setupWebView(assetLoader);

        // 沉浸式：藏状态栏，导航栏保留（可随时退出，不把人困在页面里）
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (c != null) {
            c.hide(WindowInsetsCompat.Type.statusBars());
            c.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // 观赏用页面：别熄屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView.loadUrl(ASSET_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(final WebViewAssetLoader assetLoader) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 参数预设存在 localStorage
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // 全屏画面：允许在 WebView 内播放，不做手势拦截
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // WebGL 需要硬件加速；显式声明，避免个别 ROM 上被省电策略降级
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        webView.setBackgroundColor(0xFF05090C);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                              @NonNull WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(CloudTrainActivity.this,
                            R.string.cloudtrain_load_fail, Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        // 与页面之间的桥：页面用 MediaRecorder 录 canvas，分片传过来，这里负责落盘进相册
        webView.addJavascriptInterface(new Bridge(), "AshenBridge");
    }

    // ---------------------------------------------------------------- 录制落盘（页面分片传回）

    private final Object recLock = new Object();
    private File recTemp;
    private String recName = "";
    private Throwable recError;

    /** 页面 ↔ App 的桥：videoBegin/videoChunk/videoEnd 三段式收流，toast 报进度。 */
    private class Bridge {

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(CloudTrainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void videoBegin(String name) {
            synchronized (recLock) {
                recError = null;
                recName = sanitizeName(name);
                // 临时文件扩展名跟着真名走（mp4/webm），后面相册入库的 MIME 靠它判
                String ext = recName.endsWith(".mp4") ? ".mp4" : ".webm";
                recTemp = new File(getCacheDir(), "cloud-train-rec" + ext);
                if (recTemp.exists() && !recTemp.delete()) {
                    recError = new IllegalStateException("临时文件删不掉");
                }
            }
        }

        @JavascriptInterface
        public void videoChunk(String base64) {
            synchronized (recLock) {
                if (recError != null || recTemp == null) {
                    return;
                }
                try {
                    byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    FileOutputStream fos = new FileOutputStream(recTemp, true);
                    try {
                        fos.write(data);
                    } finally {
                        fos.close();
                    }
                } catch (Throwable t) {
                    recError = t;
                }
            }
        }

        @JavascriptInterface
        public void videoEnd(boolean ok, String msg) {
            File tmp;
            String name;
            Throwable err;
            synchronized (recLock) {
                tmp = recTemp;
                name = recName;
                err = recError;
                recTemp = null;
            }
            if (tmp == null) {
                return;
            }
            if (!ok || err != null) {
                tmp.delete();
                postToast("视频保存失败：" + (err != null ? String.valueOf(err.getMessage()) : msg));
                return;
            }
            final String where = publishVideo(tmp, name);
            tmp.delete();
            postToast("视频已保存到 " + where);
        }
    }

    private static String sanitizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) {
            n = "cloud-train-" + System.currentTimeMillis() + ".webm";
        }
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }

    private void postToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(CloudTrainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 视频收进相册：Android 10+ 走 MediaStore 的 Movies/AshenFlameFoundry；更老的进应用目录并扫库。 */
    private String publishVideo(File src, String name) {
        final String mime = mimeOf(name);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/AshenFlameFoundry");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                android.net.Uri uri = getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new IllegalStateException("相册拒绝写入");
                }
                InputStream in = new FileInputStream(src);
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) {
                        throw new IllegalStateException("相册输出流打不开");
                    }
                    try {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    } finally {
                        os.close();
                    }
                } finally {
                    in.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return "相册/Movies/AshenFlameFoundry/" + name;
            } catch (Throwable ignored) {
                // 相册写不进去就退回应用目录，至少别把视频弄丢
            }
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = getCacheDir();
        }
        File out = new File(dir, name);
        try {
            InputStream in = new FileInputStream(src);
            try {
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "缓存：" + src.getAbsolutePath();
        }
        MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                new String[]{mime}, null);
        return out.getAbsolutePath();
    }

    /** 按文件名给 MIME：sanitizeName 只留 [A-Za-z0-9._-]，endsWith 判断可靠。 */
    private static String mimeOf(String name) {
        return name != null && name.endsWith(".mp4") ? "video/mp4" : "video/webm";
    }

    @Override
    protected void onPause() {
        // 切后台就停下渲染（这个 shader 在后台空转会白烧 GPU）
        try {
            webView.onPause();
            webView.pauseTimers();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            webView.resumeTimers();
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
