package com.mineways.guardlink;

/**
 * 「判无人操控」这件事的<b>前提检查</b>（纯逻辑，JVM 里可断言）。
 *
 * <p>为什么要这一层：内核的 {@code NoHumanDetector.onPendingTimeout()} 写的是<b>到点必炸</b> ——
 * 进了缓冲期之后 2.5 秒内没有真人触摸就 {@code fire()}，而「安静下来」正是它唯一的退出条件。
 * 内核自己的注释说的却是另一回事（{@code PENDING_ACTIVITY_BURST = 缓冲期内再收到 N 次
 * UI_ACTIVITY 即实锤}）。两者一冲突，后果就是他报的这两条：
 * <ul>
 *   <li><b>打开一个软件就误报</b>：启动页自己跳主页 = 一次窗口变化 → 进缓冲期 →
 *       那 2.5 秒你正在等它加载、没碰屏幕 → 报警。加载慢一点的 App 每次都中。</li>
 *   <li>处置完（卸载 / 按误报）之后自己再报一轮，见 §8.3.151。</li>
 * </ul>
 *
 * <p>修法放在 Android 层而不是改内核：内核文件要和上游 v3.2 逐字节一致（只有那份
 * 21 处参数补丁是例外，见 §8.3.146），判定语义的调整都由心跳在调 {@code onPendingTimeout()}
 * <b>之前</b>做 gate。
 */
public final class GuardPremise {

    /** 一条触摸事件都没收到之前，先给这么多窗口事件的余地（样本太少不足以判死触摸源）。 */
    public static final int MIN_WINDOW_EVENTS_TO_JUDGE = 30;

    /**
     * 降级判定的实锤门槛（§8.3.156）：触摸源不可用时，缓冲期<b>之后</b>界面还要持续异动
     * 这么多次才许报。原来的「样本不足 = 整体停摆」被他实测出代价 ——「啥也检查不到，
     * 一点也不拦截」；改成降级后，单次自翻页仍然撤防（§8.3.153 的修法保留），
     * 但持续操控不会再因为 ROM 不给触摸事件而永远漏报。
     */
    public static final int DEGRADED_MIN_ACTIVITIES = 2;

    /**
     * 触摸源不可用（{@code touchSourceSeen == false}）时的<b>降级判定</b>：
     * 缓冲期内界面持续异动够多次就许报。误报率必然高于有触摸判据的机器 ——
     * 这是 ROM 不给事件时的物理上限，不是缺陷；确认窗里的「是误报」就是给这个上限擦屁股的。
     *
     * @param activitiesWhilePending 进缓冲期<b>之后</b>界面又动过几次（开 PENDING 那次不算）
     */
    public static boolean mayFireDegraded(int activitiesWhilePending) {
        return activitiesWhilePending >= DEGRADED_MIN_ACTIVITIES;
    }

    /**
     * 缓冲期到点，这一拍到底许不许判定成「确认操控」。
     *
     * @param burstNeeded             界面上的「实锤次数」（内核 {@code PENDING_ACTIVITY_BURST}）
     * @param activitiesWhilePending  进缓冲期<b>之后</b>又动过几次（开 PENDING 的那一次不算）
     * @param touchSourceSeen         触摸信号源在给我们事件吗（{@link #touchSourceSeen}）
     */
    public static boolean mayFireOnTimeout(int burstNeeded, int activitiesWhilePending,
                                          boolean touchSourceSeen) {
        if (!touchSourceSeen) {
            // 连一次触摸都没收到过，就没有「刚才没人碰」的资格 —— 这时候炸，报的是这台 ROM
            // 不给无障碍触摸事件，不是设备被操控。宁可漏一次，也不能把日常使用全判成攻击。
            return false;
        }
        if (burstNeeded <= 1) {
            return true;      // 他把实锤次数拨到 1 = 一次就报，这是设置，不是我们擅自放宽
        }
        return activitiesWhilePending > 0;
    }

    /**
     * 触摸信号源活着吗：只统计<b>本守护这一世</b>收到的事件。
     *
     * <p>收到过一条 {@code HUMAN_TOUCH} 就算活着；一条都没有、但窗口事件已经攒了不少，
     * 说明这台 ROM 不把触摸交互事件给本服务（{@code TYPE_TOUCH_INTERACTION_*} 各家实现不一，
     * 有的只在触控探索模式下发）—— 那「无人触摸」这个判据从源头就不成立。
     */
    public static boolean touchSourceSeen(long humanTouches, long windowEvents) {
        if (humanTouches > 0) {
            return true;
        }
        return windowEvents < MIN_WINDOW_EVENTS_TO_JUDGE;
    }

    /** 状态明细与自检里那句话：把「为什么不报」也写成看得见的东西，不许静默降级。 */
    public static String describe(long humanTouches, long windowEvents, boolean seen) {
        String s = "触摸事件 " + humanTouches + " 条 · 窗口变化 " + windowEvents + " 条";
        if (seen) {
            return humanTouches == 0
                    ? s + "（样本还不够，暂按「触摸源正常」处理）"
                    : s + " · 触摸源在用";
        }
        return s + " · ⚠ 这台 ROM 一条触摸都没给本服务，「无人操控」没有直接判据，"
                + "已转降级判定：缓冲期内界面持续异动 ≥" + DEGRADED_MIN_ACTIVITIES + " 次才报";
    }

    /**
     * 确认窗的倒计时要不要<b>兜底起闸</b>。
     *
     * <p>内核把「设截止时刻 + 弹人工确认窗」挂在 {@code onLauncherEntered()} 上
     * （{@code GuardLinkCore:624-630}），也就是<b>桌面回到前台</b>才起闸。设计意图是
     * 「确认窗要弹在桌面上，别被嫌疑应用的界面盖住」。可这条链一旦断掉 ——
     * 第三方桌面认不出、launcher 事件没送到、或者恶意软件顶住前台不让回家 ——
     * {@code confirmDeadline} 就永远是 {@code Long.MAX_VALUE}：确认窗不弹、倒计时不跑、
     * 到点也不会按「确认恶意」处置，整套人工确认无限死等。
     * 他报的「没有确认恶意攻击的按钮、没有倒计时自动断网」正是这里。
     *
     * @param armed         已经起过闸（截止时刻已经不是 MAX_VALUE）
     * @param silent        内核静默模式（这条路本来就被 {@code onLauncherEntered} 挡着，不硬来）
     * @param sinceFireMs   本波 Lockdown 起来多久了
     * @param confirmMs     确认窗窗口长度
     */
    public static boolean shouldArmConfirmFallback(boolean lockdownActive, boolean armed,
                                                  boolean silent, long sinceFireMs, long confirmMs) {
        if (!lockdownActive || armed || silent || sinceFireMs < 0) {
            return false;
        }
        // 先给桌面一次真正回前台的机会（一个窗口长度 + 一点余量），再兜底
        return sinceFireMs >= confirmMs + 1_500L;
    }

    /** 静默模式下没能自动升级处置时，那句必须让人看见的说明（超过多久提醒一次）。 */
    public static boolean shouldWarnSilentStuck(boolean lockdownActive, boolean silent,
                                               long sinceFireMs, long confirmMs, boolean alreadyWarned) {
        return lockdownActive && silent && !alreadyWarned && sinceFireMs >= 3 * confirmMs;
    }

    private GuardPremise() {
    }
}
