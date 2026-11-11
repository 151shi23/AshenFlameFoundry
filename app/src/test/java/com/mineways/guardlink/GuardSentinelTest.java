package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 未触发态哨兵（§8.3.158）的纯逻辑回归钉子。
 *
 * <p>钉的是四件事，全都是「写错就静默失效」的类型：
 * ① flat 形状不对时必须跳过而不是猜（猜错一次就白发或漏发一条最高置信警报）；
 * ② 信任判定必须是目录语义（{@code com.tencent.} 放行子包、不放行 {@code com.tencentx.evil}）；
 * ③ 差分的键是「整个 flat 的小写」，不是包名 —— 同一个包换了个服务类就是一条新警报；
 * ④ 首帧只建基线，无线调试优先于 USB 调试。
 */
public class GuardSentinelTest {

    private static Set<String> set(String... items) {
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, items);
        return out;
    }

    // ---------------------------------------------------------------- flat 解析

    @Test
    public void flatSplitsPackageFromServiceClass() {
        assertEquals("com.evil.remote",
                GuardSentinel.flatPkg("com.evil.remote/.MediaActivity"));
        assertEquals("com.evil.remote",
                GuardSentinel.flatPkg("  com.evil.remote/.MediaActivity  "));
        assertEquals("com.android.talkback",
                GuardSentinel.flatPkg(
                        "com.android.talkback/com.android.talkback.TalkBackService"));
    }

    /** 形状不对一律 null：宁可漏一次，也不能把 {@code com.evil} 猜成包名去报警。 */
    @Test
    public void malformedFlatsAreSkippedNotGuessed() {
        assertNull(GuardSentinel.flatPkg(null));
        assertNull(GuardSentinel.flatPkg(""));
        assertNull(GuardSentinel.flatPkg("   "));
        assertNull(GuardSentinel.flatPkg("com.evil.remote"));    // 没有斜杠
        assertNull(GuardSentinel.flatPkg("/.MediaActivity"));    // 包名为空
        assertNull(GuardSentinel.flatPkg("com.evil.remote/"));   // 类名为空
    }

    // ---------------------------------------------------------------- 差分

    @Test
    public void onlyNewUntrustedHoldersAreReported() {
        List<String> current = Arrays.asList(
                "com.android.talkback/com.android.talkback.TalkBackService", // 系统/信任
                "com.evil.old/.Spy",                                          // 基线里已有
                "com.evil.new/.MediaActivity",                                // 新 → 报
                "com.evil.new/.MediaActivity");                               // 重复 → 仍只报一次
        List<String> news = GuardSentinel.newUntrustedAccessibility(
                current, set("com.evil.old/.spy"), set("com.android.talkback", "com.mineways"));
        assertEquals(1, news.size());
        assertEquals("com.evil.new/.MediaActivity", news.get(0));
    }

    /** 同一个包换了个服务类，就是一条新警报 —— 差分的键必须是整个 flat。 */
    @Test
    public void samePackageWithDifferentServiceIsNew() {
        List<String> current = Arrays.asList("com.evil.remote/.MediaActivity");
        List<String> news = GuardSentinel.newUntrustedAccessibility(
                current, set("com.evil.remote/.otherservice"), Collections.<String>emptySet());
        assertEquals(1, news.size());
        assertEquals("com.evil.remote/.MediaActivity", news.get(0));
    }

    /** 落盘基线是小写，系统给的可能大小写混写；比对必须归一，否则同一条会反复叫。 */
    @Test
    public void knownComparisonIgnoresCase() {
        List<String> news = GuardSentinel.newUntrustedAccessibility(
                Arrays.asList("COM.Evil.Old/.Spy"),
                set("com.evil.old/.spy"),
                Collections.<String>emptySet());
        assertTrue(news.isEmpty());
    }

    @Test
    public void nullOrEmptyCurrentScanIsQuiet() {
        assertTrue(GuardSentinel.newUntrustedAccessibility(
                null, Collections.<String>emptySet(), Collections.<String>emptySet()).isEmpty());
        assertTrue(GuardSentinel.newUntrustedAccessibility(
                new ArrayList<String>(), set("x/y"), set("x")).isEmpty());
    }

    /** null 基线与空集合语义相同（首扫走的是别一条路，但逻辑层不能 NPE）。 */
    @Test
    public void nullKnownDoesNotThrow() {
        List<String> news = GuardSentinel.newUntrustedAccessibility(
                Arrays.asList("com.evil.new/.Spy"), null, set("com.android.talkback"));
        assertEquals(1, news.size());
    }

    // ---------------------------------------------------------------- 信任语义

    @Test
    public void trustedUsesDirectorySemantics() {
        assertTrue(GuardSentinel.trusted(set("com.tencent."), "com.tencent.mm"));
        assertTrue(GuardSentinel.trusted(set("com.tencent"), "com.tencent.mm"));
        assertTrue(GuardSentinel.trusted(set("com.tencent."), "com.tencent"));
        assertFalse(GuardSentinel.trusted(set("com.tencent."), "com.tencentx.evil"));
        assertFalse(GuardSentinel.trusted(set("com.tencent."), "com.tencentservice"));
    }

    @Test
    public void trustedNormalizesHandwrittenEntries() {
        assertTrue(GuardSentinel.trusted(set("  COM.Tencent.  "), "com.tencent.mm"));
    }

    @Test
    public void emptyOrGarbageTrustListTrustsNobody() {
        assertFalse(GuardSentinel.trusted(null, "com.any.pkg"));
        assertFalse(GuardSentinel.trusted(set(""), "com.any.pkg"));
        assertFalse(GuardSentinel.trusted(set("."), "com.any.pkg"));
        assertFalse(GuardSentinel.trusted(set("com.tencent."), null));
    }

    // ---------------------------------------------------------------- ADB 变迁

    /** 首帧（prev 未知）只建基线：守护一启动就对着本来开着的无线调试嚎是纯误报。 */
    @Test
    public void firstFrameOnlyBuildsBaseline() {
        assertEquals(GuardSentinel.AdbChange.NONE,
                GuardSentinel.adbChange(null, true, null, true));
        assertEquals(GuardSentinel.AdbChange.NONE,
                GuardSentinel.adbChange(null, false, null, false));
    }

    @Test
    public void offToOnIsReported() {
        assertEquals(GuardSentinel.AdbChange.WIRELESS_ON,
                GuardSentinel.adbChange(Boolean.FALSE, false, Boolean.FALSE, true));
        assertEquals(GuardSentinel.AdbChange.ADB_ON,
                GuardSentinel.adbChange(Boolean.FALSE, true, null, false));
    }

    /** 无线调试优先：两条同时翻开时只报更危险的那条（scrcpy 免 root 远控的入口）。 */
    @Test
    public void wirelessWinsWhenBothFlip() {
        assertEquals(GuardSentinel.AdbChange.WIRELESS_ON,
                GuardSentinel.adbChange(Boolean.FALSE, true, Boolean.FALSE, true));
    }

    @Test
    public void steadyStateAndTurningOffAreNotChanges() {
        assertEquals(GuardSentinel.AdbChange.NONE,
                GuardSentinel.adbChange(Boolean.TRUE, true, Boolean.TRUE, true));
        assertEquals(GuardSentinel.AdbChange.NONE,
                GuardSentinel.adbChange(Boolean.TRUE, false, Boolean.TRUE, false));
        assertEquals(GuardSentinel.AdbChange.NONE,
                GuardSentinel.adbChange(Boolean.FALSE, false, Boolean.TRUE, false));
    }

    // ---------------------------------------------------------------- 通知监听 / 设备管理员

    /** 通知监听与设备管理员是同一种 flat 形状，共用一份差分：新包必须报、已知包必须静。 */
    @Test
    public void genericFlatDiffCoversListenersAndAdmins() {
        List<String> current = Arrays.asList(
                "com.android.systemui/.NotificationListener",   // 系统信任
                "com.evil.reader/.NotifyListener",              // 新 → 报
                "com.evil.old/.AdminReceiver");                 // 已知 → 静
        List<String> news = GuardSentinel.newUntrustedFlats(
                current, set("com.evil.old/.adminreceiver"), set("com.android.systemui"));
        assertEquals(1, news.size());
        assertEquals("com.evil.reader/.NotifyListener", news.get(0));
    }

    // ---------------------------------------------------------------- 默认短信

    /** 首帧不报：守护第一次起在换过短信 App 的设备上也不该立刻喊「被换掉」。 */
    @Test
    public void defaultSmsFirstFrameIsBaselineOnly() {
        assertFalse(GuardSentinel.defaultSmsChanged(null, "com.evil.sms", set("com.tencent.")));
        assertFalse(GuardSentinel.defaultSmsChanged("com.evil.sms", null, set("com.tencent.")));
        assertFalse(GuardSentinel.defaultSmsChanged("", "com.evil.sms", set("com.tencent.")));
    }

    @Test
    public void defaultSmsChangeToUntrustedIsReported() {
        assertTrue(GuardSentinel.defaultSmsChanged("com.android.messaging", "com.evil.sms",
                Collections.<String>emptySet()));
        assertTrue(GuardSentinel.defaultSmsChanged("com.android.messaging", "com.evil.sms",
                set("com.tencent.")));
    }

    /** 换成可信应用（或只是大小写差异）不报 —— 正常用户换短信 App 不该被打扰。 */
    @Test
    public void defaultSmsChangeToTrustedOrSameIsQuiet() {
        assertFalse(GuardSentinel.defaultSmsChanged("com.android.messaging", "com.tencent.mm",
                set("com.tencent.")));
        assertFalse(GuardSentinel.defaultSmsChanged("com.evil.sms", "COM.EVIL.SMS",
                Collections.<String>emptySet()));
    }

    // ---------------------------------------------------------------- VPN

    @Test
    public void vpnOnlyReportsOffToOnTransition() {
        assertFalse(GuardSentinel.vpnActivated(null, true));    // 首帧建基线
        assertTrue(GuardSentinel.vpnActivated(Boolean.FALSE, true));
        assertFalse(GuardSentinel.vpnActivated(Boolean.TRUE, true));   // 一直开着
        assertFalse(GuardSentinel.vpnActivated(Boolean.TRUE, false));  // 断开不报
    }
}
