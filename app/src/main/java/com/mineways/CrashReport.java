package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 闪退取证：Java 层异常写到私有文件；原生崩溃读系统 crash 缓冲。
 * 工作室的 ☰ 菜单和「我的作品」页都有入口，用户点一下就能复制出来发给开发者 —— 不用连电脑。
 */
public final class CrashReport {

    private static final String FILE = "mim_last_crash.txt";

    private CrashReport() {
    }

    /** 在任何可能崩的界面之前调用：装上兜底处理器。 */
    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    final StringWriter sw = new StringWriter();
                    sw.write("时间: ");
                    sw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
                    sw.write("\n线程: ");
                    sw.write(thread.getName());
                    sw.write("\n");
                    error.printStackTrace(new PrintWriter(sw));
                    final FileOutputStream out = context.openFileOutput(FILE, Context.MODE_PRIVATE);
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                    // 取证失败也不能影响原有的崩溃流程
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    public static String read(Context context) {
        final StringBuilder sb = new StringBuilder();
        try {
            final BufferedReader br = new BufferedReader(
                    new InputStreamReader(context.openFileInput(FILE), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
        } catch (Throwable ignored) {
            // 没有记录 = 首次运行或没崩过
        }
        if (sb.length() == 0) {
            sb.append("（还没有记录到 Java 异常）\n");
        }
        sb.append("\n— 系统崩溃缓冲（原生崩溃 SIGSEGV / abort 会在这里）—\n");
        sb.append(crashBuffer());
        return sb.toString();
    }

    private static String crashBuffer() {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "brief")
                    .redirectErrorStream(true).start();
            final List<String> lines = new ArrayList<>();
            final BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            br.close();
            p.waitFor();
            if (lines.isEmpty()) {
                return "（无）\n";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + t + "\n";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    public static void show(final Activity activity) {
        final TextView tv = new TextView(activity);
        tv.setText(read(activity));
        tv.setTextSize(11f);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        tv.setTextIsSelectable(true);
        tv.setPadding(28, 24, 28, 24);
        final ScrollView sv = new ScrollView(activity);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(tv);
        new AlertDialog.Builder(activity)
                .setTitle("崩溃记录（长按可复制）")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }
}
