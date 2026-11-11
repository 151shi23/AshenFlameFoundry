package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zeus.guardlink.GuardLinkCore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 「打开一个软件就误报」这条的断言。
 *
 * <p>内核的 {@code onPendingTimeout()} 是<b>到点必炸</b>：进了缓冲期，2.5 秒内没有真人触摸就 fire，
 * 而「安静」是它唯一的退出条件 —— 点开 App 后启动页自己跳主页（一次窗口变化）+ 你正在等加载
 * 没碰屏幕，正好凑齐。这里两件事都要钉住：
 * ① 不加闸门时内核<b>确实会炸</b>（这是上游行为，别将来当自己修好的）；
 * ② 加了闸门（{@link GuardPremise#mayFireOnTimeout}）后这种一次性自翻页<b>不许报</b>，
 *    而持续操控、以及他把实锤次数拨到 1 的场景<b>照旧要报</b>。
 */
public class GuardPremiseTest {

    private static final long W = 6_000L;
    private static final long BUF = 2_500L;

    /** 收 fire 回调，并把状态机跑起来的最小宿主。 */
    private static final class Sink implements GuardLinkCore.NoHumanDetector.Listener {
        final List<GuardLinkCore.NoHumanDetector.TriggerResult> fired = new ArrayList<>();

        @Override
        public void onNoHuman(GuardLinkCore.NoHumanDetector.TriggerResult t) {
            fired.add(t);
        }
    }

    private Sink sink;
    private GuardLinkCore.NoHumanDetector d;

    private long savedPresence;

    @Before
    public void pinWindows() {
        // 这几个字段是全局 volatile（GuardParamsTest 也会改），本类自己钉死出厂值
        GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS = W;
        GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS = BUF;
        GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST = 2;
        // 本类钉的是内核 + 闸门的旧语义，先关掉真人驻留期（它由场景P/GuardParams 管）
        savedPresence = GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS;
        GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = 0;
        GuardLinkCore.NoHumanDetector.EXTRA_WHITELIST.clear();
        sink = new Sink();
        d = new GuardLinkCore.NoHumanDetector(sink);
    }

    @After
    public void restorePresence() {
        GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = savedPresence;
    }

    private void touch(long ts) {
        d.feed(new GuardLinkCore.InteractionEvent(ts,
                GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, "com.user.app", 0L, false));
    }

    private void window(long ts, String pkg) {
        d.feed(new GuardLinkCore.InteractionEvent(ts,
                GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, pkg, 0L, false));
    }

    /** 上游行为：一次窗口变化 + 缓冲期内安静 = 炸。这正是他报的现象。 */
    @Test
    public void kernelAloneFiresOnASingleSplashJump() {
        touch(0L);
        window(7_000L, "com.shop.app");       // 启动页自己跳到主页，距上次触摸 7s
        assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d.getState());
        d.onPendingTimeout(9_500L);           // 心跳到点，内核不问缘由
        assertEquals(1, sink.fired.size());
    }

    /** 同一串事件，加上闸门就不许炸：缓冲期内界面没再跟着动 = 不是持续操控。 */
    @Test
    public void gateCancelsTheLoneWindowChange() {
        touch(0L);
        window(7_000L, "com.shop.app");
        assertFalse(GuardPremise.mayFireOnTimeout(2, 0, true));
        d.reset();                             // 心跳里就是这么撤的（reset 不动触摸基准）
        assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());
        assertTrue(sink.fired.isEmpty());
    }

    /**
     * 真在自动化时界面会一直跟着动：闸门必须放行，不能把保护一起放宽掉。
     *
     * <p>端到端那半段顺手把内核的真实语义记下来：{@code PENDING_ACTIVITY_BURST=2} 指的是
     * 进缓冲期<b>之后</b>还要两次活动才实锤（开 PENDING 那次不计），所以这里第三跳才炸；
     * 而 {@code confirmed=true}（UsageStats 前台归属）一跳就炸 —— 正因如此 Android 层
     * 只在无障碍那条源静默时才喂 confirmed，见 §8.3.153。
     */
    @Test
    public void gateLetsContinuousAutomationThrough() {
        assertTrue(GuardPremise.mayFireOnTimeout(2, 1, true));
        assertTrue(GuardPremise.mayFireOnTimeout(2, 5, true));

        touch(0L);
        window(7_000L, "com.auto.app");       // 开 PENDING
        window(8_200L, "com.auto.app");       // 跟动 1 次，还没到实锤次数
        assertTrue(sink.fired.isEmpty());
        window(8_600L, "com.auto.app");       // 跟动 2 次 = 实锤
        assertEquals(1, sink.fired.size());
    }

    /** confirmed（UsageStats 高置信前台变化）一跳即实锤 —— 所以它只在无障碍静默时才这么喂。 */
    @Test
    public void confirmedEventFiresWithoutWaitingForBurst() {
        touch(0L);
        window(7_000L, "com.auto.app");
        d.feed(new GuardLinkCore.InteractionEvent(7_800L,
                GuardLinkCore.InteractionEvent.Type.APP_RESUMED, "com.auto.app", 0L, true));
        assertEquals(1, sink.fired.size());
    }

    /** 他把「实锤次数」拨到 1 = 动一次就报，那是设置，闸门不许擅自放宽。 */
    @Test
    public void burstOneKeepsTheHairTrigger() {
        assertTrue(GuardPremise.mayFireOnTimeout(1, 0, true));
        assertTrue(GuardPremise.mayFireOnTimeout(0, 0, true));
    }

    /** 触摸源一条事件都没有 = 「没人碰」没有直接判据，常规路径照旧不许炸。 */
    @Test
    public void noTouchEvidenceStopsFiring() {
        assertFalse(GuardPremise.mayFireOnTimeout(1, 9, false));
        assertFalse(GuardPremise.mayFireOnTimeout(2, 9, false));
    }

    /**
     * §8.3.156：触摸源不可用时不再整体停摆，改降级判定 ——
     * 缓冲期内界面持续异动 ≥{@code DEGRADED_MIN_ACTIVITIES} 次才许报。
     * 单次跳页（=0 次跟动）仍然不许报，§8.3.153 的修法在降级里也保留。
     */
    @Test
    public void degradedModeFiresOnlyOnSustainedActivity() {
        assertFalse("缓冲期内界面没再跟着动：降级也不许报", GuardPremise.mayFireDegraded(0));
        assertFalse("单次跟动可能只是加载跳页的第二跳：还不够", GuardPremise.mayFireDegraded(1));
        assertTrue(GuardPremise.mayFireDegraded(GuardPremise.DEGRADED_MIN_ACTIVITIES));
        assertTrue(GuardPremise.mayFireDegraded(9));
    }

    /** 降级判定只许用在触摸源判死的机器上；触摸源活着就永远走常规闸门。 */
    @Test
    public void degradedPathIsGatedByTouchSourceBeingDead() {
        assertTrue("触摸源活着：心跳走常规闸门，不进降级分支",
                GuardPremise.touchSourceSeen(1L, 100L));
        assertFalse("触摸源判死：才允许降级",
                GuardPremise.touchSourceSeen(0L, GuardPremise.MIN_WINDOW_EVENTS_TO_JUDGE));
        assertTrue(GuardPremise.mayFireOnTimeout(2, 1, true));
        assertTrue(GuardPremise.mayFireDegraded(2));
    }

    @Test
    public void touchSourceNeedsSamplesBeforeBeingJudgedDead() {
        assertTrue(GuardPremise.touchSourceSeen(3L, 500L));
        assertTrue("一条都还没有但窗口事件也没攒够：先给余地",
                GuardPremise.touchSourceSeen(0L, GuardPremise.MIN_WINDOW_EVENTS_TO_JUDGE - 1));
        assertFalse("攒够样本还一条触摸都没收到 = 这台 ROM 不给触摸事件",
                GuardPremise.touchSourceSeen(0L, GuardPremise.MIN_WINDOW_EVENTS_TO_JUDGE));
        assertFalse(GuardPremise.touchSourceSeen(0L, 9_000L));
    }

    /** 降级必须说出来：不许「界面写着正在值守、其实什么都判不了」。 */
    @Test
    public void describeSaysWhyNothingIsFiring() {
        String dead = GuardPremise.describe(0L, 30L, false);
        assertTrue(dead, dead.contains("触摸事件 0 条"));
        assertTrue(dead, dead.contains("一条触摸都没给"));
        assertTrue(dead, dead.contains("降级判定"));
        assertTrue(dead, dead.contains("持续异动 ≥" + GuardPremise.DEGRADED_MIN_ACTIVITIES));
        String alive = GuardPremise.describe(7L, 30L, true);
        assertTrue(alive, alive.contains("触摸源在用"));
        assertTrue(GuardPremise.describe(0L, 3L, true), GuardPremise.describe(0L, 3L, true)
                .contains("样本还不够"));
    }

    // ------------------------------------------------ 确认窗倒计时兜底起闸（§8.3.154）

    /** 桌面一直不回前台：过了一个确认窗长度 + 余量就必须就地起闸，否则永远不断网。 */
    @Test
    public void confirmFallbackArmsWhenLauncherNeverComesBack() {
        long win = 10_000L;
        assertFalse("刚炸就起闸不给桌面机会", GuardPremise.shouldArmConfirmFallback(true, false, false, 0L, win));
        assertFalse(GuardPremise.shouldArmConfirmFallback(true, false, false, win, win));
        assertFalse(GuardPremise.shouldArmConfirmFallback(true, false, false, win + 1_499L, win));
        assertTrue(GuardPremise.shouldArmConfirmFallback(true, false, false, win + 1_500L, win));
        assertTrue(GuardPremise.shouldArmConfirmFallback(true, false, false, 60_000L, win));
    }

    /** 已经起过闸就别再起（会把倒计时一次次往后推，等于永不超时）。 */
    @Test
    public void confirmFallbackNeverRearms() {
        assertFalse(GuardPremise.shouldArmConfirmFallback(true, true, false, 60_000L, 10_000L));
    }

    /** 没有 Lockdown / 还没触发过（sinceFire<0）/ 静默模式：都不该起闸。 */
    @Test
    public void confirmFallbackStaysOutOfOtherStates() {
        assertFalse(GuardPremise.shouldArmConfirmFallback(false, false, false, 60_000L, 10_000L));
        assertFalse(GuardPremise.shouldArmConfirmFallback(true, false, false, -1L, 10_000L));
        assertFalse("静默是退避，兜底起闸不许把它捅穿",
                GuardPremise.shouldArmConfirmFallback(true, false, true, 60_000L, 10_000L));
    }

    /** 静默里卡住要说明白，而且只说一次（三倍窗口之后）。 */
    @Test
    public void silentStuckIsSaidOnceAndOnlyLate() {
        long win = 10_000L;
        assertFalse(GuardPremise.shouldWarnSilentStuck(true, true, win, win, false));
        assertTrue(GuardPremise.shouldWarnSilentStuck(true, true, 3 * win, win, false));
        assertFalse("说过就别每 500ms 刷屏",
                GuardPremise.shouldWarnSilentStuck(true, true, 9 * win, win, true));
        assertFalse(GuardPremise.shouldWarnSilentStuck(true, false, 9 * win, win, false));
        assertFalse(GuardPremise.shouldWarnSilentStuck(false, true, 9 * win, win, false));
    }
}
