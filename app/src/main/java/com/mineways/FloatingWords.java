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
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
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
 * <p>按用户提的几条重做：点哪就在哪出（文字中心落在指尖）、每条随机换色、抽干再洗牌的<b>不重复</b>、
 * 可以连着点（只留 200ms 冷却 + 同屏 7 条上限）、<b>滑动时不消失</b>；生效范围是<b>本 App 自己画的
 * 界面</b> —— 由 {@link MinewaysApp} 统一给每个 Activity 装，包括底部四个分类页、关于页和每个自建
 * 功能页。<b>指尖落在 web 表面上时不生效</b>（剪辑器、Blockbench、粒子编辑器、云间列车 WebGL、
 * 网页工具、授权页）：判据是"这一点被哪个 View 接住"，不是"树里有没有 WebView"，也不是记类名 ——
 * bb 转 OBJ 那页就有一个被原生面板盖住的满屏内核 WebView，按树判会误杀它。</p>
 *
 * <p>文案在 {@code res/values/core_values.xml}，收录标准写在那份注释里。</p>
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
            return;     // 指尖落在 web 表面上（剪辑器、Blockbench、粒子、WebGL 页…）→ 不冒字
        }
        lastSpawnAt = now;
        try {
            spawn(root, x, y);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 指尖这一点是不是落在 web 表面上 —— <b>顶层命中优先</b>，不是"树里有没有 WebView"。
     *
     * <p>为什么不能只看"树里有没有"：bb 模型转 OBJ 那页把 Blockbench 内核用<b>满屏 WebView</b> 承载
     * （0 尺寸会让 WebGL 上下文创建失败），但上面盖了一层不透明的原生面板 —— 那页的界面是
     * 本 App 画的，该冒字。按命中判就自然对了：点在原生面板上算我们的，点在露出来的 web 上不算。</p>
     */
    private static boolean hitWebView(View v, int winX, int winY) {
        if (v.getVisibility() != View.VISIBLE || !containsPoint(v, winX, winY)) {
            return false;
        }
        if (v instanceof android.webkit.WebView) {
            return true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {   // 后画的在上层，先判
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && containsPoint(child, winX, winY)) {
                    return hitWebView(child, winX, winY);
                }
            }
        }
        return false;
    }

    private static boolean containsPoint(View v, int winX, int winY) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return winX >= loc[0] && winX < loc[0] + v.getWidth()
                && winY >= loc[1] && winY < loc[1] + v.getHeight();
    }

    // ---------------------------------------------------------------- 冒字

    private void spawn(View root, int tapX, int tapY) {
        if (!(root instanceof FrameLayout)) {
            return;
        }
        final FrameLayout layer = (FrameLayout) root;
        int screenW = layer.getWidth();
        int screenH = layer.getHeight();
        if (screenW <= 0 || screenH <= 0) {
            return;
        }

        Resources res = layer.getResources();
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

        final TextView line = new TextView(layer.getContext());
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

        int maxW = Math.max(px(density, 140), screenW - px(density, 40));
        line.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = line.getMeasuredWidth();
        int h = line.getMeasuredHeight();
        // 点哪就在哪：文字中心落在指尖，再夹回屏内
        int left = clamp(tapX - w / 2, px(density, 12), screenW - w - px(density, 12));
        int top = clamp(tapY - h / 2, px(density, 8), screenH - h - px(density, 8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        layer.addView(line, lp);
        alive++;

        long inMs = 220;
        long holdMs = word ? 1250 : 1750;
        long outMs = 1000;
        final long life = inMs + holdMs + outMs;
        float rise = px(density, word ? 110 : 84);
        float sway = px(density, 15);

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

        UI.postDelayed(() -> remove(layer, line), life + 80);
    }

    private void remove(FrameLayout layer, TextView line) {
        if (line.getParent() == layer) {
            layer.removeView(line);
            alive = Math.max(0, alive - 1);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(v, hi));
    }

    private static int px(float density, int dp) {
        return (int) (dp * density + 0.5f);
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
