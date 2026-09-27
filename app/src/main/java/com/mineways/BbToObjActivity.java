package com.mineways;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「bb 模型转 OBJ」独立界面。
 *
 * <p>把内置的离线 Blockbench 内核当成<b>转换引擎</b>使用：内核运行在 WebView 里的 iframe 中
 * （{@code assets/bbengine/engine.html} → {@code assets/blockbench/index.html}），
 * 由 {@code assets/bbengine/engine.js} 暴露成 {@code BBEngine} 接口；
 * 本界面只做选文件、传参、取回产物与落盘。内核 UI 由本页的不透明面板完全遮住。
 *
 * <p>取产物的链路刻意绕开内核的「导出 / FileSaver」：加载用内核自带的 {@code loadModelFile}，
 * 编译用 {@code Codecs.obj.compile()}（它会派发 {@code compile} 事件，载荷里带 mtl 与贴图）。
 * 内核输出的顶点已除以 {@code model_export_scale}（默认 16），本界面按用户选择把 {@code v } 行乘回。
 */
public class BbToObjActivity extends AppCompatActivity {

    private static final String TAG = "BbToObj";

    /** 引擎页地址（WebViewAssetLoader 的虚拟 https 源，同源可直接操作 iframe）。 */
    private static final String ENGINE_URL =
            "https://appassets.androidplatform.net/assets/bbengine/engine.html";

    /** 产物落地目录：Download/MinewaysMobile/ 下。 */
    private static final String SUBDIR = "bb转obj";

    /** 单次跨桥传/取的分片长度（字符），与 engine.js 的约定一致。 */
    private static final int CHUNK = 40000;
    /** 拉取产物时的分片长度（略小于上传，回程还要过 JSON 转义）。 */
    private static final int PULL_CHUNK = 60000;
    /** 输入 bbmodel 的大小上限（超过多半是误选，直接拒绝以免 OOM）。 */
    private static final long MAX_INPUT = 32L * 1024 * 1024;

    /** 引擎调用的响应超时：超时即释放界面，避免任何一侧卡死导致按钮永远不可用。 */
    private static final long REPLY_TIMEOUT_MS = 45000;

    /** 系统「下载」目录下的子目录。 */
    private static final String PUBLIC_DIR = "Download/MinewaysMobile";

    private WebView webView;
    private TextView tvFile, tvStatus;
    private CheckBox cbScale;
    private ProgressBar progressBar;
    private WebViewAssetLoader assetLoader;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 选中的 bbmodel 文本与其文件名（不含扩展名，用作产物名）。 */
    private String inputText;
    private String sourceName;
    /** 内核报告的项目名，仅用于回显。 */
    private String projectName = "model";
    /** 内核输出使用的缩放系数（默认 16）。 */
    private int exportScale = 16;

    /** 转换产物。 */
    private String objText;
    private String mtlText;
    private final LinkedHashMap<String, byte[]> textures = new LinkedHashMap<>();
    private boolean hasResult;

    /** 引擎是否已就绪（内核加载完成）。 */
    private boolean engineReady;
    private boolean readyRequested;
    private boolean busy;

    /** 请求 / 响应：id → 回调。仅主线程访问。 */
    private int reqSeq;
    private final Map<String, Callback> pending = new HashMap<>();

    /** 拉取产物的状态。 */
    private List<Entry> entries = new ArrayList<>();
    private int pullIndex;
    private StringBuilder pullBuf;

    private interface Callback {
        /** @param ok 是否成功；@param value 成功时为解析后的 JSON 值，失败时为错误文案 */
        void onDone(boolean ok, Object value);
    }

    /** 一条产物：obj / mtl / 贴图。 */
    private static final class Entry {
        String key;
        String name;
        int len;
    }

    private final ActivityResultLauncher<String[]> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    readInput(uri);
                }
            });

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bb_to_obj);

        MaterialToolbar bar = findViewById(R.id.bb2obj_toolbar);
        setSupportActionBar(bar);
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.bb2obj_webview);
        tvFile = findViewById(R.id.tv_bb_file);
        tvStatus = findViewById(R.id.tv_bb_status);
        cbScale = findViewById(R.id.cb_bb_scale);
        progressBar = findViewById(R.id.pb_bb);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        findViewById(R.id.btn_bb_pick).setOnClickListener(v -> pickLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.btn_bb_convert).setOnClickListener(v -> convert());
        findViewById(R.id.btn_bb_save).setOnClickListener(v -> saveAll());
        findViewById(R.id.btn_bb_share).setOnClickListener(v -> shareZip());

        setupWebView();
        updateButtons();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        // 结果回调桥（注意不要叫 BBAndroid：内置保存助手只认那个名字，
        // 不注册它，助手在引擎页里的导出接管就会自动让位给内核原流程，互不干扰）
        webView.addJavascriptInterface(new Host(), "BBEngineHost");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                if (response != null) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    response.setResponseHeaders(headers);
                }
                return response;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (readyRequested) {
                    return;
                }
                readyRequested = true;
                waitEngineReady();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m != null) {
                    Log.i(TAG, "console: " + m.message());
                }
                return true;
            }
        });

        webView.loadUrl(ENGINE_URL);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            webView.removeJavascriptInterface("BBEngineHost");
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "destroy webview failed", t);
        }
    }

    // ------------------------------------------------------------------ 引擎握手

    private void waitEngineReady() {
        setBusy(true);
        tvStatus.setText(getString(R.string.bb2obj_status_kernel));
        call("ready", (ok, value) -> {
            setBusy(false);
            if (!ok) {
                tvStatus.setText(getString(R.string.bb2obj_status_kernel_failed, str(value)));
                return;
            }
            engineReady = true;
            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                exportScale = o.optInt("scale", 16);
                tvStatus.setText(getString(R.string.bb2obj_status_ready,
                        o.optString("version", "?"), exportScale));
            } else {
                tvStatus.setText(getString(R.string.bb2obj_status_ready, "?", exportScale));
            }
            updateButtons();
        });
    }

    /** JS → Java 的回调与日志。 */
    private final class Host {
        @JavascriptInterface
        public void onReply(String id, int ok, String payload) {
            main.post(() -> {
                Callback cb = pending.remove(id);
                if (cb == null) {
                    return;
                }
                Object value;
                try {
                    value = new JSONTokener(payload == null || payload.isEmpty() ? "null" : payload).nextValue();
                } catch (Throwable t) {
                    value = payload;
                }
                cb.onDone(ok == 1, value);
            });
        }

        @JavascriptInterface
        public void onLog(String line) {
            Log.i(TAG, String.valueOf(line));
        }
    }

    // ------------------------------------------------------------------ 调用引擎

    private void evalRaw(String js) {
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "evaluateJavascript failed: " + js, t);
        }
    }

    /** 调用 BBEngine 的某个方法；id 由本方法自动补成第一个参数。 */
    private void call(String fn, Callback cb, Object... args) {
        final String id = "r" + (++reqSeq);
        if (cb != null) {
            pending.put(id, cb);
            // 兜底超时：页面脚本报错时不会有人回调，这里把界面放出来并给出可读的原因
            main.postDelayed(() -> {
                Callback c = pending.remove(id);
                if (c != null) {
                    c.onDone(false, getString(R.string.bb2obj_status_timeout, fn));
                }
            }, REPLY_TIMEOUT_MS);
        }
        StringBuilder sb = new StringBuilder("BBEngine.").append(fn).append('(').append(JSONObject.quote(id));
        for (Object a : args) {
            if (a == null) {
                sb.append(",null");
            } else if (a instanceof Number) {
                sb.append(',').append(a.toString());
            } else {
                sb.append(',').append(JSONObject.quote(String.valueOf(a)));
            }
        }
        sb.append(')');
        evalRaw(sb.toString());
    }

    // ------------------------------------------------------------------ 选文件

    private void readInput(Uri uri) {
        setBusy(true);
        tvStatus.setText(getString(R.string.bb2obj_status_reading));
        new Thread(() -> {
            String text = null, err = null, name = "model";
            try {
                String dn = displayName(uri);
                if (dn != null && !dn.trim().isEmpty()) {
                    name = dn;
                }
                byte[] raw = readAll(uri);
                text = new String(raw, StandardCharsets.UTF_8);
                String head = text.substring(0, Math.min(512, text.length())).trim();
                if (!head.startsWith("{")) {
                    throw new Exception(getString(R.string.bb2obj_not_bbmodel, name));
                }
                if (!text.contains("\"meta\"")) {
                    throw new Exception(getString(R.string.bb2obj_not_bbmodel, name));
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final String fText = text, fErr = err, fName = name;
            runOnUiThread(() -> {
                setBusy(false);
                if (fErr != null) {
                    inputText = null;
                    tvFile.setText(getString(R.string.bb2obj_no_file));
                    tvStatus.setText(getString(R.string.bb2obj_status_failed, fErr));
                    updateButtons();
                    return;
                }
                inputText = fText;
                sourceName = stripExt(fName);
                hasResult = false;
                objText = null;
                mtlText = null;
                textures.clear();
                tvFile.setText(getString(R.string.bb2obj_file, fName, fText.length() / 1024));
                tvStatus.setText(getString(R.string.bb2obj_status_picked));
                updateButtons();
            });
        }, "bb2obj-read").start();
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception(getString(R.string.bb2obj_status_failed, "无法读取文件"));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_INPUT) {
                    throw new Exception("文件超过 " + (MAX_INPUT / 1024 / 1024) + "MB 上限");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------------ 转换

    private void convert() {
        if (!engineReady) {
            toast(getString(R.string.bb2obj_status_kernel));
            return;
        }
        if (inputText == null) {
            toast(getString(R.string.bb2obj_need_file));
            return;
        }
        hasResult = false;
        objText = null;
        mtlText = null;
        textures.clear();
        entries = new ArrayList<>();
        pullIndex = 0;
        setBusy(true);
        tvStatus.setText(getString(R.string.bb2obj_status_uploading));

        // 1) 分片把 bbmodel 文本送进引擎，再触发加载
        evalRaw("BBEngine.beginLoad()");
        String text = inputText;
        int off = 0;
        while (off < text.length()) {
            int end = Math.min(text.length(), off + CHUNK);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end++;
            }
            evalRaw("BBEngine.appendLoad(" + JSONObject.quote(text.substring(off, end)) + ")");
            off = end;
        }
        call("endLoad", (ok, value) -> {
            if (!ok) {
                fail(str(value));
                return;
            }
            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                projectName = o.optString("project", sourceName == null ? "model" : sourceName);
            }
            tvStatus.setText(getString(R.string.bb2obj_status_compiling));
            call("exportObj", (ok2, value2) -> {
                if (!ok2) {
                    fail(str(value2));
                    return;
                }
                if (value2 instanceof JSONObject) {
                    exportScale = ((JSONObject) value2).optInt("scale", exportScale);
                }
                tvStatus.setText(getString(R.string.bb2obj_status_pulling));
                call("info", (ok3, value3) -> {
                    if (!ok3) {
                        fail(str(value3));
                        return;
                    }
                    if (!parseEntries(value3)) {
                        fail("引擎没有返回任何产物");
                        return;
                    }
                    pullNext();
                });
            });
        });
    }

    private void fail(String message) {
        setBusy(false);
        tvStatus.setText(getString(R.string.bb2obj_status_failed, message));
    }

    private boolean parseEntries(Object value) {
        entries = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.key = o.optString("key");
                e.name = o.optString("name");
                e.len = o.optInt("len", 0);
                if (e.key != null && !e.key.isEmpty()) {
                    entries.add(e);
                }
            }
        }
        return !entries.isEmpty();
    }

    private void pullNext() {
        if (pullIndex >= entries.size()) {
            onPulled();
            return;
        }
        Entry e = entries.get(pullIndex);
        tvStatus.setText(getString(R.string.bb2obj_status_pulling_file, e.name, e.len / 1024));
        pullBuf = new StringBuilder(e.len + 16);
        if (e.len <= 0) {
            store(e, "");
            pullIndex++;
            pullNext();
            return;
        }
        pullChunk(e, 0);
    }

    private void pullChunk(Entry e, int offset) {
        call("slice", (ok, value) -> {
            if (!ok) {
                fail(str(value));
                return;
            }
            String chunk = value == null ? "" : String.valueOf(value);
            pullBuf.append(chunk);
            if (chunk.isEmpty() || pullBuf.length() >= e.len) {
                if (pullBuf.length() < e.len) {
                    fail(getString(R.string.bb2obj_status_failed,
                            "取回 " + e.name + " 不完整（" + pullBuf.length() + "/" + e.len + "）"));
                    return;
                }
                store(e, pullBuf.toString());
                pullIndex++;
                pullNext();
            } else {
                pullChunk(e, offset + chunk.length());
            }
        }, e.key, offset, PULL_CHUNK);
    }

    /** 归档一条产物：obj / mtl 存文本，贴图解开 data URI 存字节。 */
    private void store(Entry e, String text) {
        if ("obj".equals(e.key)) {
            objText = cbScale.isChecked() ? rescaleVertices(text, exportScale) : text;
            return;
        }
        if ("mtl".equals(e.key)) {
            mtlText = text;
            return;
        }
        int comma = text.indexOf(',');
        if (comma <= 0 || text.lastIndexOf(";base64", comma) < 0) {
            Log.w(TAG, "跳过非常规贴图：" + e.name);
            return;
        }
        // MIME 解码器会忽略换行等非 base64 字符，比基本解码器更耐脏数据
        try {
            textures.put(e.name, Base64.getMimeDecoder().decode(text.substring(comma + 1)));
        } catch (Throwable t) {
            Log.w(TAG, "贴图解码失败：" + e.name, t);
        }
    }

    private void onPulled() {
        setBusy(false);
        if (objText == null || objText.isEmpty()) {
            tvStatus.setText(getString(R.string.bb2obj_status_failed, "OBJ 内容为空"));
            return;
        }
        hasResult = true;
        int v = countLines(objText, "v ");
        int f = countLines(objText, "f ");
        int u = countLines(objText, "usemtl ");
        tvStatus.setText(getString(R.string.bb2obj_status_done,
                projectName, objText.length() / 1024,
                cbScale.isChecked() ? exportScale : 1,
                v, f, u, textures.size()));
        updateButtons();
    }

    // ------------------------------------------------------------------ 顶点缩放

    /**
     * 内核按 {@code model_export_scale}（默认 16）把顶点除以了 16，
     * 这一步把 {@code v } 行的三个坐标乘回，使 1 个模型单位 == 1 个像素。
     * vt / vn / f 一律不动（内核已处理过 UV 方向，二次翻转反而会错）。
     */
    private static String rescaleVertices(String obj, int scale) {
        if (scale <= 1) {
            return obj;
        }
        StringBuilder out = new StringBuilder(obj.length() + 64);
        int i = 0, n = obj.length();
        while (i <= n) {
            int nl = obj.indexOf('\n', i);
            boolean last = nl < 0;
            String line = last ? obj.substring(i) : obj.substring(i, nl);
            if (line.startsWith("v ")) {
                appendScaledV(out, line, scale);
            } else {
                out.append(line);
            }
            if (last) {
                break;
            }
            out.append('\n');
            i = nl + 1;
        }
        return out.toString();
    }

    private static void appendScaledV(StringBuilder sb, String line, int scale) {
        sb.append('v');
        int pos = 1;
        for (int t = 0; t < 3; t++) {
            int st = pos;
            while (st < line.length() && (line.charAt(st) == ' ' || line.charAt(st) == '\t')) {
                st++;
            }
            int en = st;
            while (en < line.length() && line.charAt(en) != ' ' && line.charAt(en) != '\t') {
                en++;
            }
            if (en <= st) {
                sb.append(line.substring(pos));
                return;
            }
            double v;
            try {
                v = Double.parseDouble(line.substring(st, en)) * scale;
            } catch (Throwable ignored) {
                sb.append(line.substring(pos));
                return;
            }
            sb.append(' ').append(fmt(v));
            pos = en;
        }
        if (pos < line.length()) {
            sb.append(line.substring(pos));
        }
    }

    /** 保留 6 位小数并去掉尾随 0，避免出现 1.0000000000000002 这类浮点噪声。 */
    private static String fmt(double v) {
        if (Math.abs(v) < 1e-9) {
            return "0";
        }
        String s = String.format(Locale.US, "%.6f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        s = s.substring(0, end);
        return "-0".equals(s) ? "0" : s;
    }

    private static int countLines(String s, String prefix) {
        int c = 0, i = 0;
        while (i <= s.length()) {
            int nl = s.indexOf('\n', i);
            boolean last = nl < 0;
            String line = last ? s.substring(i) : s.substring(i, nl);
            if (line.startsWith(prefix)) {
                c++;
            }
            if (last) {
                break;
            }
            i = nl + 1;
        }
        return c;
    }

    // ------------------------------------------------------------------ 落盘

    /** 落盘成一套（obj + mtl + 贴图）：Android 10+ 走 MediaStore，低版本写应用外部目录。 */
    private void saveAll() {
        if (!hasResult) {
            toast(getString(R.string.bb2obj_nothing));
            return;
        }
        final String folder = SUBDIR + "/" + safe(sourceName);
        int ok = 0, failed = 0;
        try {
            writeOne(folder, safe(sourceName) + ".obj", "text/plain", objText.getBytes(StandardCharsets.UTF_8));
            ok++;
        } catch (Throwable t) {
            failed++;
            Log.w(TAG, "保存 obj 失败", t);
        }
        if (mtlText != null) {
            try {
                writeOne(folder, "materials.mtl", "text/plain", mtlText.getBytes(StandardCharsets.UTF_8));
                ok++;
            } catch (Throwable t) {
                failed++;
                Log.w(TAG, "保存 mtl 失败", t);
            }
        }
        for (Map.Entry<String, byte[]> e : textures.entrySet()) {
            try {
                writeOne(folder, e.getKey(), "image/png", e.getValue());
                ok++;
            } catch (Throwable t) {
                failed++;
                Log.w(TAG, "保存贴图失败：" + e.getKey(), t);
            }
        }
        if (failed == 0) {
            toast(getString(R.string.bb2obj_saved_all, ok, PUBLIC_DIR + "/" + folder));
        } else {
            // P3D 兼容检查：超面数给出降面建议（公版策略，见 P3dCompat）
            toast(getString(R.string.bb2obj_saved_part, ok, failed)
                    + " · P3D：" + P3dCompat.advice(P3dCompat.analyzeObj(objText)));
        }
    }

    private void writeOne(String folder, String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_DIR + "/" + folder);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new Exception("MediaStore 创建失败：" + name);
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new Exception("MediaStore 打开失败：" + name);
                }
                os.write(data);
            }
            return;
        }
        File dir = new File(getExternalFilesDir(null), folder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建目录：" + dir);
        }
        try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) {
            fo.write(data);
        }
    }

    /** 打包成 zip 分享（OBJ 必须与 mtl、贴图同目录交付，单个附件最容易带走）。 */
    private void shareZip() {
        if (!hasResult) {
            toast(getString(R.string.bb2obj_nothing));
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("无法创建导出目录");
            }
            File zip = new File(dir, safe(sourceName) + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                putEntry(zos, safe(sourceName) + ".obj", objText.getBytes(StandardCharsets.UTF_8));
                if (mtlText != null) {
                    putEntry(zos, "materials.mtl", mtlText.getBytes(StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, byte[]> e : textures.entrySet()) {
                    putEntry(zos, e.getKey(), e.getValue());
                }
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", zip);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.bb2obj_share)));
        } catch (Throwable t) {
            toast(getString(R.string.bb2obj_status_failed,
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage()));
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ 小工具

    private void updateButtons() {
        findViewById(R.id.btn_bb_pick).setEnabled(!busy);
        findViewById(R.id.btn_bb_convert).setEnabled(!busy && engineReady && inputText != null);
        findViewById(R.id.btn_bb_save).setEnabled(!busy && hasResult);
        findViewById(R.id.btn_bb_share).setEnabled(!busy && hasResult);
    }

    private void setBusy(boolean b) {
        busy = b;
        progressBar.setVisibility(b ? View.VISIBLE : View.GONE);
        updateButtons();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    return c.getString(i);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就用路径兜底
        }
        String s = uri.getLastPathSegment();
        if (s == null) {
            return null;
        }
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        String s = dot > 0 ? name.substring(0, dot) : name;
        return safe(s);
    }

    private static String safe(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "model";
        }
        String t = s.trim().replaceAll("[^0-9A-Za-z_\\u4e00-\\u9fa5.-]", "_");
        return t.isEmpty() ? "model" : t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}