package com.mineways.guardlink;

import com.zeus.guardlink.GuardLinkCore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统级 AI 助手（小爱同学 / 鸿蒙小艺 / vivo 小 v / 荣耀 YOYO…）的放行判定。
 *
 * <p><b>为什么会误伤</b>：内核的触发条件就一句话 —— 「距上次真人触摸 ≥W 且界面自己在动」
 * （{@code GuardLinkCore.NoHumanDetector#feed} 的 UI_ACTIVITY 分支）。语音操控在信号层面
 * 和远程操控<b>完全同形</b>：人不动手，屏幕自己在变。事件流里没有「是谁下的令」这一项，
 * 所以判定层不可能区分「小爱按你说的开微信」和「远控端替你开微信」。
 *
 * <p><b>放行的门槛（两条都要满足）</b>：
 * <ol>
 *   <li>这个包<b>当前在无障碍已启用列表里</b>。能替人点屏幕的才是这套判定要谈的对象；
 *       而且这一条意味着是用户在「设置 → 无障碍」里<b>亲手打开的</b>，属于主人授权。</li>
 *   <li>它是系统应用（{@code FLAG_SYSTEM}，含被商店更新过的），或者命中内核那份
 *       大厂/OEM 前缀表（{@code A11yPolicy.TRUSTED_PKG_PREFIXES}）。</li>
 * </ol>
 *
 * <p>为什么不放宽到「所有系统应用都别报」：厂商系统里也有会自己弹的界面
 * （推送、广告、升级提示），全豁免等于把整个系统侧变成盲区。要求「持有已启用的无障碍服务」
 * 就把范围收在<b>真的能动手的那几个</b>身上。
 *
 * <p>纯函数：{@code isSystemApp} 与那串无障碍设置由 Android 层现查后喂进来。
 */
public final class GuardSysAi {

    /**
     * 从 {@code Settings.Secure.enabled_accessibility_services} 那一串里取出包名。
     *
     * <p>形态是 {@code pkg/.Cls:pkg2/com.x.Cls2}；只取包名部分并去重（一个 App 常挂多个服务）。
     */
    public static List<String> servicePackages(String flatList) {
        Set<String> out = new LinkedHashSet<>();
        if (flatList == null) {
            return new ArrayList<>();
        }
        for (String part : flatList.split(":")) {
            String s = part.trim();
            if (s.length() == 0) {
                continue;
            }
            int slash = s.indexOf('/');
            String pkg = slash > 0 ? s.substring(0, slash) : s;
            if (pkg.length() > 0 && pkg.indexOf('.') > 0) {
                out.add(pkg);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 这个包是不是「主人授权过的系统级执行者」。
     *
     * @param on        {@code guard_sys_ai} 总开关（关掉就一条都不放行，回到严格模式）
     * @param isSystemApp Android 层现查的 {@code FLAG_SYSTEM}
     * @param a11yPkgs  {@link #servicePackages} 的结果（当前开着无障碍的包）
     */
    public static boolean isTrustedActor(String pkg, boolean on, boolean isSystemApp,
                                         Collection<String> a11yPkgs) {
        if (!on || pkg == null || a11yPkgs == null || a11yPkgs.isEmpty()) {
            return false;
        }
        if (!a11yPkgs.contains(pkg)) {
            return false;               // 没有已启用的无障碍服务 = 不是能替人动手的执行者
        }
        return isSystemApp || GuardLinkCore.A11yPolicy.isTrusted(pkg, null);
    }

    /** 界面与事件流那一句：放行的是谁，一眼能查。 */
    public static String describe(Collection<String> actors) {
        if (actors == null || actors.isEmpty()) {
            return "没识别到系统级可信执行者（没装语音助手，或它们没开无障碍）";
        }
        StringBuilder sb = new StringBuilder("放行 " + actors.size() + " 个：");
        int i = 0;
        for (String p : actors) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(p);
            if (i >= 6) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 安装/卸载窗口要不要放行。
     *
     * <p>内核在 Lockdown 期间见安装器窗口就拦（{@code BLOCK_INSTALLER}）—— 那是防恶意软件静默装包/
     * 卸守护。但「本人到设置里卸载那个恶意应用」走的也是同一个窗口，于是处置动作本身被当成攻击，
     * 一遍遍弹「安装/卸载行为被拦截」，保护永远退不下去。
     *
     * <p>分开这两件事的信号只有一个：<b>活人的触摸</b>。静默装包的恶意软件拿不到真人手指，
     * 而你自己卸载必然一路在点。所以判据是「此刻距最近一次真人触摸是否在无人窗口内」。
     *
     * @param sinceHumanTouchMs 距最近一次真人触摸过去了多久；未知传负数（按「有人在」放行，
     *                          宁可少拦一次也不能把人锁死在保护里）
     * @param idleWindowMs      内核的无人窗口 {@code HUMAN_IDLE_WINDOW_MS}
     */
    public static boolean installerLooksHuman(long sinceHumanTouchMs, long idleWindowMs) {
        if (sinceHumanTouchMs < 0) {
            return true;
        }
        return sinceHumanTouchMs < idleWindowMs;
    }

    private GuardSysAi() {
    }
}
