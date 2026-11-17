package com.mineways.astrbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mineways.AppLog;
import com.mineways.blender.RootShell;
import com.mineways.ui.MwViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * AstrBot 机器人管理台：二级导航（总览 / 连接 / 运维）。
 *
 * <p>总览负责状态与部署前环境自检；连接负责桥、QQ 扫码、WebUI 跳浏览器；
 * 运维放日志、诊断、重配与卸载。WebUI 本身不进 App，全部跳系统浏览器。</p>
 */
public class AstrBotActivity extends Activity {

    private static final int BG = 0xFF000000;
    private static final int CARD_TINT = 0x12FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int FAINT = 0x5CFFFFFF;
    private static final int ACCENT = 0xFFFFFFFF;
    private static final int OK = 0xFF57C785;
    private static final int WARN = 0xFFE0B44C;
    private static final int BAD = 0xFFFF6B6B;

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_CONNECT = 1;
    private static final int TAB_OPS = 2;

    private static final int REQ_QQ_DEB = 7601;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private int tab = TAB_OVERVIEW;
    private final TextView[] tabBtns = new TextView[3];
    private LinearLayout pageOverview, pageConnect, pageOps;

    private TextView tvState, tvStateNote, tvMaint, tvDeployReason, tvEnvSummary;
    private LinearLayout envBox;
    private TextView dotAb, dotWs, dotBridge, dotLink, dotQq;
    private TextView valAb, valWs, valBridge, valLink, valQq;
    private TextView btnDeploy, btnStart, btnStop, btnCheckEnv;

    private TextView btnBridgeNap, btnBridgeSnow;
    private TextView tvQq;
    private TextView btnSelectQq;
    private TextView tvAbCred, tvSnowCred;
    private LinearLayout snowCredRow;

    private TextView tvLog, tvPercent, tvOpsInfo;
    private TextView btnReapply, btnUninstall, btnDiag;

    private AstrBotPreflight.Result preflight;
    private boolean preflightRunning;
    private long lastOpsCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        refresh();
        runPreflight();
        ui.post(refreshTask);
        ui.post(logTask);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 界面骨架

    private View buildRoot() {
        ScrollView holder = new ScrollView(this);
        holder.setFillViewport(true);
        holder.setBackground(MwViews.pageBackground(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(26), dp(16), dp(28));

        TextView title = new TextView(this);
        title.setText("AstrBot 机器人");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        tvMaint = new TextView(this);
        tvMaint.setTextColor(WARN);
        tvMaint.setTextSize(12);
        tvMaint.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvMaint.setBackground(MwViews.round(this, 0x33E0B44C, 14));
        tvMaint.setVisibility(View.GONE);
        LinearLayout.LayoutParams maintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        maintLp.bottomMargin = dp(10);
        tvMaint.setLayoutParams(maintLp);
        root.addView(tvMaint);

        // 二级导航
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.bottomMargin = dp(12);
        tabs.setLayoutParams(tabsLp);
        tabBtns[TAB_OVERVIEW] = makeTab("总览", TAB_OVERVIEW);
        tabBtns[TAB_CONNECT] = makeTab("连接", TAB_CONNECT);
        tabBtns[TAB_OPS] = makeTab("运维", TAB_OPS);
        tabs.addView(tabBtns[TAB_OVERVIEW]);
        tabs.addView(tabBtns[TAB_CONNECT]);
        tabs.addView(tabBtns[TAB_OPS]);
        root.addView(tabs);

        pageOverview = new LinearLayout(this);
        pageOverview.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageOverview, block(0));
        buildOverview();

        pageConnect = new LinearLayout(this);
        pageConnect.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageConnect, block(0));
        buildConnect();

        pageOps = new LinearLayout(this);
        pageOps.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageOps, block(0));
        buildOps();

        applyTab();
        holder.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    private TextView makeTab(String label, int idx) {
        TextView t = MwViews.pillButton(this, label, false, v -> {
            tab = idx;
            applyTab();
            if (idx == TAB_OVERVIEW && preflight == null) runPreflight();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void applyTab() {
        for (int i = 0; i < tabBtns.length; i++) {
            if (tabBtns[i] == null) continue;
            final boolean on = i == tab;
            tabBtns[i].setTextColor(on ? 0xFF000000 : 0x99FFFFFF);
            tabBtns[i].setBackground(on
                    ? MwViews.accentFill(this, 100, true)
                    : MwViews.outline(this, 100, 0x1FFFFFFF));
        }
        if (pageOverview != null) pageOverview.setVisibility(tab == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
        if (pageConnect != null) pageConnect.setVisibility(tab == TAB_CONNECT ? View.VISIBLE : View.GONE);
        if (pageOps != null) pageOps.setVisibility(tab == TAB_OPS ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 总览

    private void buildOverview() {
        LinearLayout hero = card();
        tvState = new TextView(this);
        tvState.setText("检查中…");
        tvState.setTextColor(TEXT);
        tvState.setTextSize(22);
        tvState.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hero.addView(tvState);

        tvStateNote = new TextView(this);
        tvStateNote.setTextColor(DIM);
        tvStateNote.setTextSize(12);
        tvStateNote.setLineSpacing(dp(2), 1.3f);
        hero.addView(tvStateNote, block(6));

        valAb = statusRow(hero, dotAb = dot(), "AstrBot WebUI");
        valWs = statusRow(hero, dotWs = dot(), "反向 WS");
        valBridge = statusRow(hero, dotBridge = dot(), "QQ 桥");
        valLink = statusRow(hero, dotLink = dot(), "消息链路");
        valQq = statusRow(hero, dotQq = dot(), "Linux QQ");
        pageOverview.addView(hero);

        // 部署动作
        LinearLayout act = card();
        btnDeploy = MwViews.pillButton(this, "一键部署", true, v -> askDeploy());
        act.addView(btnDeploy, block(0));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = MwViews.pillButton(this, "启动", false,
                v -> AstrBotService.start(this, AstrBotService.ACTION_START));
        btnStop = MwViews.pillButton(this, "停止", false,
                v -> AstrBotService.start(this, AstrBotService.ACTION_STOP));
        LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half1.rightMargin = dp(6);
        btnStart.setLayoutParams(half1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnStop.setLayoutParams(half2);
        row.addView(btnStart);
        row.addView(btnStop);
        act.addView(row, block(10));

        tvDeployReason = new TextView(this);
        tvDeployReason.setTextColor(WARN);
        tvDeployReason.setTextSize(11);
        tvDeployReason.setLineSpacing(dp(2), 1.3f);
        act.addView(tvDeployReason, block(8));
        pageOverview.addView(act);

        // 环境自检
        LinearLayout env = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView envTitle = new TextView(this);
        envTitle.setText("部署环境自检");
        envTitle.setTextColor(TEXT);
        envTitle.setTextSize(14);
        envTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(envTitle);
        tvEnvSummary = new TextView(this);
        tvEnvSummary.setTextColor(DIM);
        tvEnvSummary.setTextSize(11);
        tvEnvSummary.setPadding(dp(8), 0, 0, 0);
        head.addView(tvEnvSummary);
        env.addView(head);

        envBox = new LinearLayout(this);
        envBox.setOrientation(LinearLayout.VERTICAL);
        env.addView(envBox, block(8));

        btnCheckEnv = MwViews.pillButton(this, "重新检测", false, v -> runPreflight());
        env.addView(btnCheckEnv, block(10));
        pageOverview.addView(env);
    }

    /** 环境自检：后台跑，逐项刷新；不通过时部署按钮禁用并给出唯一原因。 */
    private void runPreflight() {
        if (preflightRunning) return;
        preflightRunning = true;
        if (tvEnvSummary != null) tvEnvSummary.setText("检测中…");
        if (btnCheckEnv != null) btnCheckEnv.setEnabled(false);
        new Thread(() -> {
            final AstrBotPreflight.Result r = AstrBotPreflight.run(this);
            ui.post(() -> {
                preflight = r;
                preflightRunning = false;
                if (btnCheckEnv != null) btnCheckEnv.setEnabled(true);
                renderPreflight(r);
                refresh();
            });
        }, "astrbot-preflight").start();
    }

    private void renderPreflight(AstrBotPreflight.Result r) {
        if (envBox == null) return;
        envBox.removeAllViews();
        int fails = 0;
        for (AstrBotPreflight.Item item : r.items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            TextView d = dot();
            d.setTextColor(item.ok ? OK : (item.required ? BAD : WARN));
            row.addView(d);

            TextView label = new TextView(this);
            label.setText(item.label);
            label.setTextColor(item.required ? TEXT : DIM);
            label.setTextSize(12);
            label.setWidth(dp(96));
            row.addView(label);

            TextView detail = new TextView(this);
            detail.setText(item.detail);
            detail.setTextColor(item.ok ? DIM : (item.required ? BAD : WARN));
            detail.setTextSize(11);
            row.addView(detail);
            envBox.addView(row);
            if (!item.ok && item.required) fails++;
        }
        if (tvEnvSummary != null) {
            tvEnvSummary.setText(r.ok() ? "全部就绪" : ("有 " + fails + " 项不满足"));
            tvEnvSummary.setTextColor(r.ok() ? OK : WARN);
        }
    }

    // ---------------------------------------------------------------- 连接

    private void buildConnect() {
        LinearLayout bridge = card();
        TextView bt = new TextView(this);
        bt.setText("QQ 桥");
        bt.setTextColor(TEXT);
        bt.setTextSize(14);
        bt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bridge.addView(bt);
        TextView bh = new TextView(this);
        bh.setText("两条桥共用同一个 Linux QQ；切换后点「启动」生效。");
        bh.setTextColor(DIM);
        bh.setTextSize(11);
        bridge.addView(bh, block(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnBridgeNap = MwViews.pillButton(this, "NapCat", false, v -> {
            AstrBotEnv.setBridge(this, AstrBotEnv.BRIDGE_NAPCAT);
            refresh();
            toast("已选 NapCat；点「启动」生效");
        });
        btnBridgeSnow = MwViews.pillButton(this, "SnowLuma", false, v -> {
            AstrBotEnv.setBridge(this, AstrBotEnv.BRIDGE_SNOWLUMA);
            refresh();
            toast("已选 SnowLuma；点「启动」生效");
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(6);
        btnBridgeNap.setLayoutParams(b1);
        btnBridgeSnow.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btnBridgeNap);
        row.addView(btnBridgeSnow);
        bridge.addView(row, block(10));
        pageConnect.addView(bridge);

        LinearLayout qq = card();
        TextView qt = new TextView(this);
        qt.setText("Linux QQ");
        qt.setTextColor(TEXT);
        qt.setTextSize(14);
        qt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        qq.addView(qt);
        tvQq = new TextView(this);
        tvQq.setTextColor(DIM);
        tvQq.setTextSize(11);
        qq.addView(tvQq, block(4));
        btnSelectQq = MwViews.pillButton(this, "安装 QQ（导入 .deb）", false, v -> pickQqDeb());
        qq.addView(btnSelectQq, block(10));
        pageConnect.addView(qq);

        LinearLayout web = card();
        TextView wt = new TextView(this);
        wt.setText("浏览器打开");
        wt.setTextColor(TEXT);
        wt.setTextSize(14);
        wt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        web.addView(wt);
        TextView wh = new TextView(this);
        wh.setText("WebUI 一律交给系统浏览器；账号密码在下方卡片点一下复制。");
        wh.setTextColor(DIM);
        wh.setTextSize(11);
        web.addView(wh, block(4));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        TextView panel = MwViews.pillButton(this, "AstrBot 面板", true,
                v -> openBrowser("http://127.0.0.1:" + AstrBotEnv.portWebui(this) + "/"));
        TextView scan = MwViews.pillButton(this, "QQ 扫码登录", false, v -> openScan());
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w1.rightMargin = dp(6);
        panel.setLayoutParams(w1);
        scan.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(panel);
        r1.addView(scan);
        web.addView(r1, block(10));

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        TextView snowUi = MwViews.pillButton(this, "SnowLuma 面板", false,
                v -> openBrowser("http://127.0.0.1:" + AstrBotEnv.portSnowluma(this) + "/"));
        TextView noVnc = MwViews.pillButton(this, "noVNC 桌面", false,
                v -> openBrowser("http://127.0.0.1:" + AstrBotEnv.portNovnc(this)
                        + "/vnc.html?autoconnect=1&resize=scale"));
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w2.rightMargin = dp(6);
        snowUi.setLayoutParams(w2);
        noVnc.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(snowUi);
        r2.addView(noVnc);
        web.addView(r2, block(10));
        pageConnect.addView(web);

        LinearLayout cred = card();
        TextView ct = new TextView(this);
        ct.setText("面板账号（点一下复制）");
        ct.setTextColor(TEXT);
        ct.setTextSize(14);
        ct.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cred.addView(ct);
        tvAbCred = credRow(cred, "AstrBot", "首次启动后自动生成");
        tvAbCred.setOnClickListener(v -> copyCred(AstrBotEnv.astrbotUser(this), AstrBotEnv.astrbotPass(this)));
        snowCredRow = new LinearLayout(this);
        snowCredRow.setOrientation(LinearLayout.VERTICAL);
        tvSnowCred = credRow(snowCredRow, "SnowLuma", "—");
        tvSnowCred.setOnClickListener(v -> copyCred(AstrBotEnv.snowUser(this), AstrBotEnv.snowPass(this)));
        cred.addView(snowCredRow, block(2));
        pageConnect.addView(cred);
    }

    // ---------------------------------------------------------------- 运维

    private void buildOps() {
        LinearLayout info = card();
        TextView it = new TextView(this);
        it.setText("云控与运维");
        it.setTextColor(TEXT);
        it.setTextSize(14);
        it.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        info.addView(it);
        tvOpsInfo = new TextView(this);
        tvOpsInfo.setTextColor(DIM);
        tvOpsInfo.setTextSize(11);
        tvOpsInfo.setLineSpacing(dp(2), 1.4f);
        info.addView(tvOpsInfo, block(6));
        pageOps.addView(info);

        LinearLayout logs = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = new TextView(this);
        lt.setText("运行 / 部署日志");
        lt.setTextColor(TEXT);
        lt.setTextSize(14);
        lt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(lt);
        tvPercent = new TextView(this);
        tvPercent.setTextColor(FAINT);
        tvPercent.setTextSize(11);
        tvPercent.setPadding(dp(8), 0, 0, 0);
        head.addView(tvPercent);
        logs.addView(head);

        tvLog = new TextView(this);
        tvLog.setTextColor(0x99FFFFFF);
        tvLog.setTextSize(10);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(0, dp(8), 0, 0);
        logs.addView(tvLog);
        pageOps.addView(logs);

        LinearLayout act = card();
        btnReapply = MwViews.pillButton(this, "重新应用三端配置", false, v -> reapplyConfigs());
        act.addView(btnReapply, block(0));
        btnDiag = MwViews.pillButton(this, "诊断（可复制）", false, v -> showDiagnostics());
        act.addView(btnDiag, block(10));
        btnUninstall = MwViews.pillButton(this, "卸载 AstrBot", false, v -> askUninstall());
        act.addView(btnUninstall, block(10));
        pageOps.addView(act);
    }

    // ---------------------------------------------------------------- 状态刷新

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            maybeApplyOps();
            ui.postDelayed(this, 3000);
        }
    };

    private void refresh() {
        final AstrBotOrchestrator.Status s = AstrBotOrchestrator.status(this);
        final boolean open = AstrBotEnv.enabled(this);
        final boolean ready = RootShell.isReady();
        final boolean qq = AstrBotInstaller.isQqInstalled(this);

        if (tvState != null) {
            final boolean running = s.state == AstrBotOrchestrator.State.RUNNING;
            tvState.setText(running ? "运行中" : (open ? s.state.name() : "云控未开放"));
            tvState.setTextColor(running ? OK : (open ? TEXT : WARN));
            String note = s.note;
            if (!s.botUin.isEmpty()) note += " · bot QQ " + s.botUin;
            if (AstrBotEnv.noDeploy(this)) note += " · 云控暂停部署";
            if (AstrBotEnv.noStart(this)) note += " · 云控暂停启动";
            tvStateNote.setText(note);
        }

        final String maint = AstrBotEnv.maint(this);
        if (tvMaint != null) {
            tvMaint.setVisibility(maint.isEmpty() ? View.GONE : View.VISIBLE);
            if (!maint.isEmpty()) tvMaint.setText("维护公告：" + maint);
        }

        paintDot(dotAb, valAb, s.astrbotUp, s.astrbotUp ? "在线" : "未运行");
        paintDot(dotWs, valWs, s.wsUp, s.wsUp ? "监听中" : "未监听");
        paintDot(dotBridge, valBridge, s.bridgeUp,
                (AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(this)) ? "NapCat" : "SnowLuma")
                        + (s.bridgeUp ? " 在线" : " 未运行"));
        paintDot(dotLink, valLink, s.bridgeConnected,
                s.bridgeConnected ? "已连通" : (s.bridgeUp ? "等待桥连接" : "—"));
        paintDot(dotQq, valQq, qq, qq ? "已安装" : "未安装（需导入 arm64 .deb）");

        if (btnDeploy != null) {
            final boolean envOk = preflight == null || preflight.ok();
            btnDeploy.setEnabled(open && !AstrBotEnv.noDeploy(this) && envOk);
            String why = "";
            if (!open) why = "云控未开放";
            else if (AstrBotEnv.noDeploy(this)) why = "云控暂停部署（维护中）";
            else if (preflight == null) why = "正在检测部署环境…";
            else if (!preflight.ok()) why = preflight.reason();
            tvDeployReason.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
            tvDeployReason.setText(why);
            tvDeployReason.setTextColor(!open || AstrBotEnv.noDeploy(this) ? WARN : BAD);
        }
        if (btnStart != null) {
            btnStart.setEnabled(open && !AstrBotEnv.noStart(this) && ready && AstrBotEnv.deployed(this));
            btnStop.setEnabled(open && ready && AstrBotEnv.deployed(this));
            btnSelectQq.setEnabled(open);
            btnReapply.setEnabled(open && ready && AstrBotEnv.deployed(this));
            btnUninstall.setEnabled(open);
        }

        final boolean napSel = AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(this));
        if (btnBridgeNap != null) {
            btnBridgeNap.setTextColor(napSel ? 0xFF000000 : 0x99FFFFFF);
            btnBridgeNap.setBackground(napSel
                    ? MwViews.accentFill(this, 100, true)
                    : MwViews.outline(this, 100, 0x1FFFFFFF));
            btnBridgeSnow.setVisibility(AstrBotEnv.snowEnabled(this) ? View.VISIBLE : View.GONE);
            btnBridgeSnow.setTextColor(!napSel ? 0xFF000000 : 0x99FFFFFF);
            btnBridgeSnow.setBackground(!napSel
                    ? MwViews.accentFill(this, 100, true)
                    : MwViews.outline(this, 100, 0x1FFFFFFF));
        }
        if (tvQq != null) {
            tvQq.setText(qq ? "已安装" : "未安装（NapCat/SnowLuma 未附带，需导入 arm64 安装包）");
            tvQq.setTextColor(qq ? OK : WARN);
        }
        if (tvAbCred != null) {
            final String abPass = AstrBotEnv.astrbotPass(this);
            tvAbCred.setText(abPass.isEmpty() ? "首次启动后自动生成"
                    : AstrBotEnv.astrbotUser(this) + " / " + abPass + "   [点复制]");
            final String snowPass = AstrBotEnv.snowPass(this);
            snowCredRow.setVisibility(napSel ? View.GONE : View.VISIBLE);
            tvSnowCred.setText(snowPass.isEmpty() ? "首启后自动抓取 · [点复制]"
                    : AstrBotEnv.snowUser(this) + " / " + snowPass + "   [点复制]");
        }
        if (tvOpsInfo != null) {
            tvOpsInfo.setText("版本：" + AstrBotEnv.version(this)
                    + " · 开关：" + (open ? "开" : "关")
                    + " · 已部署：" + (AstrBotEnv.deployed(this) ? "是" : "否")
                    + (AstrBotEnv.installedVersion(this).isEmpty() ? "" : "（" + AstrBotEnv.installedVersion(this) + "）")
                    + "\n模板：" + (AstrBotEnv.seedEnabled(this) ? "开" : "关")
                    + " · 内置插件：" + (AstrBotEnv.pluginsEnabled(this) ? "开" : "关")
                    + " · 健康检查：" + (AstrBotEnv.healthEnabled(this) ? (AstrBotEnv.healthSec(this) + " 秒") : "关")
                    + "\n部署门槛：剩余 ≥ " + AstrBotEnv.minFreeMb(this) + " MB"
                    + " · " + (AstrBotEnv.requireArm64(this) ? "仅 arm64" : "不限架构"));
        }

        if (AstrBotService.deploying) {
            tvPercent.setText(AstrBotService.percent + "%");
            tvLog.setText(AstrBotService.status + "\n" + tail(AstrBotService.linesText(), 2500));
        }
    }

    private void paintDot(TextView dot, TextView value, boolean ok, String text) {
        if (dot == null || value == null) return;
        dot.setTextColor(ok ? OK : FAINT);
        value.setText(text);
        value.setTextColor(ok ? TEXT : DIM);
    }

    private final Runnable logTask = new Runnable() {
        @Override
        public void run() {
            if (!AstrBotService.deploying && tab == TAB_OPS && tvLog != null) {
                String ab = AstrBotOrchestrator.Logs.astrbotTail(AstrBotActivity.this, 4000);
                String br = AstrBotOrchestrator.Logs.bridgeTail(AstrBotActivity.this, 2000);
                String text = "--- AstrBot ---\n" + (ab.isEmpty() ? "(暂无)" : ab)
                        + "\n--- 桥 ---\n" + (br.isEmpty() ? "(暂无)" : br);
                tvLog.setText(tail(text, 4500));
                if (tvPercent.getText().length() == 0) tvPercent.setText("实时");
            }
            ui.postDelayed(this, 4000);
        }
    };

    /** 云端一次性运维指令（停止/重置/重启）：10 秒检查一次，nonce 不变不会重复执行。 */
    private void maybeApplyOps() {
        final long now = System.currentTimeMillis();
        if (now - lastOpsCheck < 10000) return;
        lastOpsCheck = now;
        if (!AstrBotEnv.enabled(this)) return;
        new Thread(() -> {
            final String done = AstrBotOps.applyEmergency(this);
            if (!done.isEmpty()) {
                ui.post(() -> Toast.makeText(this, done, Toast.LENGTH_LONG).show());
            }
        }, "astrbot-ops").start();
    }

    // ---------------------------------------------------------------- 动作

    private void askDeploy() {
        if (!AstrBotEnv.enabled(this)) {
            toast("云控未开放");
            return;
        }
        if (AstrBotEnv.noDeploy(this)) {
            toast("云控暂停部署（维护中）");
            return;
        }
        if (!RootShell.isReady()) {
            toast("先授权 Shizuku");
            RootShell.requestPermission();
            return;
        }
        if (preflight == null) {
            toast("环境检测中，请稍候");
            runPreflight();
            return;
        }
        if (!preflight.ok()) {
            new AlertDialog.Builder(this)
                    .setTitle("环境不满足")
                    .setMessage(preflight.reason() + "\n\n修好后点「重新检测」。")
                    .setPositiveButton("重新检测", (d, w) -> runPreflight())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        final boolean redeploy = AstrBotEnv.deployed(this);
        new AlertDialog.Builder(this)
                .setTitle(redeploy ? "重新部署" : "一键部署")
                .setMessage("将下载并配置：Ubuntu 24.04 容器 + AstrBot " + AstrBotEnv.version(this)
                        + " + Debian 桥容器 + NapCat/SnowLuma。\n"
                        + "总共约 1–2 GB，建议 Wi-Fi、保持前台。\n"
                        + "配置按你电脑本机模板生成（模型与人格除外）。\n"
                        + (redeploy ? "\n已部署过：会保留数据目录，重装运行时。\n" : ""))
                .setPositiveButton("开始", (d, w) -> {
                    AstrBotService.clearLines();
                    AstrBotService.start(this, AstrBotService.ACTION_DEPLOY);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("卸载 AstrBot")
                .setMessage("会停止所有进程并删除 /data/local/tmp/mineways_astrbot 下的容器、QQ 与配置。确定？")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    AstrBotInstaller.uninstall(this);
                    ui.post(() -> toast("已卸载"));
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickQqDeb() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_QQ_DEB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_QQ_DEB || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "astrbot");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File dst = new File(dir, "qq-arm64.deb");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dst)) {
                    if (in == null) throw new Exception("读不到文件");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                AstrBotEnv.setQqDebPath(this, dst.getAbsolutePath());
                ui.post(() -> toast("已选 QQ 安装包，开始安装…"));
                AstrBotInstaller.installPickedQq(this, new AstrBotInstaller.Progress() {
                    public void onStatus(String msg) {
                        ui.post(() -> {
                            if (tvPercent != null) tvPercent.setText(msg);
                        });
                    }

                    public void onPercent(int percent) {
                    }

                    public void onLine(String line) {
                        ui.post(() -> {
                            if (tvLog != null) tvLog.setText(line);
                        });
                    }
                });
                ui.post(() -> {
                    toast("QQ 安装完成");
                    refresh();
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, "导入失败：" + t, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void openScan() {
        if (AstrBotEnv.BRIDGE_NAPCAT.equals(AstrBotEnv.bridge(this))) {
            String token = getSharedPreferences("astrbot", MODE_PRIVATE).getString("napcat_token", "");
            openBrowser("http://127.0.0.1:" + AstrBotEnv.portNapcat(this) + "/webui"
                    + (token.isEmpty() ? "" : "?token=" + token));
        } else {
            openBrowser("http://127.0.0.1:" + AstrBotEnv.portSnowluma(this) + "/");
        }
    }

    private void openBrowser(String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            toast("没有可用浏览器：" + t);
        }
    }

    private void copyCred(String user, String pass) {
        if (pass == null || pass.isEmpty()) {
            toast("还没有账号密码（部署并启动后自动获取）");
            return;
        }
        String text = user + "\n" + pass;
        if (AppLog.copy(this, text)) {
            toast("已复制账号和密码");
        } else {
            toast("复制失败，长按文字手动复制");
        }
    }

    private void showLogs() {
        AppLog.show(this, AppLog.bundle(this), "日志记录");
    }

    private void showDiagnostics() {
        AppLog.show(this, AstrBotOps.diagnostics(this), "AstrBot 诊断");
    }

    private void reapplyConfigs() {
        if (!AstrBotInstaller.canReapply(this)) {
            toast("请先部署并授权 Shizuku");
            return;
        }
        new Thread(() -> {
            try {
                AstrBotInstaller.reapplyAll(this, new AstrBotInstaller.Progress() {
                    public void onStatus(String msg) {
                        ui.post(() -> {
                            if (tvPercent != null) tvPercent.setText(msg);
                        });
                    }

                    public void onPercent(int percent) {
                    }

                    public void onLine(String line) {
                        ui.post(() -> {
                            if (tvLog != null) tvLog.setText(line);
                        });
                    }
                });
                ui.post(() -> {
                    toast("配置已应用；重启桥后生效");
                    refresh();
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, "应用失败：" + t, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------------------------------------------------------------- 小工具

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(MwViews.glass(this, MwViews.style(this), 20, CARD_TINT, c));
        LinearLayout.LayoutParams lp = block(10);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView dot() {
        TextView t = new TextView(this);
        t.setText("●");
        t.setTextSize(10);
        t.setTextColor(FAINT);
        t.setPadding(0, 0, dp(6), 0);
        return t;
    }

    private TextView statusRow(LinearLayout parent, TextView dotValue, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(dotValue);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(11);
        l.setWidth(dp(110));
        row.addView(l);
        TextView v = new TextView(this);
        v.setText("—");
        v.setTextColor(DIM);
        v.setTextSize(11);
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private TextView credRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(11);
        l.setWidth(dp(80));
        row.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(TEXT);
        v.setTextSize(11);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
