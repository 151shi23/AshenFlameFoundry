package com.mineways.prism;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 隐藏系统通知（snooze 3 年）的读写层。
 *
 * <p>两条铁律（与逆向文档一致）：
 * <ul>
 *   <li>隐藏用 {@code snoozeNotification(key, 94608000000ms)}，不用 cancel —— 可逆；</li>
 *   <li>恢复用 {@code snoozeNotification(key, 100ms)}，通知立刻回到通知栏。</li>
 * </ul>
 *
 * <p>被 snooze 后通知不再出现在活动列表里，所以这里把 key + 标签另存一份，
 * 供「已隐藏」列表恢复用；key 本身是系统分配的，重启后仍有效。
 */
public final class PrismNotifications {

    /** 3 年 = 事实隐藏。 */
    public static final long HIDE_MS = 94608000000L;
    /** 100ms = 立即弹回。 */
    public static final long SHOW_MS = 100L;

    private static final String PREFS = "prism_notifications";
    private static final String K_HIDDEN = "hidden";
    private static final String SEP = "\u001F";

    private PrismNotifications() {
    }

    public static final class Record {
        public final String key;
        public final String label;

        Record(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** 是否已获得「通知使用权」。 */
    public static boolean access(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.isNotificationListenerAccessGranted(
                        new ComponentName(c, PrismNotificationService.class));
            }
            String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static Intent accessIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static PrismShell.Res hide(Context c, PrismNotificationService.Item item) {
        if (item == null || TextUtils.isEmpty(item.key)) return new PrismShell.Res(false, "通知无效");
        if (!PrismNotificationService.connected()) {
            return new PrismShell.Res(false, "通知服务未连接：请在系统「通知使用权」里勾选本应用后重试");
        }
        if (!PrismNotificationService.snooze(item.key, HIDE_MS)) {
            return new PrismShell.Res(false, "系统拒绝隐藏（可能是不可 snooze 的通知）");
        }
        remember(c, item.key, item.label());
        return new PrismShell.Res(true, "已隐藏 3 年：" + item.label());
    }

    public static PrismShell.Res restore(Context c, Record r) {
        if (r == null || TextUtils.isEmpty(r.key)) return new PrismShell.Res(false, "记录无效");
        if (!PrismNotificationService.connected()) {
            return new PrismShell.Res(false, "通知服务未连接，稍后再试");
        }
        if (!PrismNotificationService.snooze(r.key, SHOW_MS)) {
            return new PrismShell.Res(false, "恢复失败（原通知可能已被系统清掉）");
        }
        forget(c, r.key);
        return new PrismShell.Res(true, "已恢复显示：" + r.label);
    }

    public static List<Record> hidden(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(K_HIDDEN, Collections.<String>emptySet());
        List<Record> out = new ArrayList<Record>();
        for (String s : raw) {
            int i = s.indexOf(SEP);
            if (i <= 0) continue;
            out.add(new Record(s.substring(0, i), s.substring(i + 1)));
        }
        return out;
    }

    public static int hiddenCount(Context c) {
        return hidden(c).size();
    }

    private static void remember(Context c, String key, String label) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<String>(sp.getStringSet(K_HIDDEN, Collections.<String>emptySet()));
        String safe = label == null ? key : label.replace(SEP, " ");
        set.add(key + SEP + safe);
        sp.edit().putStringSet(K_HIDDEN, set).apply();
    }

    private static void forget(Context c, String key) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<String>(sp.getStringSet(K_HIDDEN, Collections.<String>emptySet()));
        Set<String> keep = new HashSet<String>();
        for (String s : set) {
            if (!s.startsWith(key + SEP)) keep.add(s);
        }
        sp.edit().putStringSet(K_HIDDEN, keep).apply();
    }
}
