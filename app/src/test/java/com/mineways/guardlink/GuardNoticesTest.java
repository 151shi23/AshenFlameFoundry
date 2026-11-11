package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 通知节奏的规则断言。
 *
 * <p>这一小块之所以要单独抽出来测：名额分配（一波只发一条）如果散在四个调用点各写一遍，
 * 写歪一处的结果要么是他抱怨的「响成一片」，要么是更糟的「真触发了却一条都没发」。
 */
public class GuardNoticesTest {

    @Test
    public void firstAlertOfAnEpisodeIsAlwaysSent() {
        GuardNotices n = new GuardNotices();
        n.episodeStarts();
        assertTrue("一波的第一条通知必须发出去", n.claim(1000L));
        assertEquals(1000L, n.firstAt());
    }

    @Test
    public void laterEventsInSameEpisodeGetNoNotification() {
        GuardNotices n = new GuardNotices();
        n.episodeStarts();
        assertTrue(n.claim(2000L));
        // 装卸拦截、网络复活、确认窗兜底这些同波事件都来抢名额
        assertFalse(n.claim(2500L));
        assertFalse(n.claim(3000L));
        // 但首发时刻不能被覆盖，界面要说的是「几点发过」
        assertEquals(2000L, n.firstAt());
    }

    /** 波与波之间要重新算：撤防之后再触发，得再发一次通知。 */
    @Test
    public void endedEpisodeFreesTheSlotForTheNextOne() {
        GuardNotices n = new GuardNotices();
        n.episodeStarts();
        assertTrue(n.claim(1000L));
        assertFalse(n.claim(1500L));
        n.episodeEnds();
        assertFalse(n.inEpisode());
        n.episodeStarts();
        assertTrue(n.claim(9000L));
        assertEquals(9000L, n.firstAt());
    }

    @Test
    public void episodeFlagFollowsTheEdgesOnly() {
        GuardNotices n = new GuardNotices();
        assertFalse(n.inEpisode());
        assertFalse(n.notified());
        n.episodeStarts();
        assertTrue(n.inEpisode());
        assertFalse("刚开始还没发通知", n.notified());
        n.claim(10L);
        assertTrue(n.notified());
    }

    /** 结束边沿若被漏掉（进程被回收后重开），reset 必须能彻底清干净。 */
    @Test
    public void resetClearsEverything() {
        GuardNotices n = new GuardNotices();
        n.episodeStarts();
        n.claim(10L);
        n.gapsChanged("Shizuku 未授权");
        n.reset();
        assertFalse(n.inEpisode());
        assertFalse(n.notified());
        assertEquals(0L, n.firstAt());
        assertTrue("降级缺口文案也要重新算", n.gapsChanged("Shizuku 未授权"));
    }

    @Test
    public void degradationNoticeFiresOnlyWhenTheGapsChange() {
        GuardNotices n = new GuardNotices();
        assertTrue(n.gapsChanged("无障碍未启用"));
        assertFalse("同一串缺口不该每次健康心跳都补一条", n.gapsChanged("无障碍未启用"));
        assertFalse("没有缺口就不发", n.gapsChanged("   "));
        assertFalse(n.gapsChanged(null));
        assertTrue("缺口变多了要再发", n.gapsChanged("无障碍未启用；Shizuku 未授权"));
    }

    @Test
    public void describeTellsTheTruthForEachState() {
        GuardNotices idle = new GuardNotices();
        assertTrue(idle.describe("10:00").contains("现在没有进行中"));

        GuardNotices fresh = new GuardNotices();
        fresh.episodeStarts();
        assertTrue(fresh.describe("10:00").contains("还没发通知"));

        GuardNotices done = new GuardNotices();
        done.episodeStarts();
        done.claim(System.currentTimeMillis());
        String s = done.describe("09:58");
        assertTrue(s.contains("09:58"));
        assertTrue(s.contains("不再打扰"));
    }
}
