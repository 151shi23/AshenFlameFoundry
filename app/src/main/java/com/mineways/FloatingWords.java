package com.mineways;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 点哪儿就在哪儿浮起一行字（价值观 24 字 / 有出处的诗句原话）。
 *
 * <p>挂在窗口回调上<b>只观察不消费</b>：{@code dispatchTouchEvent} 看完抬手事件就原样交回原来的
 * callback，所以列表点按、输入、滑动、WebView 里的点击全都不受影响。</p>
 *
 * <p>几条硬要求，逐条对应实现：</p>
 * <ul>
 *   <li><b>跟着页面滚</b>：字挂到 ScrollView 内容层的 {@link View#getOverlay() overlay} 上 ——
 *       不占布局、不参与排版，但会随内容一起移动；滚出可视区就被裁掉。
 *       （早先挂在 decor 上，一点屏幕坐标就钉死，列表在它底下滚，看着像粘在玻璃上。）</li>
 *   <li><b>点哪出哪</b>：文字中心落在指尖。</li>
 *   <li><b>随机换色</b>：每条从色板里随机取，出处那行固定淡灰。</li>
 *   <li><b>不重复</b>：抽干再洗牌的 bag，一轮之内不重样。</li>
 *   <li><b>能连点</b>：200ms 冷却 + 同屏 7 条上限，连点就是连续一串在飘。</li>
 *   <li><b>只管本 App 自己画的界面</b>：抬手时做命中测试，指尖落在 {@code WebView} 上就不冒
 *       （剪辑器、Blockbench、粒子、云间列车 WebGL、3D 预览、网页工具、授权页）。
 *       不判"树里有没有 WebView"：bb 转 OBJ 那页有一个被原生面板盖住的满屏内核 WebView，
 *       那页界面其实是我们画的，按树判会误杀。</li>
 * </ul>
 *
 * <p>由 {@link MinewaysApp} 统一给每个 Activity 装，所以四个分类页、关于页和每个自建功能页都生效。
 * 文案在 {@code res/values/core_values.xml}，收录标准写在那份注释里。</p>
 */
public final class FloatingWords {

    /** 同屏最多几条：连点也不会堆满屏幕。 */
    private static final int MAX_ALIVE = 7;
    /** 两次冒字的最小间隔（毫秒）—— 短到连点有"连续漂"的感觉。 */
    private static final long COOLDOWN_MS = 200;

    /** 暗底上够亮又不刺眼的色，逐条随机取。 */
    private static final int[] PALETTE = {
            0xFFFFD54F, 0xFF4FC3F7, 0xFF81C784, 0xFFF06292, 0xFFFF8A65,
            0xFFBA68C8, 0xFFAED581, 0xFFFFF176, 0xFF90CAF9, 0xFFFFCDD2,
            0xFF80DEEA, 0xFFE6EE9C,
    };

    private static final Random RANDOM = new Random();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** 抽干再洗牌：避免"连着几条都是同一句"。 */
    private static final List<Integer> BAG = new ArrayList<>();

    private long lastSpawnAt;
    private int alive;

    private FloatingWords() {
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 给这个 Activity 的窗口装上冒字观察器。
     *
     * <p>用动态代理包一层 {@code Window.Callback}，而不是手写实现它的全部方法 —— 后者会随安卓版本
     * 加方法而漏掉转发。已经装过（或拿不到 callback）就不动。</p>
     */
    static void install(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final Window.Callback origin = window.getCallback();
            if (origin == null || Proxy.isProxyClass(origin.getClass())) {
                return;
            }
            final View decor = window.getDecorView();
            final FloatingWords self = new FloatingWords();
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class},
                    (p, method, args) -> {
                        if ("dispatchTouchEvent".equals(method.getName())
                                && args != null && args.length == 1
                                && args[0] instanceof MotionEvent) {
                            self.onTouch(decor, (MotionEvent) args[0]);
                        }
                        try {
                            return method.invoke(origin, args);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof Exception) {
                                throw cause;
                            }
                            if (cause instanceof Error) {
                                throw (Error) cause;
                            }
                            throw new RuntimeException(cause);
                        }
                    });
            window.setCallback(proxy);
        } catch (Throwable ignored) {
            // 这是全局输入路径：装不上也绝不能影响任何功能
        }
    }

    private void onTouch(View decor, MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_UP) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSpawnAt < COOLDOWN_MS || alive >= MAX_ALIVE) {
            return;
        }
        View root = decor.getRootView();
        int x = (int) ev.getRawX(), y = (int) ev.getRawY();
        if (root == null || hitWebView(root, x, y)) {
            return;     // 指尖落在 web 表面上 → 这页的界面不是本 App 画的，不冒字
        }
        View hit = hitView(root, x, y);
        if (hit == null) {
            return;
        }
        lastSpawnAt = now;
        try {
            spawn(hit, x, y);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 挂到"会滚的那一层"

    /**
     * 找这行字该挂在谁身上：往上找第一个 {@code ScrollView} / {@code NestedScrollView}，
     * 用它的内容层当宿主 —— 内容层会随滚动整体位移，挂在它 overlay 上的字自然跟着走。
     * 找不到可滚容器（弹窗、固定面板）就退回根视图，至少还能浮着飘完。
     */
    private static Anchor anchorFor(View hit, int winX, int winY) {
        for (View v = hit; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (isScrollContainer(v) && ((ViewGroup) v).getChildCount() > 0) {
                View content = ((ViewGroup) v).getChildAt(0);
                if (content instanceof ViewGroup) {
                    int[] loc = new int[2];
                    content.getLocationInWindow(loc);
                    return new Anchor((ViewGroup) content, winX - loc[0], winY - loc[1]);
                }
                // 内容层不是 ViewGroup（挂不住 overlay）：不再往上找，交给下面的兜底
            }
        }
        View root = hit.getRootView();
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        int[] loc = new int[2];
        root.getLocationInWindow(loc);
        return new Anchor((ViewGroup) root, winX - loc[0], winY - loc[1]);
    }

    private static boolean isScrollContainer(View v) {
        return v instanceof ScrollView || v instanceof androidx.core.widget.NestedScrollView;
    }

    private static final class Anchor {
        /** 挂 overlay 的宿主。必须是 ViewGroup —— {@code ViewOverlay} 只收 Drawable，
         *  能收 View 的是 {@code ViewGroupOverlay}。 */
        final ViewGroup host;
        final int x;
        final int y;

        Anchor(ViewGroup host, int x, int y) {
            this.host = host;
            this.x = x;
            this.y = y;
        }
    }

    // ---------------------------------------------------------------- 冒字

    private void spawn(View hit, int winX, int winY) {
        Anchor anchor = anchorFor(hit, winX, winY);
        if (anchor == null) {
            return;
        }
        final ViewGroup host = anchor.host;
        Resources res = host.getResources();
        float density = res.getDisplayMetrics().density;
        Entry entry = nextEntry(res);
        boolean word = entry.source == null;

        SpannableStringBuilder sb = new SpannableStringBuilder(entry.text);
        if (!word) {
            sb.append('\n').append(entry.source);
            int from = entry.text.length() + 1;
            sb.setSpan(new RelativeSizeSpan(0.72f), from, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xB3D8DCE0), from, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        final TextView line = new TextView(host.getContext());
        line.setText(sb);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, word ? 23 : 14.5f);
        line.setTypeface(word ? Typeface.SERIF : Typeface.SANS_SERIF);
        line.setLetterSpacing(word ? 0.16f : 0.01f);
        // 每条随机换色；出处那行固定淡灰，免得整块都在闪
        line.setTextColor(PALETTE[RANDOM.nextInt(PALETTE.length)]);
        line.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        line.setShadowLayer(12 * density, 0f, 1.5f * density, 0x99000000);
        line.setClickable(false);
        line.setFocusable(false);

        int hostW = Math.max(host.getWidth(), 1);
        int maxW = Math.max((int) (140 * density), hostW - (int) (40 * density));
        line.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = line.getMeasuredWidth();
        int h = line.getMeasuredHeight();
        // 点哪就在哪：文字中心落在指尖，再夹回内容里
        int left = clamp(anchor.x - w / 2, (int) (12 * density), Math.max((int) (12 * density), hostW - w - (int) (12 * density)));
        int top = clamp(anchor.y - h / 2, (int) (8 * density), Math.max((int) (8 * density), host.getHeight() - h - (int) (8 * density)));
        // overlay 不参与布局，位置要自己摆
        line.layout(left, top, left + w, top + h);
        host.getOverlay().add(line);
        alive++;

        long inMs = 220;
        long holdMs = word ? 1250 : 1750;
        long outMs = 1000;
        final long life = inMs + holdMs + outMs;
        float rise = (word ? 110 : 84) * density;
        float sway = 15 * density;

        line.setAlpha(0f);
        android.animation.ObjectAnimator in =
                android.animation.ObjectAnimator.ofFloat(line, "alpha", 0f, 1f);
        in.setDuration(inMs);
        in.setInterpolator(new DecelerateInterpolator(1.2f));
        in.start();
        android.animation.ObjectAnimator up =
                android.animation.ObjectAnimator.ofFloat(line, "translationY", 0f, -rise);
        up.setStartDelay(inMs + holdMs);
        up.setDuration(outMs);
        up.setInterpolator(new DecelerateInterpolator(1.2f));
        up.start();
        android.animation.ObjectAnimator out =
                android.animation.ObjectAnimator.ofFloat(line, "alpha", 1f, 0f);
        out.setStartDelay(inMs + holdMs);
        out.setDuration(outMs);
        out.start();
        // 左右轻摆走完整条生命，匀速 —— 一加速就像被弹出来的
        android.animation.ObjectAnimator swayAnim = android.animation.ObjectAnimator.ofFloat(line,
                "translationX", 0f, sway, -sway * 0.7f, sway * 0.45f, 0f);
        swayAnim.setDuration(life);
        swayAnim.setInterpolator(new LinearInterpolator());
        swayAnim.start();

        UI.postDelayed(() -> {
            host.getOverlay().remove(line);
            host.invalidate();
            alive = Math.max(0, alive - 1);
        }, life + 80);
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(v, hi));
    }

    // ---------------------------------------------------------------- 命中判定

    /**
     * 指尖这一点是不是落在 web 表面上 —— <b>顶层命中优先</b>，不是"树里有没有 WebView"。
     *
     * <p>bb 模型转 OBJ 那页把 Blockbench 内核用满屏 WebView 承载（0 尺寸会让 WebGL 上下文创建失败），
     * 但上面盖着一层不透明的原生面板：点在面板上算我们自己的界面，点在露出来的 web 上不算。</p>
     */
    private static boolean hitWebView(View v, int winX, int winY) {
        View hit = hitView(v, winX, winY);
        for (View p = hit; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p instanceof android.webkit.WebView) {
                return true;
            }
        }
        return false;
    }

    /** 真正接住这一点的、最深的那个 View（后画的在上层，先判；不在屏幕上则返回 null）。 */
    private static View hitView(View v, int winX, int winY) {
        if (v.getVisibility() != View.VISIBLE || !containsPoint(v, winX, winY)) {
            return null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && containsPoint(child, winX, winY)) {
                    View deeper = hitView(child, winX, winY);
                    return deeper != null ? deeper : child;
                }
            }
        }
        return v;
    }

    private static boolean containsPoint(View v, int winX, int winY) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return winX >= loc[0] && winX < loc[0] + v.getWidth()
                && winY >= loc[1] && winY < loc[1] + v.getHeight();
    }

    // ---------------------------------------------------------------- 取词（抽干再洗牌）

    private static final class Entry {
        final String text;
        final String source;

        Entry(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    private Entry nextEntry(Resources res) {
        String[] values = res.getStringArray(R.array.core_values);
        String[] quotes = res.getStringArray(R.array.core_quote_texts);
        String[] sources = res.getStringArray(R.array.core_quote_sources);
        int total = values.length + quotes.length;
        if (total <= 0) {
            return new Entry("社会主义核心价值观", null);
        }
        if (BAG.isEmpty()) {
            for (int i = 0; i < total; i++) {
                BAG.add(i);
            }
            Collections.shuffle(BAG, RANDOM);
        }
        int pick = BAG.remove(BAG.size() - 1);
        if (pick < values.length) {
            return new Entry(values[pick], null);
        }
        int q = pick - values.length;
        if (q >= quotes.length) {
            return new Entry(quotes[quotes.length - 1], null);
        }
        String src = q < sources.length ? sources[q] : null;
        return new Entry(quotes[q], src == null || src.trim().isEmpty() ? null : src);
    }
}
