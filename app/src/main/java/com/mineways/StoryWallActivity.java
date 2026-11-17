package com.mineways;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 故事墙：把 {@code story.txt} 渲染成一页。
 *
 * <p>刻意做成"一页到底"的列表，不做封面图、不做正文插图 —— 内容全部是文字，
 * 所以页面只有三种状态：加载中 / 列表 / 空或失败。内容与入口都由云控决定：
 * <ul>
 *   <li>内容：仓库根目录 {@code story.txt}，10 分钟生效，不用发版；</li>
 *   <li>入口：首页那条横幅由 {@code story_off} / {@code story_hide} 控制；</li>
 *   <li>本页也可以被 {@code ENTRY add activity=StoryWallActivity} 挂成普通卡片。</li>
 * </ul>
 *
 * <p>视觉上和首页的工具卡片刻意区分：金色细条 + 金色标题，一眼能认出不是工具。
 */
public class StoryWallActivity extends Activity {

    /** 金色强调：和工具卡片的纯白标题区分开，但不刺眼。 */
    private static final int ACCENT = 0xFFFFC86B;
    private static final int BG = 0xFF0E1216;
    private static final int CARD_TINT = 0xFF141821;

    private LinearLayout listBox;
    private TextView status;
    private TextView btnRefresh;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        StoryGate.maybeAskPackageAccess(this);   // 配了 story_pkg 且读不到应用列表时，说明一次
        load(false);
    }

    // ---------------------------------------------------------------- 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        listBox.setPadding(pad, dp(4), pad, dp(28));
        scroll.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xFF8FA3B5);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(pad, dp(36), pad, dp(36));
        status.setVisibility(View.GONE);
        listBox.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        bar.setPadding(pad, dp(10), pad, dp(6));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setBackground(com.mineways.ui.MwViews.glass(
                this, com.mineways.ui.MwViews.style(this), 22, 0x33FFFFFF));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        bar.addView(titles, tp);

        TextView title = new TextView(this);
        title.setText("故事墙");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ACCENT);
        titles.addView(title);

        btnRefresh = new TextView(this);
        btnRefresh.setText("刷新");
        btnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnRefresh.setTextColor(0xCCFFFFFF);
        btnRefresh.setGravity(Gravity.CENTER);
        btnRefresh.setPadding(dp(14), dp(8), dp(14), dp(8));
        btnRefresh.setBackground(com.mineways.ui.MwViews.glass(
                this, com.mineways.ui.MwViews.style(this), 18, 0x33FFFFFF));
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(true);
            }
        });
        bar.addView(btnRefresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return bar;
    }

    // ---------------------------------------------------------------- 数据

    private void load(final boolean manual) {
        if (loading) {
            return;
        }
        loading = true;
        btnRefresh.setText("…");

        // 先拿本地缓存铺一屏，避免白屏；网络回来再覆盖
        List<StoryStore.Story> cache = StoryStore.cached(this);
        if (cache != null && !cache.isEmpty()) {
            render(cache, true);
        } else {
            showStatus("正在加载…");
        }

        StoryStore.load(this, new StoryStore.Callback() {
            @Override
            public void onStories(List<StoryStore.Story> list, boolean fromCache, String error) {
                loading = false;
                btnRefresh.setText("刷新");
                if (list == null || list.isEmpty()) {
                    if (error != null) {
                        showStatus("没取到内容：" + error + "\n下拉或点「刷新」再试");
                    } else {
                        showStatus("这里还没有故事。\n（在仓库根目录的 story.txt 里写，10 分钟后生效）");
                    }
                    return;
                }
                render(list, fromCache);
            }
        });
    }

    private void showStatus(String text) {
        listBox.removeAllViews();
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        listBox.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void render(List<StoryStore.Story> list, boolean fromCache) {
        listBox.removeAllViews();
        status.setVisibility(View.GONE);
        for (int i = 0; i < list.size(); i++) {
            View card = buildCard(list.get(i), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            listBox.addView(card, lp);
        }
    }

    /** 一篇故事的卡片：金色细条 + 标题 + 作者/日期/标签 + 正文（默认 4 行，可展开）。 */
    private View buildCard(final StoryStore.Story s, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(com.mineways.ui.MwViews.glass(
                this, com.mineways.ui.MwViews.style(this), 16, CARD_TINT));
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);

        // 左侧金色细条：和工具卡片最直观的区别
        View bar = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(2));
        bar.setBackground(g);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        bp.rightMargin = dp(12);
        card.addView(bar, bp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(s.title.length() == 0 ? "无题" : s.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFF2F6FA);
        col.addView(title);

        String meta = join(s.by, s.date, s.tag);
        if (meta.length() > 0) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            m.setTextColor(0x73FFFFFF);
            m.setPadding(0, dp(4), 0, 0);
            col.addView(m);
        }

        final TextView body = new TextView(this);
        body.setText(s.bodyText());
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        body.setTextColor(0xC4FFFFFF);
        body.setLineSpacing(dp(4), 1f);
        body.setMaxLines(4);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.setPadding(0, dp(10), 0, 0);
        col.addView(body);

        // 正文短就不给"展开"，免得点了没反应
        boolean longEnough = s.bodyText().length() > 90 || countLines(s.bodyText()) > 4;
        if (longEnough) {
            final TextView more = new TextView(this);
            more.setText("展开全文");
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            more.setTextColor(ACCENT);
            more.setPadding(0, dp(8), 0, 0);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean expanded = body.getMaxLines() != Integer.MAX_VALUE;
                    body.setMaxLines(expanded ? Integer.MAX_VALUE : 4);
                    body.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
                    more.setText(expanded ? "收起" : "展开全文");
                }
            });
            col.addView(more);
        }

        return card;
    }

    // ---------------------------------------------------------------- 小工具

    private static String join(String by, String date, String tag) {
        StringBuilder sb = new StringBuilder();
        add(sb, by);
        add(sb, date);
        add(sb, tag);
        return sb.toString();
    }

    private static void add(StringBuilder sb, String v) {
        if (v == null || v.trim().length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(v.trim());
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
