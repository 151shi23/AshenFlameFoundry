package com.mineimator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Mine-imator 动画工作室入口（由 MinewaysMobile 的工具卡片启动）。
 *
 * <p>这里只做三件事：检查本机能不能跑 → 把 Activity / AssetManager / 私有目录交给原生引擎
 * （{@link NativeHost#nativeStart}）→ 挂上 GL 画面载体与软键盘桥。界面本体全部由原生引擎自绘。
 */
public final class MainActivity extends Activity {

    /** 引擎内核（随包发布在 lib/arm64-v8a/，安装时解压到 nativeLibraryDir）。 */
    private static final String ENGINE_LIB = "libmineimator.so";

    /** 我们的中文外壳设置（默认中文）。 */
    private static final String PREF = "mim_prefs";
    private static final String KEY_LANG = "lang";
    private static final String KEY_AUTOSAVE = "autosave";
    private static final String KEY_INSIST = "insist_lowprofile";

    private MineImatorView glView;
    private EngineLogOverlay engineLog;

    /** 激活码记账：主界面核销后带过来的 token。 */
    private String activationToken;
    private String activationFeature;
    private boolean activationDone;
    private ExportWatcher exportWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activationToken = getIntent() == null ? null
                : getIntent().getStringExtra("activation_token");
        activationFeature = getIntent() == null ? null
                : getIntent().getStringExtra("activation_feature");
        com.mineways.CrashReport.install(this);
        applyLanguage();

        // 能力评估：评估本身绝不崩（每个探针独立兜底）。能跑的机型不打扰，直接进。
        final DeviceCheck.Report report = DeviceCheck.runSafely(this);
        if (!report.ok()) {
            showBlocker(report);
            return;
        }
        if (report.needsNotice() && !insisted()) {
            showAssessment(report);
            return;
        }
        startStudio(report.lowProfile || insisted());
    }

    /** 真正开工：把 Activity / AssetManager / 私有目录交给内核，挂上画面与自己的外壳。 */
    private void startStudio(boolean lowProfile) {
        try {
            if (!engineAvailable()) {
                showUnsupported();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                final WindowManager.LayoutParams params = getWindow().getAttributes();
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                getWindow().setAttributes(params);
            }
            getWindow().setStatusBarColor(0);
            getWindow().setNavigationBarColor(0);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            if (lowProfile) {
                applyLowProfile();
            }

            final AssetManager assets = getAssets();
            // 存储根可以由外壳切换（应用私有 / 外部可见目录），内核拿到的 filesDir 就是这个根
            NativeHost.nativeStart(this, assets,
                    ProjectStorage.root(this).getAbsolutePath(), getCacheDir().getAbsolutePath());

            finishActivation(true);   // 引擎起来了 = 这次机会用掉了
            glView = new MineImatorView(this, lowProfile);
            glView.setContentDescription(getString(com.mineways.R.string.mim_app_name));
            Keyboard.INSTANCE.attach(glView);

            // GL 画面 + 引擎日志浮层（黑屏/异常时点开即可看到引擎自己的日志，不用连电脑）
            final FrameLayout root = new FrameLayout(this);
            root.addView(glView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            engineLog = new EngineLogOverlay(this);
            root.addView(engineLog.view());
            root.addView(menuButton());
            setContentView(root);
            hideSystemBars();

            // 导出自动接管：点「导出」后内核写出的视频/图片，写稳就自动送进系统相册
            exportWatcher = new ExportWatcher(this, autoSaveEnabled());
            if (lowProfile) {
                Toast.makeText(this, "已启用保守适配（低内存 / 老机型模式）", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            showEngineError(t);
        }
    }

    /**
     * 保守适配（执意进入的低端机/老机）：把引擎解包出来的渲染预设换成「性能」优先。
     * 只在解包过（第二次起）且文件存在时动手，失败一律忽略。
     */
    private void applyLowProfile() {
        try {
            final File renderDir = new File(getFilesDir(), "assets/Data/Render");
            final File perf = new File(renderDir, "performance.mirender");
            final File balanced = new File(renderDir, "balanced.mirender");
            if (perf.isFile() && balanced.isFile() && perf.length() != balanced.length()) {
                copyFile(perf, balanced);
            }
        } catch (Throwable ignored) {
            // 保守适配是加分项，失败不能影响开工
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        final InputStream in = new FileInputStream(src);
        try {
            final OutputStream out = new FileOutputStream(dst);
            try {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** 硬性不达标（架构 / 图形能力 / 空间）：说清原因，绝不闪退。 */
    private void showBlocker(final DeviceCheck.Report report) {
        new AlertDialog.Builder(this)
                .setTitle("设备能力评估未通过")
                .setMessage(DeviceCheck.blockersText(report))
                .setPositiveButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNeutralButton("仍要尝试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 空间不足这类其实可以硬闯（失败也只是渲染中断）；架构/图形不足则会被下面的
                        // try/catch 接住，变成一条明确报错 —— 无论如何都不会闪退。
                        setInsisted();
                        startStudio(true);
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** 能跑但吃力：说清风险；执意进入就给保守适配。 */
    private void showAssessment(final DeviceCheck.Report report) {
        new AlertDialog.Builder(this)
                .setTitle("设备能力评估")
                .setMessage(DeviceCheck.warningsText(report))
                .setPositiveButton("仍然进入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setInsisted();
                        startStudio(true);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** 内核起不来：即时报错，别让用户对着黑屏猜。 */
    private void showEngineError(Throwable t) {
        final String detail = String.valueOf(t);
        new AlertDialog.Builder(this)
                .setTitle("内核启动失败")
                .setMessage("动画内核没能启动：\n\n" + detail
                        + "\n\n可到 ☰ →「崩溃记录」把详情复制出来反馈。")
                .setPositiveButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private boolean insisted() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_INSIST, false);
    }

    private void setInsisted() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_INSIST, true).apply();
    }

    /** 引擎只编译了 arm64-v8a：别的架构（32 位 ARM）直接给提示，不要去 loadLibrary（会崩）。 */
    private boolean engineAvailable() {
        return new File(getApplicationInfo().nativeLibraryDir, ENGINE_LIB).isFile();
    }

    private void showUnsupported() {
        final TextView tv = new TextView(this);
        tv.setText("这台设备的 CPU 架构不受支持：Mine-imator 引擎目前只有 arm64-v8a 版本"
                + "（需要 64 位 ARM 设备）。");
        tv.setTextSize(16f);
        tv.setPadding(48, 160, 48, 48);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFECECEC);
        tv.setBackgroundColor(0xFF15171A);
        setContentView(tv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (FilePicker.INSTANCE.getPicking()) {
            // 选择器被系统杀掉后回到前台：当作取消处理，避免原生侧一直等
            FilePicker.INSTANCE.onResult(this, 0, null);
        }
        if (glView != null) {
            glView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (!FilePicker.INSTANCE.getPicking() && glView != null) {
            glView.onPause();
        }
        super.onPause();
    }

    /** 记账：成功=true 扣次，false 只是释放；重复调用只算一次。 */
    private void finishActivation(boolean success) {
        if (activationDone || activationToken == null || activationToken.length() == 0) {
            return;
        }
        activationDone = true;
        com.mineways.ActivationDialog.finish(this, activationFeature, activationToken, success);
    }

    @Override
    protected void onDestroy() {
        finishActivation(false);   // 没走到核销就被杀掉 → 释放，不扣次
        if (exportWatcher != null) {
            exportWatcher.stop();
        }
        if (engineLog != null) {
            engineLog.stop();
        }
        if (glView != null) {
            NativeHost.nativeStop();
        }
        super.onDestroy();
        // 工作室跑在独立进程（:studio）。内核是进程级单例（Qt 全局 / GL 资源 / 缓存的 Activity
        // 引用都活在进程里），退出即杀进程是唯一能保证「返回主界面后再进来」是干净的全新初始化，
        // 否则第二次 nativeStart 会重复初始化 → 必闪退。顺带把内存和 GL 资源立刻还给系统。
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FilePicker.REQUEST_OPEN) {
            FilePicker.INSTANCE.onResult(this, resultCode, data);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    // ---------------------------------------------------------------- 中文外壳（我们自己的部分）

    /**
     * 引擎（Qt）用 java.util.Locale.getDefault() 判断界面语言（Qt5 在安卓上就是这么取的），
     * 所以开工前先把进程语言定成中文 —— 界面文案、日期格式都会跟着变中文。
     */
    private void applyLanguage() {
        final SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        final String mode = sp.getString(KEY_LANG, "zh");
        if ("auto".equals(mode)) {
            return;
        }
        final Locale locale = "en".equals(mode) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(locale);
        final Configuration cfg = new Configuration(getResources().getConfiguration());
        cfg.setLocale(locale);
        getResources().updateConfiguration(cfg, getResources().getDisplayMetrics());
    }

    /** 右上角 ☰：中文菜单（引擎界面是三方的原生界面，这一层是我们外加上去的）。 */
    private View menuButton() {
        final TextView b = new TextView(this);
        b.setText("☰");
        b.setTextSize(18f);
        b.setTextColor(0xFFECECEC);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0x99000000);
        b.setPadding(dp(12), dp(4), dp(12), dp(6));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.topMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStudioMenu();
            }
        });
        return b;
    }

    private void showStudioMenu() {
        final String[] items = {
                "我的作品（保存到相册）",
                "怎么导出视频？",
                "界面语言（当前：" + languageLabel() + "）",
                "项目文件夹（查看 / 切换）",
                "查看引擎日志",
                "崩溃记录（闪退取证）",
                "导出后自动存相册（当前：" + (autoSaveEnabled() ? "开" : "关") + "）",
                "退出工作室"
        };
        new AlertDialog.Builder(this)
                .setTitle("Mine-imator 动画工作室")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                startActivity(new Intent(MainActivity.this,
                                        com.mineways.MineImatorWorksActivity.class));
                                break;
                            case 1:
                                showExportHelp();
                                break;
                            case 2:
                                showLanguageDialog();
                                break;
                            case 3:
                                showFolderDialog();
                                break;
                            case 4:
                                if (engineLog != null) {
                                    engineLog.toggle();
                                }
                                break;
                            case 5:
                                com.mineways.CrashReport.show(MainActivity.this);
                                break;
                            case 6:
                                toggleAutoSave();
                                break;
                            default:
                                finish();
                                break;
                        }
                    }
                })
                .show();
    }

    private void showExportHelp() {
        new AlertDialog.Builder(this)
                .setTitle("怎么导出视频")
                .setMessage("在工作室里：\n"
                        + "1) 先打开或新建一个工程（Projects → New / Open）；\n"
                        + "2) 点界面上的导出按钮（Export movie）；\n"
                        + "3) 选帧率与分辨率，然后开始渲染；\n"
                        + "4) 渲染完回到这里：☰ →「我的作品（保存到相册）」，\n"
                        + "   点「保存到相册」就能在系统相册看到。\n\n"
                        + "导出用的是内核里自带的 FFmpeg，渲染时请别退出工作室。\n\n"
                        + "⚠ 对话框里若看到「浏览 / 更改文件夹」按钮，别点它 —— 那个按钮在安卓版没有通道，"
                        + "点了不会有反应；直接用默认文件名导出即可，成品会自动进相册。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private String languageLabel() {
        final String mode = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_LANG, "zh");
        if ("en".equals(mode)) {
            return "English";
        }
        if ("auto".equals(mode)) {
            return "跟随系统";
        }
        return "中文";
    }

    private void showLanguageDialog() {
        final String[] options = {"中文（默认）", "跟随系统", "English"};
        new AlertDialog.Builder(this)
                .setTitle("界面语言")
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String value = which == 0 ? "zh" : (which == 1 ? "auto" : "en");
                        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                                .putString(KEY_LANG, value).apply();
                        Toast.makeText(MainActivity.this,
                                "已设为 " + options[which] + "，重新进入工作室后生效",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /** 导出后是否自动送进系统相册（默认开）。 */
    /**
     * 项目文件夹：显示真实路径 + 在「私有目录 / 外部可见目录」之间切换（含工程搬运）。
     * 引擎自带的「更改文件夹」按钮在安卓版没有通道（移植只接了"打开文件"），所以能力放在这里。
     */
    private void showFolderDialog() {
        final String[] actions = {
                ProjectStorage.useExternal(this)
                        ? "切回应用私有目录（更快更稳）"
                        : "切到外部可见目录（文件管理器能直接看到）",
                "知道了"
        };
        new AlertDialog.Builder(this)
                .setTitle("项目文件夹")
                .setMessage(ProjectStorage.describe(this))
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which != 0) {
                            return;
                        }
                        final String result = ProjectStorage.switchTo(MainActivity.this,
                                !ProjectStorage.useExternal(MainActivity.this));
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("切换结果")
                                .setMessage(result + "\n\n重新进入工作室后生效。")
                                .setPositiveButton("立刻重进", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        finish();
                                    }
                                })
                                .setNegativeButton("稍后", null)
                                .show();
                    }
                })
                .show();
    }

    private boolean autoSaveEnabled() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_AUTOSAVE, true);
    }

    private void toggleAutoSave() {
        final boolean next = !autoSaveEnabled();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_AUTOSAVE, next).apply();
        if (exportWatcher != null) {
            exportWatcher.setEnabled(next);
        }
        Toast.makeText(this, next
                        ? "已开启：导出完成后自动存进相册"
                        : "已关闭：改到「我的作品」里手动保存",
                Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller == null) {
                return;
            }
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
