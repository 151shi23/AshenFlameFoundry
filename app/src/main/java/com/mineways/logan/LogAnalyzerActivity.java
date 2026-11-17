package com.mineways.logan;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.ember.logan.LogType;
import com.mineways.AppLog;
import com.mineways.ui.MwViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志诊断（本地功能）：选一个日志包或单个日志文件，引擎自动识别是谁的日志
 * （MC 启动器 / 游戏 / JVM 崩溃 / logcat / tombstone / Windows 事件·蓝屏·CBS·DXDiag /
 * Linux syslog·Xorg / macOS 崩溃 / Gradle），再输出中文诊断报告。
 * 全程离线，不上传任何日志内容。
 */
public class LogAnalyzerActivity extends Activity {

    private static final int REQ_PICK = 7801;
    /** 单条目上限引擎里已卡在 32MB，这里再卡整包上限，避免手机堆被大包打爆。 */
    private static final long MAX_INPUT_BYTES = 48L * 1024 * 1024;

    private TextView tvState;
    private TextView tvError;
    private TextView tvBrief;
    private TextView tvReport;
    private TextView btnPick;
    private TextView btnRun;
    private TextView btnCopy;
    private TextView btnShare;

    private File picked;
    private LogRun.Result last;
    private File saved;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        setState("还没选文件。支持 .zip 日志包，也支持直接选一个 .log / .txt。");
        android.net.Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(MwViews.pageBackground(this));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MwViews.dp(this, 18), MwViews.dp(this, 16),
                MwViews.dp(this, 18), MwViews.dp(this, 30));
        scroll.addView(root);

        root.addView(MwViews.header(this, "工具箱", "日志诊断", "本地诊断，不上传"));

        // 操作卡
        LinearLayout act = MwViews.card(this, 14);
        tvState = MwViews.body(this, "", MwViews.TEXT, 13f);
        act.addView(tvState);

        tvError = MwViews.body(this, "", MwViews.DANGER, 12f);
        tvError.setVisibility(View.GONE);
        act.addView(tvError);

        LinearLayout row1 = hrow();
        btnPick = MwViews.pillButton(this, "选择日志包 / 文件", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        btnRun = MwViews.pillButton(this, "开始分析", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAnalysis();
            }
        });
        btnRun.setEnabled(false);
        btnRun.setAlpha(0.4f);
        addButton(row1, btnPick);
        addButton(row1, btnRun);
        act.addView(row1);
        root.addView(act);

        // 速览卡
        LinearLayout briefCard = MwViews.card(this, 12);
        briefCard.addView(MwViews.section(this, "识别速览"));
        tvBrief = mono(this, 11.5f, MwViews.TEXT_2);
        tvBrief.setText("");
        briefCard.addView(tvBrief);
        root.addView(briefCard);

        // 报告卡
        LinearLayout reportCard = MwViews.card(this, 12);
        LinearLayout row2 = hrow();
        btnCopy = MwViews.pillButton(this, "复制报告", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReport();
            }
        });
        btnShare = MwViews.pillButton(this, "分享 / 导出 txt", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareReport();
            }
        });
        btnCopy.setEnabled(last != null);
        btnShare.setEnabled(last != null);
        btnCopy.setAlpha(last != null ? 1f : 0.4f);
        btnShare.setAlpha(last != null ? 1f : 0.4f);
        addButton(row2, btnCopy);
        addButton(row2, btnShare);
        reportCard.addView(row2);

        reportCard.addView(MwViews.section(this, "诊断报告"));
        HorizontalScrollView hx = new HorizontalScrollView(this);
        tvReport = mono(this, 11.5f, MwViews.TEXT);
        tvReport.setText("");
        // 报告按「中文=2 列」做的全角对齐，必须等宽字体，否则表格会漂
        hx.addView(tvReport, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reportCard.addView(hx);
        root.addView(reportCard);

        // 能认什么、边界在哪，交给实际分析结果；不再用一整张卡解释。

        return scroll;
    }

    private String capabilityNote() {
        String charset = charsetNote();
        return "· 一次最多读 48MB；包里单个文件超 32MB 会被跳过，行数超 6 万截断（防止手机内存被打爆）。\n"
                + "· .evtx 与 .dmp 只认魔数不做深度解析，报告会教你怎么导出成文本再喂回来。\n"
                + "· 认不准就标认不准："
                + "置信度低于 30% 会明写「未识别 / 低置信」，不会硬猜一个类型糊你。\n"
                + "· 中文 Windows 日志（GBK）自动转码；这台设备的字符集：" + charset + "。\n"
                + "· 嵌套压缩包只解一层；__MACOSX、.DS_Store、图片、jar、db 一律跳过。";
    }

    private String charsetNote() {
        try {
            Charset.forName("GB18030");
            return "支持 GB18030";
        } catch (Throwable t) {
            try {
                Charset.forName("GBK");
                return "只支持 GBK（GB18030 缺失，已自动降级）";
            } catch (Throwable ignored) {
                return "不支持中文扩展字符集（GBK 日志可能显示成乱码）";
            }
        }
    }

    private LinearLayout hrow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = MwViews.dp(this, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private static TextView mono(Activity a, float sizeSp, int color) {
        TextView t = new TextView(a);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0f, 1.05f);
        return t;
    }

    private void addButton(LinearLayout row, TextView button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = MwViews.dp(this, 8);
        button.setLayoutParams(lp);
        row.addView(button);
    }

    private void setState(String s) {
        tvState.setText(s);
    }

    private void setError(String s) {
        if (s == null || s.isEmpty()) {
            tvError.setVisibility(View.GONE);
            return;
        }
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(s);
    }

    // ------------------------------------------------------------------ 选文件

    private void pickFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("这台设备没有可用的文件选择器：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent dataIntent) {
        super.onActivityResult(requestCode, resultCode, dataIntent);
        if (requestCode != REQ_PICK) {
            return;
        }
        if (resultCode != RESULT_OK || dataIntent == null || dataIntent.getData() == null) {
            return;
        }
        acceptIncoming(dataIntent.getData());
    }

    /** 外部「打开方式」与页内选择器共用同一条载入路径。 */
    private void acceptIncoming(Uri uri) {
        String name = queryName(uri);
        setError(null);
        setState("正在把「" + name + "」读进临时目录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dst = materialize(uri, name);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            picked = dst;
                            last = null;
                            saved = null;
                            btnRun.setEnabled(true);
                            btnRun.setAlpha(1f);
                            btnCopy.setEnabled(false);
                            btnShare.setEnabled(false);
                            btnCopy.setAlpha(0.4f);
                            btnShare.setAlpha(0.4f);
                            tvBrief.setText("");
                            tvReport.setText("");
                            setState("已选择：" + dst.getName() + "　" + human(dst.length()));
                        }
                    });
                } catch (Throwable t) {
                    AppLog.e("logan", "读取所选文件失败：" + name, t);
                    final String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setState("读取失败，没拿到可用的文件。");
                            setError("读取失败：" + msg);
                        }
                    });
                }
            }
        }, "logan-copy").start();
    }

    /** 内容提供器只给流，引擎要按文件名认类型，所以落成带原名的大小写安全文件。 */
    private File materialize(Uri uri, String displayName) throws Exception {
        String safe = sanitize(displayName);
        long declared = querySize(uri);
        if (declared > MAX_INPUT_BYTES) {
            throw new IllegalStateException("文件 " + human(declared)
                    + " 超过 48MB 上限，请先只留崩溃段/最近一段再选");
        }
        File dir = new File(getCacheDir(), "logan");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("临时目录建不起来：" + dir.getAbsolutePath());
        }
        File dst = new File(dir, safe);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("打不开输入流");
        }
        long written = 0;
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    written += n;
                    if (written > MAX_INPUT_BYTES) {
                        throw new IllegalStateException("文件超过 48MB 上限（读到时已 "
                                + human(written) + "），请缩小范围再选");
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                close(out);
            }
        } finally {
            close(in);
        }
        return dst;
    }

    private String sanitize(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            n = "logs-" + System.currentTimeMillis() + ".zip";
        }
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        String out = sb.toString();
        return out.isEmpty() || out.replace("_", "").isEmpty()
                ? "logs-" + System.currentTimeMillis() + ".zip" : out;
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                closeQuietly(c);
            }
        }
        String p = uri.getLastPathSegment();
        return p == null || p.isEmpty() ? "logs.zip" : p;
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) {
                    return c.getLong(i);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                closeQuietly(c);
            }
        }
        return -1L;
    }

    // ------------------------------------------------------------------ 分析

    private void startAnalysis() {
        if (picked == null || running) {
            return;
        }
        running = true;
        setError(null);
        btnRun.setEnabled(false);
        btnRun.setAlpha(0.4f);
        btnPick.setEnabled(false);
        btnPick.setAlpha(0.4f);
        final File src = picked;
        setState("正在解包与识别 " + src.getName() + " …");
        tvReport.setText("（分析中…）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LogRun.Result r = LogRun.analyze(src, new LogRun.Progress() {
                        @Override
                        public void onFile(final String name, final int done, final int severity) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setState("已分析 " + done + " 个文件，正在看下一个：" + name);
                                }
                            });
                        }
                    });
                    finish(r);
                } catch (Throwable t) {
                    fail(t);
                }
            }
        }, "logan-analyze").start();
    }

    private void finish(final LogRun.Result r) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                running = false;
                last = r;
                btnRun.setEnabled(true);
                btnRun.setAlpha(1f);
                btnPick.setEnabled(true);
                btnPick.setAlpha(1f);
                btnCopy.setEnabled(true);
                btnShare.setEnabled(true);
                btnCopy.setAlpha(1f);
                btnShare.setAlpha(1f);
                setState("分析完成：" + r.brief());
                tvBrief.setText(briefText(r));
                tvReport.setText(r.report);
                AppLog.i("logan", "分析完成 " + r.items.size() + " 个文件 · " + r.brief());
                if (r.critical == 0 && r.warn == 0) {
                    setError(null);
                }
            }
        });
    }

    private void fail(Throwable t) {
        AppLog.e("logan", "日志分析失败", t);
        final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                running = false;
                btnRun.setEnabled(picked != null);
                btnRun.setAlpha(picked != null ? 1f : 0.4f);
                btnPick.setEnabled(true);
                btnPick.setAlpha(1f);
                tvReport.setText("");
                tvBrief.setText("");
                setState("分析没完成。");
                setError(msg);
            }
        });
    }

    private String briefText(LogRun.Result r) {
        if (r.items.isEmpty()) {
            return "（包里没找到可分析的日志文件）";
        }
        StringBuilder sb = new StringBuilder();
        for (LogRun.Item it : r.items) {
            sb.append(pad(cut(it.name, 30), 30)).append(" → ")
                    .append(pad(cut(it.typeZh, 18), 18)).append(" ")
                    .append(String.format(Locale.US, "%5.1f%%", it.confidence))
                    .append("  发现 ").append(it.findings);
            if (it.severityMax >= 3) sb.append("  ⚠严重");
            else if (it.severityMax == 2) sb.append("  警告");
            sb.append('\n');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** 速览按显示宽度补位（中文按 2 列），和报告的口径保持一致。 */
    private String pad(String s, int width) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) < 0x2E80 ? 1 : 2;
        }
        StringBuilder sb = new StringBuilder(s);
        while (w < width) {
            sb.append(' ');
            w++;
        }
        return sb.toString();
    }

    private String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    // ------------------------------------------------------------------ 报告出口

    private void copyReport() {
        if (last == null) {
            return;
        }
        if (AppLog.copy(this, last.report)) {
            toast("报告已复制到剪贴板");
        } else {
            toast("复制失败：系统剪贴板打不开");
        }
    }

    private void shareReport() {
        if (last == null) {
            return;
        }
        File f = saved != null && saved.exists() ? saved : writeReport();
        if (f == null) {
            setError("报告落盘失败：外部目录写不进去，可先点「复制报告」");
            return;
        }
        saved = f;
        setState("报告已存：" + f.getAbsolutePath() + "\n（也可以在文件管理器里直接取走）");
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "日志诊断报告")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享诊断报告"));
        } catch (Throwable t) {
            // 弹不出分享也算落盘成功，路径已经写在上面了
            toast("分享面板打不开，报告已存在：" + f.getName());
        }
    }

    private File writeReport() {
        File dir = getExternalFilesDir("logan");
        if (dir == null) {
            dir = new File(getFilesDir(), "logan");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "日志分析报告_" + stamp + ".txt");
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(last.report.getBytes("UTF-8"));
                out.flush();
            } finally {
                close(out);
            }
            return f.exists() ? f : null;
        } catch (Throwable t) {
            AppLog.e("logan", "报告落盘失败", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void close(OutputStream out) {
        try {
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Cursor c) {
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
