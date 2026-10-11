package com.mineways.blender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;

import com.mineways.MediaPublisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Blender 离线渲染页。
 *
 * <p>流程：Shizuku 授权 → 装环境（glibc sysroot + 官方 Blender，运行时下载，不打进 APK）
 * → 选工程（.blend 或整个工程目录）→ 设参数 → 后台渲染 → 预览 / 存相册。</p>
 */
public class BlenderActivity extends ComponentActivity {

    private static final int BG = 0xFF15171A;
    private static final int CARD = 0xFF1E2126;
    private static final int INPUT = 0xFF262A31;
    private static final int TEXT = 0xFFECECEC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFFD9603A;
    private static final int OK = 0xFF8FE3B0;

    private static final String[] ENGINES = {
            "Cycles（CPU 光追 · 最稳）",
            "EEVEE（需软件 OpenGL · 快）",
            "Workbench（着色预览 · 最快）",
    };
    private static final String[] ENGINE_IDS = {"CYCLES", "BLENDER_EEVEE_NEXT", "BLENDER_WORKBENCH"};
    private static final String[] RES_PRESETS = {
            "960 × 540（预览）",
            "1280 × 720（720p）",
            "1920 × 1080（1080p）",
            "2560 × 1440（2K）",
            "3840 × 2160（4K）",
            "工程原始分辨率",
    };
    private static final int[][] RES_SIZES = {
            {960, 540}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {0, 0},
    };

    /** 输出类型：静帧 / 序列 / 视频（官方构建自带 FFmpeg，能直出 mp4）。 */
    private static final String[] FORMATS = {
            "静帧 PNG",
            "静帧 JPEG",
            "静帧 WebP",
            "静帧 TIFF",
            "静帧 BMP",
            "静帧 OpenEXR",
            "PNG 序列（可断点续渲）",
            "视频 MP4 · H.264（推荐）",
            "视频 MP4 · H.265（体积更小）",
            "视频 WebM · VP9",
            "视频 MKV · H.264",
    };
    /** 与 {@link #FORMATS} 一一对应的 (格式, 容器, 编码)；容器/编码为 null 表示静帧。 */
    private static final String[][] FORMAT_IDS = {
            {BlenderScript.Fmt.PNG, null, null},
            {BlenderScript.Fmt.JPEG, null, null},
            {BlenderScript.Fmt.WEBP, null, null},
            {BlenderScript.Fmt.TIFF, null, null},
            {BlenderScript.Fmt.BMP, null, null},
            {BlenderScript.Fmt.EXR, null, null},
            {BlenderScript.Fmt.PNG, null, null},
            {BlenderScript.Fmt.FFMPEG, "MPEG4", "H264"},
            {BlenderScript.Fmt.FFMPEG, "MPEG4", "H265"},
            {BlenderScript.Fmt.FFMPEG, "WEBM", "VP9"},
            {BlenderScript.Fmt.FFMPEG, "MKV", "H264"},
    };
    private static final String[] QUALITIES = {
            "高画质（HIGH）", "中（MEDIUM）", "低（LOW）", "很低（VERYLOW）", "最低（LOWEST）",
    };
    private static final String[] QUALITY_IDS = {"HIGH", "MEDIUM", "LOW", "VERYLOW", "LOWEST"};

    /** 硬件加速开关。 */
    private static final String[] ACCEL_MODES = {
            "自动（探测到硬件就用，否则软件）",
            "强制软件（llvmpipe，最稳）",
            "强制硬件（Turnip + Zink）",
            "自定义驱动 env",
    };
    private static final String[] ACCEL_IDS = {"auto", "soft", "hw", "custom"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> logLines = new ArrayList<>();

    private TextView tvShizuku;
    private TextView tvEnv;
    private TextView tvProject;
    private TextView tvStatus;
    private TextView tvLog;
    private TextView tvOutputs;
    private TextView tvEngineHint;
    private ProgressBar bar;
    private Spinner spEngine;
    private Spinner spRes;
    private Spinner spFormat;
    private Spinner spQuality;
    private EditText etSamples;
    private EditText etW;
    private EditText etH;
    private EditText etFs;
    private EditText etFe;
    private EditText etFps;
    private EditText etQuality;
    private CheckBox cbAnim;
    private CheckBox cbTransparent;
    private CheckBox cbAudio;
    private Button btnInstall;
    private Button btnRender;
    private Button btnCancel;
    private LinearLayout outBox;
    private LinearLayout animBox;
    private LinearLayout videoBox;
    private LinearLayout tplBox;
    private TextView tvFormatHint;
    private TextView tvExtras;
    private Spinner spAccel;
    private TextView tvAccel;
    private LinearLayout secEnv;
    private LinearLayout secProj;
    private LinearLayout secRender;
    private LinearLayout secOut;
    private Button[] tabButtons;
    private int currentTab;

    private File project;
    private BlenderRenderer.Task task;
    private volatile boolean busy;
    private volatile boolean cancelRequested;
    private long lastLogFlush;

    private ActivityResultLauncher<Intent> pickFile;
    private ActivityResultLauncher<Intent> pickTree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pickFile = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && result.getData().getData() != null) {
                        importProject(result.getData().getData());
                    }
                });
        pickTree = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && result.getData().getData() != null) {
                        importProjectDir(result.getData().getData());
                    }
                });

        RootShell.addPermissionListener((requestCode, grantResult) -> ui.post(() -> {
            refreshStatus();
            if (grantResult == 0) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
            } else {
                tvShizuku.setText("Shizuku 授权被拒绝。");
            }
        }));

        // 界面构建失败也不许整页闪退：退化成「只显示错误 + 日志」的页面，让人还能把问题发出来
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            setContentView(errorUi(t));
            return;
        }
        try {
            wireUi();
        } catch (Throwable t) {
            appendLog("接线时出错（页面仍可用）：" + t);
            Toast.makeText(this, "页面初始化有异常，已记录到日志", Toast.LENGTH_LONG).show();
        }
    }

    /** 构建失败时的兜底页面。 */
    private View errorUi(Throwable t) {
        final LinearLayout box = col();
        box.setBackgroundColor(BG);
        box.setPadding(dp(16), dp(24), dp(16), dp(24));
        box.addView(text("Blender 页构建失败（已兜底，没有闪退）", 18f, ACCENT));
        final java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        final TextView tv = text(sw.toString(), 11.5f, TEXT);
        tv.setTextIsSelectable(true);
        box.addView(pad(tv, 0, 10, 0, 10));
        final TextView hint = text("把上面这段复制反馈即可。", 13f, DIM);
        box.addView(hint);
        final ScrollView wrap = new ScrollView(this);
        wrap.addView(box);
        return wrap;
    }

    /** 界面建好之后的接线（监听、默认值、首次刷新）。 */
    private void wireUi() {
        spEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                updateEngineHint(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        spRes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                final boolean custom = pos >= RES_SIZES.length - 1;
                etW.setEnabled(custom);
                etH.setEnabled(custom);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        spAccel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                final int i = Math.max(0, Math.min(pos, ACCEL_IDS.length - 1));
                BlenderEnv.setAccelMode(BlenderActivity.this, ACCEL_IDS[i]);
                updateAccelText();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        cbAnim.setOnCheckedChangeListener((b, on) -> {
            animBox.setVisibility(on ? View.VISIBLE : View.GONE);
            etFs.setEnabled(on);
            etFe.setEnabled(on);
            updateFormatHint(spFormat.getSelectedItemPosition());
        });
        spFormat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                updateFormatHint(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        animBox.setVisibility(View.GONE);
        etFs.setEnabled(false);
        etFe.setEnabled(false);
        etFs.setText("1");
        etFe.setText("24");
        etSamples.setText("64");
        etQuality.setText("90");
        etFps.setText("24");
        updateEngineHint(0);
        updateFormatHint(0);
        refreshTemplates();
        refreshStatus();
        refreshExtras();
        refreshOutputs();
    }

    /** 输出类型提示 + 视频参数区的显隐。 */
    private void updateFormatHint(int pos) {
        if (tvFormatHint == null) return;
        final int i = Math.max(0, Math.min(pos, FORMAT_IDS.length - 1));
        final String[] ids = FORMAT_IDS[i];
        final boolean video = BlenderScript.Fmt.FFMPEG.equals(ids[0]);
        videoBox.setVisibility(video ? View.VISIBLE : View.GONE);
        if (video) {
            tvFormatHint.setText("视频由 Blender 内置 FFmpeg 直出（软编，慢但省事）。"
                    + "想更快：输出「PNG 序列」，渲完用下面「序列 → MP4（硬编）」走系统编码器。");
        } else if (i == 6) {
            tvFormatHint.setText("序列输出可边渲边看，出错只补缺的帧；也是硬编视频的输入。");
        } else if (ids[0].equals(BlenderScript.Fmt.EXR)) {
            tvFormatHint.setText("OpenEXR：线性_hdr，适合继续合成；手机上直接看会发灰。");
        } else {
            tvFormatHint.setText("静帧单图输出。");
        }
    }

    private void updateEngineHint(int pos) {
        if (tvEngineHint == null) return;
        final int i = Math.max(0, Math.min(pos, ENGINE_IDS.length - 1));
        final String id = ENGINE_IDS[i];
        if (id.contains("EEVEE")) {
            tvEngineHint.setText(BlenderEnv.softGlOk(this)
                    ? "EEVEE 走 llvmpipe 软件渲染：比 Cycles 快很多，但画质与 AO/光追较弱，长时间大图仍建议 Cycles。"
                    : "EEVEE 需要 headless OpenGL；当前环境自检没通过，可能起不来（会退回提示用 Cycles）。");
        } else if (id.contains("WORKBENCH")) {
            tvEngineHint.setText("Workbench 只出着色预览（不算是最终成图），用来快速看构图和摆位。");
        } else {
            tvEngineHint.setText("Cycles CPU：质量最高、最稳，手机上按分钟到小时计；大图建议配合降噪。");
        }
    }

    // ================================================================ 界面

    private View buildUi() {
        // 外层：顶部步骤条（固定） + 中间滚动区 + 底部操作栏（固定）
        final LinearLayout outer = col();
        outer.setBackgroundColor(BG);

        final LinearLayout tabRow = row();
        tabRow.setPadding(dp(10), dp(10), dp(10), dp(6));
        final String[] tabNames = {"1 环境", "2 工程", "3 参数", "4 产物"};
        tabButtons = new Button[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            final Button b = new Button(this);
            b.setText(tabNames[i]);
            b.setTextSize(15f);
            b.setAllCaps(false);
            b.setMinHeight(dp(46));
            b.setOnClickListener(v -> showTab(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            b.setLayoutParams(lp);
            tabButtons[i] = b;
            tabRow.addView(b);
        }
        outer.addView(tabRow);

        final ScrollView scroll = new ScrollView(this);
        final LinearLayout root = col();
        root.setPadding(dp(14), dp(6), dp(14), dp(20));
        scroll.addView(root);
        final LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);
        outer.addView(scroll);

        secEnv = col();
        secProj = col();
        secRender = col();
        secOut = col();
        root.addView(secEnv);
        root.addView(secProj);
        root.addView(secRender);
        root.addView(secOut);

        secEnv.addView(pad(text("手机本地跑完整 Blender：官方 glibc 引擎 + 官方 Linux 版，"
                + "运行时下载安装（不进 APK）。需要 Shizuku 提权（原生程序不能放在应用私有目录，那里是 noexec）。",
                13f, DIM), 0, 4, 0, 12));

        // ── 1. Shizuku ──
        final LinearLayout shizukuCard = card();
        shizukuCard.addView(text("① Shizuku 提权", 15f, TEXT));
        tvShizuku = text("", 12.5f, DIM);
        shizukuCard.addView(pad(tvShizuku, 6, 0, 0, 8));
        final LinearLayout shizukuBtns = row();
        shizukuBtns.addView(btn("申请 / 检查授权", v -> {
            if (RootShell.isInstalled() && !RootShell.isReady()) {
                RootShell.requestPermission();
            }
            refreshStatus();
        }));
        shizukuBtns.addView(btn("打开 Shizuku", v -> {
            final String[] pkgs = {"moe.shizuku.privileged.api", "rikka.shizuku", "moe.shizuku.privileged.api.debug"};
            for (String pkg : pkgs) {
                try {
                    final Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setPackage(pkg);
                    startActivity(i);
                    return;
                } catch (Throwable ignored) {
                }
            }
            Toast.makeText(this, "没装 Shizuku：请先从应用商店安装并启动（root 或 adb 模式都行）",
                    Toast.LENGTH_LONG).show();
        }));
        shizukuCard.addView(shizukuBtns);
        secEnv.addView(shizukuCard);

        // ── 2. 环境 ──
        final LinearLayout envCard = card();
        envCard.addView(text("② 渲染环境", 15f, TEXT));
        tvEnv = text("", 12.5f, DIM);
        envCard.addView(pad(tvEnv, 6, 0, 0, 8));
        final LinearLayout envBtns = row();
        btnInstall = btn("安装 / 更新", v -> install());
        envBtns.addView(btnInstall);
        envBtns.addView(btn("卸载环境", v -> confirmUninstall()));
        envBtns.addView(btn("镜像设置", v -> editMirrors()));
        envCard.addView(envBtns);
        tvStatus = text("", 12.5f, ACCENT);
        envCard.addView(pad(tvStatus, 8, 0, 0, 0));
        secEnv.addView(envCard);

        // ── 3. 工程 ──
        final LinearLayout projCard = card();
        projCard.addView(text("③ 工程文件", 15f, TEXT));
        projCard.addView(pad(text("单文件只带 .blend 本身；如果工程用了外部贴图/字体，请用「选择工程文件夹」，"
                + "会连同相对路径的素材一起复制进来。", 12f, DIM), 0, 4, 0, 8));
        final LinearLayout projBtns = row();
        projBtns.addView(btn("选择 .blend", v -> pickBlend()));
        projBtns.addView(btn("选择工程文件夹", v -> pickFolder()));
        projBtns.addView(btn("工程工具", v -> openProjectTools()));
        projCard.addView(projBtns);
        tvProject = text("还没选工程。", 12.5f, TEXT);
        projCard.addView(pad(tvProject, 8, 0, 0, 0));
        secProj.addView(projCard);

        // ── 3.5 模板工程 ──
        final LinearLayout tplCard = card();
        tplCard.addView(text("③ 模板工程（原生 .blend）", 15f, TEXT));
        tplCard.addView(pad(text("没有工程也能开工：内置模板用 bpy 现搭并存成 .blend；"
                + "官方模板直接用发行包自带的场景（不复制，避免丢相对路径贴图）。",
                12f, DIM), 0, 4, 0, 8));
        final LinearLayout tplRow1 = row();
        for (final TemplateLib.Tpl t : TemplateLib.BUILTIN) {
            tplRow1.addView(btn(t.title, v -> makeTemplate(t)));
        }
        wrapRows(tplCard, tplRow1);
        tplBox = col();
        tplCard.addView(tplBox);
        secProj.addView(tplCard);

        // ── 4. 参数 ──
        final LinearLayout paramCard = card();
        paramCard.addView(text("④ 渲染参数", 15f, TEXT));

        paramCard.addView(label("引擎"));
        spEngine = spinner(ENGINES);
        paramCard.addView(spEngine);
        tvEngineHint = text("", 12f, DIM);
        paramCard.addView(pad(tvEngineHint, 4, 0, 0, 8));

        paramCard.addView(label("分辨率"));
        spRes = spinner(RES_PRESETS);
        paramCard.addView(spRes);
        final LinearLayout sizeRow = row();
        etW = input("宽", InputType.TYPE_CLASS_NUMBER);
        etH = input("高", InputType.TYPE_CLASS_NUMBER);
        sizeRow.addView(etW, weight());
        sizeRow.addView(etH, weight());
        paramCard.addView(pad(sizeRow, 6, 0, 0, 6));
        paramCard.addView(text("分辨率预设选「工程原始分辨率」时，用这里的宽 × 高。",
                11.5f, DIM));

        paramCard.addView(label("输出"));
        spFormat = spinner(FORMATS);
        paramCard.addView(spFormat);
        tvFormatHint = text("", 12f, DIM);
        paramCard.addView(pad(tvFormatHint, 4, 0, 0, 8));

        final LinearLayout videoRow = row();
        etFps = input("帧率", InputType.TYPE_CLASS_NUMBER);
        spQuality = spinner(QUALITIES);
        videoRow.addView(etFps, weight());
        videoRow.addView(spQuality);
        videoBox = col();
        videoBox.addView(videoRow);
        cbAudio = check("视频带音频（用工程里的音轨）", true);
        videoBox.addView(cbAudio);
        paramCard.addView(videoBox);

        paramCard.addView(label("采样数"));
        etSamples = input("Cycles 建议 32~256；EEVEE 建议 16~64", InputType.TYPE_CLASS_NUMBER);
        paramCard.addView(etSamples);
        paramCard.addView(text("静帧格式的画质（JPEG / WebP）：", 11.5f, DIM));
        etQuality = input("90", InputType.TYPE_CLASS_NUMBER);
        paramCard.addView(etQuality);

        cbAnim = check("渲染动画（多帧）", false);
        paramCard.addView(pad(cbAnim, 10, 0, 0, 0));
        animBox = col();
        final LinearLayout frameRow = row();
        etFs = input("起始帧", InputType.TYPE_CLASS_NUMBER);
        etFe = input("结束帧", InputType.TYPE_CLASS_NUMBER);
        frameRow.addView(etFs, weight());
        frameRow.addView(etFe, weight());
        animBox.addView(frameRow);
        paramCard.addView(animBox);

        cbTransparent = check("透明背景（PNG 带 Alpha）", false);
        paramCard.addView(cbTransparent);

        final LinearLayout preRow = row();
        preRow.addView(btn("渲染前预检（推荐）", v -> runPreflight()));
        preRow.addView(btn("最小自检渲染", v -> miniSelfTest()));
        paramCard.addView(pad(preRow, 8, 0, 0, 0));
        // 开始渲染 / 取消 / 进度条在底部固定栏（见 buildUi 末尾）
        secRender.addView(paramCard);

        // ── 5. 产物 ──
        final LinearLayout outCard = card();
        outCard.addView(text("⑤ 渲染产物", 15f, TEXT));
        outBox = col();
        outCard.addView(pad(outBox, 8, 0, 0, 0));
        final LinearLayout outBtns = row();
        outBtns.addView(btn("刷新", v -> refreshOutputs()));
        outBtns.addView(btn("序列 → MP4（硬编）", v -> encodeSequence()));
        outCard.addView(pad(outBtns, 8, 0, 0, 0));
        tvOutputs = text("", 12f, DIM);
        outCard.addView(pad(tvOutputs, 6, 0, 0, 0));
        final LinearLayout outBtns2 = row();
        outBtns2.addView(btn("清空产物", v -> confirmClearOutputs()));
        outBtns2.addView(btn("工程工具（材质/UV/贴图）", v -> openProjectTools()));
        outCard.addView(pad(outBtns2, 6, 0, 0, 0));
        secOut.addView(outCard);

        // ── 4.5 硬件加速 ──
        final LinearLayout gpuCard = card();
        gpuCard.addView(text("④ 硬件加速（GPU）", 15f, TEXT));
        gpuCard.addView(pad(text("Android 上只有 Vulkan 一条硬件路：Zink 把桌面 GL 翻成 Vulkan，"
                + "Adreno 用 Mesa Turnip（/dev/kgsl-3d0）、Mali 用 Panfrost/PanVK（/dev/mali0）。"
                + "只对 EEVEE / Workbench 有意义 —— Cycles 从 3.0 起就没有 OpenCL，手机上只能 CPU。",
                12f, DIM), 0, 4, 0, 8));
        spAccel = spinner(ACCEL_MODES);
        spAccel.setSelection(Math.max(0, indexOfAccel(BlenderEnv.accelMode(this))));
        gpuCard.addView(spAccel);
        tvAccel = text("", 12f, DIM);
        gpuCard.addView(pad(tvAccel, 6, 0, 0, 6));
        final LinearLayout gpuRow = row();
        gpuRow.addView(btn("GPU 探测并试跑", v -> probeGpu()));
        gpuRow.addView(btn("装 Mesa Vulkan 驱动", v -> installExtra("gpu", BlenderEnv.PKGS_GPU,
                "Mesa Vulkan 驱动已装好，接着点「GPU 探测并试跑」")));
        gpuCard.addView(gpuRow);
        final LinearLayout gpuRow2 = row();
        gpuRow2.addView(btn("只看设备信息", v -> {
            final GpuProbe.Info info = GpuProbe.detect(this);
            tvAccel.setText(info.summary());
            appendLog("── GPU 探测 ──");
            appendLog(info.summary());
        }));
        gpuRow2.addView(btn("自定义驱动 env", v -> editCustomGpuEnv()));
        gpuCard.addView(gpuRow2);
        secRender.addView(gpuCard);

        // ── 5.5 可选组件 ──
        final LinearLayout exCard = card();
        exCard.addView(text("⑥ 可选组件与体检", 15f, TEXT));
        exCard.addView(text("Blender 本体自带 FFmpeg，视频可以直接渲；"
                + "Debian 的 ffmpeg 命令行则用于转码 / 抽帧 / 改封装这些后期活。", 12f, DIM));
        final LinearLayout exBtns = row();
        exBtns.addView(btn("体检并补依赖", v -> healDeps()));
        exBtns.addView(btn("装 ffmpeg 工具", v -> installExtra("ffmpeg", BlenderEnv.PKGS_VIDEO,
                "ffmpeg 工具已装好（" + BlenderEnv.root(this) + "/sysroot/usr/bin/ffmpeg）")));
        exBtns.addView(btn("装 BlenderProc", v -> installBlenderProc()));
        exCard.addView(exBtns);
        tvExtras = text("", 12f, DIM);
        exCard.addView(pad(tvExtras, 8, 0, 0, 0));
        final LinearLayout exBtns2 = row();
        exBtns2.addView(btn("查看上次崩溃", v -> showLastCrash()));
        exBtns2.addView(btn("复制运行日志", v -> {
            copyToClipboard(logText());
            Toast.makeText(this, "日志已复制（可粘贴反馈）", Toast.LENGTH_SHORT).show();
        }));
        exCard.addView(pad(exBtns2, 6, 0, 0, 0));
        secOut.addView(exCard);

        // ── 日志 ──
        final LinearLayout logCard = card();
        logCard.addView(text("运行日志", 15f, TEXT));
        tvLog = text("", 11.5f, DIM);
        tvLog.setTextIsSelectable(true);
        final ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(round(0xFF111316, 10));
        logScroll.addView(tvLog);
        logCard.addView(pad(logScroll, 8, 0, 0, 0));
        secOut.addView(logCard);

        // ── 底部固定操作栏：状态 + 进度 + 开始/取消（滚动时永远可点）──
        final LinearLayout bottom = col();
        bottom.setPadding(dp(14), dp(8), dp(14), dp(12));
        bottom.setBackgroundColor(0xFF101215);
        tvStatus = text("", 13f, ACCENT);
        bottom.addView(tvStatus);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bottom.addView(pad(bar, 0, 6, 0, 6));
        final LinearLayout bottomRow = row();
        btnRender = bigBtn("开始渲染", ACCENT, v -> startRender());
        bottomRow.addView(btnRender, bigWeight());
        btnCancel = bigBtn("取消", INPUT, v -> {
            cancelRequested = true;
            if (task != null) {
                task.cancel();
                tvStatus.setText("正在取消（当前帧会被中止，已渲好的帧保留）…");
            }
        });
        btnCancel.setEnabled(false);
        bottomRow.addView(btnCancel, bigWeight());
        bottom.addView(bottomRow);
        outer.addView(bottom);

        showTab(0);
        return outer;
    }

    /** 切换步骤页签。 */
    private void showTab(int index) {
        final LinearLayout[] secs = {secEnv, secProj, secRender, secOut};
        for (int i = 0; i < secs.length; i++) {
            if (secs[i] == null) continue;
            secs[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (tabButtons != null) {
            for (int i = 0; i < tabButtons.length; i++) {
                final Button b = tabButtons[i];
                if (b == null) continue;
                final boolean on = i == index;
                b.setTextColor(on ? 0xFFFFFFFF : TEXT);
                b.setBackground(round(on ? ACCENT : INPUT, 12));
            }
        }
        currentTab = index;
    }

    // ================================================================ 崩溃 / 日志 / 自检

    /** 看上次闪退的堆栈（MinewaysApp 会落盘）。 */
    private void showLastCrash() {
        final File f = com.mineways.MinewaysApp.crashFile(this);
        final String text = FileUtil.readText(f, 200_000);
        if (text.isEmpty()) {
            Toast.makeText(this, "没有崩溃记录（很好）", Toast.LENGTH_SHORT).show();
            return;
        }
        final TextView tv = text(text, 11f, TEXT);
        tv.setTextIsSelectable(true);
        final ScrollView wrap = new ScrollView(this);
        wrap.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("上次闪退")
                .setView(wrap)
                .setPositiveButton("复制", (d, w) -> {
                    copyToClipboard(text);
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("清空记录", (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private String logText() {
        final StringBuilder sb = new StringBuilder();
        synchronized (logLines) {
            for (String s : logLines) {
                sb.append(s).append('\n');
            }
        }
        sb.append('\n').append(Preflight.diagnostics(this));
        return sb.toString();
    }

    private void copyToClipboard(String text) {
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("MinewaysLog", text));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 最小渲染自检：256×256 / 1 采样 / 1 帧，用当前引擎跑通整条链路。
     * 这是分辨「环境坏了」和「工程/参数有问题」的最快手段。
     */
    private void miniSelfTest() {
        if (!RootShell.isReady()) {
            Toast.makeText(this, "先授权 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BlenderEnv.installed(this)) {
            Toast.makeText(this, "先装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        if (project == null || !project.isFile()) {
            Toast.makeText(this, "自检也要用一个工程：先选 .blend 或生成一个模板", Toast.LENGTH_SHORT).show();
            showTab(1);
            return;
        }
        setBusy(true);
        showTab(3);
        tvStatus.setText("自检中：256×256 / 1 采样…");
        appendLog("── 最小自检开始 ──");
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            for (String[] eng : new String[][]{{"CYCLES", "Cycles CPU"}, {"BLENDER_WORKBENCH", "Workbench"},
                    {"BLENDER_EEVEE_NEXT", "EEVEE"}}) {
                final BlenderScript.Params p = new BlenderScript.Params();
                p.engine = eng[0];
                p.samples = 1;
                p.resX = 256;
                p.resY = 256;
                p.format = BlenderScript.Fmt.PNG;
                p.animation = false;
                p.frame = 1;
                p.output = new File(BlenderEnv.outDir(this), "selftest_" + eng[0].toLowerCase() + ".png")
                        .getAbsolutePath();
                final BlenderRenderer.Frame f;
                try {
                    f = BlenderRenderer.renderOnce(this, p, project, this::appendLog);
                } catch (Throwable t) {
                    sb.append(eng[1]).append("：异常 ").append(t).append('\n');
                    continue;
                }
                final String why = RenderFallback.verify(new File(p.output), p.format);
                sb.append(eng[1]).append("：")
                        .append(f.ok && why.isEmpty() ? "OK" : ("失败 " + (why.isEmpty() ? f.crash : why)))
                        .append(f.crash.isEmpty() ? "" : ("（" + f.crash + "）"))
                        .append('\n');
            }
            final String text = sb.toString();
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText("自检结束：见下面结果");
                appendLog("── 自检结果 ──\n" + text);
                refreshOutputs();
                new AlertDialog.Builder(this)
                        .setTitle("最小自检结果")
                        .setMessage(text + "\n含义：Cycles 失败=环境/编译器问题；只有 Cycles 过=GL 那条路（EEVEE/Workbench）没通；"
                                + "全过=环境没问题，渲染失败要看工程本身。")
                        .setPositiveButton("好", null)
                        .show();
            });
        }, "mw-blender-selftest").start();
    }

    // ================================================================ 硬件加速

    private static int indexOfAccel(String id) {
        for (int i = 0; i < ACCEL_IDS.length; i++) {
            if (ACCEL_IDS[i].equals(id)) return i;
        }
        return 0;
    }

    /** 逐个候选组合试跑，找得到硬件就记住。 */
    private void probeGpu() {
        if (!RootShell.isReady()) {
            Toast.makeText(this, "先授权 Shizuku（要读 /dev/kgsl-3d0 这类节点）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BlenderEnv.installed(this)) {
            Toast.makeText(this, "先装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        tvAccel.setText("探测中：每个候选要跑一次 Blender，慢的话一两分钟…");
        new Thread(() -> {
            final GpuProbe.Info info = GpuProbe.detect(BlenderActivity.this);
            final List<GpuProbe.ProbeResult> results =
                    GpuProbe.tryHardware(BlenderActivity.this, 300, this::appendLog);
            ui.post(() -> {
                setBusy(false);
                final StringBuilder sb = new StringBuilder();
                sb.append(info.summary()).append('\n');
                sb.append("候选试跑结果：\n");
                for (GpuProbe.ProbeResult r : results) {
                    sb.append("  ").append(r.line()).append('\n');
                }
                sb.append(BlenderEnv.gpuProbeHw(BlenderActivity.this)
                        ? ("结论：已记录可用的硬件组合 → " + BlenderEnv.gpuProbeName(BlenderActivity.this)
                        + "（加速模式会自动用它）")
                        : "结论：没找到能用的硬件组合，继续软件渲染（Cycles 本来也不受影响）。");
                tvAccel.setText(sb.toString());
                appendLog(sb.toString());
                refreshStatus();
            });
        }, "mw-blender-gpu").start();
    }

    /** 加速开关当前状态的一句话说明。 */
    private void updateAccelText() {
        if (tvAccel == null) return;
        final String mode = BlenderEnv.accelMode(this);
        final StringBuilder sb = new StringBuilder();
        sb.append("当前模式：");
        sb.append("auto".equals(mode) ? "自动" : "soft".equals(mode) ? "强制软件"
                : "hw".equals(mode) ? "强制硬件" : "自定义");
        if (BlenderEnv.gpuProbeHw(this)) {
            sb.append(" · 已记录硬件组合：").append(BlenderEnv.gpuProbeName(this));
            sb.append("\n实际生效 env：").append(BlenderEnv.gpuProbeEnv(this));
        } else {
            sb.append(" · 还没找到可用硬件组合（软件渲染，Cycles 不受影响）");
        }
        if ("custom".equals(mode) && !BlenderEnv.customGpuEnv(this).isEmpty()) {
            sb.append("\n自定义 env：").append(BlenderEnv.customGpuEnv(this));
        }
        tvAccel.setText(sb.toString());
    }

    /** 自定义 GALLIUM_DRIVER / VK_ICD_FILENAMES 等（Mali 的 panfork/panvk 用得上）。 */
    private void editCustomGpuEnv() {
        final EditText e = input("例如 GALLIUM_DRIVER=zink VK_ICD_FILENAMES=/path/xxx.json",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setText(BlenderEnv.customGpuEnv(this));
        e.setMinLines(3);
        final ScrollView wrap = new ScrollView(this);
        final LinearLayout box = col();
        box.addView(text("写在这里的键值对会原样导给 Blender（空格分隔）。"
                + "Mali 的 panfork / panvk-kbase-android 构建需要自己把 .so 放进 sysroot 再指过来。",
                12f, DIM));
        box.addView(e);
        wrap.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("自定义驱动 env")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    BlenderEnv.setCustomGpuEnv(this, e.getText().toString());
                    BlenderEnv.setAccelMode(this, "custom");
                    spAccel.setSelection(indexOfAccel("custom"));
                    tvAccel.setText("已保存自定义 env，加速模式切到「自定义驱动 env」。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 预检 / 工程工具

    /** 打开工程工具页（材质 / 贴图 / UV / 场景 / 打包 / 保存 / 导出）。 */
    private void openProjectTools() {
        final Intent i = new Intent(this, ProjectToolsActivity.class);
        if (project != null) i.putExtra(ProjectToolsActivity.EXTRA_PROJECT, project.getAbsolutePath());
        startActivity(i);
    }

    /** 渲染前预检：能修的当场修，修不了的说清楚。 */
    private void runPreflight() {
        if (!RootShell.isReady()) {
            Toast.makeText(this, "先授权 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        tvStatus.setText("预检中（会打开工程读一遍，文件大时要等一会）…");
        final BlenderScript.Params probeParams = currentParams();
        new Thread(() -> {
            final Preflight.Report report = Preflight.check(BlenderActivity.this, project, probeParams,
                    true, this::appendLog);
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText(report.canRender() ? "预检通过，可以开渲。" : "预检不通过：见下方报告。");
                appendLog("── 预检报告 ──");
                for (String line : report.text().split("\\n")) {
                    appendLog(line);
                }
                tvEnv.setText(report.text());
                final Preflight.Item fixable = report.firstFixable();
                if (fixable == null) {
                    if (!report.canRender()) {
                        Toast.makeText(this, "预检不通过", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "预检通过", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("要不要自动修复？")
                        .setMessage(fixable.name + "：" + fixable.detail)
                        .setPositiveButton("修复", (d, w) -> {
                            final String msg = Preflight.applyFix(BlenderActivity.this, fixable.fixId,
                                    project, probeParams, this::appendLog);
                            tvStatus.setText(msg);
                            appendLog("· 修复：" + msg);
                        })
                        .setNegativeButton("先不管", null)
                        .show();
            });
        }, "mw-blender-preflight").start();
    }

    private void confirmClearOutputs() {
        new AlertDialog.Builder(this)
                .setTitle("清空渲染产物")
                .setMessage("删除 " + BlenderEnv.outDir(this) + " 下的所有文件。"
                        + "（序列渲到一半别清，否则续渲会从头再来）")
                .setPositiveButton("删除", (d, w) -> {
                    FileUtil.deleteRec(BlenderEnv.outDir(this));
                    //noinspection ResultOfMethodCallIgnored
                    BlenderEnv.outDir(this).mkdirs();
                    refreshOutputs();
                    tvStatus.setText("产物已清空。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按当前 UI 收集一份参数（预检与渲染共用）。 */
    private BlenderScript.Params currentParams() {
        final BlenderScript.Params p = new BlenderScript.Params();
        final int engineIndex = Math.max(0, Math.min(spEngine.getSelectedItemPosition(), ENGINE_IDS.length - 1));
        p.engine = ENGINE_IDS[engineIndex];
        p.samples = parseInt(etSamples, 64);
        final int resIndex = Math.max(0, spRes.getSelectedItemPosition());
        if (resIndex < RES_SIZES.length - 1) {
            p.resX = RES_SIZES[resIndex][0];
            p.resY = RES_SIZES[resIndex][1];
        } else {
            p.resX = parseInt(etW, 1920);
            p.resY = parseInt(etH, 1080);
        }
        final int fmtIndex = Math.max(0, Math.min(spFormat.getSelectedItemPosition(), FORMAT_IDS.length - 1));
        p.format = FORMAT_IDS[fmtIndex][0];
        p.videoContainer = FORMAT_IDS[fmtIndex][1] == null ? "MPEG4" : FORMAT_IDS[fmtIndex][1];
        p.videoCodec = FORMAT_IDS[fmtIndex][2] == null ? "H264" : FORMAT_IDS[fmtIndex][2];
        p.imageQuality = Math.max(1, Math.min(100, parseInt(etQuality, 90)));
        final int qIndex = Math.max(0, Math.min(spQuality.getSelectedItemPosition(), QUALITY_IDS.length - 1));
        p.quality = QUALITY_IDS[qIndex];
        p.fps = Math.max(1, Math.min(120, parseInt(etFps, 24)));
        p.audio = cbAudio.isChecked();
        p.animation = cbAnim.isChecked() || p.isVideo();
        p.frameStart = parseInt(etFs, 1);
        p.frameEnd = Math.max(p.frameStart, parseInt(etFe, p.frameStart));
        p.filmTransparent = cbTransparent.isChecked() && !p.isVideo();
        p.normalizeVideo();
        return p;
    }

    // ================================================================ 模板

    /** 官方模板（发行包自带）+ 生成结果提示。 */
    private void refreshTemplates() {
        if (tplBox == null) return;
        tplBox.removeAllViews();
        final List<File> official = TemplateLib.officialBlends(this);
        if (!official.isEmpty()) {
            tplBox.addView(text("官方发行包自带 " + official.size() + " 个 .blend 模板（直接用，不复制）：",
                    12f, DIM));
            final LinearLayout row1 = row();
            for (final File f : official) {
                row1.addView(btn(f.getName(), v -> setProject(f, "官方模板：" + f.getName())));
            }
            wrapRows(tplBox, row1);
        } else {
            tplBox.addView(text("这个发行包里没找到官方 .blend 模板，用上面的内置模板即可。",
                    12f, DIM));
        }
    }

    /** 生成内置模板并设为当前工程。 */
    private void makeTemplate(final TemplateLib.Tpl t) {
        if (!BlenderEnv.installed(this)) {
            Toast.makeText(this, "先装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        tvStatus.setText("生成模板：" + t.title + "…");
        new Thread(() -> {
            String msg;
            File out = null;
            try {
                out = TemplateLib.generate(BlenderActivity.this, t.key);
                msg = "已生成模板：" + t.title;
            } catch (Throwable e) {
                msg = "生成失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final File file = out;
            final String text = msg;
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText(text);
                appendLog(text);
                if (file != null) {
                    setProject(file, "模板：" + t.title);
                    tvProject.setText(t.title + "（" + t.desc + "）");
                }
            });
        }, "mw-blender-tpl").start();
    }

    private void setProject(File f, String label) {
        project = f;
        if (label != null) tvProject.setText(label);
        refreshStatus();
    }

    // ================================================================ 可选组件

    /** 环境体检 + 自动补依赖（缺什么装什么）。 */
    private void healDeps() {
        if (!BlenderEnv.installed(this)) {
            Toast.makeText(this, "先装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        tvStatus.setText("体检中…");
        new Thread(() -> {
            final String err = BlenderDeps.heal(BlenderActivity.this, msg -> ui.post(() -> {
                tvStatus.setText(msg);
                appendLog(msg);
            }), new AtomicBooleanHolder());
            ui.post(() -> {
                setBusy(false);
                tvExtras.setText(err.isEmpty() ? "环境正常。" : err);
                appendLog(err.isEmpty() ? "── 体检通过 ──" : "── 体检发现问题：" + err + " ──");
                if (err.isEmpty()) refreshStatus();
            });
        }, "mw-blender-heal").start();
    }

    private void installExtra(String key, String[] pkgs, String doneMsg) {
        setBusy(true);
        tvStatus.setText("安装可选组件…");
        new Thread(() -> {
            final boolean ok = BlenderInstaller.installExtra(BlenderActivity.this, key, pkgs,
                    new BlenderInstaller.Progress() {
                        @Override
                        public void onStatus(final String msg) {
                            ui.post(() -> {
                                tvStatus.setText(msg);
                                appendLog(msg);
                            });
                        }

                        @Override
                        public void onPercent(final int percent) {
                        }
                    }, new AtomicBooleanHolder());
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText(ok ? doneMsg : "安装失败（镜像不通？）");
                refreshExtras();
            });
        }, "mw-blender-extra").start();
    }

    /**
     * BlenderProc（DLR-RM，MIT）：程序化生成场景并渲染的管线，跑在 Blender 自带 Python 里。
     * 手机上很吃资源，所以做成可选：先装 pip，再装包。
     */
    private void installBlenderProc() {
        final File py = BlenderEnv.bundledPython(this);
        if (py == null) {
            Toast.makeText(this, "找不到 Blender 自带的 Python", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("安装 BlenderProc")
                .setMessage("BlenderProc 是 DLR-RM 的开源程序化渲染管线（MIT），能批量生成场景并渲染。\n\n"
                        + "手机上代价不小：pip + numpy/scipy/opencv 等依赖约 300MB，"
                        + "装完只在 Blender 的 Python 里可用（本工具的模板与脚本同样能用）。\n\n"
                        + "需要能访问 PyPI 的网络。继续？")
                .setPositiveButton("安装", (d, w) -> {
                    installExtra("blenderproc", BlenderEnv.PKGS_PYTHON,
                            "pip 已装好，正在装 BlenderProc…");
                    setBusy(true);
                    new Thread(() -> {
                        final String r = runPy("import sys\n"
                                + "print(sys.version)\n");
                        ui.post(() -> {
                            setBusy(false);
                            tvExtras.setText(r);
                            appendLog(r);
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 在 Blender 自带 Python 里跑一段代码，返回输出。 */
    private String runPy(String code) {
        try {
            final File py = BlenderEnv.bundledPython(this);
            if (py == null) return "找不到 Blender 自带 Python。";
            final File script = new File(BlenderEnv.tmpDir(this), "mw_py.py");
            FileUtil.writeText(script, code);
            final String loader = BlenderEnv.loaderPath(this);
            final File bin = new File(BlenderEnv.blenderBin(this));
            final File top = bin.getParentFile();
            final String libPath = BlenderEnv.libPath(this) + ":" + top.getAbsolutePath() + "/lib";
            final String env = "LD_LIBRARY_PATH=" + libPath
                    + " PYTHONHOME=" + new File(top, "python").getAbsolutePath()
                    + " HOME=" + BlenderEnv.homeDir(this).getAbsolutePath()
                    + " TMPDIR=" + BlenderEnv.tmpDir(this).getAbsolutePath() + " LANG=C.UTF-8";
            final RootShell.Result r = RootShell.sh(env + " " + RootShell.q(loader)
                    + " --library-path " + RootShell.q(libPath) + " " + RootShell.q(py.getAbsolutePath())
                    + " " + RootShell.q(script.getAbsolutePath()) + " 2>&1 | tail -12");
            return r.out.trim();
        } catch (Throwable t) {
            return "执行失败：" + t.getMessage();
        }
    }

    private void refreshExtras() {
        if (tvExtras == null) return;
        final StringBuilder sb = new StringBuilder();
        sb.append("硬编编码器：检测中…（MediaCodec 查询会占一点时间，放后台）");
        if (BlenderEnv.extraInstalled(this, "ffmpeg")) {
            final File ff = BlenderEnv.sysrootBin(this, "ffmpeg");
            sb.append("\nffmpeg：").append(ff == null ? "已标记但没找到二进制" : ff.getAbsolutePath());
        }
        if (BlenderEnv.extraInstalled(this, "blenderproc")) {
            sb.append("\nBlenderProc：已装（DLR-RM，MIT）");
        }
        tvExtras.setText(sb.toString());
        new Thread(() -> {
            final String enc = SequenceEncoder.encoderName();
            ui.post(() -> tvExtras.setText("硬编编码器：" + enc
                    + (BlenderEnv.extraInstalled(BlenderActivity.this, "ffmpeg") ? "\nffmpeg：已装" : "")
                    + (BlenderEnv.extraInstalled(BlenderActivity.this, "blenderproc") ? "\nBlenderProc：已装" : "")));
        }, "mw-blender-enc-check").start();
    }

    // ================================================================ 序列硬编

    /** 把输出目录里的 PNG 序列用系统硬编压成 MP4。 */
    private void encodeSequence() {
        if (!RootShell.isReady()) {
            Toast.makeText(this, "需要 Shizuku（渲染产物在它管的目录里）", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<File> frames = SequenceEncoder.framesIn(BlenderEnv.outDir(this), "");
        if (frames.isEmpty()) {
            Toast.makeText(this, "输出目录里没有图片序列", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("序列 → MP4")
                .setMessage("找到 " + frames.size() + " 帧（" + frames.get(0).getName() + " 起）。\n"
                        + "用系统硬编（" + SequenceEncoder.encoderName() + "）压成 MP4，比 Blender 软编快得多。")
                .setPositiveButton("开始", (d, w) -> runEncode(frames))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runEncode(final List<File> frames) {
        setBusy(true);
        bar.setProgress(0);
        final int fps = Math.max(1, parseInt(etFps, 24));
        new Thread(() -> {
            String msg;
            File out = null;
            try {
                out = SequenceEncoder.encode(frames,
                        new File(BlenderEnv.outDir(this), "seq_" + System.currentTimeMillis() + ".mp4"),
                        fps, (done, total) -> ui.post(() -> bar.setProgress(100 * done / Math.max(1, total))),
                        new AtomicBooleanHolder());
                msg = "已压成：" + out.getName() + "（" + FileUtil.human(out.length()) + "）";
            } catch (Throwable e) {
                msg = "压片失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final File f = out;
            final String text = msg;
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText(text);
                appendLog(text);
                refreshOutputs();
                if (f != null && f.isFile()) {
                    tvOutputs.setText(MediaPublisher.publish(BlenderActivity.this, f));
                }
            });
        }, "mw-blender-encode").start();
    }

    // ================================================================ 状态

    private void refreshStatus() {
        final boolean installed = RootShell.isInstalled();
        final boolean ready = RootShell.isReady();
        if (!installed) {
            tvShizuku.setText("未检测到 Shizuku。请先安装并启动 Shizuku（root 或 adb 模式都行）。");
        } else if (!ready) {
            tvShizuku.setText("Shizuku 已安装但未授权。点「申请 / 检查授权」。");
        } else {
            // isRoot() 要跑一条 shell（会阻塞 UI 线程 → 界面发涩），放后台问
            tvShizuku.setText("Shizuku 就绪 · 运行目录 " + BlenderEnv.dir(this).getAbsolutePath());
            tvShizuku.setTextColor(OK);
            new Thread(() -> {
                RootShell.invalidateRootCache();
                final boolean root = RootShell.isRoot();
                ui.post(() -> tvShizuku.setText("Shizuku 就绪 · "
                        + (root ? "root 模式（可执行目录兜底能力最强）" : "adb shell 模式（部分机型能力受限）")
                        + " · 运行目录 " + BlenderEnv.dir(this).getAbsolutePath()));
            }, "mw-blender-rootcheck").start();
        }

        if (BlenderEnv.installed(this)) {
            final String v = BlenderEnv.installedVersion(this);
            tvEnv.setText("已安装 Blender " + v + " · " + (BlenderEnv.softGlOk(this)
                    ? "含软件 OpenGL（EEVEE 可用）" : "只有 Cycles CPU 可用（软件 OpenGL 自检没通过）"));
            tvEnv.setTextColor(OK);
        } else {
            tvEnv.setText("未安装。点「安装 / 更新」：会下载 Debian 的 glibc 运行库（约 100MB）和官方 Blender（约 300MB）。");
            tvEnv.setTextColor(DIM);
        }
        final boolean readyToRender = ready && BlenderEnv.installed(this) && project != null && !busy;
        btnRender.setEnabled(readyToRender);
        updateAccelText();
    }

    private void setBusy(boolean b) {
        busy = b;
        btnInstall.setEnabled(!b);
        btnCancel.setEnabled(b);
        refreshStatus();
    }

    // ================================================================ 安装

    private void install() {
        if (!RootShell.isReady()) {
            Toast.makeText(this, "先授权 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        bar.setProgress(0);
        final BlenderInstaller.Progress pr = new BlenderInstaller.Progress() {
            @Override
            public void onStatus(final String msg) {
                ui.post(() -> {
                    tvStatus.setText(msg);
                    appendLog(msg);
                });
            }

            @Override
            public void onPercent(final int percent) {
                ui.post(() -> bar.setProgress(percent));
            }
        };
        final AtomicBooleanHolder cancel = new AtomicBooleanHolder();
        new Thread(() -> {
            try {
                BlenderInstaller.install(BlenderActivity.this, pr, cancel);
                ui.post(() -> {
                    tvStatus.setText("安装完成。");
                    refreshStatus();
                    setBusy(false);
                    appendLog("── 安装完成 ──");
                });
            } catch (Throwable e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> {
                    tvStatus.setText("安装失败：" + msg);
                    appendLog("── 安装失败：" + msg + " ──");
                    setBusy(false);
                    refreshStatus();
                });
            }
        }, "mw-blender-install").start();
    }

    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("卸载渲染环境")
                .setMessage("会删除 " + BlenderEnv.dir(this).getAbsolutePath()
                        + " 下的所有内容（glibc、Blender、下载缓存、渲染产物），约几百 MB。")
                .setPositiveButton("删除", (d, w) -> {
                    new Thread(() -> {
                        BlenderInstaller.uninstall(BlenderActivity.this);
                        ui.post(() -> {
                            tvStatus.setText("环境已删除。");
                            refreshStatus();
                            refreshOutputs();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editMirrors() {
        final LinearLayout box = col();
        final EditText blender = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        blender.setText(BlenderEnv.blenderMirrors(this));
        blender.setMinLines(3);
        final EditText deb = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        deb.setText(BlenderEnv.debMirrors(this));
        deb.setMinLines(3);
        final EditText suite = input("", InputType.TYPE_CLASS_TEXT);
        suite.setText(BlenderEnv.debSuite(this));
        box.addView(text("Blender 下载地址（一行一个，依次尝试）", 13f, TEXT));
        box.addView(blender);
        box.addView(text("Debian 镜像（一行一个）", 13f, TEXT));
        box.addView(deb);
        box.addView(text("Debian 套件（bookworm / trixie）", 13f, TEXT));
        box.addView(suite);
        final ScrollView wrap = new ScrollView(this);
        wrap.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("镜像设置")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    BlenderEnv.setBlenderMirrors(this, blender.getText().toString());
                    BlenderEnv.setDebMirrors(this, deb.getText().toString());
                    BlenderEnv.setDebSuite(this, suite.getText().toString().trim());
                    tvStatus.setText("镜像已保存，重新点「安装 / 更新」生效。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 工程

    private void pickBlend() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-blender", "application/octet-stream", "*/*"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pickFile.launch(i);
    }

    private void pickFolder() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pickTree.launch(i);
    }

    /** 复制单个 .blend 到工作目录。 */
    private void importProject(Uri uri) {
        final String name = queryName(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".blend")) {
            new AlertDialog.Builder(this)
                    .setTitle("不是 .blend 文件")
                    .setMessage("选中的文件是「" + name + "」。Blender 渲染需要 .blend 工程文件。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        setBusy(true);
        tvStatus.setText("复制工程…");
        new Thread(() -> {
            final String msg = doImport(uri, name);
            ui.post(() -> {
                setBusy(false);
                tvStatus.setText(msg);
                if (project != null) {
                    tvProject.setText("工程：" + project.getName() + "（" + FileUtil.human(project.length()) + "）");
                }
                refreshStatus();
            });
        }).start();
    }

    private String doImport(Uri uri, String name) {
        final File work = BlenderEnv.workDir(BlenderActivity.this);
        if (!FileUtil.mkdirs(work)) return "无法创建工作目录：" + work;
        final File dst = new File(work, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return "读不到该文件（可能没有授权）";
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            dst.setReadable(true, false);
            project = dst;
            return "已导入：" + name + "（" + FileUtil.human(dst.length()) + "）";
        } catch (Throwable t) {
            return "导入失败：" + t.getMessage();
        }
    }

    /** 复制整个工程目录（保留相对路径的贴图/字体）。 */
    private void importProjectDir(Uri tree) {
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        final DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
        if (dir == null || !dir.isDirectory()) {
            tvStatus.setText("选中的不是文件夹");
            return;
        }
        final List<DocumentFile> blends = new ArrayList<>();
        for (DocumentFile f : dir.listFiles()) {
            final String n = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".blend")) blends.add(f);
        }
        if (blends.isEmpty()) {
            tvStatus.setText("这个文件夹里没有 .blend 文件");
            return;
        }
        if (blends.size() > 1) {
            final String[] items = new String[blends.size()];
            for (int i = 0; i < items.length; i++) items[i] = blends.get(i).getName();
            new AlertDialog.Builder(this)
                    .setTitle("选一个工程")
                    .setItems(items, (d, w) -> copyTree(dir, blends.get(w)))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        copyTree(dir, blends.get(0));
    }

    private void copyTree(DocumentFile dir, DocumentFile blend) {
        setBusy(true);
        final String name = blend.getName();
        tvStatus.setText("复制工程目录…");
        new Thread(() -> {
            final File work = BlenderEnv.workDir(BlenderActivity.this);
            FileUtil.deleteRec(new File(work, "proj"));
            final File dstRoot = new File(work, "proj");
            int[] count = {0};
            copyDir(dir, dstRoot, count);
            final File dst = new File(dstRoot, name);
            final boolean ok = dst.isFile();
            ui.post(() -> {
                setBusy(false);
                if (ok) {
                    project = dst;
                    tvProject.setText("工程：" + name + "（已带 " + count[0] + " 个素材文件）");
                }
                tvStatus.setText(ok ? "工程目录已导入" : "复制失败");
                refreshStatus();
            });
        }).start();
    }

    private void copyDir(DocumentFile src, File dst, int[] count) {
        if (!FileUtil.mkdirs(dst)) return;
        for (DocumentFile f : src.listFiles()) {
            final String n = f.getName();
            if (n == null || n.equals(".") || n.equals("..")) continue;
            final File out = new File(dst, n);
            if (f.isDirectory()) {
                copyDir(f, out, count);
            } else {
                try (InputStream in = getContentResolver().openInputStream(f.getUri());
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) continue;
                    final byte[] buf = new byte[1 << 16];
                    int k;
                    while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                    //noinspection ResultOfMethodCallIgnored
                    out.setReadable(true, false);
                    count[0]++;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ================================================================ 渲染

    private void startRender() {
        // 点「开始渲染」本身不允许崩：任何异常都变成一句人话
        try {
            startRenderInner();
        } catch (Throwable t) {
            setBusy(false);
            final String msg = "启动渲染失败：" + t;
            tvStatus.setText(msg);
            appendLog(msg);
            new AlertDialog.Builder(this)
                    .setTitle("启动渲染失败")
                    .setMessage(msg + "\n\n（这属于参数/环境层面的问题，不是 Blender 崩溃）")
                    .setPositiveButton("好", null)
                    .show();
        }
    }

    private void startRenderInner() {
        if (project == null || !BlenderEnv.installed(this)) {
            Toast.makeText(this, "先装环境并选工程", Toast.LENGTH_SHORT).show();
            showTab(1);
            return;
        }
        final int engineIndex = Math.max(0, spEngine.getSelectedItemPosition());
        final String engine = ENGINE_IDS[Math.min(engineIndex, ENGINE_IDS.length - 1)];
        if (engine.contains("EEVEE") && !BlenderEnv.softGlOk(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("EEVEE 可能用不了")
                    .setMessage("安装时的软件 OpenGL 自检没通过，EEVEE 大概率会失败。要不要改用 Cycles（CPU）继续？")
                    .setPositiveButton("用 Cycles", (d, w) -> {
                        spEngine.setSelection(0);
                        startRender();
                    })
                    .setNegativeButton("仍然试试 EEVEE", (d, w) -> launch(engine))
                    .show();
            return;
        }
        launch(engine);
    }

    private void launch(String engine) {
        final BlenderScript.Params p = new BlenderScript.Params();
        p.engine = engine;
        p.samples = parseInt(etSamples, 64);
        p.threads = 0;
        final int resIndex = Math.max(0, spRes.getSelectedItemPosition());
        if (resIndex < RES_SIZES.length - 1) {
            p.resX = RES_SIZES[resIndex][0];
            p.resY = RES_SIZES[resIndex][1];
            p.resPercent = 100;
        } else {
            p.resX = parseInt(etW, 1920);
            p.resY = parseInt(etH, 1080);
            p.resPercent = 100;
        }
        final int fmtIndex = Math.max(0, Math.min(spFormat.getSelectedItemPosition(), FORMAT_IDS.length - 1));
        final String[] ids = FORMAT_IDS[fmtIndex];
        p.format = ids[0];
        p.videoContainer = ids[1] == null ? "MPEG4" : ids[1];
        p.videoCodec = ids[2] == null ? "H264" : ids[2];
        p.imageQuality = Math.max(1, Math.min(100, parseInt(etQuality, 90)));
        final int qIndex = Math.max(0, Math.min(spQuality.getSelectedItemPosition(), QUALITY_IDS.length - 1));
        p.quality = QUALITY_IDS[qIndex];
        p.fps = Math.max(1, Math.min(120, parseInt(etFps, 24)));
        p.audio = cbAudio.isChecked();
        p.normalizeVideo();

        // 选了静帧格式却勾了动画 → 按序列处理；选了视频但没勾动画 → 强制动画
        p.animation = cbAnim.isChecked() || p.isVideo();
        p.frameStart = parseInt(etFs, 1);
        p.frameEnd = Math.max(p.frameStart, parseInt(etFe, p.frameStart));
        p.filmTransparent = cbTransparent.isChecked() && !p.isVideo();
        final String base = baseName(project.getName());
        BlenderRenderer.applyOutput(this, p, base);

        setBusy(true);
        bar.setProgress(0);
        tvStatus.setText("渲染中…");
        appendLog("── 开始渲染：" + project.getName() + " · " + engine + " · "
                + p.resX + "×" + p.resY + " · 采样 " + p.samples + " · " + p.format
                + (p.isVideo() ? " · " + p.videoContainer + "/" + p.videoCodec + " @" + p.fps + "fps" : "")
                + (p.animation ? " · 帧 " + p.frameStart + "-" + p.frameEnd : "") + " ──");

        // 走兜底编排：校验产物 + 降级重试 + 逐帧续渲 + 视频失败回退硬编
        cancelRequested = false;
        task = new BlenderRenderer.Task();
        final BlenderRenderer.Task myTask = task;
        new Thread(() -> {
            final RenderFallback.Outcome outcome;
            try {
                outcome = RenderFallback.render(getApplicationContext(), project, p,
                        new RenderFallback.Listener() {
                            @Override
                            public void onLog(String line) {
                                appendLog(line);
                            }

                            @Override
                            public void onStage(String stage) {
                                ui.post(() -> tvStatus.setText(stage));
                                appendLog("· " + stage);
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                ui.post(() -> {
                                    final int pct = total <= 0 ? 0 : (int) (100L * current / total);
                                    bar.setProgress(Math.max(bar.getProgress(), Math.min(100, pct)));
                                    if (total > 1) {
                                        tvStatus.setText("渲染中 · " + current + " / " + total + " 帧");
                                    }
                                });
                            }
                        }, myTask);
            } catch (Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    tvStatus.setText("渲染异常：" + t);
                });
                return;
            }
            ui.post(() -> {
                try {
                    setBusy(false);
                    bar.setProgress(outcome.ok ? 100 : bar.getProgress());
                    tvStatus.setText(outcome.ok ? "渲染完成" : ("渲染失败：" + outcome.message));
                    appendLog("── 尝试记录 ──");
                    for (String a : outcome.attempts) {
                        appendLog("  " + a);
                    }
                    if (cancelRequested) {
                        tvStatus.setText("已取消（已渲好的帧保留，可再点开始续渲）");
                    }
                    if (!outcome.ok) {
                        appendLog("── " + outcome.message + " ──");
                    }
                    refreshOutputs();
                    if (outcome.ok && outcome.output != null) {
                        Toast.makeText(BlenderActivity.this,
                                "渲染完成：" + outcome.output.getName(), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    // 收尾 UI 出错也不许崩
                    tvStatus.setText("渲染结束（收尾时出错：" + t + "）");
                    appendLog("收尾异常：" + t);
                }
            });
        }, "mw-blender-fallback").start();
    }

    // ================================================================ 产物

    private void refreshOutputs() {
        if (outBox == null) return;
        outBox.removeAllViews();
        final List<File> files = listOutputs();
        if (files.isEmpty()) {
            tvOutputs.setText("还没有渲染产物。");
            return;
        }
        for (final File f : files) {
            final LinearLayout item = col();
            item.setBackground(round(CARD, 10));
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.addView(text(f.getName(), 13f, TEXT));
            item.addView(text(FileUtil.human(f.length()) + " · " + f.getAbsolutePath(), 11f, DIM));
            final LinearLayout btns = row();
            if (isImage(f.getName())) {
                btns.addView(btn("预览", v -> preview(f)));
            }
            btns.addView(btn("存相册", v -> {
                final String msg = MediaPublisher.publish(BlenderActivity.this, f);
                Toast.makeText(BlenderActivity.this, msg, Toast.LENGTH_SHORT).show();
                tvOutputs.setText(msg);
            }));
            item.addView(pad(btns, 6, 0, 0, 0));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            outBox.addView(item, lp);
        }
        tvOutputs.setText("共 " + files.size() + " 个文件。");
    }

    private List<File> listOutputs() {
        final File[] kids = BlenderEnv.outDir(this).listFiles();
        final List<File> out = new ArrayList<>();
        if (kids != null) out.addAll(Arrays.asList(kids));
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }

    private static boolean isImage(String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp");
    }

    private void preview(File f) {
        new Thread(() -> {
            Bitmap bmp = decode(f);
            ui.post(() -> {
                if (bmp == null) {
                    Toast.makeText(this, "读不了这张图（可能是 root 写的，权限不足）", Toast.LENGTH_LONG).show();
                    return;
                }
                final ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                final ScrollView wrap = new ScrollView(this);
                wrap.addView(iv);
                new AlertDialog.Builder(this).setView(wrap).setPositiveButton("关闭", null).show();
            });
        }).start();
    }

    private static Bitmap decode(File f) {
        try {
            final BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            int sample = 1;
            final int max = Math.max(opt.outWidth, opt.outHeight);
            while (max / sample > 1600) sample *= 2;
            final BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 日志

    private void appendLog(String line) {
        ui.post(() -> {
            synchronized (logLines) {
                logLines.add(line);
                while (logLines.size() > 800) logLines.remove(0);
            }
            final long now = System.currentTimeMillis();
            if (now - lastLogFlush >= 250) {
                flushLog();
            } else {
                ui.removeCallbacks(flushLogTask);
                ui.postDelayed(flushLogTask, 260);
            }
        });
    }

    private final Runnable flushLogTask = this::flushLog;

    private void flushLog() {
        lastLogFlush = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder();
        synchronized (logLines) {
            for (String s : logLines) {
                sb.append(s).append('\n');
            }
        }
        tvLog.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 安装任务继续跑完（环境装一半被杀掉反而更麻烦），渲染任务交给它自己
    }

    // ================================================================ 小组件

    private LinearLayout col() {
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        final LinearLayout l = col();
        l.setBackground(round(CARD, 14));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView text(String s, float size, int color) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView label(String s) {
        final TextView t = text(s, 13f, DIM);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private View pad(View v, int l, int t, int r, int b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
        return v;
    }

    private LinearLayout.LayoutParams weight() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    /**
     * 横向 LinearLayout 不会自动换行：每 3 个按钮折一行塞进 parent。
     *
     * <p>注意：先把子 View 全部取出来再 {@code removeAllViews()}；
     * 千万别在循环里边读边 {@code removeViewAt(i)} —— 索引会错位拿到 null，
     * 直接 NPE 崩在 ViewGroup.removeViewInternal（本项目踩过一次）。</p>
     */
    private void wrapRows(LinearLayout parent, LinearLayout source) {
        final int perRow = 3;
        final int n = source.getChildCount();
        if (n == 0) return;
        final View[] kids = new View[n];
        for (int i = 0; i < n; i++) {
            kids[i] = source.getChildAt(i);
        }
        source.removeAllViews();
        LinearLayout current = null;
        for (int i = 0; i < n; i++) {
            if (i % perRow == 0) {
                current = row();
                current.setPadding(0, dp(4), 0, 0);
                parent.addView(current);
            }
            if (current != null && kids[i] != null) {
                current.addView(kids[i]);
            }
        }
    }

    private Button btn(String s, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(s);
        // 手指友好：字大一点、高度够、间距够
        b.setTextSize(15f);
        b.setAllCaps(false);
        b.setMinHeight(dp(46));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setTextColor(TEXT);
        b.setBackground(round(INPUT, 12));
        b.setOnClickListener(l);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 底部主操作按钮（占半行、更高）。 */
    private Button bigBtn(String s, int color, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(s);
        b.setTextSize(17f);
        b.setAllCaps(false);
        b.setMinHeight(dp(54));
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(round(color, 14));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams bigWeight() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private EditText input(String hint, int type) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13f);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setInputType(type);
        e.setBackground(round(INPUT, 10));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setSingleLine(!hint.contains("，"));
        return e;
    }

    private CheckBox check(String s, boolean on) {
        final CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(13f);
        c.setTextColor(TEXT);
        c.setChecked(on);
        return c;
    }

    private Spinner spinner(String[] items) {
        final Spinner s = new Spinner(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setBackground(round(INPUT, 10));
        return s;
    }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int parseInt(EditText e, int def) {
        try {
            final String s = e.getText().toString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String baseName(String name) {
        String n = name;
        final int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        }
        final String p = uri.getLastPathSegment();
        return p == null ? null : p;
    }

    /** 简易可取消标记（安装过程用）。 */
    private static final class AtomicBooleanHolder implements Downloader.Cancel {
        private volatile boolean value;

        @Override
        public boolean isCancelled() {
            return value;
        }

        void cancel() {
            value = true;
        }
    }
}
