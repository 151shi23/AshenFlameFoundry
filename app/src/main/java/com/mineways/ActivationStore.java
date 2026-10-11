package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 激活码本地档案（「机会」存在本地，直到使用为止）。
 *
 * <p>架构：用户输入码 → {@code check}（只读）→ 把**码本身**存本地（长期有效，不占服务端锁）；
 * 点「开始使用」时才 {@code verify}（锁定）→ 执行 → 成功 {@code confirm}（扣）/
 * 失败 {@code release}（不扣）。token 只在"使用中"的那几秒内存里，绝不长期保存。
 *
 * <p>另存一条**待补发**记录：confirm/release 是网络调用，用户秒退可能掐断，
 * 下次启动由 {@link #flushPending(Context)} 补发（服务端幂等，重复调用报错直接忽略）。
 */
public final class ActivationStore {

    private static final String PREFS = "aff_activation";
    private static final String K_CODE = "code";
    private static final String K_FEATURE = "feature";
    private static final String K_SAVED_AT = "saved_at";
    private static final String K_PENDING = "pending_action";
    private static final String K_PENDING_TOKEN = "pending_token";
    private static final String K_PENDING_FEATURE = "pending_feature";

    private ActivationStore() {
    }

    /** 本地一条激活记录（一张码 = 一次机会）。 */
    public static final class Record {
        public String code = "";
        public String feature = "";
        public long savedAt;

        public boolean usable() {
            return code != null && code.length() > 0;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 存下用户刚校验通过的码（长期有效，直到使用为止）。 */
    public static void save(Context c, String feature, String code) {
        sp(c).edit()
                .putString(K_CODE, code == null ? "" : code.trim())
                .putString(K_FEATURE, feature == null ? "" : feature)
                .putLong(K_SAVED_AT, System.currentTimeMillis())
                .apply();
    }

    /** 读取当前功能可用的记录（功能不匹配视为没有）。 */
    public static Record load(Context c, String feature) {
        Record r = new Record();
        SharedPreferences p = sp(c);
        r.code = p.getString(K_CODE, "");
        r.feature = p.getString(K_FEATURE, "");
        r.savedAt = p.getLong(K_SAVED_AT, 0L);
        if (r.code.length() > 0 && feature != null && feature.length() > 0
                && r.feature.length() > 0 && !feature.equals(r.feature)) {
            r.code = "";   // 串用：这张码不属于这个功能
        }
        return r;
    }

    /** 用完（或码失效）就清掉。 */
    public static void clear(Context c, String feature) {
        Record r = load(c, feature);
        if (r.usable()) {
            sp(c).edit().remove(K_CODE).remove(K_FEATURE).remove(K_SAVED_AT).apply();
        }
    }

    // ---------------------------------------------------------------- 待补发

    public static void setPending(Context c, String action, String feature, String token) {
        sp(c).edit()
                .putString(K_PENDING, action)
                .putString(K_PENDING_FEATURE, feature)
                .putString(K_PENDING_TOKEN, token)
                .apply();
    }

    public static void clearPending(Context c) {
        sp(c).edit().remove(K_PENDING).remove(K_PENDING_TOKEN).remove(K_PENDING_FEATURE).apply();
    }

    public static String pendingAction(Context c) {
        return sp(c).getString(K_PENDING, "");
    }

    public static String pendingToken(Context c) {
        return sp(c).getString(K_PENDING_TOKEN, "");
    }

    public static String pendingFeature(Context c) {
        return sp(c).getString(K_PENDING_FEATURE, "");
    }

    /**
     * 补发上次没发完的 confirm / release（幂等：服务端已处理过会报错，直接忽略）。
     * 在 App 启动、进工具箱时调用即可，不阻塞 UI。
     */
    public static void flushPending(final Context c) {
        final String action = pendingAction(c);
        final String token = pendingToken(c);
        final String feature = pendingFeature(c);
        if (action.length() == 0 || token.length() == 0) {
            clearPending(c);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                ActivationClient.Result r = "confirm".equals(action)
                        ? ActivationClient.confirm(token)
                        : ActivationClient.release(token, "resend from previous session");
                // 无论成功还是"已处理"，本地这条待办都结束
                clearPending(c);
                if (r.ok || !r.networkError) {
                    return;
                }
                // 纯网络失败：恢复待办，下次再补
                setPending(c, action, feature, token);
            }
        }, "activation-flush").start();
    }
}
