package com.mineways.guardlink;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.mineways.AppLog;
import com.zeus.guardlink.GuardLinkCore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍防火墙：保护窗口内静默掐掉「不可信」的无障碍服务，解除时原样还原。
 *
 * <p>为什么必须有这一层：拿到无障碍权限的应用可以用 {@code dispatchGesture} 注入一段
 * <b>时长可控的假手指</b> —— 这样连「按压 ≥40ms」的真人鉴定都能骗过去，确认窗就会被恶意软件
 * 自己点掉。所以 FIRED 之后先把它的能力收掉，再谈确认。
 *
 * <p>信任口径完全听内核的 {@link GuardLinkCore.A11yPolicy}：真系统包 + 大厂/银行/输入法/OEM 放行，
 * 只掐无名小厂。全程静默：不弹通知、不响铃，用户无感。
 *
 * <p>相对上游 demo 多做了三件事，都是「装进本 App 才会遇到」的：
 * <ol>
 *   <li><b>绝不掐自己。</b>demo 按前缀判信任，本 App 的包名 {@code com.mineways} 不在可信前缀里 ——
 *       照抄就会把守护自己的无障碍服务掐掉，信号源与「踢回主界面」同时失效，
 *       防火墙反而把防盗器断了电。</li>
 *   <li><b>快照落盘。</b>掐着的时候进程被系统回收/用户划掉，内存快照就没了，别人的服务会永久被关。
 *       这里把被掐列表写进 SharedPreferences，下次守护一起来先补还原。</li>
 *   <li><b>还原时合并当前列表。</b>保护期间用户自己新开的服务不能被覆盖掉。</li>
 * </ol>
 */
public final class GuardA11y {

    private static final String PREF = "guardlink_a11y";
    private static final String KEY_SNAPSHOT = "suspended";

    private static final Set<String> suspended = new LinkedHashSet<>();
    private static volatile boolean active = false;

    private GuardA11y() {
    }

    /**
     * 掐。返回一句话结果（含「为什么没掐」），调用方负责显示。
     */
    public static synchronized String suspend(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!GuardFlags.a11yFirewall(app)) {
            return skip("无障碍防火墙已关闭（guard_a11y_fw=0）");
        }
        if (!GuardShell.ready()) {
            return skip("Shizuku 未授权，无法安全改写无障碍列表（只能提示，不会硬来）");
        }
        if (active) {
            return "已在保护窗口内（不重复掐）";
        }
        String cur = GuardProbe.enabledAccessibility(app);
        if (cur == null || cur.length() == 0) {
            return "当前没有其他无障碍服务在跑，无需掐";
        }
        PackageManager pm = app.getPackageManager();
        String own = app.getPackageName();
        Set<String> keep = new LinkedHashSet<>();
        Set<String> cut = new LinkedHashSet<>();
        for (String flat : cur.split(":")) {
            if (flat == null || flat.trim().length() == 0) {
                continue;
            }
            String one = flat.trim();
            int slash = one.indexOf('/');
            String pkg = slash >= 0 ? one.substring(0, slash) : one;
            if (pkg.equals(own)) {
                keep.add(one);                       // 守护自己必须留着：信号源 + 踢回主界面都靠它
                continue;
            }
            Boolean realSystem = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                realSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Throwable ignored) {
                // 查不到就按前缀判，判不出来就当不可信
            }
            if (GuardLinkCore.A11yPolicy.isTrusted(pkg, realSystem)) {
                keep.add(one);
            } else {
                cut.add(one);
            }
        }
        if (cut.isEmpty()) {
            return "在跑的无障碍全部属于可信名单（大厂/系统/输入法），一条没掐";
        }
        GuardShell.Result r = GuardShell.writeA11yServices(app, join(keep));
        if (!r.ok) {
            return "掐失败：" + r.detail;
        }
        suspended.clear();
        suspended.addAll(cut);
        active = true;
        saveSnapshot(app, cut);
        AppLog.i("GuardLink", "无障碍防火墙掐掉 " + cut.size() + " 条：" + cut);
        GuardRuntime.appendLog("🛡 无障碍防火墙：静默暂停 " + cut.size() + " 个不可信无障碍服务（大厂/系统/输入法未动）");
        return "已静默暂停 " + cut.size() + " 个不可信无障碍服务";
    }

    /** 还原：把被掐的并回当前列表一起写回，用户保护期间新开的服务不丢。 */
    public static synchronized String restore(Context ctx) {
        Context app = ctx.getApplicationContext();
        Set<String> pending = new LinkedHashSet<>(suspended);
        if (pending.isEmpty()) {
            pending.addAll(loadSnapshot(app));        // 进程换过，靠落盘的快照补还原
        }
        if (pending.isEmpty()) {
            active = false;
            return "没有被本守护掐掉的无障碍服务，无需还原";
        }
        if (!GuardShell.ready()) {
            return "Shizuku 未授权，暂时无法还原（快照已留存，授权后自动补）";
        }
        String cur = GuardProbe.enabledAccessibility(app);
        Set<String> merged = new LinkedHashSet<>(pending);
        if (cur != null) {
            for (String flat : cur.split(":")) {
                if (flat != null && flat.trim().length() > 0) {
                    merged.add(flat.trim());          // 还原期间用户新开的服务不丢
                }
            }
        }
        GuardShell.Result r = GuardShell.writeA11yServices(app, join(merged));
        if (!r.ok) {
            return "还原失败：" + r.detail;
        }
        suspended.clear();
        clearSnapshot(app);
        active = false;
        AppLog.i("GuardLink", "无障碍防火墙还原 " + pending.size() + " 条");
        GuardRuntime.appendLog("🛡 无障碍防火墙：已原样还原 " + pending.size() + " 个服务，用户无感");
        return "已还原 " + pending.size() + " 个被暂停的无障碍服务";
    }

    /** 给界面看：此刻是否处于「掐着没还原」的状态（内存位；跨进程的遗留看 suspendedList）。 */
    public static synchronized boolean isActive() {
        return active;
    }

    /** 现在暂停着哪几条（内存没有就读落盘快照，跨进程也能答）。 */
    public static synchronized List<String> suspendedList(Context ctx) {
        List<String> out = new ArrayList<>(suspended);
        if (out.isEmpty()) {
            out.addAll(loadSnapshot(ctx));
        }
        return out;
    }

    /** 进程一起来就检查有没有「上次掐了还没还原」的遗留，有就补上。 */
    public static synchronized void repairIfLeftover(Context ctx) {
        Context app = ctx.getApplicationContext();
        List<String> left = loadSnapshot(app);
        if (left.isEmpty()) {
            return;
        }
        GuardRuntime.appendLog("⚠ 发现上次暂停的 " + left.size()
                + " 个无障碍服务还没还原（进程被回收过），现在补还原");
        restore(app);
    }

    // ---------------------------------------------------------------- 内部

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String skip(String why) {
        AppLog.i("GuardLink", "无障碍防火墙未执行：" + why);
        return why;
    }

    private static String join(Set<String> flats) {
        StringBuilder sb = new StringBuilder();
        for (String s : flats) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static void saveSnapshot(Context ctx, Set<String> cut) {
        StringBuilder sb = new StringBuilder();
        for (String s : cut) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        prefs(ctx).edit().putString(KEY_SNAPSHOT, sb.toString()).apply();
    }

    private static void clearSnapshot(Context ctx) {
        prefs(ctx).edit().remove(KEY_SNAPSHOT).apply();
    }

    private static List<String> loadSnapshot(Context ctx) {
        List<String> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_SNAPSHOT, "");
        if (raw == null || raw.trim().length() == 0) {
            return out;
        }
        for (String s : raw.split("\n")) {
            if (s != null && s.trim().length() > 0) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
