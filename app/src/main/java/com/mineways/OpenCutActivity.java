package com.mineways;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenCut 视频剪辑器（离线 Web 版）。
 *
 * <p>页面本体是 Next.js {@code output:"export"} 的静态产物，整棵目录放在
 * {@code assets/opencut/}（490 个文件，约 38MB）。产物里的引用全是根绝对路径
 * （{@code /_next/static/...}、{@code /fonts/font-atlas.json}），所以必须把站点挂在
 * 虚拟域的<em>根</em>上，不能挂在 {@code /assets/opencut/} 这种前缀下，否则一层层
 * 绝对路径全部断链。</p>
 *
 * <p>域名单独用 {@code opencut.appassets.androidplatform.net}（而不是默认的
 * {@code appassets.androidplatform.net}，云间列车在用）：这样两者的 localStorage
 * / IndexedDB / OPFS 各自一份，互不挤占配额。走 HTTPS 虚拟域还顺带满足了 secure
 * context —— IndexedDB、OPFS、WebCodecs 只在安全上下文里开放。</p>
 *
 * <p>{@link #AssetsRootHandler} 不用现成的 {@code AssetsPathHandler}：一是它不会把
 * {@code /projects/} 这种目录名补成 {@code index.html}；二是它对 {@code .wasm} 的
 * MIME 依赖系统的 MimeTypeMap，而 WebAssembly 的流式编译（渲染引擎那个 2.9MB 的
 * wasm）要求响应头是 {@code application/wasm}，个别 ROM 给不出来就直接
 * CompileError。</p>
 *
 * <p>导出：页面里的 {@code downloadBuffer} 优先走 {@link Bridge}（和云间列车同一套
 * videoBegin/videoChunk/videoEnd 三段式）落相册，没有桥时（在浏览器里打开）才退回
 * 锚点下载。</p>
 */
public class OpenCutActivity extends AppCompatActivity {

    private static final String DOMAIN = "opencut.appassets.androidplatform.net";
    private static final String ASSET_ROOT = "opencut/";
    private static final String ENTRY_URL = "https://" + DOMAIN + "/projects/";
    private static final int REQ_FILE = 4002;
    /** 第三方管理器给的裸文件路径要复制成 content URI，但大文件不复制（私有目录经不起双倍占位） */
    private static final long MAX_UPLOAD_COPY_BYTES = 256L * 1024L * 1024L;
    private static final int BG = 0xFF15171A;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileCallback;
    private FrameLayout root;
    /** HTML5 全屏层：requestFullscreen() 的内容由系统回调塞进这里（见 onShowCustomView） */
    private FrameLayout fullscreenLayer;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(DOMAIN)
                .addPathHandler("/", new AssetsRootHandler(getAssets()))
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(plp);
        root.addView(progressBar);

        fullscreenLayer = new FrameLayout(this);
        fullscreenLayer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenLayer.setBackgroundColor(BG);
        fullscreenLayer.setVisibility(View.GONE);
        root.addView(fullscreenLayer);

        setContentView(root);
        setupWebView(assetLoader);
        goImmersive();

        // 剪辑是长活儿：亮着屏免得中途息屏被打断
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView.loadUrl(ENTRY_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(final WebViewAssetLoader assetLoader) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // 工程库在 IndexedDB，媒体在 OPFS，两者都要 DOM storage 打底
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccess(false);
        // 关掉系统的字体放大，否则中文/数字尺寸会被 ROM 按字号设置改掉，布局跟着错位
        s.setTextZoom(100);
        // 页面靠这个 UA 尾巴判定「我在 App 容器里」，进而启用默认中文 + 横屏紧凑布局
        s.setUserAgentString(s.getUserAgentString() + " MinewaysApp");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(BG);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                              @NonNull WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            /**
             * 把特效卡片拖到时间线之外时，WebView 会把卡片里那张示例图当成链接打开，
             * 整个界面就变成示例女人照了。主框架只允许停留在自己的域名里。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !DOMAIN.equals(request.getUrl().getHost());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(OpenCutActivity.this,
                            R.string.opencut_load_fail, Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            /**
             * 页面里的 requestFullscreen()（预览的全屏键）在 WebView 上默认是空操作：
             * 只有 App 接住这个回调、把系统给的视图铺满屏幕，全屏才真的生效。
             */
            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                view.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenLayer.addView(view);
                fullscreenLayer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                WindowInsetsControllerCompat fc = WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
                if (fc != null) {
                    fc.hide(WindowInsetsCompat.Type.systemBars());
                }
            }

            @Override
            public void onHideCustomView() {
                exitHtmlFullscreen();
            }

            /** 页面 console 落到 logcat：adb logcat -s MW-WEB 就能看到 wasm/渲染报错 */
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.i("MW-WEB",
                        m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }

            /** 导入素材：页面点隐藏的文件输入框，这里开系统选择器接住。 */
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
                    String[] accept = params == null ? null : params.getAcceptTypes();
                    String[] mime = mimeFilterFor(accept);
                    if (mime != null) {
                        pick.putExtra(Intent.EXTRA_MIME_TYPES, mime);
                    }
                    if (params == null || params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(Intent.createChooser(pick, "选择文件"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AshenBridge");
    }

    /**
     * 沉浸式：横屏剪辑每一行像素都金贵，状态栏（主题默认色是绿色，占 24~48dp）和
     * 导航栏都藏掉，页面拿到整块屏幕。手势上滑临时呼出，不把人困死。
     */
    private void goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (c != null) {
            c.hide(WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** 退出 HTML5 全屏（返回键和 onHideCustomView 都走这里）。 */
    private void exitHtmlFullscreen() {
        if (customView == null) {
            return;
        }
        fullscreenLayer.removeView(customView);
        customView = null;
        fullscreenLayer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (customCallback != null) {
            try {
                customCallback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            customCallback = null;
        }
        goImmersive();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (resultCode == RESULT_OK && data != null) {
                cb.onReceiveValue(chooserResult(data));
            } else {
                cb.onReceiveValue(null);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 单选在 data，多选在 clipData，两种都得摊平，否则多选只进来一个。
     *
     * 顺手把 {@code file://} 换成 FileProvider 的 {@code content://}：MT 管理器等第三方
     * 文件管理器回的是 {@code file:///storage/emulated/0/...} 这种裸路径，而本页面明确
     * 关掉了 {@code setAllowFileAccess(false)}，页面拿到它读不到字节 —— 表现就是"选了
     * 文件但什么都没发生"。复制进应用私有 files 目录再发一个 content URI，既不用放开
     * 那个安全开关，也不用为了分享整块外部存储去改 file_paths.xml
     * （已有的 {@code files-path path="./"} 正好覆盖）。
     */
    private Uri[] chooserResult(Intent data) {
        java.util.List<Uri> out = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) {
                    out.add(viaProvider(u));
                }
            }
        }
        if (out.isEmpty() && data.getData() != null) {
            out.add(viaProvider(data.getData()));
        }
        return out.toArray(new Uri[0]);
    }

    /**
     * {@code <input accept>} → 选择器能用的 MIME 表。
     *
     * accept 里可能是真 MIME（素材导入给的 {@code video/*} 那一类），也可能是一串扩展名
     * （字体导入给的 {@code .ttf,.otf,.woff,.woff2}）。以前是**原样**塞进
     * EXTRA_MIME_TYPES：扩展名不是 MIME，MT 管理器这类第三方选择器拿一张废类型表去筛，
     * 结果一个文件都不显示，看着就像"不支持第三方管理器"。
     *
     * 认得的扩展名映射成 MIME，并一律补上 {@code application/octet-stream} —— 各家 ROM
     * 对 .ttf 的嗅探不统一（新系统给 font/ttf，老系统给 octet-stream），只按一种过滤就会
     * 在部分机型上把字体藏掉。碰到认不出的扩展名就干脆不做类型过滤（返回 null，保持
     * 通配类型）：让"看得见"优先于"筛得准"，后缀由页面自己校验并报中文。
     */
    private static String[] mimeFilterFor(String[] accept) {
        if (accept == null || accept.length == 0) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean fromExtension = false;
        for (String raw : accept) {
            if (raw == null) {
                continue;
            }
            String item = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (item.isEmpty() || "*/*".equals(item) || "*".equals(item)) {
                return null;
            }
            if (item.indexOf('/') >= 0) {
                out.add(item);
                continue;
            }
            if (!item.startsWith(".")) {
                return null;
            }
            String mapped = MIME_BY_EXTENSION.get(item);
            if (mapped == null) {
                return null;
            }
            out.add(mapped);
            fromExtension = true;
        }
        if (out.isEmpty()) {
            return null;
        }
        // 只有按扩展名映射出来的名单才补 octet-stream：素材那边是真 MIME（video/* 等），
        // 补了会把"只能选视频/音频"的筛子变成"什么都能看见"。字体这边不同——各家 ROM
        // 对 .ttf 的嗅探不统一，新系统给 font/ttf、老系统给 octet-stream，只按一种过滤
        // 就会在某些机型上把字体整个藏掉。
        if (fromExtension) {
            out.add("application/octet-stream");
        }
        return out.toArray(new String[0]);
    }

    private static final Map<String, String> MIME_BY_EXTENSION = new HashMap<>();

    static {
        MIME_BY_EXTENSION.put(".ttf", "font/ttf");
        MIME_BY_EXTENSION.put(".otf", "font/otf");
        MIME_BY_EXTENSION.put(".woff", "font/woff");
        MIME_BY_EXTENSION.put(".woff2", "font/woff2");
        MIME_BY_EXTENSION.put(".ttc", "font/collection");
    }

    /** 裸文件路径 → 私有目录副本 + content URI；太大、读不到或拷坏就原样交出去，不拦路。 */
    private Uri viaProvider(Uri raw) {
        if (!"file".equals(raw.getScheme())) {
            return raw;
        }
        File src;
        try {
            src = new File(raw.getPath());
        } catch (Throwable t) {
            return raw;
        }
        long size = src.length();
        if (!src.isFile() || size <= 0L || size > MAX_UPLOAD_COPY_BYTES) {
            return raw;
        }
        File dir = new File(getFilesDir(), "uploads");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return raw;
        }
        pruneUploads(dir);
        File dst = new File(dir, System.currentTimeMillis() + "-" + safeName(src.getName()));
        InputStream in = null;
        OutputStream o = null;
        try {
            in = new FileInputStream(src);
            o = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
            o.flush();
        } catch (Throwable t) {
            if (dst.isFile()) {
                dst.delete();
            }
            return raw;
        } finally {
            closeQuietly(in);
            closeQuietly(o);
        }
        Uri shared = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", dst);
        try {
            // 注意是 Context 上的 grantUriPermission：ContentResolver 那个同名方法是 hide 的
            grantUriPermission(
                    getPackageName(), shared, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        return shared;
    }

    private static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 64; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? "upload" : sb.toString();
    }

    /** 私有目录不是回收站：超过一天的中转文件顺手清掉。 */
    private static void pruneUploads(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - 24L * 3600L * 1000L;
        for (File f : kids) {
            if (f.isFile() && f.lastModified() < deadline) {
                f.delete();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- 导出落盘（页面分片传回）

    private final Object recLock = new Object();
    private File recTemp;
    private String recName = "";
    private Throwable recError;

    /** 页面 ↔ App 的桥：三段式收字节流，收完按类型进相册。 */
    private class Bridge {

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(OpenCutActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void videoBegin(String name) {
            synchronized (recLock) {
                recError = null;
                recName = sanitizeName(name);
                String ext = extOf(recName);
                recTemp = new File(getCacheDir(), "opencut-export." + ext);
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
                postToast("导出失败：" + (err != null ? String.valueOf(err.getMessage()) : msg));
                return;
            }
            final String where = publish(tmp, name);
            tmp.delete();
            postToast("已保存到 " + where);
        }
    }

    private static String sanitizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) {
            n = "opencut-" + System.currentTimeMillis() + ".mp4";
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

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "bin"
                : name.substring(dot + 1).toLowerCase(java.util.Locale.US);
    }

    private static boolean isImage(String ext) {
        return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)
                || "webp".equals(ext) || "gif".equals(ext);
    }

    private void postToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(OpenCutActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 成品收进相册：视频进 Movies/OpenCut、图片进 Pictures/OpenCut；相册写不进就退回应用目录并扫库。 */
    private String publish(File src, String name) {
        final String ext = extOf(name);
        final boolean image = isImage(ext);
        final String mime = mimeOf(ext);
        final String dir = "OpenCut";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(image ? MediaStore.Images.Media.DISPLAY_NAME
                        : MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(image ? MediaStore.Images.Media.MIME_TYPE
                        : MediaStore.Video.Media.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES)
                                + "/" + dir);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri base = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Uri uri = getContentResolver().insert(base, cv);
                if (uri == null) {
                    throw new IllegalStateException("相册拒绝写入");
                }
                copy(src, getContentResolver().openOutputStream(uri));
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return "相册/" + dir + "/" + name;
            } catch (Throwable ignored) {
                // 相册写不进去就退回应用目录，至少别把成品弄丢
            }
        }
        File out = new File(getExternalFilesDir(
                image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES), name);
        try {
            copy(src, new FileOutputStream(out));
        } catch (Throwable t) {
            return "缓存：" + src.getAbsolutePath();
        }
        MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                new String[]{mime}, null);
        return out.getAbsolutePath();
    }

    private static void copy(File src, OutputStream os) throws IOException {
        if (os == null) {
            throw new IOException("输出流打不开");
        }
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = os;
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    @Override
    public void onBackPressed() {
        // 全屏预览时返回键先退出全屏，别直接把剪辑页关掉
        if (customView != null) {
            exitHtmlFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
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

    // ---------------------------------------------------------------- 静态产物 → 虚拟域根路径

    /**
     * 把 {@code https://<域>/xxx} 映射到 {@code assets/opencut/xxx}。
     *
     * <p>目录式的链接（{@code /projects/}）补 {@code index.html}；没有扩展名也没有
     * 斜杠结尾的（{@code /editor/new}）先当目录试，再原样试。</p>
     */
    private static final class AssetsRootHandler implements WebViewAssetLoader.PathHandler {

        private final AssetManager assets;

        AssetsRootHandler(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse handle(String path) {
            String p = path == null ? "" : path;
            // 音效库是服务端代理（Freesound），静态包里根本没有 /api：给个空结果，
            // 让面板走「暂无结果」而不是报错转圈。形状照 use-sound-search.ts 的读法来。
            if (p.startsWith("api/sounds")) {
                byte[] empty = "{\"results\":[],\"next\":null,\"count\":0}"
                        .getBytes(StandardCharsets.UTF_8);
                WebResourceResponse res = new WebResourceResponse("application/json", null,
                        new java.io.ByteArrayInputStream(empty));
                res.setResponseHeaders(noCache());
                return res;
            }
            InputStream in = null;
            String key = null;
            for (String cand : candidates(p)) {
                for (String variant : rscVariants(cand)) {
                    in = tryOpen(variant);
                    if (in != null) {
                        key = variant;
                        break;
                    }
                }
                if (in != null) {
                    break;
                }
            }
            if (in == null) {
                WebResourceResponse miss = new WebResourceResponse("text/plain", null,
                        new java.io.ByteArrayInputStream(new byte[0]));
                miss.setStatusCodeAndReasonPhrase(404, "Not Found");
                return miss;
            }
            WebResourceResponse res = new WebResourceResponse(mimeOf(key), null, in);
            res.setResponseHeaders(noCache());
            return res;
        }

        private static Map<String, String> noCache() {
            Map<String, String> h = new HashMap<>();
            // 重打包后别端着旧缓存
            h.put("Cache-Control", "no-cache");
            return h;
        }

        /**
         * Next 导出的 RSC 分片在磁盘上和请求里对不上：请求
         * {@code __next.editor.$d$project_id.txt}，落盘是 {@code __next.editor/$d$project_id.txt}。
         * 精确路径没命中时，按「{@code __next} 之后的点号还原成目录」再试一次。
         */
        private static String[] rscVariants(String key) {
            int at = key.indexOf("__next.");
            if (at < 0) {
                return new String[]{key};
            }
            String[] tok = key.substring(at).split("\\.");
            if (tok.length < 4) {
                return new String[]{key};
            }
            StringBuilder sb = new StringBuilder(key.substring(0, at));
            sb.append(tok[0]).append('.').append(tok[1]);
            for (int i = 2; i < tok.length - 2; i++) {
                sb.append('/').append(tok[i]);
            }
            sb.append('/').append(tok[tok.length - 2]).append('.').append(tok[tok.length - 1]);
            return new String[]{key, sb.toString()};
        }

        private static String[] candidates(String p) {
            if (p.length() == 0 || "/".equals(p)) {
                return new String[]{ASSET_ROOT + "index.html"};
            }
            if (p.endsWith("/")) {
                return new String[]{ASSET_ROOT + p + "index.html", ASSET_ROOT + p};
            }
            String full = ASSET_ROOT + p;
            if (p.lastIndexOf('/') >= 0 && p.lastIndexOf('.') < p.lastIndexOf('/')) {
                return new String[]{full + "/index.html", full};
            }
            return new String[]{full};
        }

        private InputStream tryOpen(String key) {
            try {
                return assets.open(key);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static String mimeOf(String extOrName) {
        String ext = extOrName.contains(".")
                ? extOrName.substring(extOrName.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.US)
                : extOrName.toLowerCase(java.util.Locale.US);
        if ("html".equals(ext) || "htm".equals(ext)) return "text/html";
        if ("js".equals(ext) || "mjs".equals(ext)) return "text/javascript";
        if ("css".equals(ext)) return "text/css";
        if ("json".equals(ext)) return "application/json";
        if ("wasm".equals(ext)) return "application/wasm";
        if ("svg".equals(ext)) return "image/svg+xml";
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("ico".equals(ext)) return "image/x-icon";
        if ("mp4".equals(ext)) return "video/mp4";
        if ("webm".equals(ext)) return "video/webm";
        if ("woff".equals(ext) || "woff2".equals(ext) || "ttf".equals(ext)
                || "otf".equals(ext) || "eot".equals(ext)) return "font/" + ext;
        if ("xml".equals(ext) || "txt".equals(ext)) return "text/plain";
        return "application/octet-stream";
    }
}
