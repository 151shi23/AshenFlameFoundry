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
import android.provider.MediaStore;import android.view.Gravity;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    /** 在线下载的字体存放处（私有 files 下的子目录，同时挂在站内 /res-fonts/ 路径上） */
    private static final String FONT_STORE_DIR = "res-fonts";
    /** 单个在线字体的体积上限：中文字体一个字重常见 8~30MB，再大基本不是单个字体 */
    private static final long MAX_FONT_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    /** 中转目录的总量上限（一个包 33 个文件就可能 80MB，攒久了私有目录会被吃掉一大块） */
    private static final long FONT_STORE_MAX_BYTES = 96L * 1024L * 1024L;
    /** 第三方管理器给的裸文件路径要复制成 content URI，但大文件不复制（私有目录经不起双倍占位） */
    private static final long MAX_UPLOAD_COPY_BYTES = 256L * 1024L * 1024L;
    private static final int BG = 0xFF15171A;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileCallback;
    /** 拉包拿到的文件元数据 + 24h 直链，只留在原生侧（键：{@code slug#seq}） */
    private final java.util.Map<String, org.json.JSONObject> packCache =
            new java.util.concurrent.ConcurrentHashMap<>();
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
                // 在线下载的字体落在私有目录，按站内路径发回页面（同源，省掉跨域与 blob 落盘）
                .addPathHandler("/res-fonts/", new DirPathHandler(
                        new File(getFilesDir(), FONT_STORE_DIR)))
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

    /**
     * 把私有目录里的一个子目录挂到站内路径上。
     *
     * <p>为什么自己写：这版 androidx.webkit 没有 {@code FilesPathHandler}（编译期就报
     * "找不到符号"），而打包是 {@code --offline} 的，不能靠升依赖解决。
     */
    private static final class DirPathHandler implements WebViewAssetLoader.PathHandler {
        private final File root;

        DirPathHandler(File root) {
            this.root = root;
        }

        @Override
        public WebResourceResponse handle(String path) {
            try {
                File target = new File(root, path == null ? "" : path).getCanonicalFile();
                String rootPath = root.getCanonicalPath();
                // 页面给的路径要能逃出这个目录就绝不发出去
                if (!target.getPath().startsWith(rootPath + File.separator)) {
                    return null;
                }
                if (!target.isFile()) {
                    return null;
                }
                // 这个构造子默认就是 200/OK，不需要再显式设状态
                return new WebResourceResponse(
                        "application/octet-stream", null, new FileInputStream(target));
            } catch (Throwable t) {
                return null;
            }
        }
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

    // ------------------------------------------------------- 在线字体（软件资源服务）

    /** 服务端整包 JSON 能用就用，不能用就退化成一条中文错误。 */
    private static String pickRaw(ActivationClient.Result r, String what) {
        String raw = r == null ? null : r.raw;
        if (raw != null) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return trimmed;
            }
        }
        String message = r == null || r.message == null || r.message.length() == 0
                ? what + "失败"
                : r.message;
        return errorJson(message);
    }

    private static String errorJson(String message) {
        return "{\"success\":false,\"error\":\""
                + ActivationClient.jsonEscape(message == null ? "未知错误" : message) + "\"}";
    }

    private static String friendlyError(Throwable t) {
        String name = t == null ? "" : t.getClass().getSimpleName();
        return "出错了：" + (name.length() == 0 ? "未知原因" : name);
    }

    /** 把 JSON 交给页面回调；回调名先消毒，免得拼进 JS 时被当成代码执行。 */
    private void deliverJson(final String callback, final String json) {
        final String fn = callback == null ? "" : callback.replaceAll("[^A-Za-z0-9_$]", "");
        if (fn.length() == 0) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(
                            "if(window." + fn + "){window." + fn + "(" + json + ")}", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 拉一个字体包的文件清单（服务端 v3 文件包模型：一个 slug = 一个包，包内多个文件）。
     *
     * <p>直链<b>留在原生侧</b>不进页面：页面既拉不动对象存储域名（CORS + 放行范围），
     * 拿到一串 24 小时签名 URL 也只是多一处会泄漏的地方。这里只回元数据，
     * 页面按 {@code seq} 找我们要文件。</p>
     */
    private String packFiles(String rawSlug) throws Exception {
        String slug = safeSlug(rawSlug);
        if (slug.length() == 0) {
            return errorJson("字体标识不合法");
        }
        String raw = ActivationClient.resourceDownload(slug).raw;
        String refusal = packRefusal(raw);
        if (refusal != null) {
            return refusal;
        }
        org.json.JSONObject data = new org.json.JSONObject(raw).optJSONObject("data");
        org.json.JSONArray incoming = data == null ? null : data.optJSONArray("files");
        if (incoming == null || incoming.length() == 0) {
            return errorJson("这个包里没有可下载的文件");
        }
        pruneFontStore();
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < incoming.length(); i++) {
            org.json.JSONObject f = incoming.optJSONObject(i);
            if (f == null) {
                continue;
            }
            int seq = f.optInt("seq", i + 1);
            String fileName = nullableText(f, "file_name");
            String versionName = nullableText(f, "version_name");
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("seq", seq);
            meta.put("file_name", fileName.length() > 0 ? fileName : "font-" + seq + ".ttf");
            meta.put("file_size_bytes", f.optLong("file_size_bytes", 0L));
            meta.put("file_sha256", nullableText(f, "file_sha256").toLowerCase());
            meta.put("version_name", versionName.length() > 0 ? versionName : "v" + seq);
            meta.put("download_url", nullableText(f, "download_url"));
            packCache.put(slug + "#" + seq, meta);
            out.put(new org.json.JSONObject()
                    .put("seq", seq)
                    .put("file_name", meta.get("file_name"))
                    .put("file_size_bytes", meta.get("file_size_bytes"))
                    .put("file_sha256", meta.get("file_sha256"))
                    .put("version_name", meta.get("version_name")));
        }
        return new org.json.JSONObject()
                .put("success", true)
                .put("slug", slug)
                .put("name", nullableText(data, "name").length() > 0
                        ? nullableText(data, "name") : slug)
                .put("total_files", out.length())
                .put("total_size_bytes", data.optLong("total_size_bytes", 0L))
                .put("expires_in", data.optInt("expires_in", 86400))
                .put("files", out)
                .toString();
    }

    /** 按包内序号取一个文件：本地已有同校验值的直接复用，否则边下边算 SHA256。 */
    private String packFile(String rawSlug, int seq) throws Exception {
        String slug = safeSlug(rawSlug);
        if (slug.length() == 0) {
            return errorJson("字体标识不合法");
        }
        org.json.JSONObject meta = packCache.get(slug + "#" + seq);
        if (meta == null) {
            // 直链只有拉包那一刻才有；页面隔太久才点、或进程被回收过，就重新拉一次
            packFiles(slug);
            meta = packCache.get(slug + "#" + seq);
        }
        if (meta == null) {
            return errorJson("这个包里没有这个文件，重新打开列表试试");
        }
        String url = nullableText(meta, "download_url");
        String wantSha = nullableText(meta, "file_sha256").toLowerCase();
        String fileName = nullableText(meta, "file_name");
        if (fileName.length() == 0) {
            fileName = "font.ttf";
        }
        if (!url.startsWith("http")) {
            return errorJson("下载链接不可用（可能已过期，请重新拉一次列表）");
        }
        File dir = new File(getFilesDir(), FONT_STORE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return errorJson("存不下来：目录创建失败");
        }
        /*
         * 落盘名以校验值开头：包内文件是"同名重传=换内容、seq 不变"（服务端 §3.2），
         * 所以只按文件名判断"下过了"必然漏更新；按 sha 命名，换内容就是换文件名，天然差量。
         */
        String stored = (wantSha.length() >= 16 ? wantSha.substring(0, 16) : String.valueOf(seq))
                + "-" + safeName(fileName);
        File out = new File(dir, stored);
        boolean skipped = false;
        if (out.isFile() && out.length() > 0L && out.length() <= MAX_FONT_DOWNLOAD_BYTES) {
            skipped = true;
        } else {
            String gotSha = downloadTo(url, out);
            if (gotSha == null) {
                if (out.isFile()) {
                    out.delete();
                }
                return errorJson("下载失败（网络或对象存储那边拒绝了）");
            }
            // 校验值不一致就丢掉：坏字节喂给 FontFace 只会变成更难懂的报错
            if (wantSha.length() > 0 && !wantSha.equals(gotSha)) {
                out.delete();
                return errorJson("文件校验不通过，已丢弃");
            }
        }
        long size = out.length();
        if (size <= 0L) {
            out.delete();
            return errorJson("字体文件是空的");
        }
        return new org.json.JSONObject()
                .put("success", true)
                .put("url", "/res-fonts/" + stored)
                .put("name", fileName)
                .put("size", size)
                .put("sha256", wantSha)
                .put("skipped", skipped)
                .toString();
    }

    /**
     * 读一个「可能是 JSON null」的字段，一律归一成空串。
     *
     * <p>这里不能直接用 {@code optString}：安卓那份 org.json 把显式 {@code null} 存成
     * {@code JSONObject.NULL}，{@code optString} 走 {@code toString()} 会拿到<b>字面量 "null"</b>
     * （长度 4，非空）。于是服务端写 {@code "require_feature": null}（= 不需要激活）会被读成
     * "需要激活"，在线字体整条链卡在「需先激活」——真机上就是这么撞的。三种情况都算空：
     * 字段缺失、值是 JSON null、值被读成字符串 "null"。</p>
     */
    private static String nullableText(org.json.JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || org.json.JSONObject.NULL.equals(value)) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    /** 服务端拒绝（success=false）或这次不该放行（require_feature）时给页面的 JSON，否则 null。 */
    private String packRefusal(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return errorJson("服务端没有返回内容");
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            if (!root.optBoolean("success", false)) {
                /*
                 * code 必须一起带给页面：`TIMESTAMP_EXPIRED`（手机时钟偏差）这类只有靠码
                 * 才能翻成"去开自动校时"，只给 message 用户不知道该干什么。
                 */
                String code = nullableText(root, "code");
                String why = nullableText(root, "error");
                if (why.length() == 0) {
                    why = nullableText(root, "message");
                }
                String message = why.length() > 0 ? why
                        : (code.length() > 0 ? "" : "服务端拒绝发放下载链接");
                return new org.json.JSONObject()
                        .put("success", false)
                        .put("error", message)
                        .put("code", code)
                        .toString();
            }
            org.json.JSONObject data = root.optJSONObject("data");
            /*
             * 文档 §6.4：现阶段服务端不强校验 require_feature，"先激活再下载"由 App 端负责。
             * 清单那侧已经不给下载入口了，这里再挡一道 —— 直链响应里也带这个字段，
             * 它才是这次下载该不该放行的权威依据（清单可能是几分钟前缓存的）。
             * 判空必须走 nullableText：这个字段线上就是 null。
             */
            if (data != null && nullableText(data, "require_feature").length() > 0) {
                return errorJson("需先激活");
            }
            return null;
        } catch (org.json.JSONException e) {
            return errorJson("服务端返回的内容读不懂");
        }
    }

    private static String safeSlug(String rawSlug) {
        return rawSlug == null ? "" : rawSlug.replaceAll("[^A-Za-z0-9._-]", "");
    }

    /** 中转目录只是"下载→页面搬进 OPFS"的中转，攒多了要清，按最旧的先扔。 */
    private void pruneFontStore() {
        try {
            File dir = new File(getFilesDir(), FONT_STORE_DIR);
            File[] kids = dir.listFiles();
            if (kids == null || kids.length == 0) {
                return;
            }
            long total = 0L;
            for (File f : kids) {
                total += f.length();
            }
            if (total <= FONT_STORE_MAX_BYTES) {
                return;
            }
            java.util.Arrays.sort(kids, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : kids) {
                if (total <= FONT_STORE_MAX_BYTES) {
                    break;
                }
                total -= f.length();
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 边下边算 SHA256；返回小写 hex，任何失败都回 null。 */
    private String downloadTo(String url, File out) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream o = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            in = conn.getInputStream();
            o = new FileOutputStream(out);
            byte[] buf = new byte[1 << 16];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FONT_DOWNLOAD_BYTES) {
                    return null;
                }
                digest.update(buf, 0, n);
                o.write(buf, 0, n);
            }
            o.flush();
            return hexLower(digest.digest());
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(o);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hexLower(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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

        /**
         * 在线字体清单。签名通道复用激活码那套（五个必填头，缺 X-Client-Type 会被网关
         * 风控），服务端整包 JSON 原样转给页面，字段解释留在页面侧 —— 以后加字段不用动原生层。
         */
        @JavascriptInterface
        public void listFonts(final String callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = pickRaw(ActivationClient.resourcesList("font"), "字体清单");
                    } catch (Throwable t) {
                        json = errorJson(friendlyError(t));
                    }
                    deliverJson(callback, json);
                }
            }).start();
        }

        /**
         * 列一个字体包里的文件（服务端 v3：一个标识 = 一个包）。
         *
         * <p>为什么绕原生层而不是页面直接 fetch：① 清单和拉包接口都要 HMAC 签名，密钥只在
         * BuildConfig（local.properties 注入），绝不能进 Web 产物；② 对象存储直链的域名不在
         * WebView 的放行范围内，页面自己拉会被 CORS 和请求拦截双双挡掉。</p>
         */
        @JavascriptInterface
        public void packFiles(final String slug, final String callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = OpenCutActivity.this.packFiles(slug);
                    } catch (Throwable t) {
                        json = errorJson(friendlyError(t));
                    }
                    deliverJson(callback, json);
                }
            }).start();
        }

        /** 取包内某一个文件，成功后回站内路径 {@code /res-fonts/<校验值前缀-文件名>}。 */
        @JavascriptInterface
        public void packFile(final String slug, final int seq, final String callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = OpenCutActivity.this.packFile(slug, seq);
                    } catch (Throwable t) {
                        json = errorJson(friendlyError(t));
                    }
                    deliverJson(callback, json);
                }
            }).start();
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
