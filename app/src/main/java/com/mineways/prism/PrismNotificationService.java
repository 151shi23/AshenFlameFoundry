package com.mineways.prism;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 棱镜玩机 · 通知隐藏（snooze）用的监听服务。
 *
 * <p>与三大工具箱同款方案：不 cancel，而是 {@code snoozeNotification(key, 3 年)}。
 * 被 snooze 的通知仍归系统队列管理，{@code snoozeNotification(key, 100ms)} 就能秒弹回；
 * cancel 之后原始 key 永久丢失，无法恢复。
 *
 * <p>用户必须在系统「通知使用权」里勾选本应用，服务才会被绑定；没授权时页面会引导。
 */
public final class PrismNotificationService extends NotificationListenerService {

    private static volatile PrismNotificationService instance;

    public static final class Item {
        public final String key;
        public final String pkg;
        public final String app;
        public final String title;
        public final String text;
        public final long time;

        Item(String key, String pkg, String app, String title, String text, long time) {
            this.key = key;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.time = time;
        }

        public String label() {
            String t = TextUtils.isEmpty(title) ? app : title;
            return app + " · " + t;
        }
    }

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean connected() {
        return instance != null;
    }

    /** 当前活动通知（上限 100 条，按时间倒序）；服务未连接返回空表。 */
    public static List<Item> snapshot() {
        PrismNotificationService svc = instance;
        List<Item> out = new ArrayList<Item>();
        if (svc == null) return out;
        StatusBarNotification[] all;
        try {
            all = svc.getActiveNotifications();
        } catch (Throwable t) {
            return out;
        }
        if (all == null) return out;
        for (StatusBarNotification sbn : all) {
            if (sbn == null || sbn.getNotification() == null) continue;
            Notification n = sbn.getNotification();
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            out.add(new Item(
                    sbn.getKey(),
                    sbn.getPackageName(),
                    appName(svc, sbn.getPackageName()),
                    title == null ? "" : title.toString(),
                    text == null ? "" : text.toString(),
                    sbn.getPostTime()));
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out.size() > 100 ? new ArrayList<Item>(out.subList(0, 100)) : out;
    }

    /** 贪睡：3 年 = 事实隐藏，100ms = 立刻弹回。 */
    public static boolean snooze(String key, long ms) {
        PrismNotificationService svc = instance;
        if (svc == null || TextUtils.isEmpty(key)) return false;
        try {
            svc.snoozeNotification(key, ms);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String appName(android.content.Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
