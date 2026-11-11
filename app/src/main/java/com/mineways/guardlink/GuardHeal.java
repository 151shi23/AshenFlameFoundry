package com.mineways.guardlink;

/**
 * 无障碍自愈的节奏与说法（纯逻辑，不碰 Android，所以能在 JVM 里断言）。
 *
 * <p>为什么原来「Shizuku 权限给了也拉不起来」：这一手以前只写在
 * {@code GuardAccessibilityService.onDestroy()} 里 —— 而进程被划掉/被系统直接杀死时
 * {@code onDestroy} 根本不会回调；就算回调了，那一刻 Shizuku 连接可能还没建好，
 * 于是走的是「Shizuku 未授权，无法自动恢复」那条死路。一次性的钩子中不了，就等于没有自愈。
 *
 * <p>改成<b>常驻服务里定时检查</b>：守护在跑就每 {@link #TRY_INTERVAL_MS} 最多试一次，
 * 试完把结果（成功/失败原因）留档给界面看。节流是必须的 —— 拦不住的 ROM 上，
 * 每 500ms 写一次 secure 设置既没用又会把事件流刷爆。
 */
public final class GuardHeal {

    /** 两次自愈尝试的最小间隔。 */
    public static final long TRY_INTERVAL_MS = 15_000L;

    /** 没试过的初值：让第一次检查立刻放行（不是「等满 15 秒才试第一回」）。 */
    private static final long NEVER = -TRY_INTERVAL_MS;

    private long lastTryAt = NEVER;
    private int tries;
    private int successes;
    private String lastOutcome = "还没试过";

    /**
     * 这一拍要不要再去拉一次无障碍。
     *
     * <p>三个条件缺一不可：服务没在用（在用就不用拉）、Shizuku 在跑（不然写不进去）、
     * 并且距离上次尝试够了间隔。
     */
    public boolean shouldTry(long now, boolean accessibilityOn, boolean shizukuReady) {
        if (accessibilityOn || !shizukuReady) {
            return false;
        }
        return now - lastTryAt >= TRY_INTERVAL_MS;
    }

    /** 记下这次的结果：成功与失败都要留痕，失败还要说清为什么。 */
    public void record(long now, boolean ok, String detail) {
        tries++;
        lastTryAt = now;
        if (ok) {
            successes++;
        }
        lastOutcome = (ok ? "✅ " : "⚠ ") + (detail == null || detail.length() == 0
                ? (ok ? "已重新启用" : "没写进去") : detail);
    }

    /**
     * 无障碍是不是<b>被 ROM 拦着写不进去</b>：试过、没成、而且不是没授权 Shizuku 那种。
     * 界面要按这个给用户换一句人话，别让他以为点了就完事。
     */
    public boolean stuck() {
        return tries > 0 && successes == 0;
    }

    public int tries() {
        return tries;
    }

    public int successes() {
        return successes;
    }

    /** 「再生与后台存活」卡与状态明细共用的一句。 */
    public String describe() {
        if (tries == 0) {
            return "还没需要自愈（无障碍一直在）";
        }
        return "已试 " + tries + " 次，成功 " + successes + " 次；最近一次：" + lastOutcome;
    }

    /** 停用/重新起守护时清零，别让上一轮的数字骗这一轮的界面。 */
    public void reset() {
        lastTryAt = NEVER;
        tries = 0;
        successes = 0;
        lastOutcome = "还没试过";
    }

    /**
     * 往「已启用无障碍服务」那一串里补上自己，别的家的服务一个都不能动。
     *
     * <p>这条必须做成纯函数：写覆盖式的一串就把用户开的其它无障碍服务清了（那是事故），
     * 写成重复追加就又脏又难查。
     */
    public static String mergeOwnInto(String current, String ownFlat) {
        if (ownFlat == null || ownFlat.length() == 0) {
            return current == null ? "" : current;
        }
        if (current == null || current.trim().length() == 0) {
            return ownFlat;
        }
        for (String part : current.split(":")) {
            if (part.trim().equals(ownFlat)) {
                return current.trim();       // 已经在里面了，别重复追加
            }
        }
        return current.trim() + ":" + ownFlat;
    }
}
