package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 系统级 AI 助手放行的断言。
 *
 * <p>他报的现象：小爱同学、鸿蒙小艺、vivo 小 v、荣耀那些系统级 AI 一自动操控就被拦。
 * 根因不是判错嫌疑人，而是「语音替你动手」在事件流里和「远程操控」同形 —— 没人触摸、界面自己在动。
 * 所以这里守的是放行范围的<b>下限</b>：只放真的能替人动手、又是主人亲手授权的那几个，
 * 宽一条就多一块盲区（尤其是自称 {@code com.xiaomi.} 的伪装包）。
 */
public class GuardSysAiTest {

    /** 各家语音助手在无障碍设置里的真实形态（服务类名不必精确，包名才是判定用的）。 */
    private static final String VOICE =
            "com.xiaomi.mico/com.xiaomi.aiasst.service.AssistAccessibilityService"
                    + ":com.huawei.vassistant/com.huawei.vassistant.ui.VassistantAccessibilityService"
                    + ":com.vivo.assistant/com.vivo.assistant.ServiceLauncherActivity";

    @Test
    public void parsesPackagesFromTheSecureSetting() {
        List<String> pkgs = GuardSysAi.servicePackages(VOICE);
        assertEquals(Arrays.asList("com.xiaomi.mico", "com.huawei.vassistant", "com.vivo.assistant"),
                pkgs);
    }

    @Test
    public void parsingToleratesRealWorldDirt() {
        // 一个 App 挂多个服务只能出一个包名；分隔符周围可能有空格；不成形的片段要丢掉
        assertEquals(Collections.singletonList("com.a.b"),
                GuardSysAi.servicePackages(" com.a.b/.S1 : com.a.b/.S2 "));
        assertEquals(Collections.emptyList(), GuardSysAi.servicePackages(null));
        assertEquals(Collections.emptyList(), GuardSysAi.servicePackages(""));
        assertEquals(Collections.emptyList(), GuardSysAi.servicePackages(":::"));
        // 没有点号的单段名字（残缺片段）不能被当成包名
        assertEquals(Collections.emptyList(), GuardSysAi.servicePackages("garbage"));
    }

    @Test
    public void systemAssistantHoldingAccessibilityIsTrusted() {
        List<String> a11y = GuardSysAi.servicePackages(VOICE);
        assertTrue(GuardSysAi.isTrustedActor("com.xiaomi.mico", true, true, a11y));
        assertTrue(GuardSysAi.isTrustedActor("com.huawei.vassistant", true, true, a11y));
    }

    /** 关掉开关就回到严格模式：一条都不放，语音助手照常报警（他要在真机上对比时靠这个）。 */
    @Test
    public void switchOffRestoresStrictMode() {
        List<String> a11y = GuardSysAi.servicePackages(VOICE);
        assertFalse(GuardSysAi.isTrustedActor("com.xiaomi.mico", false, true, a11y));
    }

    /** 没开无障碍的包不算「能替人动手的执行者」，否则整个系统侧都成盲区。 */
    @Test
    public void systemAppWithoutAccessibilityIsNotAnActor() {
        List<String> a11y = GuardSysAi.servicePackages(VOICE);
        assertFalse(GuardSysAi.isTrustedActor("com.miui.securitycenter", true, true, a11y));
    }

    /** 非系统包但命中内核大厂前缀表（比如用户自己装的 Talkback 族）也放行。 */
    @Test
    public void bigVendorPrefixCountsEvenWhenNotSystem() {
        List<String> a11y = GuardSysAi.servicePackages("com.google.android.marvin.talkback/.TalkBack");
        assertTrue(GuardSysAi.isTrustedActor(
                "com.google.android.marvin.talkback", true, false, a11y));
    }

    /** 自称大厂前缀的伪装包不能蒙混过去（目录语义，与内核同一套匹配）。 */
    @Test
    public void spoofedPrefixIsRefused() {
        List<String> a11y = GuardSysAi.servicePackages(
                "com.xiaomix.evil/.Svc:com.tencentx.evil/.Svc:com.unknown.toolkit/.Svc");
        assertFalse(GuardSysAi.isTrustedActor("com.xiaomix.evil", true, false, a11y));
        assertFalse(GuardSysAi.isTrustedActor("com.tencentx.evil", true, false, a11y));
        // 无名小厂包：开了无障碍也不算可信执行者 —— 这正是这套判定要盯的对象
        assertFalse(GuardSysAi.isTrustedActor("com.unknown.toolkit", true, false, a11y));
    }

    @Test
    public void nullAndEmptyInputsFailClosed() {
        assertFalse(GuardSysAi.isTrustedActor(null, true, true, Arrays.asList("com.a.b")));
        assertFalse(GuardSysAi.isTrustedActor("com.a.b", true, true, null));
        assertFalse(GuardSysAi.isTrustedActor("com.a.b", true, true, Collections.<String>emptyList()));
    }

    // ------------------------------------------------------------ 安装器窗口那条

    /**
     * 「本人在设置里卸载」与「恶意软件静默装包」走的是同一个窗口。
     * 只有手指能分开它们：无人窗口内有过真人触摸 = 本人在操作。
     */
    @Test
    public void installerWindowRightAfterATouchIsTheHuman() {
        assertTrue(GuardSysAi.installerLooksHuman(1_500L, 6_000L));
        assertFalse(GuardSysAi.installerLooksHuman(45_000L, 6_000L));
        assertFalse(GuardSysAi.installerLooksHuman(6_000L, 6_000L));
    }

    /** 取不到触摸时刻时宁可放行：把人锁在保护里、让他只能靠关守护脱身，代价更大。 */
    @Test
    public void unknownTouchTimeSidesWithTheHuman() {
        assertTrue(GuardSysAi.installerLooksHuman(-1L, 6_000L));
    }

    // ---------------------------------------------------------------- 那句话

    @Test
    public void describeNamesWhoGotThrough() {
        assertEquals("没识别到系统级可信执行者（没装语音助手，或它们没开无障碍）",
                GuardSysAi.describe(Collections.<String>emptyList()));
        String s = GuardSysAi.describe(Arrays.asList("com.xiaomi.mico", "com.huawei.vassistant"));
        assertTrue(s, s.contains("放行 2 个"));
        assertTrue(s, s.contains("com.xiaomi.mico"));
        assertTrue(s, s.contains("com.huawei.vassistant"));
    }
}
