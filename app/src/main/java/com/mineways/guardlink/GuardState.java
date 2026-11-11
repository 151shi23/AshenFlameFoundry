package com.mineways.guardlink;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.AppLog;
import com.zeus.guardlink.GuardLinkCore;

/**
 * 守护状态的跨重启续接。
 *
 * <p>落盘四样：Lockdown 是否生效、静默模式、累计触发数、最近卸载的嫌疑包（复活监测要跨重启续）。
 *
 * <p>刻意<b>不</b>续的一件事：断网。上游说明里写得很清楚 —— 重启本身就让 {@code svc} 设置失效，
 * 而 iptables 规则随内核重启自然清空。守护醒来后不会自动再断一次，只把「保护 + 监测」接回来，
 * 等用户回来复核。自动化重断网会让「重启就能上网」这条逃生路消失，误判时代价太大。
 */
public final class GuardState {

    private static final String PREF = "guardlink_state";
    private static final String K_LOCKDOWN = "lockdown";
    private static final String K_SILENT = "silent";
    private static final String K_FIRES = "fireCount";
    private static final String K_UNINSTALLED = "lastUninstalled";
    private static final String K_SAVED_AT = "savedAt";

    private GuardState() {
    }

    /** 心跳按 guard_state_sec 的节拍调这条；关掉跨重启开关就不落盘，并抹掉旧的。 */
    public static void save(Context ctx, GuardLinkCore.GuardController c) {
        if (c == null) {
            return;
        }
        SharedPreferences p = prefs(ctx);
        if (!GuardFlags.bootRecover(ctx)) {
            p.edit().clear().apply();
            return;
        }
        p.edit()
                .putBoolean(K_LOCKDOWN, c.lockdown.isActive())
                .putBoolean(K_SILENT, c.isSilentMode())
                .putInt(K_FIRES, c.getFireCount())
                .putString(K_UNINSTALLED, c.lastUninstalledPkg)
                .putLong(K_SAVED_AT, System.currentTimeMillis())
                .apply();
    }

    /** 开机或重启守护后调用：把保护与监测接回来（不动网络）。 */
    public static String restore(Context ctx, GuardLinkCore.GuardController c) {
        if (c == null) {
            return "还没有控制器，无从恢复";
        }
        if (!GuardFlags.bootRecover(ctx)) {
            return "跨重启续状态已关闭（guard_boot=0）";
        }
        SharedPreferences p = prefs(ctx);
        if (!p.contains(K_SAVED_AT)) {
            return "没有需要恢复的历史状态";
        }
        boolean lockdown = p.getBoolean(K_LOCKDOWN, false);
        boolean silent = p.getBoolean(K_SILENT, false);
        int fires = p.getInt(K_FIRES, 0);
        String last = p.getString(K_UNINSTALLED, null);
        long at = p.getLong(K_SAVED_AT, 0L);

        if (lockdown) {
            c.lockdown.engage();
        }
        if (silent) {
            c.setSilentForRestore();
        }
        c.restoreFireCount(fires);
        if (last != null) {
            c.lastUninstalledPkg = last;
        }
        String line = "跨重启已接回：" + (lockdown ? "Lockdown 仍在生效" : "无 Lockdown")
                + " · 静默模式 " + (silent ? "开" : "关")
                + " · 累计触发 " + fires + " 次"
                + (last == null ? "" : " · 复活监测 " + last)
                + "（快照时间 " + GuardText.stamp(at) + "）"
                + " · 网络未自动重断，等你复核";
        AppLog.i("GuardLink", line);
        return line;
    }

    /** 用户主动停用守护：状态不该在下一次开机又被接回来。 */
    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** 界面显示用：上次落盘是什么时候、存了什么。 */
    public static String describe(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(K_SAVED_AT)) {
            return "还没落过盘（守护没跑过，或跨重启开关是关的）";
        }
        return "快照 " + GuardText.stamp(p.getLong(K_SAVED_AT, 0L))
                + " · Lockdown " + (p.getBoolean(K_LOCKDOWN, false) ? "生效中" : "无")
                + " · 静默 " + (p.getBoolean(K_SILENT, false) ? "开" : "关")
                + " · 累计触发 " + p.getInt(K_FIRES, 0) + " 次"
                + " · 最近卸载 " + or(p.getString(K_UNINSTALLED, null), "无");
    }

    public static long lastSavedAt(Context ctx) {
        return prefs(ctx).getLong(K_SAVED_AT, 0L);
    }

    private static String or(String v, String def) {
        return v == null || v.length() == 0 ? def : v;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
