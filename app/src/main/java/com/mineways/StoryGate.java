package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureGate;

import java.util.ArrayList;
import java.util.List;

/**
 * 故事墙的显示判定：<b>机型 + 系统版本 + 本地是否装了某个包</b>，三条同时满足才显示。
 *
 * <p>全部走云控 FLAG，任何一条没配 = 不限制；读不到配置一律放行（fail-open）。
 *
 * <pre>
 * FLAG key=story_off to=1                     # 总闸，整条横幅 + 页面都不给
 * FLAG key=story_hide to=1001,1002            # 按登录 uid 藏（管理账号用这条）
 * FLAG key=story_models to=K70*,Xiaomi 14     # 机型，逗号/空格分隔，支持 * 通配
 * FLAG key=story_sdk_min to=30                # 系统版本，只写一端就是开区间
 * FLAG key=story_sdk_max to=99
 * FLAG key=story_pkg to=com.xxx.launcher      # 装了这些包才显示
 * FLAG key=story_pkg_all to=1                 # 1 = 必须全部装；默认任一
 * FLAG key=story_pkg_none to=com.yyy.app      # **装了任一个就隐藏**（「没装某软件的人才给看」）
 * FLAG key=story_pkg_none_all to=1            # 1 = 全部装了才隐藏；默认命中一个就藏
 * FLAG key=story_pkg_strict to=1              # 1 = 读不到包列表就当"没装"（默认 fail-open）
 * </pre>
 *
 * <h3>关于"申请读取已安装应用"</h3>
 * {@code QUERY_ALL_PACKAGES} 是<b>普通权限</b>（protectionLevel=normal），
 * 写进清单就随安装授予，<b>没有运行时弹窗</b>，{@code requestPermissions} 对它无效 ——
 * 小米 / OPPO / vivo 都是这个行为。只有部分华为 / 荣耀机型在安全中心里另有一个
 * 「读取应用列表」开关，那也不是标准权限弹窗。
 *
 * <p>所以这里不做"申请"，只做<b>检测 + 引导</b>：配了 {@code story_pkg} 或 {@code story_pkg_none}、
 * 但枚举到的应用数少得离谱（Android 11+ 被可见性限制）时，弹一次说明并给一个
 * 跳应用详情的按钮。一次进程只弹一次，答过就记住。
 */
public final class StoryGate {

    private static final String PREFS = "aff_story";
    private static final String K_ASKED = "pkg_asked";

    /** 枚举到的应用数少于这个值，就认为可见性被限制（正常设备都是上百个）。 */
    private static final int VISIBLE_ENOUGH = 20;

    /** 缓存"可见性是否受限"，避免每次组合都走一遍 binder。 */
    private static volatile Boolean sSuspicious;

    private StoryGate() {
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 首页横幅 / 故事页是否对当前用户显示。
     *
     * <p><b>默认是隐藏的</b>：不下发 {@code story_on=1} 之前，这条入口谁也看不见。
     * 其它条件（账号 / 机型 / 系统版本 / 已装包）都是在「明确打开了」之后的二次过滤 ——
     * 想只给一部分人看，就再配 story_hide / story_models / story_pkg 这些。
     */
    public static boolean allowed(Context c) {
        if (c == null) {
            return false;
        }
        FeatureGate g = CloudCatalog.gate();
        // 没有明确的云控命令 → 一律隐藏（这是刻意的默认值，不是漏判）
        if (!"1".equals(flag(g, "story_on", ""))) {
            return false;
        }
        if ("1".equals(flag(g, "story_off", ""))) {
            return false;                                   // 总闸（临时下架用）
        }
        if (StoryStore.hiddenFor(c, flag(g, "story_hide", ""))) {
            return false;                                   // 按账号藏
        }
        if (!modelOk(flag(g, "story_models", ""))) {
            return false;                                   // 机型
        }
        if (!sdkOk(flag(g, "story_sdk_min", ""), flag(g, "story_sdk_max", ""))) {
            return false;                                   // 系统版本
        }
        if (!pkgOk(c, flag(g, "story_pkg", ""),
                "1".equals(flag(g, "story_pkg_all", "")),
                "1".equals(flag(g, "story_pkg_strict", "")))) {
            return false;                                   // 装了指定包才给看
        }
        return noneOk(c, flag(g, "story_pkg_none", ""),
                "1".equals(flag(g, "story_pkg_none_all", "")),
                "1".equals(flag(g, "story_pkg_strict", "")));
    }

    /** 机型：没配 = 不限制；命中任一身份（内部型号 / 显示名）即可。 */
    private static boolean modelOk(String spec) {
        if (spec == null || spec.trim().length() == 0) {
            return true;
        }
        try {
            return DeviceId.hitAny(spec);
        } catch (Throwable t) {
            return true;                                    // 读不到机型 = 不拦
        }
    }

    /** 系统版本：两端都没配 = 不限制；只填一端按开区间。 */
    private static boolean sdkOk(String minS, String maxS) {
        boolean hasMin = minS != null && minS.trim().length() > 0;
        boolean hasMax = maxS != null && maxS.trim().length() > 0;
        if (!hasMin && !hasMax) {
            return true;
        }
        int sdk = Build.VERSION.SDK_INT;
        int min = hasMin ? intOf(minS, 0) : 0;
        int max = hasMax ? intOf(maxS, Integer.MAX_VALUE) : Integer.MAX_VALUE;
        return sdk >= min && sdk <= max;
    }

    /**
     * 包名：没配 = 不限制。
     *
     * <p>一个都没查到时要分两种情况，不能一概而论：
     * <ul>
     *   <li>能枚举出很多应用 → 可见性正常 → 确实没装 → 隐藏；</li>
     *   <li>枚举出来只有一两个 → Android 11+ 可见性被限制 → 默认放行（fail-open），
     *       免得因为读不到而把入口整个弄丢；{@code story_pkg_strict=1} 时反过来按"没装"处理。</li>
     * </ul>
     */
    private static boolean pkgOk(Context c, String spec, boolean all, boolean strict) {
        List<String> list = split(spec);
        if (list.isEmpty()) {
            return true;
        }
        return StoryPackages.verdict(countHit(c, list), list.size(), false, all, strict,
                visibilitySuspicious(c));
    }

    /**
     * 反向条件：{@code story_pkg_none} —— <b>装了其中任意一个就把入口藏起来</b>。
     *
     * <p>典型用法就是「没装某两个创作软件的人才有故事墙看」。读不到应用列表时按「没装」处理，
     * 也就是<b>照常显示</b>：这一档的默认人群是「大多数人」，不该因为系统限制把入口弄丢。
     */
    private static boolean noneOk(Context c, String spec, boolean all, boolean strict) {
        List<String> list = split(spec);
        if (list.isEmpty()) {
            return true;
        }
        return StoryPackages.verdict(countHit(c, list), list.size(), true, all, strict,
                visibilitySuspicious(c));
    }

    private static int countHit(Context c, List<String> list) {
        int hit = 0;
        for (String p : list) {
            if (installed(c, p)) {
                hit++;
            }
        }
        return hit;
    }

    private static boolean installed(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 可见性

    /** Android 11+ 是否被包可见性限制（枚举到的应用少得离谱）。 */
    static boolean visibilitySuspicious(Context c) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;                                   // 11 以下不需要这权限
        }
        Boolean cached = sSuspicious;
        if (cached != null) {
            return cached;
        }
        boolean v;
        try {
            v = c.getPackageManager().getInstalledPackages(0).size() < VISIBLE_ENOUGH;
        } catch (Throwable t) {
            v = true;
        }
        sSuspicious = v;
        return v;
    }

    /**
     * 配了 {@code story_pkg} 但读不到应用列表时，弹一次说明。
     *
     * <p>这不是"申请权限"——那个权限申请不了。只是把情况说清楚，
     * 并给一个跳到应用详情的入口（部分华为/荣耀的「读取应用列表」开关在那里）。
     */
    public static void maybeAskPackageAccess(final Activity a) {
        if (a == null || a.isFinishing()) {
            return;
        }
        try {
            FeatureGate g = CloudCatalog.gate();
            if ("1".equals(flag(g, "story_off", ""))) {
                return;
            }
            if (flag(g, "story_pkg", "").length() == 0
                    && flag(g, "story_pkg_none", "").length() == 0) {
                return;                                     // 正向反向都没配包名，不打扰
            }
            if (!visibilitySuspicious(a)) {
                return;                                     // 能正常读到，不用问
            }
            final SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean(K_ASKED, false)) {
                return;                                     // 问过一次就够了
            }
            p.edit().putBoolean(K_ASKED, true).apply();

            new AlertDialog.Builder(a)
                    .setTitle("读取已安装应用")
                    .setMessage("故事墙要先确认本机装了哪些应用，才能决定给不给你看。\n\n"
                            + "Android 11 起系统默认不让我们看到别的应用。本应用已声明 "
                            + "QUERY_ALL_PACKAGES，正常情况下安装时就生效，不需要你操作。\n\n"
                            + "如果这里一直提示，可以到「设置 → 应用 → 本应用 → 权限」里看看有没有"
                            + "「读取应用列表」这一项（部分华为 / 荣耀机型有这个开关；小米 / OPPO / vivo "
                            + "一般没有，声明即生效）。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            try {
                                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                i.setData(Uri.fromParts("package", a.getPackageName(), null));
                                a.startActivity(i);
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .setNegativeButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String flag(FeatureGate g, String key, String def) {
        return com.mineways.cloud.CloudFlags.str(g, key, def);
    }

    private static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String s : spec.split("[,，\\s]+")) {
            if (s != null && s.trim().length() > 0) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
