package com.mineways;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import com.mineways.assets.AssetPacks;
import com.mineways.assets.PackProgressDialog;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 4DGen 皮肤生成（XiaoYangx666/MCBE-4DGen，GPL-3.0）：把 PMX/VRM/GLB/OBJ 模型或图片转成
 * Minecraft 基岩版 4D 皮肤包。
 *
 * <p>站点 13 MB（其中 11.7 MB 是 libassimp 的 wasm），不随安装包分发：第一次打开时按需从
 * {@code AshenFlameAssets} 下载到 {@code filesDir/assets/4dgen}，之后离线可用。加载走虚拟域
 * {@code https://4dgen.local/}，静态文件先查下载版、再回落到 APK 内置的 {@code assets/4dgen}
 * （兼容以后想改回内置的情况）。
 */
public class FourDGenActivity extends AppCompatActivity {

    private static final String PACK_ID = "4dgen";
    private static final String DOMAIN = "4dgen.local";
    private static final String ORIGIN = "https://" + DOMAIN;
    private static final String ASSET_ROOT = "4dgen/";
    private static final int BG = 0xFF0B0B12;
    private static final int REQ_FILE = 4002;
    /** 第三方管理器给的裸 file:// 路径要复制成 content://，复制上限照抄剪辑器的口径 */
    private static final long MAX_UPLOAD_COPY_BYTES = 256L * 1024L * 1024L;

    /**
     * 页面导出皮肤包走 {@code URL.createObjectURL} + 临时 {@code <a download>} 点击。
     * WebView 里这条路径两头都不通：没有 DownloadListener 时点了什么也不会发生，
     * 加了 DownloadListener 也拿不到 {@code blob:} 的字节（它不是能被 openStream 的 URL）。
     * 所以在捕获阶段截住这类点击，自己把 blob 读成 base64 分块交给 {@link SaveBridge} 落盘。
     */
    private static final String SAVE_JS = "(function(){\n"
            + "if (window.__mwSaveOn || !window.MwSave) { return; }\n"
            + "window.__mwSaveOn = 1;\n"
            + "function b64(buf){\n"
            + "  var u = new Uint8Array(buf), s = '', C = 32768, i = 0;\n"
            + "  for (; i < u.length; i += C) { s += String.fromCharCode.apply(null, u.subarray(i, i + C)); }\n"
            + "  return btoa(s);\n"
            + "}\n"
            + "document.addEventListener('click', function (ev) {\n"
            + "  var t = ev.target;\n"
            + "  var a = t && t.closest ? t.closest('a[download]') : null;\n"
            + "  if (!a) { return; }\n"
            + "  var href = a.getAttribute('href') || '';\n"
            + "  if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) { return; }\n"
            + "  ev.preventDefault(); ev.stopPropagation();\n"
            + "  var name = a.getAttribute('download') || '4d-skin.zip';\n"
            + "  var id = '';\n"
            + "  try { id = MwSave.begin(name); } catch (e) { id = ''; }\n"
            + "  if (!id) { MwSave.bad('本机保存通道没起来'); return; }\n"
            + "  fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {\n"
            + "    return blob.arrayBuffer();\n"
            + "  }).then(function (buf) {\n"
            + "    var all = b64(buf), STEP = 524288, i = 0;\n"
            + "    function pump() {\n"
            + "      if (i >= all.length) { MwSave.end(id); return; }\n"
            + "      MwSave.write(id, all.slice(i, i + STEP));\n"
            + "      i += STEP;\n"
            + "      setTimeout(pump, 0);\n"
            + "    }\n"
            + "    pump();\n"
            + "  }).catch(function (e) { MwSave.fail(id, String(e)); });\n"
            + "}, true);\n"
            + "})();";


    private FrameLayout root;
    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> fileCallback;

    private File packDir() {
        return new File(new File(getFilesDir(), "assets"), PACK_ID);
    }

    private String entryUrl() {
        return ORIGIN + "/index.html";
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        goImmersive();

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(DOMAIN)
                .addPathHandler("/", new PackPathHandler(getAssets(), packDir()))
                .build();

        webView = new WebView(this);
        android.net.Uri openUri = com.mineways.open.OpenInbox.take(this);
        if (openUri != null) {
            com.mineways.open.WebOpenBridge.setPending(openUri,
                    com.mineways.open.OpenInbox.nameOf(this, openUri),
                    com.mineways.open.OpenInbox.mimeOf(this, openUri));
        }
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(BG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new SaveBridge(), "MwSave");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                if (com.mineways.open.WebOpenBridge.hasPending()) {
                    view.postDelayed(() -> com.mineways.open.WebOpenBridge.tryAutoTrigger(view), 800);
                }
                // 脚本自己带幂等标记，重复注入只是空转
                view.evaluateJavascript(SAVE_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                // 站内页面自己在虚拟域里跳；外链一律交给系统浏览器
                if (uri != null && DOMAIN.equals(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             android.webkit.ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (com.mineways.open.WebOpenBridge.consume(
                        params == null ? null : params.getAcceptTypes(), callback)) {
                    return true;
                }
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
                    // 页面支持拖一批文件 / 整个 ZIP 进来，选择器一律允许多选
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(pick, "选择模型或图片"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        root.addView(webView);

        loadOrFetch();
    }

    /**
     * 主题默认的 statusBarColor 会取 colorPrimary（#2E7D32 绿），横屏顶部就压着一条绿带，
     * 页面自己有顶栏，这条带纯属多余 —— 和剪辑页同一套解法（见 OpenCutActivity#goImmersive）。
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

    @Override
    public void onBackPressed() {
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
        super.onDestroy();
    }

    // ------------------------------------------------------------ 按需素材

    /** 下载版或内置版任一存在 index.html 就能打开。 */
    private boolean localAssetsReady() {
        if (new File(packDir(), "index.html").isFile()) return true;
        try (InputStream in = getAssets().open(ASSET_ROOT + "index.html")) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadOrFetch() {
        if (localAssetsReady()) {
            webView.loadUrl(entryUrl());
            return;
        }
        new Thread(() -> {
            final String[] mirror = new String[1];
            AssetPacks.Pack found = null;
            try {
                List<AssetPacks.Pack> packs = AssetPacks.fetchManifest(mirror);
                for (AssetPacks.Pack p : packs) {
                    if (PACK_ID.equals(p.id)) {
                        found = p;
                        break;
                    }
                }
            } catch (Throwable t) {
                runOnUiThread(() -> showError("拿不到资源清单", t.getMessage()));
                return;
            }
            if (found == null) {
                runOnUiThread(() -> showError("资源清单里没有 4DGen", "请稍后重试，或更新到最新版本。"));
                return;
            }
            final AssetPacks.Pack pack = found;
            runOnUiThread(() -> confirmPack(pack, mirror[0]));
        }, "4dgen-manifest").start();
    }

    private void confirmPack(AssetPacks.Pack pack, String mirror) {
        String msg = "模型解析引擎（约 " + pack.sizeText() + "，解压后约 "
                + AssetPacks.unpackedMb(pack) + " MB）不随安装包分发，需要单独下载一次。\n"
                + "来源：" + (mirror == null ? "GitHub" : mirror) + "\n\n"
                + "下载一次就够了，之后完全离线可用，文件不会上传到任何服务器。";
        new AlertDialog.Builder(this)
                .setTitle("需要下载一次素材")
                .setMessage(msg)
                .setPositiveButton("下载", (d, w) -> startDownload(pack))
                .setNegativeButton("返回", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void startDownload(AssetPacks.Pack pack) {
        final boolean[] cancel = {false};
        final PackProgressDialog progress = PackProgressDialog.show(this,
                "正在下载 4DGen 素材", () -> cancel[0] = true);
        new Thread(() -> {
            try {
                AssetPacks.install(getApplicationContext(), pack, new AssetPacks.Progress() {
                    @Override
                    public void onProgress(String stage, long done, long total) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || !progress.isAlive()) return;
                            progress.update(stage, done, total);
                        });
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancel[0];
                    }
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    if (!new File(packDir(), "index.html").isFile()) {
                        showError("素材包结构不对",
                                "解压完成，但没有找到 index.html。\n目录：" + packDir());
                        return;
                    }
                    Toast.makeText(this, "素材已就绪", Toast.LENGTH_SHORT).show();
                    webView.clearCache(true);
                    webView.loadUrl(entryUrl());
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    String m = t.getMessage();
                    if (m != null && m.contains("已取消")) {
                        Toast.makeText(this, "已取消下载", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        showError("下载失败", m);
                    }
                });
            }
        }, "4dgen-dl").start();
    }

    private void showError(String title, String detail) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail == null ? "未知错误" : detail)
                .setPositiveButton("重试", (d, w) -> loadOrFetch())
                .setNegativeButton("返回", (d, w) -> finish())
                .show();
    }

    // ------------------------------------------------------------ 文件选择

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            android.webkit.ValueCallback<Uri[]> cb = fileCallback;
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

    /** 单选在 data、多选在 clipData，两种都要摊平。 */
    private Uri[] chooserResult(android.content.Intent data) {
        java.util.List<Uri> out = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(viaProvider(u));
            }
        }
        if (out.isEmpty() && data.getData() != null) {
            out.add(viaProvider(data.getData()));
        }
        return out.toArray(new Uri[0]);
    }

    /**
     * MT 管理器这类第三方选择器给的是 {@code file:///storage/...} 裸路径，而本页面关掉了
     * {@code setAllowFileAccess(false)}，页面拿到它读不到字节（表现是「选了文件但没反应」）。
     * 复制进私有目录再换 FileProvider 的 content URI。
     */
    private Uri viaProvider(Uri raw) {
        if (raw == null || !"file".equals(raw.getScheme())) return raw;
        String path = raw.getPath();
        if (path == null) return raw;
        File src = new File(path);
        long size = src.isFile() ? src.length() : -1L;
        if (size <= 0L || size > MAX_UPLOAD_COPY_BYTES) return raw;
        try {
            File dir = new File(getFilesDir(), "uploads");
            if (!dir.isDirectory() && !dir.mkdirs()) return raw;
            File dst = new File(dir, System.currentTimeMillis() + "-" + safeName(src.getName()));
            try (java.io.InputStream in = new FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", dst);
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) return "upload";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 页面的 accept 里既有 {@code image/*} 这样的真 MIME，也有一串扩展名
     * （{@code .glb,.gltf,.vrm,.pmx,.obj,.zip}）。扩展名不是 MIME，原样塞进
     * EXTRA_MIME_TYPES 会让第三方选择器一个文件都不显示。认得的映射成 MIME 并补
     * octet-stream；碰到认不出的干脆不做类型过滤 —— 「看得见」优先于「筛得准」，
     * 格式由页面自己校验。
     */
    private static String[] mimeFilterFor(String[] accept) {
        if (accept == null || accept.length == 0) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean fromExtension = false;
        for (String raw : accept) {
            if (raw == null) continue;
            String item = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (item.isEmpty() || "*/*".equals(item) || "*".equals(item)) return null;
            if (item.indexOf('/') >= 0) {
                out.add(item);
                continue;
            }
            if (!item.startsWith(".")) return null;
            String mapped = MIME_BY_EXTENSION.get(item);
            if (mapped == null) return null;
            out.add(mapped);
            fromExtension = true;
        }
        if (out.isEmpty()) return null;
        if (fromExtension) out.add("application/octet-stream");
        return out.toArray(new String[0]);
    }

    private static final java.util.Map<String, String> MIME_BY_EXTENSION = new java.util.HashMap<>();

    static {
        MIME_BY_EXTENSION.put(".glb", "model/gltf-binary");
        MIME_BY_EXTENSION.put(".gltf", "model/gltf+json");
        // PMX/VRM/FBX/OBJ 没有统一 MIME，靠 octet-stream 兜住
        MIME_BY_EXTENSION.put(".vrm", "application/octet-stream");
        MIME_BY_EXTENSION.put(".pmx", "application/octet-stream");
        MIME_BY_EXTENSION.put(".pmd", "application/octet-stream");
        MIME_BY_EXTENSION.put(".fbx", "application/octet-stream");
        MIME_BY_EXTENSION.put(".obj", "application/octet-stream");
        MIME_BY_EXTENSION.put(".zip", "application/zip");
        MIME_BY_EXTENSION.put(".png", "image/png");
        MIME_BY_EXTENSION.put(".jpg", "image/jpeg");
        MIME_BY_EXTENSION.put(".jpeg", "image/jpeg");
    }

    // ------------------------------------------------------------ 导出落盘

    private final java.util.Map<String, File> saveTmp = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> saveName = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.io.OutputStream> saveOut = new java.util.concurrent.ConcurrentHashMap<>();
    /** 只在 UI 线程写；桥在 JS 线程被调，用它挡住「别的页面来敲 MwSave」。 */
    private volatile String pageUrl;

    /** 页面把 blob 分块 base64 送过来，这里拼成文件再交给 {@link #deliver}。 */
    private final class SaveBridge {

        @JavascriptInterface
        public String begin(String name) {
            if (pageUrl == null || !pageUrl.startsWith(ORIGIN)) {
                return "";
            }
            String id = Long.toString(System.nanoTime());
            try {
                File dir = new File(getCacheDir(), "4dgen-save");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return "";
                }
                java.io.OutputStream out = new java.io.FileOutputStream(new File(dir, id + ".bin"));
                saveTmp.put(id, new File(dir, id + ".bin"));
                saveOut.put(id, out);
                saveName.put(id, displayName(name));
                return id;
            } catch (Throwable t) {
                saveOut.remove(id);
                return "";
            }
        }

        @JavascriptInterface
        public void write(String id, String chunk) {
            java.io.OutputStream out = saveOut.get(id);
            if (out == null || chunk == null) {
                return;
            }
            try {
                out.write(Base64.decode(chunk, Base64.NO_WRAP));
            } catch (Throwable t) {
                abort(id, "写入失败：" + t.getMessage());
            }
        }

        @JavascriptInterface
        public void end(String id) {
            java.io.OutputStream out = saveOut.remove(id);
            File tmp = saveTmp.remove(id);
            String name = saveName.remove(id);
            if (out == null || tmp == null) {
                return;
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> deliver(tmp, name == null ? "4d-skin.zip" : name));
        }

        @JavascriptInterface
        public void fail(String id, String detail) {
            abort(id, detail);
        }

        @JavascriptInterface
        public void bad(String detail) {
            runOnUiThread(() -> Toast.makeText(FourDGenActivity.this,
                    "保存失败：" + detail, Toast.LENGTH_LONG).show());
        }

        private void abort(String id, String detail) {
            java.io.OutputStream out = saveOut.remove(id);
            File tmp = saveTmp.remove(id);
            saveName.remove(id);
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp != null) {
                tmp.delete();
            }
            runOnUiThread(() -> Toast.makeText(FourDGenActivity.this,
                    "保存失败：" + (detail == null ? "未知错误" : detail), Toast.LENGTH_LONG).show());
        }
    }

    /** 存进系统「下载」：Android 10+ 走 MediaStore（不用申请存储权限），更早的版本落到应用外部目录。 */
    private void deliver(File tmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeForName(name));
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/4DGen");
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("系统没给下载位置");
                }
                try (InputStream in = new FileInputStream(tmp);
                     java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    copyAll(in, out);
                }
                tmp.delete();
                saved(uri, "下载 / 4DGen / " + name, name);
                return;
            }
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "4DGen");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("建目录失败");
            }
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(tmp);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                copyAll(in, out);
            }
            tmp.delete();
            saved(androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", dst), dst.getAbsolutePath(), name);
        } catch (Throwable t) {
            tmp.delete();
            new AlertDialog.Builder(this)
                    .setTitle("皮肤包没能存下来")
                    .setMessage(t.getMessage() == null ? t.toString() : t.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private void saved(Uri uri, String where, String name) {
        new AlertDialog.Builder(this)
                .setTitle("已保存")
                .setMessage(name + "\n位置：" + where
                        + "\n\n改名为 .mcpack 后双击可直接导入 Minecraft；也可以现在发给别人。")
                .setPositiveButton("分享", (d, w) -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mimeForName(name));
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "分享皮肤包"));
                    } catch (Throwable t) {
                        Toast.makeText(this, "没有可用的分享目标", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private static String mimeForName(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if ("zip".equals(ext) || "mcpack".equals(ext)) {
            return "application/zip";
        }
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    /** 皮肤名可以带中文，只去掉不能进文件名的字符和路径分隔。 */
    private static String displayName(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
        if (s.isEmpty()) {
            s = "4d-skin";
        }
        return s.length() > 96 ? s.substring(0, 96) : s;
    }

    private static void copyAll(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    // ------------------------------------------------------------ 静态文件

    /**
     * 把 {@code https://4dgen.local/xxx} 映射到下载版目录，找不到再回落到 APK 内置的
     * {@code assets/4dgen/xxx}。目录式链接补 index.html。
     *
     * <p>MIME 必须自己写全：{@code .wasm} 给错类型，Vite 产物里的
     * {@code WebAssembly.instantiateStreaming} 会直接拒绝加载（4DGen 的模型解析全靠它）。
     */
    private static final class PackPathHandler implements WebViewAssetLoader.PathHandler {

        private final AssetManager assets;
        private final File extRoot;

        PackPathHandler(AssetManager assets, File extRoot) {
            this.assets = assets;
            this.extRoot = extRoot;
        }

        @Override
        public WebResourceResponse handle(String path) {
            String p = path == null ? "" : path;
            while (p.startsWith("/")) p = p.substring(1);
            if (p.isEmpty()) p = "index.html";

            InputStream in = open(p);
            String key = p;
            if (in == null && !p.contains(".")) {
                in = open(p + "/index.html");
                key = p + "/index.html";
            }
            if (in == null) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        null, new ByteArrayInputStream(new byte[0]));
            }
            WebResourceResponse res = new WebResourceResponse(mimeFor(key), null, in);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-cache");
            res.setResponseHeaders(headers);
            return res;
        }

        private InputStream open(String rel) {
            try {
                File file = new File(extRoot, rel);
                String base = extRoot.getCanonicalPath();
                if (file.getCanonicalPath().startsWith(base) && file.isFile()) {
                    return new FileInputStream(file);
                }
            } catch (Throwable ignored) {
            }
            try {
                return assets.open(ASSET_ROOT + rel);
            } catch (Throwable t) {
                return null;
            }
        }

        private static String mimeFor(String path) {
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            if ("wasm".equals(ext)) return "application/wasm";
            if ("js".equals(ext) || "mjs".equals(ext)) return "text/javascript";
            if ("css".equals(ext)) return "text/css";
            if ("json".equals(ext)) return "application/json";
            if ("html".equals(ext)) return "text/html";
            if ("svg".equals(ext)) return "image/svg+xml";
            if ("woff2".equals(ext)) return "font/woff2";
            if ("woff".equals(ext)) return "font/woff";
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return guessed == null ? "application/octet-stream" : guessed;
        }
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
