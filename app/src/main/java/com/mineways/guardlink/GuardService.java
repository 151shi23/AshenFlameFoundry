package com.mineways.guardlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.mineways.AppLog;

/**
 * 守护的常驻服务：让进程活得住，并<b>定时把守护和无障碍拉回来</b>。
 *
 * <p>为什么需要它：守护原来只有 Activity + 无障碍服务，没有进程常驻 —— 恶意软件（或省电策略）
 * 把这张卡片从最近任务里一划，进程就没了，检测、保护、通知全部失效；而原来唯一的自愈钩子写在
 * {@code GuardAccessibilityService.onDestroy()} 里，进程被杀时那条回调根本不跑，
 * 于是「Shizuku 权限给了也不会把自己拉起来」。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>常驻前台服务</b>（{@code dataSync}，抄 {@code AstrBotService} 的写法）：
 *       通知压到最低优先级、不响、不进角标，只是让进程优先级提上来。</li>
 *   <li><b>定时自愈</b>：每 2 秒一拍，两件事 —— 守护该在而不在就重新起来（{@code recover=true}
 *       接回 Lockdown/静默/触发数）；无障碍掉了就通过 Shizuku 补写回去（节流 15 秒，见 {@link GuardHeal}）。</li>
 *   <li><b>被划掉立刻自拉</b>：{@code onTaskRemoved} 与 {@code onDestroy} 都排一次重新拉起，
 *       不靠周期闹钟（他明确不要周期哨兵，省电优先）。</li>
 * </ul>
 *
 * <p>不上 WakeLock：判定只在亮屏/解锁后的窗口里成立（内核 C1 要唤醒信号），
 * 息屏深睡时没有要盯的东西，为这个耗电台上不值得。
 */
public class GuardService extends Service {

    public static final String ACTION_START = "guard_alive_start";
    public static final String ACTION_STOP = "guard_alive_stop";

    private static final String CHANNEL = "guard_alive";
    private static final int NOTIFY_ID = 52511;
    private static final long BEAT_MS = 2_000L;

    private static volatile boolean alive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                AppLog.e("GuardLink", "常驻服务心跳异常", t);
            }
            main.postDelayed(this, BEAT_MS);
        }
    };

    static void start(Context c, String action) {
        Intent it = new Intent(c, GuardService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(it);
            } else {
                c.startService(it);
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "常驻服务拉不起来", t);
        }
    }

    /** 界面那一行「常驻服务」要说实话。 */
    public static boolean running() {
        return alive;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        startForegroundSafe(buildNotification());
        if (ACTION_STOP.equals(action)) {
            main.removeCallbacks(beat);
            stopSelf();
            return START_NOT_STICKY;
        }
        main.removeCallbacks(beat);
        main.postDelayed(beat, 500L);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 卡片被划掉：立刻再拉一次。同一个进程还活着时这是最快的复活路径；
        // 进程真被 ROM 掐死时靠 START_STICKY 与下一次无障碍事件/开机广播。
        if (shouldRevive()) {
            GuardRuntime.appendLog("🔄 任务被从最近任务划掉 → 常驻服务立刻自拉");
            start(this, ACTION_START);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        alive = false;
        main.removeCallbacks(beat);
        // 被系统/ROM 杀掉也要留一次复活的机会（一次性闹钟，不是周期哨兵）
        if (shouldRevive()) {
            try {
                start(this, ACTION_START);
            } catch (Throwable t) {
                AppLog.e("GuardLink", "常驻服务重启失败", t);
            }
        }
        super.onDestroy();
    }

    /**
     * 这条服务该不该自己活过来。
     *
     * <p>三个条件都得在：用户想开着守护、再生开关没关、总闸没关。<b>总闸这一条不能漏</b> ——
     * 云控把 {@code guard_on} 关成 0 时 {@code tick()} 会主动 {@code stopSelf()}，
     * 如果 {@code onDestroy} 不看总闸就会把它再拉起来，两条互相打，通知栏一直挂着「正在值守」。
     */
    private boolean shouldRevive() {
        return GuardRuntime.wanted(this) && GuardFlags.regenOn(this) && GuardFlags.masterOn(this);
    }

    /** 一拍：守护该在而不在就起来；无障碍掉了就补回去。 */
    private void tick() {
        if (!shouldRevive()) {
            // 停用或总闸关了：常驻服务没有理由继续挂着那条「安全守护正在值守」的通知
            main.removeCallbacks(beat);
            stopSelf();
            return;
        }
        if (!GuardRuntime.running()) {
            String why = GuardRuntime.enable(this, true);
            if (why == null) {
                GuardRuntime.appendLog("🔄 常驻服务把守护拉回来了（接回上次落盘的保护状态）");
            } else {
                GuardRuntime.appendLog("⚠ 守护没能自动回来：" + why);
            }
            return;
        }
        GuardRuntime.selfHeal(this);
    }

    private void ensureChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL, "守护常驻",
                    NotificationManager.IMPORTANCE_MIN);
            // 静音要在渠道上钉死：IMPORTANCE_MIN 在个别 ROM 上仍会闪一下，
            // 而 Notification.Builder 没有公开的 setSilent，只能从源头把声音与震动关掉。
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            AppLog.e("GuardLink", "常驻通知渠道创建失败", t);
        }
    }

    /** 常驻通知：不响、不震动、不进角标，只说明谁在值守、点开能干什么。 */
    private Notification buildNotification() {
        Intent open = new Intent(this, GuardLinkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23
                        ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle("安全守护正在值守")
                .setContentText(GuardRuntime.statusLine() + " · 点开看判定与处置")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    private void startForegroundSafe(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "常驻前台通知挂不上", t);
        }
    }
}
