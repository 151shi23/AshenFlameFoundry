package com.mineways.guardlink;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import com.mineways.AppLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 「最近任务里隐藏守护页」的执行层。
 *
 * <p>为什么要隐藏：他报的那条攻击是<b>把这张卡片从最近任务里一划</b> —— 进程没了，检测、保护、
 * 通知全部失效。常驻服务能把它拉回来，但「划得到」本身就是风险；卡片不在列表里，划不到。
 *
 * <p>为什么只动守护页那一条：整个 App 平时只有一条最近任务卡片，把那条标志位一改，
 * 工具箱首页的卡片也会一起消失，他自己都找不回入口（他明确否掉了「隐藏整个应用」）。
 * 所以这里按 {@code TaskInfo.topActivity} 过滤：<b>只有当前栈顶正是守护页的那条任务才动</b>，
 * 回到 App 内别的页面就把标志位清掉。守护页停在后台时（按 Home 离开）栈顶仍是它，该隐藏照样隐藏。
 *
 * <p>读不到栈顶就什么都不改，并把这句话原样显示在界面上 —— 这台 ROM 不提供任务信息，
 * 比假装隐藏成功诚实。
 */
public final class GuardRecents {

    public static final String GUARD_ACTIVITY = "com.mineways.guardlink.GuardLinkActivity";
    public static final String CONFIRM_ACTIVITY = "com.mineways.guardlink.GuardConfirmActivity";

    /** 一条最近任务卡片：能问出栈顶是谁，能把隐藏标志位写下去。 */
    public interface Task {
        /** 栈顶页面类名；ROM 不给时返回 {@code null}。 */
        String topClass();

        void exclude(boolean exclude);
    }

    /** 只有栈顶正是守护页的那条任务才归本功能管。 */
    public static boolean targets(String topClass) {
        return GUARD_ACTIVITY.equals(topClass);
    }

    /**
     * App 内别的页面回到前台时，要把卡片挂回去。
     *
     * <p>确认窗不算「别的页面」：它和守护页是同一条防线，且自己已在清单里写了
     * {@code excludeFromRecents}，不该因为它把守护页挂回去。
     */
    public static boolean shouldRestore(String resumedClass) {
        return resumedClass != null && resumedClass.length() > 0
                && !GUARD_ACTIVITY.equals(resumedClass)
                && !CONFIRM_ACTIVITY.equals(resumedClass);
    }

    /**
     * 逐个判断并下发，返回界面与事件流要显示的那句实话。
     *
     * <p>纯函数：{@code tasks} 由 Android 层灌进来，名单与判断能在 JVM 里断言。
     */
    public static String apply(List<Task> tasks, boolean hide) {
        if (tasks == null || tasks.isEmpty()) {
            return "这台 ROM 没给出本应用的最近任务条目，守护页卡片没动";
        }
        int touched = 0;
        int unknown = 0;
        for (Task t : tasks) {
            if (t == null) {
                continue;
            }
            String top = t.topClass();
            if (top == null || top.length() == 0) {
                unknown++;
                continue;
            }
            if (!targets(top)) {
                continue;               // 不是守护页那条卡片：工具箱首页的标志位绝不碰
            }
            t.exclude(hide);
            touched++;
        }
        if (touched > 0) {
            return (hide ? "已从最近任务摘掉 " : "已挂回最近任务 ") + touched + " 条守护页卡片"
                    + (unknown > 0 ? "（另有 " + unknown + " 条读不到栈顶，没碰）" : "");
        }
        if (unknown > 0) {
            return "系统没给出任务栈顶，无法只针对守护页，卡片没动";
        }
        return "当前没有以守护页为栈顶的任务，卡片没动";
    }

    /** Android 侧：把本 App 的任务条目包装成 {@link Task} 后交给纯函数。 */
    public static String applyTo(Context ctx, boolean hide) {
        if (Build.VERSION.SDK_INT < 21) {
            return "系统低于 5.0，运行期改不了最近任务";
        }
        List<Task> tasks = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.AppTask> list = am.getAppTasks();
                if (list != null) {
                    for (final ActivityManager.AppTask at : list) {
                        if (at == null) {
                            continue;
                        }
                        tasks.add(wrap(at));
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "读取最近任务条目失败", t);
        }
        return apply(tasks, hide);
    }

    private static Task wrap(final ActivityManager.AppTask at) {
        return new Task() {
            @Override
            public String topClass() {
                try {
                    ActivityManager.RecentTaskInfo info = at.getTaskInfo();
                    return info == null || info.topActivity == null
                            ? null : info.topActivity.getClassName();
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            public void exclude(boolean exclude) {
                try {
                    at.setExcludeFromRecents(exclude);
                } catch (Throwable t) {
                    AppLog.e("GuardLink", "设置最近任务隐藏失败", t);
                }
            }
        };
    }

    /**
     * 挂回用的生命周期钩子：App 内别的页面一回到前台，就把守护页那条卡片挂回去。
     *
     * <p>由守护页自己的 {@code onCreate} 注册、{@code onDestroy} 注销（{@link #unwatch}），
     * 只在这页活着期间起作用，不给全局生命周期加常驻负担。
     *
     * @return 注册好的钩子，交给 {@link #unwatch} 注销；系统版本不够时返回 {@code null}
     */
    public static Application.ActivityLifecycleCallbacks watchForRestore(final Activity owner) {
        Application app = owner.getApplication();
        if (app == null) {
            return null;
        }
        Application.ActivityLifecycleCallbacks hook =
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity act, Bundle saved) {
                    }

                    @Override
                    public void onActivityStarted(Activity act) {
                    }

                    @Override
                    public void onActivityResumed(Activity act) {
                        String cls = act == null ? null : act.getClass().getName();
                        if (!shouldRestore(cls)) {
                            return;
                        }
                        // 开关关了的时候本来就没隐藏，重复下发无害，但别白折腾
                        if (!GuardFlags.hideTaskOn(owner)) {
                            return;
                        }
                        applyTo(owner, false);
                    }

                    @Override
                    public void onActivityPaused(Activity act) {
                    }

                    @Override
                    public void onActivityStopped(Activity act) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity act, Bundle out) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity act) {
                    }
                };
        app.registerActivityLifecycleCallbacks(hook);
        return hook;
    }

    /** 注销 {@link #watchForRestore} 装的钩子（守护页销毁时必须调，否则漏一个生命周期监听器）。 */
    public static void unwatch(Activity owner, Application.ActivityLifecycleCallbacks hook) {
        if (hook == null) {
            return;
        }
        try {
            Application app = owner.getApplication();
            if (app != null) {
                app.unregisterActivityLifecycleCallbacks(hook);
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "注销最近任务钩子失败", t);
        }
    }

    private GuardRecents() {
    }
}
