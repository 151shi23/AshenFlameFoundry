package com.mineways.guardlink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 未触发态哨兵（§8.3.158）—— 对抗真实样本 {@code app.cedar.weather.app}（§8.3.157）的
 * 两条「装完即暴露」信号，全部<b>绕开「注入手势与真人同形」的死角</b>：
 * 不依赖触摸判据，也不用等 FIRED，纯粹盯「设备状态差分」。
 *
 * <p><b>一、新无障碍服务</b>：远控软件的持久化第一步就是把自己挂进无障碍 ——
 * 那个样本的服务名字伪装成 {@code MediaActivity}。已启用服务清单里多出一个
 * 既非系统、又不在豁免名单、以前也没见过的持有者 = 最高置信警报。
 * 这条对「远控自家的 a11y 注入会喂 HUMAN_TOUCH 撤防」免疫：服务一挂上就被看见，
 * 而不是等它作案时被它的假手指骗过。
 *
 * <p><b>二、无线调试 / ADB 开关变迁</b>：scrcpy 类免 root 远控必经
 * 「开发者选项 → 无线调试 → 配对」（样本 dex 字符串里躺着 {@code ADB pairing is required.}）。
 * 这个开关翻到开，比任何行为信号都早，也伪造不了 —— 它是系统设置里的全局状态。
 *
 * <p>本类只做 JVM 里就能断言的纯逻辑（flat 解析 / 信任匹配 / 差分）；
 * 读系统状态、落盘基线、发通知在 {@link GuardRuntime}。
 */
public final class GuardSentinel {

    // ---------------------------------------------------------------- 无障碍哨兵

    /**
     * {@code "pkg/cls"} flat 的包名部分。形状不对（没有斜杠 / 空段）返回 {@code null}，
     * 让调用方<b>跳过而不是猜</b> —— 解析层猜错一次，警报就白发或漏发。
     */
    public static String flatPkg(String flat) {
        if (flat == null) {
            return null;
        }
        String s = flat.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return null;
        }
        return s.substring(0, slash);
    }

    /**
     * 本次扫描里<b>新出现且不可信</b>的无障碍服务。
     *
     * @param currentFlats 当前已启用的无障碍服务（{@code Settings.Secure} 那串按 {@code :} 拆开）
     * @param knownFlats   以前处置过的（基线 + 已报警），小写
     * @param trustedPkgs  信任持有者：系统应用 + 豁免名单 + 守护自己，小写；目录语义与
     *                     {@code GuardTrust.Table.matches} 一致（{@code com.tencent.} 放行
     *                     {@code com.tencent.mm}，不放行 {@code com.tencentx.evil}）
     */
    public static List<String> newUntrustedAccessibility(List<String> currentFlats,
                                                         Set<String> knownFlats,
                                                         Set<String> trustedPkgs) {
        return newUntrustedFlats(currentFlats, knownFlats, trustedPkgs);
    }

    /**
     * 通用「新出现且不可信的持有者」差分。
     *
     * <p>无障碍服务、通知监听服务、设备管理员在系统里都是同一种形状：
     * {@code pkg/cls(:pkg/cls)*}。三张设置串共用这一份逻辑，差异只在调用点的文案 ——
     * 抄三遍最容易出的错是「其中一份忘了小写归一」，那样同一条警报会被反复叫。
     */
    public static List<String> newUntrustedFlats(List<String> currentFlats,
                                                 Set<String> knownFlats,
                                                 Set<String> trustedPkgs) {
        List<String> out = new ArrayList<>();
        if (currentFlats == null) {
            return out;
        }
        for (String flat : currentFlats) {
            String pkg = flatPkg(flat);
            if (pkg == null) {
                continue;
            }
            if (trusted(trustedPkgs, pkg)) {
                continue;
            }
            String key = flat.trim().toLowerCase(Locale.ROOT);
            if (knownFlats != null && knownFlats.contains(key)) {
                continue;              // 报警过的不重复叫 —— 通知疲劳比漏报更早杀死这套功能
            }
            if (!out.contains(flat.trim())) {
                out.add(flat.trim());
            }
        }
        return out;
    }

    /** 信任判定：目录语义与 {@code GuardTrust.Table.matches} 一字不差。 */
    public static boolean trusted(Set<String> trustedPkgs, String pkg) {
        if (trustedPkgs == null || pkg == null) {
            return false;
        }
        String k = pkg.trim().toLowerCase(Locale.ROOT);
        for (String t : trustedPkgs) {
            if (t == null) {
                continue;
            }
            String bare = t.trim().toLowerCase(Locale.ROOT);
            if (bare.endsWith(".")) {
                bare = bare.substring(0, bare.length() - 1);
            }
            if (bare.length() == 0) {
                continue;
            }
            if (k.equals(bare) || k.startsWith(bare + ".")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- ADB 哨兵

    /** ADB 开关变迁结论。无线调试优先 —— 它才是免 root 远控（scrcpy 通道）的入口。 */
    public enum AdbChange { NONE, WIRELESS_ON, ADB_ON }

    /**
     * 两条开发者选项开关的差分。首帧（prev 未知，传 {@code null}）只建基线不报警 ——
     * 否则守护一启动就对着「本来就开着无线调试」的设备嚎一嗓子。
     */
    public static AdbChange adbChange(Boolean prevAdb, boolean curAdb,
                                      Boolean prevWifi, boolean curWifi) {
        if (prevWifi != null && !prevWifi && curWifi) {
            return AdbChange.WIRELESS_ON;
        }
        if (prevAdb != null && !prevAdb && curAdb) {
            return AdbChange.ADB_ON;
        }
        return AdbChange.NONE;
    }

    /**
     * 默认短信应用变迁结论。首帧（prev 未知，传 {@code null}）只建基线不报警。
     *
     * <p>默认短信应用能读收件箱里全部验证码，是电诈/盗刷链条里最贵的一格；
     * 正常用户几乎不会在无人值守时换掉它，所以这条误报率极低。
     */
    public static boolean defaultSmsChanged(String prevPkg, String curPkg,
                                            Set<String> trustedPkgs) {
        if (prevPkg == null || curPkg == null
                || prevPkg.trim().isEmpty() || curPkg.trim().isEmpty()) {
            return false;
        }
        if (prevPkg.equalsIgnoreCase(curPkg)) {
            return false;
        }
        return !trusted(trustedPkgs, curPkg);
    }

    /** VPN 激活变迁：首帧与断开都不报，只在「关 → 开」的那一拍提示一次。 */
    public static boolean vpnActivated(Boolean prev, boolean cur) {
        return prev != null && !prev && cur;
    }

    private GuardSentinel() {
    }
}
