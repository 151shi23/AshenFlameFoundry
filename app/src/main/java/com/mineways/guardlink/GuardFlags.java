package com.mineways.guardlink;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.cloud.CloudFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 守护的能力开关与运行节奏 —— 界面「直观显示、直观设置」的那一排开关，全以这份清单为唯一来源。
 *
 * <p>每条取值按三级优先：<b>云控下发 &gt; 本机设置 &gt; 出厂默认</b>。
 * 云控下发过就等于锁死（界面标「云控锁定」，本机点不动），这样既能一票强制关闭，
 * 也不会出现「界面上写着开着、实际云端早掐了」的两张皮。
 *
 * <p>{@code guard_on} 出厂就是 1：功能默认可用，用户在自己手机上开关键一拨就能值守；
 * 云端想停就下发 {@code guard_on=0}。其余出厂默认按「保护动作能救命、不可逆动作要收着」定：
 * 断网/冻结/精确断网/无障碍防火墙/静默退避/跨重启 默认开，<b>卸载默认关</b>。
 */
public final class GuardFlags {

    private static final String PREF = "guardlink_switch";

    public static final String ON = "guard_on";
    public static final String KILL_NET = "guard_kill_net";
    public static final String PER_APP = "guard_per_app";
    public static final String FREEZE = "guard_freeze";
    public static final String A11Y_FW = "guard_a11y_fw";
    public static final String SYS_AI = "guard_sys_ai";
    public static final String UNINSTALL = "guard_uninstall";
    public static final String TIMEOUT_CUT = "guard_timeout_cut";
    public static final String SILENT = "guard_silent";
    public static final String BOOT_RECOVER = "guard_boot";
    public static final String REGEN = "guard_regen";
    public static final String HIDE_TASK = "guard_hide_task";

    /** 一条可切换能力的展示信息。 */
    public static final class Switch {
        public final String key;
        public final String title;
        public final String note;
        public final boolean def;

        Switch(String key, String title, boolean def, String note) {
            this.key = key;
            this.title = title;
            this.def = def;
            this.note = note;
        }
    }

    private static final List<Switch> SWITCHES = new ArrayList<>();

    static {
        SWITCHES.add(new Switch(ON, "守护总闸", true,
                "关掉后不再检测、不再处置。出厂默认开着 —— 在你自己手机上拨一下就用，不需要等云端放行。"));
        SWITCHES.add(new Switch(KILL_NET, "允许切断网络", true,
                "准不准动 svc wifi/data disable。关掉后所有断网动作都不执行（含降级路径）；"
                        + "如果网络还断着，关掉这条会照快照自动恢复（只打开断之前就开着的那一样）。"));
        SWITCHES.add(new Switch(PER_APP, "精确断网优先", true,
                "先试 iptables owner match 只掐嫌疑应用自己的流量，其余应用联网无感；"
                        + "关掉或设备不支持时退回全局断网。"));
        SWITCHES.add(new Switch(FREEZE, "允许冻结嫌疑进程", true,
                "am force-stop：借道微信、后台外传这类底层调用随进程死亡一起失效。Lockdown 的核心手段。"));
        SWITCHES.add(new Switch(A11Y_FW, "无障碍防火墙", true,
                "保护窗口内静默掐掉不可信的无障碍服务（大厂/银行/输入法/系统自带一律放行），"
                        + "解除时原样还原。堵的是用 dispatchGesture 注入假手指骗确认窗这一条路。"));
        SWITCHES.add(new Switch(SILENT, "连续触发后静默", true,
                "连着炸到退避阈值就不再弹窗打扰，改为对嫌疑应用精确断网，打开本页即恢复弹窗。"
                        + "关掉后每次都弹。"));
        SWITCHES.add(new Switch(SYS_AI, "放行系统级 AI 助手", true,
                "小爱同学 / 鸿蒙小艺 / vivo 小 v / 荣耀 YOYO 这类「语音替你动手」的厂商助手，"
                        + "在信号上和远程操控一模一样（没人触摸、界面自己在动），判定层分不出来。"
                        + "开着时：当前持有已启用无障碍服务、且是系统应用或大厂包的执行者 "
                        + "—— 它的界面异动不算证据、Lockdown 期间不冻结它。"
                        + "关掉就回到严格模式（语音助手也会照常报警）。"));
        SWITCHES.add(new Switch(TIMEOUT_CUT, "确认超时按恶意", true,
                "倒计时内没有真人点击，是否按「确认恶意操控」断网。关掉后到点只维持 Lockdown 不断网。"));
        SWITCHES.add(new Switch(BOOT_RECOVER, "跨重启续状态", true,
                "Lockdown、静默模式、累计触发数、最近卸载包落盘，重启后由开机广播接回来。"
                        + "重启本身会让 svc 断网失效，这里只续保护与监测，不自动重断网。"));
        SWITCHES.add(new Switch(REGEN, "再生能力（常驻服务）", true,
                "挂一条最低优先级的常驻通知换进程活得住，并每 2 秒检查：守护该在而不在就重新起来、"
                        + "无障碍掉了就通过 Shizuku 自己补回去、任务被划掉立刻自拉。"
                        + "关掉后就只剩「开着页面才有效」，被划掉后台或省电掐死时不会自己回来。"));
        SWITCHES.add(new Switch(HIDE_TASK, "最近任务里隐藏守护页", true,
                "守护页与醒来确认窗不进最近任务列表：恶意软件划不到它，也就划不掉守护。"
                        + "只影响这两页，工具箱首页照常在。回本页走桌面图标或常驻通知。"));
        SWITCHES.add(new Switch(UNINSTALL, "允许卸载嫌疑应用", false,
                "pm uninstall --user 0。出厂默认关：开着也必须在溯源界面真人按压 + 二次确认才执行。"));
    }

    private GuardFlags() {
    }

    public static List<Switch> switches() {
        return Collections.unmodifiableList(SWITCHES);
    }

    // ---------------------------------------------------------------- 三级取值

    /** 云控是否下发过这条（下发过 = 锁死，本机改不动）。 */
    public static boolean cloudLocked(Context ctx, String key) {
        return CloudFlags.str(key, "").trim().length() > 0;
    }

    /** 当前这条听谁的：云控锁定 / 本机设置 / 出厂默认。界面就显示这三个字。 */
    public static String source(Context ctx, String key, boolean def) {
        if (cloudLocked(ctx, key)) {
            return "云控锁定";
        }
        return prefs(ctx).contains(key) ? "本机设置" : "出厂默认";
    }

    public static boolean flag(Context ctx, String key, boolean def) {
        String cloud = CloudFlags.str(key, "").trim();
        if (cloud.length() > 0) {
            return CloudFlags.parseBool(cloud, def);
        }
        return prefs(ctx).getBoolean(key, def);
    }

    /** 界面拨开关：被云控锁住时拒绝并返回原因，绝不让用户以为是自己手机坏了。 */
    public static String setLocal(Context ctx, String key, boolean on) {
        if (cloudLocked(ctx, key)) {
            return "「" + title(key) + "」被云控锁住（" + key + "=" + CloudFlags.str(key, "").trim() + "），本机改不动";
        }
        prefs(ctx).edit().putBoolean(key, on).apply();
        return null;
    }

    /** 清掉本机所有改动，回到「出厂默认 + 云控下发」。 */
    public static void clearLocal(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static String title(String key) {
        for (Switch s : SWITCHES) {
            if (s.key.equals(key)) {
                return s.title;
            }
        }
        return key;
    }

    // ---------------------------------------------------------------- 各条能力的语义入口

    public static boolean masterOn(Context ctx) {
        return flag(ctx, ON, true);
    }

    public static boolean killNetwork(Context ctx) {
        return flag(ctx, KILL_NET, true);
    }

    public static boolean perAppCut(Context ctx) {
        return flag(ctx, PER_APP, true);
    }

    public static boolean allowFreeze(Context ctx) {
        return flag(ctx, FREEZE, true);
    }

    public static boolean a11yFirewall(Context ctx) {
        return flag(ctx, A11Y_FW, true);
    }

    public static boolean allowUninstall(Context ctx) {
        return flag(ctx, UNINSTALL, false);
    }

    public static boolean cutOnTimeout(Context ctx) {
        return flag(ctx, TIMEOUT_CUT, true);
    }

    public static boolean silentModeAllowed(Context ctx) {
        return flag(ctx, SILENT, true);
    }

    public static boolean bootRecover(Context ctx) {
        return flag(ctx, BOOT_RECOVER, true);
    }

    /** 再生能力：常驻服务 + 划掉即拉 + 无障碍自愈。关掉它就退回「开着页面才有效」。 */
    public static boolean regenOn(Context ctx) {
        return flag(ctx, REGEN, true);
    }

    /** 守护页与确认窗是否从最近任务里隐藏。 */
    public static boolean hideTaskOn(Context ctx) {
        return flag(ctx, HIDE_TASK, true);
    }

    /** 是否放行系统级 AI 助手（见 {@link GuardSysAi}）。 */
    public static boolean sysAiOn(Context ctx) {
        return flag(ctx, SYS_AI, true);
    }

    // ---------------------------------------------------------------- 运行节奏（秒）

    /** 网络对账周期：断网保护期间多久查一次「网络是不是被恶意恢复了」。 */
    public static int auditSeconds(Context ctx) {
        return num(ctx, "guard_audit_sec", 15, 5, 300);
    }

    /** 状态落盘周期：跨重启恢复靠这条。 */
    public static int stateSeconds(Context ctx) {
        return num(ctx, "guard_state_sec", 30, 10, 600);
    }

    private static int num(Context ctx, String key, int def, int min, int max) {
        String cloud = CloudFlags.str(key, "").trim();
        if (cloud.length() > 0) {
            return CloudFlags.parseNum(cloud, def, min, max);
        }
        int v = prefs(ctx).getInt(key, def);
        return Math.max(min, Math.min(max, v));
    }

    /** 界面改节奏：同样吃云控锁定。 */
    public static String setLocalNum(Context ctx, String key, int value, int min, int max) {
        if (cloudLocked(ctx, key)) {
            return "「" + key + "」被云控锁住，本机改不动";
        }
        int v = Math.max(min, Math.min(max, value));
        prefs(ctx).edit().putInt(key, v).apply();
        return null;
    }

    public static boolean cloudLockedNum(Context ctx, String key) {
        return cloudLocked(ctx, key);
    }

    // ---------------------------------------------------------------- 给界面的一行总结

    /** 一句话说明当前这版被收成什么样，避免用户以为「没反应就是我手机坏了」。 */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(masterOn(ctx) ? "总闸 开" : "总闸 关（云端或本机关掉，不再检测）");
        sb.append(" · 断网 ").append(onOff(ctx, KILL_NET, true) ? "允" : "禁");
        sb.append(" · 精确断网 ").append(onOff(ctx, PER_APP, true) ? "优先" : "不用");
        sb.append(" · 冻结 ").append(onOff(ctx, FREEZE, true) ? "允" : "禁");
        sb.append(" · 无障碍防火墙 ").append(onOff(ctx, A11Y_FW, true) ? "开" : "关");
        sb.append(" · 静默退避 ").append(onOff(ctx, SILENT, true) ? "开" : "关");
        sb.append(" · 超时断网 ").append(onOff(ctx, TIMEOUT_CUT, true) ? "开" : "关（只维持 Lockdown）");
        sb.append(" · 跨重启 ").append(onOff(ctx, BOOT_RECOVER, true) ? "续" : "不续");
        sb.append(" · 卸载 ").append(onOff(ctx, UNINSTALL, false) ? "允（仍需真人二次确认）" : "禁");
        return sb.toString();
    }

    /** 首屏只留一行：总闸 + 两个最关键的处置开关，细节放「状态明细」。 */
    public static String brief(Context ctx) {
        if (!masterOn(ctx)) return "总闸 关";
        return "断网 " + (onOff(ctx, KILL_NET, true) ? "允" : "禁")
                + " · 冻结 " + (onOff(ctx, FREEZE, true) ? "允" : "禁")
                + " · 跨重启 " + (onOff(ctx, BOOT_RECOVER, true) ? "续" : "不续");
    }

    private static boolean onOff(Context ctx, String key, boolean def) {
        return flag(ctx, key, def);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
