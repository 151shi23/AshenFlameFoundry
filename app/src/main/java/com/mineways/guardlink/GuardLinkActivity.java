package com.mineways.guardlink;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.mineways.AppLog;
import com.mineways.ui.MwViews;
import com.zeus.guardlink.GuardLinkCore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 安全守护功能页。
 *
 * <p><b>一屏一件事，四段到底</b>：顶部 4 颗标签就是全部导航（状态 / 检测 / 处置 / 溯源日志），
 * 下拉抽屉只是同一份目录的手势版 —— 它展开成每段的卡片名，点一下滚过去。
 * 上一版这里同时摆了 6 颗标签 + 20 颗二级胶囊 + 两个抽屉 + 抽屉里的树，
 * 找一个开关要先猜该用哪套导航，被明确否掉了，所以那些全部拆掉，只留一条路径。
 *
 * <p><b>首屏固定回答三个问题</b>：现在什么状态（26sp 大字）、缺哪门授权（缺才提示，点一下跳系统页）、
 * 要不要立刻撤销（撤销断网常驻，真人按压才生效）。
 *
 * <p><b>参数</b>：常用四条（无人窗口 / 缓冲 / 确认窗 / 嫌疑门槛）排在「检测」最上面，
 * 其余 17 条同段往下排；全部看得见、全部改得动，不再被分组页切碎。
 * 不可逆动作一律走 {@link GuardLinkCore.HumanVerifier} 的真人按压鉴定。
 *
 * <p>页面自绘（玻璃内核 {@link MwViews} + 本包 {@link GuardSheet}），所以留在
 * {@code MwViews.restyle} 与 {@code MwChrome.inject} 的跳过名单里，不被遍历重写。
 */
public class GuardLinkActivity extends Activity implements GuardRuntime.UiSink {

    private static final long REFRESH_MS = 1000L;

    private static final String S_STATE = "state";
    private static final String S_CHECK = "check";
    private static final String S_ACT = "act";
    private static final String S_TRACE = "trace";

    /** 一级 = 段。 */
    private static final String[][] SECTIONS = {
            {S_STATE, "状态", "现在什么情形 · 启停 · 授权 · 再生 · 撤销"},
            {S_CHECK, "检测", "判定窗口与推举权重，全在这里改"},
            {S_ACT, "处置", "九项能力开关 · 运行节奏 · 不可逆动作"},
            {S_TRACE, "溯源", "嫌疑榜 · 豁免名单 · 事件流 · 证据包 · 能力边界"},
    };

    /** 二级 = 每段里的卡片（id|标题）。 */
    private static final String[][] CARDS = {
            {S_STATE, "now|现在", "audit|安装包体检", "auth|授权", "detail|状态明细",
                    "regen|再生与后台存活", "boot|跨重启"},
            {S_CHECK, "common|常用四条", "human|真人按压与退避", "weights|溯源权重"},
            {S_ACT, "switches|能力开关", "cadence|运行节奏", "irreversible|不可逆动作"},
            {S_TRACE, "verdict|推举榜", "target|对嫌疑应用动手", "trust|豁免名单", "events|事件流",
                    "evidence|证据包", "limits|能力边界"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, LinearLayout> cards = new LinkedHashMap<>();

    private ScrollView scroll;
    private LinearLayout host;
    private LinearLayout tabRow;
    private LinearLayout navTree;
    private GuardSheet.Drawer nav;
    private TextView tvStatus;
    private TextView tvSub;
    /** 「信号现场」那一行：触摸/窗口事件计数每秒就地更新，不重建整张卡。 */
    private TextView tvSignal;
    /** 「确认窗现在…」那一行：倒计时要一秒一跳，同样不重建首屏。 */
    private TextView tvConfirm;
    private String section = S_STATE;
    /** 「再生与后台存活」卡的内容指纹：没变就不重建，避免每秒刷一次导致的闪。 */
    private String regenSig = "";
    /** 最近任务隐藏的回挂钩子（{@link GuardRecents#watchForRestore}）， onDestroy 注销。 */
    private android.app.Application.ActivityLifecycleCallbacks recentsHook;
    /** 「再生与后台存活」卡要显示的那句实话（隐藏/挂回各一次结果）。 */
    private String recentsNote = "";
    /** 最近一次安装包体检的结果（换段重建时还要显示）。 */
    private GuardApkAudit.Report lastAudit;
    private GuardApkAudit.Meta lastAuditMeta;
    private String lastAuditName = "";
    private TextView tvAudit;
    private boolean auditRunning;
    private static final int REQ_AUDIT = 9001;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            if (S_TRACE.equals(section)) {
                renderEvents();
            }
            if (S_STATE.equals(section)) {
                // 自愈的尝试次数/成功次数是活的，常驻服务起来没有也要看得见
                renderRegen();
                if (tvConfirm != null) {
                    tvConfirm.setText("确认窗现在：" + GuardRuntime.confirmState());
                }
            }
            if (S_CHECK.equals(section) && tvSignal != null) {
                tvSignal.setText("信号现场：" + GuardRuntime.signalReport());
            }
            main.postDelayed(this, REFRESH_MS);
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GuardRuntime.ensureChannel(this);
        GuardRuntime.attach(this, this);
        setContentView(buildUi());
        selectSection(S_STATE, null);
        // 最近任务隐藏：本页是栈顶时把这条卡片摘掉，回到 App 内别的页面再挂回去
        syncRecents();
        recentsHook = GuardRecents.watchForRestore(this);
        android.net.Uri open = com.mineways.open.OpenInbox.take(this);
        if (open != null) {
            acceptIncoming(open);
        }
    }

    @Override
    protected void onDestroy() {
        GuardRecents.unwatch(this, recentsHook);
        recentsHook = null;
        GuardRuntime.detach(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardRuntime.attach(this, this);
        // 用户回到本页 = 已知悉前面的触发，静默退避该退出（内核 exitSilentMode 的口径）
        GuardRuntime.userReturned(this);
        main.postDelayed(refresher, 300L);
        selectSection(section, null);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresher);
        GuardRuntime.detach(this);
        super.onPause();
    }

    @Override
    public void onGuardLog(String line) {
        renderStatus();
    }

    @Override
    public void onGuardVerdict(GuardLinkCore.SuspectEngine.Verdict v) {
        renderVerdict();
        renderTargets();
        renderStatus();
    }

    @Override
    public void onGuardHealth(int flags) {
        renderAuth();
        renderStatus();
    }

    @Override
    public void onBackPressed() {
        if (nav != null && nav.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 骨架

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MwViews.BG);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(MwViews.pageBackground(this));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(MwViews.dp(this, 18), MwViews.dp(this, 10),
                MwViews.dp(this, 18), MwViews.dp(this, 36));
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(MwViews.header(this, "工具箱", "安全守护", null));

        col.addView(drawerButton());
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trl.topMargin = MwViews.dp(this, 10);
        col.addView(tabRow, trl);
        buildTabRow();

        host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = MwViews.dp(this, 4);
        col.addView(host, hlp);

        View scrim = GuardSheet.scrim(this);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int navSpan = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
        LinearLayout panel = GuardSheet.panel(this, 22);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navSpan);
        nlp.gravity = Gravity.TOP;
        panel.addView(GuardSheet.head(this, "目录", null));
        View grab = GuardSheet.handle(this, "收起");
        panel.addView(grab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView navScroll = new ScrollView(this);
        navTree = new LinearLayout(this);
        navTree.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(navTree, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel, nlp);
        nav = new GuardSheet.Drawer(this, true, panel, grab, scrim, navSpan);
        return root;
    }

    /** 抽屉入口做成一颗看得见的按钮 —— 上一版只有一根 4dp 小横杠，等于没有入口。 */
    private View drawerButton() {
        LinearLayout card = MwViews.card(this, 10);
        card.addView(GuardSheet.pillWide(this, "☰ 目录", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nav.open();
            }
        }));
        return card;
    }

    private void buildTabRow() {
        tabRow.removeAllViews();
        for (final String[] s : SECTIONS) {
            final String sid = s[0];
            TextView t = new TextView(this);
            t.setText(s[1]);
            t.setTextSize(16f);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, MwViews.dp(this, 13), 0, MwViews.dp(this, 13));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSection(sid, null);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (tabRow.getChildCount() > 0) {
                lp.leftMargin = MwViews.dp(this, 6);
            }
            tabRow.addView(t, lp);
        }
        applyTabs();
    }

    private void applyTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView t = (TextView) tabRow.getChildAt(i);
            boolean on = SECTIONS[i][0].equals(section);
            t.setTextColor(on ? 0xFF000000 : MwViews.TEXT_2);
            t.setBackground(on ? MwViews.accentFill(this, 8, true)
                    : MwViews.outline(this, 8, MwViews.HAIRLINE));
        }
    }

    // ------------------------------------------------------------------ 导航树（抽屉）

    private void buildNavTree() {
        navTree.removeAllViews();
        for (String[] s : SECTIONS) {
            final String sid = s[0];
            boolean open = sid.equals(section);
            navTree.addView(GuardSheet.navGroup(this, s[1], null, open,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            section = sid;
                            selectSection(sid, null);
                            nav.close();
                        }
                    }));
            if (!open) {
                continue;
            }
            for (String card : cardsOf(sid)) {
                String[] kv = card.split("\\|", 2);
                final String cid = kv[0];
                navTree.addView(GuardSheet.navLeaf(this, kv[1], false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectSection(sid, cid);
                        nav.close();
                    }
                }));
            }
        }
    }

    private static String[] cardsOf(String sid) {
        for (String[] c : CARDS) {
            if (c[0].equals(sid)) {
                String[] out = new String[c.length - 1];
                System.arraycopy(c, 1, out, 0, out.length);
                return out;
            }
        }
        return new String[0];
    }

    private static String cardTitle(String sid, String cid) {
        for (String card : cardsOf(sid)) {
            String[] kv = card.split("\\|", 2);
            if (kv[0].equals(cid)) {
                return kv[1];
            }
        }
        return cid;
    }

    /** 换段 = 重建那一段的卡片；cardId 非空则滚到那张卡。 */
    private void selectSection(String sid, String cardId) {
        section = sid;
        host.removeAllViews();
        cards.clear();
        for (String card : cardsOf(sid)) {
            String cid = card.split("\\|", 2)[0];
            LinearLayout box = MwViews.card(this, 12);
            box.addView(GuardSheet.head(this, cardTitle(sid, cid), null));
            host.addView(box);
            cards.put(sid + "/" + cid, box);
        }
        applyTabs();
        buildNavTree();
        renderSection(sid);
        renderStatus();
        if (cardId != null) {
            scrollToCard(sid + "/" + cardId);
        } else {
            scroll.smoothScrollTo(0, 0);
        }
    }

    private void scrollToCard(final String key) {
        final View target = cards.get(key);
        if (target == null) {
            return;
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                int top = 0;
                View p = target;
                while (p != null && p != scroll) {
                    top += p.getTop();
                    p = (View) p.getParent();
                }
                scroll.smoothScrollTo(0, Math.max(0,
                        top - MwViews.dp(GuardLinkActivity.this, 10)));
            }
        });
    }

    // ------------------------------------------------------------------ 各段渲染

    private void renderSection(String sid) {
        if (S_STATE.equals(sid)) {
            renderNow();
            renderAudit();
            renderAuth();
            renderDetail();
            renderRegen();
            renderBoot();
        } else if (S_CHECK.equals(sid)) {
            renderCommon();
            renderHuman();
            renderWeights();
        } else if (S_ACT.equals(sid)) {
            renderSwitches();
            renderCadence();
            renderIrreversible();
        } else {
            renderVerdict();
            renderTargets();
            renderTrust();
            renderEvents();
            renderEvidence();
            renderLimits();
        }
    }

    private void renderStatus() {
        if (tvStatus == null) {
            return;
        }
        GuardLinkCore.GuardController c = GuardRuntime.controller();
        tvStatus.setText(GuardRuntime.statusLine());
        tvStatus.setTextColor(c == null ? MwViews.TEXT_2
                : (c.lockdown.isActive() || c.confirmedCrack ? MwViews.DANGER : MwViews.TEXT));
        tvSub.setText(GuardFlags.brief(this));
    }

    /** 首屏那张卡：大字状态 + 启停 + 自检 + 撤销断网（真人按压）。 */
    private void renderNow() {
        LinearLayout card = cards.get(S_STATE + "/now");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "现在", null));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(GuardSheet.T_STATE);
        tvStatus.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvStatus.setTextColor(MwViews.TEXT);
        card.addView(tvStatus);

        tvSub = GuardSheet.body(this, "", MwViews.TEXT_2, GuardSheet.T_BODY);
        tvSub.setPadding(0, MwViews.dp(this, 8), 0, 0);
        card.addView(tvSub);

        GuardLinkCore.GuardController c = GuardRuntime.controller();
        boolean cut = c != null && (c.confirmedCrack || c.networkCut
                || GuardRuntime.appNetCutPkg() != null);
        LinearLayout r1 = GuardSheet.pills(this);
        if (c == null) {
            r1.addView(GuardSheet.pill(this, "启用守护", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGuard();
                }
            }));
        } else {
            r1.addView(GuardSheet.pill(this, "停用守护", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopGuard();
                }
            }));
            r1.addView(GuardSheet.pill(this, "按误报解除", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissAsFalseAlarm();
                }
            }));
        }
        r1.addView(GuardSheet.pill(this, "立即自检", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selfCheck();
            }
        }));
        card.addView(r1);

        if (cut) {
            card.addView(mkDivider());
            String pkg = GuardRuntime.appNetCutPkg();
            card.addView(GuardSheet.body(this, pkg == null
                    ? "网络已被本守护全局切断（svc wifi/data）。" + GuardNetState.describe(this)
                    : "已只切断 " + pkg + " 的联网（iptables 按 uid），其余应用不受影响。",
                    MwViews.WARN, 14f));
            TextView undo = MwViews.pillButton(this, "按住不放：撤销断网", true, null);
            undo.setMinHeight(MwViews.dp(this, 52));
            card.addView(undo, matchWidth(this, 12));
            realPress(undo, new Runnable() {
                @Override
                public void run() {
                    String why = GuardRuntime.cancelCrack(GuardLinkActivity.this);
                    toast(why == null ? "已撤销断网，网络与无障碍都恢复原样" : why);
                    selectSection(S_STATE, null);
                }
            });
            card.addView(GuardSheet.body(this,
                    "这颗按钮认真人的手：无障碍模拟点击没有触摸时序、远程注入是同帧瞬时点，两种都点不动，"
                            + "被拒也不消耗任何东西，你再按一次就行。", MwViews.TEXT_2, GuardSheet.T_SUB));
        }
        if (c != null && c.lockdown.isActive()) {
            card.addView(mkDivider());
            tvConfirm = GuardSheet.body(this, "确认窗现在：" + GuardRuntime.confirmState(),
                    MwViews.WARN, GuardSheet.T_SUB);
            card.addView(tvConfirm);
            card.addView(GuardSheet.body(this,
                    "现在处于 Lockdown 保护：隐私应用被踢回、无人操作下的装/卸窗口被拦、陌生前台包冻结、"
                            + "不可信无障碍暂停。确认过是自己人（例如你正照提示到设置里卸载那个应用）"
                            + "就按住下面这颗解除 —— 不用关掉整个守护，也就不会留下没人值守的那段。"
                            + "不管你有没有回来：桌面没回前台也会就地起倒计时，到点没人真人按压就按"
                            + "「确认恶意」处置（先只掐嫌疑应用自己的联网）。",
                    MwViews.TEXT_2, GuardSheet.T_SUB));
            card.addView(GuardSheet.pillWide(this, "弹出确认窗（是误报 / 确认恶意）", true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String why = GuardRuntime.showConfirmWindow();
                            if (why != null) {
                                toast(why);
                            }
                        }
                    }));
            TextView rel = MwViews.pillButton(this, "按住不放：解除保护（不动网络）", true, null);
            rel.setMinHeight(MwViews.dp(this, 52));
            card.addView(rel, matchWidth(this, 12));
            realPress(rel, new Runnable() {
                @Override
                public void run() {
                    String why = GuardRuntime.releaseLockdown(GuardLinkActivity.this);
                    toast(why == null ? "保护已解除，检测照常值守" : why);
                    selectSection(S_STATE, null);
                }
            });
        }
        renderStatus();
    }

    /**
     * 安装包体检：装之前先看结构与权限面。
     *
     * <p>只看、不执行：解压目录 + 包管理器元数据，规则全在本地跑，断网也能用，样本文件不外传。
     */
    private void renderAudit() {
        LinearLayout card = cards.get(S_STATE + "/audit");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "安装包体检",
                "装之前先看结构与权限面；只读压缩包，不执行任何代码"));
        tvAudit = GuardSheet.body(this, auditLine(), auditColor(lastAudit), GuardSheet.T_SUB);
        card.addView(tvAudit);

        LinearLayout row = GuardSheet.pills(this);
        row.addView(GuardSheet.pill(this, "选择 APK 体检", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        }));
        if (lastAudit != null) {
            row.addView(GuardSheet.pill(this, "查看最近结论", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAuditDialog(lastAudit, lastAuditMeta);
                }
            }));
        }
        card.addView(row);
        card.addView(GuardSheet.body(this,
                "检查项：内置 scrcpy / SPAKE2 远控组件、隐藏 DEX 载荷、超大清单、"
                        + "危险权限组合、签名证书、无障碍/通知监听/VPN/设备管理员声明。"
                        + "规则是本地启发式，未命中不代表绝对安全 —— 它负责在装之前把明显不该装的挡在门外。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
    }

    private String auditLine() {
        if (auditRunning) {
            return "正在体检…";
        }
        if (lastAudit == null) {
            return "尚未体检。选择要安装的 APK 文件开始。";
        }
        return lastAudit.headline() + "（风险分 " + lastAudit.score + "/100）"
                + (lastAuditName.isEmpty() ? "" : " · " + lastAuditName);
    }

    private int auditColor(GuardApkAudit.Report r) {
        if (r == null) {
            return MwViews.TEXT_2;
        }
        switch (r.level) {
            case DANGEROUS:
                return MwViews.DANGER;
            case SUSPICIOUS:
                return MwViews.WARN;
            default:
                return MwViews.TEXT;
        }
    }

    private void pickApk() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.android.package-archive",
                    "application/octet-stream", "application/zip"});
            startActivityForResult(i, REQ_AUDIT);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                startActivityForResult(i, REQ_AUDIT);
            } catch (Throwable t2) {
                toast("没有可用的文件选择器");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_AUDIT || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        acceptIncoming(data.getData());
    }

    /** 外部「打开方式」：apk 直接进安装包审计。 */
    private void acceptIncoming(final android.net.Uri uri) {
        String seg = uri.getLastPathSegment();
        lastAuditName = seg == null ? "" : seg;
        auditRunning = true;
        if (tvAudit != null) {
            tvAudit.setText(auditLine());
            tvAudit.setTextColor(MwViews.TEXT_2);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final GuardApkAudit.Meta m = GuardApkReader.read(getApplicationContext(), uri);
                // 走当前生效特征集：云端可以对内置项改分/改文案/停用，也可以新增静态特征
                final GuardApkAudit.Report r = GuardApkAudit.audit(m, GuardCloudFeatures.load());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        auditRunning = false;
                        lastAudit = r;
                        lastAuditMeta = m;
                        if (isFinishing()) {
                            return;
                        }
                        if (tvAudit != null) {
                            tvAudit.setText(auditLine());
                            tvAudit.setTextColor(auditColor(r));
                        }
                        showAuditDialog(r, m);
                    }
                });
            }
        }).start();
    }

    private void showAuditDialog(final GuardApkAudit.Report r, final GuardApkAudit.Meta m) {
        if (r == null) {
            return;
        }
        final String text = r.report(m);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(r.headline());
        b.setMessage(text);
        b.setPositiveButton("知道了", null);
        b.setNeutralButton("复制结论", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                copyText(text, "安装包体检结论");
            }
        });
        b.show();
    }

    /** 授权：缺哪门、点哪颗跳过去。 */
    private void renderAuth() {
        LinearLayout card = cards.get(S_STATE + "/auth");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "授权", null));

        card.addView(GuardSheet.statusRow(this, dot(GuardProbe.accessibilityEnabled(this)),
                "① 无障碍 · 触摸与窗口事件的唯一来源",
                GuardProbe.accessibilityEnabled(this) ? "已启用" : "未启用"));
        card.addView(GuardSheet.statusRow(this, dot(GuardShell.ready()),
                "② Shizuku · 断网 / 冻结 / 取证",
                GuardShell.ready() ? "已授权" : (GuardShell.installed() ? "装了未授权" : "未安装")));
        card.addView(GuardSheet.statusRow(this, dot(GuardProbe.usageStatsGranted(this)),
                "③ 使用情况访问 · 归属与媒体豁免",
                GuardProbe.usageStatsGranted(this) ? "可用" : "不可用"));

        LinearLayout r1 = GuardSheet.pills(this);
        r1.addView(GuardSheet.pill(this, "开无障碍", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        }));
        r1.addView(GuardSheet.pill(this, "授权 Shizuku", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askShizuku();
            }
        }));
        r1.addView(GuardSheet.pill(this, "授权使用情况", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grantUsageStats();
            }
        }));
        card.addView(r1);
        card.addView(GuardSheet.body(this,
                "三样都没有也能跑：检测与报警照做，只是不动网络、不冻结进程、归属判不准。"
                        + "厂商 ROM 还要在自启动与省电里放行本应用，否则后台会被掐掉。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
    }

    private void renderDetail() {
        LinearLayout card = cards.get(S_STATE + "/detail");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "状态明细", null));
        card.addView(monotv(GuardRuntime.statusDetail(this)));
        card.addView(GuardSheet.pillWide(this, "复制明细", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(GuardRuntime.statusDetail(GuardLinkActivity.this), "状态明细");
            }
        }));
    }

    /**
     * 再生与后台存活：进程活得住、被划掉能自己回来、无障碍掉了有人补。
     *
     * <p>这四行全部是<b>现场读出来的真相</b>，不是开关的期望值：常驻服务没起来就写「没在跑」，
     * 自愈被 ROM 拦住就把那次失败原因贴出来。每秒刷一次，但内容没变就不重建卡片（免得闪）。
     */
    private void renderRegen() {
        LinearLayout card = cards.get(S_STATE + "/regen");
        if (card == null) {
            return;
        }
        boolean svc = GuardService.running();
        boolean regen = GuardFlags.regenOn(this);
        boolean hide = GuardFlags.hideTaskOn(this);
        boolean a11y = GuardProbe.accessibilityEnabled(this);
        String heal = GuardRuntime.healState();
        String sig = svc + "|" + regen + "|" + hide + "|" + a11y + "|" + heal + "|" + recentsNote;
        if (sig.equals(regenSig) && card.getChildCount() > 0) {
            return;
        }
        regenSig = sig;
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "再生与后台存活",
                "被划掉后台 / 被省电掐死 / 无障碍被关掉之后，守护自己回来的能力"));

        card.addView(GuardSheet.statusRow(this, dot(svc), "① 常驻服务 · 进程活得住",
                svc ? "在跑（最低优先级通知，不响）"
                        : (regen ? "没在跑（起不来或还没起）" : "已被关（guard_regen=0）")));
        card.addView(GuardSheet.statusRow(this, dot(a11y), "② 无障碍 · 自愈的目标",
                a11y ? "在" : "掉了，正在定时补"));
        card.addView(GuardSheet.statusRow(this, dot(!GuardRuntime.healStuck()),
                "③ 自愈记录（试 " + GuardRuntime.healTries()
                        + " 次 / 成 " + GuardRuntime.healSuccesses() + " 次）", heal));
        card.addView(GuardSheet.statusRow(this, dot(hide), "④ 最近任务里隐藏守护页",
                hide ? "开 · 守护页那张卡片划不到" : "关 · 卡片照旧能被划掉"));
        if (recentsNote.length() > 0) {
            card.addView(GuardSheet.body(this, "隐藏动作的结果：" + recentsNote,
                    MwViews.INFO, GuardSheet.T_SUB));
        }
        if (!GuardShell.ready() && !a11y) {
            card.addView(GuardSheet.body(this,
                    "Shizuku 没在跑，自愈写不回无障碍 —— 这一条只能靠手动「开无障碍」或先把 Shizuku 起起来。",
                    MwViews.WARN, GuardSheet.T_SUB));
        }

        card.addView(GuardSheet.pillWide(this, "立刻试一次自愈", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                healNow();
            }
        }));
        card.addView(GuardSheet.pillWide(this, "自启动设置（华米 OV）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOem(false);
            }
        }));
        card.addView(GuardSheet.pillWide(this, "省电 / 后台限制设置", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOem(true);
            }
        }));

        card.addView(GuardSheet.body(this,
                "为什么需要这三样：守护原来只有页面 + 无障碍服务，没有常驻进程 —— 卡片被一划，"
                        + "检测、保护、通知全停；而原来的自愈钩子写在无障碍服务销毁回调里，"
                        + "进程被划掉时那条回调根本不跑，所以「Shizuku 权限给了也不会自己拉起来」。"
                        + "现在常驻服务每 2 秒查一次（无障碍补写自己节流 15 秒），任务被划掉立刻自拉，"
                        + "隐藏卡片让划不到。厂商 ROM 的自启动与省电还得你亲手放行，"
                        + "上面两颗按钮逐个试探各家页面，打开哪一个就说哪一个。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
    }

    /** 不等节流，立刻试一次把无障碍写回去，并把结果当场说给他听。 */
    private void healNow() {
        if (GuardProbe.accessibilityEnabled(this)) {
            toast("无障碍一直在，没有要补的。自愈记录：" + GuardRuntime.healState());
            regenSig = "";
            renderRegen();
            return;
        }
        GuardRuntime.selfHealNow(this);
        String s = GuardRuntime.healState();
        toast(s);
        GuardRuntime.appendLog("👤 手动触发一次自愈：" + s);
        regenSig = "";
        renderRegen();
        renderAuth();
    }

    /**
     * 逐个试探厂商的自启动/省电页：打开哪个就说哪个，全打不开就如实说全打不开。
     *
     * <p>各家 ROM 这些页面的组件名不保证存在，版本一变就换。所以不硬指定第一个，
     * 而是按 {@link GuardOem} 的候选顺序试，异常就退到下一档。
     */
    private void openOem(final boolean battery) {
        GuardOem.Page hit = GuardOem.probe(battery ? GuardOem.BATTERY : GuardOem.AUTOSTART,
                new GuardOem.Tester() {
                    @Override
                    public boolean tryStart(GuardOem.Page p) {
                        try {
                            Intent it = new Intent();
                            if (p.action != null && p.action.length() > 0) {
                                it.setAction(p.action);
                                if (GuardOem.ACTION_APP_DETAILS.equals(p.action)) {
                                    it.setData(android.net.Uri.fromParts("package",
                                            getPackageName(), null));
                                }
                            } else {
                                it.setComponent(new android.content.ComponentName(p.pkg, p.cls));
                            }
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it);
                            return true;
                        } catch (Throwable t) {
                            return false;
                        }
                    }
                });
        String what = battery ? "省电/后台限制" : "自启动";
        if (hit == null) {
            String msg = "试过 " + GuardOem.labels(battery ? GuardOem.BATTERY : GuardOem.AUTOSTART).size()
                    + " 个" + what + "页面，一个都没能打开。请到「设置 → 应用 → 本应用」里手动放行自启动与后台。";
            GuardRuntime.appendLog("⚠ " + msg);
            toast(msg);
            return;
        }
        String msg = "已请求打开：" + hit.describe();
        GuardRuntime.appendLog("🔗 " + msg);
        toast(msg);
    }

    /** 按开关把守护页那张最近任务卡片摘掉/挂回，结果留一句在卡上。 */
    private void syncRecents() {
        recentsNote = GuardRecents.applyTo(this, GuardFlags.hideTaskOn(this));
        regenSig = "";
        renderRegen();
    }

    private void renderBoot() {
        LinearLayout card = cards.get(S_STATE + "/boot");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "跨重启", null));
        card.addView(GuardSheet.body(this, GuardState.describe(this), MwViews.TEXT, GuardSheet.T_BODY));
        List<String> cut = GuardA11y.suspendedList(this);
        if (!cut.isEmpty()) {
            StringBuilder sb = new StringBuilder("无障碍防火墙正暂停 " + cut.size() + " 个服务：\n");
            for (String s : cut) {
                sb.append("· ").append(s).append('\n');
            }
            sb.append("解除 Lockdown / 卸载完成 / 关掉防火墙开关时原样还原。");
            card.addView(GuardSheet.body(this, sb.toString(), MwViews.WARN, GuardSheet.T_SUB));
        }
        card.addView(GuardSheet.body(this,
                "重启本身就让 svc 设置失效、iptables 规则随内核清空。醒来自动再断一次会把"
                        + "「重启就能上网」这条逃生路堵死，误判时代价太大，所以这里只续保护与监测。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
    }

    // ------------------------------------------------------------ 检测参数

    private void renderCommon() {
        LinearLayout card = cards.get(S_CHECK + "/common");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "常用四条",
                "改这四条就够调「灵敏度 vs 误报」；出厂值取自内核，界面写的就是判定用的"));
        for (String id : new String[]{"idle_ms", "pending_ms", "confirm_ms", "score_min"}) {
            card.addView(paramRow(id));
        }
        card.addView(mkDivider());
        card.addView(GuardSheet.pillWide(this, "恢复出厂参数", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreFactory();
            }
        }));
        List<String> diff = GuardParams.localDiff(this);
        if (!diff.isEmpty()) {
            card.addView(GuardSheet.body(this, "本机改过：" + TextUtils.join("、", diff),
                    MwViews.INFO, GuardSheet.T_SUB));
        }
        // 「为什么这次没报 / 为什么刚报了」的现场，就摆在调灵敏度的地方下面
        tvSignal = GuardSheet.body(this, "信号现场：" + GuardRuntime.signalReport(),
                MwViews.TEXT_2, GuardSheet.T_SUB);
        card.addView(tvSignal);
        card.addView(GuardSheet.body(this,
                "缓冲期到点不再自动定罪：只有那 2.5 秒里界面「还跟着又动了」才算持续操控。"
                        + "点开一个 App、启动页自己跳主页而你没再碰屏幕 —— 这种一次性的自翻页现在直接撤防，"
                        + "不报也不处置（上一版就是在这里必炸，见 PROJECT_NOTES §8.3.153）。"
                        + "要回到「动一次就报」，把「实锤次数」拨到 1。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
    }

    private void renderHuman() {
        LinearLayout card = cards.get(S_CHECK + "/human");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "真人按压与退避",
                "这两条是反注入的底线；实锤次数与退避阈值决定「弹几次就不打扰你」"));
        for (String id : new String[]{"press_ms", "age_ms", "burst", "silent_after"}) {
            card.addView(paramRow(id));
        }
    }

    private void renderWeights() {
        LinearLayout card = cards.get(S_CHECK + "/weights");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "溯源权重",
                "四步筛查每项证据值多少分；累计到上面「嫌疑门槛」才被推举为主要嫌疑"));
        for (GuardParams.Item it : GuardParams.inGroup(GuardParams.GROUP_SCORE.id)) {
            if ("score_min".equals(it.id)) {
                continue;   // 门槛在「常用四条」里，别在两个地方显示同一个数
            }
            card.addView(paramRow(it.id));
        }
    }

    private View paramRow(String id) {
        final GuardParams.Item it = GuardParams.find(id);
        return GuardSheet.paramRow(this, it, new GuardSheet.ValueChanger() {
            @Override
            public void onChange(long v) {
                String why = GuardParams.setLocal(GuardLinkActivity.this, it.id, v);
                if (why != null) {
                    toast(why);
                } else {
                    GuardRuntime.appendLog("🎚 " + it.title + " → " + v + it.unit
                            + "（出厂 " + it.factory + "）");
                }
                renderSection(section);
                renderStatus();
            }
        });
    }

    // ------------------------------------------------------------ 处置能力

    private void renderSwitches() {
        LinearLayout card = cards.get(S_ACT + "/switches");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "能力开关",
                "优先级：云控下发 > 本机设置 > 出厂默认；每条都标着现在听谁的"));
        for (final GuardFlags.Switch s : GuardFlags.switches()) {
            card.addView(GuardSheet.switchRow(this, s.key, s.title, s.note,
                    GuardFlags.flag(this, s.key, s.def),
                    GuardFlags.cloudLocked(this, s.key),
                    GuardFlags.source(this, s.key, s.def),
                    new GuardSheet.ValueChanger() {
                        @Override
                        public void onChange(long v) {
                            String why = GuardFlags.setLocal(GuardLinkActivity.this, s.key, v != 0);
                            if (why != null) {
                                toast(why);
                                renderSwitches();   // 被拒就把显示拉回真相
                                return;
                            }
                            GuardRuntime.appendLog("🔧 " + s.title + " → "
                                    + (v != 0 ? "开" : "关") + "（" + s.key + "="
                                    + (v != 0 ? 1 : 0) + "）");
                            if (GuardFlags.REGEN.equals(s.key)) {
                                // 开关一拨就要真的起/停服务，不能只是记个数
                                if (v != 0) {
                                    GuardService.start(GuardLinkActivity.this,
                                            GuardService.ACTION_START);
                                } else {
                                    GuardService.start(GuardLinkActivity.this,
                                            GuardService.ACTION_STOP);
                                }
                            }
                            if (GuardFlags.HIDE_TASK.equals(s.key)) {
                                syncRecents();
                            }
                            renderStatus();
                            renderIrreversible();
                        }
                    }));
        }
    }

    private void renderCadence() {
        LinearLayout card = cards.get(S_ACT + "/cadence");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "运行节奏",
                "这两条不住在内核里，住在本 App 的存储 + 云控，用的是同一种滑杆"));
        card.addView(cadenceRow("guard_audit_sec", "网络对账周期", 15, 5, 300, 5,
                GuardFlags.auditSeconds(this),
                "断网保护期间每隔多久查一次「网络是不是被恶意恢复了」，查出来就再断并报警。"));
        card.addView(cadenceRow("guard_state_sec", "状态落盘周期", 30, 10, 600, 5,
                GuardFlags.stateSeconds(this),
                "每隔多久把 Lockdown / 静默模式 / 累计触发数 / 卸载目标写进磁盘，供开机后接回。"));
    }

    private View cadenceRow(final String key, String title, final int def,
                            final int min, final int max, int step, long cur, String note) {
        return GuardSheet.cadenceRow(this, title, " 秒", note, min, max, step, cur, def,
                GuardFlags.cloudLockedNum(this, key), key, new GuardSheet.ValueChanger() {
                    @Override
                    public void onChange(long v) {
                        String why = GuardFlags.setLocalNum(GuardLinkActivity.this, key,
                                (int) v, min, max);
                        if (why != null) {
                            toast(why);
                        } else {
                            GuardRuntime.appendLog("⏱ " + title + " → " + v + " 秒（出厂 " + def + "）");
                        }
                        renderCadence();
                    }
                });
    }

    private void renderIrreversible() {
        LinearLayout card = cards.get(S_ACT + "/irreversible");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "不可逆动作", null));
        card.addView(GuardSheet.body(this,
                "· 精确断网：iptables 按 uid 只掐嫌疑应用，其余应用无感；不支持或被关掉才退 svc 全局断网。\n"
                        + "· 冻结：am force-stop，进程一死，借道微信 / 后台外传的调用链一起断。\n"
                        + "· 卸载：pm uninstall --user 0（停用当前用户，不刷系统分区），出厂默认关，"
                        + "开着也要真人按压 + 弹窗二次确认。\n"
                        + "· 无障碍防火墙：保护窗口内静默暂停不可信服务，解除即原样还原。",
                MwViews.TEXT, GuardSheet.T_BODY));
        LinearLayout row = GuardSheet.pills(this);
        row.addView(GuardSheet.pill(this, "恢复网络", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestoreNetwork();
            }
        }));
        row.addView(GuardSheet.pill(this, "还原无障碍", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(GuardA11y.restore(GuardLinkActivity.this));
                selectSection(S_STATE, null);
            }
        }));
        card.addView(row);
    }

    // ------------------------------------------------------------ 溯源与日志

    private void renderVerdict() {
        LinearLayout card = cards.get(S_TRACE + "/verdict");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "四步加权推举榜",
                "Step1 权限面 → Step2 无障碍 → Step3 流量 → Step4 行为归属"));
        GuardLinkCore.SuspectEngine.Verdict v = GuardRuntime.verdict();
        if (v == null || v.ranked.isEmpty()) {
            card.addView(GuardSheet.body(this, "还没触发过判定。触发一次后这里列嫌疑榜与证据链。",
                    MwViews.TEXT_2, GuardSheet.T_BODY));
        } else {
            card.addView(monotv(verdictText(v)));
        }
        LinearLayout row = GuardSheet.pills(this);
        row.addView(GuardSheet.pill(this, "复制嫌疑榜", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(textOf(cards.get(S_TRACE + "/verdict")), "嫌疑榜");
            }
        }));
        row.addView(GuardSheet.pill(this, "重新推举", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reInvestigate();
            }
        }));
        card.addView(row);
    }

    private String verdictText(GuardLinkCore.SuspectEngine.Verdict v) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (GuardLinkCore.SuspectEngine.Suspect s : v.ranked) {
            if (s.score <= 0 && shown >= 6) {
                continue;
            }
            sb.append(GuardText.normalize(s.report()));
            shown++;
            if (shown >= 12) {
                sb.append("…（其余 ").append(v.ranked.size() - shown).append(" 项 0 分未列）\n");
                break;
            }
        }
        sb.append(v.primary == null
                ? "\n主要嫌疑：证据不足，未推举（门槛 " + GuardParams.find("score_min").current() + " 分）"
                : "\n主要嫌疑：" + v.primary.profile.pkg);
        return sb.toString();
    }

    private void renderTargets() {
        LinearLayout card = cards.get(S_TRACE + "/target");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "对嫌疑应用动手",
                "每个动作都单独过闸门，被拒时下面会写明为什么"));
        GuardLinkCore.SuspectEngine.Verdict v = GuardRuntime.verdict();
        List<GuardLinkCore.SuspectEngine.Suspect> top = new ArrayList<>();
        if (v != null) {
            for (GuardLinkCore.SuspectEngine.Suspect s : v.ranked) {
                if (s.score > 0 && top.size() < 5) {
                    top.add(s);
                }
            }
        }
        if (top.isEmpty()) {
            card.addView(GuardSheet.body(this, "榜上还没有有分数的应用。",
                    MwViews.TEXT_2, GuardSheet.T_BODY));
            return;
        }
        for (final GuardLinkCore.SuspectEngine.Suspect s : top) {
            final String pkg = s.profile.pkg;
            LinearLayout one = new LinearLayout(this);
            one.setOrientation(LinearLayout.VERTICAL);
            one.setPadding(0, MwViews.dp(this, 12), 0, 0);
            one.addView(GuardSheet.body(this, pkg + " · " + s.score + " 分"
                    + (s.isPrimary() ? " · 主要嫌疑" : ""), MwViews.TEXT, 14.5f));
            LinearLayout r1 = GuardSheet.pills(this);
            r1.addView(GuardSheet.pill(this, "冻结", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toast(GuardShell.forceStop(GuardLinkActivity.this, pkg).detail);
                }
            }));
            r1.addView(GuardSheet.pill(this, "精确断网", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    GuardShell.Result r = GuardShell.cutAppNet(GuardLinkActivity.this, pkg);
                    toast(r.detail);
                    if (r.ok) {
                        GuardRuntime.noteAppNetCut(pkg);
                    }
                    renderStatus();
                }
            }));
            one.addView(r1);
            LinearLayout r2 = GuardSheet.pills(this);
            r2.addView(GuardSheet.pill(this, "恢复联网", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toast(GuardShell.restoreAppNet(GuardLinkActivity.this, pkg).detail);
                    GuardRuntime.noteAppNetCut(null);
                    renderStatus();
                }
            }));
            r2.addView(GuardSheet.pill(this, "卸载", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmUninstall(pkg);
                }
            }));
            one.addView(r2);
            card.addView(one);
        }
    }

    private void renderEvents() {
        LinearLayout card = cards.get(S_TRACE + "/events");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "事件流", null));
        List<String> logs = GuardRuntime.snapshotLogs();
        if (logs.isEmpty()) {
            card.addView(GuardSheet.body(this, "（暂无事件）", MwViews.TEXT_2, GuardSheet.T_BODY));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, logs.size() - 40); i < logs.size(); i++) {
                sb.append(logs.get(i)).append('\n');
            }
            card.addView(monotv(sb.toString()));
        }
        card.addView(GuardSheet.pillWide(this, "复制事件流", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (String s : GuardRuntime.snapshotLogs()) {
                    sb.append(s).append('\n');
                }
                copyText(sb.toString(), "事件流");
            }
        }));
    }

    private void renderEvidence() {
        LinearLayout card = cards.get(S_TRACE + "/evidence");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "证据包", null));
        File root = GuardProfiles.evidenceRoot(this);
        File[] kids = root.listFiles();
        StringBuilder sb = new StringBuilder("目录：" + root.getAbsolutePath() + "\n");
        if (kids == null || kids.length == 0) {
            sb.append("（还没触发过取证）");
        } else {
            List<File> dirs = new ArrayList<>();
            for (File f : kids) {
                if (f.isDirectory()) {
                    dirs.add(f);
                }
            }
            sb.append("共 ").append(dirs.size()).append(" 份，最近 5 份：\n");
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            for (int i = dirs.size() - 1, n = 0; i >= 0 && n < 5; i--, n++) {
                sb.append("· ").append(dirs.get(i).getName()).append("  ")
                        .append(f.format(new Date(dirs.get(i).lastModified()))).append('\n');
            }
        }
        card.addView(monotv(sb.toString()));
        card.addView(GuardSheet.body(this,
                "里面四份：meta.txt（判定原因与活动包）、net_table.txt（/proc/net/tcp 连接表）、"
                        + "usage_events.txt（前台切换记录）、traffic_snapshot.txt（按 uid 流量差分）。"
                        + "它不是 pcap —— 真报文要 root 的 tcpdump，Shizuku 的 shell 身份没有 CAP_NET_RAW。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
        card.addView(GuardSheet.pillWide(this, "复制目录路径", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(GuardProfiles.evidenceRoot(GuardLinkActivity.this).getAbsolutePath(),
                        "证据目录");
            }
        }));
    }

    private void renderLimits() {
        LinearLayout card = cards.get(S_TRACE + "/limits");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "能力边界", null));
        card.addView(GuardSheet.body(this,
                "时序：命中 → Lockdown（隐私应用踢回 / 装卸拦截 / 陌生包冻结 / 不可信无障碍暂停）"
                        + "→ 取证推举 → 弹「是否误报」"
                        + GuardText.duration(GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS)
                        + "（这条现在可调）→ 真人点误报就全部解除、网络不动；"
                        + "点确认恶意或到点没人理，才先只掐嫌疑应用的联网。\n"
                        + "会误报：长时间无人操作后应用自己弹界面（下载完成、推送打开页面）就可能命中，"
                        + "所以留了人工确认窗与连 " + GuardLinkCore.GuardController.SILENT_AFTER_FIRES
                        + " 次退避。\n"
                        + "做不到：拦不住系统层 IPC 调度本身（只能让源头进程先死），出不了真 pcap 报文，"
                        + "不能替你判断这次到底是不是误报。Android 13/14 收窄包变更广播，"
                        + "复活监测可能收不到，查不到我就说查不到。\n"
                        + "内核：GuardLink v3.2 原样 vendored（13 场景 96 条断言），"
                        + "本 App 只把 21 个参数常量改成可覆盖字段，出厂值未动。",
                MwViews.TEXT_2, GuardSheet.T_BODY));
        card.addView(GuardSheet.pillWide(this, "查安装来源", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askInstaller();
            }
        }));
    }

    /**
     * 豁免名单：谁不该被当成嫌疑人。
     *
     * <p>三档强度各占一行（数字现算，不写死），下面四个动作各自对应一条改名单的路。
     */
    private void renderTrust() {
        LinearLayout card = cards.get(S_TRACE + "/trust");
        if (card == null) {
            return;
        }
        card.removeAllViews();
        card.addView(GuardSheet.head(this, "豁免名单",
                "常见 App 与游戏不该被当成嫌疑人；三档强度，云控 PKG 行能加能撤"));

        card.addView(GuardSheet.statusRow(this, MwViews.OK,
                "① 不算证据 · 输入法、系统界面这类本来就自己会动的",
                countOf(GuardTrust.Level.EVIDENCE) + " 条"));
        card.addView(GuardSheet.statusRow(this, MwViews.INFO,
                "② 不进推举榜 · 大厂主力 App，照报警但不追它",
                countOf(GuardTrust.Level.RANK) + " 条"));
        card.addView(GuardSheet.statusRow(this, MwViews.TEXT_2,
                "③ 不许动手 · 游戏与常见 App，可上榜但禁止断网/冻结/卸载",
                countOf(GuardTrust.Level.IRREVERSIBLE) + " 条"));

        card.addView(GuardSheet.body(this, GuardTrust.summary(this),
                MwViews.TEXT_2, GuardSheet.T_BODY));
        card.addView(GuardSheet.statusRow(this, MwViews.INFO,
                "系统应用（FLAG_SYSTEM）：不建档、不当嫌疑人，也绝不对它动手",
                "按事实判，不靠名单"));
        card.addView(GuardSheet.body(this,
                "系统应用这一层不写在名单里，因为它靠的是系统给的 FLAG_SYSTEM 标志 —— "
                        + "包名能伪装成 com.huawei.xxx，这个标志伪装不了，所以「厂商组件」不用逐条列，也列不全。\n"
                        + "它挡住的是动作，不是报警：系统组件自己弹界面是常态，但真有人远程操控时界面照样会动、"
                        + "照样报警，只是不会把系统组件写成嫌疑人，更不会去冻结它 —— 冻系统设置会让机器看起来像砖了。",
                MwViews.TEXT_2, GuardSheet.T_SUB));
        card.addView(GuardSheet.body(this,
                "优先级：云控 PKG add/drop ＞ 本机临时豁免 ＞ 包内内置清单（逐条带出处）。"
                        + "前缀按目录语义匹配：写 com.tencent. 放行 com.tencent.mm，"
                        + "但不放行 com.tencentx.evil —— 仿冒知名前缀正是这条路最容易漏的地方。\n"
                        + "内置那份读不到时会退回内核自带的大厂前缀表，不会因为没有名单就变成「微信被推举成主要嫌疑」。",
                MwViews.TEXT_2, GuardSheet.T_SUB));

        LinearLayout row = GuardSheet.pills(this);
        row.addView(GuardSheet.pill(this, "查一个包名", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trustLookup(false);
            }
        }));
        row.addView(GuardSheet.pill(this, "本机加一条", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trustAdd();
            }
        }));
        card.addView(row);

        LinearLayout row2 = GuardSheet.pills(this);
        row2.addView(GuardSheet.pill(this, "撤本机那条", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trustLookup(true);
            }
        }));
        row2.addView(GuardSheet.pill(this, "复制名单", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(GuardTrust.dump(), "豁免名单");
            }
        }));
        card.addView(row2);
    }

    private static int countOf(GuardTrust.Level lv) {
        int n = 0;
        for (GuardTrust.Entry e : GuardTrust.entries()) {
            if (e.level == lv) {
                n++;
            }
        }
        return n;
    }

    /** 查一条包名现在算第几档、听谁的；{@code remove} 为真时改成撤掉本机那条。 */
    private void trustLookup(final boolean remove) {
        final android.widget.EditText input = pkgInput(remove ? "要撤掉的本机包名" : "要查的包名，如 com.tencent.mm");
        new AlertDialog.Builder(this)
                .setTitle(remove ? "撤掉本机豁免" : "查这条包名")
                .setView(input)
                .setPositiveButton(remove ? "撤" : "查", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        String pkg = String.valueOf(input.getText()).trim();
                        if (remove) {
                            toast(GuardTrust.removeLocal(GuardLinkActivity.this, pkg));
                        } else {
                            showTrustHit(pkg);
                        }
                        renderTrust();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTrustHit(String pkg) {
        GuardTrust.Entry e = GuardTrust.entryOf(pkg);
        String msg;
        if (e == null) {
            msg = pkg + " 不在名单上。\n\n"
                    + "它会被建档打分，分数累计到门槛就可能被推举成「主要嫌疑」，"
                    + "那时精确断网与卸载都对它开放。若这是误报，用「本机加一条」先挡下来，"
                    + "再等云控 PKG 正式收录。";
        } else {
            msg = e.name + "\n" + e.pkg + "\n\n现在算：" + e.level.cn()
                    + "\n来源：" + e.source + (e.from.length() == 0 ? "" : "（" + e.from + "）");
        }
        new AlertDialog.Builder(this)
                .setTitle("命中结果")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 本机加一条：包名 → 选档。线上误报不用等发版。 */
    private void trustAdd() {
        final android.widget.EditText input = pkgInput("包名，如 com.xxx.game");
        new AlertDialog.Builder(this)
                .setTitle("本机豁免一条")
                .setMessage("先选档位，再确认包名。这条只在这台手机上生效，"
                        + "云控或内置清单里有同一条时听云控的。")
                .setView(input)
                .setPositiveButton("下一步", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        pickLevel(String.valueOf(input.getText()).trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickLevel(final String pkg) {
        String[] names = {"不许动手（照报警、可上榜，但禁止断网/冻结/卸载）",
                "不进榜（照报警，但它进不了推举榜）",
                "不算证据（它自己的界面异动完全不进判定）"};
        final GuardTrust.Level[] lv = {GuardTrust.Level.IRREVERSIBLE,
                GuardTrust.Level.RANK, GuardTrust.Level.EVIDENCE};
        new AlertDialog.Builder(this)
                .setTitle(pkg + " 算哪一档")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        String why = GuardTrust.addLocal(GuardLinkActivity.this, pkg,
                                String.valueOf(lv[i]));
                        if (why != null) {
                            toast(why);
                        } else {
                            GuardRuntime.appendLog("🛡 本机豁免 " + pkg + " → " + lv[i].cn());
                        }
                        renderTrust();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.widget.EditText pkgInput(String hint) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        MwViews.styleInput(input, MwViews.style(this));
        return input;
    }

    // ------------------------------------------------------------------ 动作

    private void startGuard() {
        String why = GuardRuntime.enable(this);
        if (why != null) {
            toast(why);
            GuardRuntime.appendLog("⛔ 未能启用守护：" + why);
        } else {
            prefs(true);
        }
        selectSection(S_STATE, null);
    }

    /**
     * 停用守护。
     *
     * <p>顺序是这里的重点：<b>先把「用户要停用」落盘，再去做清理</b>。
     * 以前是先 disable 再写 prefs，清理里任何一步抛异常都会让这条意愿没存住 ——
     * 表现就是他要的：明明关了守护，下次进来（或下次开机）它又自己起来了。
     */
    private void stopGuard() {
        new AlertDialog.Builder(this)
                .setTitle("停用守护？")
                .setMessage("停用后不再检测也不再处置；网络与被暂停的无障碍如果是本功能动过的，会一并恢复。"
                        + "停用会记住：下次进本页、下次开机都不会自己重新开启，要开就点「启用守护」。")
                .setPositiveButton("停用", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        prefs(false);
                        GuardRuntime.disable(GuardLinkActivity.this);
                        selectSection(S_STATE, null);
                    }
                })
                .setNegativeButton("继续值守", null)
                .show();
    }

    private void dismissAsFalseAlarm() {
        final GuardLinkCore.GuardController c = GuardRuntime.controller();
        if (c == null || !c.lockdown.isActive()) {
            toast("当前没有生效中的 Lockdown，无需解除");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认这是误报？")
                .setMessage("解除后隐私应用不再被踢回、装拦截与陌生包冻结停止、被暂停的无障碍原样还原。"
                        + "网络不会被改动。")
                .setPositiveButton("是误报，解除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        c.humanConfirmDismiss(System.currentTimeMillis());
                        // 复位 + 重新计时都在这一步（内核停在 FIRED 就再也不判定；
                        // 而 reset 不动触摸基准，卸完/按完立刻又被自己的界面异动绊一次）
                        GuardRuntime.settle("按误报解除");
                        selectSection(S_STATE, null);
                    }
                })
                .setNegativeButton("先不动", null)
                .show();
    }

    private void confirmRestoreNetwork() {
        GuardLinkCore.GuardController c = GuardRuntime.controller();
        if (c == null || (!c.networkCut && GuardRuntime.appNetCutPkg() == null)) {
            toast("网络不是本功能切断的");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复网络？")
                .setMessage("操控还没清掉就恢复，等于把通道还给对方。建议先确认嫌疑应用已被冻结或卸载。")
                .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        String pkg = GuardRuntime.appNetCutPkg();
                        if (pkg != null) {
                            GuardRuntime.appendLog("🎯 手动恢复精确断网："
                                    + GuardShell.restoreAppNet(GuardLinkActivity.this, pkg).detail);
                            GuardRuntime.noteAppNetCut(null);
                        } else {
                            GuardShell.Result r = GuardShell.restoreNetwork(GuardLinkActivity.this);
                            GuardRuntime.appendLog(r.ok ? "🌐 手动恢复网络：" + r.detail
                                    : "🌐 手动恢复网络失败：" + r.detail);
                            GuardLinkCore.GuardController cc = GuardRuntime.controller();
                            if (cc != null) {
                                cc.networkCut = false;
                            }
                        }
                        selectSection(S_STATE, null);
                    }
                })
                .setNegativeButton("保持断网", null)
                .show();
    }

    private void confirmUninstall(final String pkg) {
        if (!GuardFlags.allowUninstall(this)) {
            toast("卸载能力是关的（guard_uninstall 出厂即 0）：到「处置 · 能力开关」打开，"
                    + "或让云端下发 guard_uninstall=1");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("卸载 " + pkg + "？")
                .setMessage("执行 pm uninstall --user 0（停用当前用户）。下一步还要你再真人按一次。")
                .setPositiveButton("继续", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        doUninstall(pkg);
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void doUninstall(String pkg) {
        final String target = pkg;
        TextView press = MwViews.pillButton(this, "按住不放：确认卸载 " + target, true, null);
        realPress(press, new Runnable() {
            @Override
            public void run() {
                String why = GuardRuntime.userUninstall(target);
                if (why != null) {
                    toast(why);
                    return;
                }
                toast("已执行卸载并解除保护，计时也重新起算（不再拿卸载过程本身当证据）");
                selectSection(S_TRACE, null);
            }
        });
        new AlertDialog.Builder(this)
                .setTitle("最后确认")
                .setView(press)
                .setNegativeButton("取消", null)
                .show();
    }

    private void reInvestigate() {
        if (GuardRuntime.controller() == null) {
            toast("守护没在跑：先把总闸打开");
            return;
        }
        List<GuardLinkCore.SuspectEngine.AppProfile> ps = new GuardProfiles(this).collect(null);
        GuardLinkCore.SuspectEngine.Verdict v = GuardLinkCore.SuspectEngine.investigate(ps);
        String text = verdictText(v);
        GuardRuntime.appendLog("🔎 手动推举：画像 " + ps.size() + " 个（无触发上下文，行为项不计分）");
        new AlertDialog.Builder(this).setTitle("重新推举").setMessage(text)
                .setPositiveButton("知道了", null).show();
        renderVerdict();
        renderTargets();
    }

    private void restoreFactory() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = MwViews.dp(this, 18);
        col.setPadding(pad, pad, pad, 0);
        col.addView(GuardSheet.body(this,
                "清掉：本机改过的 21 条参数 + 本机拨过的开关与节奏。\n"
                        + "云控下发过的条目仍听云控，不会被这一步覆盖掉。",
                MwViews.TEXT, GuardSheet.T_BODY));
        final TextView press = MwViews.pillButton(this, "按住不放：恢复出厂", true, null);
        col.addView(press, matchWidth(this, 12));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("恢复出厂设置？")
                .setView(col)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        realPress(press, new Runnable() {
            @Override
            public void run() {
                GuardParams.clearLocal(GuardLinkActivity.this);
                GuardFlags.clearLocal(GuardLinkActivity.this);
                GuardRuntime.appendLog("🧹 已恢复出厂：" + GuardParams.applyAll(GuardLinkActivity.this));
                toast("已恢复出厂");
                dlg.dismiss();
                // 出厂值里 guard_regen / guard_hide_task 都是开，恢复后要把服务与卡片重新对上
                syncRecents();
                selectSection(S_CHECK, null);
            }
        });
    }

    private void selfCheck() {
        StringBuilder sb = new StringBuilder("自检结果\n");
        sb.append("总闸：").append(GuardFlags.masterOn(this) ? "开" : "关（guard_on=0）").append('\n');
        sb.append("无障碍：").append(GuardProbe.accessibilityEnabled(this) ? "已启用" : "未启用").append('\n');
        sb.append("Shizuku：").append(GuardShell.ready() ? "已授权"
                : (GuardShell.installed() ? "已安装未授权" : "未安装")).append('\n');
        sb.append("使用情况访问：").append(GuardProbe.usageStatsGranted(this) ? "可用" : "不可用").append('\n');
        sb.append("网络当前：").append(GuardProbe.networkAlive(this) ? "通" : "断").append('\n');
        sb.append("桌面：").append(GuardProbe.homePackages(this)).append('\n');
        sb.append("输入法：").append(GuardProbe.imePackages(this)).append('\n');
        String fg = GuardProbe.lastResumedPkg(this);
        sb.append("前台快照：").append(fg == null ? "取不到（无权限）" : fg).append('\n');
        sb.append("常驻服务：").append(GuardService.running() ? "在跑" : "没在跑")
                .append("（再生开关 ").append(GuardFlags.regenOn(this) ? "开" : "关").append("）\n");
        sb.append("无障碍自愈：").append(GuardRuntime.healState()).append('\n');
        sb.append("最近任务隐藏：").append(GuardFlags.hideTaskOn(this) ? "开" : "关")
                .append(recentsNote.length() == 0 ? "" : " —— " + recentsNote).append('\n');
        sb.append("系统级可信执行者：").append(GuardSysAi.describe(GuardRuntime.trustedActors()))
                .append(GuardFlags.sysAiOn(this) ? "（开关开）" : "（开关关，全部照算）").append('\n');
        sb.append("触发前提：").append(GuardRuntime.signalReport()).append('\n');
        sb.append("参数：").append(GuardParams.applyAll(this)).append('\n');
        sb.append("状态盘：").append(GuardState.describe(this));
        GuardRuntime.appendLog("🔎 自检：" + GuardText.flat(sb.toString()));
        new AlertDialog.Builder(this).setTitle("立即自检").setMessage(sb.toString())
                .setPositiveButton("知道了", null).show();
        renderDetail();
        regenSig = "";
        renderRegen();
    }

    private void askShizuku() {
        if (GuardShell.ready()) {
            toast("Shizuku 已授权");
        } else if (!GuardShell.requestPermission()) {
            toast("发不起授权请求：这台手机上 Shizuku 没装或没在跑");
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderAuth();
            }
        }, 800L);
    }

    private void grantUsageStats() {
        if (GuardProbe.usageStatsGranted(this)) {
            toast("已经有使用情况访问权限");
            return;
        }
        if (!GuardShell.ready()) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("在列表里把「领境」这一项打开");
            } catch (Throwable t) {
                toast("这台 ROM 打不开使用情况访问页面");
            }
            return;
        }
        GuardShell.Result r = GuardShell.grantUsageStats(this);
        toast(r.ok ? "已通过 Shizuku 自授 USAGE_STATS" : "自授失败：" + r.detail);
        renderAuth();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("在列表里找「安全守护」并打开");
        } catch (Throwable t) {
            toast("这台 ROM 打不开无障碍设置页，请到系统设置里手动找");
        }
    }

    private void askInstaller() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("嫌疑包包名，如 com.xxx.tool");
        input.setSingleLine(true);
        MwViews.styleInput(input, MwViews.style(this));
        new AlertDialog.Builder(this)
                .setTitle("查安装来源")
                .setView(input)
                .setPositiveButton("查", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        String pkg = String.valueOf(input.getText()).trim();
                        if (!GuardShell.safePkg(pkg)) {
                            toast("包名不对：只能是字母数字和 . _ -");
                            return;
                        }
                        GuardShell.Result r = GuardShell.installerOf(GuardLinkActivity.this, pkg);
                        GuardRuntime.appendLog("🔎 安装来源 " + pkg + "：" + r.detail);
                        new AlertDialog.Builder(GuardLinkActivity.this)
                                .setTitle(pkg + " 的安装来源")
                                .setMessage(r.detail.length() > 0 ? r.detail : "查不到")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 不可逆动作的按钮：必须真人按住。按下/抬起由 OnTouchListener 采集（返回 false 让点击照走），
     * 点击时过 HumanVerifier —— 没有 DOWN/UP（无障碍模拟）或同帧（注入）都打回，且不消耗任何东西。
     */
    private void realPress(View v, final Runnable go) {
        final Long[] ts = new Long[2];
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                long now = System.currentTimeMillis();
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    ts[0] = now;
                    ts[1] = null;
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    ts[1] = now;
                }
                return false;
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String reject = GuardLinkCore.HumanVerifier.verify(
                        System.currentTimeMillis(), ts[0], ts[1]);
                if (reject != null) {
                    GuardRuntime.appendLog("⛔ 拒绝程序点击：" + reject);
                    toast("这次点击不算数：" + reject);
                    ts[0] = null;
                    ts[1] = null;
                    return;
                }
                ts[0] = null;
                ts[1] = null;
                go.run();
            }
        });
    }

    private View mkDivider() {
        return MwViews.divider(this);
    }

    private static int dot(boolean good) {
        return good ? MwViews.OK : MwViews.DANGER;
    }

    private View monotv(String text) {
        TextView tv = GuardSheet.mono(this, GuardSheet.T_BODY, MwViews.TEXT);
        tv.setText(text);
        android.widget.HorizontalScrollView hx = new android.widget.HorizontalScrollView(this);
        hx.addView(tv, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hx;
    }

    private static LinearLayout.LayoutParams matchWidth(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = MwViews.dp(c, topDp);
        return lp;
    }

    private static String textOf(ViewGroup card) {
        if (card == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (v instanceof TextView) {
                sb.append(((TextView) v).getText()).append('\n');
            } else if (v instanceof ViewGroup) {
                sb.append(textOf((ViewGroup) v));
            }
        }
        return sb.toString();
    }

    /** 一句话回执：失败原因、被拒理由、复制结果都走这里，别只写进日志让他去捞。 */
    private void toast(String s) {
        Toast.makeText(this, s == null ? "" : s, Toast.LENGTH_LONG).show();
    }

    private void copyText(String s, String what) {
        if (TextUtils.isEmpty(s)) {
            toast("没有可复制的" + what);
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("guardlink", s));
            AppLog.copy(this, s);
            toast(what + "已复制");
        } catch (Throwable t) {
            toast("复制失败：" + t.getClass().getSimpleName());
        }
    }

    private void prefs(boolean on) {
        GuardRuntime.setWanted(this, on);
    }

    /**
     * 由 {@code MainActivity} 在启动时调用：总闸没关、上次也是开着的，就自动值守。
     * prefs 默认 true —— 功能默认可用，不是「开着页面才有效」；
     * 但用户在页面上点过「停用守护」之后，这条意愿优先，启动与开机都不许把它偷偷开回来。
     */
    public static void autostartIfWanted(final Context ctx) {
        try {
            if (GuardRuntime.running()) {
                return;
            }
            if (!GuardRuntime.wanted(ctx)) {
                return;
            }
            String why = GuardRuntime.enable(ctx, true);
            if (why != null) {
                AppLog.i("GuardLink", "自动值守未起来：" + why);
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "自动值守异常", t);
        }
    }
}
