package com.mineways.guardlink;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.zeus.guardlink.GuardLinkCore;

/**
 * 人工确认窗：「这次是否是误报？」
 *
 * <p>它是整套守护里唯一一个「真人 vs 程序」的关口，所以实现必须守住三件事：
 * <ol>
 *   <li><b>只认 onTouch 的 DOWN→UP 时序</b>。无障碍的 {@code performAction(ACTION_CLICK)}
 *       只发 TYPE_VIEW_CLICKED，不产生 MotionEvent；{@code input tap} 注入的 DOWN/UP 在同一帧
 *       （&lt;40ms）。这两类都被 {@link GuardLinkCore.HumanVerifier} 直接拒掉。</li>
 *   <li><b>无效点击不消耗窗口</b>。恶意软件抢点多少次都没用，倒计时照常走到结束 —— 拒完就返回，
 *       不 finish、不改 deadline。</li>
 *   <li><b>返回键不能关掉它</b>；超时由服务那侧的 tick 收尾，界面自己不做「跳过」。</li>
 * </ol>
 *
 * <p>视觉走本页原有的玻璃内核（{@code MwViews}），不是 demo 那种裸控件。
 */
public class GuardConfirmActivity extends android.app.Activity {

    private Long downAt;
    private Long upAt;
    private long deadline;
    private TextView countdown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private GuardLinkCore.GuardController ctl;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                finish();
                return;
            }
            countdown.setText(remainText(left));
            main.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ctl = GuardRuntime.controller();
        if (ctl == null) {
            // 守护已停 or 还没装配：不该继续拦用户，直接走
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        deadline = GuardRuntime.confirmDeadline();
        if (deadline <= System.currentTimeMillis()) {
            deadline = System.currentTimeMillis()
                    + GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS;
        }
        setContentView(buildUi());
        GuardRuntime.appendLog("🔔 人工确认窗已弹出（只有真人按压算数）");
        main.post(tick);
    }

    private String remainText(long leftMs) {
        String tail = GuardFlags.cutOnTimeout(this)
                ? "到点无人点击 → 按「确认恶意」处置：优先只掐嫌疑应用自己的联网"
                : "到点无人点击 → 只保持 Lockdown，不断网（guard_timeout_cut=0，云控或本机下发）";
        return "剩余 " + (leftMs / 1000) + " 秒（窗口 "
                + GuardText.duration(GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS)
                + "，可在「检测 · 真人按压与退避」里改）· " + tail;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(com.mineways.ui.MwViews.pageBackground(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(com.mineways.ui.MwViews.dp(this, 20), com.mineways.ui.MwViews.dp(this, 22),
                com.mineways.ui.MwViews.dp(this, 20), com.mineways.ui.MwViews.dp(this, 26));
        scroll.addView(root);

        root.addView(com.mineways.ui.MwViews.header(this, "安全守护", "这次是否是误报？",
                "只认真人按压，模拟/远程点击无效。"));

        LinearLayout card = com.mineways.ui.MwViews.card(this, 14);
        card.addView(com.mineways.ui.MwViews.body(this, GuardRuntime.statusLine(),
                com.mineways.ui.MwViews.WARN, 14f));
        countdown = com.mineways.ui.MwViews.body(this, remainText(
                deadline - System.currentTimeMillis()), com.mineways.ui.MwViews.TEXT_2, 13f);
        card.addView(countdown);
        root.addView(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = com.mineways.ui.MwViews.dp(this, 14);
        row.setLayoutParams(rlp);

        TextView safe = com.mineways.ui.MwViews.pillButton(this, "是误报（解除保护）", false, null);
        TextView danger = com.mineways.ui.MwViews.pillButton(this, "确认恶意（先只掐它联网）", true, null);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = com.mineways.ui.MwViews.dp(this, 8);
        safe.setLayoutParams(lp1);
        safe.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        danger.setLayoutParams(lp2);
        danger.setGravity(Gravity.CENTER);
        row.addView(safe);
        row.addView(danger);
        root.addView(row);

        final View.OnClickListener guarded = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handle(v == safe ? 0 : 1);
            }
        };
        safe.setOnClickListener(guarded);
        danger.setOnClickListener(guarded);

        View.OnTouchListener logger = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                long now = System.currentTimeMillis();
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downAt = now;
                    upAt = null;
                } else if (a == MotionEvent.ACTION_UP) {
                    upAt = now;
                }
                return false;       // 不吞事件，继续走 onClick
            }
        };
        safe.setOnTouchListener(logger);
        danger.setOnTouchListener(logger);

        return scroll;
    }

    private void handle(int which) {
        GuardLinkCore.GuardController c = GuardRuntime.controller();
        if (c == null) {
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        String reject = GuardLinkCore.HumanVerifier.verify(now, downAt, upAt);
        if (reject != null) {
            // 关键：拒了就原地不动，窗口继续开着
            GuardRuntime.appendLog("⛔ 拒绝程序点击：" + reject);
            Toast.makeText(this, "这次点击不算数：" + reject, Toast.LENGTH_LONG).show();
            downAt = null;
            upAt = null;
            return;
        }
        downAt = null;
        upAt = null;
        if (which == 0) {
            c.humanConfirmDismiss(now);
            // 状态机停在 FIRED 就不再响应任何事件（SCREEN_ON 见非 IDLE 直接 return，
            // UI_ACTIVITY 只认 IDLE/PENDING 两个分支）。不复位的话，误报解除后守护就永久失明了。
            c.detector.reset();
            Toast.makeText(this, "已按误报处理，Lockdown 解除，网络未动", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // 「确认恶意」是不可逆动作（断网），所以真人点过之后还要再确认一次；
        // 第一次点击已经把 downAt/upAt 消费掉，第二次同样必须走真人按压。
        new AlertDialog.Builder(this)
                .setTitle("确认恶意操控？")
                .setMessage("只切断嫌疑应用自己的联网；误判可回安全守护页撤销。")
                .setPositiveButton("确认断网", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        c.humanConfirmCrack(now);
                        finish();
                    }
                })
                .setNegativeButton("再想想", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        // 故意不响应：返回键不该成为绕过人工确认的出口
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        long d = GuardRuntime.confirmDeadline();
        if (d > System.currentTimeMillis()) {
            deadline = d;
            main.removeCallbacks(tick);
            main.post(tick);
        }
    }
}
