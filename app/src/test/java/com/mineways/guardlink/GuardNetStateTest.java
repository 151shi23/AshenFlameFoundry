package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 断网/恢复的开关快照断言。
 *
 * <p>守的就是他报的那句：「恢复网络为啥流量没有开也要开流量」。
 * 原来恢复是硬写两句 {@code svc wifi enable; svc data enable}，压根不看断之前是什么样子，
 * 于是本来关着的流量被凭空打开 —— 这条必须用「断之前关着 ⇒ 恢复命令里绝对没有它」钉死。
 */
public class GuardNetStateTest {

    private static boolean has(GuardNetState.Plan p, String cmd) {
        return p.cmds.contains(cmd);
    }

    // ------------------------------------------------------------------ 断

    @Test
    public void cutSkipsRadiosThatWereAlreadyOff() {
        GuardNetState.Plan p = GuardNetState.cut(Boolean.TRUE, Boolean.FALSE);
        assertEquals(1, p.cmds.size());
        assertTrue(has(p, "svc wifi disable"));
        assertFalse("本来就关着的流量不该再关一次", has(p, "svc data disable"));
        assertTrue(p.note, p.note.contains("没动 数据流量（本来就关着）"));
    }

    @Test
    public void cutStillCutsWhenStateIsUnreadable() {
        // 保护优先：读不到也要断干净，不能因为「不确定」就留一条通路
        GuardNetState.Plan p = GuardNetState.cut(null, null);
        assertEquals(2, p.cmds.size());
        assertTrue(p.note, p.note.contains("WiFi（状态读不到，照断）"));
        assertTrue(p.note, p.note.contains("数据流量（状态读不到，照断）"));
    }

    @Test
    public void cutWithEverythingOffIssuesNothing() {
        GuardNetState.Plan p = GuardNetState.cut(Boolean.FALSE, Boolean.FALSE);
        assertTrue(p.isEmpty());
        assertTrue(p.note, p.note.contains("一个没动"));
    }

    // ------------------------------------------------------------------ 恢复

    /** 他这条抱怨的正例：断之前流量就关着 → 恢复时绝对不许出现 svc data enable。 */
    @Test
    public void restoreNeverTurnsOnWhatWasOffBefore() {
        GuardNetState.Plan p = GuardNetState.restore(true,
                GuardNetState.ON, GuardNetState.OFF);
        assertEquals(1, p.cmds.size());
        assertTrue(has(p, "svc wifi enable"));
        assertFalse(p.cmds.toString(), has(p, "svc data enable"));
        assertTrue(p.note, p.note.contains("数据流量（断之前你就关着，不替你打开）"));
        assertTrue(p.note, p.note.contains("已恢复 WiFi"));
    }

    /** 读不到状态的那一样也不碰：宁可让他自己看一眼，不能凭空开走流量。 */
    @Test
    public void restoreLeavesUnknownAlone() {
        GuardNetState.Plan p = GuardNetState.restore(true,
                GuardNetState.UNKNOWN, GuardNetState.ON);
        assertEquals(1, p.cmds.size());
        assertTrue(has(p, "svc data enable"));
        assertFalse(p.cmds.toString(), has(p, "svc wifi enable"));
        assertTrue(p.note, p.note.contains("WiFi（断之前读不到状态，没敢替你打开）"));
    }

    @Test
    public void restoreTurnsOnBothWhenBothWereOn() {
        GuardNetState.Plan p = GuardNetState.restore(true, GuardNetState.ON, GuardNetState.ON);
        assertEquals(2, p.cmds.size());
        assertTrue(p.note, p.note.contains("已恢复 WiFi 与 数据流量"));
    }

    /** 没断过就一条都不下发 —— 精确断网（iptables）不碰总开关，恢复不该顺带打开什么。 */
    @Test
    public void restoreWithoutSnapshotDoesNothing() {
        GuardNetState.Plan p = GuardNetState.restore(false, GuardNetState.ON, GuardNetState.ON);
        assertTrue(p.isEmpty());
        assertTrue(p.note, p.note.contains("没有切断过网络总开关"));
    }

    @Test
    public void everythingOffBeforeMeansNothingToRestore() {
        GuardNetState.Plan p = GuardNetState.restore(true, GuardNetState.OFF, GuardNetState.OFF);
        assertTrue(p.isEmpty());
        assertEquals("保持原样 WiFi（断之前你就关着，不替你打开） 与 数据流量（断之前你就关着，不替你打开）",
                p.note);
    }

    // ------------------------------------------------------------------ 读数

    @Test
    public void radioValuesParseIntoTriState() {
        assertEquals(Boolean.TRUE, GuardNetState.parseRadio("1"));
        assertEquals(Boolean.TRUE, GuardNetState.parseRadio(" 1 \n"));
        assertEquals(Boolean.FALSE, GuardNetState.parseRadio("0"));
        assertEquals(Boolean.TRUE, GuardNetState.parseRadio("true"));
        assertEquals(Boolean.FALSE, GuardNetState.parseRadio("false"));
    }

    /** 看不懂的一律算「读不到」，绝不能猜成开着。 */
    @Test
    public void unreadableRadioValuesAreNotGuessed() {
        assertNull(GuardNetState.parseRadio(null));
        assertNull(GuardNetState.parseRadio(""));
        assertNull(GuardNetState.parseRadio("null"));
        assertNull(GuardNetState.parseRadio("NULL"));
        assertNull(GuardNetState.parseRadio("SecurityException: requires ..."));
        assertNull(GuardNetState.parseRadio("on"));
    }

    @Test
    public void radioValueToleratesNoiseAroundTheNumber() {
        // 有的 ROM 一句 settings get 前后还会打别的行，取第一个看得懂的 token
        assertEquals(Boolean.TRUE, GuardNetState.parseRadio("warning: x\n1"));
    }
}
