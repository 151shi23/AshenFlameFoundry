package com.mineways.guardlink;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.mineways.AppLog;
import com.zeus.guardlink.GuardLinkCore;

/**
 * 守护的信号源：只收事件时序，<b>不读屏幕内容</b>（配置里 {@code canRetrieveWindowContent=false}）。
 *
 * <p>事件映射（§8.3.156 起撤防信号<b>过真人鉴定</b>，之前是四类事件一律喂 HUMAN_TOUCH ——
 * 自动点击器 / 远控注入的「假手指」每一下都在替攻击者撤防，检测永远开不了口）：
 * <ul>
 *   <li>TYPE_TOUCH_INTERACTION_END：按压时长 ≥ {@code press_ms} 才算真人（瞬时单点 = 注入）</li>
 *   <li>VIEW_CLICKED / LONG_CLICKED：必须紧随一段真实触摸序列（内核 {@code HumanVerifier}）；
 *       ROM 连触摸交互事件都不给时降级为「点击即真人」（§8.3.153 预留的判据）</li>
 *   <li>TYPE_WINDOW_STATE_CHANGED → UI_ACTIVITY（C2 静默运行型），并交给 Lockdown 处置</li>
 * </ul>
 * 已知极限：dispatchGesture 注入的<b>完整手势</b>（有时长、有起止）在无障碍事件流里与真人同形，
 * 这条鉴定挡的是「瞬时点击型」注入；完整手势注入只能靠 Lockdown 期间的溯源与人工确认窗兜底。
 *
 * <p>两处相对 demo 的适配：
 * <ol>
 *   <li><b>本 App 自己的包名一律不喂</b>。Core 的白名单写的是 {@code com.zeus.guardlink}
 *       （demo 的包名），我们的包是 {@code com.mineways}，不改的话「自己的确认窗弹出来」
 *       会被当成「界面自行变化」而自我触发。修在喂事件这一侧，不动 vendored 源码，
 *       将来拿新 zip 仍能整包覆盖。</li>
 *   <li>事件不在主线程处置，一律交 {@link GuardRuntime} 的工作线程；服务只做「收」和「转」。</li>
 * </ol>
 */
public class GuardAccessibilityService extends AccessibilityService {

    private static volatile GuardAccessibilityService service;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String ownPackage;

    // ---- 真人按压鉴定的现场：最近一段触摸交互序列的起止（getEventTime()，同一 uptime 时基） ----
    private long touchDownAt = -1L;
    private long touchUpAt = -1L;
    private boolean everSawTouchSeq = false;   // 这台 ROM 给没给过触摸交互事件（降级判据用）
    private long windowEvents = 0L;            // 本服务自己数的窗口变化（攒够样本才许判 ROM 死刑）
    private long lastRejectLogAt = 0L;         // 拒绝说明限流：点击器狂点时不能刷爆事件流

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            GuardRuntime.heartbeat();
            main.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) {
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.SCREEN_ON, null, false, 0);
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.SCREEN_OFF, null, false, 0);
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.USER_PRESENT, null, false, 0);
            }
        }
    };

    public static GuardAccessibilityService instance() {
        return service;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        service = this;
        ownPackage = getPackageName();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
        } catch (Throwable t) {
            AppLog.e("GuardLink", "亮屏/解锁广播注册失败", t);
        }
        main.removeCallbacks(beat);
        main.postDelayed(beat, 500);
        GuardRuntime.appendLog("无障碍信号源已连上，本服务只做「收事件 + 转线程」");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        String pkg = e.getPackageName() == null ? null : String.valueOf(e.getPackageName());
        if (pkg != null && pkg.equals(ownPackage)) {
            return;     // 自己不算嫌疑，见类注释第 1 条
        }
        switch (e.getEventType()) {
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                everSawTouchSeq = true;
                touchDownAt = e.getEventTime();
                break;
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END: {
                everSawTouchSeq = true;
                long up = e.getEventTime();
                long dur = touchDownAt < 0 ? -1L : up - touchDownAt;
                touchUpAt = up;
                if (dur >= GuardLinkCore.HumanVerifier.MIN_TOUCH_DURATION_MS) {
                    GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, pkg, false, 0);
                } else {
                    reject("触摸序列只有 " + dur + "ms（真人手指 ≥"
                            + GuardLinkCore.HumanVerifier.MIN_TOUCH_DURATION_MS
                            + "ms）→ 疑似注入瞬时点击，不撤防");
                }
                break;
            }
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED: {
                String why = GuardLinkCore.HumanVerifier.verify(e.getEventTime(),
                        touchDownAt < 0 ? null : touchDownAt, touchUpAt < 0 ? null : touchUpAt);
                if (why == null) {
                    GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, pkg, false, 0);
                } else if (degradedClicksTrusted()) {
                    // 这台 ROM 连触摸交互事件都不给：序列鉴定无从做起，点击降级为唯一真人证据
                    GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, pkg, false, 0);
                } else {
                    reject("点击不算真人（" + why + "）→ 不撤防");
                }
                break;
            }
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                windowEvents++;
                GuardRuntime.feed(GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, pkg, false, 0);
                GuardRuntime.onWindowChanged(pkg);
                break;
            default:
                // WINDOW_CONTENT_CHANGED 噪声太大（刷视频、列表滚动），不作为触发源
                break;
        }
    }

    /**
     * ROM 不给触摸交互事件时（§8.3.153 的判死分支），VIEW_CLICKED 单独算真人 ——
     * 不然真人连点都点不撤防，守护在那种机器上就永远哑着。前提是窗口事件样本已攒够
     * （证明不是「序列还没来」，是真的不给），并且本服务确实从没收到过任何触摸序列。
     */
    private boolean degradedClicksTrusted() {
        return !everSawTouchSeq && windowEvents >= GuardPremise.MIN_WINDOW_EVENTS_TO_JUDGE;
    }

    /** 拒绝说明 5 秒最多一条：自动点击器一秒几十下，全打出来事件流就废了。 */
    private void reject(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRejectLogAt < 5_000L) {
            return;
        }
        lastRejectLogAt = now;
        GuardRuntime.appendLog("✋ " + why + "（本类日志 5 秒最多一条）");
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        service = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        service = null;
        main.removeCallbacks(beat);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        // 无障碍被省电策略/用户误关掐掉：先报警，再抢先试一次自己补回去。
        // 这一把只是「抢先」—— 进程被划掉时这条回调根本不跑，真正的自愈在常驻服务里定时做。
        GuardRuntime.appendLog("⚠ 无障碍服务被关闭，守护降级（只缺触摸与窗口事件）");
        GuardRuntime.selfHealNow(this);
        super.onDestroy();
    }
}
