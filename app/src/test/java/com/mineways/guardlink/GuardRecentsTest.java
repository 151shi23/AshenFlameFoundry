package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 「最近任务里隐藏守护页」的判定断言。
 *
 * <p>他要的是「在后台里隐藏此应用」，但他同时否掉了「整个 App 都不见」——工具箱首页的卡片必须还在，
 * 否则他自己都找不回入口。这条边界全靠「只动栈顶是守护页的那条任务」，所以必须钉住：
 * 越过这条线就是把他锁在外面，不到这条线就是划一下就没守护。
 */
public class GuardRecentsTest {

    private static final String GUARD = GuardRecents.GUARD_ACTIVITY;
    private static final String CONFIRM = GuardRecents.CONFIRM_ACTIVITY;

    /** 记下每条卡片被下过什么指令：没被碰过的保持 {@code null}。 */
    private static final class FakeTask implements GuardRecents.Task {
        final String top;
        Boolean last;

        FakeTask(String top) {
            this.top = top;
        }

        @Override
        public String topClass() {
            return top;
        }

        @Override
        public void exclude(boolean exclude) {
            last = exclude;
        }
    }

    @Test
    public void onlyTheGuardPageTaskIsOurs() {
        assertTrue(GuardRecents.targets(GUARD));
        assertFalse("确认窗那条另有清单里的 excludeFromRecents", GuardRecents.targets(CONFIRM));
        assertFalse("工具箱首页绝不能被我们动", GuardRecents.targets("com.mineways.MainActivity"));
        assertFalse(GuardRecents.targets(null));
        assertFalse(GuardRecents.targets(""));
        assertFalse("同名前缀的别人家页面也不行", GuardRecents.targets(GUARD + "X"));
    }

    @Test
    public void leavingToAnotherPageRestoresTheCard() {
        assertTrue(GuardRecents.shouldRestore("com.mineways.MainActivity"));
        assertFalse("守护页自己回到前台不挂回", GuardRecents.shouldRestore(GUARD));
        assertFalse("确认窗也不算离开", GuardRecents.shouldRestore(CONFIRM));
        assertFalse(GuardRecents.shouldRestore(null));
        assertFalse(GuardRecents.shouldRestore(""));
    }

    @Test
    public void hideTouchesOnlyTheGuardTask() {
        FakeTask guard = new FakeTask(GUARD);
        FakeTask main = new FakeTask("com.mineways.MainActivity");
        String note = GuardRecents.apply(Arrays.asList(main, guard), true);
        assertEquals(Boolean.TRUE, guard.last);
        assertNullNote(main);
        assertTrue(note, note.contains("摘掉 1 条"));
    }

    @Test
    public void restoreAlsoLeavesOtherTasksAlone() {
        FakeTask guard = new FakeTask(GUARD);
        FakeTask main = new FakeTask("com.mineways.MainActivity");
        String note = GuardRecents.apply(Arrays.asList(guard, main), false);
        assertEquals(Boolean.FALSE, guard.last);
        assertNullNote(main);
        assertTrue(note, note.contains("挂回最近任务 1 条"));
    }

    /** ROM 不给栈顶时什么都不改，并把这句实话留在界面上。 */
    @Test
    public void unknownTopActivityMeansNoGuessing() {
        FakeTask unknown = new FakeTask(null);
        FakeTask blank = new FakeTask("");
        String note = GuardRecents.apply(Arrays.asList(unknown, blank), true);
        assertNullNote(unknown);
        assertNullNote(blank);
        assertTrue(note, note.contains("没给出任务栈顶"));
    }

    @Test
    public void guardTaskPlusUnknownOnesStillHidesTheGuardOne() {
        FakeTask guard = new FakeTask(GUARD);
        FakeTask unknown = new FakeTask(null);
        FakeTask main = new FakeTask("com.mineways.MainActivity");
        String note = GuardRecents.apply(Arrays.asList(guard, unknown, main), true);
        assertEquals(Boolean.TRUE, guard.last);
        assertNullNote(unknown);
        assertTrue("混着读不到的也要说清只动了几条", note.contains("摘掉 1 条"));
        assertTrue(note, note.contains("另有 1 条"));
    }

    @Test
    public void emptyTaskListSaysSo() {
        String note = GuardRecents.apply(Collections.<GuardRecents.Task>emptyList(), true);
        assertTrue(note, note.contains("没给出本应用的最近任务条目"));
        assertTrue(GuardRecents.apply(null, false).contains("没给出本应用的最近任务条目"));
    }

    /** 前台只开着工具箱时点这颗按钮：没有守护页那条卡片，就不该假装摘掉了什么。 */
    @Test
    public void nothingToDoIsReportedAsNothingToDo() {
        FakeTask main = new FakeTask("com.mineways.MainActivity");
        String note = GuardRecents.apply(new ArrayList<>(Arrays.asList(main)), true);
        assertNullNote(main);
        assertEquals("当前没有以守护页为栈顶的任务，卡片没动", note);
    }

    @Test
    public void nullEntriesAreSkipped() {
        List<GuardRecents.Task> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(new FakeTask(GUARD));
        String note = GuardRecents.apply(withNull, true);
        assertTrue(note, note.contains("摘掉 1 条"));
    }

    private void assertNullNote(FakeTask t) {
        assertTrue(t.top + " 不该被动过: " + t.last, t.last == null);
    }
}
