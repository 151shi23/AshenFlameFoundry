package com.mineways.prism;

import android.content.Context;

import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.CloudFlags;
import com.mineways.cloud.FeatureGate;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 「棱镜玩机」云控开关（与 {@code GjgjxFlags} 同一套读法）。
 *
 * <p>云端只下发 {@code FLAG}，读不到一律按出厂值（全开）：
 * <pre>
 * FLAG key=prism_on      to=1|0        总开关；0 = 首页入口隐藏，直接打开也会被拦下
 * FLAG key=prism_risk    to=0|1|2      允许的最高风险档：0=只安全项，1=到谨慎项，2=全部（默认 2）
 * FLAG key=prism_notice  to=文字       关闭时给用户的理由（原样显示）
 * FLAG key=prism_vendor  to=auto|xiaomi|vivo|oppo|generic  云端指定适配系统（优先于本机判断）
 * FLAG key=prism_off     to=id1,id2    一次性关掉若干功能 id（与 prism_&lt;id&gt; 等价，写起来短）
 * FLAG key=prism_battery_magic to=0     单条功能开关（任意功能 id 都可这样关）
 * </pre>
 *
 * <p>层级：总开关 → 单条开关 / prism_off → 风险档 → 功能门面（FEAT hide/disable/label/desc/pin/order）。
 * 高风险项（如冻结安装器）只有在 {@code prism_risk=2} 时才可能出现。
 *
 * <p>FEAT 那层要在 {@code MinewaysApp} 里先调 {@link PrismFeatures#registerCloud()} 才生效；
 * 没登记或云端配置没下发时，本类只按 FLAG 和出厂值判断。
 */
public final class PrismFlags {

    public static final String ON = "prism_on";
    public static final String RISK = "prism_risk";
    public static final String NOTICE = "prism_notice";
    public static final String VENDOR = "prism_vendor";
    public static final String OFF = "prism_off";

    private PrismFlags() {
    }

    public static boolean on(Context ctx) {
        return CloudFlags.bool(ON, true);
    }

    /** 0..2，默认全开。 */
    public static int risk(Context ctx) {
        return CloudFlags.num(RISK, PrismFeatures.DANGER, 0, PrismFeatures.DANGER);
    }

    public static String notice(Context ctx) {
        return CloudFlags.str(NOTICE, "").trim();
    }

    /** 云端指定适配系统；空串/auto = 跟随本机判断。 */
    public static String vendor(Context ctx) {
        return CloudFlags.str(VENDOR, "").trim().toLowerCase(Locale.ROOT);
    }

    /** 单条功能是否该出现在列表里（总开关、单条键、off 列表、风险档、FEAT hide 五层都过）。 */
    public static boolean feature(Context ctx, String id) {
        return state(ctx, id).visible;
    }

    /**
     * 单条功能最终应该长什么样：FLAG 层负责「谁能看到」，功能门面负责
     * {@code FEAT hide/disable/label/desc/pin/order}。读不到云控一律回退出厂值。
     */
    public static final class State {
        public boolean visible = true;
        /** false = 云端停用：入口保留但置灰，点按显示 {@link #notice}。 */
        public boolean usable = true;
        /** 云端改写的标题/说明；null = 用包内原文。 */
        public String label;
        public String desc;
        public String notice = "";
        public boolean pinned;
        /** 分组内排序，-1 = 未指定。 */
        public int order = -1;
    }

    public static State state(Context ctx, String id) {
        State s = new State();
        if (id == null) {
            s.visible = false;
            return s;
        }
        if (!on(ctx)) {
            s.visible = false;
            s.notice = notice(ctx);
            return s;
        }
        if (offSet(ctx).contains(id)) {
            s.visible = false;
            return s;
        }
        if (PrismFeatures.riskOf(id) > risk(ctx)) {
            s.visible = false;
            return s;
        }
        if (!CloudFlags.bool("prism_" + id, true)) {
            s.visible = false;
            return s;
        }
        try {
            FeatureGate g = CloudCatalog.gate();
            if (g == null || !g.active()) return s;
            FeatureGate.State fs = g.stateOf(PrismFeatures.cloudKey(id),
                    PrismFeatures.CLOUD_CATEGORY, FeatureGate.KIND_FREE);
            s.visible = fs.visible;
            s.usable = fs.usable;
            s.notice = fs.notice == null ? "" : fs.notice;
            s.label = fs.label;
            s.desc = fs.desc;
            s.pinned = fs.pinned;
            s.order = fs.order;
        } catch (Throwable ignored) {
            // 门面解析失败不能影响本页：退回只按 FLAG 控制
        }
        return s;
    }

    /** 高风险项是否被云控放行（界面上决定要不要渲染红色按钮）。 */
    public static boolean allowDanger(Context ctx) {
        return on(ctx) && risk(ctx) >= PrismFeatures.DANGER;
    }

    private static Set<String> offSet(Context ctx) {
        Set<String> out = new HashSet<String>();
        String raw = CloudFlags.str(OFF, "");
        if (raw.isEmpty()) return out;
        for (String p : raw.split("[,;\\s]+")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
