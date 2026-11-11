package com.mineways.guardlink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.zeus.guardlink.GuardLinkCore;

/**
 * 复活监测：卸载嫌疑包后如果同名包又被装回来，说明有同伙在拉活重装 —— 警报 + 反查安装来源。
 *
 * <p>诚实说明边界：Android 13/14 起 {@code PACKAGE_ADDED} 这类包变更广播对普通应用收窄，
 * 不是安装方就可能收不到。收不到不等于没被重装，所以功能页另外给了一颗「手动查安装来源」，
 * 把这条链补上；这里收到就报警，收不到也不谎称「已确认干净」。
 */
public class GuardRebirthReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            return;
        }
        String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
        GuardLinkCore.GuardController c = GuardRuntime.controller();
        if (pkg == null || c == null || c.lastUninstalledPkg == null
                || !pkg.equals(c.lastUninstalledPkg)) {
            return;
        }
        GuardShell.Result who = GuardShell.installerOf(ctx, pkg);
        GuardRuntime.appendLog("☠ 已卸载的嫌疑包复活：" + pkg + "，安装来源线索：" + who.detail);
        GuardRuntime.alarm(ctx, "GuardLink 警报：已卸载的恶意应用复活",
                pkg + " 又被装回来了。安装来源：" + (who.ok ? who.detail : "查不到（" + who.detail + "）")
                        + "。建议保持断网并手动核对来源。");
    }
}
