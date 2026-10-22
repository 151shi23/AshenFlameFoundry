package com.mineways.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mineways.R;

/**
 * 全局界面挂载点：每个 Activity 建完界面后做两件事。
 *
 * <p>一、{@link MwViews#restyle(Activity)} —— 整棵视图树换成统一视觉（字阶、色阶、玻璃面板、
 * 输入框、按钮）。二、{@link #inject} —— 给工具页套一条悬浮玻璃顶栏（只有标题芯片）。
 *
 * <p>为什么挂在生命周期回调上而不是每个 Activity 里各调一次：这二十多个工具页各自手搓样式，
 * 逐个加调用既容易漏，又会让「新页面忘记接」变成常态。挂在这里 = 现在与以后所有页面自动同视觉。
 *
 * <p>时机：{@code onActivityCreated} 在 {@code setContentView} 之前触发，所以只往 content 上
 * post 一个动作，等这一帧铺完再动手；{@code onActivityResumed} 再补一次，覆盖异步搭界面的页面
 * （列表刷新、下载完成后加行）—— 重写只改视觉属性，注入有标记 id 防重复。
 *
 * <p>整套东西不能掀翻页面：所有动作裹在 try/catch 里，云控 {@code ui_restyle=0} 关整棵树的视觉重写、
 * {@code ui_page_glass=0} 关悬浮顶栏。<b>{@code ui_backdisc} 这个开关随悬浮返回盘一起删除了</b>
 * （不是留一个空转的开关），配置里再写它只是不被认识的一个 FLAG 键，不会再有任何效果。
 */
public final class MwChrome implements Application.ActivityLifecycleCallbacks {

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new MwChrome());
    }

    @Override
    public void onActivityCreated(@NonNull Activity act, @Nullable Bundle saved) {
        schedule(act);
    }

    @Override
    public void onActivityResumed(@NonNull Activity act) {
        schedule(act);
    }

    private void schedule(final Activity act) {
        View root = act.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.post(new Runnable() {
            @Override
            public void run() {
                try {
                    MwViews.Style st = MwViews.style(act);
                    if (st.restyle) {
                        MwViews.restyle(act);
                        inject(act);
                    }
                    askGlass(act);
                } catch (Throwable t) {
                    // 视觉层绝不能把页面弄崩：出错就保持页面原样，但必须留痕，
                    // 否则「整页白屏、什么都没有」这类问题无从排查。
                    android.util.Log.e("MwChrome",
                            "restyle/inject failed on " + act.getClass().getSimpleName(), t);
                }
            }
        });
    }

    /** 一次进程只问一次（云控命中 {@code ui_glass_models=…:ask} 且这台机器还没答过）。 */
    private static volatile boolean glassAsked;

    private void askGlass(Activity act) {
        if (glassAsked || !com.mineways.GlassPolicy.shouldAsk(act)) {
            return;
        }
        glassAsked = true;
        com.mineways.GlassPolicy.ask(act);
    }

    /**
     * 工具页悬浮顶栏（只有标题芯片）。首页与门槛页不注入。
     *
     * <p>以前这里还有两条兜底：一枚悬浮玻璃返回盘 + 一道顶部渐隐黑幕，现在**都删了** ——
     * 手机本身有返回键/返回手势，那枚圆既挡页面第一行操作又容易误触，行为还和系统返回完全重合
     * （{@code WebToolActivity.onBackPressed} 已经把返回键接成「页内后退、到底再退出」）；
     * 那道黑渐隐幕只是给它当衬底，盘没了幕也没意义。
     */
    private void inject(Activity act) {
        View content = act.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            return;
        }
        String name = act.getClass().getSimpleName();
        if ("MainActivity".equals(name) || "UpdateGateActivity".equals(name)
                || "CloudGateActivity".equals(name)) {
            return;
        }
        FrameLayout box = (FrameLayout) content;
        boolean hosted = false;
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof MwGlassRoot) {
                hosted = true;
                break;
            }
        }
        if (box.getTag(R.id.mw_overlay) != null && hosted) {
            return;     // 已经套过玻璃顶栏，别重复包一层
        }
        box.setTag(R.id.mw_overlay, Boolean.TRUE);
        // 剪辑 / Blockbench / 小游戏这类自绘界面在 MwPageGlass.SKIP 名单里，不套顶栏
        if (MwPageGlass.allowed(act)) {
            MwPageGlass.attach(act);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity act) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity act) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity act) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity act, @NonNull Bundle out) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity act) {
    }
}
