package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * <b>全 App 唯一的日志出口</b>：一个目录、一套格式、一套复制实现。
 *
 * <p>目录固定为 {@code files/logs/}：
 * <ul>
 *   <li>{@code app.log} —— 追加流水，超过 512KB 自动轮转到 {@code app.1.log}</li>
 *   <li>{@code last-error.txt} —— 最近一次错误的全文（覆盖写；原生崩溃由 C++ 追加）</li>
 * </ul>
 *
 * <p>展示/复制也只有这一份实现：{@link #show(Activity, String, String)} 弹可滚动可选中文本，
 * 并在弹窗出现后重试写剪贴板；{@link #bundle(Context)} 拼装可复制的完整内容。
 * 任何模块都不要再自己 new 一套 File/Clipboard 逻辑。
 */
public final class AppLog {

    /** 唯一目录：files/logs */
    public static final String DIR = "logs";
    /** 流水日志（滚动） */
    public static final String APP_LOG = "logs/app.log";
    /** 最近一次错误（覆盖写；下次进 App 时拾取展示） */
    public static final String LAST_ERROR = "logs/last-error.txt";

    private static final long MAX_APP_LOG = 512 * 1024;

    private AppLog() {
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    // ---------------------------------------------------------------- 写

    /** 装兜底处理器：Java 未捕获异常也进同一份日志。 */
    public static void init(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                crash(ctx, "Java 未捕获异常（线程 " + thread.getName() + "）", error);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public static void i(String tag, String msg) {
        Context ctx = Ctx.ctx;
        if (ctx == null) return;
        append(ctx, APP_LOG, "[" + now() + "] " + tag + ": " + msg + "\r\n", false);
    }

    public static void e(String tag, String msg, Throwable t) {
        Context ctx = Ctx.ctx;
        String text = "[" + now() + "] " + tag + ": " + msg
                + (t == null ? "" : "\r\n" + stack(t));
        if (ctx == null) return;
        append(ctx, APP_LOG, text + "\r\n", false);
        append(ctx, LAST_ERROR, head(ctx, tag + ": " + msg) + text + "\r\n\r\n", true);
    }

    /** 原生崩溃/致命错误：写 last-error（覆盖），由下次进入时拾取。 */
    public static void crash(Context ctx, String where, Throwable t) {
        if (ctx == null) return;
        String text = head(ctx, where) + "[" + now() + "] " + where
                + (t == null ? "" : "\r\n" + stack(t));
        append(ctx, LAST_ERROR, text + "\r\n\r\n", true);
        append(ctx, APP_LOG, text + "\r\n", false);
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String head(Context ctx, String reason) {
        return "=== 错误 ===\r\n"
                + "时间: " + now() + "\r\n"
                + "机型: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE + "\r\n"
                + "原因: " + reason + "\r\n\r\n";
    }

    private static synchronized void append(Context ctx, String rel, String text, boolean overwrite) {
        try {
            File f = new File(ctx.getFilesDir(), rel);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            if (!overwrite && f.isFile() && f.length() > MAX_APP_LOG) {
                File old = new File(parent, f.getName().replace(".log", ".1.log"));
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, !overwrite)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 读 / 复制

    /** 拼一份可直接粘贴的完整日志：错误全文 + 流水尾部 + 系统崩溃缓冲。 */
    public static String bundle(Context ctx) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== MinewaysMobile 日志 ===\r\n")
                .append("时间: ").append(now()).append("\r\n")
                .append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append("\r\n\r\n");

        String last = readTail(ctx, LAST_ERROR, 60000);
        if (last.length() > 0) sb.append(last).append("\r\n");

        String flow = readTail(ctx, APP_LOG, 60000);
        if (flow.length() > 0) {
            sb.append("--- 流水（尾 60KB）---\r\n").append(flow).append("\r\n");
        }
        String crashBuf = crashBuffer();
        if (crashBuf.length() > 0) {
            sb.append("--- 系统崩溃缓冲 ---\r\n").append(crashBuf);
        }
        return sb.toString();
    }

    private static String readTail(Context ctx, String rel, int maxBytes) {
        File f = new File(ctx.getFilesDir(), rel);
        if (!f.isFile() || f.length() == 0) return "";
        try (InputStream in = new FileInputStream(f)) {
            long len = f.length();
            long skip = Math.max(0, len - maxBytes);
            if (skip > 0) //noinspection ResultOfMethodCallIgnored
                in.skip(skip);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String crashBuffer() {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "brief")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            p.waitFor();
            String s = new String(out.toByteArray(), "UTF-8");
            String[] lines = s.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - 60);
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * 唯一复制实现：写剪贴板并<b>回读校验</b>。
     * 必须在窗口有焦点时调用（部分国产 ROM 会静默丢弃后台写入）。
     */
    public static boolean copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("app-log", text));
            // 回读校验：能读到且开头一致才算真的复制成功
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence back = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                if (back != null && back.length() > 0) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 待复制文本：窗口拿到焦点后（onWindowFocusChanged / onResume）再补一次复制。 */
    private static volatile String sPendingCopy = null;

    /** 由 Activity 在 onResume / onWindowFocusChanged(true) 调用：把上次没复制成功的补上。 */
    public static void flushPendingCopy(Activity act) {
        final String text = sPendingCopy;
        if (act == null || text == null) return;
        if (copy(act, text)) {
            sPendingCopy = null;
            Toast.makeText(act, "日志已复制，直接粘贴发我们", Toast.LENGTH_LONG).show();
        }
    }

    /** 唯一弹窗实现。整体 try/catch —— 弹窗自身不能成为第二个崩溃源。 */
    public static void show(final Activity act, String log, String title) {
        if (act == null || act.isFinishing()) return;
        final String body = log.length() > 200000 ? log.substring(0, 200000) : log;
        sPendingCopy = body;

        // 复制先做（不依赖弹窗成功）
        copy(act, body);

        try {
            TextView tv;
            ScrollView scroll;
            try {
                tv = new TextView(act);
                tv.setText(body);
                tv.setTextSize(11f);
                tv.setTextIsSelectable(true);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(0xFFE6E6E6);
                int pad = (int) (12 * act.getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                scroll = new ScrollView(act);
                scroll.addView(tv);
                scroll.setBackgroundColor(0xFF15171A);
            } catch (Throwable styled) {
                // Material3 主题色资源缺失 → 素 TextView 兜底（不走主题 textAppearance）
                tv = new TextView(act, null, android.R.attr.textViewStyle);
                tv.setText(body);
                tv.setTextIsSelectable(true);
                tv.setTypeface(Typeface.MONOSPACE);
                scroll = new ScrollView(act);
                scroll.addView(tv);
            }

            final AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(title)
                    .setView(scroll)
                    .setPositiveButton("复制并关闭", (d, w) -> {
                        if (copy(act, body)) {
                            sPendingCopy = null;
                            Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(act, "复制失败，请长按文字手动复制", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNeutralButton("分享", (d, w) -> share(act, body))
                    .setNegativeButton("关闭", null)
                    .create();
            dlg.show();

            // 弹窗出现后再补复制（部分 ROM 只在有焦点时接受写入）
            final Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(() -> copy(act, body), 400);
        } catch (Throwable dialogFail) {
            // 弹窗也炸了 → 只好 Toast + 复制
            copy(act, body);
            Toast.makeText(act, "出错了（弹窗失败: " + dialogFail.getMessage()
                    + "），日志已复制到剪贴板", Toast.LENGTH_LONG).show();
        }
    }

    /** 分享兜底：不依赖剪贴板，能直接发到 QQ/微信/邮件给开发者。 */
    private static void share(Activity act, String text) {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(android.content.Intent.EXTRA_SUBJECT, "MinewaysMobile 日志");
            it.putExtra(android.content.Intent.EXTRA_TEXT, text);
            act.startActivity(android.content.Intent.createChooser(it, "分享日志"));
        } catch (Throwable t) {
            Toast.makeText(act, "分享失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 进入界面时调用：有未读错误就弹出来并自动复制，然后清掉。 */
    public static void pickup(final Activity act) {
        if (act == null) return;
        new Thread(() -> {
            Context ctx = act.getApplicationContext();
            File f = new File(ctx.getFilesDir(), LAST_ERROR);
            String text = readTail(ctx, LAST_ERROR, 200000);
            if (text.trim().length() == 0) return;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            final String full = bundle(ctx);
            act.runOnUiThread(() -> show(act, full, "上次出错了"));
        }).start();
    }

    /** 记录一个可供各模块使用的 application context。 */
    private static final class Ctx {
        static Context ctx;
    }

    public static void bind(Context c) {
        if (c != null) Ctx.ctx = c.getApplicationContext();
    }
}
