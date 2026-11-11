package com.mineways.guardlink;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读探针：前台归属、网络是否活着、无障碍在不在位、UsageStats 授权有没有。
 *
 * <p>前台包一律走 {@link UsageStatsManager}（系统记录，第三方伪造不了），不用
 * {@code ActivityManager} 那条早就被限死的路；拿不到授权就返回 null，让调用方
 * 把「媒体豁免」退化成不豁免 —— 宁可多报一次，不可漏报。
 */
public final class GuardProbe {

    private GuardProbe() {
    }

    /** 最近 10 秒内最后一个进入前台的包；没授权或没记录返回 null。 */
    public static String lastResumedPkg(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 10_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            int resumed = Build.VERSION.SDK_INT >= 29
                    ? UsageEvents.Event.ACTIVITY_RESUMED : UsageEvents.Event.MOVE_TO_FOREGROUND;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == resumed && e.getPackageName() != null) {
                    last = String.valueOf(e.getPackageName());
                }
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    /** WiFi 或蜂窝任一在用即算活着（断网保护期间的「被反恢复」对账就靠它）。 */
    public static boolean networkAlive(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前有没有音频在放（媒体豁免的第一路信号）。 */
    public static boolean musicActive(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前处于 PLAYER_STATE_STARTED 的播放器所属包名。
     *
     * <p>为什么不能只靠 {@link #musicActive} + {@link #lastResumedPkg}：小窗/后台播放时
     * 音频开始那一刻的前台可能是桌面或聊天软件，UsageStats 抓到的「音频主人」和真正在放的
     * 播放器对不上 —— 于是播放器自己的界面变化被当成「无人操控的异动」，看着视频就被断网。
     * 播放器配置由系统音频服务维护，与前台是谁无关，按 uid 反查包名即可。
     */
    public static java.util.Set<String> activePlaybackPackages(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (Build.VERSION.SDK_INT < 26) {
            return out;
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            if (am == null || pm == null) {
                return out;
            }
            for (Object c : am.getActivePlaybackConfigurations()) {
                if (c == null) {
                    continue;
                }
                // AudioPlaybackConfiguration 的 getClientUid()/isActive() 在 SDK 里没有公开，
                // 只能反射读；读不到就退回「有播放器就算媒体在场」（不按包豁免，仍按 owner 兜底）。
                try {
                    java.lang.reflect.Method activeM = c.getClass().getMethod("isActive");
                    activeM.setAccessible(true);
                    if (!(Boolean) activeM.invoke(c)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // 没有 isActive()：当作在场
                }
                int uid;
                try {
                    java.lang.reflect.Method uidM = c.getClass().getMethod("getClientUid");
                    uidM.setAccessible(true);
                    uid = (Integer) uidM.invoke(c);
                } catch (Throwable ignored) {
                    continue;
                }
                if (uid < 0) {
                    continue;
                }
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null) {
                    java.util.Collections.addAll(out, pkgs);
                }
            }
        } catch (Throwable ignored) {
            // 有些 ROM 把这条读取掐了 —— 返回空集合，退回原来的「只认音频主人」
        }
        return out;
    }

    /** 本 App 的无障碍服务是否已在系统设置里启用。 */
    public static boolean accessibilityEnabled(Context ctx) {
        String s = enabledAccessibility(ctx);
        return s != null && s.contains(ctx.getPackageName() + "/");
    }

    /** 系统里当前启用的无障碍服务串（溯源第二步要用它判断「谁现在真的具备操控能力」）。 */
    public static String enabledAccessibility(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 系统里当前启用的通知监听服务串（形状与无障碍一致：{@code pkg/cls(:pkg/cls)*}）。 */
    public static String enabledNotificationListeners(Context ctx) {
        try {
            // 常量名在新 SDK 里被标成隐藏 API，用字面量最稳（值自 API 21 未变）
            return Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前默认短信应用；读不到返回 null。能读全部验证码，是最贵的角色之一。 */
    public static String defaultSmsPackage(Context ctx) {
        try {
            return android.provider.Telephony.Sms.getDefaultSmsPackage(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有桌面入口的包集合：没有入口却持危险能力，是隐藏型恶意应用的常见形态。 */
    public static java.util.Set<String> launcherPackages(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager()
                    .queryIntentActivities(main, 0)) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    out.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
            // 查不到就当「全都有入口」：宁可少给这条分，也不要凭一次查询失败把正常 App 打成隐藏包
        }
        return out;
    }

    /** 是否有 VPN 网络在用（流量经第三方应用转发，抓屏木马的常见配套）。 */
    public static boolean vpnActive(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写回设置时要用的扁平值：本服务在系统里的规范名字。 */
    public static String ownServiceFlat(Context ctx) {
        return ctx.getPackageName() + "/" + GuardAccessibilityService.class.getName();
    }

    @SuppressLint("WrongConstant")
    public static boolean usageStatsGranted(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                // 部分 ROM 把「有权限」表达成 default，跟随系统前台检测的可用性
                return mode == AppOpsManager.MODE_DEFAULT && canQueryUsage(ctx);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean canQueryUsage(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            return !usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3_600_000L, now).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统 launcher 的包名（进入主界面的判定，弹人工确认窗的触发条件）。 */
    public static String homePackage(Context ctx) {
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 所有能当桌面的包（用户装了第三方桌面时默认的那个只是其一）。
     * 降噪用：桌面 widget 每分钟自己刷新不能被当成「界面自行变化」的证据，
     * Lockdown 也不能去冻结桌面本身。
     */
    public static List<String> homePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager()
                    .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    addUnique(out, ri.activityInfo.packageName);
                }
            }
        } catch (Throwable t) {
            String one = homePackage(ctx);
            if (one != null) {
                out.add(one);
            }
        }
        return out;
    }

    /** 默认输入法 + 已启用输入法的包名（同上，输入法弹起/切换不该被当成作案）。 */
    public static List<String> imePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            addFlats(out, Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD));
            addFlats(out, Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS));
        } catch (Throwable ignored) {
            // 读不到就不加豁免：输入法事件本来就已被内核的 inputmethod 前缀白名单覆盖一部分
        }
        return out;
    }

    /** "pkg/service;pkg2/service2" 这种设置串里取包名。 */
    private static void addFlats(List<String> out, String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return;
        }
        for (String part : raw.split("[:;]")) {
            if (part == null || part.trim().length() == 0) {
                continue;
            }
            int slash = part.indexOf('/');
            addUnique(out, slash >= 0 ? part.substring(0, slash).trim() : part.trim());
        }
    }

    private static void addUnique(List<String> list, String v) {
        if (v != null && v.length() > 0 && !list.contains(v)) {
            list.add(v);
        }
    }
}
