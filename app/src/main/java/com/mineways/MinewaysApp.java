package com.mineways;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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

    @Override
    public void onCreate() {
        super.onCreate();

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