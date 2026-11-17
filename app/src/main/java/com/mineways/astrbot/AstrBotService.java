package com.mineways.astrbot;

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
import android.os.PowerManager;

import com.mineways.AppLog;
import com.mineways.blender.Downloader;

import java.util.ArrayDeque;

/**
 * 前台服务：部署与机器人进程都挂在这条线上，锁屏后进程树不被回收。
 * 通知栏显示当前阶段；部署/启动的进度与日志通过静态字段给界面轮询。
 */
public final class AstrBotService extends Service {

    static final String ACTION_DEPLOY = "com.mineways.astrbot.DEPLOY";
    static final String ACTION_START = "com.mineways.astrbot.START";
    static final String ACTION_STOP = "com.mineways.astrbot.STOP";
    static final String ACTION_CANCEL = "com.mineways.astrbot.CANCEL";

    private static final String CHANNEL = "mw_astrbot";
    private static final int NOTIFY_ID = 7301;

    /** 界面轮询用的共享进度（部署可能跑十分钟，Activity 关掉也要继续）。 */
    static volatile boolean deploying;
    static volatile int percent;
    static volatile String status = "";
    static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private static final Downloader.Cancel CANCEL = () -> !deploying;
    private static volatile boolean cancelRequested;

    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int healthFails;
    private int healthRestarts;
    private boolean healthScheduled;

    static void start(Context c, String action) {
        Intent it = new Intent(c, AstrBotService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
            else c.startService(it);
        } catch (Throwable t) {
            AppLog.e("astrbot", "前台服务拉不起来", t);
        }
    }

    static void cancelDeploy() {
        cancelRequested = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AstrBot 机器人",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mineways:astrbot");
            wake.setReferenceCounted(false);
            wake.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        startForegroundSafe(buildNotification("AstrBot"));

        if (ACTION_STOP.equals(action)) {
            new Thread(() -> {
                ui.removeCallbacks(healthTask);
                healthScheduled = false;
                AstrBotOrchestrator.stop(this);
                stopSelf();
            }, "astrbot-stop").start();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            return START_NOT_STICKY;
        }
        if (ACTION_DEPLOY.equals(action)) {
            if (deploying) return START_NOT_STICKY;
            new Thread(this::doDeploy, "astrbot-deploy").start();
            return START_NOT_STICKY;
        }
        new Thread(this::doStart, "astrbot-start").start();
        return START_NOT_STICKY;
    }

    private void doDeploy() {
        if (!AstrBotEnv.enabled(this)) {
            setStatus("云控未开放（astrbot_cfg.enabled=0）");
            deploying = false;
            return;
        }
        deploying = true;
        cancelRequested = false;
        percent = 0;
        setStatus("准备部署…");
        try {
            AstrBotInstaller.deploy(this, new AstrBotInstaller.Progress() {
                @Override
                public void onStatus(String msg) {
                    setStatus(msg);
                }

                @Override
                public void onPercent(int p) {
                    percent = p;
                    notifyText(status + "（" + p + "%）");
                }

                @Override
                public void onLine(String line) {
                    addLine(line);
                }
            }, () -> cancelRequested || !deploying);
            setStatus("部署完成");
            percent = 100;
            notifyText("AstrBot 部署完成，可以启动");
        } catch (Throwable t) {
            final String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            setStatus("部署失败：" + msg);
            addLine("! " + msg);
            AppLog.e("astrbot", "部署失败", t);
            notifyText("AstrBot 部署失败（点开看日志）");
            deploying = false;
            return;
        }
        deploying = false;
    }

    private void doStart() {
        // 先执行云端一次性运维指令（停止/重置/重启），避免"刚点启动又被远程停"
        final String ops = AstrBotOps.applyEmergency(this);
        if (!ops.isEmpty()) {
            setStatus(ops);
            AppLog.i("astrbot", "云端运维：" + ops);
        }
        if (!AstrBotEnv.deployed(this)) {
            setStatus("尚未部署（或已被远程重置）");
            return;
        }
        notifyText("AstrBot 启动中…");
        setStatus("启动中…");
        AstrBotOrchestrator.Status s = AstrBotOrchestrator.start(this, msg -> {
            setStatus(msg);
            notifyText("AstrBot：" + msg);
        });
        setStatus(s.note);
        notifyText("AstrBot：" + s.note);
        if (s.state == AstrBotOrchestrator.State.RUNNING && AstrBotEnv.healthEnabled(this)) {
            healthFails = 0;
            healthRestarts = 0;
            scheduleHealth();
        }
        if (s.state != AstrBotOrchestrator.State.RUNNING) {
            // 启动失败/半启动：保持服务但不刷新通知，避免误导为运行中
            AppLog.e("astrbot", "启动未完成：" + s.note, null);
        }
    }

    // ---------------------------------------------------------------- 健康检查（运维）

    private void scheduleHealth() {
        if (healthScheduled) return;
        healthScheduled = true;
        ui.postDelayed(healthTask, AstrBotEnv.healthSec(this) * 1000L);
    }

    private final Runnable healthTask = new Runnable() {
        @Override
        public void run() {
            healthScheduled = false;
            if (!AstrBotEnv.enabled(AstrBotService.this)
                    || !AstrBotEnv.healthEnabled(AstrBotService.this)
                    || !AstrBotEnv.deployed(AstrBotService.this)) {
                return;
            }
            final Context app = getApplicationContext();
            new Thread(() -> {
                final boolean ok = AstrBotOrchestrator.portOpen(AstrBotEnv.portWebui(app))
                        && AstrBotOrchestrator.portOpen(AstrBotEnv.portWs(app));
                ui.post(() -> {
                    if (ok) {
                        healthFails = 0;
                        healthRestarts = 0;
                    } else if (++healthFails >= 2) {
                        healthFails = 0;
                        if (healthRestarts >= 3) {
                            setStatus("健康检查连续失败，已停止自动重启（3 次）");
                            notifyText("AstrBot 组件异常，自动重启已停手，请查看诊断");
                            return;
                        }
                        healthRestarts++;
                        setStatus("健康检查失败，自动重启 AstrBot（第 " + healthRestarts + " 次）…");
                        notifyText("AstrBot 异常，正在自动重启");
                        new Thread(() -> {
                            AstrBotOrchestrator.stop(app);
                            AstrBotOrchestrator.start(app, msg -> setStatus(msg));
                        }, "astrbot-heal").start();
                    }
                    ui.postDelayed(healthTask, AstrBotEnv.healthSec(app) * 1000L);
                    healthScheduled = true;
                });
            }, "astrbot-health").start();
        }
    };

    private static synchronized void setStatus(String msg) {
        status = msg == null ? "" : msg;
    }

    private static synchronized void addLine(String line) {
        if (line == null) return;
        if (LINES.size() >= 400) LINES.pollFirst();
        LINES.offerLast(line.length() > 500 ? line.substring(0, 500) : line);
    }

    static synchronized String linesText() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clearLines() {
        LINES.clear();
    }

    // ---------------------------------------------------------------- 通知

    private void startForegroundSafe(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Throwable t) {
            AppLog.e("astrbot", "startForeground 失败", t);
        }
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFY_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, AstrBotActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("AstrBot 机器人")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            AstrBotOrchestrator.stop(this);
        } catch (Throwable ignored) {
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
