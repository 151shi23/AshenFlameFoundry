package com.mineways.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mineways.R;
import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureGate;

/**
 * 原生 View 页面的统一视觉内核：所有工具页共用的字阶、色阶、圆角、间距、按钮、输入框、
 * 分隔线都从这里出，页面里不再自己写十六进制色和 {@code new GradientDrawable()}。
 *
 * <p>为什么做成「内核 + 遍历重写」而不是一页页改：这个 App 有二十多个工具页，每页都各自手搓
 * {@code round(PANEL, 14)}（BrandScrub 12 处、ColorCode 8 处、Gif 8 处…）。一页页改要几十次提交，
 * 还会漏；把样式收进一个内核，再把每个页面那几个私有构造方法改成转发到内核，**所有页面同时**
 * 换成同一套设计，业务逻辑一行都不碰。{@link MwChrome} 还会在生命周期上兜一次底，
 * 新加的页面就算忘了接也是这个视觉。
 *
 * <p>内核只改「看起来怎样」，不改结构、不改 id、不改文本、不改点击行为。唯一例外是
 * {@link #round} —— 它是各页背景构造方法的替身，遇到有彩色（色卡、状态色）会原样保留实心，
 * 只有中性深色底才被换成玻璃层，免得把「选颜色」这种功能洗成透明的。
 *
 * <p>全部参数云控（{@code ui_accent / ui_radius / ui_glass / ui_motion / ui_restyle}），
 * 真机上观感不对可以一句话下发退回，不用重编包。
 */
public final class MwViews {

    // ── 出厂色板（纯黑 · 白光体系，与 Compose 侧 MwTokens 同一套值）──────────────
    public static final int BG = 0xFF000000;
    public static final int BG_TOP = 0xFF000000;
    /** 兜底面是透明白的深浅阶梯：玻璃关掉时面板也保持「透出黑底」，不是灰色块。 */
    public static final int SURFACE = 0x0FFFFFFF;
    public static final int SURFACE_HIGH = 0x14FFFFFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_2 = 0x99FFFFFF;
    public static final int TEXT_3 = 0x5CFFFFFF;
    public static final int HAIRLINE = 0x1AFFFFFF;
    public static final int ACCENT = 0xFFFFFFFF;
    /** 主按钮渐变：白 → 浅银灰（金属高光那一线），纯白会失去体积感。 */
    public static final int GRAD_FROM = 0xFFFFFFFF;
    public static final int GRAD_TO = 0xFFC9CDD2;
    public static final int ON_ACCENT = 0xFF000000;
    public static final int OK = 0xFF4FBF7B;
    public static final int INFO = 0xFF59A8FF;
    public static final int WARN = 0xFFE09551;
    public static final int DANGER = 0xFFE07B7B;

    /** 一次运行的界面参数（云控覆盖后的结果）。 */
    public static final class Style {
        public int accent = ACCENT;
        public int radiusDp = 14;
        public boolean glass = true;
        public boolean motion = true;
        /** ui_flow=0 时页底白色流光关掉（纯黑静底）。 */
        public boolean flow = true;
        /** ui_restyle=0 时整套重写关掉（真机上万一观感不对，云控一句话退回）。 */
        public boolean restyle = true;
    }

    private static final Style S = new Style();

    private MwViews() {
    }

    /** 读云控覆盖；读不到就出厂值。 */
    public static Style style(Context ctx) {
        FeatureGate gate = CloudCatalog.gate();
        S.accent = parseColor(gate.flag("ui_accent", ""), ACCENT);
        S.radiusDp = clampInt(gate.flag("ui_radius", ""), S.radiusDp, 0, 32);
        // 机型策略（ui_glass_models）优先：命中 off 就直接不画玻璃，命中 low 顺带关动效
        S.glass = com.mineways.GlassPolicy.glassOn(ctx, !"0".equals(gate.flag("ui_glass", "")));
        S.motion = !"0".equals(gate.flag("ui_motion", ""))
                && !com.mineways.GlassPolicy.dropMotion();
        S.flow = !"0".equals(gate.flag("ui_flow", ""))
                && !com.mineways.GlassPolicy.dropFlow();
        S.restyle = !"0".equals(gate.flag("ui_restyle", ""));
        return S;
    }

    // ── 页面重写（由 MwChrome 在生命周期上触发）──────────────────────────────

    /**
     * Activity 建完内容后调一次：整棵视图树换新视觉。
     *
     * <p>页面自己也可以在建完界面后手动调一次（比如异步刷新列表之后加了新行）。
     */
    public static void restyle(Activity act) {
        Style st = style(act);
        if (!st.restyle) {
            return;
        }
        View root = act.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        // 首页那块 content 正是底部玻璃导航栏每帧采样的源：给它刷一层死黑，栏里透出来的就是黑边
        // （真机反馈「不通透 / 有黑边」的根因）。所以首页只重写子树，根底一律不碰；其它页面
        // 「没有底」或「还铺着旧调色板的深灰底」都换成纯黑 + 白色流光 —— 玻璃面板透出来的才是活的。
        // 自绘界面（OpenCut / Blockbench / 小游戏这些，名单同 MwPageGlass.SKIP）不动它们的底。
        boolean main = "MainActivity".equals(act.getClass().getSimpleName());
        if (!main && (root.getBackground() == null || isOldRootDark(root.getBackground()))) {
            root.setBackground(new FlowLightDrawable(act, st));
        }
        walk((ViewGroup) root, st, 0);
    }

    /** 旧页面自己铺的深灰根底（每页本地 BG 常量）→ 值得换成流光；自绘的黑/彩底不动。 */
    private static boolean isOldRootDark(Drawable d) {
        int color;
        try {
            if (d instanceof GradientDrawable) {
                android.content.res.ColorStateList c = ((GradientDrawable) d).getColor();
                color = c == null ? 0 : c.getDefaultColor();
            } else if (d instanceof android.graphics.drawable.ColorDrawable) {
                color = ((android.graphics.drawable.ColorDrawable) d).getColor();
            } else {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return color != 0 && !isSemantic(color) && isOldNeutral(color);
    }

    /** 递归重写：只动视觉属性，不动布局参数与文本。 */
    private static void walk(ViewGroup group, Style st, int depth) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            decolor(v, st);
            if (v instanceof TextView) {
                paintText((TextView) v, st);
            }
            if (v instanceof EditText) {
                styleInput((EditText) v, st);
            }
            if (v instanceof Button) {
                pill((Button) v, st);
            }
            if (v instanceof LinearLayout || v instanceof FrameLayout) {
                panel((ViewGroup) v, st);
            }
            if (v instanceof ScrollView) {
                ScrollView sv = (ScrollView) v;
                sv.setClipToPadding(false);
                sv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            }
            // WebView 的子树不归原生管（OpenCut / Blockbench 那些页面自己在画），整块跳过
            if (v instanceof android.webkit.WebView) {
                continue;
            }
            if (v instanceof ViewGroup) {
                walk((ViewGroup) v, st, depth + 1);
            }
        }
    }

    /**
     * 去漆：功能页各自手写的旧中性深色实底（BlenderActivity 的 CARD/INPUT、ActivationDialog 的
     * BG 这类，每页一套本地常量）一律换成透明玻璃，让黑底与流光透出来。
     *
     * <p>只碰「确认是旧调色板」的颜色：语义色（色卡、状态色）原样保留，改了就是改功能。
     */
    private static void decolor(View v, Style st) {
        if (v instanceof android.webkit.WebView || v.getBackground() == null) {
            return;
        }
        Drawable d = v.getBackground();
        int color;
        int radiusDp = 14;
        try {
            if (d instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) d;
                android.content.res.ColorStateList c = gd.getColor();
                color = c == null ? 0 : c.getDefaultColor();
                radiusDp = Math.round(gd.getCornerRadius()
                        / v.getResources().getDisplayMetrics().density);
            } else if (d instanceof android.graphics.drawable.ColorDrawable) {
                color = ((android.graphics.drawable.ColorDrawable) d).getColor();
            } else {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        if (color == 0 || isSemantic(color) || !isOldNeutral(color)) {
            return;
        }
        v.setBackground(glass(v.getContext(), st, radiusDp <= 0 ? 14 : radiusDp, color, v));
    }

    /** 旧调色板的中性深色（每页本地常量的值域）：黑到深灰蓝、无彩或极低饱和。 */
    static boolean isOldNeutral(int color) {
        if ((color >>> 24) < 0x60) {
            return false;
        }
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int mx = Math.max(r, Math.max(g, b));
        if (mx - Math.min(r, Math.min(g, b)) > 32) {
            return false;
        }
        return mx <= 0x48;
    }

    /**
     * 字阶：按现有字号归类，不新增控件也不改文本。
     * 18sp 以上是大标题，15~18 是卡片标题，12.5~15 是正文，再小是说明。
     */
    private static void paintText(TextView t, Style st) {
        float sp = t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity;
        int cur = t.getCurrentTextColor();
        if (t.getHint() != null && cur == Color.TRANSPARENT) {
            return;
        }
        // 旧品牌橙 = 退役的强调色，全部转成现在的强调色（白）；语义橙 WARN 0xFFE09551 不在列
        if (cur == 0xFFD9603A) {
            cur = st.accent;
        }
        if (sp >= 18f) {
            t.setTextColor(TEXT);
            bold(t);
            t.setLineSpacing(dp(t, 2), 1.12f);
        } else if (sp >= 15f) {
            t.setTextColor(TEXT);
            bold(t);
            t.setLineSpacing(dp(t, 2), 1.2f);
        } else if (sp >= 12.5f) {
            t.setTextColor(isSemantic(cur) ? cur : TEXT_2);
            t.setLineSpacing(dp(t, 3), 1.32f);
        } else {
            t.setTextColor(isSemantic(cur) ? cur : TEXT_3);
            t.setLineSpacing(dp(t, 2), 1.35f);
        }
    }

    private static void bold(TextView t) {
        t.setTypeface(android.graphics.Typeface.create(t.getTypeface(),
                android.graphics.Typeface.BOLD));
    }

    /** 有彩色倾向（不是灰白黑）就当语义色保留：橙/绿/蓝/红/紫，还有色卡页的那些真颜色。 */
    public static boolean isSemantic(int color) {
        int a = color >>> 24;
        if (a < 0x80) {
            return false;
        }
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return mx - mn > 40;
    }

    /** 面板：原来用纯色圆角的地方换成玻璃层（半透渐变 + 上亮下暗描边）。 */
    private static void panel(ViewGroup v, Style st) {
        Drawable d = v.getBackground();
        if (!(d instanceof GradientDrawable)) {
            return;
        }
        GradientDrawable gd = (GradientDrawable) d;
        int tint;
        try {
            android.content.res.ColorStateList c = gd.getColor();
            int color = c == null ? 0 : c.getDefaultColor();
            if (color == 0 || isSemantic(color)) {
                return;      // 语义底（进度条、色块、状态提示）不动，改了就是改功能
            }
            tint = color;
        } catch (Throwable ignored) {
            return;          // 渐变底的面板保持原样更安全
        }
        v.setBackground(glass(v.getContext(), st, dpOf(v, gd), tint, v));
    }

    // ── 背景：各页私有 round() 的替身 ────────────────────────────────────────

    /**
     * 背景构造：中性深色底 → 玻璃层；有彩色底（色卡、档位色、状态提示）→ 原色但收圆、
     * 加一道顶部高光。返回的一律是可用的 Drawable，页面调用点一个都不用改签名。
     */
    public static Drawable round(Context c, int color, int radiusDp) {
        return round(c, style(c), color, radiusDp);
    }

    static Drawable round(Context c, Style st, int color, int radiusDp) {
        if (isSemantic(color)) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(c, fix(radiusDp)));
            gd.setColor(color);
            return gd;
        }
        return glass(c, st, radiusDp <= 0 ? st.radiusDp : radiusDp, color);
    }

    /** 一块玻璃面板底（不绑定宿主：折射退化成平面玻璃）。 */
    public static Drawable glass(Context c, Style st, int radiusDp, int tint) {
        return glass(c, st, radiusDp, tint, null);
    }

    /**
     * 一块玻璃面板底，并绑定宿主 View。
     *
     * <p>宿主只用来算「这块玻璃在整页的坐标」：页底的光带是解析函数，知道位置就能在
     * 面板内部按同一函数取样再偏移几 dp —— 不需要抓位图，也不会多一层 RenderEffect。
     * 这是原生工具页从「半透明色块」变成「真玻璃」的关键。
     */
    public static Drawable glass(Context c, Style st, int radiusDp, int tint, View host) {
        MwSurfaceDrawable d = new MwSurfaceDrawable(dp(c, fix(radiusDp)),
                st.glass, tint == 0 ? SURFACE : tint);
        d.setDensity(c.getResources().getDisplayMetrics().density);
        d.setFlow(st.flow);
        d.setHost(host);
        return d;
    }

    /** 强调色实心底（主按钮、选中态），渐变 = 白 → 浅银灰（黑底上唯一的亮块）。 */
    public static Drawable accentFill(Context c, int radiusDp, boolean gradient) {
        Style st = style(c);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(c, fix(radiusDp)));
        if (gradient) {
            gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            gd.setColors(new int[]{st.accent, blend(st.accent, GRAD_TO, 0.55f)});
        } else {
            gd.setColor(st.accent);
        }
        return gd;
    }

    /** 描边底（次要按钮、chip）。 */
    public static Drawable outline(Context c, int radiusDp, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(c, fix(radiusDp)));
        gd.setColor(strokeColor >>> 24 == 0 ? 0x0DFFFFFF : strokeColor);
        gd.setStroke(dp(c, 1), strokeColor);
        return gd;
    }

    /** 圆角归一到三档：小胶囊 / 卡片 / 大面板，避免同屏出现 8、10、12、14、16 五种角。 */
    private static int fix(int radiusDp) {
        if (radiusDp >= 100) {
            return 100;                  // 胶囊
        }
        if (radiusDp <= 6) {
            return 8;
        }
        return radiusDp <= 16 ? 16 : 22;
    }

    private static int dpOf(View v, GradientDrawable gd) {
        float r = gd.getCornerRadius();
        return Math.round(r / v.getResources().getDisplayMetrics().density);
    }

    // ── 结构件：新页面骨架直接用 ────────────────────────────────────────────

    /** 顶栏：小字类目 + 大标题 + 一句人话副标题（QQ/抖音详情页的头图节奏）。 */
    public static View header(Context c, String eyebrow, String title, String sub) {
        Style st = style(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 14));
        if (eyebrow != null && eyebrow.length() > 0) {
            TextView e = new TextView(c);
            e.setText(eyebrow);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            e.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            e.setTextColor(st.accent);
            e.setLetterSpacing(0.16f);
            col.addView(e);
        }
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setPadding(0, dp(c, eyebrow == null || eyebrow.length() == 0 ? 0 : 6), 0, 0);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            s.setTextColor(TEXT_2);
            s.setPadding(0, dp(c, 8), 0, 0);
            s.setLineSpacing(dp(c, 3), 1.3f);
            col.addView(s);
        }
        return col;
    }

    public static View header(Context c, String title, String sub) {
        return header(c, null, title, sub);
    }

    /** 区块标题：一句小字，左边一道强调色短竖。 */
    public static TextView section(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setLetterSpacing(0.04f);
        t.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 8));
        return t;
    }

    /** 说明文字（弱），页面里满屏的 hint/tip 都走它。 */
    public static TextView body(Context c, String s, int color, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setLineSpacing(dp(c, 3), 1.3f);
        return t;
    }

    /** 一行文字按钮（可点），替代原来「一个 TextView 挂点击」到处乱摆的做法。 */
    public static TextView link(Context c, String s, View.OnClickListener go) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(style(c).accent);
        t.setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9));
        t.setBackground(outline(c, 100, HAIRLINE));
        if (go != null) {
            t.setOnClickListener(go);
        }
        return t;
    }

    /** 胶囊按钮：主要走强调渐变，次要走 1px 描边。 */
    public static TextView pillButton(Context c, String label, boolean primary, View.OnClickListener go) {
        Style st = style(c);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(primary ? ON_ACCENT : TEXT_2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 11), dp(c, 20), dp(c, 11));
        t.setBackground(primary ? accentFill(c, 100, true) : outline(c, 100, HAIRLINE));
        if (go != null) {
            t.setOnClickListener(go);
        }
        return t;
    }

    /** 小标签 chip（档位、状态、筛选）。 */
    public static TextView chip(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(c, 100));
        gd.setColor(alpha(color, 0x26));
        gd.setStroke(dp(c, 1), alpha(color, 0x59));
        t.setBackground(gd);
        return t;
    }

    /** 内缩分隔线（微信列表里那道线就是内缩的，通栏的线一眼就是安卓）。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1));
        lp.leftMargin = dp(c, 16);
        lp.rightMargin = dp(c, 16);
        v.setLayoutParams(lp);
        return v;
    }

    /** 一张玻璃卡容器（横向内边距交给页面，卡片自己只负责底）。 */
    public static LinearLayout card(Context c) {
        return card(c, 0);
    }

    public static LinearLayout card(Context c, int topMarginDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        box.setBackground(glass(c, style(c), 16, SURFACE, box));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        box.setLayoutParams(lp);
        return box;
    }

    /** 输入框：全透玻璃 + 1px 描边（原生 EditText 那根下划线一眼就露馅）。 */
    public static void styleInput(EditText e, Style st) {
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_3);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        int r = st.radiusDp >= 14 ? 14 : st.radiusDp;
        if (st.glass) {
            // 输入框也用真玻璃：绑定宿主后能折射页底的光，和面板同一材质。
            e.setBackground(glass(e.getContext(), st, r, 0x0FFFFFFF, e));
            e.setPadding(dp(e, 12), dp(e, 11), dp(e, 12), dp(e, 11));
            return;
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(e, fix(r)));
        gd.setColor(SURFACE_HIGH);
        gd.setStroke(dp(e, 1), HAIRLINE);
        e.setBackground(gd);
        int pad = dp(e, 13);
        e.setPadding(pad, pad, pad, pad);
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        styleInput(e, style(c));
        return e;
    }

    /** 原生 Button 的兜底美化（重写器扫到就用它）。 */
    private static void pill(Button b, Style st) {
        if (st.glass) {
            b.setBackground(glass(b.getContext(), st, 100, 0x0FFFFFFF, b));
        } else {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(b, 100));
            gd.setColor(SURFACE_HIGH);
            gd.setStroke(dp(b, 1), HAIRLINE);
            b.setBackground(gd);
        }
        b.setTextColor(TEXT_2);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(b, 18), dp(b, 10), dp(b, 18), dp(b, 10));
        b.setStateListAnimator(null);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    public static int dp(View v, int dps) {
        return dp(v.getContext(), dps);
    }

    public static int dp(Context c, int dps) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dps,
                c.getResources().getDisplayMetrics()));
    }

    /** 把不透明色改写成指定 alpha 的底（chip 那种「同色淡底 + 同色描边」）。 */
    public static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    public static int blend(int from, int to, float t) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                r, g, b);
    }

    private static int clampInt(String raw, int def, int lo, int hi) {
        try {
            int n = Integer.parseInt(raw.trim());
            return n < lo || n > hi ? def : n;
        } catch (Throwable e) {
            return def;
        }
    }

    private static int parseColor(String raw, int def) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            if (s.length() == 6) {
                return (int) (0xFF000000L | Long.parseLong(s, 16));
            }
            if (s.length() == 8) {
                return (int) Long.parseLong(s, 16);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /**
     * 玻璃面板背景：全透明（透出黑底与流光）+ 顶缘高光 + 一条 1px 渐变描边。
     *
     * <p>不用 Material 的 {@code CardView} 是因为它自带投影与实心底，一眼就是安卓；
     * 这里的玻璃是「身后那道光能透过来、边缘亮一道」，白纱只有 4~7%。
     */
    public static final class MwSurfaceDrawable extends Drawable {
        private final float radius;
        private final boolean glass;
        private final int tint;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float density = 3f;
        private java.lang.ref.WeakReference<View> host;
        private boolean refract;
        private boolean flow = true;
        private boolean pressed;
        private final int[] loc = new int[2];
        private final int[] rootLoc = new int[2];
        private final float[] pts = new float[4];

        MwSurfaceDrawable(float radiusPx, boolean glass, int tint) {
            this.radius = radiusPx;
            this.glass = glass;
            this.tint = tint;
        }

        void setDensity(float d) {
            this.density = d;
        }

        void setFlow(boolean f) {
            this.flow = f;
        }

        /**
         * 绑定宿主 View：折射要知道「这块玻璃在整页的哪个位置」，才能把页底那道光
         * 按同一函数取样、再偏移一点画进来 —— 这就是原生 View 页的"真玻璃"。
         * 拿不到宿主（代码里 new 出来还没挂上去）时退化成原来的平面玻璃。
         */
        void setHost(View v) {
            if (v == null) {
                host = null;
                refract = false;
                return;
            }
            host = new java.lang.ref.WeakReference<>(v);
            try {
                refract = MwDevice.tierWithCloud(v.getContext()) != MwTier.FLAT;
            } catch (Throwable t) {
                refract = true;
            }
        }

        @Override
        public void draw(Canvas canvas) {
            RectF r = new RectF(getBounds());
            float stroke = Math.max(1f, density * 0.7f);
            r.inset(stroke / 2f, stroke / 2f);
            path.reset();
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            if (glass) {
                // 白纱：透出页底的光与色，不是死色块
                // 暗底玻璃：照片亮部透过来时白字才压得住（白纱只留顶部一道反光）
                fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                        new int[]{0x590A0C12, 0x330A0C12}, null, Shader.TileMode.CLAMP));
                canvas.drawPath(path, fill);
                if (pressed) {
                    // 按压：白 8% 的"呼吸"层，边缘同步提亮（见 draw 尾部 edge 的三元）
                    fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                            new int[]{0x14FFFFFF, 0x04FFFFFF}, null, Shader.TileMode.CLAMP));
                    canvas.drawPath(path, fill);
                }
                // 折射：把页底的两条光带按 10dp 的偏移再画一遍，边缘处会"错位" ——
                // 这一下就是玻璃有厚度的来源。只画光带，不抓位图，二十多个页面也赔得起。
                if (refract && flow) {
                    drawRefraction(canvas);
                }
                // 左上斜向反光
                fill.setShader(new LinearGradient(r.left, r.top,
                        r.left + r.width() * 0.62f, r.top + r.height() * 0.95f,
                        new int[]{0x18FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            } else {
                fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                        new int[]{tint, darken(tint)}, null, Shader.TileMode.CLAMP));
            }
            canvas.drawPath(path, fill);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            edge.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{glass ? (pressed ? 0x70FFFFFF : 0x40FFFFFF) : HAIRLINE,
                            glass ? 0x0AFFFFFF : 0x00000000},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(path, edge);
        }

        private void drawRefraction(Canvas canvas) {
            View v = host == null ? null : host.get();
            if (v == null) {
                return;
            }
            View root = v.getRootView();
            if (root == null) {
                return;
            }
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) {
                return;
            }
            // 整页容器（ScrollView / 铺满的根布局）不做折射：它一折射就等于把光带在
            // 整屏上又画了一遍，会变成重影。只给真正"浮在内容上"的面板做。
            if (v instanceof ScrollView) {
                return;
            }
            Rect own = getBounds();
            if (own.width() >= rw * 0.90f && own.height() >= rh * 0.50f) {
                return;
            }
            try {
                v.getLocationInWindow(loc);
                root.getLocationInWindow(rootLoc);
            } catch (Throwable t) {
                return;
            }
            float dx = rootLoc[0] - loc[0];
            float dy = rootLoc[1] - loc[1];
            float shift = density * 10f;
            float ox = (float) Math.cos(MwFlow.BASE_ANGLE) * shift;
            float oy = (float) Math.sin(MwFlow.BASE_ANGLE) * shift;
            MwFlow.band(rw, rh, 24_000L, MwFlow.PERIOD_MAIN, 0f, MwFlow.BASE_ANGLE, pts);
            band.setShader(new LinearGradient(pts[0] + dx + ox, pts[1] + dy + oy,
                    pts[2] + dx + ox, pts[3] + dy + oy,
                    MwFlow.colors(0.12f), MwFlow.STOPS, Shader.TileMode.CLAMP));
            canvas.drawPath(path, band);
            MwFlow.band(rw, rh, 24_000L, MwFlow.PERIOD_SECOND, 0.5f, MwFlow.BASE_ANGLE, pts);
            band.setShader(new LinearGradient(pts[0] + dx + ox, pts[1] + dy + oy,
                    pts[2] + dx + ox, pts[3] + dy + oy,
                    MwFlow.colors(0.06f), MwFlow.STOPS, Shader.TileMode.CLAMP));
            canvas.drawPath(path, band);

            // 两条细光丝也一起错位：它们的高频边缘比光带更能显出"玻璃有厚度"。
            int save = canvas.save();
            canvas.clipPath(path);
            band.setShader(null);
            band.setStyle(Paint.Style.STROKE);
            band.setStrokeWidth(Math.max(1f, density * 1.2f));
            band.setColor(0x1CFFFFFF);
            canvas.drawLine(-rw * 0.1f + dx + ox, rh * 0.30f + dy + oy,
                    rw * 1.1f + dx + ox, rh * 0.15f + dy + oy, band);
            band.setStrokeWidth(Math.max(1f, density));
            band.setColor(0x12FFFFFF);
            canvas.drawLine(-rw * 0.1f + dx + ox, rh * 0.66f + dy + oy,
                    rw * 1.1f + dx + ox, rh * 0.51f + dy + oy, band);
            band.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(save);
        }

        private static int darken(int color) {
            return Color.argb(Color.alpha(color),
                    Math.max(0, Color.red(color) - 8),
                    Math.max(0, Color.green(color) - 8),
                    Math.max(0, Color.blue(color) - 8));
        }

        /** 按压态：可点控件按下时提亮一层、边缘更亮 —— 原生按钮的"液态反馈"。 */
        @Override
        protected boolean onStateChange(int[] state) {
            boolean p = false;
            for (int s2 : state) {
                if (s2 == android.R.attr.state_pressed) {
                    p = true;
                    break;
                }
            }
            if (p != pressed) {
                pressed = p;
                invalidateSelf();
                return true;
            }
            return false;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * 页面底：深冷灰蓝渐变 + 四团低饱和色雾 + 两道白色光带 + 点阵细节。
     *
     * <p>与 Compose 侧 {@code MwHome.drawMwScene()} 是同一套场景，所以首页与工具页同色温。
     * 为什么是静态：逐帧 invalidate 会让整棵视图树（连同每块玻璃）每帧重画，是上一版
     * 「骁龙 8 至尊版都卡」的根因之一。静态场景只画一次，玻璃照样有光可折。
     * {@code ui_flow=0} 仍然只留底色，不画光带与点阵。
     */
    public static final class FlowLightDrawable extends Drawable {

        private static Bitmap sPhoto;
        private static long sPhotoVer = -1;
        private static boolean sPhotoIsCloud;

        private final boolean flow;
        private final float density;
        private final Context context;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] pts = new float[4];
        private Shader photo;
        private Shader scrim;
        private Shader base;
        private int builtW;
        private int builtH;

        FlowLightDrawable(Context c, Style st) {
            flow = st != null && st.flow;
            density = c.getResources().getDisplayMetrics().density;
            context = c.getApplicationContext();
            // 照片压暗 55%：银河/地平线接近纯白，原生页的白字直接铺上去会"消失"。
            ColorMatrix cm = new ColorMatrix();
            cm.setScale(0.45f, 0.45f, 0.45f, 1f);
            photoPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        }

        /**
         * 背景照片只解码一次（多页共用同一张 Bitmap）。
         * 云端下发的背景（BG set，sha256 校验过的那张）优先，包内星空兜底；
         * 版本号变了（新图落地 / BG reset）才重新解码。
         */
        private static Bitmap photo(Context c) {
            long ver = com.mineways.cloud.CloudAssets.backgroundVersion();
            if (sPhoto == null || sPhoto.isRecycled() || sPhotoVer != ver) {
                sPhoto = null;
                try {
                    java.io.File f = com.mineways.cloud.CloudAssets.backgroundFile(c);
                    if (f != null) {
                        sPhoto = BitmapFactory.decodeFile(f.getPath());
                        sPhotoIsCloud = true;
                    }
                } catch (Throwable t) {
                    sPhoto = null;
                }
                if (sPhoto == null) {
                    try {
                        sPhoto = BitmapFactory.decodeResource(c.getResources(), R.drawable.mw_bg);
                    } catch (Throwable t) {
                        sPhoto = null;
                    }
                    sPhotoIsCloud = false;
                }
                sPhotoVer = ver;
            }
            return sPhoto;
        }

        private void build(int w, int h) {
            builtW = w;
            builtH = h;
            Bitmap bmp = photo(context);
            if (bmp != null) {
                // 居中裁切铺满（与 Compose 侧 ContentScale.Crop 一致）
                float s = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
                Matrix m = new Matrix();
                m.setScale(s, s);
                m.postTranslate((w - bmp.getWidth() * s) * 0.5f,
                        (h - bmp.getHeight() * s) * 0.5f);
                BitmapShader bs = new BitmapShader(bmp,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bs.setLocalMatrix(m);
                photo = bs;
            } else {
                photo = new LinearGradient(0, 0, 0, h,
                        new int[]{0xFF07080D, 0xFF0C0E16}, null, Shader.TileMode.CLAMP);
            }
            // 压暗：顶部给顶栏、底部给导航栏，中间留出照片的亮部
            scrim = new LinearGradient(0, 0, 0, h,
                    new int[]{0x59000000, 0x1A000000, 0x73000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            // ui_flow=0 的兜底：纯深色静底，不画照片
            base = new LinearGradient(0, 0, 0, h,
                    new int[]{0xFF07080D, 0xFF0C0E16, 0xFF05060A},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (photo == null || builtW != w || builtH != h) {
                build(w, h);
            }
            int save = canvas.save();
            canvas.translate(b.left, b.top);
            paint.setStyle(Paint.Style.FILL);
            if (flow) {
                photoPaint.setShader(photo);
                canvas.drawRect(0, 0, w, h, photoPaint);
                paint.setShader(scrim);
                canvas.drawRect(0, 0, w, h, paint);
                // 两道很淡的白光带：原生面板的"折射"靠它们错位显形。
                MwFlow.band(w, h, 24_000L, MwFlow.PERIOD_MAIN, 0f, MwFlow.BASE_ANGLE, pts);
                drawBand(canvas, w, h, pts, 0.10f);
                MwFlow.band(w, h, 24_000L, MwFlow.PERIOD_SECOND, 0.5f,
                        MwFlow.BASE_ANGLE, pts);
                drawBand(canvas, w, h, pts, 0.05f);
            } else {
                paint.setShader(base);
                canvas.drawRect(0, 0, w, h, paint);
            }
            canvas.restoreToCount(save);
        }

        private void drawBand(Canvas canvas, float w, float h, float[] p, float peak) {
            paint.setShader(new LinearGradient(p[0], p[1], p[2], p[3],
                    MwFlow.colors(peak), MwFlow.STOPS, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return false;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            photo = null;
            invalidateSelf();
        }
    }
}
