package com.mineimator.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎自检浮层：把<b>本应用自己</b>的 logcat（引擎用的是 MineImator 这个 tag）实时显示在屏幕上。
 * 用途：黑屏/异常时不用连电脑抓日志，点开就能看到引擎自己打的：
 * <pre>
 *   copied gm=… cwd=…        ← 资产解包结果
 *   files=… cache=… …        ← 启动参数与依赖自检
 *   GLES … / …               ← GL 版本
 *   editor ready %dx%d       ← 第一帧跑通
 *   primitive shader did not load  ← 着色器没加载（黑屏根因）
 * </pre>
 */
public final class EngineLogOverlay {

    /** 只抓我们自己关心的 tag：引擎日志、原生库报错、原生崩溃。 */
    private static final String[] CMD = {
            "logcat", "-d", "-v", "brief",
            "-s", "MineImator:V", "libmineimator:V", "DEBUG:V", "AndroidRuntime:E"
    };

    private static final int TAIL_LINES = 14;

    private final TextView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile boolean expanded = false;
    private boolean pollingStarted = false;
    private String lastText = "";

    public EngineLogOverlay(Activity activity) {
        view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        view.setTextColor(Color.parseColor("#B6FFD9"));
        view.setBackgroundColor(0xCC0A0A0A);
        view.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        view.setTextIsSelectable(true);
        view.setText("▸ Mine-imator 日志（点这里展开/收起）");
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded = !expanded;
                render(lastText);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        view.setLayoutParams(lp);
        // 默认不显示：收起状态也还是整条浮层，会挡住顶部的按钮。
        // 需要看引擎日志时从「Mine-imator 动画工作室」菜单打开。
        view.setVisibility(View.GONE);

        // 默认隐藏时不跑 logcat；真要排查时打开日志条再开始轮询。
        // logcat -d 会带上启动以来的缓冲，所以不会漏掉引擎早期日志。
    }

    private synchronized void ensurePolling() {
        if (pollingStarted) return;
        pollingStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, "mim-engine-log");
        t.setDaemon(true);
        t.start();
    }

    public View view() {
        return view;
    }

    public void stop() {
        running = false;
    }

    /** 供菜单调用：展开 / 收起日志。 */
    public void toggle() {
        expanded = !expanded;
        render(lastText);
    }

    public boolean isShowing() {
        return view.getVisibility() == View.VISIBLE;
    }

    /** 菜单入口：显示时直接展开到最新日志；再点一次隐藏，不占触控区域。 */
    public void toggleVisibility() {
        if (isShowing()) {
            view.setVisibility(View.GONE);
            return;
        }
        expanded = true;
        view.setVisibility(View.VISIBLE);
        ensurePolling();
        render(lastText);
    }

    private static int dp(Activity a, int value) {
        return (int) (value * a.getResources().getDisplayMetrics().density);
    }

    private void poll() {
        while (running) {
            final String text = readLog();
            if (!text.equals(lastText)) {
                lastText = text;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        render(text);
                    }
                });
            }
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String readLog() {
        Process p = null;
        try {
            p = new ProcessBuilder(CMD).redirectErrorStream(true).start();
            final List<String> lines = new ArrayList<>();
            final BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            br.close();
            p.waitFor();
            if (lines.isEmpty()) {
                return "（引擎还没打日志）";
            }
            final StringBuilder sb = new StringBuilder();
            final int from = Math.max(0, lines.size() - TAIL_LINES);
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i).trim()).append('\n');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            running = false;
            return "读取日志失败：" + t;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private void render(String text) {
        if (!expanded) {
            final String tail = text.isEmpty() ? "" : text.substring(text.lastIndexOf('\n') + 1);
            view.setText("▸ Mine-imator 日志（点开）  " + tail);
        } else {
            view.setText("▾ Mine-imator 日志（点收起）\n" + text);
        }
    }
}
