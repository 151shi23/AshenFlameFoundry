package com.mineways;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.mineways.mod.ModOutput;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import rikka.shizuku.Shizuku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mineways Mobile 主界面。
 * 底部导航三栏：导出 / 世界 / 关于。
 * 通过 SAF 选择 Minecraft 存档目录后复制到应用私有目录，再由 JNI 调用 C++ 核心导出 OBJ。
 */
public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("mineways_core");
    }

    // ---- Native 接口 ----
    native String coreInfo();
    native String probeWorld(String path);
    native String exportWorld(String worldDir, String outBaseNoExt,
                              int fileType, int minx, int miny, int minz,
                              int maxx, int maxy, int maxz,
                              int cullMode, int decimateOn, String options);

    // 当前所选世界目录（复制到私有目录后的路径）
    private File currentWorldDir;
    private File exportDir;

    // ---- 转换页状态 ----
    private ScrollView pageConvert;
    private EditText etConvertSrc;
    private AutoCompleteTextView acConvertTarget;
    private TextView tvConvertResult;
    private MaterialButton btnConvert, btnConvertCancel;
    private File convertSourceDir;
    private String convertTargetId;
    private final AtomicBoolean convertCancelled = new AtomicBoolean(false);

    private EditText etWorldDir;
    private ScrollView pageExport, pageWorld, pageAbout, pageHome, pageTools;
    private TextView tvWorldText, tvResult, tvShizukuStatus, tvToolsStatus;
    private MaterialButton btnExport;

    // 当前显示的有内容页面（首页 / 关于为顶层，其余子页面按返回键回到首页）
    private ScrollView currentPage;

    // 文件类型：0 = Wavefront OBJ（绝对）
    private static final int FILE_TYPE_WAVEFRONT_ABS_OBJ = 0;

    private final ActivityResultLauncher<Uri> pickWorldLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return;
                        try {
                            setWorkInProgress("正在读取存档目录…");
                            DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
                            if (dir == null) { fail("无法读取所选目录"); return; }
                            // 复制到私有目录
                            File targetRoot = new File(getFilesDir(), "world");
                            currentWorldDir = new File(targetRoot, "world");
                            if (currentWorldDir.exists()) deleteRecursive(currentWorldDir);
                            if (!currentWorldDir.mkdirs()) { fail("无法创建工作目录"); return; }
                            int copied = copyTree(dir, currentWorldDir);
                            File lvl = new File(currentWorldDir, "level.dat");
                            if (!lvl.exists()) {
                                // 可能整个目录是 level.dat 所在，扫描一层
                                lvl = findLevelDat(currentWorldDir);
                                if (lvl == null) {
                                    // 常见错误一次说清：普通 Java 世界有 region/，基岩版有 db/（level.dat 也在根目录）
                                    File pickRoot = currentWorldDir;
                                    boolean hasRegion = new File(pickRoot, "region").isDirectory();
                                    boolean hasDb = new File(pickRoot, "db").isDirectory();
                                    String m = "这层目录里没有 level.dat（不是存档根目录）\n\n";
                                    if (hasDb && !hasRegion) {
                                        m += "检测到 db/ 目录 → 这是**基岩版**存档。\n"
                                           + "用法：先到「转换」页把它转成 Java 版，转出的世界（含 region/）再来导出。";
                                    } else if (hasRegion) {
                                        m += "已看到 region/ 目录：请把目录选到**含 level.dat 与 region/ 的那一层**。";
                                    } else {
                                        m += "请进入存档文件夹再选一次：Java 世界根目录里应有 level.dat、region/ 等；"
                                           + "基岩版则是 level.dat、db/。";
                                    }
                                    fail(m);
                                    return;
                                }
                            }
                            String path = lvl.getParentFile().getAbsolutePath();
                            etWorldDir.setText(path);
                            currentWorldDir = lvl.getParentFile();
                            tvResult.setText("世界目录已就绪，已读取 " + copied + " 个文件。");
                            setWorkDone();
                            refreshWorldInfo(path);
                            // 自动识别网易加密存档并就地解密（非网易存档原样跳过，不影响现有功能）
                            decryptNeteaseIfNeeded(lvl.getParentFile(), path);
                        } catch (Exception e) {
                            fail("选择目录失败：" + e.getMessage());
                        }
                    });

    // 转换页：选择输入存档目录（复制到独立私有目录，避免影响导出页状态）
    private final ActivityResultLauncher<Uri> pickConvertLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return;
                        // 立即给反馈，防止用户以为没选上而重复点击
                        setConvertResult("已选择目录，正在读取存档文件…");
                        final View b = findViewById(R.id.btn_pick_convert_src);
                        if (b != null) b.setEnabled(false);
                        final DocumentFile selectedDir = DocumentFile.fromTreeUri(this, uri);
                        final String originalName =
                                (selectedDir != null && selectedDir.getName() != null)
                                        ? selectedDir.getName() : "存档";
                        // 拷贝在后台线程执行，避免阻塞主线程导致界面卡顿/无响应
                        Thread copyThread = new Thread(() -> {
                            try {
                                File targetRoot = new File(getFilesDir(), "convert_src");
                                File src = new File(targetRoot, "world");
                                if (src.exists()) deleteRecursive(src);
                                if (!src.mkdirs()) {
                                    runOnUiThread(() -> {
                                        if (b != null) b.setEnabled(true);
                                        setConvertResult("无法创建工作目录");
                                    });
                                    return;
                                }
                                int copied = copyTree(selectedDir, src);
                                File lvl = new File(src, "level.dat");
                                if (!lvl.exists()) {
                                    lvl = findLevelDat(src);
                                    if (lvl == null) {
                                        runOnUiThread(() -> {
                                            if (b != null) b.setEnabled(true);
                                            setConvertResult("所选目录没有 level.dat（不是存档根）");
                                        });
                                        return;
                                    }
                                }
                                convertSourceDir = lvl.getParentFile();
                                // 自动识别网易加密存档并就地解密（非网易存档原样跳过），
                                // 再让用户看到“已载入”。解密在后台线程完成，避免卡 UI。
                                NeteaseDecryptor.Result dres = null;
                                try {
                                    dres = NeteaseDecryptor.decrypt(convertSourceDir, null);
                                } catch (Throwable ignored) {
                                }
                                final String decryptNote = (dres != null && dres.encrypted)
                                        ? (dres.success
                                            ? "（网易加密存档，已解密 " + dres.decryptedFiles + " 个文件）"
                                            : "（检测到网易加密存档，但解密未完成：" + dres.message + "）")
                                        : "";
                                runOnUiThread(() -> {
                                    if (b != null) b.setEnabled(true);
                                    etConvertSrc.setText(convertSourceDir.getAbsolutePath());
                                    setConvertResult("已载入存档「" + originalName + "」，共读取 " + copied + " 个文件。"
                                            + decryptNote);
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    if (b != null) b.setEnabled(true);
                                    setConvertResult("选择目录失败：" + e.getMessage());
                                });
                            }
                        });
                        copyThread.setDaemon(true);
                        copyThread.start();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 防泄密水印：纯装饰叠加层，不改布局、不拦触摸，异常全吞（见 WatermarkView）
        WatermarkView.attach(this);

        exportDir = new File(getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        // 底部导航（首页 / 关于）
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        pageExport = findViewById(R.id.page_export);
        pageWorld = findViewById(R.id.page_world);
        pageAbout = findViewById(R.id.page_about);
        pageConvert = findViewById(R.id.page_convert);
        pageHome = findViewById(R.id.page_home);
        pageTools = findViewById(R.id.page_tools);
        tvWorldText = findViewById(R.id.tv_world_text);
        tvResult = findViewById(R.id.tv_result);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvToolsStatus = findViewById(R.id.tv_tools_status);

        setUpConvertPage();
        setUpHomePage();
        setUpToolsPage();
        setUpNeteasePage();

        registerShizukuPermissionListener();
        updateShizukuStatus();

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.tab_home) showPage(pageHome);
            else if (id == R.id.tab_about) showPage(pageAbout);
            return true;
        });
        // 默认显示首页
        showPage(pageHome);
        registerBackHandler();

        etWorldDir = findViewById(R.id.et_world_dir);

        // 文件类型下拉
        AutoCompleteTextView acType = findViewById(R.id.ac_filetype);
        String[] types = getResources().getStringArray(R.array.file_types);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, types);
        acType.setAdapter(adapter);
        acType.setText(types[0], false);
        acType.setEnabled(true);

        // 面剔除方案下拉
        AutoCompleteTextView acCull = findViewById(R.id.ac_cullmode);
        String[] cullModes = getResources().getStringArray(R.array.cull_modes);
        ArrayAdapter<String> cullAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, cullModes);
        acCull.setAdapter(cullAdapter);
        acCull.setText(cullModes[0], false);
        acCull.setEnabled(true);

        findViewById(R.id.btn_pick_world).setOnClickListener(v ->
                pickWorldLauncher.launch(null));

        findViewById(R.id.btn_shizuku_import).setOnClickListener(v -> onShizukuImportClick());

        btnExport = findViewById(R.id.btn_export);
        btnExport.setOnClickListener(v -> doExport());

        // 机型适配：Android 8/9（API 26-28）需要该权限才能把结果写进公共「下载」；
        // 未授权也不影响使用——导出会退回应用私有目录（Android 10+ 走 MediaStore，无需权限）。
        requestLegacyStorageIfNeeded();

        // 模型预览（独立界面）：点一下先选来源——最近导出 / ZIP / 直接挑 .obj 文件
        findViewById(R.id.btn_preview_model).setOnClickListener(v -> showPreviewSourceChooser());

        // ZIP 预览：选一个压缩包（例如「下载/MinewaysMobile」里的导出包），解压后直接看
        findViewById(R.id.btn_preview_zip).setOnClickListener(v -> pickZip());

        findViewById(R.id.btn_join).setOnClickListener(v -> openJoinUrl());

        // 首次启动引导窗（强窗口：10 秒倒计时后才能关闭）
        showFirstRunIntro();
    }

    /** 首次启动引导窗：setCancelable(false) + 10 秒倒计时后按钮才可用；只显示一次。 */
    private void showFirstRunIntro() {
        try {
            final android.content.SharedPreferences sp = exportOpts();
            if (sp.getBoolean("first_run_intro_shown", false)) return;
            View content = getLayoutInflater().inflate(R.layout.dialog_first_run, null);
            final androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.intro_title))
                    .setView(content)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.intro_close_wait, 10), null)
                    .create();
            d.show();
            final android.widget.Button ok = d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            new android.os.CountDownTimer(10000, 200) {
                @Override
                public void onTick(long msUntilFinished) {
                    try {
                        ok.setText(getString(R.string.intro_close_wait,
                                (int) Math.ceil(msUntilFinished / 1000.0)));
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onFinish() {
                    try {
                        ok.setText(getString(R.string.intro_close));
                        ok.setEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
            }.start();
            d.setOnDismissListener(dialog -> {
                try {
                    sp.edit().putBoolean("first_run_intro_shown", true).apply();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 选择 ZIP 压缩包预览：解压其中的 OBJ/MTL/贴图到缓存目录，再打开查看器。 */
    private final ActivityResultLauncher<String[]> pickZipLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                onZipPicked(uri);
            });

    /** 直接挑一个 .obj（任意位置）：预览界面会把它连同同目录材质一起复制到缓存再显示。 */
    private final ActivityResultLauncher<String[]> pickObjLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    Intent i = new Intent(this, ObjPreviewActivity.class);
                    i.putExtra(ObjPreviewActivity.EXTRA_DOC_URI, uri.toString());
                    i.putExtra(ObjPreviewActivity.EXTRA_LABEL, "手动选择");
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(this, "无法打开预览：" + t, Toast.LENGTH_LONG).show();
                }
            });

    /** 模组转换：选择一个或多个模组 jar / 资源包（多选）。 */
    private final ActivityResultLauncher<String[]> pickModLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                for (Uri u : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(u,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) { }
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(u.toString());
                }
                exportOpts().edit().putString("mod_uris", sb.toString()).apply();
                Toast.makeText(this, "已选择 " + uris.size() + " 个模组文件", Toast.LENGTH_SHORT).show();
            });

    private int modUriCount() {
        String s = exportOpts().getString("mod_uris", "");
        if (s == null || s.isEmpty()) return 0;
        return s.split("\n").length;
    }

    private java.util.List<Uri> decodeModUris(String s) {
        java.util.List<Uri> out = new java.util.ArrayList<>();
        if (s != null && !s.isEmpty()) for (String part : s.split("\n")) {
            try { out.add(Uri.parse(part)); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** ZIP 选择入口（按钮与来源选择共用）。 */
    private void pickZip() {
        try {
            pickZipLauncher.launch(new String[]{
                    "application/zip", "application/x-zip-compressed", "*/*"});
        } catch (Throwable t) {
            Toast.makeText(this, R.string.preview_zip_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 独立预览的来源选择。三个来源随时都能用，某个找不到（换了保存位置、删了文件）就换另一个，
     * 不用去猜"为什么预览不了"。
     */
    private void showPreviewSourceChooser() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("3D 预览 · 选择来源")
                .setItems(new String[]{
                        "最近一次导出（自动找 .obj，带贴图）",
                        "选择 ZIP 压缩包（解包后预览）",
                        "直接选择 .obj 文件（任意位置）"}, (d, which) -> {
                    if (which == 0) openObjPreview();
                    else if (which == 1) pickZip();
                    else pickObjLauncher.launch(new String[]{"*/*"});
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 处理选中的 ZIP：后台解压 → 主线程打开预览。 */
    private void onZipPicked(final Uri uri) {
        Toast.makeText(this, R.string.preview_zip_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final File dir = extractZipToPreviewDir(uri);
            final File obj = (dir != null) ? findPreviewObj(dir) : null;
            runOnUiThread(() -> {
                if (dir == null || obj == null || !obj.isFile()) {
                    Toast.makeText(this, R.string.preview_zip_empty, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Intent i = new Intent(this, ObjPreviewActivity.class);
                    i.putExtra(ObjPreviewActivity.EXTRA_MODEL_DIR, dir.getAbsolutePath());
                    i.putExtra(ObjPreviewActivity.EXTRA_OBJ_NAME, obj.getName());
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(this, "无法打开预览：" + t, Toast.LENGTH_LONG).show();
                }
            });
        }, "zip-preview").start();
    }

    /**
     * 解压 ZIP 里的模型文件到 {@code cache/preview/<时间戳>/}：
     * 只取 .obj/.mtl/.png/.jpg（外层目录名被压平），带总量与条目数上限，避免异常压缩包。
     * 返回目录；失败时返回 null（已有部分内容则返回部分目录，交由调用方判断有没有 .obj）。
     */
    private File extractZipToPreviewDir(Uri uri) {
        File base = new File(getCacheDir(), "preview");
        deleteRecursively(base);        // 清掉上一次的预览缓存，避免堆积
        File dir = new File(base, new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()));
        if (!dir.mkdirs() && !dir.isDirectory()) return null;
        long total = 0;
        int kept = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(in))) {
                byte[] buf = new byte[64 * 1024];
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (name == null) continue;
                    String ln = name.toLowerCase(Locale.US);
                    if (!(ln.endsWith(".obj") || ln.endsWith(".mtl") || ln.endsWith(".png")
                            || ln.endsWith(".jpg") || ln.endsWith(".jpeg"))) {
                        continue;
                    }
                    String plain = name.substring(name.lastIndexOf('/') + 1);
                    if (plain.isEmpty()) continue;
                    try (OutputStream os = new FileOutputStream(new File(dir, plain))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            total += n;
                            if (total > 400L * 1024 * 1024) return dir;   // 安全上限
                        }
                    }
                    kept++;
                    if (kept > 4000) break;
                }
            }
            return dir;
        } catch (Throwable t) {
            return dir.isDirectory() ? dir : null;
        }
    }

    /**
     * 打开 OBJ 自动贴图预览：默认预览最近一次导出的 {@code mineways_export.obj}
     * （同目录的 .mtl 与贴图 PNG 会被查看器自动加载）。
     */
    private void openObjPreview() {
        try {
            File dir = (exportDir != null) ? exportDir : new File(getExternalFilesDir(null), "exports");
            File obj = findPreviewObj(dir);
            if (obj == null || !obj.isFile()) {
                // 失败时说清"在哪个目录、找了什么"，便于反馈（不再只报"没有模型"）
                StringBuilder names = new StringBuilder();
                File[] kids = dir.listFiles();
                if (kids != null) {
                    for (File f : kids) {
                        if (names.length() > 120) { names.append(" …"); break; }
                        if (names.length() > 0) names.append(" / ");
                        names.append(f.getName());
                    }
                }
                Toast.makeText(this,
                        "没有找到可预览的 .obj\n目录：" + dir.getAbsolutePath()
                                + (names.length() > 0 ? ("\n目录内容：" + names) : "\n（目录为空或无法读取）"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(this, ObjPreviewActivity.class);
            i.putExtra(ObjPreviewActivity.EXTRA_MODEL_DIR, obj.getParentFile().getAbsolutePath());
            i.putExtra(ObjPreviewActivity.EXTRA_OBJ_NAME, obj.getName());
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开预览：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 找要预览的 OBJ：优先 {@code mineways_export.obj}（本应用的默认输出名），
     * 找不到就取导出目录（含一层子目录，跳过「导出报错」）里**最近修改的 .obj**。
     * 这样即使输出改名、或用了「按方块类型分割」、或用户手动放了别的 OBJ，都能预览。
     */
    private static File findPreviewObj(File dir) {
        try {
            File exact = new File(dir, "mineways_export.obj");
            if (exact.isFile()) return exact;
            File best = null;
            long bestTime = -1;
            java.util.List<File> stack = new java.util.ArrayList<>();
            stack.add(dir);
            while (!stack.isEmpty()) {
                File d = stack.remove(stack.size() - 1);
                File[] kids = d.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    if (f.isDirectory()) {
                        if (!"导出报错".equals(f.getName())) stack.add(f);
                        continue;
                    }
                    String n = f.getName().toLowerCase(Locale.US);
                    if (!n.endsWith(".obj")) continue;
                    if (f.lastModified() > bestTime) {
                        bestTime = f.lastModified();
                        best = f;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Android 8/9（API 26-28）申请写外部存储权限：这是"导出 ZIP 落到公共下载目录"的唯一前提，
     * Blockbench 页早已这么做，主界面此前漏了 → 在 8/9 机型上只能拿到 Android/data 里的私有副本。
     * Android 10+ 走 MediaStore 不需要权限，直接跳过。
     */
    private void requestLegacyStorageIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return;
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            }
        } catch (Throwable ignored) {
            // 权限流程异常不影响导出（会退回私有目录，文件不会丢）
        }
    }

    private void openJoinUrl() {
        String url = getString(R.string.about_join_url);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPage(ScrollView page) {
        pageHome.setVisibility(page == pageHome ? View.VISIBLE : View.GONE);
        pageExport.setVisibility(page == pageExport ? View.VISIBLE : View.GONE);
        pageWorld.setVisibility(page == pageWorld ? View.VISIBLE : View.GONE);
        pageConvert.setVisibility(page == pageConvert ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(page == pageTools ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(page == pageAbout ? View.VISIBLE : View.GONE);

        // 首页 / 关于为顶层页面，不显示返回箭头；其余子页面显示返回箭头便于返回首页
        MaterialToolbar bar = tBar();
        if (page == pageHome || page == pageAbout) {
            bar.setNavigationIcon(null);
        } else if (bar.getNavigationIcon() == null || currentPage == pageHome || currentPage == pageAbout) {
            bar.setNavigationIcon(backArrow());
        }
        currentPage = page;
    }

    private MaterialToolbar tBar() {
        return findViewById(R.id.toolbar);
    }

    private Drawable backArrow() {
        Drawable d = AppCompatResources.getDrawable(this, R.drawable.ic_back);
        if (d != null) d.setTint(ContextCompat.getColor(this, android.R.color.white));
        return d;
    }

    /** 系统返回键 / 工具栏返回箭头：子页面回到首页，顶层页面则退出。 */
    private void registerBackHandler() {
        tBar().setNavigationOnClickListener(v -> {
            if (currentPage != null && currentPage != pageHome && currentPage != pageAbout) {
                showPage(pageHome);
            } else {
                finish();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentPage != null && currentPage != pageHome && currentPage != pageAbout) {
                    showPage(pageHome);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // ================= 首页功能入口 =================

    private void setUpHomePage() {
        findViewById(R.id.card_export).setOnClickListener(v -> showPage(pageExport));
        // 首页独立入口：3D 预览（独立界面、独立按钮，不必先进导出页）
        try {
            findViewById(R.id.card_preview).setOnClickListener(v -> showPreviewSourceChooser());
        } catch (Throwable ignored) {
            // 老布局没有这张卡片时忽略
        }
        // 首页独立入口：AI 动画助手（自然语言 → 动画/粒子 JSON 与 Molang）
        try {
            findViewById(R.id.card_ai).setOnClickListener(v ->
                    startActivity(new Intent(this, AiAnimActivity.class)));
        } catch (Throwable ignored) {
        }
        findViewById(R.id.card_convert).setOnClickListener(v -> showPage(pageConvert));
        findViewById(R.id.card_world).setOnClickListener(v -> {
            showPage(pageWorld);
            refreshWorldInfoFromCurrent();
        });
        findViewById(R.id.card_bb_online).setOnClickListener(v -> openBlockbench("online"));
        findViewById(R.id.card_bb_offline).setOnClickListener(v -> openBlockbench("offline"));
        // 首页独立入口：PNG 转模型（纯色背景像素图 → .bbmodel）
        try {
            findViewById(R.id.card_png2model).setOnClickListener(v ->
                    startActivity(new Intent(this, PngToModelActivity.class)));
        } catch (Throwable ignored) {
        }
        // 首页独立入口：bb 模型转 OBJ（内置离线 Blockbench 内核做转换）
        try {
            findViewById(R.id.card_bb2obj).setOnClickListener(v ->
                    startActivity(new Intent(this, BbToObjActivity.class)));
        } catch (Throwable ignored) {
        }
        // 首页独立入口：粒子编辑器（Snowstorm 离线版 · 汉化；导出进「下载/MinewaysMobile/粒子」）
        try {
            findViewById(R.id.card_snowstorm).setOnClickListener(v ->
                    startActivity(new Intent(this, SnowstormActivity.class)));
        } catch (Throwable ignored) {
        }
        findViewById(R.id.card_tools).setOnClickListener(v -> {
            showPage(pageTools);
            refreshToolsStatus();
        });
        findViewById(R.id.card_minigame).setOnClickListener(v -> openMiniGame());
    }

    private void openBlockbench(String mode) {
        try {
            Intent i = new Intent(this, BlockbenchActivity.class);
            i.putExtra(BlockbenchActivity.EXTRA_MODE, mode);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开 Blockbench：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开内置小游戏《寂零快跑》（纯静态 HTML5，完全离线，进度存本机）。 */
    private void openMiniGame() {
        try {
            startActivity(new Intent(this, MiniGameActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开小游戏：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 快捷工具页 =================

    private void setUpToolsPage() {
        findViewById(R.id.btn_tools_open_exports)
                .setOnClickListener(v -> openFolder(exportDir));
        findViewById(R.id.btn_tools_open_converted)
                .setOnClickListener(v -> openFolder(new File(
                        new File(getExternalFilesDir(null), "converted"), "world")));
        findViewById(R.id.btn_tools_copy_world)
                .setOnClickListener(v -> copyCurrentWorldPath());
        findViewById(R.id.btn_tools_probe)
                .setOnClickListener(v -> probeCurrentWorld());
        findViewById(R.id.btn_tools_bb_docs)
                .setOnClickListener(v -> openExternalUrl("https://www.blockbench.net/wiki"));
        findViewById(R.id.btn_tools_bb_repo)
                .setOnClickListener(v -> openExternalUrl("https://github.com/JannisX11/blockbench"));
    }

    private void refreshToolsStatus() {
        if (currentWorldDir != null) {
            tvToolsStatus.setText("当前存档：" + currentWorldDir.getAbsolutePath());
        } else {
            tvToolsStatus.setText(getString(R.string.tools_no_world));
        }
    }

    private void copyCurrentWorldPath() {
        if (currentWorldDir == null) {
            tvToolsStatus.setText(getString(R.string.tools_no_world));
            return;
        }
        copyToClipboard(currentWorldDir.getAbsolutePath(), "存档路径已复制到剪贴板");
    }

    private void probeCurrentWorld() {
        if (currentWorldDir == null) {
            tvToolsStatus.setText(getString(R.string.tools_no_world));
            return;
        }
        refreshWorldInfo(currentWorldDir.getAbsolutePath());
    }

    private void openFolder(final File dir) {
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, "目录不存在，请先执行一次导出或转换。", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", dir);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "打开： " + dir.getAbsolutePath()));
        } catch (Throwable t) {
            copyToClipboard(dir.getAbsolutePath(), "无法打开目录，路径已复制到剪贴板");
        }
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 转换页 =================

    private void setUpConvertPage() {
        etConvertSrc = findViewById(R.id.et_convert_src);
        acConvertTarget = findViewById(R.id.ac_convert_target);
        tvConvertResult = findViewById(R.id.tv_convert_result);
        btnConvert = findViewById(R.id.btn_convert);
        btnConvertCancel = findViewById(R.id.btn_convert_cancel);

        // 输出格式下拉
        List<ChunkerConverter.OutputFormat> formats;
        try {
            formats = ChunkerConverter.listOutputFormats();
        } catch (Throwable t) {
            setConvertResult("无法加载目标格式：" + t.getMessage());
            formats = null;
        }
        if (formats != null && !formats.isEmpty()) {
            ArrayAdapter<ChunkerConverter.OutputFormat> formatAdapter =
                    new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, formats);
            acConvertTarget.setAdapter(formatAdapter);
            acConvertTarget.setText(formatAdapter.getItem(0).label, false);
            convertTargetId = formatAdapter.getItem(0).id;
            acConvertTarget.setOnItemClickListener((parent, view, pos, id) -> {
                convertTargetId = formatAdapter.getItem(pos).id;
            });
        } else {
            acConvertTarget.setEnabled(false);
        }

        findViewById(R.id.btn_pick_convert_src).setOnClickListener(v -> pickConvertLauncher.launch(null));

        btnConvert.setOnClickListener(v -> startConvert());

        btnConvertCancel.setOnClickListener(v -> {
            convertCancelled.set(true);
            setConvertWorking("正在取消…");
        });

        installCoordinatePaste();
        installExportOptions();
    }

    // ================= 坐标框：粘贴即解析（不用手动一个个输入） =================

    /**
     * 让 6 个坐标框支持"粘贴一坨坐标"并自动分配：
     * 数字键盘的输入过滤器默认会把空格、逗号、X:、括号全部吃掉，所以这里先放宽过滤，
     * 再用【文本变化】和【长按读剪贴板】两条路解析。
     * 支持写法：`0 -60 0`、`-7,-53,-7`、`X: -7 Y: -53 Z: -7`、`(-7, -53, -7)`、
     * `/tp @s -7 -53 -7`、全角标点、`§` 颜色码、小数（向下取整）。
     * 粘 3 个数 → 填"起点"或"终点"（看粘在哪个框）；粘 6 个数 → 同时填两组；随后逐轴自动排序。
     */
    private void installCoordinatePaste() {
        EditText[] fields = {findViewById(R.id.et_minx), findViewById(R.id.et_miny), findViewById(R.id.et_minz),
                findViewById(R.id.et_maxx), findViewById(R.id.et_maxy), findViewById(R.id.et_maxz)};
        for (final EditText f : fields) {
            if (f == null) continue;

            f.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
                StringBuilder sb = new StringBuilder(end - start);
                for (int i = start; i < end; i++) {
                    char c = source.charAt(i);
                    if (isCoordinateChar(c)) sb.append(c);
                }
                if (sb.length() == end - start) return null;   // 没问题，原样放行
                return sb.toString();                            // 丢掉用不到的字符
            }});

            f.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (applyingCoordinates || count < 3) return;   // 逐字输入不处理，避免打断手输
                    String added = s.subSequence(start, start + count).toString();
                    if (parseCoordinateTriples(added).isEmpty()) return;
                    applyPastedCoordinates(f, added);
                }
                @Override public void afterTextChanged(Editable s) { }
            });

            // 长按：直接读剪贴板解析；返回 false 让系统菜单照常弹出，不影响原来的粘贴/全选
            f.setOnLongClickListener(v -> {
                String clip = readClipboardText();
                if (clip != null && !parseCoordinateTriples(clip).isEmpty()) {
                    applyPastedCoordinates(f, clip);
                }
                return false;
            });
        }
    }

    /** 坐标可能用到的字符（数字/正负号/小数点/分隔符/XYZ 前缀/括号/颜色码）。 */
    private static boolean isCoordinateChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= '０' && c <= '９') return true;
        switch (c) {
            case '+': case '-': case '.': case 'x': case 'X': case 'y': case 'Y': case 'z': case 'Z':
            case ' ': case ',': case ':': case '(': case ')': case '/':
            case '，': case '、': case '：': case '（': case '）': case '－': case '＋': case '　': case '§':
                return true;
            default:
                return false;
        }
    }

    /** 填充 6 个坐标框期间抑制解析，避免递归触发。 */
    private boolean applyingCoordinates = false;

    /** 读取剪贴板首条文本（失败返回 null）。 */
    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence t = clip.getItemAt(0).getText();
            return t == null ? null : t.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析并填入坐标框。3 个数按"粘在哪个框"决定填起点还是终点，6 个数两组都填。 */
    private void applyPastedCoordinates(EditText src, String raw) {
        List<int[]> groups = parseCoordinateTriples(raw);
        if (groups.isEmpty()) return;

        EditText minx = findViewById(R.id.et_minx), miny = findViewById(R.id.et_miny), minz = findViewById(R.id.et_minz);
        EditText maxx = findViewById(R.id.et_maxx), maxy = findViewById(R.id.et_maxy), maxz = findViewById(R.id.et_maxz);
        boolean srcIsMax = src != null && (src.getId() == R.id.et_maxx || src.getId() == R.id.et_maxy || src.getId() == R.id.et_maxz);

        int[] a = null, b = null;
        if (groups.size() >= 2) {
            a = groups.get(0);
            b = groups.get(1);
        } else if (srcIsMax) {
            b = groups.get(0);
        } else {
            a = groups.get(0);
        }

        applyingCoordinates = true;
        try {
            if (a != null) {
                minx.setText(String.valueOf(a[0]));
                miny.setText(String.valueOf(a[1]));
                minz.setText(String.valueOf(a[2]));
            }
            if (b != null) {
                maxx.setText(String.valueOf(b[0]));
                maxy.setText(String.valueOf(b[1]));
                maxz.setText(String.valueOf(b[2]));
            }
            normalizeCoordinateRange(minx, miny, minz, maxx, maxy, maxz);
        } finally {
            applyingCoordinates = false;
        }

        StringBuilder msg = new StringBuilder("已填入 ");
        if (a != null) msg.append("起点(").append(a[0]).append(",").append(a[1]).append(",").append(a[2]).append(")");
        if (a != null && b != null) msg.append("  ");
        if (b != null) msg.append("终点(").append(b[0]).append(",").append(b[1]).append(",").append(b[2]).append(")");
        msg.append("；可再粘另一个角点");
        Toast.makeText(this, msg.toString(), Toast.LENGTH_SHORT).show();
    }

    /** 逐轴排序，保证每个轴的 最小 ≤ 最大（粘贴的角点顺序不定）。 */
    private void normalizeCoordinateRange(EditText minx, EditText miny, EditText minz,
                                          EditText maxx, EditText maxy, EditText maxz) {
        EditText[] mins = {minx, miny, minz};
        EditText[] maxs = {maxx, maxy, maxz};
        for (int i = 0; i < 3; i++) {
            int mn = readCoordinate(mins[i], Integer.MIN_VALUE);
            int mx = readCoordinate(maxs[i], Integer.MAX_VALUE);
            if (mn != Integer.MIN_VALUE && mx != Integer.MAX_VALUE && mn > mx) {
                mins[i].setText(String.valueOf(mx));
                maxs[i].setText(String.valueOf(mn));
            }
        }
    }

    private static int readCoordinate(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 把任意文本里的数字按 3 个一组解析成坐标组（最多 2 组）。
     * 先去掉颜色码、全角归一、去掉 X:/Y:/Z: 前缀，再抓数字；小数向下取整。
     */
    private static List<int[]> parseCoordinateTriples(String raw) {
        List<int[]> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        String s = raw.replaceAll("\u00a7.", " ");
        s = s.replace('，', ' ').replace('、', ' ').replace('；', ' ')
                .replace('：', ':').replace('（', '(').replace('）', ')')
                .replace('－', '-').replace('＋', '+').replace('　', ' ')
                .replace('．', '.');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') sb.append((char) ('0' + (c - '０')));
            else sb.append(c);
        }
        s = sb.toString().replaceAll("(?i)\\b[xXyYzZ]\\s*:", " ");

        List<Integer> nums = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(s);
        while (m.find()) {
            try {
                nums.add((int) Math.floor(Double.parseDouble(m.group())));
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i + 2 < nums.size() && out.size() < 2; i += 3) {
            out.add(new int[]{nums.get(i), nums.get(i + 1), nums.get(i + 2)});
        }
        return out;
    }

    private void setConvertResult(String msg) {
        if (tvConvertResult != null) tvConvertResult.setText(msg);
    }

    private void setConvertWorking(String msg) {
        setConvertResult(msg);
        btnConvert.setEnabled(false);
        btnConvert.setVisibility(View.GONE);
        btnConvertCancel.setVisibility(View.VISIBLE);
    }

    private void setConvertIdle() {
        btnConvert.setEnabled(true);
        btnConvert.setVisibility(View.VISIBLE);
        btnConvertCancel.setVisibility(View.GONE);
    }

    private void startConvert() {
        if (convertSourceDir == null) {
            setConvertResult("请先选择要转换的存档目录。");
            return;
        }
        if (convertTargetId == null) {
            setConvertResult("请选择目标格式。");
            return;
        }
        // 后端以 convertSourceDir（选取回调里确定的目录）为准，不依赖输入框的回显文本。
        // 这样即便界面未显示路径，转换也会读取正确的存档目录。
        if (!new File(convertSourceDir, "level.dat").exists()) {
            setConvertResult("所选存档无效（缺少 level.dat）。");
            return;
        }

        convertCancelled.set(false);
        setConvertWorking("正在准备转换（首次使用会解压内置映射，稍等）…");

        File outputDir = new File(new File(getExternalFilesDir(null), "converted"), "world");
        ChunkerConverter.convert(this, convertSourceDir, outputDir,
                convertTargetId,
                new ChunkerConverter.ProgressListener() {
                        @Override
                        public void onProgress(double progress) {
                            runOnUiThread(() -> setConvertWorking(String.format("转换进度：%.0f%%", progress * 100)));
                        }

                        @Override
                        public void onSuccess(String message) {
                            runOnUiThread(() -> {
                                setConvertResult(message);
                                setConvertIdle();
                            });
                        }

                        @Override
                        public void onFailure(String message) {
                            runOnUiThread(() -> {
                                setConvertIdle();
                                // 转换失败同样落盘 + 复制 + 弹可选中对话框
                                reportConvertFailure(message);
                            });
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> {
                                setConvertResult("转换已取消。");
                                setConvertIdle();
                            });
                        }
                    },
                    () -> convertCancelled.get());
    }

    private void setWorkInProgress(String msg) {
        tvResult.setText(msg);
        btnExport.setEnabled(false);
    }

    private void setWorkDone() {
        btnExport.setEnabled(true);
    }

    private void refreshWorldInfoFromCurrent() {
        if (currentWorldDir != null) refreshWorldInfo(currentWorldDir.getAbsolutePath());
    }

    private void refreshWorldInfo(String dir) {
        try {
            String info = probeWorld(dir);
            tvWorldText.setText("存档信息：\n" + info);
        } catch (Throwable t) {
            tvWorldText.setText("读取失败：" + t.getMessage());
        }
    }

    /**
     * 自动识别并就地解密网易版加密存档（SAF 导出页路径）。
     * <p/>
     * 仅在检测到网易 XOR 加密时才会改写文件；普通存档原样跳过，因此不改变现有功能。
     * 解密在后台线程执行，避免阻塞主线程；完成后刷新一次世界信息并提示用户。
     *
     * @param worldDir 存档根目录（含 level.dat）。
     * @param path     用于刷新存档信息的绝对路径（通常即 worldDir）。
     */
    private void decryptNeteaseIfNeeded(final File worldDir, final String path) {
        new Thread(() -> {
            NeteaseDecryptor.Result res;
            try {
                res = NeteaseDecryptor.decrypt(worldDir, null);
            } catch (Throwable t) {
                res = null;
            }
            final NeteaseDecryptor.Result r = res;
            runOnUiThread(() -> {
                if (r == null) return;
                if (r.encrypted && r.success) {
                    tvResult.setText("网易加密存档已解密 " + r.decryptedFiles + " 个文件，可正常使用。");
                    refreshWorldInfo(path);
                } else if (r.encrypted && !r.success) {
                    tvResult.setText("检测到网易加密存档，但解密未完成：" + r.message);
                }
                // r 非加密（普通存档）时不做任何提示，保持原有行为
            });
        }, "netease-decrypt").start();
    }

    private void doExport() {
        String dir = etWorldDir.getText().toString().trim();
        if (TextUtils.isEmpty(dir)) {
            Toast.makeText(this, "请先选择存档目录", Toast.LENGTH_SHORT).show();
            return;
        }
        File wdir = new File(dir);
        if (!new File(wdir, "level.dat").exists()) {
            Toast.makeText(this, "该目录不是有效的存档（缺少 level.dat）", Toast.LENGTH_SHORT).show();
            return;
        }
        // 常见错误提前拦下：基岩版/网易版存档同样有 level.dat，但只有 db/、没有 region/，
        // 直接导出只会得到 "world too old (<Anvil>)"。这里一次说清该走「转换」页。
        if (!new File(wdir, "region").isDirectory() && new File(wdir, "db").isDirectory()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("这是基岩版存档，不能直接导出 OBJ")
                    .setMessage("检测到 db/ 目录（基岩版特征），且没有 region/ 目录。\n\n"
                            + "请先到「转换」页把这个存档转成 Java 版，再用转出的世界（含 region/）来导出。")
                    .setPositiveButton("去「转换」页", (d, w) -> showPage(pageConvert))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        int minx = parseInt(R.id.et_minx, -64);
        int miny = parseInt(R.id.et_miny, -64);
        int minz = parseInt(R.id.et_minz, -64);
        int maxx = parseInt(R.id.et_maxx, 64);
        int maxy = parseInt(R.id.et_maxy, 319);
        int maxz = parseInt(R.id.et_maxz, 64);
        if (maxx < minx || maxz < minz) {
            Toast.makeText(this, "X/Z 选区范围不正确（起点应小于等于终点）", Toast.LENGTH_SHORT).show();
            return;
        }
        // 常见手误：Y 起点/终点写反（如 Y -53..-60）。以前会静默继续，
        // 这里自动对调并明确告知，避免"导出 0 方块"的困惑。
        // 注意：导出线程的 lambda 会捕获这些值，所以必须是 final（不能改写 miny/maxy 本身）。
        final int y0 = Math.min(miny, maxy);
        final int y1 = Math.max(miny, maxy);
        if (maxy < miny) {
            Toast.makeText(this, "Y 范围写反了，已自动对调为 Y " + y0 + ".." + y1, Toast.LENGTH_LONG).show();
        }
        String fileTypeStr = ((AutoCompleteTextView) findViewById(R.id.ac_filetype))
                .getText().toString();
        int fileType = FILE_TYPE_WAVEFRONT_ABS_OBJ; // 本版本固定 OBJ

        // 面剔除方案：下拉索引 0=标准 1=全显示 2=强剔除
        String cullStr = ((AutoCompleteTextView) findViewById(R.id.ac_cullmode))
                .getText().toString();
        String[] cullModes = getResources().getStringArray(R.array.cull_modes);
        int cullMode = 1; // 默认标准
        for (int i = 0; i < cullModes.length; i++) {
            if (cullModes[i].equals(cullStr)) { cullMode = i; break; }
        }
        // cull_modes 数组：0=标准 1=全显示 2=强 → native 的 cullMode：1=标准 0=全显示 2=强
        int nativeCullMode = (cullMode == 0) ? 1 : ((cullMode == 1) ? 0 : 2);

        // 面数简化：以偏好为唯一权威（导出页开关只是它的镜像，点开关时已即时写回偏好）。
        // 注意：这里不能再把开关状态写回偏好——否则在选项面板里取消勾选后，一导出又会被打开。
        int decimateOn = exportOpts().getBoolean("decimate", false) ? 1 : 0;

        final String outBase = new File(exportDir, "mineways_export").getAbsolutePath();
        setWorkInProgress("正在导出，请稍候（大型模型可能耗时较长）…");

        new Thread(() -> {
            final String r;
            // 先清掉上一次导出的产物：否则旧的 .mtl / tex 贴图会被一起打包进 ZIP，
            // 表现为"选了不导出材质，结果还是有材质"（OBJ 本身早已没有 mtllib）。
            cleanPreviousExport(exportDir);
            try {
                r = exportWorld(wdir.getAbsolutePath(), outBase, fileType,
                        minx, y0, minz, maxx, y1, maxz,
                        nativeCullMode, decimateOn, buildExportOptions());
            } catch (Throwable t) {
                // 导出外抛异常（如选项数值类型不匹配）也走完整报错通道：
                // 本地报错文件（主通道）+ 剪贴板 + 可长按选中的对话框，并带上堆栈便于定位。
                String msg = "导出异常：" + t + "\n" + android.util.Log.getStackTraceString(t);
                runOnUiThread(() -> {
                    reportExportFailure(msg);
                    setWorkDone();
                });
                return;
            }
            // 原生首行协议：OK files=N / WARN files=N / ERR <code> <name>（后面跟诊断报告）
            // 只有 files>0 才算成功——以前只要不以 ERR 开头就报"导出成功"，
            // 于是 files=0 err=512（没有任何方块）也被显示成成功。
            final boolean coreOk = isExportSuccess(r);
            // 模组转换（默认关）：把选区内的模组方块按上传的模组文件合并进 OBJ/MTL。
            // 这里刻意不看核心是否成功：纯模组选区（一个原版方块都没有）核心会 files=0，
            // 但模组段照样要能导出，能不能落地交给 ModOutput 自己判断。
            String modReport = null;
            boolean modMerged = false;
            if (exportOpts().getBoolean("modconvert", false)) {
                try {
                    java.util.List<Uri> modUris = decodeModUris(exportOpts().getString("mod_uris", ""));
                    if (modUris.isEmpty()) {
                        modReport = "[模组转换] 已开启但未选择模组文件：请在「导出选项 → 模组转换」里点「选择模组文件」。\n";
                    } else {
                        File objF = new File(outBase + ".obj");
                        File mtlF = new File(outBase + ".mtl");
                        File texDir = new File(exportDir, exportOpts().getString("tiledir", "tex"));
                        boolean nf = new File(wdir, "dimensions").isDirectory();
                        int dimV = exportOpts().getInt("dim", 0);
                        if (dimV < 0) dimV = 0;
                        ModOutput.Result mr = ModOutput.run(this, objF, mtlF, texDir, wdir, dimV, nf,
                                minx, y0, minz, maxx, y1, maxz, modUris);
                        modReport = mr.report;
                        modMerged = mr.merged;
                    }
                } catch (Throwable t) {
                    modReport = "[模组转换] 失败：" + t + "（原版导出结果未受影响）\n";
                }
            }
            // 核心没找到任何方块、但模组段合并成功时，这次导出整体算成功——否则纯模组选区会被判"导出失败"，
            // 连 ZIP 也不会发布，用户拿不到任何东西。
            final boolean ok = coreOk || modMerged;
            // 成功时把结果（obj/mtl/贴图）打包成 zip 放到用户可见的「下载/MinewaysMobile/」：
            // 否则文件全在 Android/data/... 里，Android 11+ 文件管理器根本进不去，等于拿不到模型。
            final boolean wantZip = exportOpts().getBoolean("zip", true);
            final String published = (ok && wantZip) ? publishExportZipToDownloads() : null;
            final String report = buildExportReport(r, ok, wdir, minx, y0, minz, maxx, y1, maxz,
                    cullStr, decimateOn)
                    + (modReport != null ? ("\n" + modReport) : "")
                    + (published != null ? ("\n[已导出 ZIP] " + published + "\n") : "");
            runOnUiThread(() -> {
                tvResult.setText(report);
                if (ok) {
                    Toast.makeText(MainActivity.this, "导出完成", Toast.LENGTH_SHORT).show();
                } else {
                    // 失败：写入本地报错文件（主通道）+ 尝试复制剪贴板 + 弹可选中对话框
                    reportExportFailure(report);
                }
                setWorkDone();
            });
        }).start();
    }

    /** 导出是否真的产出了文件（只有 files>0 才算成功）。 */
    private static boolean isExportSuccess(String r) {
        if (r == null) return false;
        String first = r.split("\n", 2)[0].trim();
        if (first.startsWith("ERR")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("files=(\\d+)").matcher(first);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) > 0;
            } catch (Exception e) {
                return false;
            }
        }
        return first.startsWith("OK") || first.startsWith("WARN");
    }

    /** 组装最终报告：环境/参数上下文 + 原生诊断报告，便于用户整段粘贴反馈。 */
    private String buildExportReport(String nativeResult, boolean ok, File worldDir,
                                     int minx, int miny, int minz, int maxx, int maxy, int maxz,
                                     String cullStr, int decimateOn) {
        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "导出完成\n" : "导出失败\n");
        sb.append("—— 环境信息 ——\n");
        sb.append("[应用] Mineways Mobile ").append(appVersionName()).append('\n');
        sb.append("[系统] Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")  ")
                .append(java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS)).append('\n');
        sb.append("[设备] ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        int dimV = exportOpts().getInt("dim", 0);
        if (dimV < 0) dimV = 0;   // "不选" → 主世界
        String dimStr = dimV == 1 ? "下界" : (dimV == 2 ? "末地" : "主世界");
        sb.append("[参数] 输出=OBJ 绝对坐标 ；框选 X ").append(minx).append("..").append(maxx)
                .append(", Y ").append(miny).append("..").append(maxy)
                .append(", Z ").append(minz).append("..").append(maxz)
                .append(" ；维度=").append(dimStr)
                .append(" ；面剔除=").append(cullStr)
                .append(" ；合并共面=").append(decimateOn != 0 ? "开" : "关").append('\n');
        if (dimV != 0) {
            sb.append("[提示] 当前维度：").append(dimStr)
              .append("。建筑若在别的维度，导出的会是\"另一张地图\"")
              .append("——到「导出选项 → 维度」改对再导。\n");
        }
        sb.append("[世界目录] ").append(worldDir == null ? "?" : worldDir.getAbsolutePath()).append('\n');
        sb.append("—— 原生核心报告 ——\n");
        sb.append(nativeResult == null ? "(空)" : nativeResult.trim()).append('\n');
        sb.append("（以上内容已保存为本地报错文件，也可整段复制粘贴反馈）\n");
        sb.append("—— 提示 ——\n");
        sb.append("点「导出选项」按钮（或长按「开始导出」）可设置：创建分组对象 / 实心树叶 / 焊接所有共享边、")
          .append("肥大块、告示牌翻倍、掏空与超中空、封闭入口、连接零件、删除浮动对象、Z 向上、旋转 0-270°、纹理模式等。\n");
        sb.append("点「预览模型（自动贴图）」可在本机查看刚导出的模型：指尖旋转、双指缩放，可切线框/贴图/自动旋转。\n");
        return sb.toString();
    }

    /** 报错上报结果：展示路径 + 可分享的私有副本。 */
    private static final class ErrorReport {
        String display = "";
        File shareFile = null;
    }

    /** 转换失败的报错上报（与导出共用：文件为主通道 + 剪贴板 + 可选中对话框）。 */
    private void reportConvertFailure(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("转换失败\n");
        sb.append("—— 环境信息 ——\n");
        sb.append("[应用] Mineways Mobile ").append(appVersionName()).append('\n');
        sb.append("[系统] Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")  ")
                .append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("[设备] ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("[源存档] ").append(convertSourceDir == null ? "?" : convertSourceDir.getAbsolutePath()).append('\n');
        sb.append("[目标格式] ").append(convertTargetId == null ? "?" : convertTargetId).append('\n');
        sb.append("—— 报错 ——\n").append(message == null ? "(空)" : message).append('\n');
        sb.append("（以上内容已保存为本地报错文件，也可整段复制粘贴反馈）\n");

        String report = sb.toString();
        ErrorReport out = writeErrorReportFile(report);
        boolean copied = copyToClipboardQuiet(report);
        String shown = report + "\n—— 报错文件 ——\n" + out.display
                + "\n（剪贴板：" + (copied ? "已复制" : "复制未生效，请用上面的文件") + "）\n";
        setConvertResult(shown);
        showExportErrorDialog("转换失败", shown, report, out, copied);
    }

    /**
     * 导出失败的多通道上报：本地文件（主通道）+ 剪贴板 + 可长按选中的对话框。
     * <p>
     * 剪贴板不可靠的原因：Android 10+ 只允许处于前台焦点窗口的应用写剪贴板，
     * 导出耗时较长时用户若切走过应用，写入会静默失败；部分定制系统还会弹"已复制"却不落内容。
     * 所以把"报错文件"作为主通道，剪贴板只当作加速通道。
     */
    private void reportExportFailure(String report) {
        ErrorReport out = writeErrorReportFile(report);
        boolean copied = copyToClipboardQuiet(report);
        // 界面只显示"短提示"（协议行 + [原因与处理]）；超长技术报告仍完整写进报错文件并可由按钮复制
        String shown = hintPart(report)
                + "\n—— 报错文件（含完整技术报告）——\n" + out.display + "\n"
                + "（\"再复制一次\"复制的是**完整报告**；" + (copied ? "已复制" : "剪贴板写入未生效") + "）\n";
        tvResult.setText(shown);
        showExportErrorDialog("导出失败", shown, report, out, copied);
    }

    /**
     * 截出"短提示"部分：从开头到完整报告标题为止（协议行 + 环境信息 + [原因与处理]）。
     * 目的：常见错误一眼能看懂该改什么，不必翻超长报告；细节仍在报错文件与复制内容里。
     */
    private static String hintPart(String nativeResult) {
        if (nativeResult == null || nativeResult.trim().isEmpty()) return "(没有收到核心返回内容)";
        String r = nativeResult.trim();
        int cut = r.indexOf("—— 导出诊断报告");
        if (cut > 0) r = r.substring(0, cut).trim();
        if (r.length() > 1400) r = r.substring(0, 1400) + "\n…（完整内容见报错文件）";
        return r;
    }

    /**
     * 把报错写进本地文件。
     * 1) 公共「下载/MinewaysMobile/导出报错/」：文件管理器可见（Android 10+ 走 MediaStore，无需权限）；
     * 2) 应用外部目录 exports/导出报错/：一定能写，且已被 FileProvider 放通，可直接分享给别人。
     */
    private ErrorReport writeErrorReportFile(String report) {
        ErrorReport result = new ErrorReport();
        String name = "报错-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
        byte[] data;
        try {
            data = report.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            data = report.getBytes();
        }

        // 1) 公共下载目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MinewaysMobile/导出报错");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data);
                            result.display = "内部存储/Download/MinewaysMobile/导出报错/" + name;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } else {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "MinewaysMobile/导出报错");
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, name);
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(data);
                    }
                    result.display = "内部存储/Download/MinewaysMobile/导出报错/" + name;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2) 应用外部目录（一定能写；该目录已在 file_paths.xml 的 exports/ 下，可被分享）
        try {
            File base = getExternalFilesDir(null);
            if (base == null) base = getFilesDir();
            File dir = new File(new File(base, "exports"), "导出报错");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
            result.shareFile = f;
            if (result.display.isEmpty()) {
                result.display = f.getAbsolutePath();
            }
            // 再留一份固定名，方便反复查找
            try {
                File latest = new File(dir, "最近一次报错.log");
                try (OutputStream os = new FileOutputStream(latest)) {
                    os.write(data);
                }
                if (result.shareFile == null) result.shareFile = latest;
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            if (result.display.isEmpty()) result.display = "(报错文件写入失败：" + t + ")";
        }
        return result;
    }

    /** 写剪贴板并回读校验；不抛异常，返回是否确实写进去了。 */
    private boolean copyToClipboardQuiet(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("MinewaysMobile导出报错", text));
            try {
                ClipData back = cm.getPrimaryClip();
                if (back != null && back.getItemCount() > 0 && back.getItemAt(0).getText() != null) {
                    return back.getItemAt(0).getText().length() == text.length();
                }
            } catch (Throwable ignored) {
                // 部分系统读取剪贴板需要焦点，读不到不代表写失败
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 报错对话框：正文可长按选中，另有"再复制一次 / 分享报错文件"。 */
    private void showExportErrorDialog(final String title, final String shown, final String rawReport,
                                       final ErrorReport out, final boolean copied) {
        try {
            TextView tv = new TextView(this);
            tv.setText(shown);
            tv.setTextIsSelectable(true);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(11f);
            int pad = (int) (14 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);

            new MaterialAlertDialogBuilder(this)
                    .setTitle(title + (copied ? "（报错已复制并写入文件）" : "（报错已写入文件）"))
                    .setView(sv)
                    .setPositiveButton("再复制一次", (d, w) -> {
                        boolean ok = copyToClipboardQuiet(rawReport);
                        Toast.makeText(MainActivity.this,
                                ok ? "已复制到剪贴板" : "复制失败，请长按选中文字手动复制",
                                Toast.LENGTH_LONG).show();
                    })
                    .setNeutralButton("分享报错文件", (d, w) -> shareErrorReport(out))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable ignored) {
            // 对话框不可用也不影响文件与剪贴板
        }
    }

    /** 把报错文件分享出去（微信/QQ/邮件等）；文件不可用时退回发送路径文本。 */
    private void shareErrorReport(ErrorReport out) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            File f = out.shareFile;
            boolean shared = false;
            if (f != null && f.isFile()) {
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    shared = true;
                } catch (Throwable ignored) {
                    shared = false;
                }
            }
            if (!shared) {
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, "报错文件位置：" + out.display);
            }
            i.putExtra(Intent.EXTRA_SUBJECT, "Mineways Mobile 导出报错");
            startActivity(Intent.createChooser(i, "分享报错"));
        } catch (Throwable t) {
            Toast.makeText(this, "分享失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把导出结果（.obj / .mtl / 贴图）打包成一个 zip，放到用户可见的「下载/MinewaysMobile/」。
     * Android 10+ 走 MediaStore（无需存储权限），8/9 直写公共下载目录；
     * 失败也不影响已完成的导出，只是返回 null。
     *
     * @return 可读位置说明，或 null
     */
    private String publishExportZipToDownloads() {
        File tmpZip = null;
        try {
            if (exportDir == null || !exportDir.isDirectory()) return null;
            File[] files = exportDir.listFiles();
            if (files == null || files.length == 0) return null;

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String zipName = "mineways_export-" + stamp + ".zip";
            // 先写进应用缓存（MediaStore 需要一次性写入完整文件，不能流式追加）
            tmpZip = new File(getCacheDir(), zipName);
            int entries;
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmpZip)))) {
                entries = addDirToZip(zos, exportDir, "");
            }
            if (entries == 0) return null;

            if (!copyOneToDownloads(tmpZip, Environment.DIRECTORY_DOWNLOADS + "/MinewaysMobile")) return null;
            return "内部存储/Download/MinewaysMobile/" + zipName
                    + "（" + entries + " 个文件，" + formatSize(tmpZip.length()) + "）";
        } catch (Throwable t) {
            return null;
        } finally {
            if (tmpZip != null) tmpZip.delete();
        }
    }

    /** 递归把一个目录的文件写进 zip，返回写入的文件数。 */
    private static int addDirToZip(ZipOutputStream zos, File dir, String prefix) throws IOException {
        int count = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            // 报错日志目录不放进模型包里（它是诊断用的，不属于导出结果）
            if (f.isDirectory() && "导出报错".equals(f.getName())) continue;
            String name = prefix + f.getName();
            if (f.isDirectory()) {
                count += addDirToZip(zos, f, name + "/");
            } else {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                try (InputStream in = new java.io.FileInputStream(f)) {
                    copyStream(in, zos);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    /**
     * 清掉上一次导出的产物（模型文件 + 贴图目录），避免旧文件污染本次结果。
     * <p>
     * 触发过的真实困惑：先导出过一次"带材质"，再选"不导出材质"重新导出——
     * OBJ 里确实没有 mtllib 了，但旧的 mineways_export.mtl 与 tex/*.png 仍留在目录里，
     * 而打包是整目录递归压缩，于是 ZIP 里"还是有材质"。
     * 只清已知的导出产物，不动「导出报错」日志目录。
     */
    private void cleanPreviousExport(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            String tile = exportOpts().getString("tiledir", "tex");
            if (tile == null || tile.trim().isEmpty()) tile = "tex";
            tile = tile.trim();
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File f : kids) {
                String n = f.getName();
                if (f.isDirectory()) {
                    if (!"导出报错".equals(n) && (n.equals(tile) || n.equals("tex"))) {
                        deleteRecursively(f);
                    }
                    continue;
                }
                String ln = n.toLowerCase(Locale.US);
                if (n.startsWith("mineways_export")
                        || ln.endsWith(".obj") || ln.endsWith(".mtl") || ln.endsWith(".png")
                        || ln.endsWith(".jpg") || ln.endsWith(".jpeg") || ln.endsWith(".mdl")) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(File f) {
        try {
            if (f == null || !f.exists()) return;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteRecursively(k);
            }
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 人类可读的体积文本。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 复制单个文件到公共下载目录下的相对路径（MediaStore 或直写）。 */
    private boolean copyOneToDownloads(File src, String relativePath) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, guessExportMime(src.getName()));
                v.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (InputStream in = new java.io.FileInputStream(src);
                     OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return false;
                    copyStream(in, os);
                }
                return true;
            }
            String sub = relativePath.startsWith("Download/") ? relativePath.substring("Download/".length()) : relativePath;
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub);
            if (!dir.exists() && !dir.mkdirs()) return false;
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream os = new FileOutputStream(new File(dir, src.getName()))) {
                copyStream(in, os);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void copyStream(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static String guessExportMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".obj") || n.endsWith(".mtl")) return "text/plain";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    // ================= 导出选项（桌面 Mineways 导出对话框的常用项，第一批） =================

    private static final String OPT_PREFS = "export_options";

    private android.content.SharedPreferences exportOpts() {
        android.content.SharedPreferences sp = getSharedPreferences(OPT_PREFS, MODE_PRIVATE);
        normalizeOptionTypes(sp);
        return sp;
    }

    /**
     * 统一选项数值类型，杜绝 SharedPreferences 的 ClassCastException。
     * <p>
     * 触发过的真实崩溃：{@code floatercount}（浮动对象最小方块数）由「数字行」用 putFloat 写入，
     * 导出时却用 getInt 读取 → {@code java.lang.Float cannot be cast to java.lang.Integer}。
     * 规则：单选项（mat/rotate/scale/units/physmat）必须是 int；数字行（modelheight/blocksize/
     * cost/hollowthick/floatercount）必须是 float。每次取用前规整一次，老版本存下的数据也会被修正。
     */
    private static void normalizeOptionTypes(android.content.SharedPreferences sp) {
        final String[] intKeys = {"mat", "rotate", "scale", "units", "physmat"};
        final String[] floatKeys = {"modelheight", "blocksize", "cost", "hollowthick", "floatercount"};
        java.util.Map<String, ?> all;
        try {
            all = sp.getAll();
        } catch (Throwable t) {
            return;
        }
        android.content.SharedPreferences.Editor ed = null;
        for (String k : intKeys) {
            Object v = all.get(k);
            if (v instanceof Number && !(v instanceof Integer)) {
                if (ed == null) ed = sp.edit();
                ed.putInt(k, ((Number) v).intValue());
            } else if (v instanceof Boolean) {
                if (ed == null) ed = sp.edit();
                ed.putInt(k, ((Boolean) v) ? 1 : 0);
            }
        }
        for (String k : floatKeys) {
            Object v = all.get(k);
            if (v instanceof Number) {
                float f = ((Number) v).floatValue();
                float c = clampOptionValue(k, f);
                // 类型不对或越界（老版本可能存过 0，会让核心除零）都纠正一次
                if (!(v instanceof Float) || c != f) {
                    if (ed == null) ed = sp.edit();
                    ed.putFloat(k, c);
                }
            }
        }
        if (ed != null) ed.apply();
    }

    /** 组装传给原生核心的选项串：k=v;k=v（键名对应 jni_export.cpp 的 optInt/optRaw），默认值与旧行为一致。 */
    private String buildExportOptions() {
        android.content.SharedPreferences sp = exportOpts();
        StringBuilder sb = new StringBuilder();
        sb.append("mat=").append(radioOrDefault(sp, "mat", 4));          // 单选"不选"→用默认值
        sb.append(";texrgb=").append(sp.getBoolean("texrgb", true) ? 1 : 0);
        sb.append(";texa=").append(sp.getBoolean("texa", true) ? 1 : 0);
        sb.append(";texrgba=").append(sp.getBoolean("texrgba", true) ? 1 : 0);
        String td = sp.getString("tiledir", "tex");
        if (td != null && !td.trim().isEmpty()) sb.append(";tiledir=").append(td.trim());
        sb.append(";rotate=").append(radioOrDefault(sp, "rotate", 0));
        sb.append(";dim=").append(radioOrDefault(sp, "dim", 0));   // 0=主世界 1=下界 2=末地
        sb.append(";zup=").append(sp.getBoolean("zup", false) ? 1 : 0);
        sb.append(";center=").append(sp.getBoolean("center", false) ? 1 : 0);
        sb.append(";decimate=").append(sp.getBoolean("decimate", false) ? 1 : 0);
        sb.append(";borderfaces=").append(sp.getBoolean("borderfaces", true) ? 1 : 0);
        sb.append(";leaves=").append(sp.getBoolean("leaves", false) ? 1 : 0);
        sb.append(";septypes=").append(sp.getBoolean("septypes", true) ? 1 : 0);
        sb.append(";split=").append(sp.getBoolean("split", true) ? 1 : 0);
        sb.append(";groups=").append(sp.getBoolean("groups", true) ? 1 : 0);
        sb.append(";indiv=").append(sp.getBoolean("indiv", false) ? 1 : 0);
        sb.append(";custommtl=").append(sp.getBoolean("custommtl", true) ? 1 : 0);
        sb.append(";fam=").append(sp.getBoolean("fam", true) ? 1 : 0);
        // —— 第二批：3D 打印（默认值与 initViewExportData 逐项一致）——
        sb.append(";scale=").append(radioOrDefault(sp, "scale", 2));          // 2=每个区块毫米（旧默认）
        sb.append(";modelheight=").append(sp.getFloat("modelheight", 100f));
        sb.append(";blocksize=").append(sp.getFloat("blocksize", 1000f));
        sb.append(";cost=").append(sp.getFloat("cost", 25f));
        sb.append(";units=").append(radioOrDefault(sp, "units", 0));          // 0=米（旧默认）
        sb.append(";physmat=").append(radioOrDefault(sp, "physmat", 1));      // 1=彩色砂岩（旧默认）
        sb.append(";p3d=").append(sp.getBoolean("p3d", true) ? 1 : 0);        // 1=材质适配 Prisma3D（默认开）
        sb.append(";hollow=").append(sp.getBoolean("hollow", false) ? 1 : 0);
        sb.append(";superhollow=").append(sp.getBoolean("superhollow", false) ? 1 : 0);
        sb.append(";hollowthick=").append(sp.getFloat("hollowthick", 1000f));
        sb.append(";sealentrances=").append(sp.getBoolean("sealentrances", false) ? 1 : 0);
        sb.append(";sealtunnels=").append(sp.getBoolean("sealtunnels", false) ? 1 : 0);
        sb.append(";fillbubbles=").append(sp.getBoolean("fillbubbles", false) ? 1 : 0);
        sb.append(";connectparts=").append(sp.getBoolean("connectparts", false) ? 1 : 0);
        sb.append(";connectcorners=").append(sp.getBoolean("connectcorners", false) ? 1 : 0);
        sb.append(";connectalledges=").append(sp.getBoolean("connectalledges", false) ? 1 : 0);
        sb.append(";deletefloaters=").append(sp.getBoolean("deletefloaters", false) ? 1 : 0);
        sb.append(";floatercount=").append((int) sp.getFloat("floatercount", 16f));   // 数字行存的是 Float，不能 getInt
        sb.append(";meltsnow=").append(sp.getBoolean("meltsnow", false) ? 1 : 0);
        sb.append(";compositeoverlay=").append(sp.getBoolean("compositeoverlay", false) ? 1 : 0);
        sb.append(";fatten=").append(sp.getBoolean("fatten", false) ? 1 : 0);       // 肥大块：块数/面数更少
        sb.append(";doubled=").append(sp.getBoolean("doubled", false) ? 1 : 0);     // 告示牌/花草翻倍（正反各一面）
        sb.append(";mergeflat=").append(sp.getBoolean("mergeflat", false) ? 1 : 0);  // 默认关：保持既有导出结果不变
        sb.append(";showparts=").append(sp.getBoolean("showparts", false) ? 1 : 0);
        sb.append(";showwelds=").append(sp.getBoolean("showwelds", false) ? 1 : 0);
        // 第四批：桌面对话框剩下的几项（默认值＝此前固定行为）
        sb.append(";mdl=").append(sp.getBoolean("mdl", false) ? 1 : 0);
        sb.append(";biome=").append(sp.getBoolean("biome", false) ? 1 : 0);
        sb.append(";exportall=").append(sp.getBoolean("exportall", true) ? 1 : 0);
        sb.append(";cremodel=").append(sp.getBoolean("cremodel", true) ? 1 : 0);
        sb.append(";dbggroups=").append(sp.getBoolean("dbggroups", false) ? 1 : 0);
        sb.append(";dbgwelds=").append(sp.getBoolean("dbgwelds", false) ? 1 : 0);
        return sb.toString();
    }

    /** 打开导出选项面板：显式「导出选项」按钮 + 长按「开始导出」两条入口。 */
    private void installExportOptions() {
        try {
            if (btnExport != null) {
                btnExport.setOnLongClickListener(v -> {
                    showExportOptionsDialog();
                    return true;
                });
            }
            android.view.View optBtn = findViewById(R.id.btn_export_options);
            if (optBtn != null) {
                optBtn.setOnClickListener(v -> showExportOptionsDialog());
            }

            // 「面数简化」的单一数据源修正：
            // 之前导出时会把导出页开关的状态强行写回偏好，导致"在选项面板里取消勾选后，
            // 一导出又被打开"（表现为选项打上了就取消不了）。现在改为：
            //   - 偏好是唯一权威（在选项面板里改、或点这个开关，都写同一份偏好）
            //   - 开关只是镜像：进入页面时按偏好刷新，点击时立即写偏好
            MaterialSwitch swDecimate = findViewById(R.id.sw_decimate);
            if (swDecimate != null) {
                swDecimate.setChecked(exportOpts().getBoolean("decimate", false));
                swDecimate.setOnCheckedChangeListener((v, checked) -> {
                    try {
                        exportOpts().edit().putBoolean("decimate", checked).apply();
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private TextView optTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 4);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private android.widget.CheckBox optSwitch(final String key, String label, boolean def) {
        final android.content.SharedPreferences sp = exportOpts();
        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText(label);
        cb.setChecked(sp.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, checked) -> {
            sp.edit().putBoolean(key, checked).apply();
            // 「面数简化」在导出页还有一个同名开关：两边保持同步，避免各说各话、看着"点了没用"
            if ("decimate".equals(key)) {
                MaterialSwitch sw = findViewById(R.id.sw_decimate);
                if (sw != null) sw.setChecked(checked);
            }
        });
        return cb;
    }

    /** 单选组里"不选（用默认）"的存储值（与 {@link ExportOptionSelector} 共用常量）。 */
    private static final int OPT_NONE = ExportOptionSelector.NONE;

    /**
     * 生成一组互斥单选按钮并接好"读/存"，同时支持**随时不选**。
     * <p>
     * 交互约定（三种状态都能随时切）：
     * <ul>
     *   <li><b>多选</b>：开关（CheckBox）随便勾，互不影响；</li>
     *   <li><b>单选</b>：单选组内互斥，点谁是谁；</li>
     *   <li><b>不选</b>：每组第一项固定为「不选（用默认）」；<b>再点一次已选中的那一项也会回到「不选」</b>。
     *       "不选"以 {@link #OPT_NONE} 存盘，导出时按该键默认值发送，行为与"从未设置"一致。</li>
     * </ul>
     * 另外两个实现要点：程序化创建的 {@code RadioButton} **必须显式 setId()**（RadioGroup 靠 id 记住
     * 当前选中项，否则无法互斥、点已选项还会取消不掉）；初始选中必须在 {@code addView} **之后**设置。
     *
     * @param key      存储键（putInt）
     * @param values   每个选项对应的存储值，**第一项应为 {@link #OPT_NONE}**（可与下标不同，如 0/90/180/270）
     * @param labels   选项文案，与 values 一一对应
     * @param defValue 缺省值（"不选"时回退到它）
     */
    private android.widget.RadioGroup optRadioGroup(final String key, final int[] values, String[] labels,
                                                    int defValue, boolean horizontal,
                                                    final android.content.SharedPreferences sp) {
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(horizontal
                ? android.widget.RadioGroup.HORIZONTAL : android.widget.RadioGroup.VERTICAL);
        int stored = sp.getInt(key, defValue);
        // 预选/取值规则全部走 ExportOptionSelector（与单元测试共用同一份实现）
        int checkedIndex = ExportOptionSelector.indexForStored(values, stored, defValue);
        final int noneIndex = ExportOptionSelector.noneIndex(values);
        for (int i = 0; i < labels.length && i < values.length; i++) {
            final android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(android.view.View.generateViewId());   // ★ 唯一 id：RadioGroup 靠它互斥
            rb.setText(labels[i]);
            // "再点一次已选中项 = 取消（回到不选）"：用按下瞬间的状态判断，避免刚选中就被撤掉
            final boolean[] wasChecked = new boolean[1];
            rb.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    wasChecked[0] = rb.isChecked();
                }
                return false;
            });
            rb.setOnClickListener(v -> {
                if (!wasChecked[0] || noneIndex < 0) return;
                android.view.View none = group.getChildAt(noneIndex);
                if (none instanceof android.widget.RadioButton) {
                    ((android.widget.RadioButton) none).setChecked(true);   // → 存 OPT_NONE
                }
            });
            group.addView(rb);
        }
        if (checkedIndex >= 0 && checkedIndex < group.getChildCount()) {
            ((android.widget.RadioButton) group.getChildAt(checkedIndex)).setChecked(true);
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            int idx = g.indexOfChild(g.findViewById(checkedId));
            int value = ExportOptionSelector.valueAt(values, idx);
            if (idx >= 0 && idx < values.length) {
                sp.edit().putInt(key, value).apply();
            }
        });
        return group;
    }

    /** 单选键取值：为负（不选）时用默认值，等价于"从未设置"。 */
    private static int radioOrDefault(android.content.SharedPreferences sp, String key, int def) {
        return ExportOptionSelector.valueOrDefault(sp.getInt(key, def), def);
    }

    /** 单选键 → 默认值（供「不选」回退与全选使用）。 */
    private static final String[] OPT_RADIO_KEYS = {"mat", "rotate", "scale", "units", "physmat", "dim"};
    private static final int[] OPT_RADIO_DEFAULTS = {4, 0, 2, 0, 1, 0};

    /** 开关键 → 默认值（true 的键在「全选」时打开、"全不选"时关闭）。 */
    private static final Object[][] OPT_BOOL_KEYS = {
            {"texrgb", true}, {"texa", true}, {"texrgba", true}, {"zup", false}, {"center", false},
            {"decimate", false}, {"borderfaces", true}, {"leaves", false}, {"septypes", true},
            {"p3d", true},
            {"split", true}, {"groups", true}, {"indiv", false}, {"custommtl", true}, {"fam", true},
            {"fatten", false}, {"doubled", false}, {"mergeflat", false}, {"hollow", false},
            {"superhollow", false}, {"sealentrances", false}, {"sealtunnels", false},
            {"fillbubbles", false}, {"meltsnow", false}, {"connectparts", false},
            {"connectcorners", false}, {"connectalledges", false}, {"deletefloaters", false},
            {"compositeoverlay", false}, {"dbggroups", false}, {"dbgwelds", false},
            {"showparts", false}, {"showwelds", false}, {"exportall", true}, {"mdl", false},
            {"biome", false}, {"cremodel", true}, {"zip", true}, {"modconvert", false},
    };

    /**
     * 选项预设：0=全不选（开关全关、单选全不选）、1=全选（开关全开、单选取默认）、2=恢复默认。
     * 数字行不参与"全不选/全选"（它们是数值不是选择项），"恢复默认"会一并复位。
     */
    private void applyOptionPreset(android.content.SharedPreferences sp, int mode) {
        android.content.SharedPreferences.Editor ed = sp.edit();
        if (mode == 2) {
            ed.clear();
        } else {
            boolean on = (mode == 1);
            for (Object[] pair : OPT_BOOL_KEYS) {
                ed.putBoolean((String) pair[0], on);
            }
            for (int i = 0; i < OPT_RADIO_KEYS.length; i++) {
                ed.putInt(OPT_RADIO_KEYS[i], on ? OPT_RADIO_DEFAULTS[i] : OPT_NONE);
            }
        }
        ed.apply();
    }

    /** 预设改完后重开面板，让界面立刻反映新状态。 */
    private void reopenOptionsPanel(final androidx.appcompat.app.AlertDialog[] box) {
        try {
            if (box[0] != null) box[0].dismiss();
        } catch (Throwable ignored) {
        }
        showExportOptionsDialog();
    }

    /** 数字选项行：收起时统一在「保存」里写回 SharedPreferences。 */
    private final java.util.List<EditText> optNumberFields = new java.util.ArrayList<>();
    private final java.util.List<String> optNumberKeys = new java.util.ArrayList<>();

    private android.widget.LinearLayout optNumberRow(final String key, String label, float def) {
        final android.content.SharedPreferences sp = exportOpts();
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        TextView lb = new TextView(this);
        lb.setText(label);
        row.addView(lb);
        EditText et = new EditText(this);
        et.setText(optNumberText(sp.getFloat(key, def)));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(et);
        optNumberFields.add(et);
        optNumberKeys.add(key);
        return row;
    }

    private static String optNumberText(float f) {
        if (f == Math.round(f)) return String.valueOf((long) f);
        return String.valueOf(f);
    }

    /**
     * 数字选项的安全区间（与原生 jni_export.cpp 的 optFloatRange/optIntRange 保持一致）。
     * <p>
     * 原因：核心把「模型高度 / 每个区块毫米」换算成统一缩放系数（ObjFileManip.cpp:17712/17737），
     * 0 会导致后续除法产生 NaN；掏空壁厚有 assert(&gt;0) 且参与除法（:17913/:17914）。
     * 界面允许手输，所以在这里把越界值夹到安全区间；区间内的正常值（含各默认值）一律不变。
     */
    private static float clampOptionValue(String key, float v) {
        if (Float.isNaN(v)) return 1f;
        if ("modelheight".equals(key))  return Math.max(0.5f, Math.min(1000f, v));      // 厘米
        if ("blocksize".equals(key))    return Math.max(0.1f, Math.min(10000f, v));     // 毫米
        if ("cost".equals(key))         return Math.max(1f, Math.min(1000000f, v));
        if ("hollowthick".equals(key))  return Math.max(0.1f, Math.min(1000f, v));      // 毫米
        if ("floatercount".equals(key)) return Math.max(0f, Math.min(1000000f, v));     // 个
        return v;
    }

    /** 导出选项面板：改动即写入 SharedPreferences，下次导出生效。 */
    private void showExportOptionsDialog() {
        final android.content.SharedPreferences sp = exportOpts();
        ScrollView sv = new ScrollView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        // 底部多留一段：面板很长，末尾选项不会被系统手势条/屏幕边缘遮住
        root.setPadding(pad, pad, pad, (int) (32 * getResources().getDisplayMetrics().density));
        sv.addView(root);

        // 顶部快捷区：随时「全不选 / 全选 / 恢复默认」。配合每组里的「不选（用默认）」
        // 以及"再点一次已选中项即取消"，多选 / 单选 / 不选三种状态都能随时切。
        final androidx.appcompat.app.AlertDialog[] box = new androidx.appcompat.app.AlertDialog[1];
        android.widget.LinearLayout quick = new android.widget.LinearLayout(this);
        quick.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        String[] quickLabels = {"全不选", "全选", "恢复默认"};
        float dp = getResources().getDisplayMetrics().density;
        for (int qi = 0; qi < quickLabels.length; qi++) {
            final int mode = qi;
            com.google.android.material.button.MaterialButton qb =
                    new com.google.android.material.button.MaterialButton(this);
            qb.setText(quickLabels[qi]);
            qb.setTextSize(12);
            qb.setMinHeight(0);
            android.widget.LinearLayout.LayoutParams qlp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (qi < quickLabels.length - 1) qlp.setMarginEnd((int) (6 * dp));
            qb.setLayoutParams(qlp);
            qb.setOnClickListener(v -> {
                applyOptionPreset(sp, mode);
                reopenOptionsPanel(box);
            });
            quick.addView(qb);
        }
        root.addView(quick);

        root.addView(optTitle("材质与纹理"));
        final String[] mats = {"不选（用默认：整幅大图 3 张马赛克）", "不导出材质", "实体材质颜色",
                "带颜色噪点纹理（大图）", "整幅大图（3 张马赛克）", "单独纹理（每个方块一张 PNG）"};
        root.addView(optRadioGroup("mat", new int[]{OPT_NONE, 0, 1, 2, 3, 4}, mats, 4, false, sp));

        // 适配 Prisma3D（P3D）：只改导出材质参数、不动几何。参照 Prisma3D 2.0.8 自带的 OBJ 材质模板
        // （illum 4 / Ka 0 0 0 / Kd 1 1 1 / 无 map_Ka / 无自发光 Ke），专治"模型导入 P3D 后过曝"。
        root.addView(optSwitch("p3d",
                "适配 Prisma3D（P3D 2.0 与 3.x）：Ka 0 / 无环境贴图 / 无自发光 / illum 4（防过曝）", true));
        root.addView(optSwitch("texrgb", "纹理输出 RGB", true));
        root.addView(optSwitch("texa", "纹理输出 Alpha", true));
        root.addView(optSwitch("texrgba", "纹理输出 RGBA", true));

        android.widget.LinearLayout rowDir = new android.widget.LinearLayout(this);
        rowDir.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        TextView lbDir = new TextView(this);
        lbDir.setText("单独纹理目录名：");
        rowDir.addView(lbDir);
        final EditText etTileDir = new EditText(this);
        etTileDir.setText(sp.getString("tiledir", "tex"));
        etTileDir.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowDir.addView(etTileDir);
        root.addView(rowDir);

        root.addView(optTitle("朝向（3D 打印 / Blender 常用）"));
        // 注意：这里原来横排（horizontal=true）。横排项数多时总宽会超出屏幕，
        // 右侧按钮被裁掉、点不到（用户反馈"有几个选项根本选不了，溢出到屏幕外"），
        // 除极短标签外一律竖排。
        root.addView(optRadioGroup("rotate", new int[]{OPT_NONE, 0, 90, 180, 270},
                new String[]{"不选（用默认 0°）", "0°", "90°", "180°", "270°"}, 0, false, sp));
        root.addView(optSwitch("zup", "将 Z 设为向上方向（而不是 Y）", false));
        root.addView(optSwitch("center", "围绕原点居中模型", false));

        // 维度：桌面版从地图视图切维度，安卓端此前没有这个开关 → 建筑在下界/末地时
        // 导出的永远是主世界地形（"地图转 OBJ 货不对版"的头号原因）。
        root.addView(optTitle("维度（地图在哪一层）"));
        root.addView(optRadioGroup("dim", new int[]{OPT_NONE, 0, 1, 2},
                new String[]{"不选（用默认：主世界）", "主世界（Overworld）",
                        "下界（Nether）", "末地（The End）"}, 0, false, sp));

        root.addView(optTitle("网格与分块"));
        root.addView(optSwitch("decimate", "简化网格（合并共面，面数大幅减少）", false));
        root.addView(optSwitch("borderfaces", "在边界处创建块面", true));
        root.addView(optSwitch("leaves", "实心树叶（少一些多边形）", false));
        root.addView(optSwitch("septypes", "导出不同的类型（按方块类型分开）", true));
        root.addView(optSwitch("split", "按方块类型分割", true));
        root.addView(optSwitch("groups", "创建分组对象", true));
        root.addView(optSwitch("indiv", "导出单个块", false));
        root.addView(optSwitch("custommtl", "自定义材质", true));
        root.addView(optSwitch("fam", "每个系列的材料", true));
        root.addView(optSwitch("fatten", "肥大块（相邻同类合并成大块，面数更少）", false));
        root.addView(optSwitch("doubled", "花草/告示牌翻倍（正反各画一面，Unity 等单面渲染器用）", false));
        root.addView(optSwitch("mergeflat", "合并平面块（草径等平顶块压到下方；桌面默认开，这里默认关以保持既有结果）", false));

        root.addView(optTitle("3D 打印：尺寸"));
        optNumberFields.clear();
        optNumberKeys.clear();
        root.addView(optRadioGroup("scale", new int[]{OPT_NONE, 0, 1, 2, 3},
                new String[]{"不选（用默认：每个区块毫米）", "按高度（厘米）",
                        "根据材料壁厚最小化尺寸", "每个区块（毫米）", "目标成本"},
                2, false, sp));
        root.addView(optNumberRow("modelheight", "模型高度（厘米）：", 100f));
        root.addView(optNumberRow("blocksize", "每个区块（毫米）：", 2f));   // 桌面对话框默认 2 毫米
        root.addView(optNumberRow("cost", "目标成本：", 25f));

        root.addView(optTitle("3D 打印：单位与材料"));
        root.addView(optRadioGroup("units", new int[]{OPT_NONE, 0, 1, 2, 3},
                new String[]{"不选（用默认：米）", "米", "厘米", "毫米", "英寸"}, 0, false, sp));
        root.addView(optRadioGroup("physmat", new int[]{OPT_NONE, 0, 1, 13},
                new String[]{"不选（用默认：彩色砂岩）", "白色强韧材料", "彩色砂岩（默认）", "自定义材料"},
                1, false, sp));

        root.addView(optTitle("3D 打印：掏空与结构"));
        root.addView(optSwitch("hollow", "掏空模型底部（省料）", false));
        root.addView(optSwitch("superhollow", "超中空（更激进地掏空）", false));
        root.addView(optNumberRow("hollowthick", "掏空壁厚（毫米）：", 2f));   // 桌面对话框默认 2 毫米（原 1000 是 1 米，明显不合理）
        root.addView(optSwitch("sealentrances", "封闭入口", false));
        root.addView(optSwitch("sealtunnels", "填充模型基础中孤立的隧道", false));
        root.addView(optSwitch("fillbubbles", "填充气泡", false));
        root.addView(optSwitch("meltsnow", "融化雪块（与掏空/密封配合使结构坚固）", false));
        root.addView(optSwitch("connectparts", "连接共享一条边的零件", false));
        root.addView(optSwitch("connectcorners", "连接转角提示", false));
        root.addView(optSwitch("connectalledges", "焊接所有共享边（最激进）", false));
        root.addView(optSwitch("deletefloaters", "删除浮动对象（树和过小的块）", false));
        root.addView(optNumberRow("floatercount", "浮动对象最小方块数：", 16f));
        root.addView(optSwitch("compositeoverlay", "创建复合叠加面", false));

        root.addView(optTitle("调试"));
        root.addView(optSwitch("dbggroups", "用不同颜色显示浮动部件", false));
        root.addView(optSwitch("dbgwelds", "用明亮的颜色显示焊接块", false));
        root.addView(optSwitch("showparts", "用颜色标出零件（核心调试显示）", false));
        root.addView(optSwitch("showwelds", "用颜色标出焊接块（核心调试显示）", false));

        root.addView(optTitle("导出范围与附加文件"));
        root.addView(optSwitch("exportall", "导出较少、详细的块（小装饰方块；桌面这个对话框里是关的）", true));
        root.addView(optSwitch("mdl", "导出 MDL（额外生成 .mdl 文件）", false));
        root.addView(optSwitch("biome", "在出口区域的中心使用生物群系", false));
        root.addView(optSwitch("cremodel", "只创建文件本身（导出后保留 .obj/.mtl；关掉会在导出结束时删除它们）", true));

        root.addView(optTitle("输出"));
        root.addView(optSwitch("zip", "导出后打包 ZIP 到「下载/MinewaysMobile」", true));

        root.addView(optTitle("模组转换（实验性，默认关）"));
        root.addView(optSwitch("modconvert", "开启模组转换：选区内的模组方块按上传的模组文件生成几何与贴图（仅支持绝对坐标 OBJ）", false));
        {
            int n = modUriCount();
            com.google.android.material.button.MaterialButton btnMod =
                    new com.google.android.material.button.MaterialButton(this);
            btnMod.setText("选择模组文件（jar/资源包，已选 " + n + " 个）");
            btnMod.setTextSize(12);
            btnMod.setOnClickListener(v -> {
                try {
                    pickModLauncher.launch(new String[]{"*/*"});
                } catch (Throwable t) {
                    Toast.makeText(this, "打不开文件选择器：" + t, Toast.LENGTH_SHORT).show();
                }
            });
            root.addView(btnMod);
            TextView modHint = new TextView(this);
            modHint.setTextSize(12);
            modHint.setTextColor(0xFF9AA0A6);
            modHint.setText("支持 Forge/Fabric 模组 jar 与资源包；先导出一次看报告里的「模组方块清单」，再上传包含这些方块的模组。multipart 与连接性方块会降级为占位。");
            root.addView(modHint);
        }

        box[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("导出选项（改动即保存，下次导出生效）")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    String t = etTileDir.getText().toString().trim();
                    sp.edit().putString("tiledir", t.isEmpty() ? "tex" : t).apply();
                    for (int i = 0; i < optNumberFields.size(); i++) {
                        try {
                            float v = Float.parseFloat(optNumberFields.get(i).getText().toString().trim());
                            String key = optNumberKeys.get(i);
                            sp.edit().putFloat(key, clampOptionValue(key, v)).apply();
                        } catch (Throwable ignored) {
                        }
                    }
                    Toast.makeText(this, "已保存，下次导出生效", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    applyOptionPreset(sp, 2);
                    Toast.makeText(this, "已恢复默认，下次导出生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用版本号（失败返回 ?）。 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int parseInt(int resId, int def) {
        EditText et = findViewById(resId);
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private void fail(String msg) {
        tvResult.setText("出错了：" + msg);
        setWorkDone();
    }

    // 导出/转换报错时，自动把错误信息复制到系统剪贴板，便于用户直接粘贴反馈。
    // 该方法可能被后台线程调用，Toast 必须切回主线程，否则会抛
    // "Can't toast on a thread that has not called Looper.prepare()"。
    private void copyToClipboard(String msg) {
        copyToClipboard(msg, "导出报错，错误信息已复制到剪贴板");
    }

    private void copyToClipboard(final String msg, final String toastMsg) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("MinewaysExportError", msg));
            }
        } catch (Throwable ignored) {
            // 剪贴板不可用时静默失败，不影响导出流程
        }
        // Toast 只在主线程显示，避免后台线程崩溃
        runOnUiThread(() -> {
            try {
                Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    // ---- 递归复制 DocumentFile 树 ----
    private int copyTree(DocumentFile dir, File toDir) throws IOException {
        int count = 0;
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (DocumentFile k : kids) {
            String name = safeName(k.getName());
            if (TextUtils.isEmpty(name)) continue;
            File target = new File(toDir, name);
            if (k.isDirectory()) {
                if (!target.mkdirs()) continue;
                count += copyTree(k, target);
            } else if (k.isFile()) {
                if (copyFile(k.getUri(), target)) count++;
            }
        }
        return count;
    }

    private boolean copyFile(Uri uri, File target) {
        try (InputStream in = new BufferedInputStream(
                getContentResolver().openInputStream(uri));
             OutputStream out = new BufferedOutputStream(
                     new FileOutputStream(target))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String safeName(String s) {
        if (s == null) return "";
        s = s.replace("/", "_").replace("\\", "_").replace(":", "_");
        return s;
    }

    private File findLevelDat(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = findLevelDat(k);
                if (r != null) return r;
            } else if (k.getName().equals("level.dat")) {
                return k;
            }
        }
        return null;
    }

    private void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    // ================= Shizuku 提权导入 =================

    private void registerShizukuPermissionListener() {
        try {
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                runOnUiThread(() -> {
                    if (grantResult == 0) {
                        updateShizukuStatus();
                        Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                        // 授权成功后直接列出基岩版世界
                        listBedrockWorldsAndPrompt();
                    } else {
                        tvShizukuStatus.setText("Shizuku 授权被拒绝。");
                    }
                });
            });
        } catch (Throwable t) {
            tvShizukuStatus.setText("Shizuku 不可用：" + t.getMessage());
        }
    }

    private void updateShizukuStatus() {
        boolean ready = false;
        try { ready = ShizukuWorldImporter.isReady(); }
        catch (Throwable ignored) {}
        if (tvShizukuStatus == null) return;
        if (ready) tvShizukuStatus.setText("Shizuku 已就绪，可导入基岩版存档。");
        else tvShizukuStatus.setText("Shizuku 未授权。点击按钮申请授权后，可读取 Minecraft 私有存档目录。");
    }

    private void onShizukuImportClick() {
        boolean ready;
        try { ready = ShizukuWorldImporter.isReady(); }
        catch (Throwable t) { ready = false; }
        if (ready) {
            listBedrockWorldsAndPrompt();
            return;
        }
        tvShizukuStatus.setText("正在申请 Shizuku 授权…");
        ShizukuWorldImporter.requestPermission(new ShizukuWorldImporter.Callback() {
            @Override
            public void onResult(String message) {
                runOnUiThread(() -> updateShizukuStatus());
            }
        });
    }

    private void listBedrockWorldsAndPrompt() {
        tvShizukuStatus.setText("正在列出基岩版存档…");
        new Thread(() -> {
            final List<String> worlds = ShizukuWorldImporter.listBedrockWorlds();
            runOnUiThread(() -> {
                if (worlds == null || worlds.isEmpty()) {
                    tvShizukuStatus.setText("未找到基岩版存档目录（检查 /Android/data/com.mojang.minecraftpe/）。");
                    return;
                }
                String[] items = worlds.toArray(new String[0]);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("选择要导入的基岩版存档")
                        .setItems(items, (d, w) -> importBedrockWorld(items[w]))
                        .setNegativeButton("取消", null)
                        .show();
            });
        }).start();
    }

    private void importBedrockWorld(final String srcDir) {
        tvShizukuStatus.setText("正在复制存档到应用私有目录…");
        new Thread(() -> {
            ShizukuWorldImporter.importWorld(this, srcDir, new ShizukuWorldImporter.Callback() {
                @Override
                public void onResult(final String message) {
                    runOnUiThread(() -> {
                        if (message.startsWith("OK:")) {
                            String path = message.substring(3);
                            currentWorldDir = new File(path);
                            etWorldDir.setText(path);
                            tvShizukuStatus.setText("已导入：" + path);
                            tvResult.setText("已通过 Shizuku 导入存档，可开始导出。");
                            refreshWorldInfo(path);
                            showPage(pageExport);
                        } else {
                            tvShizukuStatus.setText("导入失败：" + message);
                        }
                    });
                }
            });
        }).start();
    }

    // ================= 网易版存档：扫描 → 解密 → 导出到下载目录 =================
    // 「世界信息」页的重定位实现：该页的职责就是"通过权限找到网易版存档 → 解密 → 输出到下载目录"。
    // 扫描分三层：① 直读（能读到就扫）② Shizuku 提权（Android 11+ 对 /Android/data 的强制隔离）
    // ③ 手动指定文件夹（SAF）。解密只作用于副本（Android/media 临时区），绝不改动玩家原存档。

    /** 当前选中的待导出存档（null 表示未选）。 */
    private NeteaseWorldScanner.World selectedWorld;

    /** 手动指定存档文件夹（SAF），可直接读到用户可见位置。 */
    private final ActivityResultLauncher<Uri> pickNeteaseTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                exportNeteaseFromTree(uri);
            });

    private void setUpNeteasePage() {
        try {
            findViewById(R.id.btn_world_scan).setOnClickListener(v -> startNeteaseScan(false));
            findViewById(R.id.btn_world_scan_shizuku).setOnClickListener(v -> startNeteaseScan(true));
            findViewById(R.id.btn_world_pick).setOnClickListener(v -> {
                try {
                    pickNeteaseTreeLauncher.launch(null);
                } catch (Throwable t) {
                    tvShizukuStatus.setText("无法打开文件选择器：" + t.getMessage());
                }
            });
            findViewById(R.id.btn_world_export).setOnClickListener(v -> exportSelectedNeteaseWorld());
            findViewById(R.id.btn_world_open_download).setOnClickListener(v -> openNeteaseDownloads());
        } catch (Throwable ignored) {
        }
        updateNeteaseButtons();
    }

    /** 扫描网易版存档；useShizuku 为真时先确保 Shizuku 已授权再扫。 */
    private void startNeteaseScan(final boolean useShizuku) {
        // Android 8~12：直读公共目录需要读权限；缺权限时先申请（拒绝也不阻断，
        // 扫描会自动跳过读不到的目录，用户仍可走 Shizuku 或手动指定）。
        try {
            if (Build.VERSION.SDK_INT <= 32
                    && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 2801);
            }
        } catch (Throwable ignored) {
        }
        if (useShizuku) {
            boolean ready;
            try {
                ready = ShizukuWorldImporter.isReady();
            } catch (Throwable t) {
                ready = false;
            }
            if (!ready) {
                tvShizukuStatus.setText(R.string.shizuku_requesting);
                ShizukuWorldImporter.requestPermission(message -> runOnUiThread(() -> {
                    updateShizukuStatus();
                    startNeteaseScan(true);
                }));
                return;
            }
        }
        tvShizukuStatus.setText(R.string.world_scanning);
        new Thread(() -> {
            final List<NeteaseWorldScanner.World> worlds = NeteaseWorldScanner.scan(useShizuku,
                    msg -> runOnUiThread(() -> tvShizukuStatus.setText(msg)));
            runOnUiThread(() -> onNeteaseScanDone(worlds, useShizuku));
        }, "netease-scan").start();
    }

    private void onNeteaseScanDone(List<NeteaseWorldScanner.World> worlds, boolean usedShizuku) {
        if (worlds.isEmpty()) {
            selectedWorld = null;
            tvShizukuStatus.setText(usedShizuku ? R.string.world_none_shizuku : R.string.world_none);
            updateNeteaseButtons();
            return;
        }
        String[] items = new String[worlds.size()];
        for (int i = 0; i < worlds.size(); i++) {
            NeteaseWorldScanner.World w = worlds.get(i);
            items[i] = w.name + "\n" + w.sizeText()
                    + (w.encrypted ? " · 网易加密" : " · 未加密")
                    + (w.viaShizuku ? " · Shizuku" : "")
                    + "\n" + w.path;
        }
        tvShizukuStatus.setText("找到 " + worlds.size() + " 个存档，请选择要导出的那个。");
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.world_pick_title)
                .setItems(items, (d, which) -> {
                    selectedWorld = worlds.get(which);
                    NeteaseWorldScanner.World w = selectedWorld;
                    tvShizukuStatus.setText(getString(R.string.world_selected, w.name, w.sizeText(),
                            w.encrypted ? getString(R.string.world_enc_yes)
                                    : getString(R.string.world_enc_no)));
                    updateNeteaseButtons();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateNeteaseButtons() {
        try {
            MaterialButton b = findViewById(R.id.btn_world_export);
            if (b != null) b.setEnabled(selectedWorld != null);
        } catch (Throwable ignored) {
        }
    }

    private void exportSelectedNeteaseWorld() {
        final NeteaseWorldScanner.World w = selectedWorld;
        if (w == null) {
            Toast.makeText(this, "请先扫描并选择一个存档。", Toast.LENGTH_SHORT).show();
            return;
        }
        tvShizukuStatus.setText(R.string.world_exporting);
        updateNeteaseButtons();
        new Thread(() -> {
            NeteaseSaveExporter.Result r = NeteaseSaveExporter.export(this, w,
                    (msg, ratio) -> runOnUiThread(() -> tvShizukuStatus.setText(msg)));
            runOnUiThread(() -> onNeteaseExportDone(r));
        }, "netease-export").start();
    }

    private void exportNeteaseFromTree(final Uri uri) {
        tvShizukuStatus.setText(R.string.world_exporting);
        new Thread(() -> {
            NeteaseSaveExporter.Result r = NeteaseSaveExporter.exportFromTree(this, uri,
                    (msg, ratio) -> runOnUiThread(() -> tvShizukuStatus.setText(msg)));
            runOnUiThread(() -> onNeteaseExportDone(r));
        }, "netease-export-tree").start();
    }

    private void onNeteaseExportDone(NeteaseSaveExporter.Result r) {
        updateNeteaseButtons();
        if (r == null) {
            tvShizukuStatus.setText(getString(R.string.world_export_failed, "未知错误"));
            return;
        }
        if (!r.success) {
            tvShizukuStatus.setText(getString(R.string.world_export_failed, r.message));
            return;
        }
        tvShizukuStatus.setText(r.message + "\n"
                + getString(R.string.world_export_dest, r.destDir == null ? "" : r.destDir));
        // 导出结果直接挂成"当前世界"：回到「导出」页即可马上转 OBJ
        try {
            if (r.destDir != null && r.destDir.startsWith("/")) {
                File dir = new File(r.destDir);
                if (dir.isDirectory()) {
                    currentWorldDir = dir;
                    etWorldDir.setText(dir.getAbsolutePath());
                    refreshWorldInfo(dir.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已导出到下载目录", Toast.LENGTH_LONG).show();
    }

    /** 打开下载目录：Android 10+ 打开系统「下载」位置，旧系统直接打开该子目录。 */
    private void openNeteaseDownloads() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI);
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "MinewaysMobile/网易存档");
        openFolder(dir);
    }
}