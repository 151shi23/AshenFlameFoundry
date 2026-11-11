package com.mineways.guardlink;

import java.util.ArrayList;
import java.util.List;

/**
 * 华米 OV 这些 ROM 的「自启动 / 省电」入口。
 *
 * <p>写法上的老实话：各家 ROM 的这些页面<b>组件名不保证存在</b>，版本一变就换。
 * 所以这里不是「我知道华为的页面叫什么」，而是一份<b>候选顺序表 + 逐个试探</b>：
 * 打得开才算，打不开自动退到下一个，全都不行就退回通用的「应用信息」页，
 * 并在界面上说清楚最后打开的是哪一个 —— 绝不能假装打开了然后让用户自己在系统里瞎找。
 *
 * <p>探测动作留给 Android 层做（{@link Tester}），名单与顺序逻辑是纯的，能在 JVM 里断言。
 */
public final class GuardOem {

    /** 一次要打开的页面：{@code action} 与 {@code pkg/cls} 至少有一个能用。 */
    public static final class Page {
        public final String label;
        public final String action;
        public final String pkg;
        public final String cls;

        Page(String label, String action, String pkg, String cls) {
            this.label = label;
            this.action = action;
            this.pkg = pkg;
            this.cls = cls;
        }

        /** 界面与日志里说「打开的是哪个页面」用这句。 */
        public String describe() {
            if (pkg != null && pkg.length() > 0) {
                return label + "（" + pkg + "）";
            }
            return label + "（系统设置）";
        }
    }

    /** 通用兜底页：任何 Android 上都有。 */
    public static final String ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    /** 通用省电页（Android 6+ 公开设置页，不需要额外权限）。 */
    public static final String ACTION_BATTERY_OPTS =
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS";

    private static Page act(String label, String action) {
        return new Page(label, action, null, null);
    }

    private static Page cmp(String label, String pkg, String cls) {
        return new Page(label, null, pkg, cls);
    }

    /** 自启动/后台管理的候选顺序：先厂商专属页，最后通用应用信息页。 */
    public static final Page[] AUTOSTART = {
            cmp("小米/红米 自启动管理", "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            cmp("小米/红米 应用管理", "com.miui.securitycenter",
                    "com.miui.applicationmanagement.settings.ApplicationManagementSettings"),
            cmp("华为/荣耀 应用启动管理", "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cmp("华为/荣耀 权限管理", "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            cmp("荣耀 应用启动管理", "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cmp("OPPO/realme/一加 自启动管理", "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cmp("OPPO/realme/一加 自启动管理（新路径）", "com.oplus.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cmp("vivo/iQOO 后台高耗电与自启动", "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cmp("魅族 Flyme 自启动管理", "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"),
            act("通用：应用信息页（自己找自启动/省电项）", ACTION_APP_DETAILS),
    };

    /** 省电/电池优化的候选顺序。 */
    public static final Page[] BATTERY = {
            cmp("小米/红米 省电策略", "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            cmp("华为/荣耀 启动关联与省电", "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            cmp("OPPO/realme/一加 省电策略", "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            act("通用：忽略电池优化列表", ACTION_BATTERY_OPTS),
            act("通用：应用信息页", ACTION_APP_DETAILS),
    };

    /** 试探动作由 Android 层实现：真打得开才返回 true。 */
    public interface Tester {
        boolean tryStart(Page page);
    }

    /**
     * 按顺序试，返回第一个打得开的页面；全打不开返回 {@code null}（调用方要如实说）。
     *
     * <p>不猜、不硬指定第一个：ROM 改版后组件名换了也只是退回下一档，不会出现
     * 「点了没反应还以为手机坏了」。
     */
    public static Page probe(Page[] candidates, Tester t) {
        if (candidates == null || t == null) {
            return null;
        }
        for (Page p : candidates) {
            if (p == null) {
                continue;
            }
            try {
                if (t.tryStart(p)) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 打不开就是打不开，换下一个候选；这里不需要记日志，调用方会报最终结果
            }
        }
        return null;
    }

    /** 试过的候选名，给用户看「我试过哪些页」，省得以为我在敷衍。 */
    public static List<String> labels(Page[] candidates) {
        List<String> out = new ArrayList<>();
        if (candidates != null) {
            for (Page p : candidates) {
                if (p != null) {
                    out.add(p.label);
                }
            }
        }
        return out;
    }

    private GuardOem() {
    }
}
