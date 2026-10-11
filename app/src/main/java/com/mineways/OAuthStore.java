package com.mineways;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 登录态本地档案：access_token + 用户资料（用户名 / 头像 / 注册时间）。
 * 退出登录即清空；token 丢了重新授权一次即可（论坛侧重生成授权码）。
 */
public final class OAuthStore {

    private static final String PREFS = "aff_oauth";
    private static final String K_TOKEN = "access_token";
    private static final String K_UID = "user_id";
    private static final String K_NAME = "username";
    private static final String K_AVATAR = "avatar";
    private static final String K_JOINED = "joined_at";

    private OAuthStore() {
    }

    public static final class Account {
        public String token = "";
        public long id;
        public String username = "";
        public String avatar = "";
        public String joinedAt = "";

        public boolean loggedIn() {
            return token != null && token.length() > 0;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context c, String token, OAuthClient.User u) {
        SharedPreferences.Editor e = sp(c).edit()
                .putString(K_TOKEN, token == null ? "" : token);
        if (u != null) {
            e.putLong(K_UID, u.id)
                    .putString(K_NAME, u.username)
                    .putString(K_AVATAR, u.avatar)
                    .putString(K_JOINED, u.joinedAt);
        }
        e.apply();
    }

    public static Account load(Context c) {
        Account a = new Account();
        SharedPreferences p = sp(c);
        a.token = p.getString(K_TOKEN, "");
        a.id = p.getLong(K_UID, 0L);
        a.username = p.getString(K_NAME, "");
        a.avatar = p.getString(K_AVATAR, "");
        a.joinedAt = p.getString(K_JOINED, "");
        return a;
    }

    /** 只更新资料（token 不变）。 */
    public static void updateProfile(Context c, OAuthClient.User u) {
        if (u == null) {
            return;
        }
        sp(c).edit()
                .putLong(K_UID, u.id)
                .putString(K_NAME, u.username)
                .putString(K_AVATAR, u.avatar)
                .putString(K_JOINED, u.joinedAt)
                .apply();
    }

    public static void logout(Context c) {
        sp(c).edit().clear().apply();
    }
}
