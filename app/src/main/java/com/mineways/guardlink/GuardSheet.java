package com.mineways.guardlink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mineways.ui.MwViews;

/**
 * 安全守护专属的界面件：玻璃开关行、参数滑杆行、下拉导航抽屉。
 *
 * <p>为什么要在本包内自己写：{@code MwViews} 提供的是底、字、卡、胶囊按钮、chip 这些通用件，
 * 整个 App 里<b>没有</b>现成的开关件、滑杆件，也没有任何抽屉/侧栏/底部面板的实现可以复用
 * （唯一的折叠先例是 RealWorldActivity 里直接 setVisibility 那种）。
 *
 * <p>配色、圆角、玻璃底继续走 {@link MwViews}，但<b>字阶在本包里单独定了一份</b>（下面 T_*）：
 * 上一版正文用 11.5sp、说明用 TEXT_3，在黑底 + 6% 白玻璃上基本等于没印 ——
 * 这是被明确否掉的，所以这里整体上一档，并把说明色的下限从 TEXT_3 提到 TEXT_2。
 */
public final class GuardSheet {

    /** 大字状态（首屏第一行）。 */
    public static final float T_STATE = 26f;
    /** 条目标题：开关名、参数名。 */
    public static final float T_TITLE = 16f;
    /** 正文与说明，一律不低于这个值。 */
    public static final float T_BODY = 13f;
    /** 次要说明（范围/一格/出厂对照）：和正文同档，只靠颜色分层，不靠字号把人看瞎。 */
    public static final float T_SUB = 13f;
    /** 参数当前值。 */
    public static final float T_VALUE = 26f;
    /** 导航与抽屉里的条目。 */
    public static final float T_LABEL = 14.5f;
    /** 玻璃卡的底：上一版 6% 白在纯黑上看不出边界，提到 12%。 */
    public static final int CARD_TINT = 0x1FFFFFFF;

    private GuardSheet() {
    }

    /** 值改变回调（long 统一：毫秒、分、次数都用它）。 */
    public interface ValueChanger {
        void onChange(long value);
    }

    // ---------------------------------------------------------------- 文本与行

    /** 说明文字：默认走 TEXT_2，TEXT_3 只允许用在真正可跳过的补充说明上。 */
    public static TextView body(Context c, String s, int color, float sizeSp) {
        return MwViews.body(c, s, color, sizeSp);
    }

    /** 一段说明（正文档）。 */
    public static TextView prose(Context c, String s) {
        return MwViews.body(c, s, MwViews.TEXT_2, T_BODY);
    }

    /**
     * 卡片头：只保留大标题。
     * 第二行解释参数仍接收，但不再渲染 —— 安全守护所有卡片的副标题统一收掉，
     * 具体状态和按钮本身已经说明这一屏能干什么。
     */
    public static View head(Context c, String title, String why) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, MwViews.dp(c, 6));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(18f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(MwViews.TEXT);
        col.addView(t);
        return col;
    }

    public static TextView mono(Context c, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    /** 左标题右读数的信息行（健康位、计数、状态用）。 */
    public static View statusRow(Context c, int dotColor, String label, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, MwViews.dp(c, 8), 0, MwViews.dp(c, 8));

        View dot = new View(c);
        dot.setBackground(MwViews.round(c, dotColor, 100));
        row.addView(dot, new LinearLayout.LayoutParams(MwViews.dp(c, 10), MwViews.dp(c, 10)));

        TextView l = MwViews.body(c, label, MwViews.TEXT_2, T_BODY);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = MwViews.dp(c, 10);
        llp.rightMargin = MwViews.dp(c, 10);
        row.addView(l, llp);

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(14f);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(MwViews.TEXT);
        row.addView(v);
        return row;
    }

    /** 一行胶囊按钮（等宽铺开用）。手指是主角，点击区加高到 48dp 上下留白。 */
    public static TextView pill(Context c, String label, boolean primary, View.OnClickListener go) {
        TextView t = MwViews.pillButton(c, label, primary, go);
        t.setTextSize(14.5f);
        t.setPadding(MwViews.dp(c, 12), MwViews.dp(c, 14), MwViews.dp(c, 12), MwViews.dp(c, 14));
        t.setMinHeight(MwViews.dp(c, 48));
        t.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = MwViews.dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    public static LinearLayout pills(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, MwViews.dp(c, 10), 0, 0);
        return row;
    }

    /**
     * 整宽胶囊按钮：给竖直容器（卡片直接 addView）用的。
     *
     * <p>不能复用 {@link #pill} —— 那个的宽度是 0 + weight=1，只在水平行里才被撑开；
     * 放进竖直 LinearLayout 就是宽度 0，按钮存在但看不见、摸不着。
     */
    public static TextView pillWide(Context c, String label, boolean primary, View.OnClickListener go) {
        TextView t = pill(c, label, primary, go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = MwViews.dp(c, 8);
        t.setLayoutParams(lp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ---------------------------------------------------------------- 玻璃开关行

    /**
     * 一条能力开关：标题 + 现值（出厂/本机/云控）+ 右侧拨动件 + 说明。
     *
     * <p>被云控锁住时不是把开关置灰了事，而是照样显示云端下发的值、并把「改不动」的原因
     * 用一行小字写明 —— 用户看得懂为什么拨不动，才不会以为手机坏了。
     */
    public static View switchRow(final Context c, final String key, String title, String note,
                                 boolean on, boolean locked, String source,
                                 final ValueChanger toggle) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, MwViews.dp(c, 14), 0, MwViews.dp(c, 14));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(T_TITLE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(MwViews.TEXT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.rightMargin = MwViews.dp(c, 12);
        head.addView(t, tl);

        final Knob knob = new Knob(c, on, locked);
        head.addView(knob, new LinearLayout.LayoutParams(
                MwViews.dp(c, 56), MwViews.dp(c, 32)));

        box.addView(head);

        final TextView s = MwViews.body(c, reading(on, source, locked),
                locked ? MwViews.WARN : MwViews.TEXT_2, T_SUB);
        s.setPadding(0, MwViews.dp(c, 8), 0, 0);
        box.addView(s);

        if (note != null && note.length() > 0) {
            TextView n = MwViews.body(c, note, MwViews.TEXT_2, T_BODY);
            n.setPadding(0, MwViews.dp(c, 4), 0, 0);
            box.addView(n);
        }

        View.OnClickListener go = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean want = !knob.isOn();
                // 就地动画 + 就地改读数：拨一下必须有即时反应。外层如果被云控拒了会重建这一屏，
                // 把显示拉回真实的取值，所以这里乐观更新不会说谎太久。
                knob.set(want, locked);
                s.setText(reading(want, source, locked));
                s.setTextColor(locked ? MwViews.WARN : MwViews.TEXT_3);
                toggle.onChange(want ? 1 : 0);
            }
        };
        head.setOnClickListener(go);
        t.setOnClickListener(go);
        knob.setOnClickListener(go);
        box.setTag(key);
        return box;
    }

    private static String reading(boolean on, String source, boolean locked) {
        return (on ? "开" : "关") + " · 来源 " + source
                + (locked ? " · 云控锁定，本机拨不动" : "");
    }

    // ---------------------------------------------------------------- 参数滑杆行

    /** 一条可调参数（内核参数清单里的）：出厂值、范围、锁定状态都取自 {@link GuardParams}。 */
    public static View paramRow(Context c, GuardParams.Item it, ValueChanger write) {
        return buildRow(c, it.title, it.unit, it.note, it.min, it.max, it.step,
                it.current(), it.factory, it.cloudPinned(), "gp_" + it.id, write);
    }

    /**
     * 运行节奏类参数（不住在内核里，住在本 App 的存储 + 云控）：控件与内核参数完全同一套，
     * 免得同屏出现两种长相的滑杆。
     */
    public static View cadenceRow(Context c, String title, String unit, String note,
                                  long min, long max, long step, long cur, long factory,
                                  boolean locked, String cloudKey, ValueChanger write) {
        return buildRow(c, title, unit, note, min, max, step, cur, factory, locked, cloudKey, write);
    }

    /**
     * 一条可调参数的实体：大号当前值 + 滑杆 + 加减键 + 出厂对照 + 说明。
     *
     * <p>滑杆拖到哪就写哪，但值一律由调用方回读后再刷新（云控锁定或夹取改写过时会显示改写后的值），
     * 这样界面显示的永远是真正生效的那个数，而不是用户以为他设上的那个。
     */
    private static View buildRow(Context c, String title, final String unit, String note,
                                 long min, long max, long step, long cur, final long factory,
                                 boolean locked, final String cloudKey,
                                 final ValueChanger write) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, MwViews.dp(c, 15), 0, MwViews.dp(c, 15));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);

        TextView name = new TextView(c);
        name.setText(title);
        name.setTextSize(T_TITLE);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(MwViews.TEXT);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.bottomMargin = MwViews.dp(c, 3);
        head.addView(name, nl);

        TextView value = new TextView(c);
        value.setTextSize(T_VALUE);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setTextColor(MwViews.TEXT);
        head.addView(value);

        TextView unitLabel = new TextView(c);
        unitLabel.setText(unit.trim());
        unitLabel.setTextSize(T_SUB);
        unitLabel.setTextColor(MwViews.TEXT_2);
        unitLabel.setPadding(MwViews.dp(c, 4), 0, 0, MwViews.dp(c, 4));
        head.addView(unitLabel);
        box.addView(head);

        Bar bar = new Bar(c, min, max, step, cur, locked, new ValueChanger() {
            @Override
            public void onChange(long v) {
                write.onChange(v);
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MwViews.dp(c, 38));
        bl.topMargin = MwViews.dp(c, 10);
        box.addView(bar, bl);

        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, MwViews.dp(c, 8), 0, 0);

        final Bar barRef = bar;
        TextView minus = MwViews.pillButton(c, "−", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                write.onChange(barRef.move(-1));
            }
        });
        foot.addView(minus, chipLp(c));
        TextView plus = MwViews.pillButton(c, "+", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                write.onChange(barRef.move(1));
            }
        });
        foot.addView(plus, chipLp(c));

        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fill.leftMargin = MwViews.dp(c, 10);
        TextView sub = MwViews.body(c, "", MwViews.TEXT_2, T_SUB);
        foot.addView(sub, fill);
        box.addView(foot);

        if (note != null && note.length() > 0) {
            box.addView(MwViews.body(c, note, MwViews.TEXT_2, T_BODY));
        }

        value.setText(String.valueOf(cur));
        sub.setText(scope(factory, unit, min, max, step, locked, cloudKey));
        return box;
    }

    private static String scope(long factory, String unit, long min, long max, long step,
                                boolean locked, String cloudKey) {
        return "出厂 " + factory + unit + " · 可设 " + min + "~" + max + unit
                + " · 一格 " + step + unit
                + (locked ? " · 云控锁定（" + cloudKey + "）" : "");
    }

    private static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MwViews.dp(c, 52), MwViews.dp(c, 44));
        lp.rightMargin = MwViews.dp(c, 8);
        return lp;
    }

    // ---------------------------------------------------------------- 抽屉

    /**
     * 一个从屏幕边缘进来的玻璃面板。
     *
     * <p>{@code fromTop} 就是下拉式（顶部进来，装分类导航）；{@code false} 是滑动式。
     * 本页只用下拉那一个：上一版同时给了「6 标签 + 20 胶囊 + 两个抽屉 + 抽屉里的树」，
     * 三套导航互相重复，找一个东西要先猜该用哪套 —— 侧滑那个已删，它的动作都在首屏按钮上。
     * 手势与动画两种共用：把手可以拖，拖过半程就顺势
     * 开/合，没到就弹回；scrim 点一下就收；返回键优先收抽屉。
     *
     * <p>{@code spanPx} 是「完全收起时要平移的距离」，由调用方按面板的固定尺寸给 ——
     * 不读 panel.getHeight()，因为第一次 layout 之前它是 0，收起态就会算错、抽屉露一角。
     */
    public static final class Drawer {

        private final Context ctx;
        private final boolean fromTop;
        private final View panel;
        private final View handle;
        private final View scrim;
        private final float span;
        private float openFraction;
        private boolean opened;

        public Drawer(Context c, boolean fromTop, View panel, View handle, View scrim, int spanPx) {
            this.ctx = c;
            this.fromTop = fromTop;
            this.panel = panel;
            this.handle = handle;
            this.scrim = scrim;
            this.span = Math.max(1f, spanPx);
            panel.setClickable(true);
            panel.setOnTouchListener(new Drag());
            handle.setOnTouchListener(new Drag());
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    close();
                }
            });
            settle(0f);
        }

        public boolean isOpen() {
            return opened;
        }

        public void toggle() {
            if (opened) {
                close();
            } else {
                open();
            }
        }

        public void open() {
            opened = true;
            settle(1f);
            scrim.setVisibility(View.VISIBLE);
            scrim.animate().alpha(1f).setDuration(160).start();
        }

        public void close() {
            opened = false;
            settle(0f);
            scrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override
                public void run() {
                    scrim.setVisibility(View.GONE);
                }
            }).start();
        }

        /** 返回键该不该被抽屉吃掉。 */
        public boolean onBackPressed() {
            if (!opened) {
                return false;
            }
            close();
            return true;
        }

        private void settle(float f) {
            openFraction = f;
            if (fromTop) {
                panel.setTranslationY(-(1f - f) * span);
            } else {
                panel.setTranslationX((1f - f) * span);
            }
        }

        private final class Drag implements View.OnTouchListener {
            private float start;
            private float base;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        dragging = false;
                        start = fromTop ? e.getRawY() : e.getRawX();
                        base = openFraction;
                        // 把手在 ScrollView 里面：不先把拦截关掉，外层滚动会立刻把这次拖动抢走
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;          // 把手永远归抽屉，不跟内容滚动手势抢
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float cur = fromTop ? e.getRawY() : e.getRawX();
                        float delta = (cur - start) / span;
                        if (Math.abs(cur - start) > MwViews.dp(ctx, 6)) {
                            dragging = true;
                        }
                        if (!dragging) {
                            return true;
                        }
                        settle(Math.max(0f, Math.min(1f, base + (fromTop ? delta : -delta))));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        if (dragging) {
                            if (openFraction > 0.45f) {
                                open();
                            } else {
                                close();
                            }
                        } else if (v == handle) {
                            toggle();
                        } else {
                            v.performClick();
                        }
                        dragging = false;
                        return true;
                    }
                    default:
                        return false;
                }
            }
        }
    }

    /** 抽屉把手：横杠加粗加长，旁边一句正文档的说明 —— 上一版是 4dp × 44dp + 10.5sp，看不见。 */
    public static View handle(Context c, String label) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, MwViews.dp(c, 10), 0, MwViews.dp(c, 10));
        col.setClickable(true);

        View bar = new View(c);
        bar.setBackground(MwViews.round(c, 0x73FFFFFF, 100));
        col.addView(bar, new LinearLayout.LayoutParams(MwViews.dp(c, 68), MwViews.dp(c, 6)));

        TextView t = MwViews.body(c, label, MwViews.TEXT_2, T_SUB);
        t.setPadding(0, MwViews.dp(c, 6), 0, 0);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        return col;
    }

    /**
     * 抽屉面板的底：整块大圆角玻璃，绑定自己作宿主，这样它能折射底下的光而不是死色一块。
     */
    public static LinearLayout panel(Context c, int radiusDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(MwViews.glass(c, MwViews.style(c), radiusDp, CARD_TINT, box));
        box.setPadding(MwViews.dp(c, 20), MwViews.dp(c, 14), MwViews.dp(c, 20), MwViews.dp(c, 18));
        return box;
    }

    // ---------------------------------------------------------------- 自绘控件

    /** 拨动件：轨道 + 滑块，颜色只允许「白/银渐变」这一种光源。 */
    private static final class Knob extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackOn;
        private boolean on;
        private boolean locked;
        private float knobX = -1f;

        Knob(Context c, boolean on, boolean locked) {
            super(c);
            this.on = on;
            this.locked = locked;
            this.trackOn = MwViews.style(c).accent;
            setContentDescription("开关");
        }

        boolean isOn() {
            return on;
        }

        void set(boolean on, boolean locked) {
            this.on = on;
            this.locked = locked;
            animateKnob();
            invalidate();
        }

        private void animateKnob() {
            if (knobX < 0f) {
                return;
            }
            final float from = knobX;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofFloat(from, on ? 1f : 0f);
            va.setDuration(180);
            va.setInterpolator(new DecelerateInterpolator(1.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    knobX = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            va.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            if (knobX < 0f) {
                knobX = on ? 1f : 0f;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            RectF track = new RectF(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? MwViews.alpha(trackOn, 0x33) : 0x0DFFFFFF);
            canvas.drawRoundRect(track, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(MwViews.dp(getContext(), 1));
            p.setColor(on ? MwViews.alpha(trackOn, 0x8C) : (locked ? MwViews.alpha(MwViews.WARN, 0x66)
                    : MwViews.HAIRLINE));
            canvas.drawRoundRect(track, r, r, p);

            float kr = h * 0.36f;
            float cx = kr + 2f + knobX * (w - 2f * kr - 4f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? trackOn : (locked ? MwViews.WARN : 0x80FFFFFF));
            canvas.drawCircle(cx, h / 2f, kr, p);
        }
    }

    /** 参数滑杆：轨道 + 强调色已填充段 + 白色滑块；支持拖动与松手吸附到 step。 */
    private static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long min, max, step;
        private final int accent;
        private final ValueChanger onCommit;
        private long value;
        private boolean locked;

        Bar(Context c, long min, long max, long step, long value, boolean locked,
            ValueChanger onCommit) {
            super(c);
            this.min = min;
            this.max = max;
            this.step = step;
            this.value = value;
            this.locked = locked;
            this.onCommit = onCommit;
            this.accent = MwViews.style(c).accent;
            setContentDescription("参数滑杆");
        }

        void set(long v, boolean locked) {
            this.value = v;
            this.locked = locked;
            invalidate();
        }

        /** 加减键走一格，返回新值。 */
        long move(int dir) {
            long next = GuardParams.clamp(value + dir * step, min, max, step);
            if (next != value && !locked) {
                value = next;
                onCommit.onChange(next);
            }
            invalidate();
            return value;
        }

        private float frac(long v) {
            if (max == min) {
                return 0f;
            }
            return (float) (v - min) / (max - min);
        }

        private long snap(float f) {
            float clamped = Math.max(0f, Math.min(1f, f));
            return GuardParams.clamp(min + Math.round(clamped * (max - min)), min, max, step);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float pad = h * 0.34f;
            float trackH = MwViews.dp(getContext(), 5);
            float cy = h / 2f;
            r.set(pad, cy - trackH / 2f, w - pad, cy + trackH / 2f);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14FFFFFF);
            canvas.drawRoundRect(r, trackH, trackH, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(MwViews.dp(getContext(), 1));
            p.setColor(MwViews.HAIRLINE);
            canvas.drawRoundRect(r, trackH, trackH, p);

            float knobX = r.left + frac(value) * r.width();
            r.set(pad, cy - trackH / 2f, Math.max(pad + 1f, knobX), cy + trackH / 2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(locked ? MwViews.alpha(MwViews.WARN, 0x55) : accent);
            canvas.drawRoundRect(r, trackH, trackH, p);

            float kr = h * 0.30f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(locked ? 0xB0FFFFFF : accent);
            canvas.drawCircle(knobX, cy, kr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(MwViews.dp(getContext(), 1));
            p.setColor(0x66000000);
            canvas.drawCircle(knobX, cy, kr, p);

            // 端点标尺：min / max 直接写在滑杆下面，省得用户猜这根条的范围
            p.setStyle(Paint.Style.FILL);
            p.setColor(MwViews.TEXT_3);
            p.setTextSize(MwViews.dp(getContext(), 11));
            canvas.drawText(String.valueOf(min), pad, h - 1f, p);
            String mx = String.valueOf(max);
            canvas.drawText(mx, w - pad - p.measureText(mx), h - 1f, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (locked) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    apply(e.getX());
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    apply(e.getX());
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    apply(e.getX());
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    onCommit.onChange(value);
                    performClick();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }
                default:
                    return false;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        /** x → 比例 → 吸附到 step 网格；拖动过程中只更新显示，松手才提交。 */
        private void apply(float x) {
            float pad = pad0();
            float span = Math.max(1f, getWidth() - 2f * pad);
            value = snap((x - pad) / span);
            invalidate();
        }

        private float pad0() {
            return getHeight() * 0.34f;
        }
    }

    /** 抽屉里的一级条目（展开后列出它的二级卡片名）。 */
    public static View navGroup(Context c, String title, String hint, boolean expanded,
                                View.OnClickListener tap) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(c);
        head.setText((expanded ? "▾  " : "▸  ") + title);
        head.setTextSize(17f);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setTextColor(expanded ? MwViews.TEXT : MwViews.TEXT_2);
        head.setPadding(0, MwViews.dp(c, 14), 0, MwViews.dp(c, 4));
        head.setOnClickListener(tap);
        col.addView(head);

        if (hint != null && hint.length() > 0) {
            TextView h = MwViews.body(c, hint, MwViews.TEXT_2, T_SUB);
            h.setPadding(MwViews.dp(c, 4), 0, 0, 0);
            col.addView(h);
        }
        return col;
    }

    /** 抽屉里的二级条目：点它 = 跳到那一张卡。 */
    public static View navLeaf(Context c, String label, boolean active, View.OnClickListener tap) {
        TextView t = new TextView(c);
        t.setText("· " + label);
        t.setTextSize(T_LABEL);
        t.setTextColor(active ? MwViews.TEXT : MwViews.TEXT_2);
        if (active) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setBackground(MwViews.outline(c, 8, MwViews.alpha(MwViews.style(c).accent, 0x40)));
        }
        t.setPadding(MwViews.dp(c, 14), MwViews.dp(c, 12), MwViews.dp(c, 14), MwViews.dp(c, 12));
        t.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = MwViews.dp(c, 2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 建 scrim（遮罩）：压暗底下一层，抽屉的边界才看得出来。 */
    public static View scrim(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xB3000000);
        v.setVisibility(View.GONE);
        return v;
    }
}
