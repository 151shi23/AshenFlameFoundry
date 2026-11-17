package com.mineways;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置 Blockbench 模型编辑器。
 * <p>
 * 两种模式：
 * <ul>
 *   <li>{@code online}：加载官方在线版 https://web.blockbench.net/（需网络，功能完整）；</li>
 *   <li>{@code offline}：用 WebViewAssetLoader 加载打包进 assets 的离线版（无网也能建模）。</li>
 * </ul>
 * <p>
 * 导入导出兼容：
 * <ul>
 *   <li>导入：拦截 WebView 的 <input type=file>，打开系统文件选择器，选中的文件回填给页面；</li>
 *   <li>导出：一律写入用户可见的 {@code Download/MinewaysMobile} 目录（见下方保存链路）。</li>
 * </ul>
 * <p>
 * <b>保存链路（三层兜底，前者命中即不再往后走）</b>：
 * <ol>
 *   <li>内置自研插件 {@code mineways_saver} 接管 {@code Blockbench.export()}——网页版所有
 *       编解码器与第三方插件的统一出口，能直接拿到文件名与内容；</li>
 *   <li>同一插件再兜一层 {@code HTMLAnchorElement.prototype.dispatchEvent/click}：内核用的
 *       FileSaver 会创建<b>从不插入 DOM</b> 的 {@code <a download>} 再派发合成 click，
 *       游离节点的事件到不了 document，页面级捕获钩子永远收不到——这正是
 *       “提示导出成功却没有任何文件”的根因；</li>
 *   <li>原生 {@code DownloadListener} 若还看到 {@code blob:} 下载，就反向调用
 *       {@code MMSave.rescueBlob()} 请页面写盘，不再静默丢弃。</li>
 * </ol>
 * 落盘统一走 {@link BbBridge} 的分块接口（每块 240KB，避免超大 base64 压爆内存）：
 * Android 10+ 经 MediaStore 写入 {@code Download/MinewaysMobile}（无需存储权限、文件管理器可见），
 * Android 8/9 直写公共下载目录，无权限时退回应用私有 exports 目录，保证文件不会凭空消失。
 * <p>
 * 内置插件：页面加载完成后注入脚本，按 Blockbench 官方登记流程
 * {@code new Plugin().loadFromURL(url, false)} 装载内置插件（清单见 {@code loadBundledPlugins}
 * 里的 {@code ids} 数组：官方插件索引中 Minecraft 建模相关的高热度插件 +
 * 随 App 打包的本地增强插件（Mineways 保存助手、Full Physics Ragdoll、3D Pen）；
 * 该 API 会先登记占位实例，
 * 插件源里的 {@code Plugin.register(id)} 才能匹配）。联网时优先官方 CDN，无网时用打包进 assets
 * 的本地副本；拉取失败还会由 loadFromURL 自动回退到 IndexedDB 源码缓存。依赖 THREE 的插件在无 GL 时跳过。
 * <p>
 * 商店源：注入脚本会<b>中和 CDN 镜像</b>（{@code cdn_mirror}）。镜像 {@code blckbn.ch} 的 CORS 只放行
 * {@code https://web.blockbench.net}，而离线版页面 origin 是 {@code appassets.androidplatform.net}，
 * 在离线版里镜像永远取不到数据；偏偏内核在“索引拉取失败且 navigator.onLine 为真”时会自动开启镜像
 * 并持久化（需重启生效），一旦触发市场会永久消失。因此每次打开都会把 {@code api_path} 校正回 jsDelivr、
 * 把 {@code cdn_mirror} 持久化为 false，并在本次索引确已失败时补拉一次，保证市场入口可用。
 * <p>
 * 这些插件由本加载器<b>独占</b>负责：装载成功后会把它们从内核持久化的“已安装”列表
 * （{@code StateMemory.installed_plugins}）中摘除，否则内核启动时的 {@code H4()} 会再装一遍，
 * 同一插件被执行两次（onload/补丁跑两遍）会破坏内核、导致无法进入建模界面。
 * <p>
 * 应急开关：在应用导出目录放一个 {@code bb_plugins.json}（每次打开页面重新读取，无需重装）——
 * {@code {"enabled":false}} 全部不装载、{@code {"disabled":["插件id",…]}} 停用若干、
 * {@code {"only":["插件id",…]}} 只装列出的。每次装载的逐插件结果会写到
 * {@code bb_plugin_report.txt}（同目录），便于排查。
 * <p>
 * <b>注意：{@code assets/blockbench/} 下有多处针对本应用的「源码级」改动</b>——升级内置 Blockbench
 * 若整目录覆盖会静默丢失，重跑 {@code neteasemc/tools/patch_offline_blockbench.ps1} 即可恢复
 * （脚本幂等、自动备份、并用 node 做语法校验）：
 * <ol>
 *   <li>{@code dist/bundle.js} · 补丁 A：商店列表填充条件去掉 {@code navigator.onLine}
 *       （离线 WebView 会把它误报为 false，索引取到了列表仍被丢弃、市场入口消失）；</li>
 *   <li>{@code dist/bundle.js} · 补丁 B：导出 provider 的三处下载调用与 FileSaver 的锚点点击
 *       都优先走 Android 文件桥（{@code window.MMSaveNative}），失败才回退浏览器下载
 *       —— 这是「提示导出成功却无文件」在内核层的根治；</li>
 *   <li>{@code index.html} · 补丁 C：以 {@code <script defer>} 直接引入自研插件
 *       {@code plugins/mineways_saver/mineways_saver.js}（内置保存助手，不依赖注入脚本即生效）。</li>
 * </ol>
 */
public class BlockbenchActivity extends AppCompatActivity {

    /** Intent 参数：模式（online / offline）。 */
    public static final String EXTRA_MODE = "bb_mode";
    public static final String MODE_ONLINE = "online";
    public static final String MODE_OFFLINE = "offline";

    /** Intent 参数：本次打开只为抓取当前预设（关于页 → 设置分享 → Blockbench）。 */
    public static final String EXTRA_SHARE_CAPTURE = "bb_share_capture";

    private static final String LOG_TAG = "Blockbench";
    private static final String ASSET_BASE_URL =
            "https://appassets.androidplatform.net/assets/blockbench/index.html";
    private static final String ONLINE_URL = "https://web.blockbench.net/";

    /** 系统“下载”目录下的子目录名：导出的模型都落在这里，文件管理器可见。 */
    private static final String PUBLIC_FOLDER = "MinewaysMobile";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private File exportDir;
    private ValueCallback<Uri[]> filePathCallback;
    private WebViewAssetLoader assetLoader;

    /** 本次是否处于「抓取预设」模式；抓完直接回到分享页。 */
    private boolean shareCapture;
    /** 预设只抓一次，防止 onPageFinished 多次回调重复回传。 */
    private boolean captureSent;
    /** 导入的预设已回灌，避免重载后再次回灌造成死循环。 */
    private boolean presetApplied;

    /** 保存结果的状态条浮在网页上，看 8 秒就自己收回去，不长期挡页面。 */
    private final Runnable hideStatus = () -> {
        if (statusView != null) statusView.setVisibility(View.GONE);
    };

    /** 关掉弹窗后系统栏会自己回来，这里重新按独占全屏收掉（返回手势仍然可用，只是栏藏起来）。 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            EditorImmersive.hideBars(this);
        }
    }

    /** 分块保存会话：页面按块 append，最后一次 finish 发布到下载目录。 */
    private final Map<String, SaveSession> saveSessions = new ConcurrentHashMap<>();

    /** 文件选择结果（由 onShowFileChooser 发起的系统文件选择器返回）。 */
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (filePathCallback == null) return;
                        Uri[] uris = null;
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            if (result.getData().getClipData() != null) {
                                int n = result.getData().getClipData().getItemCount();
                                uris = new Uri[n];
                                for (int i = 0; i < n; i++) {
                                    uris[i] = result.getData().getClipData().getItemAt(i).getUri();
                                }
                            } else if (result.getData().getData() != null) {
                                uris = new Uri[]{result.getData().getData()};
                            }
                        }
                        filePathCallback.onReceiveValue(uris);
                        filePathCallback = null;
                    });

    /**
     * Android 8/9（API ≤ 28）把导出写进公共「下载」目录需要存储权限；
     * Android 10+ 走 MediaStore 完全不需要，所以这里仅对老系统发起申请。
     * 用户拒绝也不影响功能：文件会退回应用私有导出目录，绝不丢。
     */
    private final ActivityResultLauncher<String> legacyStoragePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && statusView != null) {
                    Toast.makeText(this, R.string.bb_storage_permission_hint,
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blockbench);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        boolean online = MODE_ONLINE.equals(mode);
        shareCapture = getIntent().getBooleanExtra(EXTRA_SHARE_CAPTURE, false);

        webView = findViewById(R.id.bb_webview);
        android.net.Uri openUri = com.mineways.open.OpenInbox.take(this);
        if (openUri != null) {
            com.mineways.open.WebOpenBridge.setPending(openUri,
                    com.mineways.open.OpenInbox.nameOf(this, openUri),
                    com.mineways.open.OpenInbox.mimeOf(this, openUri));
        }
        progressBar = findViewById(R.id.bb_progress);
        statusView = findViewById(R.id.bb_status);

        exportDir = new File(getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        // 离线模式用它拦截 https://appassets.androidplatform.net/assets/… 请求并返回 assets 内容；
        // 在线模式对非该域名的请求返回 null，由系统正常联网加载。
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // 没有标题栏了：网页独占整块屏幕，退出只走系统返回手势/返回键。
        // 但「有未保存改动先问一句」这条保护留着 —— 手势返回照样回主界面，只是别把作品悄悄丢掉。
        EditorImmersive.hideBars(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                exitWithUnsavedGuard();
            }
        });

        setupWebView(online);
        requestLegacyStorageIfNeeded();

        // 每次进入离线版都提示一次：本页为「123 离线改版」，已适配兼容插件
        if (!online) showOfflineNotice();
    }

    /** Android 8/9 上按需申请写公共「下载」目录的权限（Android 10+ 无需权限，直接跳过）。 */
    private void requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "request legacy storage failed", t);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(boolean online) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 解锁插件所需能力：允许本地文件/URI 访问、任意来源混合内容、图片缩放，
        // 使离线插件可读取内部存储、加载 file:// / content:// / blob: 资源。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 网页不校验 Origin，避免 WebViewAssetLoader 的 https 虚拟源被同源策略误伤
        s.setAllowUniversalAccessFromFileURLs(true);

        // 文件 Bridge（JavascriptInterface）：把页面导出 / 插件读写的文件内容传回并保存，
        // 并为页面提供从内部存储读写文件、目录、插件的能力，突破浏览器 Blob 限制。
        webView.addJavascriptInterface(new BbBridge(), "BBAndroid");
        // 设置分享：抓取 / 回灌 localStorage 预设（只在分享流程里被调用）
        webView.addJavascriptInterface(new ShareBridge(), "MWPreset");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                if (response != null) {
                    // 在线版页面（web.blockbench.net）也会跨域取内置插件与离线资源，
                    // 而 WebViewAssetLoader 默认不带 CORS 头，会被浏览器直接拦掉。
                    // 这里统一放行，让内置插件（含保存助手）在在线版同样能加载。
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    response.setResponseHeaders(headers);
                }
                return response;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (com.mineways.open.WebOpenBridge.hasPending()) {
                    view.postDelayed(() -> com.mineways.open.WebOpenBridge.tryAutoTrigger(view), 800);
                }
                injectExportHook();
                // 建模页是包内的桌面版网页，云端可以给它重排版面（CSS）或补交互（JS）
                AdaptInject.apply(BlockbenchActivity.this, view, url);
                if (shareCapture) {
                    if (captureSent) return;
                    captureSent = true;
                    // 稍等一下，确保内核已把设置写进 localStorage
                    view.postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        try {
                            view.evaluateJavascript(com.mineways.share.PresetBridge.captureScript(), null);
                        } catch (Throwable t) {
                            Log.w(LOG_TAG, "capture share preset failed", t);
                        }
                    }, 500);
                } else if (!presetApplied) {
                    applyPendingPreset(view);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePath,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (com.mineways.open.WebOpenBridge.consume(
                        fileChooserParams == null ? null : fileChooserParams.getAcceptTypes(), filePath)) {
                    return true;
                }
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = filePath;
                Intent chooser = null;
                try {
                    chooser = fileChooserParams.createIntent();
                } catch (Throwable ignored) {
                }
                if (chooser == null) {
                    chooser = new Intent(Intent.ACTION_GET_CONTENT);
                    chooser.setType("*/*");
                    chooser.addCategory(Intent.CATEGORY_OPENABLE);
                }
                try {
                    fileChooserLauncher.launch(Intent.createChooser(chooser, "选择模型 / 纹理文件"));
                } catch (Throwable t) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                // 正常情况已被保存助手（mineways_saver）在工作流最前端拦下，不会走到原生；
                // 一旦走到这里说明页面侧接管失败，反向请它把该 blob 写盘，避免静默丢文件。
                rescueBlobDownload(url);
                return;
            }
            Toast.makeText(this, R.string.bb_download_unsupported, Toast.LENGTH_LONG).show();
        });

        // 保持屏幕常亮，避免建模过程中熄屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (online) {
            webView.loadUrl(ONLINE_URL);
        } else {
            webView.loadUrl(ASSET_BASE_URL);
        }
    }

    /**
     * 导入预设的回灌：把待应用槽里的 localStorage 快照写回页面，然后重载一次让内核重新读设置。
     * 只做一次（{@link #presetApplied} 守卫），重载后的 onPageFinished 不会再触发。
     */
    private void applyPendingPreset(WebView view) {
        try {
            String body = com.mineways.share.PresetBridge.takePending(
                    this, com.mineways.share.PresetBridge.BLOCKBENCH);
            if (body == null) return;
            presetApplied = true;
            view.evaluateJavascript(com.mineways.share.PresetBridge.applyScript(body), value -> {
                try {
                    view.reload();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            Log.w(LOG_TAG, "apply share preset failed", t);
        }
    }

    /** 设置分享桥：抓取完成后带着预设回到分享页。 */
    private final class ShareBridge {
        @JavascriptInterface
        public void deliver(final String json) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Intent it = new Intent(BlockbenchActivity.this, com.mineways.share.ShareActivity.class);
                it.putExtra(com.mineways.share.ShareActivity.EXTRA_MODE,
                        com.mineways.share.ShareActivity.MODE_EXPORT);
                it.putExtra(com.mineways.share.ShareActivity.EXTRA_PRESET_ID,
                        com.mineways.share.PresetBridge.BLOCKBENCH);
                it.putExtra(com.mineways.share.ShareActivity.EXTRA_PRESET_BODY,
                        json == null ? "{}" : json);
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
                finish();
            });
        }
    }

    /**
     * 兜底通道：页面里有 blob: 下载逃过了 JS 接管（例如插件直接调用 FileSaver），
     * 就反向调用保存助手的 {@code MMSave.rescueBlob(url)}，把内容取回并写入下载目录。
     */
    private void rescueBlobDownload(String url) {
        try {
            String js = "(function(){try{var m=window.MMSave;"
                    + "if(!m||typeof m.rescueBlob!=='function')return 'no-hook';"
                    + "return m.rescueBlob(" + JSONObject.quote(url) + ")?'ok':'no-blob';"
                    + "}catch(e){return 'err';}})()";
            webView.evaluateJavascript(js, value -> {
                if (value == null) return;
                if (value.contains("no-hook") || value.contains("no-blob")) {
                    Toast.makeText(this, R.string.bb_save_helper_missing, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable t) {
            Log.w(LOG_TAG, "rescue blob download failed", t);
        }
    }

    /**
     * 注入脚本，五件事：
     * <ol>
     *   <li>引入自研保存助手 {@code mineways_saver}（经典 script 标签，不受 CORS 限制，在线版也能装上）；</li>
     *   <li>中和 CDN 镜像，保证商店索引与市场入口可用；</li>
     *   <li>兜底拦截 <a href="#">blob:</a> 下载链接并写盘（正常路径已被保存助手接管）；</li>
     *   <li>暴露 AndroidFS 文件桥，供插件/页面读写应用 exports 目录；</li>
     *   <li>按官方登记流程加载内置插件（联网优先官方 CDN，无网用本地副本 + IndexedDB 缓存）。</li>
     * </ol>
     * 脚本在每次页面加载完成（onPageFinished）后执行，保证 SPA 重载后仍生效。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void injectExportHook() {
        try {
            String js =
                    "(function(){" +
                    "if(window.__bbExportLoaded)return;window.__bbExportLoaded=true;" +
                    "var bridge=window.BBAndroid;if(!bridge)return;" +
                    // 保存助手（自研插件）必须最先到位：用经典 <script> 标签引入，
                    // 经典脚本不受 CORS 限制，在线版页面里也能装上；插件源内部有二次求值保护，
                    // 稍后加载器按官方流程再装载一次（那次才真正登记插件），不会重复挂钩子。
                    "function ensureSaver(){" +
                    "  try{" +
                    "    if(window.__mmSaverLoaded)return;" +
                    "    if(document.getElementById('mineways-saver-script'))return;" +
                    "    var s=document.createElement('script');" +
                    "    s.id='mineways-saver-script';" +
                    "    s.src='https://appassets.androidplatform.net/assets/blockbench/plugins/mineways_saver/mineways_saver.js';" +
                    "    s.onerror=function(){console.warn('[BB]保存助手脚本注入失败');};" +
                    "    (document.head||document.documentElement).appendChild(s);" +
                    "  }catch(e){}" +
                    "}" +
                    "ensureSaver();" +
                    // ---- 中和“CDN 镜像”，保证离线版的商店源始终可用 ----
                    // 镜像 blckbn.ch 的 CORS 只放行 https://web.blockbench.net，而离线版页面的 origin 是
                    // https://appassets.androidplatform.net → 镜像在离线版里永远取不到数据。偏偏内核在
                    // “商店索引拉取失败且 navigator.onLine 为真”时会自动开启镜像并持久化（ta.cdn_mirror.set(true)，
                    // 需重启生效），一旦触发市场就会永久消失（正好抵消“强开市场”）。因此这里：
                    //   ① 立刻把内存中的 api_path/path 校正回 jsDelivr；
                    //   ② 改写 localStorage 的 settings，让每次打开都走 jsDelivr；
                    //   ③ 若本次索引确已失败（loading_promise 已结束且 St.json 仍为空），补拉一次，
                    //      使误开镜像后第一次打开也能看到市场（此时内核已不会再填充，不会产生重复实例）。
                    "function neutralizeStoreSource(){" +
                    "  var JSD='https://cdn.jsdelivr.net/gh/JannisX11/blockbench-plugins/plugins';" +
                    "  var P=window.Plugins;" +
                    "  try{if(P&&P.api_path&&P.api_path.indexOf('blckbn.ch')>=0){P.api_path=JSD;P.path=JSD+'/';}}catch(e){}" +
                    "  try{" +
                    "    var raw=localStorage.getItem('settings'),o=raw?JSON.parse(raw):{};" +
                    "    if(!o||typeof o!=='object')o={};" +
                    "    if(!o.cdn_mirror||o.cdn_mirror.value!==false){" +
                    "      o.cdn_mirror={value:false};localStorage.setItem('settings',JSON.stringify(o));" +
                    "    }" +
                    "  }catch(e){}" +
                    "  try{" +
                    "    var C=window.Plugin;" +
                    "    if(!P||!C||P.loading_promise||P.json instanceof Object)return;" +
                    "    var x=new XMLHttpRequest();" +
                    "    x.open('GET',P.api_path+'.json',true);x.timeout=8000;" +
                    "    x.onreadystatechange=function(){" +
                    "      if(x.readyState!==4||!(x.status>=200&&x.status<400))return;" +
                    "      try{" +
                    "        var d=JSON.parse(x.responseText);" +
                    "        if(!(d instanceof Object))return;" +
                    "        P.json=d;" +
                    "        for(var id in d){" +
                    "          try{if(!P.all.some(function(q){return q&&q.id===id;}))new C(id,d[id]);}catch(e){}" +
                    "        }" +
                    "        if(typeof P.sort==='function')P.sort();" +
                    "        console.log('[BB]商店索引已由 jsDelivr 补拉，市场入口可用');" +
                    "      }catch(e){}" +
                    "    };" +
                    "    x.send();" +
                    "  }catch(e){}" +
                    "}" +
                    "neutralizeStoreSource();" +
                    // ---- 统一导出 hooks：拦截 <a download> 与 blob URL ----
                    "function doDownload(href,name){fetch(href).then(function(r){return r.blob();}).then(function(blob){" +
                    "  blob.arrayBuffer().then(function(buf){" +
                    "    var u=new Uint8Array(buf),CH=8192,bin='';" +
                    "    for(var i=0;i<u.length;i+=CH){" +
                    "      bin+=String.fromCharCode.apply(null,u.subarray(i,i+CH));" +
                    "    }" +
                    "    var b64=btoa(bin);" +
                    "    try{bridge.onExport(name,b64,'');}catch(err){console.log(err);}" +
                    "  });" +
                    "}).catch(function(){});}" +
                    "document.addEventListener('click',function(e){" +
                    "  var a=e.target.closest?e.target.closest('a'):null;if(!a)return;" +
                    "  var href=a.href||'';" +
                    "  if(href.indexOf('blob:')===0 && a.hasAttribute('download')){" +
                    "    e.preventDefault();e.stopPropagation();" +
                    "    var raw=a.getAttribute('download')||'model';var name=raw;" +
                    "    try{name=decodeURIComponent(raw);}catch(x){}" +
                    "    doDownload(href,name);return;" +
                    "  }" +
                    "},true);" +
                    // ---- 暴露原生文件桥，供插件/页面读写应用 exports 目录 ----
                    "if(!window.AndroidFS){" +
                    "  window.AndroidFS={" +
                    "    writeFile:function(p,b64){return bridge.writeFile(p,b64);}," +
                    "    readFile:function(p){return bridge.readFile(p);}," +
                    "    listFiles:function(p){return bridge.listFiles(p||'');}," +
                    "    clearCache:function(){bridge.clearPluginCache();}" +
                    "  };" +
                    "  Object.defineProperty(window,'AndroidFS',{value:window.AndroidFS,writable:false,configurable:false});" +
                    "}" +
                    // ---- 自动内置 Blockbench 官方/实用插件 ----
                    // 必须走官方登记流程 new Plugin().loadFromURL(url,false)，不能 fetch 源码后自行 eval：
                    // loadFromURL 会先 this.id=pathToName(url) 并 St.registered[this.id]=this 登记占位实例，
                    // 插件源里的 Plugin.register(id) 才能取到该实例（否则弹 load_plugin_failed）。
                    // 传 false：跳过确认框，且不触发插件的 oninstall（避免弹出 N 个欢迎框）。
                    // 另外 loadFromURL 内部在拉取失败时会自动回退 IndexedDB 源码缓存（plugin_sources）。
                    // 注意：url 的文件名必须等于插件注册 ID（本项目内置插件已逐一核对一致）。
                    "function loadBundledPlugins(){" +
                    "  if(window.__bbPluginsLoaded)return;window.__bbPluginsLoaded=true;" +
                    "  var PluginCtor=window.Plugin,St=window.Plugins;if(!PluginCtor)return;" +
                    "  var LOCAL='https://appassets.androidplatform.net/assets/blockbench/plugins/';" +
                    // 官方 CDN 基址候选：优先 bundle 内的 api_path（尊重用户的 CDN 镜像设置），再补 jsDelivr。
                    // 两者 CORS 策略不同：jsDelivr 返回 Access-Control-Allow-Origin:*（任意来源可跨域），
                    // 镜像 blckbn.ch 只放行 https://web.blockbench.net，故离线版页面需靠 jsDelivr 兜底。
                    "  var CDNS=[];" +
                    "  if(St&&St.api_path)CDNS.push(St.api_path+'/');" +
                    "  var JSD='https://cdn.jsdelivr.net/gh/JannisX11/blockbench-plugins/plugins/';" +
                    "  if(CDNS.indexOf(JSD)<0)CDNS.push(JSD);" +
                    // 依赖全局 THREE 的插件：仅当 THREE 可用时加载，避免离线报错
                    "  var hasGL=!!window.THREE;" +
                    // 清单：['插件id', 是否依赖 THREE]。第二项只在内核未暴露 window.THREE 时用于跳过，
                    // 本版内核已暴露（Object.assign(window,{...,THREE:...})），因此当前不影响加载。
                    // 新增来源：官方插件索引按下载量筛选的 Minecraft 建模相关高热度插件。
                    // 只从本地 assets 取的插件（自研 / 不对外发布，官方 CDN 上并不存在）：
                    // 即便联网也直接使用打包副本，免得白发一次注定 404 的请求。
                    "  var LOCAL_ONLY=['mineways_saver'"
                            + (RecentToolFlags.bbRagdoll() ? ",'full_physics_ragdoll'" : "")
                            + (RecentToolFlags.bbPen3d() ? ",'pen_3d'" : "") + "];" +
                    "  var ids=[" +
                    // —— 本机保存助手（自研：修复网页版“导出提示成功但磁盘无文件”）——
                    "    ['mineways_saver',0]," +
                    // —— 本地内置增强（不在官方插件索引里，仅随 App 打包）——
                    (RecentToolFlags.bbRagdoll() ? "    ['full_physics_ragdoll',1]," : "") +
                    // 3D Pen（MIT，PuddingKC）：路径建模 / 管道 / 沿路径盖印
                    (RecentToolFlags.bbPen3d() ? "    ['pen_3d',0]," : "") +
                    // —— Mojang 官方 Wizard ——
                    "    ['minecraft_block_wizard',0]," +
                    "    ['minecraft_entity_wizard',0]," +
                    "    ['minecraft_item_wizard',0]," +
                    // —— 格式支持（模组/平台） ——
                    "    ['geckolib',0]," +
                    "    ['meshy',0]," +
                    "    ['easy_model_entities',0]," +
                    "    ['emf_animation_addon',0]," +
                    "    ['structure_to_model',0]," +
                    "    ['voxel_shape_generator',0]," +
                    "    ['player_statue_generator',0]," +
                    "    ['seat_position',0]," +
                    // —— 导入/导出 ——
                    "    ['gltf_importer',1]," +
                    "    ['cem_template_loader',0]," +
                    "    ['vox_importer',0]," +
                    "    ['resourcepack_packager',0]," +
                    // —— 动画/绑定 ——
                    "    ['animated_java',1]," +
                    "    ['animation_utils',0]," +
                    "    ['animation_sliders',0]," +
                    "    ['bone_view',1]," +
                    // —— 建模辅助 ——
                    "    ['mesh_tools',1]," +
                    "    ['shape_generator',0]," +
                    "    ['outline_creator',0]," +
                    "    ['minecraft_title_generator',0]," +
                    "    ['asset_browser',0]," +
                    // —— 纹理/UV ——
                    "    ['uv_locker',1]," +
                    "    ['texture_stitcher',0]," +
                    "    ['colour_gradient_generator',0]," +
                    "    ['baked_ambient_occlusion',0]," +
                    "    ['transparency_fix',0]," +
                    "    ['plaster',0]," +
                    // —— 资源包 ——
                    "    ['resource_pack_utilities',0]" +
                    "  ];" +
                    // 可选配置 exports/bb_plugins.json（放在导出目录里，随时可改；每次打开页面重新读取）：
                    //   {"enabled":false}      全部不自动装载
                    //   {"disabled":["id",…]}  停用若干
                    //   {"only":["id",…]}      只装载列出的
                    // 某个插件导致界面异常时，写这个文件再重开 Blockbench 即可恢复，无需重新打包。
                    "  var cfg={};" +
                    "  try{var cfgRaw=bridge.readFile('bb_plugins.json');" +
                    "    if(cfgRaw&&cfgRaw.indexOf('ERR:')!==0)cfg=JSON.parse(atob(cfgRaw))||{};}catch(e){}" +
                    "  function allowed(id){" +
                    "    if(cfg.enabled===false)return false;" +
                    "    if(cfg.only instanceof Array&&cfg.only.length)return cfg.only.indexOf(id)>=0;" +
                    "    if(cfg.disabled instanceof Array&&cfg.disabled.indexOf(id)>=0)return false;" +
                    "    return true;" +
                    "  }" +
                    // 内置插件由本加载器“独占”负责，因此要先抹掉内核持久化的“已安装”记录：
                    // loadFromURL 成功时会 St.installed.push + localStorage 持久化，内核启动时 H4() 会把这些
                    // 记录再装一遍（plugin_await_loading_timeout 默认只等 2s，装载与建模界面初始化重叠），
                    // 与这里的装载叠加后同一插件会被执行两次（onload/补丁跑两遍）→ 破坏内核。
                    "  function persistInstalled(){" +
                    "    try{if(window.StateMemory&&window.StateMemory.save)window.StateMemory.save('installed_plugins');}catch(e){}" +
                    "  }" +
                    "  function forget(id){" +
                    "    try{" +
                    "      if(!St||!St.installed)return;" +
                    "      for(var k=St.installed.length-1;k>=0;k--){" +
                    "        if(St.installed[k]&&St.installed[k].id===id)St.installed.splice(k,1);" +
                    "      }" +
                    "    }catch(e){}" +
                    "  }" +
                    // 已被内核装载过（H4 抢在前面）或本次已装好的，不要再装
                    "  function alreadyLoaded(id){" +
                    "    try{return !!(St&&St.all&&St.all.some(function(q){return q&&q.id===id&&q.installed;}));}" +
                    "    catch(e){return false;}" +
                    "  }" +
                    // 已下架插件：源码和清单都删了，但旧版本可能把它写进 St.installed，
                    // 内核启动时仍会按持久化记录重新装一遍 —— 这里一并抹掉。
                    "  var RETIRED=['full_immersion_preview'];" +
                    // 尽早同步清空，让内核的 H4() 无论何时遍历都看不到这些记录
                    "  function sweepInstalled(){ids.forEach(function(p){forget(p[0]);});RETIRED.forEach(forget);persistInstalled();}" +
                    "  sweepInstalled();" +
                    // H4() 要等“插件索引”请求返回后才开始遍历，可能晚于本次注入、并把记录写回，
                    // 因此开局再补扫几次，确保旧版本残留的持久化记录被彻底清掉（之后每次启动都干净）。
                    "  [1500,3000,5000,8000].forEach(function(t){setTimeout(sweepInstalled,t);});" +
                    // 候选源：联网时官方 CDN 优先（探针命中的那个排最前，各 CDN 依次试新仓库格式 -> 旧格式），
                    // 本地内置始终兜底
                    "  function sources(id,online,preferred){" +
                    "    var l=[];" +
                    // 自研插件只有本地副本（CDN 上没有），不必做注定失败的探测
                    "    if(LOCAL_ONLY.indexOf(id)>=0)return [LOCAL+id+'/'+id+'.js'];" +
                    "    if(online){" +
                    "      var order=[];" +
                    "      if(preferred>=0&&preferred<CDNS.length)order.push(CDNS[preferred]);" +
                    "      CDNS.forEach(function(b,i){if(i!==preferred)order.push(b);});" +
                    "      order.forEach(function(b){l.push(b+id+'/'+id+'.js');l.push(b+id+'.js');});" +
                    "    }" +
                    "    l.push(LOCAL+id+'/'+id+'.js');" +
                    "    return l;" +
                    "  }" +
                    // 加载失败（且无 IndexedDB 缓存可用）时清掉半注册实例，避免残留在插件列表里
                    "  function discard(p,id){" +
                    "    try{" +
                    "      if(St&&St.all&&St.all.remove)St.all.remove(p);" +
                    "      if(St&&St.registered&&St.registered[id]===p)delete St.registered[id];" +
                    "    }catch(e){}" +
                    "    return false;" +
                    "  }" +
                    "  function attempt(id,url){" +
                    "    var p;try{p=new PluginCtor();}catch(e){return Promise.resolve(false);}" +
                    "    var r;try{r=p.loadFromURL(url,false);}catch(e){return Promise.resolve(discard(p,id));}" +
                    "    return Promise.resolve(r).then(" +
                    "      function(){return p.installed?true:discard(p,id);}," +
                    "      function(){return discard(p,id);});" +
                    "  }" +
                    "  function trySource(id,list,i){" +
                    "    if(i>=list.length)return Promise.resolve(false);" +
                    "    return attempt(id,list[i]).then(function(ok){return ok?true:trySource(id,list,i+1);});" +
                    "  }" +
                    // 网络探测：逐个 HEAD 探针各 CDN，命中第一个可达的作为首选；全部不可达则只用本地内置。
                    // 注意：不能把 navigator.onLine===false 当硬闸门 —— 本应用 WebView 里它可能误报为 false
                    // （正是离线版商店列表被它挡掉的那类问题），这里只拿它来缩短超时，探针仍会真发一次。
                    "  function probe(cb){" +
                    "    var timeout=(navigator.onLine===false)?1200:2500;" +
                    "    var i=0;" +
                    "    (function step(){" +
                    "      if(i>=CDNS.length){cb(false,-1);return;}" +
                    "      var idx=i++,base=CDNS[idx],done=false;" +
                    "      var fin=function(ok){if(done)return;done=true;ok?cb(true,idx):step();};" +
                    "      try{" +
                    "        var x=new XMLHttpRequest();" +
                    "        x.open('HEAD',base+'uv_locker/uv_locker.js',true);" +
                    "        x.timeout=timeout;" +
                    "        x.onreadystatechange=function(){if(x.readyState===4)fin(x.status>=200&&x.status<400);};" +
                    "        x.ontimeout=function(){fin(false);};" +
                    "        x.onerror=function(){fin(false);};" +
                    "        x.send();" +
                    "      }catch(e){fin(false);}" +
                    "    })();" +
                    "  }" +
                    "  probe(function(online,preferred){" +
                    "    var srcName=online?('cdn '+CDNS[preferred]):'local';" +
                    "    console.log('[BB]内置插件源：'+srcName+'（失败自动回退 IndexedDB 缓存）');" +
                    "    var report=[],chain=Promise.resolve();" +
                    "    ids.forEach(function(p){chain=chain.then(function(){" +
                    "      var id=p[0];" +
                    "      if(p[1]&&!hasGL){report.push(id+' skip no-gl');return;}" +
                    "      if(!allowed(id)){report.push(id+' skip config');return;}" +
                    "      if(alreadyLoaded(id)){report.push(id+' skip already-loaded');return;}" +
                    "      return trySource(id,sources(id,online,preferred),0).then(function(ok){" +
                    "        report.push(id+(ok?' ok':' fail'));" +
                    "        if(ok){console.log('[BB]内置插件已加载:',id);forget(id);persistInstalled();}" +
                    "        else console.warn('[BB]内置插件加载失败:',id);" +
                    // 每个插件之间让出一次主线程：12 个插件合计约 8MB，连续编译会把界面卡住
                    "        return new Promise(function(r){setTimeout(r,50);});" +
                    "      }).catch(function(e){report.push(id+' error');console.warn('[BB]内置插件异常:',id,e);});" +
                    "    });});" +
                    "    chain.then(function(){" +
                    "      sweepInstalled();" +
                    "      var head='Blockbench '+((window.Blockbench&&window.Blockbench.version)||'?')+' | source='+srcName+'\\n';" +
                    "      var text=head+report.join('\\n');" +
                    "      console.log('[BB]内置插件处理完成:\\n'+text);" +
                    // 结果落盘到导出目录，便于排查（快捷工具 -> 打开导出目录）
                    "      try{bridge.writeFile('bb_plugin_report.txt',btoa(text));}catch(e){}" +
                    "    });" +
                    "  });" +
                    "}" +
                    "if(window.setup_successful||document.getElementById('work_screen')){setTimeout(loadBundledPlugins,800);}" +
                    "else{window.addEventListener('blockbench_ready',loadBundledPlugins,{once:true});}" +
                    "setTimeout(function(){if(!window.__bbPluginsLoaded){loadBundledPlugins();}},4000);" +
                    "})();";
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "inject hook failed", t);
        }
    }

    /** 供 JS 调用的文件导出 / 读写端口。 */
    private final class BbBridge {
        /**
         * 一次性导出（旧接口，保留兼容）：内容一次性传回后发布到用户可见的下载目录。
         * 新代码建议改用 {@link #beginSave}/{@link #appendSave}/{@link #finishSave} 分块写入，
         * 避免超大 base64 字符串同时占满 WebView 与原生两侧的内存。
         */
        @android.webkit.JavascriptInterface
        public void onExport(String fileName, String base64, String mimeType) {
            final String name = sanitize(fileName);
            final String data = base64;
            final String mime = (mimeType == null || mimeType.isEmpty()) ? guessMime(name) : mimeType;
            new Thread(() -> {
                File temp = null;
                try {
                    temp = newTempFile();
                    byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(temp), 64 * 1024)) {
                        os.write(bytes);
                    }
                    notifySaved(publishToDownloads(temp, name, mime), bytes.length);
                } catch (Throwable t) {
                    notifySaveFailed(t);
                } finally {
                    if (temp != null) temp.delete();
                }
            }, "bb-export").start();
        }

        /** 开启一次分块保存，返回会话 id（失败返回 {@code ERR:原因}）。 */
        @android.webkit.JavascriptInterface
        public String beginSave(String fileName, String mimeType) {
            try {
                String name = sanitize(fileName);
                File temp = newTempFile();
                SaveSession session = new SaveSession(temp, name,
                        (mimeType == null || mimeType.isEmpty()) ? guessMime(name) : mimeType);
                session.out = new BufferedOutputStream(new FileOutputStream(temp), 64 * 1024);
                String id = Long.toHexString(System.nanoTime())
                        + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
                saveSessions.put(id, session);
                return id;
            } catch (Throwable t) {
                return "ERR:" + t.getMessage();
            }
        }

        /** 追加一块 base64 内容，返回 {@code OK} 或 {@code ERR:原因}。 */
        @android.webkit.JavascriptInterface
        public String appendSave(String id, String base64) {
            SaveSession session = saveSessions.get(id);
            if (session == null) return "ERR:保存会话不存在或已结束";
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                session.out.write(bytes);
                session.bytes += bytes.length;
                return "OK";
            } catch (Throwable t) {
                return "ERR:" + t.getMessage();
            }
        }

        /**
         * 结束保存并发布到下载目录，返回可读路径（形如 {@code Download/MinewaysMobile/模型.bbmodel}）。
         */
        @android.webkit.JavascriptInterface
        public String finishSave(String id) {
            SaveSession session = saveSessions.remove(id);
            if (session == null) return "ERR:保存会话不存在或已结束";
            try {
                session.out.flush();
                session.out.close();
                session.out = null;
                String display = publishToDownloads(session.temp, session.name, session.mime);
                notifySaved(display, session.bytes);
                return display;
            } catch (Throwable t) {
                notifySaveFailed(t);
                return "ERR:" + t.getMessage();
            } finally {
                session.temp.delete();
            }
        }

        /** 放弃一次保存并删除临时文件。 */
        @android.webkit.JavascriptInterface
        public void abortSave(String id) {
            SaveSession session = saveSessions.remove(id);
            if (session == null) return;
            try {
                if (session.out != null) session.out.close();
            } catch (Throwable ignored) {
            }
            session.temp.delete();
        }

        /** 新建一个位于缓存目录的临时文件，用于接收分块内容。 */
        private File newTempFile() throws IOException {
            File dir = new File(getCacheDir(), "bb_upload");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建临时目录");
            return new File(dir, System.nanoTime() + ".part");
        }

        /**
         * 把临时文件发布到用户可见的「下载 / MinewaysMobile」目录。
         * <p>
         * Android 10+ 走 MediaStore，无需任何存储权限，文件管理器直接可见；
         * Android 8/9 直写公共下载目录，未授予存储权限时退回应用私有 exports 目录，
         * 保证文件一定落地（只是位置不同）。
         *
         * @return 可读路径：发布到下载目录时为相对路径，退回私有目录时为绝对路径
         */
        private String publishToDownloads(File src, String name, String mime) throws IOException {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("无法在“下载”目录创建文件");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IOException("无法写入“下载”目录");
                    copy(src, os);
                } catch (Throwable t) {
                    try {
                        cr.delete(uri, null, null);
                    } catch (Throwable ignored) {
                    }
                    throw (t instanceof IOException) ? (IOException) t
                            : new IOException(String.valueOf(t.getMessage()), t);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, values, null, null);
                String actual = queryDisplayName(cr, uri);
                return Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER + "/"
                        + (actual == null ? name : actual);
            }
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), PUBLIC_FOLDER);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("下载目录不可写");
                File out = uniqueFile(dir, name);
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
                    copy(src, os);
                }
                return Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER + "/" + out.getName();
            } catch (Throwable t) {
                File out = uniqueFile(exportDir, name);
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
                    copy(src, os);
                }
                return out.getAbsolutePath();
            }
        }

        /** 读取 MediaStore 最终采用的文件名（重名时系统会自动改名）。 */
        private String queryDisplayName(ContentResolver cr, Uri uri) {
            try (Cursor c = cr.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** 同目录下取一个不冲突的文件名。 */
        private File uniqueFile(File dir, String name) {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            if (!f.exists()) return f;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i < 1000; i++) {
                File cand = new File(dir, base + " (" + i + ")" + ext);
                if (!cand.exists()) return cand;
            }
            return new File(dir, base + "-" + System.currentTimeMillis() + ext);
        }

        private void copy(File src, OutputStream os) throws IOException {
            try (InputStream in = new java.io.FileInputStream(src)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            os.flush();
        }

        private void notifySaved(String display, long bytes) {
            final String msg = getString(R.string.bb_export_saved, display, formatSize(bytes));
            runOnUiThread(() -> {
                // 状态条现在是浮在网页上的覆盖层，看一会儿就自己收起来，不长期挡页面
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(msg);
                statusView.removeCallbacks(hideStatus);
                statusView.postDelayed(hideStatus, 8000);
                Toast.makeText(BlockbenchActivity.this, msg, Toast.LENGTH_LONG).show();
            });
        }

        private void notifySaveFailed(Throwable t) {
            final String msg = getString(R.string.bb_export_failed, String.valueOf(t.getMessage()));
            runOnUiThread(() -> Toast.makeText(BlockbenchActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        /** 把一段 base64 内容以指定相对文件名写入应用外部 exports 目录，返回绝对路径。 */
        @android.webkit.JavascriptInterface
        public String writeFile(String relativePath, String base64Content) {
            try {
                File f = resolveSafe(exportDir, relativePath);
                byte[] bytes = Base64.decode(
                        base64Content.startsWith("data:") && base64Content.indexOf(',') > 0
                                ? base64Content.substring(base64Content.indexOf(',') + 1)
                                : base64Content,
                        Base64.DEFAULT);
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                return f.getAbsolutePath();
            } catch (Throwable t) {
                return "ERR:" + t.getMessage();
            }
        }

        /** 以 base64 读取应用外部 exports 目录下的一个文件，供插件读取模型/纹理等。 */
        @android.webkit.JavascriptInterface
        public String readFile(String relativePath) {
            try {
                File f = resolveSafe(exportDir, relativePath);
                if (!f.isFile()) return "ERR:not-found";
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                return Base64.encodeToString(bytes, Base64.DEFAULT);
            } catch (Throwable t) {
                return "ERR:" + t.getMessage();
            }
        }

        /** 列出 exports 目录（及可选子目录）下的文件名，供插件浏览文件。 */
        @android.webkit.JavascriptInterface
        public String listFiles(String subPath) {
            try {
                File base = subPath == null ? exportDir : resolveSafe(exportDir, subPath);
                File[] kids = base.listFiles();
                if (kids == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < kids.length; i++) {
                    if (i > 0) sb.append(",");
                    File k = kids[i];
                    sb.append("{\"name\":\"").append(escapeJson(k.getName()))
                      .append("\",\"dir\":").append(k.isDirectory()).append("}");
                }
                return sb.append("]").toString();
            } catch (Throwable t) {
                return "ERR:" + t.getMessage();
            }
        }

        /** 删除内部插件缓存目录，便于重装。 */
        @android.webkit.JavascriptInterface
        public void clearPluginCache() {
            File cache = new File(getCacheDir(), "bb_plugins");
            deleteRecursiveBlockbench(cache);
        }

        private File resolveSafe(File base, String rel) throws IOException {
            if (rel == null || rel.isEmpty()) throw new IOException("empty path");
            // 仅允许相对路径，防目录穿越
            File f = new File(base, rel.replace("../", ""));
            File canon = f.getCanonicalFile();
            if (!canon.getPath().startsWith(base.getCanonicalPath() + File.separator)) {
                throw new IOException("invalid path");
            }
            File p = canon.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            return canon;
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void deleteRecursiveBlockbench(File f) {
            if (f == null) return;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteRecursiveBlockbench(k);
            }
            f.delete();
        }
    }

    private String sanitize(String name) {
        if (name == null || name.isEmpty()) return "model.dat";
        String n = name.replace("\\", "_").replace("/", "_");
        if (n.indexOf('.') < 0) n = n + ".model";
        return n;
    }

    /** 一次分块保存的会话状态（临时文件 + 目标名 + 已收字节数）。 */
    private static final class SaveSession {
        final File temp;
        final String name;
        final String mime;
        OutputStream out;
        long bytes;

        SaveSession(File temp, String name, String mime) {
            this.temp = temp;
            this.name = name;
            this.mime = mime;
        }
    }

    /** 按扩展名推断 MIME，供 MediaStore 归类；未知类型按二进制流处理。 */
    private static String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "zip":
                return "application/zip";
            case "json":
            case "bbmodel":
                return "application/json";
            case "glb":
                return "model/gltf-binary";
            case "gltf":
                return "model/gltf+json";
            case "txt":
            case "obj":
            case "mtl":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }

    /** 文件大小的人类可读形式。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    // ================= 离线版提示 / 退出保护 =================

    /**
     * 退出前探测页面的未保存状态：0=无未保存改动（可直接退出）、1=有且可自动保存、
     * 2=有但拿不到保存入口（由用户自行保存）。
     * <p/>
     * 判定用 {@code Project.saved === false}：内核只在真正发生编辑时把它置为 false
     * （{@code finished_edit} / {@code undo} / {@code redo} 等处），新建的空白项目不会误报。
     * 这里只依赖内核已显式暴露到 window 的 {@code Project} 与 {@code Format}
     * （{@code window.Format=0;window.Project=0}），不触碰任何内部实现。
     */
    private static final String JS_PROBE_UNSAVED =
            "(function(){try{"
            + "if(!(window.Project&&window.Project.saved===false))return 0;"
            + "var F=window.Format;"
            + "return (F&&F.codec&&typeof F.codec.export==='function')?1:2;"
            + "}catch(e){return 0;}})()";

    /** 走内核自身的导出/保存通道（.bbmodel 以文件下载形式发出，由本类的下载钩子落盘到导出目录）。 */
    private static final String JS_TRIGGER_SAVE =
            "(function(){try{window.Format.codec.export();return true;}catch(e){return false;}})()";

    /** 保存是否真正完成（内核在下载完成后才把 Project.saved 置为 true）。 */
    private static final String JS_IS_SAVED =
            "(function(){try{return !!(window.Project&&window.Project.saved===true);}catch(e){return false;}})()";

    /** 保存结果轮询间隔，以及最大轮询次数（约 6 秒）。 */
    private static final int SAVE_POLL_INTERVAL_MS = 300;
    private static final int SAVE_POLL_MAX = 20;

    /** 每次进入离线版都提示一次：本页为「123 离线改版」，已适配兼容插件。 */
    private void showOfflineNotice() {
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bb_offline_notice_title)
                    .setMessage(R.string.bb_offline_notice_message)
                    .setPositiveButton(R.string.bb_offline_notice_ok, null)
                    .show();
        } catch (Throwable ignored) {
            // 弹窗失败不影响使用
        }
    }

    /** 退出前询问是否保存：无未保存改动直接退出，否则弹三选一。 */
    private void exitWithUnsavedGuard() {
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(JS_PROBE_UNSAVED, value -> {
            boolean unsaved = value != null && (value.indexOf('1') >= 0 || value.indexOf('2') >= 0);
            if (!unsaved) {
                // 页面未就绪 / 探测异常时一律按“无改动”处理，避免把用户困在页面里
                finish();
                return;
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bb_exit_unsaved_title)
                    .setMessage(R.string.bb_exit_unsaved_message)
                    .setNegativeButton(R.string.bb_exit_unsaved_discard, (d, w) -> finish())
                    .setNeutralButton(R.string.bb_exit_unsaved_cancel, null);
            if (value.indexOf('1') >= 0) {
                builder.setPositiveButton(R.string.bb_exit_unsaved_save, (d, w) -> saveThenFinish());
            }
            try {
                builder.show();
            } catch (Throwable t) {
                finish();
            }
        });
    }

    /** 触发保存并轮询确认：只有内核确认已保存（Project.saved === true）才退出。 */
    private void saveThenFinish() {
        webView.evaluateJavascript(JS_TRIGGER_SAVE, null);
        pollSaved(0);
    }

    private void pollSaved(final int attempt) {
        webView.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            webView.evaluateJavascript(JS_IS_SAVED, value -> {
                if ("true".equals(value)) {
                    Toast.makeText(this, R.string.bb_exit_saved_toast, Toast.LENGTH_LONG).show();
                    finish();
                } else if (attempt >= SAVE_POLL_MAX) {
                    // 没确认到保存完成就不退出，避免悄悄丢掉作品
                    Toast.makeText(this, R.string.bb_exit_save_failed, Toast.LENGTH_LONG).show();
                } else {
                    pollSaved(attempt + 1);
                }
            });
        }, SAVE_POLL_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            webView.onPause();
        } catch (Throwable ignored) {
        }
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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}