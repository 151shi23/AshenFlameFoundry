package com.mineways.guardlink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mineways.AppLog;

/**
 * 开机后把守护接回来。
 *
 * <p>只在这一种情况下真正起守护：用户上次是开着用的（{@code guardlink.user_on}），
 * 且云端/本机总闸没关。恢复的是保护与监测（Lockdown、静默模式、累计触发数、复活监测的卸载包名），
 * <b>不自动重断网</b> —— 重启本身就让 {@code svc} 设置失效、iptables 规则随内核清空，
 * 醒来第一件事就断网会把「重启就能上网」这条逃生路堵死，误判时代价太大。
 *
 * <p>Manifest 侧静态注册 {@code BOOT_COMPLETED} + {@code LOCKED_BOOT_COMPLETED}，
 * 需要 {@code RECEIVE_BOOT_COMPLETED} 权限；HyperOS/ColorOS 还要在自启动白名单里放行（引导页有说明）。
 */
public class GuardBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null || (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(a))) {
            return;
        }
        try {
            if (!GuardFlags.bootRecover(ctx)) {
                AppLog.i("GuardLink", "开机：跨重启续状态已关闭，不起守护");
                return;
            }
            // 这条注释以前就写了「用户上次是开着用的」，但代码根本没查 —— 于是停用守护后
            // 一开机又自己起来。意愿在这里落地，跟启动页走同一个判断。
            if (!GuardRuntime.wanted(ctx)) {
                AppLog.i("GuardLink", "开机：用户已停用守护，不自动接回");
                return;
            }
            String why = GuardRuntime.enable(ctx, true);
            if (why != null) {
                AppLog.i("GuardLink", "开机未接回守护：" + why);
                return;
            }
            GuardRuntime.appendLog("开机接回守护（" + GuardText.stamp(GuardState.lastSavedAt(ctx)) + " 的快照）");
        } catch (Throwable t) {
            AppLog.e("GuardLink", "开机恢复守护失败", t);
        }
    }
}
