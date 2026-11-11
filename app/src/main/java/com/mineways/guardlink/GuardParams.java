package com.mineways.guardlink;

import android.content.Context;
import android.content.SharedPreferences;

import com.mineways.cloud.CloudFlags;
import com.zeus.guardlink.GuardLinkCore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 守护的全部可调参数 —— 界面要做到「直观显示、直观设置」，就得以这里为唯一清单。
 *
 * <p>三件事按顺序决定一个参数的当前值：
 * <ol>
 *   <li><b>云控下发</b> {@code gp_<id>=数字}：下发了就锁死，界面标「云控锁定」，本机拖动无效；</li>
 *   <li><b>本机设置</b>：{@code guardlink_params} 里存过的值；</li>
 *   <li><b>出厂值</b>：直接取内核字段的初始值，不在这里手打数字 ——
 *       上游改了出厂值，本清单自动跟着变，不会出现「界面写着 6 秒、内核其实是 5 秒」这种谎。</li>
 * </ol>
 *
 * <p>写值走 {@link #applyAll}，落到 {@code GuardLinkCore} 的 volatile 字段上（内核为此打过最小补丁，
 * 见该文件 NoHumanDetector 上方说明与 PROJECT_NOTES §8.3.146）。改动即刻生效，不需要重启守护。
 */
public final class GuardParams {

    private static final String PREF = "guardlink_params";

    /** 读取内核当前值。 */
    public interface Get {
        long read();
    }

    /** 写入内核字段。 */
    public interface Put {
        void write(long v);
    }

    public static final class Item {
        public final String id;
        public final String group;
        public final String title;
        public final String unit;
        public final long min;
        public final long max;
        public final long step;
        public final long factory;
        public final String note;
        private final Get get;
        private final Put put;

        Item(String id, String group, String title, String unit,
             long min, long max, long step, Get get, Put put, String note) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.put = put;
            this.note = note;
            // 出厂值 = 本类被初始化那一刻内核里的值（在那之前没人写过内核，所以就是上游原值）
            this.factory = get.read();
        }

        /** 内核里的现值。 */
        public long current() {
            return get.read();
        }

        /** 云控是否锁死这条（gp_&lt;id&gt; 下发了且能解析成数字）。 */
        public boolean cloudPinned() {
            return CloudFlags.str("gp_" + id, "").length() > 0;
        }

        /** 直接落内核（已按 min/max/step 夹好）。 */
        public void write(long v) {
            put.write(clamp(v, min, max, step));
        }

        /** 一行 "当前 / 出厂 / 可设范围"，给界面当副标题用。 */
        public String readout() {
            return current() + unit + "（出厂 " + factory + unit + "，可设 " + min + "~" + max + unit + "）";
        }
    }

    /** 分组：界面二级菜单直接按这个顺序排。 */
    public static final class Group {
        public final String id;
        public final String title;
        public final String why;

        Group(String id, String title, String why) {
            this.id = id;
            this.title = title;
            this.why = why;
        }
    }

    public static final Group GROUP_WINDOW = new Group("window", "判定窗口",
            "决定「多久没碰屏幕才算不是你在操作」。调小更灵敏但误报多，调大更安静但漏报。");
    public static final Group GROUP_HUMAN = new Group("human", "真人按压鉴定",
            "决定「什么样的点击才算真人的手」。这两条是反注入的底线，除非被无障碍辅助功能误伤，一般别动。");
    public static final Group GROUP_CONFIRM = new Group("confirm", "确认窗与退避",
            "决定「弹窗给你多少秒」和「连炸几次之后闭嘴」。");
    public static final Group GROUP_SCORE = new Group("score", "溯源权重与门槛",
            "四步筛查每项证据值多少分，以及累计到多少分才算「主要嫌疑」。分越高越敢点名。");

    private static volatile List<Item> items;

    private GuardParams() {
    }

    public static List<Item> items() {
        List<Item> local = items;
        if (local == null) {
            synchronized (GuardParams.class) {
                local = items;
                if (local == null) {
                    local = build();
                    items = local;
                }
            }
        }
        return local;
    }

    public static List<Item> inGroup(String groupId) {
        List<Item> out = new ArrayList<>();
        for (Item it : items()) {
            if (it.group.equals(groupId)) {
                out.add(it);
            }
        }
        return out;
    }

    public static Item find(String id) {
        for (Item it : items()) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 纯逻辑（JVM 可测）

    /**
     * 夹到 [min,max] 并吸附到 step 网格上。区间写反或步长非法（这是清单自己的编程错误，
     * 不是用户输入）时退回两个界里较小的那个，绝不把脏值写进内核。
     */
    public static long clamp(long v, long min, long max, long step) {
        if (min > max || step <= 0) {
            return Math.min(min, max);
        }
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        if (step == 1) {
            return v;
        }
        long snapped = min + Math.round((v - min) / (double) step) * step;
        if (snapped > max) {
            snapped = max;
        }
        if (snapped < min) {
            snapped = min;
        }
        return snapped;
    }

    /** 云控原文解析成数字；空/不可解析 = 没锁（返回 Long.MIN_VALUE 作哨兵）。 */
    public static long cloudValue(String raw) {
        if (raw == null) {
            return Long.MIN_VALUE;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    // ---------------------------------------------------------------- 生效与持久化

    /**
     * 按「云控 &gt; 本机 &gt; 出厂」把每条参数写进内核。守护启动时调一次，界面改值时也走这里。
     *
     * @return 本次生效结果的一行说明（有多少条来自云控、多少条来自本机），给事件流显示
     */
    public static String applyAll(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int fromCloud = 0, fromLocal = 0, atFactory = 0;
        for (Item it : items()) {
            long cv = cloudValue(CloudFlags.str("gp_" + it.id, ""));
            if (cv != Long.MIN_VALUE) {
                it.write(cv);
                fromCloud++;
            } else if (p.contains(it.id)) {
                it.write(p.getLong(it.id, it.factory));
                fromLocal++;
            } else {
                // 没下发也没存过：回出厂，避免上一次会话的覆盖值赖在内存里
                it.write(it.factory);
                atFactory++;
            }
        }
        return "参数已生效：云控锁定 " + fromCloud + " 条 · 本机设置 " + fromLocal
                + " 条 · 出厂值 " + atFactory + " 条";
    }

    /** 界面改值：立刻落盘 + 立刻进内核。云控锁着的条目直接拒绝，并把原因返回给界面显示。 */
    public static String setLocal(Context ctx, String id, long value) {
        Item it = find(id);
        if (it == null) {
            return "没有这条参数：" + id;
        }
        if (it.cloudPinned()) {
            return "「" + it.title + "」被云控锁住（gp_" + id + "），本机改不动";
        }
        long v = clamp(value, it.min, it.max, it.step);
        prefs(ctx).edit().putLong(id, v).apply();
        it.write(v);
        return null;
    }

    /** 清掉本机所有改动（云控锁着的仍然听云控的）。 */
    public static void clearLocal(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit().clear().apply();
        for (Item it : items()) {
            if (!it.cloudPinned()) {
                it.write(it.factory);
            }
        }
    }

    /** 本机有没有改过任何一条 —— 界面用来决定「恢复出厂」按钮灰不灰。 */
    public static boolean hasLocalEdits(Context ctx) {
        SharedPreferences p = prefs(ctx);
        for (Item it : items()) {
            if (p.contains(it.id) && p.getLong(it.id, it.factory) != it.factory) {
                return true;
            }
        }
        return false;
    }

    /** 改了哪几条，给功能页顶部一行看清「现在不是出厂状态」。 */
    public static List<String> localDiff(Context ctx) {
        SharedPreferences p = prefs(ctx);
        List<String> out = new ArrayList<>();
        for (Item it : items()) {
            if (p.contains(it.id) && p.getLong(it.id, it.factory) != it.factory) {
                out.add(it.title + "：" + it.factory + it.unit + " → " + p.getLong(it.id, it.factory) + it.unit);
            }
        }
        return out;
    }

    public static List<Group> groups() {
        return java.util.Arrays.asList(GROUP_WINDOW, GROUP_HUMAN, GROUP_CONFIRM, GROUP_SCORE);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 清单

    private static List<Item> build() {
        final List<Item> out = new ArrayList<>();
        NoHuman noHuman = new NoHuman();
        Human human = new Human();
        Confirm confirm = new Confirm();
        Score score = new Score();

        // ---- 判定窗口 ----
        // 说明一律不写「出厂 N」：那个数字下面那行 scope() 会从内核现读现显，这里再手打一份就是第二个真相。
        out.add(new Item("idle_ms", GROUP_WINDOW.id, "无人操作窗口", " 毫秒",
                2000, 30000, 500, noHuman.idle(), noHuman.setIdle(),
                "距上次真人触摸超过这么久、界面却自己在动，就视为「不是你在操作」。"));
        out.add(new Item("presence_ms", GROUP_WINDOW.id, "真人驻留期", " 毫秒",
                0, 600000, 60000, noHuman.presence(), noHuman.setPresence(),
                "一次真人触摸/解锁后这么久内都算「人就在手机前」：界面怎么变都不判无人操控、"
                        + "不自动断网。只有人离开（超过驻留期没碰手机）才恢复自动值守；"
                        + "媒体播放期间本来就会持续续期，看视频听歌不会突然断网。"));
        out.add(new Item("pending_ms", GROUP_WINDOW.id, "触发缓冲期", " 毫秒",
                500, 10000, 250, noHuman.pending(), noHuman.setPending(),
                "命中后先观察这么久，期间你碰一下屏幕就立刻撤防。"));
        out.add(new Item("burst", GROUP_WINDOW.id, "缓冲期内实锤次数", " 次",
                1, 5, 1, noHuman.burst(), noHuman.setBurst(),
                "缓冲期内界面自行变化累计到几次就实锤（系统前台记录类的高置信事件一次即实锤）。"));
        out.add(new Item("wake_ms", GROUP_WINDOW.id, "亮屏后自启动窗口", " 毫秒",
                2000, 30000, 500, noHuman.wake(), noHuman.setWake(),
                "亮屏/解锁后这么久内出现无人自启动，按「冷启动型」处理。"));

        // ---- 真人按压鉴定 ----
        out.add(new Item("press_ms", GROUP_HUMAN.id, "按压时长下限", " 毫秒",
                20, 300, 5, human.press(), human.setPress(),
                "按下到抬起至少这么久才算手指。adb / 远程注入是同一帧的瞬时点，会被这条拒掉。"));
        out.add(new Item("age_ms", GROUP_HUMAN.id, "点击新鲜度窗口", " 毫秒",
                300, 5000, 100, human.age(), human.setAge(),
                "「视图被点击」必须紧跟着真实触摸序列，超过这个间隔不算。"));

        // ---- 确认窗与退避 ----
        out.add(new Item("confirm_ms", GROUP_CONFIRM.id, "人工确认倒计时", " 毫秒",
                3000, 60000, 1000, confirm.window(), confirm.setWindow(),
                "进入桌面后弹窗问你「是否是误报」的时长，到点没真人点就按恶意操控处置。"));
        out.add(new Item("silent_after", GROUP_CONFIRM.id, "退避阈值", " 次",
                1, 10, 1, confirm.silentAfter(), confirm.setSilentAfter(),
                "连续触发到第几次转静默（保护照常，不再弹窗打扰，改为对嫌疑应用精确断网）。"));

        // ---- 溯源权重与门槛 ----
        out.add(new Item("w_install", GROUP_SCORE.id, "静默装包能力", " 分",
                0, 60, 1, score.install(), score.setInstall(), "能自己装应用的 +分。"));
        out.add(new Item("w_declared_a11y", GROUP_SCORE.id, "声明无障碍服务", " 分",
                0, 60, 1, score.declaredA11y(), score.setDeclaredA11y(), "清单里挂了无障碍服务的 +分。"));
        out.add(new Item("w_enabled_a11y", GROUP_SCORE.id, "无障碍已启用", " 分",
                0, 60, 1, score.enabledA11y(), score.setEnabledA11y(), "当前真的开着无障碍（具备模拟点击能力）的 +分，最重的一条能力证据。"));
        out.add(new Item("w_overlay", GROUP_SCORE.id, "悬浮窗权限", " 分",
                0, 60, 1, score.overlay(), score.setOverlay(), "能盖在别人上面的 +分。"));
        out.add(new Item("w_write_settings", GROUP_SCORE.id, "改系统设置权限", " 分",
                0, 60, 1, score.writeSettings(), score.setWriteSettings(), "能写系统设置的 +分。"));
        out.add(new Item("w_device_admin", GROUP_SCORE.id, "设备管理员", " 分",
                0, 60, 1, score.deviceAdmin(), score.setDeviceAdmin(), "激活了设备管理员（难卸载）的 +分。"));
        out.add(new Item("w_boot_fg", GROUP_SCORE.id, "开机自启 + 前台服务", " 分",
                0, 60, 1, score.bootFg(), score.setBootFg(), "开机就起来常驻的 +分。"));
        out.add(new Item("w_sms", GROUP_SCORE.id, "短信权限", " 分",
                0, 60, 1, score.sms(), score.setSms(), "能收短信（拦验证码）的 +分。"));
        out.add(new Item("w_fake_system", GROUP_SCORE.id, "伪装系统包名", " 分",
                0, 60, 1, score.fakeSystem(), score.setFakeSystem(), "包名装成 com.android.* 但不是真系统包的 +分。"));
        out.add(new Item("w_traffic_top", GROUP_SCORE.id, "事发窗口流量前三", " 分",
                0, 60, 1, score.trafficTop(), score.setTrafficTop(), "流量增量排前三的 +分。"));
        out.add(new Item("w_traffic_any", GROUP_SCORE.id, "有流量增量", " 分",
                0, 60, 1, score.trafficAny(), score.setTrafficAny(), "排在前三之外但有增量的 +分。"));
        out.add(new Item("w_behavior", GROUP_SCORE.id, "无人窗口内活动归属", " 分",
                0, 60, 1, score.behavior(), score.setBehavior(), "事发窗口内界面活动/自启动就归它 —— 最直接的实锤。"));
        out.add(new Item("score_min", GROUP_SCORE.id, "主要嫌疑门槛", " 分",
                20, 200, 5, score.threshold(), score.setThreshold(),
                "累计到多少分才被推举成「主要嫌疑」并允许精确断网/卸载；"
                        + "过线之外还必须有实锤（行为命中 / 无障碍已启用 / 伪装系统 / 隐藏入口），"
                        + "只有权限能力的正常软件不会点名。"));
        out.add(new Item("w_delete", GROUP_SCORE.id, "静默删包能力", " 分",
                0, 60, 1, score.deletePkg(), score.setDeletePkg(), "能卸载别的应用（REQUEST_DELETE_PACKAGES）的 +分 —— 反卸载自保的标志。"));
        out.add(new Item("w_projection", GROUP_SCORE.id, "投屏采集能力", " 分",
                0, 60, 1, score.projection(), score.setProjection(), "能拿 MediaProjection 抓屏（FOREGROUND_SERVICE_MEDIA_PROJECTION）的 +分。"));
        out.add(new Item("w_notify_declared", GROUP_SCORE.id, "声明通知监听", " 分",
                0, 60, 1, score.notifyDeclared(), score.setNotifyDeclared(),
                "挂了 NotificationListenerService 的 +分 —— 能读全部通知（含验证码/银行推送）。"));
        out.add(new Item("w_notify_enabled", GROUP_SCORE.id, "通知监听已启用", " 分",
                0, 60, 1, score.notifyEnabled(), score.setNotifyEnabled(),
                "通知监听当前真的在跑、正在读通知的 +分，比「只是声明」更硬。"));
        out.add(new Item("w_vpn", GROUP_SCORE.id, "VPN 服务", " 分",
                0, 60, 1, score.vpn(), score.setVpn(),
                "声明了 VPNService 的 +分 —— 能把全部流量过自己手里（中间人/劫持）。"));
        out.add(new Item("w_default_sms", GROUP_SCORE.id, "默认短信应用", " 分",
                0, 60, 1, score.defaultSms(), score.setDefaultSms(),
                "当前是系统默认短信应用的 +分 —— 验证码全部落它手里。"));
        out.add(new Item("w_hidden", GROUP_SCORE.id, "隐藏桌面入口", " 分",
                0, 60, 1, score.hidden(), score.setHidden(),
                "没有桌面图标、却同时持危险能力的 +分（组合条件，正常无入口插件不会被单独判分）。"));

        return Collections.unmodifiableList(out);
    }

    // 下面几个小类只是把「读哪条内核字段 / 写哪条内核字段」分组写清楚，不含任何数字。
    private static final class NoHuman {
        Get idle() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS;
                }
            };
        }

        Put setIdle() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS = v;
                }
            };
        }

        Get presence() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS;
                }
            };
        }

        Put setPresence() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = v;
                }
            };
        }

        Get pending() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS;
                }
            };
        }

        Put setPending() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS = v;
                }
            };
        }

        Get burst() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST;
                }
            };
        }

        Put setBurst() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST = (int) v;
                }
            };
        }

        Get wake() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.NoHumanDetector.WAKE_ACTIVITY_WINDOW_MS;
                }
            };
        }

        Put setWake() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.NoHumanDetector.WAKE_ACTIVITY_WINDOW_MS = v;
                }
            };
        }
    }

    private static final class Human {
        Get press() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.HumanVerifier.MIN_TOUCH_DURATION_MS;
                }
            };
        }

        Put setPress() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.HumanVerifier.MIN_TOUCH_DURATION_MS = v;
                }
            };
        }

        Get age() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.HumanVerifier.TOUCH_AGE_WINDOW_MS;
                }
            };
        }

        Put setAge() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.HumanVerifier.TOUCH_AGE_WINDOW_MS = v;
                }
            };
        }
    }

    private static final class Confirm {
        Get window() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS;
                }
            };
        }

        Put setWindow() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS = v;
                }
            };
        }

        Get silentAfter() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.GuardController.SILENT_AFTER_FIRES;
                }
            };
        }

        Put setSilentAfter() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.GuardController.SILENT_AFTER_FIRES = (int) v;
                }
            };
        }
    }

    private static final class Score {
        private Get g(final int idx) {
            return new Get() {
                @Override
                public long read() {
                    return w(idx);
                }
            };
        }

        private Put p(final int idx) {
            return new Put() {
                @Override
                public void write(long v) {
                    putW(idx, (int) v);
                }
            };
        }

        Get install() {
            return g(0);
        }

        Put setInstall() {
            return p(0);
        }

        Get declaredA11y() {
            return g(1);
        }

        Put setDeclaredA11y() {
            return p(1);
        }

        Get enabledA11y() {
            return g(2);
        }

        Put setEnabledA11y() {
            return p(2);
        }

        Get overlay() {
            return g(3);
        }

        Put setOverlay() {
            return p(3);
        }

        Get writeSettings() {
            return g(4);
        }

        Put setWriteSettings() {
            return p(4);
        }

        Get deviceAdmin() {
            return g(5);
        }

        Put setDeviceAdmin() {
            return p(5);
        }

        Get bootFg() {
            return g(6);
        }

        Put setBootFg() {
            return p(6);
        }

        Get sms() {
            return g(7);
        }

        Put setSms() {
            return p(7);
        }

        Get fakeSystem() {
            return g(8);
        }

        Put setFakeSystem() {
            return p(8);
        }

        Get trafficTop() {
            return g(9);
        }

        Put setTrafficTop() {
            return p(9);
        }

        Get trafficAny() {
            return g(10);
        }

        Put setTrafficAny() {
            return p(10);
        }

        Get behavior() {
            return g(11);
        }

        Get deletePkg() {
            return g(12);
        }

        Put setDeletePkg() {
            return p(12);
        }

        Get projection() {
            return g(13);
        }

        Put setProjection() {
            return p(13);
        }

        Get notifyDeclared() {
            return g(14);
        }

        Put setNotifyDeclared() {
            return p(14);
        }

        Get notifyEnabled() {
            return g(15);
        }

        Put setNotifyEnabled() {
            return p(15);
        }

        Get vpn() {
            return g(16);
        }

        Put setVpn() {
            return p(16);
        }

        Get defaultSms() {
            return g(17);
        }

        Put setDefaultSms() {
            return p(17);
        }

        Get hidden() {
            return g(18);
        }

        Put setHidden() {
            return p(18);
        }

        Put setBehavior() {
            return p(11);
        }

        Get threshold() {
            return new Get() {
                @Override
                public long read() {
                    return GuardLinkCore.SuspectEngine.PRIMARY_SUSPECT_THRESHOLD;
                }
            };
        }

        Put setThreshold() {
            return new Put() {
                @Override
                public void write(long v) {
                    GuardLinkCore.SuspectEngine.PRIMARY_SUSPECT_THRESHOLD = (int) v;
                }
            };
        }

        private static long w(int i) {
            switch (i) {
                case 0:
                    return GuardLinkCore.SuspectEngine.W_REQUEST_INSTALL;
                case 1:
                    return GuardLinkCore.SuspectEngine.W_DECLARED_ACCESSIBILITY;
                case 2:
                    return GuardLinkCore.SuspectEngine.W_ENABLED_ACCESSIBILITY;
                case 3:
                    return GuardLinkCore.SuspectEngine.W_OVERLAY;
                case 4:
                    return GuardLinkCore.SuspectEngine.W_WRITE_SETTINGS;
                case 5:
                    return GuardLinkCore.SuspectEngine.W_DEVICE_ADMIN;
                case 6:
                    return GuardLinkCore.SuspectEngine.W_BOOT_FG;
                case 7:
                    return GuardLinkCore.SuspectEngine.W_SMS;
                case 8:
                    return GuardLinkCore.SuspectEngine.W_IMPERSONATE_SYSTEM;
                case 9:
                    return GuardLinkCore.SuspectEngine.W_TRAFFIC_TOP;
                case 10:
                    return GuardLinkCore.SuspectEngine.W_TRAFFIC_ANY;
                case 12:
                    return GuardLinkCore.SuspectEngine.W_REQUEST_DELETE;
                case 13:
                    return GuardLinkCore.SuspectEngine.W_MEDIA_PROJECTION;
                case 14:
                    return GuardLinkCore.SuspectEngine.W_NOTIFICATION_DECLARED;
                case 15:
                    return GuardLinkCore.SuspectEngine.W_NOTIFICATION_ENABLED;
                case 16:
                    return GuardLinkCore.SuspectEngine.W_VPN_SERVICE;
                case 17:
                    return GuardLinkCore.SuspectEngine.W_DEFAULT_SMS;
                case 18:
                    return GuardLinkCore.SuspectEngine.W_HIDDEN_APP;
                default:
                    return GuardLinkCore.SuspectEngine.W_BEHAVIOR_HIT;
            }
        }

        private static void putW(int i, int v) {
            switch (i) {
                case 0:
                    GuardLinkCore.SuspectEngine.W_REQUEST_INSTALL = v;
                    break;
                case 1:
                    GuardLinkCore.SuspectEngine.W_DECLARED_ACCESSIBILITY = v;
                    break;
                case 2:
                    GuardLinkCore.SuspectEngine.W_ENABLED_ACCESSIBILITY = v;
                    break;
                case 3:
                    GuardLinkCore.SuspectEngine.W_OVERLAY = v;
                    break;
                case 4:
                    GuardLinkCore.SuspectEngine.W_WRITE_SETTINGS = v;
                    break;
                case 5:
                    GuardLinkCore.SuspectEngine.W_DEVICE_ADMIN = v;
                    break;
                case 6:
                    GuardLinkCore.SuspectEngine.W_BOOT_FG = v;
                    break;
                case 7:
                    GuardLinkCore.SuspectEngine.W_SMS = v;
                    break;
                case 8:
                    GuardLinkCore.SuspectEngine.W_IMPERSONATE_SYSTEM = v;
                    break;
                case 9:
                    GuardLinkCore.SuspectEngine.W_TRAFFIC_TOP = v;
                    break;
                case 10:
                    GuardLinkCore.SuspectEngine.W_TRAFFIC_ANY = v;
                    break;
                case 12:
                    GuardLinkCore.SuspectEngine.W_REQUEST_DELETE = v;
                    break;
                case 13:
                    GuardLinkCore.SuspectEngine.W_MEDIA_PROJECTION = v;
                    break;
                case 14:
                    GuardLinkCore.SuspectEngine.W_NOTIFICATION_DECLARED = v;
                    break;
                case 15:
                    GuardLinkCore.SuspectEngine.W_NOTIFICATION_ENABLED = v;
                    break;
                case 16:
                    GuardLinkCore.SuspectEngine.W_VPN_SERVICE = v;
                    break;
                case 17:
                    GuardLinkCore.SuspectEngine.W_DEFAULT_SMS = v;
                    break;
                case 18:
                    GuardLinkCore.SuspectEngine.W_HIDDEN_APP = v;
                    break;
                default:
                    GuardLinkCore.SuspectEngine.W_BEHAVIOR_HIT = v;
            }
        }
    }
}
