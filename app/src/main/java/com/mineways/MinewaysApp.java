package com.mineways;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

/**
 * 全局应用入口。
 * <p/>
 * 负责安装全局未捕获异常处理器：无论哪个功能（导出 / 世界信息 / Shizuku 导入 / Chunker 转换……）
 * 在哪个线程上出错，只要线程没有自己的异常处理器，都会被这里捕获，并把完整的错误堆栈
 * 自动复制到系统剪贴板，方便用户直接粘贴反馈。随后继续交给系统默认处理器处理，
 * 不吞掉崩溃本身，保留原有行为。
 */
public class MinewaysApp extends Application {

    private static final String TAG = "MinewaysGlobalError";
    private static final String CLIP_LABEL = "MinewaysError";

    /** 前台 Activity 计数：到 0 表示用户已经离开软件。 */
    private int started;

    /** 离开软件时按「有权限就开」的约定挂系统悬浮条；回前台立刻收起。 */
    private void overlayOnBackground() {
        try {
            if (!com.mineways.music.MusicCtl.active()) return;
            if (!com.mineways.music.MusicSources.overlayOn(this)) return;
            if (!com.mineways.music.MusicSources.backgroundAllowed(this)) return;
            if (Build.VERSION.SDK_INT >= 23
                    && !android.provider.Settings.canDrawOverlays(this)) {
                return;   // 没给「显示在其他应用上层」就不打扰
            }
            com.mineways.music.MusicOverlay.start(this);
        } catch (Throwable t) {
            Log.w(TAG, "overlay start failed", t);
        }
    }

    /** 本进程是否已经拾取过待展示的错误日志（只弹一次）。 */
    private boolean pickedUp = false;

    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * 先把功能清单登记进门面，再启动云端配置。顺序反过来的话，解析器面对一张空清单，
         * 每一条 id= 都会报「找不到功能」，那些假错误会留在关于页面板上，看起来像配置写坏了。
         */
        // 机型/包名云控条件要用的包列表：在任何界面/配置求值之前注入。
        // 安装/卸载后立刻失效缓存并重建界面，ui_hide_*_pkgs 不必等重启。
        com.mineways.cloud.PkgProbe.init(this);
        try {
            IntentFilter pkgFilter = new IntentFilter();
            pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pkgFilter.addDataScheme("package");
            ContextCompat.registerReceiver(this, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    com.mineways.cloud.CloudCatalog.onPackagesChanged();
                }
            }, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
        com.mineways.home.HomeIndex.register(MainActivity.rows(this), MainActivity.catRows());
        // 棱镜玩机的功能表也登记进门面：云端才能用 FEAT hide/disable/label/pin 统一控制，
        // 而不是只认 prism_<id> 这种私有键。同样必须早于 CloudCatalog.init。
        com.mineways.prism.PrismFeatures.registerCloud();

        /*
         * 云端功能目录在这里启动：Application 先于所有 Activity，任何入口（更新门槛页、
         * 云控门槛页、工具箱）都绕不过它。之前挂在 MainActivity.onCreate 上，
         * 出现过"提示和效果都没有"的真机现象，挪到这里最稳。
         */
        com.mineways.cloud.CloudCatalog.init(this);

        /*
         * 统一视觉内核挂在 Application 的生命周期上，而不是每个 Activity 里各调一次：
         * 二十多个工具页各自手搓样式，逐个接线一定会漏，漏掉的页面就还是「一眼安卓」。
         * 必须在 CloudCatalog.init 之后注册——重写要读云控的 ui_* 参数。
         */
        com.mineways.ui.MwChrome.install(this);

        /*
         * 日志只有一个出口：files/logs（app.log 流水 + last-error.txt 最近一次错误）。
         * 装兜底处理器 + 绑定 application context，所有模块都往这里写、都用 AppLog 的复制实现。
         * 必须在任何可能出错的东西之前（CloudCatalog/MwChrome 之后、界面之前）。
         */
        com.mineways.AppLog.bind(this);
        com.mineways.AppLog.init(this);

        registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity activity) {
                /*
                 * 任何入口（更新门槛页/云控页/首页/工具页）第一次回到前台时，
                 * 把上一轮没看过的错误日志弹出来并自动复制 —— 不依赖具体哪个 Activity。
                 */
                if (!pickedUp) {
                    pickedUp = true;
                    com.mineways.AppLog.pickup(activity);
                }
                com.mineways.cloud.CloudCatalog.setUi(activity);
                /*
                 * 网页工具的稳定访问：把本进程 WebView 的流量接到本机代理（自带 HTTPDNS）。
                 * 放在每次 resume 上，是为了让云控的 net_proxy_off 能中途生效，不用重启 App。
                 */
                com.mineways.net.WebViewProxy.sync(activity);
            }

            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle saved) {
            }

            @Override
            public void onActivityStarted(android.app.Activity activity) {
                started++;
                if (started == 1) {
                    // 回到软件里：系统条收起，改用应用内那条
                    com.mineways.music.MusicOverlay.stop(MinewaysApp.this);
                }
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
                started = Math.max(0, started - 1);
                if (started == 0) {
                    overlayOnBackground();
                }
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle out) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {
            }
        });

        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // 0) 落盘：闪退后用户还能在应用里看到堆栈（剪贴板会被别的东西覆盖）
            writeCrashToFile(throwable, thread);

            // 1) 无论什么功能、什么线程出错，都复制到剪贴板
            copyErrorToClipboard(throwable);

            // 2) 通知用户错误已复制
            notifyErrorCopied(this);

            // 3) 尽量透传系统默认处理器，保留崩溃对话框与原有行为
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                // 兜底：兜不住也至少记入日志
                Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
                throwable.printStackTrace();
            }
        });
    }

    /** 闪退日志落盘位置（Blender 页有「查看上次崩溃」按钮读它）。 */
    public static java.io.File crashFile(Context c) {
        final java.io.File dir = new java.io.File(c.getFilesDir(), "blender");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new java.io.File(dir, "last_crash.txt");
    }

    /** 把异常堆栈写到私有目录（失败不抛）。 */
    private void writeCrashToFile(Throwable throwable, Thread thread) {
        try {
            Writer writer = new StringWriter();
            PrintWriter pw = new PrintWriter(writer);
            pw.println("时间：" + new java.util.Date());
            pw.println("线程：" + (thread == null ? "?" : thread.getName()));
            pw.println("版本：" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            pw.println();
            throwable.printStackTrace(pw);
            pw.flush();
            final byte[] bytes = writer.toString().getBytes("UTF-8");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(crashFile(this), false)) {
                out.write(bytes);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把异常的完整堆栈写入系统剪贴板。 */
    private void copyErrorToClipboard(Throwable throwable) {
        try {
            Writer writer = new StringWriter();
            PrintWriter pw = new PrintWriter(writer);
            throwable.printStackTrace(pw);
            pw.flush();
            String text = writer.toString();

            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text));
            }
        } catch (Throwable ignored) {
            // 剪贴板不可用时静默失败，不影响崩溃原始行为
        }
    }

    /** 在主线程上提示用户错误已复制到剪贴板。 */
    private void notifyErrorCopied(Context context) {
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "出错了，错误信息已自动复制到剪贴板", Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) {
            // Toast 失败不影响剪贴板复制
        }
    }
}