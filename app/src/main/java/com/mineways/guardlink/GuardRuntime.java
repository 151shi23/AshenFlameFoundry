package com.mineways.guardlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.mineways.AppLog;
import com.zeus.guardlink.GuardLinkCore;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 守护的运行时装配：控制器、工作线程、事件日志、通知、UI 转发。
 *
 * <p><b>为什么要自己拉一条工作线程</b>：上游 demo 把「喂事件 → 判定 → 处置」全跑在无障碍服务的
 * 主线程上，而处置里包含 {@code svc / iptables / am / pm} 这类要等 shell 回来的调用（单次最长 8~15 秒）。
 * 在 Android 上这就是主线程阻塞，后果是 ANR 加整台手机卡住 —— 恰恰在真正被攻击的时候。
 * 这里把判定与处置统一挪到 {@code guard-worker}，只有「踢回主界面」「弹确认窗」「发通知」
 * 这三件必须在主线程做的事再 post 回去。
 *
 * <p><b>闸门三级取值</b>：能力开关与节奏都是「云控 &gt; 本机 &gt; 出厂」；判定参数由
 * {@link GuardParams} 在启动时一次性写进内核字段，界面上改了即刻生效，不用重启守护。
 *
 * <p><b>内核里没有出口的动作，在这一层拦</b>：例如「超时但不断网」内核根本没有这条路，
 * 所以关掉 {@code guard_timeout_cut} 时这一层干脆不调 {@code onConfirmTimeout()}；
 * 又如 {@code networkCut} 是内核调完 cutNetwork() 之后无条件置 true 的，它分不清
 * 「真断了」还是「被闸门挡下」，心跳里按事实纠正。
 */
public final class GuardRuntime {

    private static final String TAG = "GuardLink";
    private static final int LOG_CAP = 240;
    private static final int NOTIFY_ID = 9527;
    /** 人工确认那条单独一个 id：它不能被「一波只发一次」的警报名额挤掉，那是唯一的判定入口。 */
    private static final int CONFIRM_NOTIFY_ID = 9528;
    private static final String CHANNEL = "guardlink_alarm";
    /** 心跳节拍 500 毫秒，所有周期任务按拍数分摊。 */
    private static final int TICK_MS = 500;

    private static Context app;
    private static volatile GuardLinkCore.GuardController controller;
    private static HandlerThread thread;
    private static Handler worker;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static WeakReference<UiSink> sink = new WeakReference<>(null);
    private static volatile long tickCount;

    /** UsageStats 前台归属上一次的值，用来识别「界面自己变了」。 */
    private static volatile String lastForeground;
    /** 媒体豁免：音频/系统音频服务里只要有播放，人在手机前的驻留期就持续续期。 */
    private static volatile boolean prevMusic;
    private static volatile boolean prevMediaHold;
    private static volatile String mediaOwner;
    private static volatile java.util.Set<String> mediaPkgs = java.util.Collections.emptySet();
    private static volatile long lastMediaAt;
    private static volatile int lastMediaSuppressed;
    private static volatile int lastHumanSuppressed;
    /** 音视频刚停时的宽限期：切集/缓冲会让播放状态抖一下，不能立刻把豁免撤掉。 */
    private static final long MEDIA_GRACE_MS = 15_000L;
    private static volatile String lastAlarm = "";
    /** 真人确认窗的截止时刻，确认窗自己也要显示倒计时。 */
    private static volatile long confirmDeadline = Long.MAX_VALUE;
    /** 被精确断网（iptables）的包；内核那份是私有的，界面撤销与状态显示要靠这份。 */
    private static volatile String appNetCutPkg;
    /** Lockdown 期间已冻结过的包，防止每个心跳周期重发一次 force-stop。 */
    private static final Set<String> frozenRound = new HashSet<>();
    /** 桌面包（进入主界面的判定）与降噪豁免包：启动时查一次，别在窗口事件里反复查包。 */
    private static final Set<String> homePkgs = new LinkedHashSet<>();
    private static final Set<String> exemptPkgs = new LinkedHashSet<>();
    /** 关掉静默退避时这条提示一分钟最多刷一次。 */
    private static volatile long lastSilentWarnAt;
    /** 被豁免名单挡下的事件条数：挡了不刷屏，只在状态明细里给个数（免得用户以为什么都没发生）。 */
    private static volatile long trustSkipped;
    /** 一波操控只发一条手机通知，规则全在这个小机器里（见 {@link GuardNotices}）。 */
    private static final GuardNotices notices = new GuardNotices();
    /** 无障碍自愈的节流与留痕（见 {@link GuardHeal}）。 */
    private static final GuardHeal heal = new GuardHeal();
    /** 上一次拍子里无障碍在不在，只用于「掉线/恢复」各报一次，不刷屏。 */
    private static volatile boolean a11yLastKnown = true;
    /**
     * 最近一次<b>真人</b>触摸的时刻（{@code HUMAN_TOUCH} 事件）。
     *
     * <p>内核自己也有这一份，但它不对外暴露；Android 层要拿它区分「恶意软件静默装卸」和
     * 「本人正在设置里卸载恶意软件」—— 两件事走的是同一个安装器窗口，只有手指能分开。
     * -1 = 这一世还没见过触摸。
     */
    private static volatile long lastHumanTouchMs = -1L;
    /** 已启用无障碍的包名缓存（语音助手判定用），2 秒重查一次，别每条事件都读一次 secure 设置。 */
    private static final Set<String> sysAiCache = new LinkedHashSet<>();
    private static volatile long sysAiAt;
    /**
     * 「无人操控」这个判据自己有没有立足点：本守护这一世收到过多少条<b>触摸</b>与
     * <b>无障碍来源的窗口变化</b>，以及缓冲期内又动过几次（见 {@link GuardPremise}）。
     */
    private static volatile long premiseTouches;
    private static volatile long premiseWindows;
    private static int activitiesWhilePending;
    /** 最近一次收到无障碍来源事件的时刻，用来决定 UsageStats 要不要按「单发即实锤」喂。 */
    private static volatile long lastA11yEventAt;
    /** 前提塌了这句报警只刷一次，别每拍刷一条。 */
    private static volatile boolean premiseWarned;
    /**
     * 本波 Lockdown 起来的时刻。确认窗的倒计时原本等「桌面回前台」才起闸
     * （{@link GuardPremise#shouldArmConfirmFallback}），这个字段就是兜底起闸的计时起点。
     */
    private static volatile long firedAtMs;
    /** 静默模式卡住那句提醒只说一次。 */
    private static volatile boolean silentStuckWarned;
    /** 「需要人工确认」那条常驻通知还挂着没有（判定完就撤）。 */
    private static volatile boolean confirmNoticeUp;
    /** 用户到底想不想开着守护。停用必须<b>先</b>写这一条，见 {@link #setWanted}。 */
    private static final String PREF_USER_ON = "guard_user_on";

    private GuardRuntime() {
    }

    /** 功能页实现的回调接口：新日志、判定结论、健康变化都要即时反映到界面。 */
    public interface UiSink {
        void onGuardLog(String line);

        void onGuardVerdict(GuardLinkCore.SuspectEngine.Verdict v);

        void onGuardHealth(int flags);
    }

    public static void attach(Context ctx, UiSink s) {
        app = ctx.getApplicationContext();
        sink = new WeakReference<>(s);
    }

    public static void detach(UiSink s) {
        if (sink != null && sink.get() == s) {
            sink = null;
        }
    }

    public static boolean running() {
        return controller != null;
    }

    /**
     * 用户是不是想开着守护。默认开（功能默认可用 = 装上就自动值守），
     * 但「停用」一旦写下去就是明确意愿：启动页、开机广播都不能当它没发生。
     */
    public static boolean wanted(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences("guardlink", Context.MODE_PRIVATE)
                .getBoolean(PREF_USER_ON, true);
    }

    /** 记下用户的开/关意愿。停用守护时要<b>先</b>调这个再去做清理，见 {@code GuardLinkActivity.stopGuard}。 */
    public static void setWanted(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences("guardlink", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_USER_ON, on).apply();
    }

    public static GuardLinkCore.GuardController controller() {
        return controller;
    }

    // ---------------------------------------------------------------- 启停

    /**
     * 起守护。总闸关着（云控下发或本机拨的 guard_on=0）就返回原因，让界面把「为什么开不了」
     * 显示出来，而不是灰着不动、让用户以为自己手机坏了。
     *
     * @param recover 是否接回上次落盘的状态（开机广播、进程被回收后为 true）
     */
    public static synchronized String enable(Context ctx, boolean recover) {
        if (!GuardFlags.masterOn(ctx)) {
            return "守护总闸是关的（guard_on=0），打开才检测";
        }
        if (controller != null) {
            return null;
        }
        app = ctx.getApplicationContext();
        ensureChannel(app);
        thread = new HandlerThread("guard-worker");
        thread.start();
        worker = new Handler(thread.getLooper());
        tickCount = 0;
        lastForeground = null;
        prevMusic = false;
        prevMediaHold = false;
        mediaOwner = null;
        mediaPkgs = java.util.Collections.emptySet();
        lastMediaAt = 0;
        lastMediaSuppressed = 0;
        lastHumanSuppressed = 0;
        confirmDeadline = Long.MAX_VALUE;
        appNetCutPkg = null;
        lastSilentWarnAt = 0L;
        trustSkipped = 0L;
        notices.reset();
        frozenRound.clear();
        controller = new GuardLinkCore.GuardController(new GuardActions(app), new GuardLinkCore.GuardController.Ui() {
            @Override
            public void onLog(String line) {
                appendLog(line);
            }

            @Override
            public void onSuspectVerdict(GuardLinkCore.SuspectEngine.Verdict v) {
                forwardVerdict(v);
            }

            @Override
            public void onHealthChanged(int flags) {
                confirmDeadline = Long.MAX_VALUE;
                forwardHealth(flags);
            }

            @Override
            public void onHumanConfirmRequired(long deadlineTs) {
                confirmDeadline = deadlineTs;
                openConfirmWindow();
                confirmNotice(deadlineTs);
            }
        });

        // 参数：云控锁定的优先，其余听本机，都没动过就是出厂值
        appendLog(GuardParams.applyAll(app));
        // 云控特征：给运维留一条「这版实际吃到了哪些特征」的凭据
        appendLog("特征集：" + GuardCloudFeatures.summary());
        // 白名单注入（降噪）：桌面 / 输入法 / 守护自身，widget 自刷新不再算作案证据
        appendLog(injectWhitelist(app));
        if (recover) {
            appendLog(GuardState.restore(app, controller));
        }
        // 上次掐了还没还原的无障碍（进程被回收过）：不在保护状态就补还原
        if (!controller.lockdown.isActive()) {
            GuardA11y.repairIfLeftover(app);
        }
        appendLog("守护已启动：无人窗口 " + GuardText.duration(idleMs())
                + " · 真人驻留 " + GuardText.duration(GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS)
                + " · 缓冲 " + GuardText.duration(pendingMs())
                + " · 确认窗 " + GuardText.duration(confirmMs())
                + " · 连炸 " + silentAfter() + " 次转静默");
        heal.reset();
        a11yLastKnown = GuardProbe.accessibilityEnabled(app);
        // 触发前提的样本按「这一世」统计：重新起守护就重新数，别拿上一轮的触摸数糊这一轮
        premiseTouches = 0;
        premiseWindows = 0;
        activitiesWhilePending = 0;
        premiseWarned = false;
        firedAtMs = 0L;
        silentStuckWarned = false;
        confirmNoticeUp = false;
        // 先当它是活的：刚起守护就判「无障碍静默」会让 UsageStats 立刻按单发实锤炸一次
        lastA11yEventAt = System.currentTimeMillis();
        // 常驻服务：进程活得住、被划掉能自己回来、无障碍掉了有人定时补。
        // 关掉它（guard_regen=0）就只剩「开着页面才有效」，界面上要说清这个代价。
        if (GuardFlags.regenOn(app)) {
            GuardService.start(app, GuardService.ACTION_START);
        } else {
            appendLog("ℹ 再生能力已关（guard_regen=0）：不挂常驻服务，"
                    + "被划掉后台或被省电掐死时不会自己回来");
        }
        AppLog.i(TAG, "guard enabled");
        return null;
    }

    /** 起守护（不接历史状态，用户手动开的场景）。 */
    public static String enable(Context ctx) {
        return enable(ctx, false);
    }

    // ---------------------------------------------------------------- 能力自愈

    /**
     * 无障碍掉了自己补回去。成功返回 {@code null}，失败返回给用户看的那句原因。
     *
     * <p>原来只在无障碍服务 {@code onDestroy} 里试一次，两个后果：进程被划掉时那条回调不跑；
     * 而且写完不回读，Shizuku 报「命令成功」但 ROM 没认时，界面还以为恢复了。
     * 现在由常驻服务定时调，并且<b>写完一定回读一遍</b>：系统里那串真有本包名才算恢复。
     */
    public static String restoreOwnAccessibility(Context ctx) {
        String own = GuardProbe.ownServiceFlat(ctx);
        String merged = GuardHeal.mergeOwnInto(GuardProbe.enabledAccessibility(ctx), own);
        GuardShell.Result r = GuardShell.enableOwnAccessibility(ctx, merged);
        // 成败都带原因回去：GuardShell 已经回读验证过，这里的 ok 就是系统真的认了
        return r.ok ? null : r.detail;
    }

    /**
     * 一拍一次的自愈检查：只在「无障碍确实掉了 + Shizuku 在跑 + 距上次够了 15 秒」时才动手。
     *
     * <p>掉线与恢复各报一次事件流，别每两秒刷一条。
     */
    public static void selfHeal(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean on = GuardProbe.accessibilityEnabled(app);
        if (on != a11yLastKnown) {
            a11yLastKnown = on;
            appendLog(on ? "🟢 无障碍已回来：触摸与窗口事件的信号源恢复"
                    : "⚠ 无障碍被关闭：现在只缺触摸与窗口事件，正在定时尝试自愈");
        }
        if (on) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!heal.shouldTry(now, false, GuardShell.ready())) {
            return;
        }
        healOnce(app);
    }

    /**
     * 无障碍服务自己被关闭的那一刻调用（不等节流）：能立刻补回去就别等下一拍。
     *
     * <p>但这只是<b>抢先一试</b> —— 进程被划掉时 {@code onDestroy} 根本不跑，
     * 真正的自愈靠常驻服务里那圈定时检查，见 {@link GuardService#tick()}。
     */
    public static void selfHealNow(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (GuardProbe.accessibilityEnabled(app)) {
            a11yLastKnown = true;
            return;
        }
        if (!GuardShell.ready()) {
            if (notices.gapsChanged("无障碍已关闭且 Shizuku 没在跑")) {
                alarm(app, "GuardLink 守护降级", "无障碍服务被关闭，Shizuku 也没在跑，无法自愈。"
                        + "请在「授权」里点「开无障碍」手动打开，或先把 Shizuku 起起来。");
            }
            return;
        }
        healOnce(app);
    }

    /** 真去补一次，并把结果留痕（成功与失败都要有名字，界面要说实话）。 */
    private static void healOnce(Context app) {
        long now = System.currentTimeMillis();
        String why = restoreOwnAccessibility(app);
        heal.record(now, why == null, why == null ? "已通过 Shizuku 重新启用，并回读确认" : why);
        appendLog("🩺 自愈尝试：" + heal.describe());
        if (why == null) {
            a11yLastKnown = true;
            alarm(app, "GuardLink：无障碍已自动恢复",
                    "检测到守护无障碍被关闭，已重新启用（只动本服务，别人家的无障碍设置没改）。");
            return;
        }
        if (heal.stuck() && notices.gapsChanged("无障碍自愈反复失败：" + why)) {
            alarm(app, "GuardLink 守护降级", why);
        }
    }

    /** 界面「再生与后台存活」卡要显示的那句。 */
    public static String healState() {
        return heal.describe();
    }

    /**
     * 触发前提的现场：收到过几条触摸、几条窗口变化、这一拍凭什么报或不报。
     *
     * <p>这一行必须显示出来。前提塌了（这台 ROM 不给触摸事件）时，最怕的就是
     * 「界面一片绿、写着正在值守，其实什么都判不了」—— 那是拿安全功能骗人。
     */
    public static String signalReport() {
        return GuardPremise.describe(premiseTouches, premiseWindows,
                GuardPremise.touchSourceSeen(premiseTouches, premiseWindows))
                + " · 缓冲期内跟动 " + activitiesWhilePending + " 次";
    }

    /**
     * 确认窗现在到底在等什么。<b>必须显示</b>：上一版「桌面没回前台 → 不弹不切」是全静默的，
     * 界面看着像在值守，其实人工确认链路根本没启动 —— 他因此只能得出「这功能没有确认恶意按钮」的结论。
     */
    public static String confirmState() {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑";
        }
        if (c.confirmedCrack) {
            return "已按「确认恶意」处置（断网保护中，可真人按压撤销）";
        }
        if (!c.lockdown.isActive()) {
            return "没有进行中的 Lockdown，无需人工确认";
        }
        long now = System.currentTimeMillis();
        if (confirmDeadline == Long.MAX_VALUE) {
            if (c.isSilentMode()) {
                return "静默模式：不弹确认窗，只维持 Lockdown（要人工判定就点「弹出确认窗」）";
            }
            long left = confirmMs() + 1_500L - (firedAtMs == 0L ? 0L : now - firedAtMs);
            return "倒计时还没起闸（原设计要等桌面回前台），"
                    + GuardText.duration(Math.max(0L, left)) + "后就地兜底起闸";
        }
        return "倒计时剩 " + GuardText.duration(Math.max(0L, confirmDeadline - now)) + "，到点"
                + (GuardFlags.cutOnTimeout(app) ? "按「确认恶意」处置断网"
                : "只维持 Lockdown（超时断网已关 guard_timeout_cut=0）");
    }

    /** 自愈有没有反复失败过（ROM 拦住写入时，界面要把这句话换成劝人手动开的版本）。 */
    public static boolean healStuck() {
        return heal.stuck();
    }

    /** 已经试过几次、成功几次，界面上给个准数。 */
    public static int healTries() {
        return heal.tries();
    }

    public static int healSuccesses() {
        return heal.successes();
    }

    /**
     * 停守护：解除 Lockdown、还原被掐的无障碍、恢复被本功能切断的网络、抹掉状态盘。
     *
     * <p>清理整段包在 try 里、收尾动作放 finally：<b>停用必须停得下来</b>。
     * 这里以前没有兜底，任何一步抛异常都会把后面的步骤整段跳过 ——
     * 调用方排在其后的「记住用户已停用」也跟着没执行，于是下次启动又自己开起来。
     */
    public static synchronized void disable(Context ctx) {
        GuardLinkCore.GuardController c = controller;
        controller = null;
        confirmDeadline = Long.MAX_VALUE;
        confirmNoticeUp = false;
        clearConfirmNotice();
        notices.reset();
        try {
            if (c != null) {
                if (appNetCutPkg != null) {
                    appendLog("关闭守护时恢复精确断网："
                            + GuardShell.restoreAppNet(ctx, appNetCutPkg).detail);
                }
                noteAppNetCut(null);
                if (c.networkCut) {
                    appendLog("关闭守护时恢复网络：" + GuardShell.restoreNetwork(ctx).detail);
                    c.networkCut = false;
                }
                c.lockdown.disarm();
                appendLog("🧱 " + GuardA11y.restore(ctx));
            }
        } catch (Throwable t) {
            appendLog("⚠ 停守护时有一步清理没做完（" + t.getClass().getSimpleName() + "）：" + t.getMessage()
                    + "。守护已经停了、不再检测也不再处置；残留的断网或无障碍可以用本页"
                    + "「不可逆动作」里的恢复网络 / 还原无障碍手动撤。");
            AppLog.e(TAG, "disable 清理未完成", t);
        } finally {
            GuardState.clear(ctx);
            if (worker != null) {
                worker.removeCallbacksAndMessages(null);
            }
            if (thread != null) {
                thread.quitSafely();
            }
            thread = null;
            worker = null;
            frozenRound.clear();
            notices.reset();
            heal.reset();
            GuardService.start(ctx, GuardService.ACTION_STOP);
            appendLog("守护已停止：不再检测、不再处置");
            AppLog.i(TAG, "guard disabled");
        }
    }

    /**
     * 桌面与输入法进白名单：桌面时钟/天气 widget 每分钟自刷新、输入法弹起收起，
     * 这些既不该当作案证据，也不该被 Lockdown 冻结（冻桌面等于把手机弄成砖的样子）。
     *
     * <p>常见 App / 游戏的豁免名单（{@link GuardTrust}）在这里一起装配。它<b>不能</b>只靠往内核那个
     * {@code Set} 里塞包名来实现：内核那份是严格 {@code equals}，而名单里成的是
     * {@code com.tencent.} 这种前缀条目，塞进去谁也不匹配 —— 所以挡证据这件事必须留在喂事件之前。
     */
    private static String injectWhitelist(Context ctx) {
        List<String> homes = GuardProbe.homePackages(ctx);
        List<String> imes = GuardProbe.imePackages(ctx);
        String own = ctx.getPackageName();
        homePkgs.clear();
        homePkgs.addAll(homes);
        exemptPkgs.clear();
        exemptPkgs.addAll(homes);
        exemptPkgs.addAll(imes);
        exemptPkgs.add(own);
        for (String p : exemptPkgs) {
            GuardLinkCore.NoHumanDetector.EXTRA_WHITELIST.add(p);
            GuardLinkCore.LockdownController.EXTRA_WHITELIST.add(p);
        }
        GuardTrust.refresh(ctx);
        return "降噪白名单：桌面 " + homes.size() + " 个 · 输入法 " + imes.size()
                + " 个 · 自身 " + own + "（widget 刷新与键盘弹起不计证据、不冻结）· 豁免名单 "
                + GuardTrust.summary(ctx);
    }

    /**
     * 「不算证据」档的包 + 系统级 AI 助手，它们自己的界面异动不进状态机。
     *
     * <p>{@code HUMAN_TOUCH} 一律放行 —— 那是撤防信号，挡掉它等于把「用户正在操作」这件事
     * 对一个常用 App 视而不见，方向正好反了。
     */
    private static boolean exemptAsEvidence(GuardLinkCore.InteractionEvent.Type type, String pkg) {
        if (pkg == null || type == GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH) {
            return false;
        }
        GuardTrust.ensureLoaded(app);       // 无障碍服务可能比 enable() 更早起
        if (GuardTrust.notEvidence(pkg) || trustedActor(pkg)) {
            trustSkipped++;
            return true;
        }
        return false;
    }

    /**
     * 现在算「系统级可信执行者」的包（语音助手这类），供界面显示。
     *
     * <p>读的是系统里<b>当前已启用的无障碍服务</b>：能替人动手的就是这几个，一个都猜不出来。
     */
    public static List<String> trustedActors() {
        List<String> out = new ArrayList<>();
        if (app == null || !GuardFlags.sysAiOn(app)) {
            return out;
        }
        for (String p : actorPackages()) {
            if (GuardShell.isSystemApp(app, p)
                    || GuardLinkCore.A11yPolicy.isTrusted(p, null)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 这一条包现在是不是被放行的执行者（开关关着永远返回 false，回到严格模式）。 */
    public static boolean trustedActor(String pkg) {
        if (app == null || !GuardFlags.sysAiOn(app)) {
            return false;
        }
        return GuardSysAi.isTrustedActor(pkg, true, GuardShell.isSystemApp(app, pkg), actorPackages());
    }

    /** 已启用无障碍的包名，2 秒缓存：窗口事件很密，别每条都去读 secure 设置。 */
    private static Set<String> actorPackages() {
        long now = System.currentTimeMillis();
        if (now - sysAiAt >= 2_000L) {
            sysAiAt = now;
            List<String> pkgs = GuardSysAi.servicePackages(GuardProbe.enabledAccessibility(app));
            sysAiCache.clear();
            sysAiCache.addAll(pkgs);
        }
        return sysAiCache;
    }

    /**
     * 处置收尾后重新计时：让「距上次真人触摸」从零算起。
     *
     * <p>内核的 {@code detector.reset()} 只把状态机退回 IDLE，<b>不动 {@code lastHumanTouchAt}</b> ——
     * 于是卸载完（或按了误报）之后，只要 6 秒内没有新的真人触摸，下一个任何包的界面异动
     * 立刻再进 PENDING、2.5 秒后再 FIRED。卸载确认框、清理动画、桌面刷新本身就是界面异动，
     * 结果就是「明明已经卸掉了，还是一路报警」。
     *
     * <p>修法不动内核：喂一条 {@code HUMAN_TOUCH} 把触摸基准推到当下（这条信号在 {@code reset()}
     * 之后不会改变状态机），语义就是「处置是活人做的，从这里重新起算」。
     */
    public static void settle(final String why) {
        GuardLinkCore.GuardController c = controller;
        Handler w = worker;
        if (c == null || w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                GuardLinkCore.GuardController cur = controller;
                if (cur == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                cur.detector.reset();
                cur.detector.feed(new GuardLinkCore.InteractionEvent(now,
                        GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null, 0, false));
                lastHumanTouchMs = now;
                cur.uiLog("⏱ 处置收尾重新计时（" + why + "）：接下来 "
                        + GuardText.duration(idleMs()) + " 内的界面异动不算「无人操控」");
                GuardState.save(app, cur);
            }
        });
    }

    /**
     * 手动解除保护（Lockdown）：真人按压才生效，网络不在这里恢复。
     *
     * <p>这条出口原本不存在 —— Lockdown 只有「按误报」「撤销断网」「走我们那颗卸载按钮」三条路，
     * 到系统设置里自己卸载的应用解不开它，表现就是「只有关掉守护再开才不报警」。
     * 关守护代价太大（那段没人值守），所以给一颗只解保护的键。
     */
    public static String releaseLockdown(Context ctx) {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑，没有保护可解除";
        }
        if (!c.lockdown.isActive()) {
            return "现在不处于 Lockdown 保护状态";
        }
        c.lockdown.disarm();
        appendLog("🔓 真人手动解除保护（Lockdown 退出，无障碍防火墙原样还原）："
                + GuardA11y.restore(ctx));
        settle("手动解除保护");
        GuardState.save(ctx, c);
        if (c.networkCut || appNetCutPkg != null) {
            return "保护已解除；网络仍在断网保护中，要恢复请用首屏那颗「撤销断网」";
        }
        return null;
    }

    /** 界面那颗「卸载」键：走内核的完整收尾（恢复网络 + disarm + 还原无障碍 + 复位）。 */
    public static String userUninstall(String pkg) {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑，不走这条处置（免得只卸载不恢复网络）";
        }
        c.userConfirmedUninstall(pkg);
        settle("卸载 " + pkg + " 完成");
        return null;
    }

    // ---------------------------------------------------------------- 事件入口

    /** 无障碍/TrafficStats 的信号统一从这条线程走，绝不在主线程跑处置。 */
    public static void feed(final GuardLinkCore.InteractionEvent.Type type, final String pkg,
                            final boolean confirmed, final long byteDelta) {
        GuardLinkCore.GuardController c = controller;
        Handler w = worker;
        if (c == null || w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                GuardLinkCore.GuardController cur = controller;
                if (cur == null) {
                    return;
                }
                boolean fromA11y = type == GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH
                        || type == GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY;
                if (fromA11y) {
                    lastA11yEventAt = System.currentTimeMillis();
                }
                if (type == GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH) {
                    // 活人的时刻要自己留一份：安装器窗口的「本人 vs 静默」判据也用得上
                    lastHumanTouchMs = System.currentTimeMillis();
                    premiseTouches++;
                } else if (type == GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY) {
                    premiseWindows++;
                }
                boolean isActivity = type == GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY
                        || type == GuardLinkCore.InteractionEvent.Type.APP_RESUMED;
                if (isActivity && cur.detector.getState()
                        == GuardLinkCore.NoHumanDetector.State.PENDING) {
                    // 缓冲期<b>之后</b>还跟着动 = 持续操控的凭据；开 PENDING 那一次不算
                    activitiesWhilePending++;
                }
                if (exemptAsEvidence(type, pkg)) {
                    return;      // ①档 / 可信执行者：它自己的界面异动不算「没人碰却在动」
                }
                tickEpisode(cur, System.currentTimeMillis());
                cur.detector.feed(new GuardLinkCore.InteractionEvent(
                        System.currentTimeMillis(), type, pkg, byteDelta, confirmed));
            }
        });
    }

    /** 无障碍服务每 500ms 打一次点；周期任务按拍数分摊，别每 0.5s 就查一遍全量应用。 */
    public static void heartbeat() {
        GuardLinkCore.GuardController c = controller;
        Handler w = worker;
        if (c == null || w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                GuardLinkCore.GuardController cur = controller;
                if (cur == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                tickCount++;

                // ---- 真人驻留 / 媒体豁免（§8.3.165） ----
                //       核心判据是「非真人操控」：人刚碰过手机、或正在看视频听歌，界面自己
                //       在动是正常的。媒体期不仅豁免播放器，所有包的界面活动都不判炸 ——
                //       小窗/后台播放时前台归属会错，只认「音频主人」照样误报。
                if (tickCount % 4 == 0) {
                    boolean music = GuardProbe.musicActive(app);
                    java.util.Set<String> playing = GuardProbe.activePlaybackPackages(app);
                    boolean active = music || !playing.isEmpty();
                    long nowMs = System.currentTimeMillis();
                    if (active) {
                        lastMediaAt = nowMs;
                        if (!prevMusic) {
                            mediaOwner = GuardProbe.lastResumedPkg(app);
                        }
                        if (!playing.isEmpty()) {
                            mediaPkgs = playing;
                        }
                    }
                    // 切集/缓冲会让播放状态抖一下：停播后 15s 内仍按「媒体在场」处理
                    boolean hold = active || (nowMs - lastMediaAt < MEDIA_GRACE_MS);
                    if (!hold) {
                        mediaOwner = null;
                        mediaPkgs = java.util.Collections.emptySet();
                    } else {
                        // 媒体在放 = 人在手机前：把真人驻留期续上，视频停了也不会立刻恢复判炸
                        cur.detector.markHumanPresence(nowMs);
                    }
                    if (hold && !prevMediaHold) {
                        cur.uiLog("🎬 检测到媒体播放：看视频/听歌期间自动判炸已暂停，不会突然断网");
                    } else if (!hold && prevMediaHold) {
                        cur.uiLog("🎬 媒体已停止：恢复自动值守（真人在场驻留期内仍不判炸）");
                    }
                    prevMusic = active;
                    prevMediaHold = hold;
                    cur.detector.setMediaActive(hold, mediaOwner, mediaPkgs);
                }

                // 媒体/真人在场期间发生过「本该判炸」的场景，界面留一条痕，方便事后核对
                int suppressed = cur.detector.mediaSuppressed;
                if (suppressed > lastMediaSuppressed) {
                    lastMediaSuppressed = suppressed;
                    cur.uiLog("🎬 媒体/真人驻留期间，已拦下自动判炸（不自动断网）");
                }
                int humanSuppressed = cur.detector.humanSuppressed;
                if (humanSuppressed > lastHumanSuppressed) {
                    lastHumanSuppressed = humanSuppressed;
                    cur.uiLog("🙋 真人在操作手机，已拦下自动判炸（不在人眼皮底下断网）");
                }

                // ---- 缓冲期到点：先问前提成不成立，再决定让不让内核判炸（见 GuardPremise）
                boolean pending = cur.detector.getState()
                        == GuardLinkCore.NoHumanDetector.State.PENDING;
                boolean touchSeen = GuardPremise.touchSourceSeen(premiseTouches, premiseWindows);
                if (pending && !touchSeen && !premiseWarned) {
                    premiseWarned = true;
                    cur.uiLog("⚠ 触发前提不成立：这台设备一条触摸事件都没给本服务，"
                            + "「刚才没人碰屏幕」无从直接判断 → 降级判定：缓冲期内界面持续异动 ≥"
                            + GuardPremise.DEGRADED_MIN_ACTIVITIES + " 次才报（单次跳页仍撤防）");
                    alarm(app, "GuardLink：触摸事件源不可用（已转降级判定）",
                            "这台 ROM 不给无障碍服务触摸交互事件，「无人操控」没有直接判据。"
                                    + "已转降级判定：缓冲期内界面持续异动才算，单次自翻页仍撤防。"
                                    + "误报率会比有触摸判据的机器高，确认窗里的「是误报」就是给这个用的。");
                }
                // 降级判定（§8.3.156）：触摸源不可用时不再整体停摆 —— 缓冲期内界面持续异动
                // 达到门槛就放内核判炸，否则上一轮修的「宁可漏报」在他机器上就是「永远漏报」。
                // 但媒体播放期间不行：没有触摸判据时，看不出「人在看视频」还是「鬼在操作」，
                // 这种时候宁可漏报也不能把正在放的视频一刀断网（§8.3.165）。
                boolean mediaHold = cur.detector.isMediaActive();
                // 真人在手机前（刚触摸/解锁/媒体在放）时，心跳这一拍也不许推进判炸
                boolean humanHold = cur.detector.isHumanPresent(now);
                boolean presenceHold = mediaHold || humanHold;
                boolean degradedFire = pending && !touchSeen && !presenceHold
                        && GuardPremise.mayFireDegraded(activitiesWhilePending);
                if (pending && presenceHold) {
                    // 人回来了 / 视频开始了：直接撤防，不让「到点判炸」在人在场时发生
                    cur.detector.reset();
                    activitiesWhilePending = 0;
                } else if (pending && !degradedFire && !GuardPremise.mayFireOnTimeout(
                        burst(), activitiesWhilePending, touchSeen)) {
                    cur.detector.reset();
                    if (touchSeen && activitiesWhilePending == 0) {
                        cur.uiLog("🟢 缓冲期内界面没再跟着动 → 判为自翻页/加载跳页，撤防（不计触发）");
                    }
                } else {
                    if (degradedFire) {
                        cur.uiLog("🎯 降级判定：缓冲期内界面持续异动 " + activitiesWhilePending
                                + " 次（触摸源不可用，按持续操控处置）");
                    }
                    cur.detector.onPendingTimeout(now);
                }
                if (!pending) {
                    activitiesWhilePending = 0;
                }

                // ---- 确认窗倒计时兜底起闸
                //      内核把「设截止时刻 + 弹人工确认窗」挂在 onLauncherEntered() 上，也就是
                //      <b>桌面回到前台</b>才起闸（设计意图：确认窗要弹在桌面上，别被嫌疑应用盖住）。
                //      可这条链一断 —— 第三方桌面认不出、launcher 窗口事件没送到、恶意软件顶住前台
                //      不让回家 —— confirmDeadline 就永远是 Long.MAX_VALUE：确认窗不弹、倒计时不跑、
                //      到点也不处置，整套「人工确认 → 恶意则断网」无限死等。
                //      他报的「没有确认恶意攻击的按钮、也没有倒计时自动断网」就是这里。
                if (GuardPremise.shouldArmConfirmFallback(cur.lockdown.isActive(),
                        confirmDeadline != Long.MAX_VALUE, cur.isSilentMode(),
                        firedAtMs == 0L ? -1L : now - firedAtMs, confirmMs())) {
                    cur.uiLog("⏱ 桌面迟迟没回前台 → 确认窗就地起倒计时兜底："
                            + GuardText.duration(confirmMs()) + "内无人点击就按「确认恶意」处置");
                    cur.onLauncherEntered(now);
                }
                if (GuardPremise.shouldWarnSilentStuck(cur.lockdown.isActive(), cur.isSilentMode(),
                        firedAtMs == 0L ? 0L : now - firedAtMs, confirmMs(), silentStuckWarned)) {
                    silentStuckWarned = true;
                    cur.uiLog("😴 静默模式中：不再弹确认窗，也没推举出可动手的嫌疑包 → "
                            + "只维持 Lockdown、不会自动断网。要立刻人工判定就回本页点「弹出确认窗」");
                }

                // 关掉超时断网时干脆不推进内核的超时处置（它一旦触发就是断网，内核没有
                // 「到点但不断网」这条路）：不调它，Lockdown 与拦截照常保持，网络一根手指都不碰。
                if (GuardFlags.cutOnTimeout(app)) {
                    cur.onConfirmTimeout(now);
                } else if (cur.lockdown.isActive() && !cur.confirmedCrack && now >= confirmDeadline) {
                    confirmDeadline = Long.MAX_VALUE;   // 只报一次，别每 500ms 刷屏
                    cur.uiLog("⏰ 确认窗到点：超时断网已关闭 → 不自动断网，Lockdown 继续维持");
                }
                // 判定完了（点了任一个键 / 超时处置 / 保护已退）就把那条常驻确认通知撤掉
                if (confirmNoticeUp && (confirmDeadline == Long.MAX_VALUE
                        || !cur.lockdown.isActive() || cur.confirmedCrack)) {
                    confirmNoticeUp = false;
                    clearConfirmNotice();
                }

                // ---- 前台归属：UsageStats 是系统记录，伪造不了，作为高置信事件直接喂进状态机 ----
                if (tickCount % 2 == 0) {
                    String fg = GuardProbe.lastResumedPkg(app);
                    if (fg != null && !fg.equals(lastForeground)) {
                        lastForeground = fg;
                        if (!fg.equals(app.getPackageName()) && !exemptAsEvidence(
                                GuardLinkCore.InteractionEvent.Type.APP_RESUMED, fg)) {
                            // confirmed=true 在内核里等于「单发即实锤、跳过缓冲期」。只在无障碍那条源
                            // 已经静默（服务被关 / 收不到事件）时才这么喂 —— 两个源各报一次同一个前台
                            // 变化、第二个还能直接炸，就是「点开一个软件立刻报警」的放大器。
                            boolean a11yQuiet = now - lastA11yEventAt > 10_000L;
                            cur.detector.feed(new GuardLinkCore.InteractionEvent(now,
                                    GuardLinkCore.InteractionEvent.Type.APP_RESUMED, fg, 0, a11yQuiet));
                        }
                    }
                }

                // ---- 一波操控的通知节奏（首次必发，同波只发一次） ----
                tickEpisode(cur, now);

                // ---- Lockdown 前台巡查：隐私应用踢回 / 陌生包冻结（同一轮不重复冻） ----
                if (cur.lockdown.isActive()) {
                    String fg = lastForeground;
                    if (fg != null) {
                        GuardLinkCore.LockdownController.Action a = cur.lockdown.evaluate(fg);
                        if (a == GuardLinkCore.LockdownController.Action.KICK_HOME) {
                            cur.uiLog("🚫 Lockdown：隐私应用 " + fg + " 被调起 → 立即踢回主界面");
                            kickHome();
                        } else if (a == GuardLinkCore.LockdownController.Action.FREEZE_SUSPECT
                                && !frozenRound.contains(fg) && !trustedActor(fg)) {
                            frozenRound.add(fg);
                            cur.uiLog("🧊 Lockdown：陌生前台包 " + fg + " → 冻结（FIRED 后同伙新拉起）");
                            appendLog(GuardShell.forceStop(app, fg).detail);
                        }
                    }
                } else if (!frozenRound.isEmpty()) {
                    frozenRound.clear();          // 保护解除，下一轮重新计
                }

                // ---- 静默退避被关掉：内核一旦进静默就不再弹窗，这里立刻把它退出来 ----
                if (cur.isSilentMode() && !GuardFlags.silentModeAllowed(app)) {
                    cur.exitSilentMode();
                    if (now - lastSilentWarnAt > 60_000L) {
                        lastSilentWarnAt = now;
                        cur.uiLog("😴→🔔 静默退避已关闭（guard_silent=0）→ 每次触发都照常弹窗");
                    }
                }

                // 内核是「先调 cutNetwork()，再把 networkCut 置 true」，它分不清这次是真断了
                // 还是被闸门挡下。不纠正的话界面会谎报「网络已切断」，下面的网络对账还会反复重试。
                // 但这条纠正只在<b>本守护其实没真断过</b>时才成立：盘上有断网快照就说明 svc 真下发过，
                // 那时把 networkCut 抹成 false 等于把网络永久丢在断开状态（恢复动作再也不会被调到）。
                if (cur.networkCut && !GuardFlags.killNetwork(app) && !GuardNetState.hasCut(app)) {
                    cur.networkCut = false;
                    cur.uiLog("🌐 状态纠正：断网能力已关闭（guard_kill_net=0），本守护并没有切断网络");
                }
                if (GuardNetState.hasCut(app) && !GuardFlags.killNetwork(app)) {
                    // 断网能力被本机或云端关掉、网络却还真断着：照快照恢复，不等用户去点那颗键
                    GuardShell.Result r = GuardShell.restoreNetwork(app);
                    cur.networkCut = false;
                    cur.uiLog("🌐 断网能力已关闭（guard_kill_net=0）→ 照快照恢复网络：" + r.detail);
                    GuardState.save(app, cur);
                }
                if (cur.networkCut && ticksEvery(GuardFlags.auditSeconds(app))
                        && GuardProbe.networkAlive(app)) {
                    GuardShell.Result r = GuardShell.cutNetwork(app);
                    cur.uiLog("⚠ 断网保护期间检测到网络复活（提权级反恢复）→ 再断：" + r.detail);
                    notifyEpisode(app, "GuardLink 警报：网络被恶意恢复",
                            "已再次切断。请立即排查设备上的可疑应用。");
                }
                if (ticksEvery(GuardFlags.stateSeconds(app))) {
                    GuardState.save(app, cur);     // 跨重启恢复靠这条
                }
                if (tickCount % 60 == 0) {
                    int flags = 0;
                    if (GuardProbe.accessibilityEnabled(app)) {
                        flags |= GuardLinkCore.GuardController.HEALTH_ACCESSIBILITY;
                    }
                    if (GuardShell.ready()) {
                        flags |= GuardLinkCore.GuardController.HEALTH_SHIZUKU;
                    }
                    if (GuardProbe.usageStatsGranted(app)) {
                        flags |= GuardLinkCore.GuardController.HEALTH_USAGE_STATS;
                    }
                    cur.setHealth(flags);
                }
                // ---- 未触发态哨兵（§8.3.158）：新无障碍服务 / ADB 开关，30 秒一查。
                //      这两条不依赖触摸判据 —— 远控软件的无障碍持久化与「无线调试配对」
                //      在手势同形死角之外，装完即暴露。
                if (tickCount % 60 == 0) {
                    runSentinels(cur);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 未触发态哨兵（§8.3.158）

    private static Boolean lastAdbEnabled;     // null = 还没建基线，首帧不报警
    private static Boolean lastAdbWifi;
    private static Boolean lastVpn;

    private static void runSentinels(GuardLinkCore.GuardController cur) {
        try {
            runA11ySentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "无障碍哨兵失败", t);
        }
        try {
            runListenerSentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "通知监听哨兵失败", t);
        }
        try {
            runAdminSentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "设备管理员哨兵失败", t);
        }
        try {
            runSmsSentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "默认短信哨兵失败", t);
        }
        try {
            runAdbSentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "ADB 哨兵失败", t);
        }
        try {
            runVpnSentinel(cur);
        } catch (Throwable t) {
            AppLog.e(TAG, "VPN 哨兵失败", t);
        }
    }

    /**
     * 新无障碍服务哨兵。基线（首次扫描）只记名不报警 —— 否则守护一启动就对着
     * 用户早已授权的输入法/银行辅助服务嚎一嗓子，哨兵自己先被关掉。
     */
    private static void runA11ySentinel(GuardLinkCore.GuardController cur) {
        android.content.SharedPreferences p = app.getSharedPreferences("guardlink", Context.MODE_PRIVATE);
        java.util.Set<String> stored = p.getStringSet("sentinel_a11y_known", null);
        java.util.Set<String> known = stored == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(stored);
        String raw = GuardProbe.enabledAccessibility(app);
        java.util.List<String> flats = new java.util.ArrayList<String>();
        if (raw != null && raw.length() > 0) {
            for (String s : raw.split(":")) {
                if (s.trim().length() > 0) {
                    flats.add(s.trim());
                }
            }
        }
        java.util.Set<String> trusted = trustedHolders(flats);
        if (stored == null) {
            for (String f : flats) {
                known.add(f.toLowerCase());
            }
            p.edit().putStringSet("sentinel_a11y_known", known).apply();
            cur.uiLog("🛡 哨兵基线：当前无障碍服务 " + flats.size()
                    + " 个已记名（信任 " + trusted.size() + "），此后新出现的会报警");
            return;
        }
        java.util.List<String> news = GuardSentinel.newUntrustedAccessibility(flats, known, trusted);
        if (news.isEmpty()) {
            return;
        }
        boolean dirty = false;
        for (String f : news) {
            String pkg = GuardSentinel.flatPkg(f);
            cur.uiLog("🚨 哨兵：发现新的无障碍服务 " + f
                    + " —— 可读屏、可注入手势，远控软件持久化的第一步。"
                    + "不是你自己开的：去系统设置关掉它并卸载那个应用");
            alarm(app, "GuardLink 哨兵：新的无障碍服务",
                    (pkg == null ? f : pkg) + " 刚拿到无障碍权限（可读屏、可注入手势）。"
                            + "远控软件靠它操控你的手机。如果你没有主动授权，立即到系统设置关闭并卸载。");
            if (pkg != null) {
                known.add(f.toLowerCase());
                dirty = true;
            }
        }
        if (dirty) {
            p.edit().putStringSet("sentinel_a11y_known", known).apply();
        }
    }

    /**
     * 新通知监听服务哨兵。
     *
     * <p>形状与无障碍完全一致，基线口径也一致：首帧只记名。信誉最低的一类入口 ——
     * 通知里全是验证码、银行动账、社交消息，读通知等于把验证码直接递给对方。
     */
    private static void runListenerSentinel(GuardLinkCore.GuardController cur) {
        android.content.SharedPreferences p = app.getSharedPreferences("guardlink", Context.MODE_PRIVATE);
        java.util.Set<String> stored = p.getStringSet("sentinel_listener_known", null);
        java.util.Set<String> known = stored == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(stored);
        java.util.List<String> flats = flatsOf(GuardProbe.enabledNotificationListeners(app));
        java.util.Set<String> trusted = trustedHolders(flats);
        if (stored == null) {
            for (String f : flats) {
                known.add(f.toLowerCase());
            }
            p.edit().putStringSet("sentinel_listener_known", known).apply();
            cur.uiLog("🛡 哨兵基线：当前通知监听服务 " + flats.size()
                    + " 个已记名（信任 " + trusted.size() + "），此后新出现的会报警");
            return;
        }
        java.util.List<String> news = GuardSentinel.newUntrustedFlats(flats, known, trusted);
        boolean dirty = false;
        for (String f : news) {
            String pkg = GuardSentinel.flatPkg(f);
            cur.uiLog("🚨 哨兵：发现新的通知监听服务 " + f
                    + " —— 能读全部通知（验证码/银行动账）。不是你开的：去「通知使用权」里关掉它");
            alarm(app, "GuardLink 哨兵：新的通知监听服务",
                    (pkg == null ? f : pkg) + " 刚拿到通知读取权（能看到验证码与银行推送）。"
                            + "如果你没有主动授权，立即到系统设置「通知使用权」里关闭并卸载。");
            if (pkg != null) {
                known.add(f.toLowerCase());
                dirty = true;
            }
        }
        if (dirty) {
            p.edit().putStringSet("sentinel_listener_known", known).apply();
        }
    }

    /**
     * 新设备管理员哨兵。激活设备管理员要过系统确认框，正常用户极少在后台默默做这件事；
     * 它拿到的是锁屏、清数据、改密码、拒绝卸载 —— 勒索与反卸载的最后一道门。
     */
    private static void runAdminSentinel(GuardLinkCore.GuardController cur) {
        android.content.SharedPreferences p = app.getSharedPreferences("guardlink", Context.MODE_PRIVATE);
        java.util.Set<String> stored = p.getStringSet("sentinel_admin_known", null);
        java.util.Set<String> known = stored == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(stored);
        java.util.List<String> flats = new java.util.ArrayList<String>();
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            java.util.List<android.content.ComponentName> admins = dpm == null ? null : dpm.getActiveAdmins();
            if (admins != null) {
                for (android.content.ComponentName cn : admins) {
                    if (cn != null) {
                        flats.add(cn.flattenToString());
                    }
                }
            }
        } catch (Throwable ignored) {
            return;   // 读不到就这一拍什么都不说，绝不当成「全部消失」
        }
        java.util.Set<String> trusted = trustedHolders(flats);
        if (stored == null) {
            for (String f : flats) {
                known.add(f.toLowerCase());
            }
            p.edit().putStringSet("sentinel_admin_known", known).apply();
            cur.uiLog("🛡 哨兵基线：当前设备管理员 " + flats.size()
                    + " 个已记名（信任 " + trusted.size() + "），此后新出现的会报警");
            return;
        }
        java.util.List<String> news = GuardSentinel.newUntrustedFlats(flats, known, trusted);
        boolean dirty = false;
        for (String f : news) {
            String pkg = GuardSentinel.flatPkg(f);
            cur.uiLog("🚨 哨兵：新的设备管理员被激活 " + f
                    + " —— 可锁屏/清数据/改密码/拒绝卸载。不是你自己加的：立刻撤销");
            alarm(app, "GuardLink 哨兵：新的设备管理员",
                    (pkg == null ? f : pkg) + " 刚激活设备管理员（可锁屏、清数据、改密码、阻止卸载）。"
                            + "如果你没有主动添加，立即到「设备管理应用」里取消激活并卸载。");
            if (pkg != null) {
                known.add(f.toLowerCase());
                dirty = true;
            }
        }
        if (dirty) {
            p.edit().putStringSet("sentinel_admin_known", known).apply();
        }
    }

    /** 默认短信应用哨兵：换掉它 = 全部验证码改投别人。首帧只建基线，正常换机/换短信 App 不惊动。 */
    private static void runSmsSentinel(GuardLinkCore.GuardController cur) {
        String now = GuardProbe.defaultSmsPackage(app);
        if (now == null || now.trim().length() == 0) {
            return;
        }
        android.content.SharedPreferences p = app.getSharedPreferences("guardlink", Context.MODE_PRIVATE);
        String prev = p.getString("sentinel_sms_default", null);
        if (prev == null) {
            p.edit().putString("sentinel_sms_default", now).apply();
            cur.uiLog("🛡 哨兵基线：默认短信应用记名 " + now);
            return;
        }
        if (prev.equalsIgnoreCase(now)) {
            return;
        }
        p.edit().putString("sentinel_sms_default", now).apply();
        java.util.Set<String> trusted = new java.util.HashSet<String>();
        if (trustedPkg(now)) {
            trusted.add(now);
        }
        if (!GuardSentinel.defaultSmsChanged(prev, now, trusted)) {
            cur.uiLog("ℹ 默认短信应用从 " + prev + " 换成 " + now + "（可信应用，仅记录）");
            return;
        }
        cur.uiLog("🚨 哨兵：默认短信应用被换成 " + now
                + "（原 " + prev + "）—— 收件箱里的验证码现在全归它读");
        alarm(app, "GuardLink 哨兵：默认短信应用被更换",
                "默认短信应用从 " + prev + " 换成了 " + now + "。新应用将能读取全部短信验证码。"
                        + "如果你没有主动更换，立即到系统设置改回并卸载它。");
    }

    /**
     * VPN 激活哨兵：只留痕不报警 —— 正常人也天天用 VPN。它的价值是和其它信号拼图：
     * 同一时段若又出现新无障碍/新通知监听，那就是流量劫持 + 读屏的完整组合。
     */
    private static void runVpnSentinel(GuardLinkCore.GuardController cur) {
        boolean vpn = GuardProbe.vpnActive(app);
        if (GuardSentinel.vpnActivated(lastVpn, vpn)) {
            cur.uiLog("ℹ VPN 刚建立：全部流量经第三方应用转发（若你没开 VPN，留意谁在代理流量）");
        }
        lastVpn = vpn;
    }

    /** "pkg/cls:pkg/cls" → 列表（无障碍 / 通知监听共用）。 */
    private static java.util.List<String> flatsOf(String raw) {
        java.util.List<String> flats = new java.util.ArrayList<String>();
        if (raw != null && raw.length() > 0) {
            for (String s : raw.split(":")) {
                if (s.trim().length() > 0) {
                    flats.add(s.trim());
                }
            }
        }
        return flats;
    }

    /** 信任持有者：守护自己 + 系统应用 + 豁免名单任一档。查不到（已卸载）一律不信任。 */
    private static java.util.Set<String> trustedHolders(java.util.List<String> flats) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String f : flats) {
            String pkg = GuardSentinel.flatPkg(f);
            if (pkg != null && trustedPkg(pkg)) {
                out.add(pkg.toLowerCase());
            }
        }
        return out;
    }

    /** 单个包是否可信（系统应用 / 守护自己 / 豁免名单任一档）。 */
    private static boolean trustedPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        GuardTrust.ensureLoaded(app);
        if (pkg.equals(app.getPackageName())) {
            return true;
        }
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            if (pi != null && pi.applicationInfo != null
                    && (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                return true;
            }
        } catch (Throwable ignored) {
            // 已卸载/不可见：继续走豁免名单
        }
        return GuardTrust.level(pkg) != GuardTrust.Level.NONE;
    }

    /** ADB/无线调试开关哨兵：scrcpy 类免 root 远控的必经通道（样本 dex 里就有配对提示）。 */
    private static void runAdbSentinel(GuardLinkCore.GuardController cur) {
        boolean adb = settingsGlobalOn("adb_enabled");
        boolean wifi = settingsGlobalOn("adb_wifi_enabled");
        GuardSentinel.AdbChange ch = GuardSentinel.adbChange(lastAdbEnabled, adb, lastAdbWifi, wifi);
        lastAdbEnabled = adb;
        lastAdbWifi = wifi;
        if (ch == GuardSentinel.AdbChange.WIRELESS_ON) {
            cur.uiLog("🚨 哨兵：无线调试刚被打开 —— 免 root 远控（scrcpy 类）靠它配对拿 shell 权限，"
                    + "注入键鼠、镜像屏幕。不是你自己开的：立刻关闭，并检查最近安装的应用");
            alarm(app, "GuardLink 哨兵：无线调试已开启",
                    "「开发者选项 → 无线调试」被打开：远控软件可借 ADB 配对注入键鼠、镜像屏幕。"
                            + "不是你本人操作请立即关闭，并卸载来路不明的应用。");
        } else if (ch == GuardSentinel.AdbChange.ADB_ON) {
            cur.uiLog("🚨 哨兵：USB 调试（adb_enabled）刚被打开 —— ADB shell 可静默装包、注入输入事件");
            alarm(app, "GuardLink 哨兵：ADB 调试已开启",
                    "「USB 调试」被打开：拿到 ADB 权限的应用可静默装包、注入点击。"
                            + "不是你本人操作请立即关闭。");
        }
    }

    private static boolean settingsGlobalOn(String key) {
        try {
            return android.provider.Settings.Global.getInt(app.getContentResolver(), key, 0) == 1;
        } catch (Throwable t) {
            return false;      // 读不到当关：哨兵宁可漏报也不拿假警报刷存在感
        }
    }

    private static boolean ticksEvery(int seconds) {
        int per = Math.max(1, seconds * 1000 / TICK_MS);
        return tickCount % per == 0;
    }

    /** 窗口变化：装/卸拦截 + 陌生包冻结 + 进入主界面后拉人工确认窗。 */
    public static void onWindowChanged(final String pkg) {
        GuardLinkCore.GuardController c = controller;
        Handler w = worker;
        if (c == null || w == null || pkg == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                GuardLinkCore.GuardController cur = controller;
                if (cur == null) {
                    return;
                }
                if (cur.lockdown.isActive()) {
                    GuardLinkCore.LockdownController.Action a = cur.lockdown.evaluate(pkg);
                    if (a == GuardLinkCore.LockdownController.Action.BLOCK_INSTALLER) {
                        // 内核见安装器窗口就拦，防的是恶意软件静默装包 / 卸守护。但「本人到设置里
                        // 卸载那个恶意应用」走的是同一个窗口 —— 上一版就是卡在这里：处置动作本身
                        // 被当成攻击，一遍遍弹「安装/卸载行为被拦截」，保护永远退不下去。
                        // 分开两者的信号只有手指：静默装包拿不到真人触摸。
                        long since = lastHumanTouchMs < 0 ? -1L
                                : System.currentTimeMillis() - lastHumanTouchMs;
                        if (GuardSysAi.installerLooksHuman(since, idleMs())) {
                            cur.uiLog("🖐 安装/卸载窗口是真人正在点（距上次触摸 "
                                    + GuardText.duration(since) + "）→ 放行，不拦不冻");
                            return;
                        }
                        cur.uiLog("🚫 Lockdown：无人操作下出现安装/卸载窗口（" + pkg + "）→ 拦截");
                        kickHome();
                        appendLog(GuardShell.forceStop(app, pkg).detail);
                        notifyEpisode(app, "GuardLink 警报：安装/卸载行为被拦截",
                                "Lockdown 期间无人触摸却出现 " + pkg + " 窗口，已强制退出。"
                                        + "若是本人操作，请照屏幕上提示在确认窗点「是误报」，或手动解除保护。");
                        return;
                    }
                    if (a == GuardLinkCore.LockdownController.Action.FREEZE_SUSPECT
                            && !trustedActor(pkg) && frozenRound.add(pkg)) {
                        cur.uiLog("🧊 Lockdown：窗口变化出现陌生包 " + pkg + " → 冻结");
                        appendLog(GuardShell.forceStop(app, pkg).detail);
                        return;
                    }
                }
                if (homePkgs.contains(pkg)) {
                    cur.onLauncherEntered(System.currentTimeMillis());
                }
            }
        });
    }

    /** 踢回主界面：优先无障碍（零提权依赖），服务不在再用 shell 兜底。必须在主线程调无障碍。 */
    public static void kickHome() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                GuardAccessibilityService svc = GuardAccessibilityService.instance();
                if (svc != null) {
                    try {
                        svc.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                GuardShell.sh("input keyevent KEYCODE_HOME");
            }
        });
    }

    /** 用户回到本页（或点通知进来）：静默模式该退出，弹窗恢复照常。 */
    public static void userReturned(Context ctx) {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return;
        }
        if (c.isSilentMode()) {
            c.exitSilentMode();
            appendLog("🔔 你已回到守护页面 → 退出静默模式，累计触发数清零，下次照常弹窗确认");
            GuardState.save(ctx, c);
        }
    }

    // ---------------------------------------------------------------- 处置出口（界面按钮走这里）

    /** 真人撤销断网（界面已先过 HumanVerifier 鉴定）。不在断网保护里就返回原因。 */
    public static String cancelCrack(Context ctx) {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑，也没有断网保护可撤销";
        }
        boolean ok = c.humanCancelCrack(System.currentTimeMillis());
        if (ok) {
            noteAppNetCut(null);
            GuardState.save(ctx, c);
            settle("真人撤销断网");
        }
        return ok ? null : "当前不处于断网保护状态，撤销无效";
    }

    /** 内核里那份 appNetCutPkg 是私有的，动作层执行成功时在这里留一份给界面看。 */
    public static void noteAppNetCut(String pkg) {
        appNetCutPkg = pkg;
    }

    public static String appNetCutPkg() {
        return appNetCutPkg;
    }

    // ---------------------------------------------------------------- 给界面用

    public static List<String> snapshotLogs() {
        synchronized (LOGS) {
            return new ArrayList<>(LOGS);
        }
    }

    public static String statusLine() {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "未运行";
        }
        GuardLinkCore.NoHumanDetector.State s = c.detector.getState();
        String st = s == GuardLinkCore.NoHumanDetector.State.IDLE ? "值守中"
                : s == GuardLinkCore.NoHumanDetector.State.PENDING
                ? "缓冲观察（" + GuardText.duration(pendingMs()) + "）" : "已触发";
        if (c.lockdown.isActive()) {
            st = st + " · Lockdown 生效中";
        }
        if (appNetCutPkg != null) {
            st = st + " · 已精确断网 " + appNetCutPkg;
        } else if (c.networkCut) {
            st = st + " · 全局网络已切断";
        }
        if (c.confirmedCrack) {
            st = st + " · 已确认恶意";
        }
        if (c.isSilentMode()) {
            st = st + " · 静默退避中（累计 " + c.getFireCount() + " 次）";
        }
        return st;
    }

    /** 内核自带的状态报告 + 本层才知道的几件事（精确断网包名、防火墙暂停列表、参数现值）。 */
    public static String statusDetail(Context ctx) {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑：什么都没在被监测。\n";
        }
        StringBuilder sb = new StringBuilder(c.statusReport());
        if (appNetCutPkg != null) {
            sb.append("精确断网应用: ").append(appNetCutPkg)
              .append("（其余应用联网不受影响，可真人按压撤销）\n");
        }
        List<String> cut = GuardA11y.suspendedList(ctx);
        if (!cut.isEmpty()) {
            sb.append("无障碍防火墙暂停中: ").append(cut.size()).append(" 个\n");
            for (String s : cut) {
                sb.append("      └ ").append(s).append('\n');
            }
        }
        sb.append("状态快照: ").append(GuardState.describe(ctx)).append('\n');
        sb.append("网络开关: ").append(GuardNetState.describe(ctx)).append('\n');
        sb.append("确认窗: ").append(confirmState()).append('\n');
        sb.append("触发前提: ").append(signalReport()).append('\n');
        sb.append("豁免名单: ").append(GuardTrust.summary(ctx)).append('\n');
        sb.append("系统级可信执行者: ").append(GuardSysAi.describe(trustedActors())).append('\n');
        sb.append("      └ 开关 ").append(GuardFlags.sysAiOn(ctx) ? "开（guard_sys_ai=1）" : "关（严格模式）")
          .append(" · 距上次真人触摸 ")
          .append(lastHumanTouchMs < 0 ? "这一世还没摸过"
                  : GuardText.duration(System.currentTimeMillis() - lastHumanTouchMs)).append('\n');
        sb.append("通知节奏: ").append(notices.describe(GuardText.stamp(notices.firstAt()))).append('\n');
        sb.append("再生能力: ").append(GuardFlags.regenOn(ctx) ? "开" : "关（不会自己回来）")
          .append(" · 常驻服务 ").append(GuardService.running() ? "在跑" : "没在跑")
          .append(" · 最近任务隐藏 ").append(GuardFlags.hideTaskOn(ctx) ? "开" : "关").append('\n');
        sb.append("      └ 无障碍自愈: ").append(heal.describe()).append('\n');
        if (trustSkipped > 0) {
            sb.append("      └ 「不算证据」档与可信执行者已挡下 ").append(trustSkipped)
              .append(" 条界面事件\n");
        }
        sb.append("参数现值: 无人窗口 ").append(GuardText.duration(idleMs()))
          .append(" · 缓冲 ").append(GuardText.duration(pendingMs()))
          .append(" · 实锤 ").append(burst()).append(" 次")
          .append(" · 确认窗 ").append(GuardText.duration(confirmMs()))
          .append(" · 静默阈值 ").append(silentAfter()).append(" 次")
          .append(" · 嫌疑门槛 ").append(threshold()).append(" 分\n");
        return sb.toString();
    }

    // 参数现值一律直接读内核字段：界面文案与判定共用同一个数，不存在两处口径
    private static long idleMs() {
        return GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS;
    }

    private static long pendingMs() {
        return GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS;
    }

    private static int burst() {
        return GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST;
    }

    private static long confirmMs() {
        return GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS;
    }

    private static int silentAfter() {
        return GuardLinkCore.GuardController.SILENT_AFTER_FIRES;
    }

    private static int threshold() {
        return GuardLinkCore.SuspectEngine.PRIMARY_SUSPECT_THRESHOLD;
    }

    public static long confirmDeadline() {
        return confirmDeadline;
    }

    public static String lastAlarmText() {
        return lastAlarm;
    }

    public static GuardLinkCore.SuspectEngine.Verdict verdict() {
        GuardLinkCore.GuardController c = controller;
        return c == null ? null : c.getLastVerdict();
    }

    public static void appendLog(String line) {
        synchronized (LOGS) {
            LOGS.addLast(line);
            while (LOGS.size() > LOG_CAP) {
                LOGS.pollFirst();
            }
        }
        AppLog.i(TAG, line == null ? "" : GuardText.flat(line));
        UiSink s = sink == null ? null : sink.get();
        if (s != null && line != null) {
            MAIN.post(newLog(s, line));
        }
    }

    private static Runnable newLog(final UiSink s, final String line) {
        return new Runnable() {
            @Override
            public void run() {
                s.onGuardLog(line);
            }
        };
    }

    private static void forwardVerdict(final GuardLinkCore.SuspectEngine.Verdict v) {
        final UiSink s = sink == null ? null : sink.get();
        if (s == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                s.onGuardVerdict(v);
            }
        });
    }

    private static void forwardHealth(final int flags) {
        final UiSink s = sink == null ? null : sink.get();
        if (s != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    s.onGuardHealth(flags);
                }
            });
        }
        String missing = healthGaps(flags);
        if (missing.length() > 0 && notices.gapsChanged(missing)) {
            alarm(app, "GuardLink 守护降级", missing);
        }
    }

    /** 健康位的中文解释，界面和通知共用一份，别说出两套口径。 */
    public static String healthGaps(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & GuardLinkCore.GuardController.HEALTH_ACCESSIBILITY) == 0) {
            sb.append("无障碍未启用（收不到触摸与窗口事件）");
        }
        if ((flags & GuardLinkCore.GuardController.HEALTH_SHIZUKU) == 0) {
            sb.append(sb.length() == 0 ? "" : "；").append("Shizuku 未授权（无法断网/冻结/取证）");
        }
        if ((flags & GuardLinkCore.GuardController.HEALTH_USAGE_STATS) == 0) {
            sb.append(sb.length() == 0 ? "" : "；").append("UsageStats 未授权（媒体豁免与行为终筛降级为保守不豁免）");
        }
        return sb.toString();
    }

    /**
     * 界面上那颗「弹出确认窗」：不等桌面信号，人工要判就现在判。
     *
     * <p>静默模式下内核不弹确认窗（那是「连着炸烦人」的退避），但退避不该把判定权一起收走 ——
     * 他要「确认恶意」时得有地方点。人工点这颗键就是明确要求判定：先退静默，再让内核就地起闸，
     * 否则窗弹出来了倒计时却是空的（{@code confirmDeadline} 还是 MAX_VALUE），到点什么都不发生。
     */
    public static String showConfirmWindow() {
        GuardLinkCore.GuardController c = controller;
        if (c == null) {
            return "守护没在跑，没有要判定的操控";
        }
        if (!c.lockdown.isActive()) {
            return "现在没有进行中的 Lockdown：没有要判定的操控（先等它触发一次）";
        }
        if (c.isSilentMode()) {
            c.exitSilentMode();
            appendLog("🔔 人工要求判定 → 退出静默模式（退避只该压弹窗，不该收走判定权）");
        }
        if (confirmDeadline == Long.MAX_VALUE) {
            appendLog("🔔 人工要求判定 → 就地起倒计时，不等桌面回前台");
            c.onLauncherEntered(System.currentTimeMillis());   // 内核设截止，回调里弹窗
            if (confirmDeadline == Long.MAX_VALUE) {
                return "本波已经人工判定过了（确认窗一波只判一次）："
                        + "要重新判定就先解除保护，让它再触发一次";
            }
            return null;
        }
        appendLog("🔔 人工把确认窗重新调到前台（倒计时照原截止走）");
        openConfirmWindow();
        return null;
    }

    private static void openConfirmWindow() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent it = new Intent(app, GuardConfirmActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    app.startActivity(it);
                } catch (Throwable t) {
                    appendLog("确认窗拉不起来（" + t.getClass().getSimpleName()
                            + "），请用系统通知进入本页手动处置");
                    notifyEpisode(app, "GuardLink：需要人工确认",
                            "疑似恶意操控已触发 Lockdown。请打开「安全守护」点击是否误报。");
                }
            }
        });
    }

    // ---------------------------------------------------------------- 通知

    public static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL, "守护警报",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("疑似被远程操控时的警报与人工确认提醒");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            AppLog.e(TAG, "通知渠道创建失败", t);
        }
    }

    /**
     * 一波操控的通知节奏：Lockdown 起来 = 这一波开始，<b>第一次</b>必须发手机通知
     * （人在别的 App 里，不发通知就等于没通知）；这一波里后面再多的事件只写事件流。
     *
     * <p>心跳和事件入口都调它：Lockdown 是内核在那一拍里置起来的，只靠 500ms 心跳认边沿的话，
     * 这半秒里装卸拦截那条会先把名额占走，结果「检测到远程操控」这条最该发的反而没发。
     */
    private static void tickEpisode(GuardLinkCore.GuardController c, long now) {
        boolean on = c.lockdown.isActive();
        if (on && !notices.inEpisode()) {
            notices.episodeStarts();
            firedAtMs = now;              // 兜底起闸从这一拍算起
            silentStuckWarned = false;
        } else if (!on && notices.inEpisode()) {
            notices.episodeEnds();
            firedAtMs = 0L;
            appendLog("✅ 本波 Lockdown 已结束 → 下次检测到的第一次会再发手机通知");
        }
        if (on && notices.claim(now)) {
            alarm(app, "GuardLink 警报：检测到疑似远程操控", firstAlertText(c));
        }
    }

    /** 一波之内只发一条手机通知：领不到名额就只写事件流，并把为什么没发说清楚。 */
    private static void notifyEpisode(Context ctx, String title, String text) {
        if (notices.claim(System.currentTimeMillis())) {
            alarm(ctx, title, text);
            return;
        }
        appendLog("🔕 同一波只发一次手机通知（首次 " + GuardText.stamp(notices.firstAt())
                + " 那条）：" + title + " · " + text);
    }

    /** 首次触发那条通知的正文：说清楚判到了什么、现在处于什么保护、要点开做什么。 */
    private static String firstAlertText(GuardLinkCore.GuardController c) {
        GuardLinkCore.SuspectEngine.Verdict v = c.getLastVerdict();
        String who = "还在取证（主要嫌疑未定）";
        if (v != null && v.primary != null) {
            who = v.primary.profile.pkg;
        }
        return "屏幕没人碰却在动 → Lockdown 已生效：隐私应用踢回、装卸拦截、不可信无障碍暂停。\n"
                + "当前主要嫌疑：" + who + "\n"
                + "点开创卫页选择「是误报」或「确认恶意」；不点就不动网络。";
    }

    /** 高优先级警报通知，点开直接进功能页；失败也只记日志，绝不因为通知发不出去就影响处置。 */
    public static void alarm(Context ctx, String title, String text) {
        if (ctx == null) {
            return;
        }
        lastAlarm = title + "｜" + text;
        appendLog("🔔 " + title);
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            ensureChannel(ctx);
            Intent open = new Intent(ctx, GuardLinkActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, open,
                    android.os.Build.VERSION.SDK_INT >= 23
                            ? (android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE)
                            : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Throwable t) {
            AppLog.e(TAG, "警报通知发送失败", t);
        }
    }

    /**
     * 「现在需要你判定」那条通知：确认窗的保底入口。
     *
     * <p>Android 10 起后台起 Activity 会被系统静默拦掉（不抛异常），所以只靠 startActivity
     * 弹确认窗，在很多 ROM 上就是「什么都没发生」—— 他看到的「只有确认误报、没有确认恶意」
     * 有一半是这里。这条通知带 fullScreenIntent：有权限就直接盖上来，没权限就是横幅，
     * 点开同样进确认窗，两个键（是误报 / 确认恶意）都在里面。
     */
    private static void confirmNotice(long deadlineTs) {
        Context ctx = app;
        if (ctx == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            ensureChannel(ctx);
            Intent open = new Intent(ctx, GuardConfirmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 1, open, flags);
            long left = Math.max(0L, deadlineTs - System.currentTimeMillis());
            String tail = GuardFlags.cutOnTimeout(ctx)
                    ? "无人点击就按「确认恶意」处置：先只掐嫌疑应用自己的联网。"
                    : "超时断网已关闭（guard_timeout_cut=0）：到点只维持 Lockdown，不动网络。";
            String text = "Lockdown 已生效，" + GuardText.duration(left) + "内需要你判定。" + tail;
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("需要人工确认：这次是否是误报？")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .build();
            nm.notify(CONFIRM_NOTIFY_ID, n);
            confirmNoticeUp = true;
            appendLog("🔔 已发「需要人工确认」通知（确认窗被系统挡住时，点这条同样能判定）");
        } catch (Throwable t) {
            appendLog("确认通知发不出去（" + t.getClass().getSimpleName()
                    + "）：请自己打开「安全守护」点「弹出确认窗」");
            AppLog.e(TAG, "确认通知发送失败", t);
        }
    }

    /** 判定完了（误报 / 恶意 / 超时）就把那条常驻的确认通知撤掉，别一直挂着。 */
    public static void clearConfirmNotice() {
        Context ctx = app;
        if (ctx == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(CONFIRM_NOTIFY_ID);
            }
        } catch (Throwable ignored) {
        }
    }
}
