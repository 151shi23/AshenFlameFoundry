package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 无障碍自愈的节奏断言。
 *
 * <p>要守住的正是他报的那条：「Shizuku 权限给了，它也不会把自己拉起来」。
 * 节流、条件、还有「补回去时不许清掉别人家的无障碍服务」，三条都在这里钉住。
 */
public class GuardHealTest {

    @Test
    public void noNeedToHealWhileAccessibilityIsRunning() {
        GuardHeal h = new GuardHeal();
        assertFalse(h.shouldTry(1_000L, true, true));
    }

    @Test
    public void withoutShizukuThereIsNoWayToWriteItBack() {
        GuardHeal h = new GuardHeal();
        assertFalse("Shizuku 没在跑就别白试", h.shouldTry(1_000L, false, false));
    }

    @Test
    public void firstTryIsAllowedImmediately() {
        GuardHeal h = new GuardHeal();
        assertTrue(h.shouldTry(1_000L, false, true));
    }

    /** 拦不住的 ROM 上每 2 秒写一次 secure 设置既没用又会把事件流刷爆。 */
    @Test
    public void triesAreThrottled() {
        GuardHeal h = new GuardHeal();
        h.record(0L, false, "命令成功但系统没认");
        assertFalse(h.shouldTry(GuardHeal.TRY_INTERVAL_MS - 1L, false, true));
        assertTrue(h.shouldTry(GuardHeal.TRY_INTERVAL_MS, false, true));
    }

    @Test
    public void throttlingRestartsFromTheLatestTry() {
        GuardHeal h = new GuardHeal();
        h.record(100_000L, false, "还是没认");
        assertTrue(h.shouldTry(115_000L, false, true));
        h.record(115_000L, false, "还是没认");
        assertFalse(h.shouldTry(129_999L, false, true));
    }

    @Test
    public void outcomeKeepsCountersAndReason() {
        GuardHeal h = new GuardHeal();
        assertEquals("还没需要自愈（无障碍一直在）", h.describe());
        h.record(1L, false, "这台 ROM 拦了改无障碍设置");
        assertEquals(1, h.tries());
        assertEquals(0, h.successes());
        String s = h.describe();
        assertTrue(s.contains("拦了改无障碍设置"));
        assertTrue(s.contains("已试 1 次"));
        h.record(20_000L, true, null);
        assertEquals(1, h.successes());
        assertTrue(h.describe().contains("成功 1 次"));
    }

    /** 试过但一次都没成 = 被拦住，界面要换成劝人手动开的那句话。 */
    @Test
    public void stuckMeansTriedAndNeverSucceeded() {
        GuardHeal h = new GuardHeal();
        assertFalse("没试过不算卡住", h.stuck());
        h.record(1L, false, "写不进去");
        assertTrue(h.stuck());
        h.record(2L, true, "已重新启用");
        assertFalse("成功过一次就不算反复失败", h.stuck());
    }

    @Test
    public void resetClearsCountersForTheNextGuardRun() {
        GuardHeal h = new GuardHeal();
        h.record(1L, false, "失败");
        h.reset();
        assertEquals(0, h.tries());
        assertEquals(0, h.successes());
        assertFalse(h.stuck());
        assertTrue(h.shouldTry(1L, false, true));
    }

    // ------------------------------------------------------------- 合并那一串

    @Test
    public void mergesIntoEmptyList() {
        assertEquals("com.mineways/.GuardAccessibilityService",
                GuardHeal.mergeOwnInto("", "com.mineways/.GuardAccessibilityService"));
        assertEquals("com.mineways/.GuardAccessibilityService",
                GuardHeal.mergeOwnInto(null, "com.mineways/.GuardAccessibilityService"));
    }

    /** 写覆盖式的一串会把用户开的其它无障碍服务清掉 —— 那是事故，必须追加。 */
    @Test
    public void keepsOtherServicesWhenAddingOwn() {
        String own = "com.mineways/.GuardAccessibilityService";
        String others = "com.miui.hybrid/.Foo:com.google.marvin.talkback/.TalkBack";
        assertEquals(others + ":" + own, GuardHeal.mergeOwnInto(others, own));
    }

    @Test
    public void neverAppendsItselfTwice() {
        String own = "com.mineways/.GuardAccessibilityService";
        String once = GuardHeal.mergeOwnInto("", own);
        String twice = GuardHeal.mergeOwnInto(once, own);
        assertEquals(once, twice);
        assertEquals("com.android.settings/.Foo:" + own,
                GuardHeal.mergeOwnInto(" com.android.settings/.Foo ", own));
    }

    @Test
    public void alreadyPresentServiceIsLeftAloneEvenWithSpaces() {
        String own = "com.mineways/.GuardAccessibilityService";
        String cur = "com.a/.B : " + own;
        assertEquals(cur.trim(), GuardHeal.mergeOwnInto(cur, own));
    }

    @Test
    public void noOwnFlatMeansNothingChanges() {
        assertEquals("com.a/.B", GuardHeal.mergeOwnInto("com.a/.B", null));
        assertEquals("", GuardHeal.mergeOwnInto(null, ""));
    }
}
