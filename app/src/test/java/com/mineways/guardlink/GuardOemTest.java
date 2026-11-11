package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 厂商页面候选表与「逐个试探」的断言。
 *
 * <p>这里守的是两件他都会当场发现的事：一是打不开时不许假装打开了（返回值必须是试探出来的），
 * 二是名单里不许出现我凭印象手写的组件名 —— 编出来的类名带空格、包名不像包名，一测就露馅。
 */
public class GuardOemTest {

    /**
     * 记录试过的顺序，并按名单决定谁能打开。
     *
     * <p>真机上「打不开」表现为 {@code ActivityNotFoundException}；纯函数只认「抛异常或返回
     * false 都算打不开」，所以这里用普通异常与 false 两种各测一遍（在 JVM 里实例化 android
     * 那个异常类只会拿到桩类的 {@code Stub!}，测不到我要测的东西）。
     */
    private static final class FakeTester implements GuardOem.Tester {
        final List<String> tried = new ArrayList<>();
        private final String opens;
        private final boolean throwsOnMissing;

        FakeTester(String opens) {
            this(opens, true);
        }

        FakeTester(String opens, boolean throwsOnMissing) {
            this.opens = opens;
            this.throwsOnMissing = throwsOnMissing;
        }

        @Override
        public boolean tryStart(GuardOem.Page p) {
            tried.add(p.label);
            if (p.label.equals(opens)) {
                return true;
            }
            if (throwsOnMissing) {
                throw new IllegalStateException("no such page: " + p.label);
            }
            return false;
        }
    }

    @Test
    public void probeReturnsTheFirstPageThatActuallyOpens() {
        FakeTester t = new FakeTester("华为/荣耀 应用启动管理");
        GuardOem.Page hit = GuardOem.probe(GuardOem.AUTOSTART, t);
        assertNotNull(hit);
        assertEquals("华为/荣耀 应用启动管理", hit.label);
        assertTrue("前面打不开的也该试过", t.tried.size() >= 3);
    }

    @Test
    public void probeStopsAtTheFirstSuccess() {
        FakeTester t = new FakeTester("小米/红米 自启动管理");
        assertSame(GuardOem.AUTOSTART[0], GuardOem.probe(GuardOem.AUTOSTART, t));
        assertEquals(1, t.tried.size());
    }

    /** 全打不开必须返回 null，调用方据此说「一个都没打开」，不能谎报。 */
    @Test
    public void probeGivesUpHonestly() {
        FakeTester t = new FakeTester("这一台机器上一个都不存在");
        assertNull(GuardOem.probe(GuardOem.BATTERY, t));
        assertEquals(GuardOem.BATTERY.length, t.tried.size());
    }

    /** 有的 ROM 打不开时不抛异常，只是没反应 —— 返回 false 也一样要退到下一档。 */
    @Test
    public void probeFallsThroughOnSilentFailure() {
        FakeTester t = new FakeTester("", false);
        assertNull(GuardOem.probe(GuardOem.AUTOSTART, t));
        assertEquals(GuardOem.AUTOSTART.length, t.tried.size());
    }

    @Test
    public void nullArgumentsDoNotBlowUp() {
        assertNull(GuardOem.probe(null, new FakeTester("x")));
        assertNull(GuardOem.probe(GuardOem.AUTOSTART, null));
    }

    // ------------------------------------------------------------- 名单本身

    /** 每一档都要有可用的路由：要么有 action，要么包名 + 类名齐全。 */
    @Test
    public void everyCandidateHasAUsableRoute() {
        check(GuardOem.AUTOSTART);
        check(GuardOem.BATTERY);
    }

    private void check(GuardOem.Page[] pages) {
        Set<String> seen = new HashSet<>();
        for (GuardOem.Page p : pages) {
            assertNotNull(p);
            assertNotNull(p.label);
            assertTrue("名单不许重复：" + p.label, seen.add(p.label));
            boolean hasAction = p.action != null && p.action.length() > 0;
            if (hasAction) {
                assertTrue("action 必须是点分形式：" + p.action, p.action.contains("."));
                assertFalse(p.action.contains(" "));
            } else {
                qualified("pkg", p.pkg, p.label);
                qualified("cls", p.cls, p.label);
            }
        }
    }

    /**
     * 组件名得像个组件名。
     *
     * <p>这条不是洁癖：上一轮我照印象补过一个 vivo 的页面，写出来的类名里带了一个空格
     * （{@code …BatteryHib ernateViewActiviy}），这种东西 startActivity 一定抛异常，
     * 等于名单里白占一格。有格式断言就当场拦下。
     */
    private void qualified(String what, String v, String label) {
        assertNotNull(what + " 缺失：" + label, v);
        assertFalse(what + " 带空格：" + label, v.contains(" "));
        assertTrue(what + " 不像全限定名：" + label, v.matches("[A-Za-z0-9_$.]+\\.[A-Za-z0-9_$.]+"));
        assertFalse(what + " 不能是相对名：" + label, v.startsWith("."));
    }

    /** 最后一档必须是通用页：厂商页全被裁掉时也要有个出口。 */
    @Test
    public void autostartListEndsWithTheGenericPage() {
        GuardOem.Page last = GuardOem.AUTOSTART[GuardOem.AUTOSTART.length - 1];
        assertEquals(GuardOem.ACTION_APP_DETAILS, last.action);
        assertTrue(last.describe().contains("系统设置"));
    }

    @Test
    public void batteryListEndsWithTheGenericPages() {
        GuardOem.Page[] b = GuardOem.BATTERY;
        assertEquals(GuardOem.ACTION_APP_DETAILS, b[b.length - 1].action);
        assertEquals(GuardOem.ACTION_BATTERY_OPTS, b[b.length - 2].action);
    }

    /** 组件页要说清是哪家，通用页要说清是系统设置 —— 界面那一句靠这个。 */
    @Test
    public void describeNamesTheOwner() {
        assertEquals("小米/红米 自启动管理（com.miui.securitycenter）",
                GuardOem.AUTOSTART[0].describe());
    }

    @Test
    public void labelsCoverEveryCandidate() {
        assertEquals(GuardOem.AUTOSTART.length, GuardOem.labels(GuardOem.AUTOSTART).size());
        assertTrue(GuardOem.labels(GuardOem.AUTOSTART).contains(
                "通用：应用信息页（自己找自启动/省电项）"));
        assertFalse(GuardOem.labels(null).iterator().hasNext());
    }

    /** 华米 OV 四家都要在名单里（他点名要适配的那几家）。 */
    @Test
    public void allFourVendorsAreCovered() {
        String all = GuardOem.labels(GuardOem.AUTOSTART).toString();
        for (String need : new String[]{"小米", "华为", "OPPO", "vivo", "荣耀", "realme", "魅族"}) {
            assertTrue("缺少 " + need + " 的入口", all.contains(need));
        }
    }
}
